package com.enterprise.agentharness.qa;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers "why did this run fail, and is it new?" from ingested test rows: a category and explanation per failure,
 * and each failing test's history in the same project and suite (first seen, new failure, persistent or flaky).
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class QaTriageService {
    private final JdbcTemplate jdbc;
    public QaTriageService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record History(String label, String previousStatus, String previousCommit, String lastPassedCommit, int recentRuns, int recentFailures) {}
    public record FailedTest(String testClass, String testName, String status, String failureType, String message, String category,
            String explanation, History history) {}
    public record Triage(UUID runId, boolean ingested, String outcome, String category, String explanation, int tests, int failed,
            List<FailedTest> failures, String error) {}
    public record TestResult(UUID runId, String commit, String status, Integer durationMs, String message, String category, Instant runCreatedAt) {}

    public Triage triage(UUID runId) {
        var rows = jdbc.queryForList("SELECT * FROM qa_run_ingestions WHERE run_id=?", runId);
        if (rows.isEmpty()) return new Triage(runId, false, null, null, null, 0, 0, List.of(), null);
        var ingestion = rows.get(0);
        String category = (String) ingestion.get("category");
        List<FailedTest> failures = new ArrayList<>();
        for (var f : jdbc.queryForList("""
                SELECT * FROM qa_test_results WHERE run_id=? AND status IN ('FAILED','ERROR') ORDER BY test_class, test_name LIMIT 100
                """, runId)) {
            failures.add(new FailedTest((String) f.get("test_class"), (String) f.get("test_name"), (String) f.get("status"),
                    (String) f.get("failure_type"), (String) f.get("message"), (String) f.get("category"),
                    FailureTriage.EXPLANATIONS.get((String) f.get("category")), history(f)));
        }
        return new Triage(runId, true, (String) ingestion.get("outcome"), category, category == null ? null : FailureTriage.EXPLANATIONS.get(category),
                ((Number) ingestion.get("test_count")).intValue(), ((Number) ingestion.get("failed_count")).intValue(), failures, (String) ingestion.get("error"));
    }

    public List<TestResult> history(String project, String suite, String testClass, String testName, int limit) {
        return jdbc.query("""
                SELECT * FROM qa_test_results WHERE project_key=? AND suite_key=? AND test_class=? AND test_name=?
                ORDER BY run_created_at DESC, id DESC LIMIT ?
                """, this::result, project, suite, testClass, testName, limit);
    }

    /**
     * FLAKY: the same commit both passed and failed. FIRST_SEEN: no earlier result. NEW_FAILURE: the previous result
     * passed. PERSISTENT: it already failed before. Looks at the last 20 results of the test in this suite up to this
     * run, so a run's history never depends on runs that came after it.
     */
    private History history(Map<String, Object> failure) {
        var recent = jdbc.queryForList("""
                SELECT run_id, commit_sha, status, run_created_at FROM qa_test_results
                WHERE project_key=? AND suite_key=? AND test_class=? AND test_name=? AND status <> 'SKIPPED' AND run_created_at <= ?
                ORDER BY run_created_at DESC, id DESC LIMIT 20
                """, failure.get("project_key"), failure.get("suite_key"), failure.get("test_class"), failure.get("test_name"), failure.get("run_created_at"));
        Object runId = failure.get("run_id"), commit = failure.get("commit_sha");
        var created = (java.sql.Timestamp) failure.get("run_created_at");
        boolean sameCommitPassed = recent.stream().anyMatch(r -> commit.equals(r.get("commit_sha")) && "PASSED".equals(r.get("status")));
        var earlier = recent.stream().filter(r -> !runId.equals(r.get("run_id")) && ((java.sql.Timestamp) r.get("run_created_at")).before(created)).toList();
        var previous = earlier.isEmpty() ? null : earlier.get(0);
        String lastPassed = earlier.stream().filter(r -> "PASSED".equals(r.get("status"))).map(r -> (String) r.get("commit_sha")).findFirst().orElse(null);
        String label = sameCommitPassed ? "FLAKY" : previous == null ? "FIRST_SEEN" : "PASSED".equals(previous.get("status")) ? "NEW_FAILURE" : "PERSISTENT";
        int failures = (int) recent.stream().filter(r -> !"PASSED".equals(r.get("status"))).count();
        return new History(label, previous == null ? null : (String) previous.get("status"), previous == null ? null : (String) previous.get("commit_sha"),
                lastPassed, recent.size(), failures);
    }

    private TestResult result(ResultSet r, int i) throws SQLException {
        return new TestResult(r.getObject("run_id", UUID.class), r.getString("commit_sha"), r.getString("status"), (Integer) r.getObject("duration_ms"),
                r.getString("message"), r.getString("category"), r.getTimestamp("run_created_at").toInstant());
    }
}
