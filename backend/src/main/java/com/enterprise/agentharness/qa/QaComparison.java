package com.enterprise.agentharness.qa;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Test-level difference between two commits for a suite, from each commit's latest ingested run: tests that fail at
 * head but passed at base (new failures), that failed at base and pass at head (fixed), that still fail, and tests
 * added or removed. Removed tests are only reported when both runs had the same scope, since a selective run (P19)
 * naturally runs fewer tests.
 */
@Service
public class QaComparison {
    static final int MAX_LISTED = 50;
    private final JdbcTemplate jdbc;
    public QaComparison(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Side(UUID runId, String outcome, int tests, String scope) {}
    public record Diff(String suite, String status, Side base, Side head, boolean sameScope, List<String> newFailures, List<String> fixed,
            List<String> stillFailing, List<String> added, List<String> removed, int newFailureCount, int fixedCount, int stillFailingCount,
            int addedCount, int removedCount) {}

    /** One diff per suite that has a result at {@code head} (or just {@code suite} when given). */
    public List<Diff> compare(String project, String base, String head, String suite) {
        var suites = suite != null ? List.of(suite) : jdbc.queryForList(
                "SELECT DISTINCT suite_key FROM qa_run_ingestions WHERE project_key=? AND commit_sha=? ORDER BY suite_key", String.class, project, head);
        List<Diff> result = new ArrayList<>();
        for (String s : suites) result.add(diff(project, s, base, head));
        return result;
    }

    private Diff diff(String project, String suite, String base, String head) {
        var b = latest(project, suite, base);
        var h = latest(project, suite, head);
        if (b == null || h == null) {
            return new Diff(suite, h == null ? "NO_HEAD_RESULT" : "NO_BASE_RESULT", b == null ? null : b.side, h == null ? null : h.side,
                    false, List.of(), List.of(), List.of(), List.of(), List.of(), 0, 0, 0, 0, 0);
        }
        boolean sameScope = Objects.equals(b.side.scope(), h.side.scope());
        List<String> newFailures = new ArrayList<>(), fixed = new ArrayList<>(), still = new ArrayList<>(), added = new ArrayList<>(), removed = new ArrayList<>();
        for (var e : h.statuses.entrySet()) {
            String before = b.statuses.get(e.getKey());
            boolean failsNow = failing(e.getValue());
            if (before == null) added.add(e.getKey());
            else if (failsNow && failing(before)) still.add(e.getKey());
            else if (failsNow && before.equals("PASSED")) newFailures.add(e.getKey());
            else if (!failsNow && e.getValue().equals("PASSED") && failing(before)) fixed.add(e.getKey());
            // Newly added tests that fail are new failures too: nothing proves they ever passed.
            if (before == null && failsNow) newFailures.add(e.getKey());
        }
        if (sameScope) for (String t : b.statuses.keySet()) if (!h.statuses.containsKey(t)) removed.add(t);
        return new Diff(suite, "COMPARED", b.side, h.side, sameScope, limit(newFailures), limit(fixed), limit(still), limit(added), limit(removed),
                newFailures.size(), fixed.size(), still.size(), added.size(), removed.size());
    }

    private record Latest(Side side, Map<String, String> statuses) {}

    private Latest latest(String project, String suite, String commit) {
        var runs = jdbc.queryForList("""
                SELECT i.run_id, i.outcome, i.test_count, r.arguments::jsonb->'modules' AS modules FROM qa_run_ingestions i JOIN agent_runs r ON r.id = i.run_id
                WHERE i.project_key=? AND i.suite_key=? AND i.commit_sha=? ORDER BY r.created_at DESC LIMIT 1
                """, project, suite, commit);
        if (runs.isEmpty()) return null;
        var run = runs.get(0);
        UUID runId = (UUID) run.get("run_id");
        Map<String, String> statuses = new TreeMap<>();
        for (var t : jdbc.queryForList("SELECT test_class, test_name, status FROM qa_test_results WHERE run_id=?", runId)) {
            String name = t.get("test_class") + (((String) t.get("test_name")).isEmpty() ? "" : "#" + t.get("test_name"));
            // A test reported twice (for example a retried method) counts as failing if any report failed.
            statuses.merge(name, (String) t.get("status"), (x, y) -> failing(x) ? x : y);
        }
        String scope = run.get("modules") == null ? "FULL" : run.get("modules").toString();
        return new Latest(new Side(runId, (String) run.get("outcome"), ((Number) run.get("test_count")).intValue(), scope), statuses);
    }

    private static boolean failing(String status) { return status.equals("FAILED") || status.equals("ERROR"); }
    private static List<String> limit(List<String> values) { return values.size() <= MAX_LISTED ? List.copyOf(values) : List.copyOf(values.subList(0, MAX_LISTED)); }

}
