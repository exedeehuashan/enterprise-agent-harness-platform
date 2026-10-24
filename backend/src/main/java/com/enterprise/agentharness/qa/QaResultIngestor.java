package com.enterprise.agentharness.qa;

import com.enterprise.agentharness.artifact.ArtifactStore;
import com.enterprise.agentharness.tool.CanonicalJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads the stored evidence of finished QA runs into per-test rows and a triage category. It only reads artifacts
 * that the run's accepted result stored, so a stale or rejected worker can never contribute history; it also
 * backfills runs that finished before this existed. Each run is ingested once, in its own transaction.
 */
@Service
public class QaResultIngestor {
    private static final Logger log = LoggerFactory.getLogger(QaResultIngestor.class);
    static final long MAX_ENTRY_BYTES = 5L * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final ArtifactStore artifacts;
    private final FailureClassifier classifier;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final GitHubWriteBack writeBack;

    public QaResultIngestor(JdbcTemplate jdbc, ArtifactStore artifacts, FailureClassifier classifier, ObjectMapper mapper, TransactionTemplate tx,
            GitHubWriteBack writeBack) {
        this.jdbc = jdbc; this.artifacts = artifacts; this.classifier = classifier; this.mapper = mapper; this.tx = tx; this.writeBack = writeBack;
    }

    /** Ingests up to 20 finished QA runs; returns how many were processed. */
    public int runOnce() {
        var ids = jdbc.queryForList("""
                SELECT r.id FROM agent_runs r LEFT JOIN qa_run_ingestions i ON i.run_id = r.id
                WHERE r.tool IN ('QA_RUN_SUITE','QA_RUN_PRIVILEGED_SUITE','QA_RUN_API_TESTS','QA_RUN_ENVIRONMENT_TESTS') AND i.run_id IS NULL
                  AND r.status NOT IN ('QUEUED','RUNNING','WAITING_APPROVAL') AND r.arguments IS NOT NULL
                ORDER BY r.created_at, r.id LIMIT 20
                """, UUID.class);
        for (UUID id : ids) {
            try {
                tx.executeWithoutResult(status -> ingest(id));
            } catch (RuntimeException ex) {
                log.warn("Ingesting QA run {} failed", id, ex);
                tx.executeWithoutResult(status -> recordFailure(id));
            }
        }
        return ids.size();
    }

