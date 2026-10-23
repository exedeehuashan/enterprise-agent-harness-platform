package com.enterprise.agentharness.ops;

import com.enterprise.agentharness.governance.AuditService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps storage bounded without losing the evidence trail. Artifact content older than the retention window is
 * removed but its metadata and SHA-256 stay, so a run still shows what it produced; the artifacts of the latest QA
 * run for each project, suite and ref are always kept. Per-test history older than its window is deleted.
 */
@Service
public class RetentionService {
    static final List<String> TABLES = List.of("run_artifacts", "qa_test_results", "agent_runs", "run_steps", "audit_events", "qa_run_ingestions",
            "qa_pr_runs", "qa_change_impacts", "model_calls", "agent_tasks");
    private final JdbcTemplate jdbc;
    private final int artifactDays;
    private final int testResultDays;

    public RetentionService(JdbcTemplate jdbc, @Value("${eahp.retention.artifact-days:30}") int artifactDays,
            @Value("${eahp.retention.test-result-days:180}") int testResultDays) {
        if (artifactDays < 1 || testResultDays < 1) throw new IllegalStateException("Retention windows must be at least one day");
        this.jdbc = jdbc; this.artifactDays = artifactDays; this.testResultDays = testResultDays;
    }

    public record Result(long id, String actor, Instant startedAt, int artifactDays, int testResultDays, int artifactsExpired, long bytesFreed, int testResultsDeleted) {}
    public record Report(Map<String, Long> tableBytes, long liveArtifacts, long liveArtifactBytes, long expiredArtifacts, int artifactDays,
            int testResultDays, Result lastRun) {}

    /** Called by the nightly job (actor "retention") and by approvers on demand. */
    @Transactional
    public Result run(String actor) {
        var expired = jdbc.queryForList("""
                WITH keep AS (
                  SELECT DISTINCT ON (arguments::jsonb->>'project', arguments::jsonb->>'suite', arguments::jsonb->>'ref') id
                  FROM agent_runs WHERE tool IN ('QA_RUN_SUITE','QA_RUN_PRIVILEGED_SUITE') AND arguments IS NOT NULL
                  ORDER BY arguments::jsonb->>'project', arguments::jsonb->>'suite', arguments::jsonb->>'ref', created_at DESC, id DESC)
                UPDATE run_artifacts SET content = ''::bytea, expired_at = clock_timestamp()
                WHERE expired_at IS NULL AND created_at < clock_timestamp() - (? * interval '1 day') AND run_id NOT IN (SELECT id FROM keep)
                RETURNING size_bytes
                """, Integer.class, artifactDays);
        long bytes = expired.stream().mapToLong(Integer::longValue).sum();
        int deleted = jdbc.update("DELETE FROM qa_test_results WHERE run_created_at < clock_timestamp() - (? * interval '1 day')", testResultDays);
        Long id = jdbc.queryForObject("""
                INSERT INTO retention_runs(actor, artifact_days, test_result_days, artifacts_expired, bytes_freed, test_results_deleted)
                VALUES (?,?,?,?,?,?) RETURNING id
                """, Long.class, actor, artifactDays, testResultDays, expired.size(), bytes, deleted);
        return last(id);
    }

    @PreAuthorize("hasRole('APPROVER')")
    public Result runNow() { return run(AuditService.actor()); }

    @PreAuthorize("hasRole('APPROVER')")
    @Transactional(readOnly = true)
    public Report report() {
        Map<String, Long> sizes = new LinkedHashMap<>();
        for (String t : TABLES) sizes.put(t, jdbc.queryForObject("SELECT pg_total_relation_size(?::regclass)", Long.class, t));
        var artifacts = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE expired_at IS NULL) AS live, COALESCE(sum(size_bytes) FILTER (WHERE expired_at IS NULL), 0) AS live_bytes,
                       count(*) FILTER (WHERE expired_at IS NOT NULL) AS expired FROM run_artifacts
                """);
        var lastId = jdbc.queryForList("SELECT id FROM retention_runs ORDER BY id DESC LIMIT 1", Long.class);
        return new Report(sizes, ((Number) artifacts.get("live")).longValue(), ((Number) artifacts.get("live_bytes")).longValue(),
                ((Number) artifacts.get("expired")).longValue(), artifactDays, testResultDays, lastId.isEmpty() ? null : last(lastId.get(0)));
    }

    private Result last(long id) {
        return jdbc.queryForObject("SELECT * FROM retention_runs WHERE id=?", (r, i) -> new Result(r.getLong("id"), r.getString("actor"),
                r.getTimestamp("started_at").toInstant(), r.getInt("artifact_days"), r.getInt("test_result_days"), r.getInt("artifacts_expired"),
                r.getLong("bytes_freed"), r.getInt("test_results_deleted")), id);
    }
}
