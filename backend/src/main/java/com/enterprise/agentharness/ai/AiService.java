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
 * validates its content before storing or using it.
 */
@Service
public class AiService {
    /** Common credential shapes: bearer tokens, key=value secrets, provider keys, private keys, JWTs, basic-auth URLs. */
    static final Pattern SECRETS = Pattern.compile(
            "(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{12,}"
            + "|((?:password|passwd|secret|token|api[_-]?key|authorization)\\s*[:=]\\s*)(?:bearer\\s+|basic\\s+)?[^\\s,;\"']{4,}"
            + "|(sk-ant-[A-Za-z0-9_-]{8,}|sk-[A-Za-z0-9]{16,}|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|eahp_[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16})"
            + "|(-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----)"
            + "|(eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,})"
            + "|(://[^/\\s:@]+:)[^@\\s/]+@");
    private final LlmProvider provider;
    private final JdbcTemplate jdbc;
    private final long dailyBudget;
    private final ObjectMapper strict = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public AiService(LlmProvider provider, JdbcTemplate jdbc, @Value("${eahp.ai.daily-token-budget:500000}") long dailyBudget) {
        this.provider = provider; this.jdbc = jdbc; this.dailyBudget = dailyBudget;
    }

    public record Status(String provider, String model, long dailyTokenBudget, long usedToday) {}
    public record Answer(JsonNode json, String provider, String model, long inputTokens, long outputTokens) {}

    public Status status() { return new Status(provider.id(), provider.model(), dailyBudget, usedToday()); }

    /** Runs one completion for {@code feature}; failures become RunExceptions with a bounded code. */
    public Answer complete(String feature, LlmProvider.Request request) {
        if (usedToday() >= dailyBudget) throw new RunException(HttpStatus.TOO_MANY_REQUESTS, "AI_BUDGET_EXHAUSTED: the daily AI token budget is used up");
        var safe = new LlmProvider.Request(redact(request.system()), redact(request.user()), request.schema(), request.maxOutputTokens());
        LlmProvider.Completion completion;
        try {
            completion = provider.complete(safe);
        } catch (LlmProvider.LlmException ex) {
            record(feature, 0, 0, ex.code());
            throw new RunException(ex.code().equals("AI_RATE_LIMITED") ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY, ex.code() + ": " + ex.getMessage());
        }
        record(feature, completion.inputTokens(), completion.outputTokens(), null);
        JsonNode json;
        try {
            json = strict.readTree(completion.text().strip());
        } catch (Exception ex) {
            throw new RunException(HttpStatus.BAD_GATEWAY, "AI_INVALID_ANSWER: the model did not return valid JSON");
        }
        if (json == null || !json.isObject()) throw new RunException(HttpStatus.BAD_GATEWAY, "AI_INVALID_ANSWER: the model did not return a JSON object");
        return new Answer(json, provider.id(), provider.model(), completion.inputTokens(), completion.outputTokens());
    }

    static String redact(String text) {
        if (text == null) return "";
        var m = SECRETS.matcher(text);
        var out = new StringBuilder();
        while (m.find()) {
            String keep = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(6) != null ? m.group(6) : "";
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(keep + "[REDACTED]" + (m.group(6) != null ? "@" : "")));
        }
        m.appendTail(out);
        return out.toString();
    }

    private long usedToday() {
        Long used = jdbc.queryForObject("SELECT COALESCE(sum(input_tokens+output_tokens),0) FROM ai_usage WHERE created_at >= date_trunc('day', clock_timestamp() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'", Long.class);
        return used == null ? 0 : used;
    }

    private void record(String feature, long in, long out, String error) {
        jdbc.update("INSERT INTO ai_usage(feature,provider,model,input_tokens,output_tokens,actor,error) VALUES (?,?,?,?,?,?,?)",
                feature, provider.id(), provider.model(), in, out, AuditService.actor(), error);
    }
}
