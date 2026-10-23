package com.enterprise.agentharness.qa;

import com.enterprise.agentharness.governance.AuditService;
import com.enterprise.agentharness.run.RunException;
import com.enterprise.agentharness.run.RunService;
import com.enterprise.agentharness.run.StartRunRequest;
import com.enterprise.agentharness.task.AgentTaskService;
import com.enterprise.agentharness.task.CreateTaskRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Runs QA suites without a person or a pull request: ON_CHANGE schedules run whenever the ref's head commit moves
 * (for example after a merge to main), CRON schedules on a UTC timetable (for example a nightly regression). Each
 * schedule has its own task; runs are created by the "scheduler" actor and keyed by schedule and commit (and slot
 * for CRON), so a schedule never runs the same thing twice. Docker suites still wait for approval.
 */
@Service
public class QaSchedules {
    private static final Logger log = LoggerFactory.getLogger(QaSchedules.class);
    static final String ACTOR = "scheduler";
    private final JdbcTemplate jdbc;
    private final QaRegistry registry;
    private final QaSuiteResolver resolver;
    private final AgentTaskService tasks;
    private final RunService runs;
    private final GitMirror git;

    public QaSchedules(JdbcTemplate jdbc, QaRegistry registry, QaSuiteResolver resolver, AgentTaskService tasks, RunService runs, GitMirror git) {
        this.jdbc = jdbc; this.registry = registry; this.resolver = resolver; this.tasks = tasks; this.runs = runs; this.git = git;
    }

    /** {@code environment} set: an API test collection ({@code suiteKey} is the collection) run against that environment. */
    public record Schedule(UUID id, String projectKey, String suiteKey, String ref, String kind, String cron, boolean enabled, UUID taskId,
            String lastCommit, UUID lastRunId, String lastRunStatus, Instant lastCheckedAt, String lastError, Instant nextRunAt, String environment) {}

    public List<Schedule> list(String project) {
        return jdbc.query("""
                SELECT s.*, r.status AS last_run_status FROM qa_schedules s LEFT JOIN agent_runs r ON r.id = s.last_run_id
                WHERE (?::text IS NULL OR s.project_key=?) ORDER BY s.project_key, s.suite_key, s.ref, s.kind, s.environment NULLS FIRST
                """, this::map, project, project);
    }

    /** One cycle: every enabled schedule that is due (CRON) or may have moved (ON_CHANGE). */
    public void runOnce() {
        for (var s : list(null)) {
            if (!s.enabled()) continue;
            if (s.kind().equals("CRON") && s.nextRunAt() != null && s.nextRunAt().isAfter(Instant.now())) continue;
            try {
                check(s);
            } catch (RuntimeException ex) {
                log.warn("Schedule {} failed", s.id(), ex);
                jdbc.update("UPDATE qa_schedules SET last_checked_at=clock_timestamp(), last_error='SCHEDULE_FAILED' WHERE id=?", s.id());
            }
        }
    }