    private void ingest(UUID runId) {
        var run = jdbc.queryForMap("SELECT status, arguments, created_at FROM agent_runs WHERE id=?", runId);
        var args = CanonicalJson.read((String) run.get("arguments"));
        String project = args.get("project").asText(), suite = args.get("suite").asText(), commit = args.get("commit").asText();
        Timestamp created = (Timestamp) run.get("created_at");
        String outcome = null, consoleTail = null;
        byte[] coverage = null, performance = null, coverageLines = null;
        List<JUnitReports.TestCase> cases = new ArrayList<>();
        for (var a : artifacts.list(runId)) {
            if (a.expiredAt() != null) continue; // content removed by retention; nothing left to read
            byte[] bytes = artifacts.content(a.id()).bytes();
            switch (a.name()) {
                case "summary.json" -> {
                    try { outcome = mapper.readTree(bytes).path("outcome").asText(null); } catch (Exception ignored) { /* evidence stays as stored */ }
                }
                case "console.log" -> consoleTail = new String(bytes, Math.max(0, bytes.length - 200_000), Math.min(bytes.length, 200_000), StandardCharsets.UTF_8);
                case "junit-reports.zip" -> cases.addAll(readZip(bytes));
                case "coverage.json" -> coverage = bytes;
                case "performance.json" -> performance = bytes;
                case "coverage-lines.json" -> coverageLines = bytes;
                default -> { }
            }
        }
        Map<String, Integer> categories = new HashMap<>();
        int failed = 0;
        for (var c : cases) {
            String category = null;
            if (c.status().equals("FAILED") || c.status().equals("ERROR")) {
                failed++;
                category = classifier.classifyTest(c.failureType(), c.message(), c.detail());
                categories.merge(category, 1, Integer::sum);
            }
            jdbc.update("""
                    INSERT INTO qa_test_results(run_id,project_key,suite_key,commit_sha,test_class,test_name,status,duration_ms,failure_type,message,category,run_created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """, runId, project, suite, commit, c.className(), c.name(), c.status(), c.durationMs(), c.failureType(), c.message(), category, created);
        }
        String runCategory = classifier.classifyRun(outcome, consoleTail);
        if (runCategory == null && !categories.isEmpty()) {
            runCategory = categories.entrySet().stream().max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder()))).get().getKey();
        }
        jdbc.update("""
                INSERT INTO qa_run_ingestions(run_id,project_key,suite_key,commit_sha,run_status,outcome,category,test_count,failed_count)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, runId, project, suite, commit, run.get("status"), outcome, runCategory, cases.size(), failed);
        if (coverage != null) recordCoverage(runId, project, suite, commit, coverage);
        if (coverageLines != null) recordCoverageLines(runId, coverageLines);
        if (performance != null) recordPerformance(runId, project, suite, commit, outcome, performance);
        // Makes the result an event like any other (for example for webhooks): code is the outcome.
        jdbc.update("INSERT INTO audit_events(actor,action,run_id,code) VALUES ('qa','QA_RESULT_INGESTED',?,?)", runId, outcome == null ? "NO_RESULT" : outcome);
        writeBack.onIngested(project, suite, commit, outcome == null ? "NO_RESULT" : outcome, cases.size(), failed);
    }

    private void recordFailure(UUID runId) {
        var run = jdbc.queryForMap("SELECT status, arguments FROM agent_runs WHERE id=?", runId);
        var args = CanonicalJson.read((String) run.get("arguments"));
        jdbc.update("""
                INSERT INTO qa_run_ingestions(run_id,project_key,suite_key,commit_sha,run_status,error) VALUES (?,?,?,?,?,'INGESTION_FAILED')
                ON CONFLICT (run_id) DO NOTHING
                """, runId, args.path("project").asText("?"), args.path("suite").asText("?"), args.path("commit").asText("?"), run.get("status"));
    }

    /** Stores the run's coverage totals; a malformed coverage.json loses only the coverage, not the test results. */
    private void recordCoverage(UUID runId, String project, String suite, String commit, byte[] json) {
        try {
            var c = mapper.readTree(json);
            if (c.path("linesCovered").asLong() + c.path("linesMissed").asLong() == 0) return; // no report was found or readable
            jdbc.update("""
                    INSERT INTO qa_coverage(run_id,project_key,suite_key,commit_sha,lines_covered,lines_missed,branches_covered,branches_missed,modules)
                    VALUES (?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT (run_id) DO NOTHING
                    """, runId, project, suite, commit, c.path("linesCovered").asLong(), c.path("linesMissed").asLong(),
                    c.path("branchesCovered").asLong(), c.path("branchesMissed").asLong(), c.path("modules").toString());
        } catch (java.io.IOException ex) {
            log.warn("Unreadable coverage.json for run {}", runId);
        }
    }

    /** Stores line-level coverage; a malformed file loses only the line data. */
    private void recordCoverageLines(UUID runId, byte[] json) {
        try {
            var rows = new ArrayList<Object[]>();
            for (var f : mapper.readTree(json).path("files")) {
                String module = f.path("module").asText(""), path = f.path("path").asText("");
                if (path.isEmpty() || path.length() > 500 || module.length() > 300) continue;
                rows.add(new Object[] { runId, module, path, ints(f.path("covered")), ints(f.path("missed")) });
                if (rows.size() >= CoverageLines.MAX_FILES) break;
            }
            jdbc.batchUpdate("INSERT INTO qa_coverage_lines(run_id,module,path,covered,missed) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING", rows,
                    500, (ps, row) -> {
                        ps.setObject(1, row[0]); ps.setString(2, (String) row[1]); ps.setString(3, (String) row[2]);
                        ps.setArray(4, ps.getConnection().createArrayOf("integer", (Integer[]) row[3]));
                        ps.setArray(5, ps.getConnection().createArrayOf("integer", (Integer[]) row[4]));
                    });
        } catch (java.io.IOException ex) {
            log.warn("Unreadable coverage-lines.json for run {}", runId);
        }
    }
    private static Integer[] ints(com.fasterxml.jackson.databind.JsonNode array) {
        var out = new ArrayList<Integer>();
        for (var v : array) if (v.canConvertToInt() && v.intValue() > 0) out.add(v.intValue());
        return out.toArray(Integer[]::new);
    }

    /** Stores the run's k6 results; a summary that reported an error (no or invalid file) records nothing. */
    private void recordPerformance(UUID runId, String project, String suite, String commit, String outcome, byte[] json) {
        try {
            var p = mapper.readTree(json);
            if (p.has("error") || !p.path("p95Ms").isNumber()) return;
            jdbc.update("""
                    INSERT INTO qa_performance(run_id,project_key,suite_key,commit_sha,outcome,requests,requests_per_second,avg_ms,p90_ms,p95_ms,max_ms,error_rate,checks_rate)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (run_id) DO NOTHING
                    """, runId, project, suite, commit, outcome, p.path("requests").asLong(), p.path("requestsPerSecond").asDouble(), p.path("avgMs").asDouble(),
                    p.path("p90Ms").asDouble(), p.path("p95Ms").asDouble(), p.path("maxMs").asDouble(),
                    p.path("errorRate").isNumber() ? p.get("errorRate").asDouble() : null, p.path("checksRate").isNumber() ? p.get("checksRate").asDouble() : null);
        } catch (java.io.IOException ex) {
            log.warn("Unreadable performance.json for run {}", runId);
        }
    }

    /** Reads every XML report in the archive, skipping oversized or unreadable entries. */
    private static List<JUnitReports.TestCase> readZip(byte[] zip) {
        List<JUnitReports.TestCase> cases = new ArrayList<>();
        try (var in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                if (entry.isDirectory() || !entry.getName().endsWith(".xml")) continue;
                byte[] xml = bounded(in);
                if (xml == null) continue;
                try { cases.addAll(JUnitReports.cases(new ByteArrayInputStream(xml))); } catch (Exception ignored) { /* counted as unreadable in summary.json */ }
            }
        } catch (java.io.IOException ignored) {
            // A damaged archive contributes no test rows; summary.json still records the outcome.
        }
        return cases;
    }

    private static byte[] bounded(InputStream in) throws java.io.IOException {
        var out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) > 0; ) {
            out.write(buffer, 0, n);
            if (out.size() > MAX_ENTRY_BYTES) return null;
        }
        return out.toByteArray();
    }
}
