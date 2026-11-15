package com.enterprise.agentharness.ai;

import com.enterprise.agentharness.governance.AuditService;
import com.enterprise.agentharness.run.RunException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The one gate every AI feature calls through: enforces the daily token budget, redacts secrets from prompts, records
 * usage per feature, and parses the answer strictly as a JSON object. The answer is untrusted data; each feature
 * validates its content before storing or using it. Every call is also an entry of the AI call log ({@link AiCallLog}):
 * what it was for ({@link AiCallSource}) and, unless {@code eahp.ai.log.store-prompts} is false, the redacted prompt and
 * answer, so administrators can see exactly what EAHP sent to the model and what came back.
 */
@Service
public class AiService {
    static final int MAX_STORED_CHARS = 200_000;
    /** Common credential shapes: bearer tokens, key=value secrets, provider keys, private keys, JWTs, basic-auth URLs. */
    static final Pattern SECRETS = Pattern.compile(
            "(?i)(\"(?:password|passwd|secret|token|access_token|refresh_token|api[_-]?key|apikey|authorization|client_secret)\"\\s*:\\s*\")[^\"]*(?=\")"
            + "|(bearer\\s+)[A-Za-z0-9._~+/=-]{12,}"
            + "|((?:password|passwd|secret|token|api[_-]?key|authorization)\\s*[:=]\\s*)(?:bearer\\s+|basic\\s+)?[^\\s,;\"']{4,}"
            + "|(sk-ant-[A-Za-z0-9_-]{8,}|sk-[A-Za-z0-9]{16,}|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|eahp_[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16})"
            + "|(-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----)"
            + "|(eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,})"
            + "|(://[^/\\s:@]+:)[^@\\s/]+@");
    private final LlmProvider provider;
    private final JdbcTemplate jdbc;
    private final long dailyBudget;
    private final boolean storePrompts;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final ObjectMapper strict = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public AiService(LlmProvider provider, JdbcTemplate jdbc, org.springframework.transaction.support.TransactionTemplate tx,
            @Value("${eahp.ai.daily-token-budget:500000}") long dailyBudget, @Value("${eahp.ai.log.store-prompts:true}") boolean storePrompts) {
        this.provider = provider; this.jdbc = jdbc; this.tx = tx; this.dailyBudget = dailyBudget; this.storePrompts = storePrompts;
    }

    public record Status(String provider, String model, long dailyTokenBudget, long usedToday) {}
    /** {@code callId} is the call's entry in the AI call log. */
    public record Answer(JsonNode json, String provider, String model, long inputTokens, long outputTokens, long callId) {}

    public Status status() { return new Status(provider.id(), provider.model(), dailyBudget, usedToday()); }

