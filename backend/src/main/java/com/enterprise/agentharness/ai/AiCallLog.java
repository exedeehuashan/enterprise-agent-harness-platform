package com.enterprise.agentharness.ai;

import com.enterprise.agentharness.run.RunException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Reads the AI call log that {@link AiService} writes: one entry per call with its feature, source, model, tokens,
 * duration and outcome, plus the redacted prompt and answer while they are kept. Prompts can hold build output and
 * API documents, so only approvers read the log.
 */
@Service
public class AiCallLog {
    static final Set<String> STATUSES = Set.of("ok", "failed", "running");
    private final JdbcTemplate jdbc;

    public AiCallLog(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Call(long id, Instant createdAt, String feature, String provider, String model, long inputTokens, long outputTokens,
            Integer maxOutputTokens, Long durationMs, String actor, String status, String error, String sourceType, String sourceId) {}
    public record Detail(Call call, String systemPrompt, String userPrompt, String answer, boolean contentStored, Instant contentExpiredAt) {}
    public record Page(List<Call> items, int page, int size, long totalElements, long totalPages) {}
    public record Query(String feature, String status, String sourceType, String sourceId, int page, int size) {}

    @PreAuthorize("hasRole('APPROVER')")
    public Page list(Query q) {
        if (q.status() != null && !STATUSES.contains(q.status())) throw new RunException(HttpStatus.BAD_REQUEST, "status must be ok, failed or running");
        if (q.page() < 0 || q.size() < 1 || q.size() > 100) throw new RunException(HttpStatus.BAD_REQUEST, "page must be 0 or more and size 1..100");
        var where = new StringBuilder(" WHERE true");
        var args = new ArrayList<Object>();
        if (q.feature() != null) { where.append(" AND feature=?"); args.add(q.feature()); }
        if (q.sourceType() != null) { where.append(" AND source_type=?"); args.add(q.sourceType()); }
        if (q.sourceId() != null) { where.append(" AND source_id=?"); args.add(q.sourceId()); }
        if ("ok".equals(q.status())) where.append(" AND error IS NULL");
        if ("failed".equals(q.status())) where.append(" AND error IS NOT NULL AND error<>'RESERVED'");
        if ("running".equals(q.status())) where.append(" AND error='RESERVED'");
        long total = jdbc.queryForObject("SELECT count(*) FROM ai_usage" + where, Long.class, args.toArray());
        var pageArgs = new ArrayList<Object>(args);
        pageArgs.add(q.size());
        pageArgs.add((long) q.page() * q.size());
        var items = jdbc.query("SELECT * FROM ai_usage" + where + " ORDER BY id DESC LIMIT ? OFFSET ?", this::call, pageArgs.toArray());
        return new Page(items, q.page(), q.size(), total, (total + q.size() - 1) / q.size());
    }

    @PreAuthorize("hasRole('APPROVER')")
    public Detail get(long id) {
        var rows = jdbc.query("SELECT * FROM ai_usage WHERE id=?", (r, i) -> {
            var expired = r.getTimestamp("content_expired_at");
            boolean stored = r.getString("system_prompt") != null;
            return new Detail(call(r, i), r.getString("system_prompt"), r.getString("user_prompt"), r.getString("answer_text"), stored,
                    expired == null ? null : expired.toInstant());
        }, id);
        if (rows.isEmpty()) throw new RunException(HttpStatus.NOT_FOUND, "AI call not found: " + id);
        return rows.get(0);
    }

    /** The features that have called the model, for the list filter. */
    @PreAuthorize("hasRole('APPROVER')")
    public List<String> features() { return jdbc.queryForList("SELECT DISTINCT feature FROM ai_usage ORDER BY feature", String.class); }

    private Call call(ResultSet r, int i) throws SQLException {
        String error = r.getString("error");
        String status = error == null ? "ok" : error.equals("RESERVED") ? "running" : "failed";
        return new Call(r.getLong("id"), r.getTimestamp("created_at").toInstant(), r.getString("feature"), r.getString("provider"), r.getString("model"),
                r.getLong("input_tokens"), r.getLong("output_tokens"), r.getObject("max_output_tokens", Integer.class), r.getObject("duration_ms", Long.class),
                r.getString("actor"), status, status.equals("failed") ? error : null, r.getString("source_type"), r.getString("source_id"));
    }
}