    private void check(Schedule s) {
        QaSuiteResolver.Resolved resolved = null;
        String commit;
        try {
            if (s.environment() == null) {
                resolved = resolver.resolve(s.projectKey(), s.suiteKey(), s.ref());
                commit = resolved.commit();
            } else {
                // A suite that runs against an environment, or else an API test collection; both are validated when the run starts.
                try {
                    resolved = resolver.resolve(s.projectKey(), s.suiteKey(), s.ref());
                    if (!resolved.suite().needsEnvironment()) resolved = null;
                } catch (IllegalArgumentException notASuite) {
                    resolved = null;
                }
                commit = resolved != null ? resolved.commit() : git.resolve(resolver.enabledProject(s.projectKey()), s.ref());
            }
        } catch (IllegalArgumentException ex) {
            recordCheck(s, null, null, clip(ex.getMessage()));
            return;
        }
        Instant slot = null;
        if (s.kind().equals("CRON")) {
            slot = s.nextRunAt() == null ? Instant.now() : s.nextRunAt();
            jdbc.update("UPDATE qa_schedules SET next_run_at=? WHERE id=?", java.sql.Timestamp.from(next(s.cron(), Instant.now())), s.id());
            if (s.nextRunAt() == null) { recordCheck(s, s.lastCommit(), s.lastRunId(), null); return; } // first activation only plans the next slot
        } else if (commit.equals(s.lastCommit())) {
            recordCheck(s, commit, s.lastRunId(), null);
            return;
        }
        UUID taskId = s.taskId();
        if (taskId == null || jdbc.queryForObject("SELECT count(*) FROM agent_tasks WHERE id=?", Long.class, taskId) == 0) {
            String title = (s.kind().equals("CRON") ? "Scheduled " : "On change: ") + s.suiteKey() + " on " + s.projectKey() + "@" + s.ref()
                    + (s.environment() == null ? "" : " against " + s.environment());
            taskId = tasks.create(new CreateTaskRequest(PullRequestTriggers.clean(title, 255), "Created by EAHP schedule " + s.id() + ".")).id();
            jdbc.update("UPDATE qa_schedules SET task_id=? WHERE id=?", taskId, s.id());
        }
        if (jdbc.queryForObject("SELECT count(*) FROM agent_runs WHERE task_id=? AND status IN ('QUEUED','RUNNING','WAITING_APPROVAL')", Long.class, taskId) > 0) {
            // The previous run is still going; try again next cycle rather than queueing behind it.
            recordCheck(s, s.lastCommit(), s.lastRunId(), "PREVIOUS_RUN_ACTIVE");
            return;
        }
        var args = JsonNodeFactory.instance.objectNode().put("project", s.projectKey()).put("ref", commit);
        String tool;
        int timeout;
        if (resolved == null) {
            args.put("collection", s.suiteKey()).put("environment", s.environment());
            tool = "QA_RUN_API_TESTS";
            timeout = 600;
        } else {
            args.put("suite", s.suiteKey());
            if (s.environment() != null) args.put("environment", s.environment());
            tool = resolved.suite().dockerAccess() ? "QA_RUN_PRIVILEGED_SUITE" : "QA_RUN_SUITE";
            timeout = Math.min(1800, resolved.suite().maxTimeoutSeconds());
        }
        String key = "schedule-" + s.id().toString().substring(0, 8) + "-" + commit.substring(0, 12) + (slot == null ? "" : "-" + slot.getEpochSecond());
        try {
            var run = runs.start(taskId, key, new StartRunRequest(tool, timeout, args), ACTOR);
            recordCheck(s, commit, run.id(), null);
        } catch (RunException ex) {
            recordCheck(s, s.lastCommit(), s.lastRunId(), "START_REJECTED_" + ex.status().value());
        }
    }

    @PreAuthorize("hasRole('APPROVER')")
    public Schedule create(JsonNode body) {
        if (body == null || !body.isObject()) throw bad("A JSON object is required");
        var names = body.fieldNames();
        while (names.hasNext()) if (!Set.of("project", "suite", "ref", "kind", "cron", "enabled", "environment").contains(names.next())) throw bad("Unknown field");
        String project = text(body, "project"), suite = text(body, "suite"), kind = text(body, "kind");
        var p = registry.project(project).orElseThrow(() -> new RunException(HttpStatus.NOT_FOUND, "Project not found: " + project));
        if (!suite.matches("[a-z0-9][a-z0-9-]{1,59}")) throw bad("invalid suite key");
        String ref = body.has("ref") ? text(body, "ref") : p.defaultRef();
        if (!ref.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,99}") || ref.contains("..")) throw bad("ref must be a branch, tag or commit");
        if (!kind.equals("ON_CHANGE") && !kind.equals("CRON")) throw bad("kind must be ON_CHANGE or CRON");
        String cron = body.has("cron") ? text(body, "cron") : null;
        if (kind.equals("CRON")) {
            if (cron == null) throw bad("cron is required for CRON schedules");
            next(cron, Instant.now());
        } else if (cron != null) throw bad("cron is only allowed for CRON schedules");
        boolean enabled = !body.has("enabled") || body.get("enabled").asBoolean(true);
        String environment = body.has("environment") ? text(body, "environment") : null;
        if (environment != null && !environment.matches("[a-z0-9][a-z0-9-]{1,59}")) throw bad("invalid environment key");
        if (environment != null && jdbc.queryForObject("SELECT count(*) FROM qa_environments WHERE project_key=? AND key=?", Long.class, project, environment) == 0) {
            throw bad("unknown environment: " + environment);
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO qa_schedules(id,project_key,suite_key,ref,kind,cron,enabled,environment) VALUES (?,?,?,?,?,?,?,?)", id, project, suite, ref, kind, cron, enabled, environment);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new RunException(HttpStatus.CONFLICT, "This schedule already exists");
        }
        audit("SCHEDULE_CREATED", project, suite, null, "{\"ref\":\"" + ref + "\",\"kind\":\"" + kind + "\",\"enabled\":" + enabled
                + (environment == null ? "" : ",\"environment\":\"" + environment + "\"") + "}");
        return get(id);
    }

