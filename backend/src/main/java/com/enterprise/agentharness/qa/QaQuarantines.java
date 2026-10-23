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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quarantined tests (方案 4): an approver accepts, with a reason and for at most {@link #MAX_DAYS} days, that a known
 * flaky or broken test may fail without blocking the release gate. The test still runs and its results are still
 * recorded; only the gate's verdict changes. Every quarantine expires on its own, and creating or releasing one is
 * audited in {@code qa_admin_events}.
 */
@Service
public class QaQuarantines {
    static final int MAX_DAYS = 30;
    private final JdbcTemplate jdbc;
    private final QaRegistry registry;
    private final ObjectMapper mapper;

    public QaQuarantines(JdbcTemplate jdbc, QaRegistry registry, ObjectMapper mapper) { this.jdbc = jdbc; this.registry = registry; this.mapper = mapper; }

    public record Quarantine(UUID id, String projectKey, String suiteKey, String testClass, String testName, String reason, String createdBy,
            Instant createdAt, Instant expiresAt, Instant releasedAt, String releasedBy, boolean active) {}

    /** Quarantines of a project; by default only those in force now. */
    public List<Quarantine> list(String project, boolean all) {
        return jdbc.query("""
                SELECT *, (released_at IS NULL AND expires_at > clock_timestamp()) AS active FROM qa_quarantines
                WHERE (?::text IS NULL OR project_key=?) AND (? OR (released_at IS NULL AND expires_at > clock_timestamp()))
                ORDER BY created_at DESC LIMIT 200
                """, this::map, project, project, all);
    }

    /** Whether a test is quarantined at this moment. */
    public boolean active(String project, String suite, String testClass, String testName) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM qa_quarantines WHERE project_key=? AND suite_key=? AND test_class=? AND test_name=?
                  AND released_at IS NULL AND expires_at > clock_timestamp()
                """, Long.class, project, suite, testClass, testName) > 0;
    }

    @Transactional
    @PreAuthorize("hasRole('APPROVER')")
    public Quarantine create(JsonNode body) {
        if (body == null || !body.isObject()) throw bad("A JSON object is required");
        var names = body.fieldNames();
        while (names.hasNext()) if (!Set.of("project", "suite", "testClass", "testName", "reason", "days").contains(names.next())) throw bad("Unknown field");
        String project = text(body, "project", 60, true), suite = text(body, "suite", 60, true);
        String testClass = text(body, "testClass", 300, true), testName = text(body, "testName", 300, false), reason = text(body, "reason", 500, true);
        if (registry.project(project).isEmpty()) throw new RunException(HttpStatus.NOT_FOUND, "Project not found: " + project);
        if (!suite.matches("[a-z0-9][a-z0-9-]{1,59}")) throw bad("invalid suite key");
        if (reason.length() < 10) throw bad("reason must explain the quarantine (at least 10 characters)");
        if (!body.path("days").isInt() || body.get("days").intValue() < 1 || body.get("days").intValue() > MAX_DAYS) throw bad("days must be 1.." + MAX_DAYS);
        // An expired quarantine no longer applies; close it so the test can be quarantined again.
        jdbc.update("""
                UPDATE qa_quarantines SET released_at=expires_at, released_by='expiry'
                WHERE project_key=? AND suite_key=? AND test_class=? AND test_name=? AND released_at IS NULL AND expires_at <= clock_timestamp()
                """, project, suite, testClass, testName);
        UUID id = UUID.randomUUID();
        Instant expires = Instant.now().plus(Duration.ofDays(body.get("days").intValue()));
        try {
            jdbc.update("INSERT INTO qa_quarantines(id,project_key,suite_key,test_class,test_name,reason,created_by,expires_at) VALUES (?,?,?,?,?,?,?,?)",
                    id, project, suite, testClass, testName, reason, AuditService.actor(), Timestamp.from(expires));
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new RunException(HttpStatus.CONFLICT, "This test is already quarantined");
        }
        var state = new LinkedHashMap<String, Object>();
        state.put("testClass", testClass); state.put("testName", testName); state.put("reason", reason); state.put("expiresAt", expires.toString());
        audit("QUARANTINE_CREATED", project, suite, null, state);
        return get(id);
    }

    @Transactional
    @PreAuthorize("hasRole('APPROVER')")
    public Quarantine release(UUID id) {
        var q = get(id);
        if (q.releasedAt() == null) {
            jdbc.update("UPDATE qa_quarantines SET released_at=clock_timestamp(), released_by=? WHERE id=? AND released_at IS NULL", AuditService.actor(), id);
            var state = new LinkedHashMap<String, Object>();
            state.put("testClass", q.testClass()); state.put("testName", q.testName()); state.put("reason", q.reason());
            audit("QUARANTINE_RELEASED", q.projectKey(), q.suiteKey(), state, null);
        }
        return get(id);
    }

    public Quarantine get(UUID id) {
        var rows = jdbc.query("SELECT *, (released_at IS NULL AND expires_at > clock_timestamp()) AS active FROM qa_quarantines WHERE id=?", this::map, id);
        if (rows.isEmpty()) throw new RunException(HttpStatus.NOT_FOUND, "Quarantine not found: " + id);
        return rows.get(0);
    }

    private void audit(String action, String project, String suite, Object before, Object after) {
        try {
            jdbc.update("INSERT INTO qa_admin_events(actor,action,project_key,subject,before_state,after_state) VALUES (?,?,?,?,?::jsonb,?::jsonb)",
                    AuditService.actor(), action, project, suite, before == null ? null : mapper.writeValueAsString(before),
                    after == null ? null : mapper.writeValueAsString(after));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
    private Quarantine map(ResultSet r, int i) throws SQLException {
        return new Quarantine(r.getObject("id", UUID.class), r.getString("project_key"), r.getString("suite_key"), r.getString("test_class"),
                r.getString("test_name"), r.getString("reason"), r.getString("created_by"), instant(r, "created_at"), instant(r, "expires_at"),
                instant(r, "released_at"), r.getString("released_by"), r.getBoolean("active"));
    }
    private static Instant instant(ResultSet r, String k) throws SQLException { var t = r.getTimestamp(k); return t == null ? null : t.toInstant(); }
    /** Test names may legitimately be empty (a class-level failure), so {@code required=false} allows "" but not a missing field. */
    private static String text(JsonNode body, String field, int max, boolean required) {
        JsonNode v = body.get(field);
        if (v == null || !v.isTextual() || v.textValue().length() > max || v.textValue().chars().anyMatch(Character::isISOControl)
                || (required && v.textValue().isBlank())) {
            throw bad(field + " is required text up to " + max + " characters");
        }
        return v.textValue().strip();
    }
    private static RunException bad(String m) { return new RunException(HttpStatus.BAD_REQUEST, m); }
}
