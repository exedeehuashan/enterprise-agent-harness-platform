package com.enterprise.agentharness.qa;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Release gate and quality dashboard, computed only from recorded evidence (ingested runs and test results). A gate
 * verdict never runs anything: missing evidence makes it PENDING, never PASS.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class QualityService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final QaTriageService triage;
    public QualityService(JdbcTemplate jdbc, ObjectMapper mapper, QaTriageService triage) {
        this.jdbc = jdbc; this.mapper = mapper; this.triage = triage;
    }

    public record GatePolicy(List<String> requiredSuites) {}
    public record SuiteCheck(String suite, String status, UUID runId, String outcome, String category, String reason) {}
    /** {@code reasons} explain a FAIL or PENDING verdict; {@code notes} add context such as new or flaky failures. */
    public record Gate(String project, String commit, String verdict, List<SuiteCheck> suites, List<String> reasons, List<String> notes, GatePolicy policy) {}

    public record Day(LocalDate day, int runs, int passed) {}
    public record SuiteStats(String suite, int runs, int passed, Double passRate, String lastOutcome, String lastCommit, Instant lastAt, List<Day> daily) {}
    public record FailingTest(String suite, String testClass, String testName, String category, int consecutiveFailures, String failingSince, Instant failingSinceAt) {}
    public record FlakyTest(String suite, String testClass, String testName, int flakyCommits) {}
    public record SlowTest(String suite, String testClass, String testName, int averageMs, int runs) {}
    public record PullRequestStatus(int prNumber, String title, String url, String headSha, String gate, int affectedModules, int impactNotes) {}
    public record Dashboard(String project, int days, int runs, int passed, Double passRate, List<SuiteStats> suites, List<FailingTest> failingNow,
            List<FlakyTest> flaky, List<SlowTest> slowest, List<PullRequestStatus> pullRequests, GatePolicy gate) {}

    public GatePolicy policy(String project) {
        var rows = jdbc.queryForList("SELECT required_suites::text AS suites FROM qa_quality_gates WHERE project_key=?", String.class, project);
        if (rows.isEmpty()) return null;
        try {
            return new GatePolicy(mapper.readValue(rows.get(0), new TypeReference<List<String>>() {}));
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid gate policy", ex);
        }
    }

    /** PASS only when every required suite's latest run at this commit passed and no blocking failure exists. */
    public Gate gate(String project, String commit) {
        var policy = policy(project);
        if (policy == null) return new Gate(project, commit, "NOT_CONFIGURED", List.of(), List.of("No quality gate is configured for this project."), List.of(), null);
        List<SuiteCheck> checks = new ArrayList<>();
        List<String> reasons = new ArrayList<>(), notes = new ArrayList<>();
        boolean pending = false, failed = false;
        for (String suite : policy.requiredSuites()) {
            var latest = jdbc.queryForList("""
                    SELECT i.run_id, i.outcome, i.category FROM qa_run_ingestions i JOIN agent_runs r ON r.id = i.run_id
                    WHERE i.project_key=? AND i.suite_key=? AND i.commit_sha=? ORDER BY r.created_at DESC LIMIT 1
                    """, project, suite, commit);
            boolean active = !jdbc.queryForList("""
                    SELECT id FROM agent_runs WHERE tool IN ('QA_RUN_SUITE','QA_RUN_PRIVILEGED_SUITE') AND status IN ('QUEUED','RUNNING','WAITING_APPROVAL')
                      AND arguments::jsonb->>'project' = ? AND arguments::jsonb->>'suite' = ? AND arguments::jsonb->>'commit' = ? LIMIT 1
                    """, UUID.class, project, suite, commit).isEmpty();
            if (latest.isEmpty()) {
                pending = true;
                String reason = active ? suite + " is running at this commit." : suite + " has not run at this commit.";
                checks.add(new SuiteCheck(suite, "PENDING", null, null, null, reason));
                reasons.add(reason);
                continue;
            }
            var row = latest.get(0);
            UUID runId = (UUID) row.get("run_id");
            String outcome = (String) row.get("outcome"), category = (String) row.get("category");
            if ("PASSED".equals(outcome)) {
                checks.add(new SuiteCheck(suite, active ? "PENDING" : "PASSED", runId, outcome, null, active ? suite + " passed but is running again." : null));
                if (active) { pending = true; reasons.add(suite + " is running again at this commit."); }
            } else {
                failed = true;
                String reason = suite + " " + (outcome == null ? "did not produce a result" : "failed (" + outcome + (category == null ? "" : ", " + category) + ")") + ".";
                checks.add(new SuiteCheck(suite, "FAILED", runId, outcome, category, reason));
                reasons.add(reason);
            }
            for (var f : triage.triage(runId).failures()) {
                String test = f.testClass() + (f.testName().isEmpty() ? "" : "#" + f.testName());
                switch (f.history().label()) {
                    case "NEW_FAILURE" -> notes.add("New failure in " + suite + ": " + test + " (last passed at " + shortSha(f.history().lastPassedCommit()) + ").");
                    case "FLAKY" -> notes.add("Flaky in " + suite + ": " + test + " also passed at this commit.");
                    case "PERSISTENT" -> notes.add("Already failing in " + suite + ": " + test + ".");
                    default -> notes.add("First result for " + test + " in " + suite + ".");
                }
            }
        }
        String verdict = failed ? "FAIL" : pending ? "PENDING" : "PASS";
        return new Gate(project, commit, verdict, checks, reasons, notes, policy);
    }

    public Dashboard dashboard(String project, int days) {
        var since = Instant.now().minusSeconds(days * 86_400L);
        Map<String, List<Map<String, Object>>> bySuite = new LinkedHashMap<>();
        for (var r : jdbc.queryForList("""
                SELECT i.suite_key, i.outcome, i.commit_sha, r.created_at, (r.created_at AT TIME ZONE 'UTC')::date AS day
                FROM qa_run_ingestions i JOIN agent_runs r ON r.id = i.run_id
                WHERE i.project_key=? AND r.created_at >= ? ORDER BY i.suite_key, r.created_at
                """, project, java.sql.Timestamp.from(since))) {
            bySuite.computeIfAbsent((String) r.get("suite_key"), k -> new ArrayList<>()).add(r);
        }
        List<SuiteStats> suites = new ArrayList<>();
        int runs = 0, passed = 0;
        for (var e : bySuite.entrySet()) {
            Map<LocalDate, int[]> daily = new LinkedHashMap<>();
            int suiteRuns = 0, suitePassed = 0;
            for (var r : e.getValue()) {
                boolean ok = "PASSED".equals(r.get("outcome"));
                suiteRuns++; if (ok) suitePassed++;
                var counts = daily.computeIfAbsent(((java.sql.Date) r.get("day")).toLocalDate(), d -> new int[2]);
                counts[0]++; if (ok) counts[1]++;
            }
            var last = e.getValue().get(e.getValue().size() - 1);
            List<Day> days_ = new ArrayList<>();
            daily.forEach((d, c) -> days_.add(new Day(d, c[0], c[1])));
            suites.add(new SuiteStats(e.getKey(), suiteRuns, suitePassed, rate(suitePassed, suiteRuns), (String) last.get("outcome"),
                    (String) last.get("commit_sha"), ((java.sql.Timestamp) last.get("created_at")).toInstant(), days_));
            runs += suiteRuns; passed += suitePassed;
        }
        return new Dashboard(project, days, runs, passed, rate(passed, runs), suites, failingNow(project), flaky(project, since), slowest(project, since),
                pullRequests(project), policy(project));
    }

    /**
     * Tests that failed in each suite's most recent run, with how long their failing streak has lasted. A test whose
     * last result was a failure but that no longer runs in the suite (for example after being excluded) is not listed.
     */
    private List<FailingTest> failingNow(String project) {
        List<FailingTest> result = new ArrayList<>();
        for (var t : jdbc.queryForList("""
                WITH latest_run AS (
                  SELECT DISTINCT ON (i.suite_key) i.run_id FROM qa_run_ingestions i JOIN agent_runs r ON r.id = i.run_id
                  WHERE i.project_key=? ORDER BY i.suite_key, r.created_at DESC, r.id DESC)
                SELECT t.suite_key, t.test_class, t.test_name, t.category FROM qa_test_results t JOIN latest_run l ON l.run_id = t.run_id
                WHERE t.status IN ('FAILED','ERROR') ORDER BY t.suite_key, t.test_class, t.test_name LIMIT 50
                """, project)) {
            var history = jdbc.queryForList("""
                    SELECT status, commit_sha, run_created_at FROM qa_test_results
                    WHERE project_key=? AND suite_key=? AND test_class=? AND test_name=? AND status <> 'SKIPPED'
                    ORDER BY run_created_at DESC, id DESC LIMIT 100
                    """, project, t.get("suite_key"), t.get("test_class"), t.get("test_name"));
            int streak = 0;
            Map<String, Object> first = null;
            for (var h : history) {
                if ("PASSED".equals(h.get("status"))) break;
                streak++; first = h;
            }
            result.add(new FailingTest((String) t.get("suite_key"), (String) t.get("test_class"), (String) t.get("test_name"), (String) t.get("category"),
                    streak, first == null ? null : (String) first.get("commit_sha"),
                    first == null ? null : ((java.sql.Timestamp) first.get("run_created_at")).toInstant()));
        }
        result.sort((a, b) -> Integer.compare(b.consecutiveFailures(), a.consecutiveFailures()));
        return result;
    }

    private List<FlakyTest> flaky(String project, Instant since) {
        return jdbc.query("""
                SELECT suite_key, test_class, test_name, count(*) AS commits FROM (
                  SELECT suite_key, test_class, test_name, commit_sha FROM qa_test_results
                  WHERE project_key=? AND run_created_at >= ? AND status <> 'SKIPPED'
                  GROUP BY suite_key, test_class, test_name, commit_sha
                  HAVING bool_or(status = 'PASSED') AND bool_or(status IN ('FAILED','ERROR'))) f
                GROUP BY suite_key, test_class, test_name ORDER BY commits DESC, test_class LIMIT 20
                """, (r, i) -> new FlakyTest(r.getString("suite_key"), r.getString("test_class"), r.getString("test_name"), r.getInt("commits")),
                project, java.sql.Timestamp.from(since));
    }

    private List<SlowTest> slowest(String project, Instant since) {
        return jdbc.query("""
                SELECT suite_key, test_class, test_name, avg(duration_ms)::int AS avg_ms, count(*) AS n FROM qa_test_results
                WHERE project_key=? AND run_created_at >= ? AND status = 'PASSED' AND duration_ms IS NOT NULL
                GROUP BY suite_key, test_class, test_name ORDER BY avg_ms DESC LIMIT 10
                """, (r, i) -> new SlowTest(r.getString("suite_key"), r.getString("test_class"), r.getString("test_name"), r.getInt("avg_ms"), r.getInt("n")),
                project, java.sql.Timestamp.from(since));
    }

    /** The latest head commit of each recently seen pull request, its gate verdict and cached impact summary. */
    private List<PullRequestStatus> pullRequests(String project) {
        List<PullRequestStatus> result = new ArrayList<>();
        for (var p : jdbc.queryForList("""
                SELECT DISTINCT ON (pr_number) pr_number, pr_title, pr_url, head_sha, base_sha FROM qa_pr_runs
                WHERE project_key=? ORDER BY pr_number DESC, created_at DESC LIMIT 20
                """, project)) {
            String head = (String) p.get("head_sha");
            var impact = p.get("base_sha") == null ? List.<Map<String, Object>>of() : jdbc.queryForList("""
                    SELECT jsonb_array_length(impact->'modules') AS modules, jsonb_array_length(impact->'notes') AS notes
                    FROM qa_change_impacts WHERE project_key=? AND base_sha=? AND head_sha=? AND impact IS NOT NULL
                    """, project, p.get("base_sha"), head);
            int modules = impact.isEmpty() ? -1 : ((Number) impact.get(0).get("modules")).intValue();
            int notes = impact.isEmpty() ? -1 : ((Number) impact.get(0).get("notes")).intValue();
            result.add(new PullRequestStatus(((Number) p.get("pr_number")).intValue(), (String) p.get("pr_title"), (String) p.get("pr_url"), head,
                    gate(project, head).verdict(), modules, notes));
        }
        return result;
    }

    private static String shortSha(String sha) { return sha == null ? "?" : sha.substring(0, Math.min(7, sha.length())); }

    private static Double rate(int passed, int runs) { return runs == 0 ? null : Math.round(1000.0 * passed / runs) / 10.0; }
}