    @PreAuthorize("hasRole('APPROVER')")
    public Schedule setEnabled(UUID id, boolean enabled) {
        var s = get(id);
        if (s.enabled() != enabled) {
            jdbc.update("UPDATE qa_schedules SET enabled=? WHERE id=?", enabled, id);
            audit("SCHEDULE_" + (enabled ? "ENABLED" : "DISABLED"), s.projectKey(), s.suiteKey(), "{\"enabled\":" + s.enabled() + "}", "{\"enabled\":" + enabled + "}");
        }
        return get(id);
    }

    @PreAuthorize("hasRole('APPROVER')")
    public void delete(UUID id) {
        var s = get(id);
        jdbc.update("DELETE FROM qa_schedules WHERE id=?", id);
        audit("SCHEDULE_DELETED", s.projectKey(), s.suiteKey(), "{\"ref\":\"" + s.ref() + "\",\"kind\":\"" + s.kind() + "\"}", null);
    }

    public Schedule get(UUID id) {
        var rows = jdbc.query("SELECT s.*, r.status AS last_run_status FROM qa_schedules s LEFT JOIN agent_runs r ON r.id = s.last_run_id WHERE s.id=?", this::map, id);
        if (rows.isEmpty()) throw new RunException(HttpStatus.NOT_FOUND, "Schedule not found: " + id);
        return rows.get(0);
    }

    /** Next UTC firing after {@code from}; Spring's six-field cron (second minute hour day month weekday). */
    static Instant next(String cron, Instant from) {
        CronExpression expression;
        try { expression = CronExpression.parse(cron); } catch (IllegalArgumentException ex) { throw bad("cron must be a six-field expression, for example \"0 0 2 * * *\""); }
        var next = expression.next(ZonedDateTime.ofInstant(from, ZoneOffset.UTC));
        if (next == null) throw bad("cron never fires");
        if (expression.next(next).toInstant().getEpochSecond() - next.toInstant().getEpochSecond() < 900) throw bad("cron must not fire more often than every 15 minutes");
        return next.toInstant();
    }

    private void recordCheck(Schedule s, String commit, UUID runId, String error) {
        jdbc.update("UPDATE qa_schedules SET last_checked_at=clock_timestamp(), last_commit=?, last_run_id=?, last_error=? WHERE id=?", commit, runId, error, s.id());
    }
    private void audit(String action, String project, String subject, String before, String after) {
        jdbc.update("INSERT INTO qa_admin_events(actor,action,project_key,subject,before_state,after_state) VALUES (?,?,?,?,?::jsonb,?::jsonb)",
                AuditService.actor(), action, project, subject, before, after);
    }
    private Schedule map(ResultSet r, int i) throws SQLException {
        return new Schedule(r.getObject("id", UUID.class), r.getString("project_key"), r.getString("suite_key"), r.getString("ref"), r.getString("kind"),
                r.getString("cron"), r.getBoolean("enabled"), r.getObject("task_id", UUID.class), r.getString("last_commit"), r.getObject("last_run_id", UUID.class),
                r.getString("last_run_status"), instant(r, "last_checked_at"), r.getString("last_error"), instant(r, "next_run_at"), r.getString("environment"));
    }
    private static Instant instant(ResultSet r, String k) throws SQLException { var t = r.getTimestamp(k); return t == null ? null : t.toInstant(); }
    private static String text(JsonNode body, String field) {
        if (!body.path(field).isTextual() || body.get(field).textValue().isBlank() || body.get(field).textValue().length() > 100) throw bad(field + " is required text");
        return body.get(field).textValue().strip();
    }
    private static String clip(String s) { return s == null ? null : s.length() <= 200 ? s : s.substring(0, 200); }
    private static RunException bad(String m) { return new RunException(HttpStatus.BAD_REQUEST, m); }
}
