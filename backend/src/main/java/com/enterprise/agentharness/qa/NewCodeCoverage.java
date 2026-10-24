package com.enterprise.agentharness.qa;

import java.io.IOException;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Coverage trends and new-code coverage (P39). New-code coverage is the share of the lines a change adds or modifies
 * (from the merge base of {@code base} to {@code head}) that the suite's latest run at {@code head} executed. Lines
 * no report knows (comments, configuration, files outside the reports) are not counted either way.
 */
@Service
public class NewCodeCoverage {
    static final int MAX_FILES_LISTED = 100;
    static final int MAX_LINES_LISTED = 50;
    private final JdbcTemplate jdbc;
    private final GitMirror git;
    private final QaRegistry registry;

    public NewCodeCoverage(JdbcTemplate jdbc, GitMirror git, QaRegistry registry) { this.jdbc = jdbc; this.git = git; this.registry = registry; }

    public record CoveragePoint(UUID runId, String suite, String commit, String outcome, Instant at, Double linePercent, Double branchPercent,
            long linesCovered, long linesMissed) {}
    public record FileCoverage(String path, int covered, int missed, List<Integer> uncoveredLines) {}
    /** {@code status}: COMPUTED, NO_RUN (no run at head), NO_LINE_DATA (the run kept no line coverage) or NO_CHANGED_LINES. */
    public record Result(String project, String suite, String base, String head, String status, UUID runId, int changedFiles, int covered, int missed,
            Double percent, List<FileCoverage> files) {
        /** Unrounded, for threshold checks. */
        public Double exactPercent() { return covered + missed == 0 ? null : 100.0 * covered / (covered + missed); }
    }

    /** The suite's (or every suite's) recorded coverage runs, oldest first. */
    public List<CoveragePoint> trend(String project, String suite, int limit) {
        var points = new ArrayList<>(jdbc.query("""
                SELECT c.*, r.created_at, i.outcome FROM qa_coverage c JOIN agent_runs r ON r.id = c.run_id LEFT JOIN qa_run_ingestions i ON i.run_id = c.run_id
                WHERE c.project_key=? AND (?::text IS NULL OR c.suite_key=?) ORDER BY r.created_at DESC LIMIT ?
                """, (r, i) -> new CoveragePoint(r.getObject("run_id", UUID.class), r.getString("suite_key"), r.getString("commit_sha"), r.getString("outcome"),
                r.getTimestamp("created_at").toInstant(), CoverageReports.Summary.percent(r.getLong("lines_covered"), r.getLong("lines_missed")),
                CoverageReports.Summary.percent(r.getLong("branches_covered"), r.getLong("branches_missed")), r.getLong("lines_covered"), r.getLong("lines_missed")),
                project, suite, suite, limit));
        java.util.Collections.reverse(points);
        return points;
    }

    /** New-code coverage of {@code head} against {@code base} (both exact commits) for one suite. */
    public Result compute(String projectKey, String suite, String base, String head) throws IOException {
        var project = registry.project(projectKey).orElseThrow(() -> new IllegalArgumentException("unknown project"));
        var runs = jdbc.queryForList("""
                SELECT c.run_id FROM qa_coverage c JOIN agent_runs r ON r.id = c.run_id
                WHERE c.project_key=? AND c.suite_key=? AND c.commit_sha=? ORDER BY r.created_at DESC LIMIT 1
                """, UUID.class, projectKey, suite, head);
        if (runs.isEmpty()) return empty(projectKey, suite, base, head, "NO_RUN", null, 0);
        UUID runId = runs.get(0);
        var reported = jdbc.query("SELECT module, path, covered, missed FROM qa_coverage_lines WHERE run_id=?",
                (r, i) -> new CoverageLines.FileLines(r.getString("module"), r.getString("path"), set(r.getArray("covered")), set(r.getArray("missed"))), runId);
        if (reported.isEmpty()) return empty(projectKey, suite, base, head, "NO_LINE_DATA", runId, 0);
        Map<String, TreeSet<Integer>> changed = git.changedLines(project, base, head);
        int covered = 0, missed = 0;
        List<FileCoverage> files = new ArrayList<>();
        for (var e : changed.entrySet()) {
            var entry = match(e.getKey(), reported);
            if (entry == null) continue;
            int c = 0, m = 0;
            List<Integer> uncovered = new ArrayList<>();
            for (int line : e.getValue()) {
                if (entry.covered().contains(line)) c++;
                else if (entry.missed().contains(line)) { m++; if (uncovered.size() < MAX_LINES_LISTED) uncovered.add(line); }
            }
            if (c + m == 0) continue;
            covered += c; missed += m;
            files.add(new FileCoverage(e.getKey(), c, m, List.copyOf(uncovered)));
        }
        files.sort((a, b) -> Integer.compare(b.missed(), a.missed()));
        if (covered + missed == 0) return empty(projectKey, suite, base, head, "NO_CHANGED_LINES", runId, changed.size());
        return new Result(projectKey, suite, base, head, "COMPUTED", runId, changed.size(), covered, missed,
                Math.floor(1000.0 * covered / (covered + missed)) / 10.0, List.copyOf(files.subList(0, Math.min(files.size(), MAX_FILES_LISTED))));
    }

    /** The report entry for a repository path: the most specific module wins when several match. */
    static CoverageLines.FileLines match(String path, List<CoverageLines.FileLines> reported) {
        CoverageLines.FileLines best = null;
        for (var f : reported) {
            if (!CoverageLines.matches(path, f.module(), f.path())) continue;
            if (best == null || f.module().length() + f.path().length() > best.module().length() + best.path().length()) best = f;
        }
        return best;
    }

    private static Result empty(String project, String suite, String base, String head, String status, UUID runId, int changedFiles) {
        return new Result(project, suite, base, head, status, runId, changedFiles, 0, 0, null, List.of());
    }

    private static TreeSet<Integer> set(Array array) throws SQLException {
        return new TreeSet<>(Arrays.asList((Integer[]) array.getArray()));
    }
}
