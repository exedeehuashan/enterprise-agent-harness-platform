package com.enterprise.agentharness.qa;

import com.enterprise.agentharness.governance.AuditService;
import com.enterprise.agentharness.run.RunException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Flaky-test quarantine suggestions (P40). A test that both passed and failed at the same commit on at least
 * {@code eahp.qa.flaky.min-commits} commits within {@code eahp.qa.flaky.days} days is flaky by evidence, not by guess.
 * EAHP proposes quarantining it, with that evidence; an approver confirms in one step (which creates an ordinary,
 * expiring, audited quarantine) or dismisses it, which snoozes the test for the same window. EAHP never quarantines on
 * its own. New suggestions are audit events ({@code QA_QUARANTINE_SUGGESTED}), so webhooks and notifications see them.
 */
@Service
public class QaQuarantineSuggestions {
    private static final Logger log = LoggerFactory.getLogger(QaQuarantineSuggestions.class);
    static final int DEFAULT_DAYS = 14;
    private final JdbcTemplate jdbc;
    private final QaQuarantines quarantines;
    private final ObjectMapper mapper;
    private final int windowDays;
    private final int minCommits;

    public QaQuarantineSuggestions(JdbcTemplate jdbc, QaQuarantines quarantines, ObjectMapper mapper, @Value("${eahp.qa.flaky.days:14}") int windowDays,
            @Value("${eahp.qa.flaky.min-commits:2}") int minCommits) {
        this.jdbc = jdbc; this.quarantines = quarantines; this.mapper = mapper; this.windowDays = windowDays; this.minCommits = Math.max(1, minCommits);
    }

    public record Suggestion(UUID id, String projectKey, String suiteKey, String testClass, String testName, int flakyCommits, JsonNode evidence,
            String status, Instant createdAt, Instant updatedAt, String decidedBy, Instant decidedAt, String decisionNote, UUID quarantineId) {}

    @Scheduled(initialDelayString = "${eahp.qa.flaky.initial-delay-ms:120000}", fixedDelayString = "${eahp.qa.flaky.interval-ms:600000}")
    public void scheduled() {
        try { detect(); } catch (RuntimeException ex) { log.warn("Flaky-test detection failed", ex); }
    }