    /**
     * Runs one completion for {@code feature}; failures become RunExceptions with a bounded code. The worst case (an
     * input estimate plus the full output allowance) is reserved under a lock before the call, so concurrent calls can
     * never overspend the daily budget; the reservation is replaced by the actual usage afterwards, including for
     * refused or truncated answers the provider still charged for.
     */
    public Answer complete(String feature, LlmProvider.Request request) {
        var safe = new LlmProvider.Request(redact(request.system()), redact(request.user()), request.schema(), request.maxOutputTokens());
        long reserve = (safe.system().length() + safe.user().length() + request.schema().toString().length()) / 3 + request.maxOutputTokens();
        var source = AiCallSource.current();
        String sourceType = source == null ? null : source.type(), sourceId = source == null ? null : source.id();
        Long reservation = tx.execute(status -> {
            jdbc.queryForList("SELECT pg_advisory_xact_lock(724001)");
            if (usedToday() + reserve > dailyBudget) return null;
            return jdbc.queryForObject("""
                    INSERT INTO ai_usage(feature,provider,model,input_tokens,output_tokens,actor,error,source_type,source_id,max_output_tokens,system_prompt,user_prompt)
                    VALUES (?,?,?,?,0,?,'RESERVED',?,?,?,?,?) RETURNING id
                    """, Long.class, feature, provider.id(), provider.model(), reserve, AuditService.actor(), sourceType, sourceId, request.maxOutputTokens(),
                    storePrompts ? bounded(safe.system()) : null, storePrompts ? bounded(safe.user()) : null);
        });
        if (reservation == null) {
            // A refused call stays in the log too: it shows who asked when the budget ran out.
            jdbc.update("""
                    INSERT INTO ai_usage(feature,provider,model,input_tokens,output_tokens,actor,error,source_type,source_id,max_output_tokens,duration_ms)
                    VALUES (?,?,?,0,0,?,'AI_BUDGET_EXHAUSTED',?,?,?,0)
                    """, feature, provider.id(), provider.model(), AuditService.actor(), sourceType, sourceId, request.maxOutputTokens());
            throw new RunException(HttpStatus.TOO_MANY_REQUESTS, "AI_BUDGET_EXHAUSTED: the daily AI token budget does not cover this request");
        }
        long started = System.nanoTime();
        LlmProvider.Completion completion;
        try {
            completion = provider.complete(safe);
        } catch (LlmProvider.LlmException ex) {
            settle(reservation, ex.inputTokens(), ex.outputTokens(), ex.code(), null, started);
            throw new RunException(ex.code().equals("AI_RATE_LIMITED") ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY, ex.code() + ": " + ex.getMessage());
        } catch (RuntimeException ex) {
            settle(reservation, 0, 0, "AI_UNAVAILABLE", null, started);
            throw new RunException(HttpStatus.BAD_GATEWAY, "AI_UNAVAILABLE: the model provider failed");
        }
        JsonNode json;
        try {
            json = strict.readTree(completion.text().strip());
        } catch (Exception ex) {
            json = null;
        }
        settle(reservation, completion.inputTokens(), completion.outputTokens(), json != null && json.isObject() ? null : "AI_INVALID_ANSWER", completion.text(), started);
        if (json == null) throw new RunException(HttpStatus.BAD_GATEWAY, "AI_INVALID_ANSWER: the model did not return valid JSON");
        if (!json.isObject()) throw new RunException(HttpStatus.BAD_GATEWAY, "AI_INVALID_ANSWER: the model did not return a JSON object");
        return new Answer(json, provider.id(), provider.model(), completion.inputTokens(), completion.outputTokens(), reservation);
    }

    /** Points a finished call at what it produced, when that only exists afterwards (a test-generation draft). */
    public void relink(long callId, String sourceType, String sourceId) {
        jdbc.update("UPDATE ai_usage SET source_type=?, source_id=? WHERE id=?", sourceType, sourceId, callId);
    }

    private void settle(long reservation, long in, long out, String error, String answer, long startedNanos) {
        jdbc.update("UPDATE ai_usage SET input_tokens=?, output_tokens=?, error=?, answer_text=?, duration_ms=? WHERE id=?", in, out, error,
                storePrompts && answer != null ? bounded(redact(answer)) : null, (System.nanoTime() - startedNanos) / 1_000_000, reservation);
    }

    /** Stored prompts and answers are capped: an OpenAPI document can be large, and the log must stay bounded. */
    static String bounded(String text) {
        if (text == null || text.length() <= MAX_STORED_CHARS) return text;
        return text.substring(0, MAX_STORED_CHARS) + "\n…[" + (text.length() - MAX_STORED_CHARS) + " more characters not stored]";
    }

    static String redact(String text) {
        if (text == null) return "";
        var m = SECRETS.matcher(text);
        var out = new StringBuilder();
        while (m.find()) {
            String keep = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(7) != null ? m.group(7) : "";
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(keep + "[REDACTED]" + (m.group(7) != null ? "@" : "")));
        }
        m.appendTail(out);
        return out.toString();
    }

    private long usedToday() {
        Long used = jdbc.queryForObject("SELECT COALESCE(sum(input_tokens+output_tokens),0) FROM ai_usage WHERE created_at >= date_trunc('day', clock_timestamp() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'", Long.class);
        return used == null ? 0 : used;
    }

}
