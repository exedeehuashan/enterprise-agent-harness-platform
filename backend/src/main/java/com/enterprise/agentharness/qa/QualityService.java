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
    private final QaQuarantines quarantines;
    public QualityService(JdbcTemplate jdbc, ObjectMapper mapper, QaTriageService triage, QaQuarantines quarantines) {
        this.jdbc = jdbc; this.mapper = mapper; this.triage = triage; this.quarantines = quarantines;
    }

    /** {@code minLineCoverage}: optional minimum line coverage (percent) per required suite, checked on full-scope runs. */
    public record GatePolicy(List<String> requiredSuites, Map<String, Double> minLineCoverage) {}
    /** {@code modules} is empty for a full run, or lists the only modules a selective run built and tested. */
    public record SuiteCheck(String suite, String status, UUID runId, String outcome, String category, String reason, List<String> modules, Double lineCoverage) {}
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
        var rows = jdbc.queryForList("SELECT required_suites::text AS suites, min_line_coverage::text AS coverage FROM qa_quality_gates WHERE project_key=?", project);
        if (rows.isEmpty()) return null;
        try {
            return new GatePolicy(mapper.readValue((String) rows.get(0).get("suites"), new TypeReference<List<String>>() {}),
                    mapper.readValue((String) rows.get(0).get("coverage"), new TypeReference<java.util.LinkedHashMap<String, Double>>() {}));
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
                    SELECT i.run_id, i.outcome, i.category, i.test_count, i.failed_count, r.created_at, r.arguments::jsonb->'modules' AS modules
                    FROM qa_run_ingestions i JOIN agent_runs r ON r.id = i.run_id
                    WHERE i.project_key=? AND i.suite_key=? AND i.commit_sha=? ORDER BY r.created_at DESC LIMIT 1
                    """, project, suite, commit);
            boolean active = !jdbc.queryForList("""
                    SELECT id FROM agent_runs WHERE tool IN ('QA_RUN_SUITE','QA_RUN_PRIVILEGED_SUITE','QA_RUN_API_TESTS') AND status IN ('QUEUED','RUNNING','WAITING_APPROVAL')
                      AND arguments::jsonb->>'project' = ? AND arguments::jsonb->>'suite' = ? AND arguments::jsonb->>'commit' = ? LIMIT 1
                    """, UUID.class, project, suite, commit).isEmpty();
            if (latest.isEmpty()) {
                pending = true;
                String reason = active ? suite + " is running at this commit." : suite + " has not run at this commit.";
                checks.add(new SuiteCheck(suite, "PENDING", null, null, null, reason, List.of(), null));
                reasons.add(reason);
                continue;
            }
            var row = latest.get(0);
            UUID runId = (UUID) row.get("run_id");
            String outcome = (String) row.get("outcome"), category = (String) row.get("category");
            List<String> modules = modules(row.get("modules"));
            if (!modules.isEmpty()) notes.add(suite + " ran only the affected modules at this commit: " + String.join(", ", modules) + ".");
            Double lineCoverage = lineCoverage(runId);
            String coverageFailure = coverageFailure(policy, suite, modules, exactLineCoverage(runId), lineCoverage, notes);
            if ("PASSED".equals(outcome) && coverageFailure != null) {
                failed = true;
                checks.add(new SuiteCheck(suite, "FAILED", runId, outcome, null, coverageFailure, modules, lineCoverage));
                reasons.add(coverageFailure);
            } else if ("PASSED".equals(outcome)) {
                checks.add(new SuiteCheck(suite, active ? "PENDING" : "PASSED", runId, outcome, null, active ? suite + " passed but is running again." : null, modules, lineCoverage));
                if (active) { pending = true; reasons.add(suite + " is running again at this commit."); }
            } else {
                var waiver = "TESTS_FAILED".equals(outcome) ? quarantineWaiver(project, suite, row) : null;
                if (waiver != null && waiver.accepted() && coverageFailure != null) {
                    // A quarantine waives test failures, not the separate coverage requirement.
                    failed = true;
                    checks.add(new SuiteCheck(suite, "FAILED", runId, outcome, category, coverageFailure, modules, lineCoverage));
                    reasons.add(coverageFailure);
                } else if (waiver != null && waiver.accepted()) {
                    checks.add(new SuiteCheck(suite, active ? "PENDING" : "PASSED", runId, outcome, category, waiver.message(), modules, lineCoverage));
                    notes.add(waiver.message());
                    if (active) { pending = true; reasons.add(suite + " is running again at this commit."); }
                } else {
                    failed = true;
                    String reason = suite + " " + (outcome == null ? "did not produce a result" : "failed (" + outcome + (category == null ? "" : ", " + category) + ")") + ".";
                    if (waiver != null) reason += " " + waiver.message();
                    checks.add(new SuiteCheck(suite, "FAILED", runId, outcome, category, reason, modules, lineCoverage));
                    reasons.add(reason);
                }
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

    /** Line coverage the run recorded, in percent with one decimal, or null when it reported none. */
    private Double lineCoverage(UUID runId) {
        Double exact = exactLineCoverage(runId);
        return exact == null ? null : Math.floor(exact * 10) / 10.0; // display value; never rounds up past a threshold
    }

    /** Unrounded percentage, used for threshold checks (99.95% must not pass a 100% minimum). */
    private Double exactLineCoverage(UUID runId) {
        var rows = jdbc.queryForList("SELECT lines_covered, lines_missed FROM qa_coverage WHERE run_id=?", runId);
        if (rows.isEmpty()) return null;
        long covered = ((Number) rows.get(0).get("lines_covered")).longValue(), missed = ((Number) rows.get(0).get("lines_missed")).longValue();
        return covered + missed == 0 ? null : 100.0 * covered / (covered + missed);
    }

    /**
     * The gate's coverage requirement for a passing suite: null when met or not configured. A module-selected run
     * only covers part of the code, so its percentage is not comparable and the check is skipped with a note.
     */
    private static String coverageFailure(GatePolicy policy, String suite, List<String> modules, Double exact, Double display, List<String> notes) {
        Double min = policy.minLineCoverage().get(suite);
        if (min == null) return null;
        if (!modules.isEmpty()) { notes.add("Coverage of " + suite + " was not checked: the run covered only selected modules."); return null; }
        if (exact == null) return suite + " passed but reported no coverage; the gate requires at least " + min + "% line coverage.";
        if (exact < min) return suite + " line coverage " + display + "% is below the gate's " + min + "%.";
        return null;
    }

    record Waiver(boolean accepted, String message) {}

    /**
     * A run whose only failures are quarantined tests does not block the gate, provided the rest of the suite still
     * ran: Maven skips modules downstream of a failure, so the run must have executed at least as many tests as the
     * suite's last passing run with the same scope. Null when some failure is not quarantined.
     */
    private Waiver quarantineWaiver(String project, String suite, Map<String, Object> run) {
        UUID runId = (UUID) run.get("run_id");
        var failures = jdbc.queryForList("SELECT test_class, test_name FROM qa_test_results WHERE run_id=? AND status IN ('FAILED','ERROR')", runId);
        int failedCount = ((Number) run.get("failed_count")).intValue();
        if (failures.isEmpty() || failures.size() != failedCount) return null;
        for (var f : failures) {
            if (!quarantines.active(project, suite, (String) f.get("test_class"), (String) f.get("test_name"))) return null;
        }
        String tests = failures.stream().map(f -> f.get("test_class") + (((String) f.get("test_name")).isEmpty() ? "" : "#" + f.get("test_name")))
                .sorted().collect(java.util.stream.Collectors.joining(", "));
        var baseline = jdbc.queryForList("""
                SELECT i.test_count FROM qa_run_ingestions i JOIN agent_runs r ON r.id = i.run_id
                WHERE i.project_key=? AND i.suite_key=? AND i.outcome='PASSED' AND r.created_at < ?
                  AND (r.arguments::jsonb->'modules') IS NOT DISTINCT FROM ?::jsonb
                ORDER BY r.created_at DESC LIMIT 1
                """, Integer.class, project, suite, run.get("created_at"), run.get("modules") == null ? null : run.get("modules").toString());
        int ran = ((Number) run.get("test_count")).intValue();
        if (baseline.isEmpty()) {
            return new Waiver(false, "Only quarantined tests failed (" + tests + "), but no earlier passing run of " + suite + " confirms the rest of the suite ran.");
        }
        if (ran < baseline.get(0)) {
            return new Waiver(false, "Only quarantined tests failed (" + tests + "), but the run executed " + ran + " tests, fewer than the "
                    + baseline.get(0) + " of its last passing run, so tests after the failure may not have run.");
        }
        return new Waiver(true, suite + " failed only in quarantined tests, which do not block the gate: " + tests + ".");
    }

    private List<String> modules(Object json) {
        if (json == null) return List.of();
        try { return List.of(mapper.readValue(json.toString(), String[].class)); } catch (Exception ex) { return List.of(); }
    }

    private static String shortSha(String sha) { return sha == null ? "?" : sha.substring(0, Math.min(7, sha.length())); }

    private static Double rate(int passed, int runs) { return runs == 0 ? null : Math.round(1000.0 * passed / runs) / 10.0; }
}