    /** One detection pass over all projects; returns how many suggestions were created. */
    @Transactional
    public int detect() {
        Timestamp since = Timestamp.from(Instant.now().minus(Duration.ofDays(windowDays)));
        // Suggestions for tests that were quarantined some other way meanwhile are settled.
        jdbc.update("""
                UPDATE qa_quarantine_suggestions s SET status='RESOLVED', updated_at=clock_timestamp(), decision_note='quarantined separately'
                WHERE status='PENDING' AND EXISTS (SELECT 1 FROM qa_quarantines q WHERE q.project_key=s.project_key AND q.suite_key=s.suite_key
                  AND q.test_class=s.test_class AND q.test_name=s.test_name AND q.released_at IS NULL AND q.expires_at > clock_timestamp())
                """);
        var candidates = jdbc.queryForList("""
                SELECT project_key, suite_key, test_class, test_name, count(*) AS commits FROM (
                  SELECT project_key, suite_key, test_class, test_name, commit_sha FROM qa_test_results
                  WHERE run_created_at >= ? AND status <> 'SKIPPED'
                  GROUP BY project_key, suite_key, test_class, test_name, commit_sha
                  HAVING bool_or(status = 'PASSED') AND bool_or(status IN ('FAILED','ERROR'))) f
                GROUP BY project_key, suite_key, test_class, test_name HAVING count(*) >= ?
                ORDER BY commits DESC LIMIT 200
                """, since, minCommits);
        int created = 0;
        for (var c : candidates) {
            String project = (String) c.get("project_key"), suite = (String) c.get("suite_key"), cls = (String) c.get("test_class"), name = (String) c.get("test_name");
            if (quarantines.active(project, suite, cls, name)) continue;
            boolean open = jdbc.queryForObject("""
                    SELECT count(*) FROM qa_quarantine_suggestions WHERE project_key=? AND suite_key=? AND test_class=? AND test_name=?
                      AND (status='PENDING' OR (status IN ('DISMISSED','ACCEPTED') AND decided_at >= ?))
                    """, Long.class, project, suite, cls, name, since) > 0;
            if (open) continue; // already proposed, or decided within the window (a dismissal snoozes the test)
            var evidence = evidence(project, suite, cls, name, since);
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO qa_quarantine_suggestions(id,project_key,suite_key,test_class,test_name,flaky_commits,evidence,status)
                    VALUES (?,?,?,?,?,?,?::jsonb,'PENDING')
                    """, id, project, suite, cls, name, ((Number) c.get("commits")).intValue(), evidence.toString());
            UUID failedRun = latestFailedRun(project, suite, cls, name, since);
            if (failedRun != null) jdbc.update("INSERT INTO audit_events(actor,action,run_id,code) VALUES ('qa','QA_QUARANTINE_SUGGESTED',?,?)", failedRun, suite);
            created++;
        }
        return created;
    }

    /** Up to ten commits where the test both passed and failed, with the runs and the latest failure message. */
    private JsonNode evidence(String project, String suite, String cls, String name, Timestamp since) {
        var commits = mapper.createArrayNode();
        for (var row : jdbc.queryForList("""
                SELECT commit_sha, count(*) FILTER (WHERE status='PASSED') AS passed, count(*) FILTER (WHERE status IN ('FAILED','ERROR')) AS failed,
                       max(run_created_at) AS last_at
                FROM qa_test_results WHERE project_key=? AND suite_key=? AND test_class=? AND test_name=? AND run_created_at >= ?
                GROUP BY commit_sha HAVING bool_or(status='PASSED') AND bool_or(status IN ('FAILED','ERROR')) ORDER BY max(run_created_at) DESC LIMIT 10
                """, project, suite, cls, name, since)) {
            commits.addObject().put("commit", (String) row.get("commit_sha")).put("passed", ((Number) row.get("passed")).intValue())
                    .put("failed", ((Number) row.get("failed")).intValue()).put("lastAt", ((Timestamp) row.get("last_at")).toInstant().toString());
        }
        var message = jdbc.queryForList("""
                SELECT message FROM qa_test_results WHERE project_key=? AND suite_key=? AND test_class=? AND test_name=? AND status IN ('FAILED','ERROR')
                  AND run_created_at >= ? ORDER BY run_created_at DESC LIMIT 1
                """, String.class, project, suite, cls, name, since);
        var node = mapper.createObjectNode().put("windowDays", windowDays);
        node.set("commits", commits);
        String m = message.isEmpty() || message.get(0) == null ? null : message.get(0);
        node.put("lastFailure", m == null ? null : m.length() > 300 ? m.substring(0, 300) + "…" : m);
        return node;
    }

    private UUID latestFailedRun(String project, String suite, String cls, String name, Timestamp since) {
        var ids = jdbc.queryForList("""
                SELECT run_id FROM qa_test_results WHERE project_key=? AND suite_key=? AND test_class=? AND test_name=? AND status IN ('FAILED','ERROR')
                  AND run_created_at >= ? ORDER BY run_created_at DESC LIMIT 1
                """, UUID.class, project, suite, cls, name, since);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public List<Suggestion> list(String project, String status) {
        return jdbc.query("""
                SELECT * FROM qa_quarantine_suggestions WHERE (?::text IS NULL OR project_key=?) AND (?::text IS NULL OR status=?)
                ORDER BY (status='PENDING') DESC, created_at DESC LIMIT 100
                """, this::map, project, project, status, status);
    }

    /** Confirms a suggestion: creates the quarantine (default {@value #DEFAULT_DAYS} days) with a reason that cites the evidence. */
    @Transactional
    @PreAuthorize("hasRole('APPROVER')")
    public Suggestion accept(UUID id, JsonNode body) {
        var s = pending(id);
        fields(body, Set.of("days", "reason"));
        int days = body != null && body.has("days") ? body.get("days").asInt(-1) : DEFAULT_DAYS;
        String reason = body != null && body.hasNonNull("reason") ? body.get("reason").asText() : "Flaky: passed and failed at the same commit on "
                + s.flakyCommits() + " commit" + (s.flakyCommits() == 1 ? "" : "s") + " in " + s.evidence().path("windowDays").asInt(windowDays)
                + " days (EAHP suggestion " + id.toString().substring(0, 8) + ").";
        var request = mapper.createObjectNode().put("project", s.projectKey()).put("suite", s.suiteKey()).put("testClass", s.testClass())
                .put("testName", s.testName()).put("reason", reason).put("days", days);
        var quarantine = quarantines.create(request);
        jdbc.update("""
                UPDATE qa_quarantine_suggestions SET status='ACCEPTED', decided_by=?, decided_at=clock_timestamp(), updated_at=clock_timestamp(), quarantine_id=?
                WHERE id=? AND status='PENDING'
                """, AuditService.actor(), quarantine.id(), id);
        return get(id);
    }

    /** Dismisses a suggestion; the test is not proposed again within the detection window. */
    @Transactional
    @PreAuthorize("hasRole('APPROVER')")
    public Suggestion dismiss(UUID id, JsonNode body) {
        var s = pending(id);
        fields(body, Set.of("note"));
        String note = body != null && body.hasNonNull("note") ? body.get("note").asText().strip() : null;
        if (note != null && (note.length() > 500 || note.chars().anyMatch(Character::isISOControl))) throw bad("note must be text up to 500 characters");
        jdbc.update("""
                UPDATE qa_quarantine_suggestions SET status='DISMISSED', decided_by=?, decided_at=clock_timestamp(), updated_at=clock_timestamp(), decision_note=?
                WHERE id=? AND status='PENDING'
                """, AuditService.actor(), note, id);
        try {
            jdbc.update("INSERT INTO qa_admin_events(actor,action,project_key,subject,before_state,after_state) VALUES (?,?,?,?,NULL,?::jsonb)",
                    AuditService.actor(), "QUARANTINE_SUGGESTION_DISMISSED", s.projectKey(), s.suiteKey(),
                    mapper.writeValueAsString(java.util.Map.of("testClass", s.testClass(), "testName", s.testName(), "note", note == null ? "" : note)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
        return get(id);
    }

    public Suggestion get(UUID id) {
        var rows = jdbc.query("SELECT * FROM qa_quarantine_suggestions WHERE id=?", this::map, id);
        if (rows.isEmpty()) throw new RunException(HttpStatus.NOT_FOUND, "Suggestion not found: " + id);
        return rows.get(0);
    }

    private Suggestion pending(UUID id) {
        var s = get(id);
        if (!s.status().equals("PENDING")) throw new RunException(HttpStatus.CONFLICT, "This suggestion was already " + s.status().toLowerCase());
        return s;
    }

    private static void fields(JsonNode body, Set<String> allowed) {
        if (body == null || body.isNull()) return;
        if (!body.isObject()) throw bad("A JSON object is required");
        for (var it = body.fieldNames(); it.hasNext(); ) if (!allowed.contains(it.next())) throw bad("Unknown field");
    }

    private Suggestion map(ResultSet r, int i) throws SQLException {
        JsonNode evidence;
        try { evidence = mapper.readTree(r.getString("evidence")); } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new SQLException(ex); }
        return new Suggestion(r.getObject("id", UUID.class), r.getString("project_key"), r.getString("suite_key"), r.getString("test_class"),
                r.getString("test_name"), r.getInt("flaky_commits"), evidence, r.getString("status"), instant(r, "created_at"), instant(r, "updated_at"),
                r.getString("decided_by"), instant(r, "decided_at"), r.getString("decision_note"), r.getObject("quarantine_id", UUID.class));
    }
    private static Instant instant(ResultSet r, String k) throws SQLException { var t = r.getTimestamp(k); return t == null ? null : t.toInstant(); }
    private static RunException bad(String m) { return new RunException(HttpStatus.BAD_REQUEST, m); }
}
