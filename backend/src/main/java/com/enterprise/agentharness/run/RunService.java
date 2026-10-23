package com.enterprise.agentharness.run;

import com.enterprise.agentharness.task.TaskNotFoundException;
import com.enterprise.agentharness.governance.AuditService;
import com.enterprise.agentharness.governance.ToolCatalog;
import com.enterprise.agentharness.artifact.ArtifactStore;
import com.enterprise.agentharness.tool.CanonicalJson;
import com.enterprise.agentharness.tool.ToolRegistry;
import com.enterprise.agentharness.tool.ToolResult;
import java.util.Objects;
import java.util.Set;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class RunService {
    private final JdbcTemplate jdbc;
    private final ToolCatalog catalog;
    private final AuditService audit;
    private final ToolRegistry registry;
    private final ArtifactStore artifacts;
    public RunService(JdbcTemplate jdbc, ToolCatalog catalog, AuditService audit, ToolRegistry registry, ArtifactStore artifacts) {
        this.jdbc=jdbc;this.catalog=catalog;this.audit=audit;this.registry=registry;this.artifacts=artifacts;
    }
    private static final int MAX_OUTPUT_CHARS = 65536;
    private static final Set<String> RESERVED_CODES = Set.of("WORKER_LOST","DEADLINE_EXCEEDED","DISPATCH_NOT_AUTHORIZED","POLICY_CHANGED","TOOL_FAILED");

    @Transactional
    public RunResponse start(UUID taskId, String key, StartRunRequest request) { return start(taskId, key, request, AuditService.actor()); }

    /** The actor is explicit so trusted plan advancement can launch runs outside a request context. */
    @Transactional
    public RunResponse start(UUID taskId, String key, StartRunRequest request, String actor) {
        StartRunRequest.validateKey(key);
        String arguments = normalizedArguments(request);
        var tasks = jdbc.queryForList("SELECT title,description FROM agent_tasks WHERE id=? FOR UPDATE", taskId);
        if (tasks.isEmpty()) throw new TaskNotFoundException(taskId);
        var existing = jdbc.queryForList("SELECT id,tool,timeout_seconds,arguments FROM agent_runs WHERE task_id=? AND idempotency_key=?", taskId, key);
        if (!existing.isEmpty()) {
            var row = existing.get(0);
            if (!row.get("tool").equals(request.tool()) || ((Number) row.get("timeout_seconds")).intValue() != request.timeoutSeconds()
                    || !Objects.equals(row.get("arguments"), arguments)) {
                throw conflict("Idempotency-Key already used for a different request");
            }
            return get((UUID) row.get("id"));
        }
        if (jdbc.queryForObject("SELECT count(*) FROM agent_runs WHERE task_id=? AND status IN ('QUEUED','RUNNING','WAITING_APPROVAL')", Long.class, taskId) != 0) {
            throw conflict("Task already has an active run");
        }
        var policy = catalog.require(request.tool());
        UUID id = UUID.randomUUID();
        var task = tasks.get(0);
        String initial = policy.requiresApproval() ? "WAITING_APPROVAL" : "QUEUED";
        int initialTimeout = policy.requiresApproval() ? 1800 : request.timeoutSeconds();
        jdbc.update("""
                INSERT INTO agent_runs(id,task_id,idempotency_key,timeout_seconds,tool,snapshot_title,snapshot_description,status,deadline_at,tool_version,target,arguments)
                VALUES (?,?,?,?,?,?,?,?,clock_timestamp() + (? * interval '1 second'),?,?,?)
                """, id, taskId, key, request.timeoutSeconds(), request.tool(), task.get("title"), task.get("description"), initial, initialTimeout, policy.version(), policy.target(), arguments);
        jdbc.update("INSERT INTO run_steps(run_id,sequence,tool,status) VALUES (?,1,?,?)", id, request.tool(), initial);
        audit.record(actor,"RUN_CREATED",id,request.tool());
        if (policy.requiresApproval()) {
            String digest=catalog.digest(id,taskId,request.tool(),policy.version(),policy.target(),(String)task.get("title"),(String)task.get("description"),request.timeoutSeconds(),arguments);
            jdbc.update("INSERT INTO approval_requests(id,run_id,input_digest,status,expires_at) VALUES (?,?,?,'PENDING',clock_timestamp()+interval '30 minutes')",UUID.randomUUID(),id,digest);
            audit.record(actor,"APPROVAL_REQUESTED",id,null);
        }
        updateTask(taskId, "RUNNING");
        return get(id);
    }

    /** Validated by the tool's own executor and stored as canonical JSON, so replays and digests are stable. */
    private String normalizedArguments(StartRunRequest request) {
        var executor = registry.find(request.tool()).orElseThrow(() -> new RunException(HttpStatus.BAD_REQUEST, "Tool is not allowed"));
        com.fasterxml.jackson.databind.JsonNode normalized;
        try { normalized = executor.normalize(request.arguments()); }
        catch (IllegalArgumentException ex) { throw new RunException(HttpStatus.BAD_REQUEST, "Invalid arguments: " + ex.getMessage()); }
        try { executor.checkTimeout(normalized, request.timeoutSeconds()); }
        catch (IllegalArgumentException ex) { throw new RunException(HttpStatus.BAD_REQUEST, ex.getMessage()); }
        String text = CanonicalJson.write(normalized);
        if (text != null && text.length() > 16384) throw new RunException(HttpStatus.BAD_REQUEST, "Arguments are too large");
        return text;
    }

    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public RunResponse get(UUID id) {
        var runs = jdbc.query("SELECT * FROM agent_runs WHERE id=?", this::mapRun, id);
        if (runs.isEmpty()) throw new RunException(HttpStatus.NOT_FOUND, "Run not found: " + id);
        return runs.get(0);
    }

    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public RunPageResponse history(UUID taskId, int page, int size) {
        if (!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_tasks WHERE id=?)", Boolean.class, taskId)) throw new TaskNotFoundException(taskId);
        long count = jdbc.queryForObject("SELECT count(*) FROM agent_runs WHERE task_id=?", Long.class, taskId);
        var items = jdbc.query("SELECT * FROM agent_runs WHERE task_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                this::mapRun, taskId, size, (long) page * size);
        return new RunPageResponse(items, page, size, count, (count + size - 1) / size);
    }

    @Transactional
    public RunResponse cancel(UUID id) {
        UUID taskId = taskId(id);
        lockTask(taskId);
        var row = lockedRun(id);
        String status = (String) row.get("status");
        if (status.equals("QUEUED") || status.equals("WAITING_APPROVAL")) terminal(id, taskId, "CANCELLED", null, null);
        else if (status.equals("RUNNING") && !(Boolean)row.get("cancel_requested")) {
            jdbc.update("UPDATE agent_runs SET cancel_requested=TRUE WHERE id=?", id);
            audit.record(AuditService.actor(),"CANCEL_REQUESTED",id,null);
        }
        return get(id);
    }

    /** A fresh token fences completion from any older claim. Claim commits before tool execution. */
    @Transactional
    public Claim claim() {
        UUID token = UUID.randomUUID();
        var rows = jdbc.queryForList("""
                WITH candidate AS (
                    SELECT id FROM agent_runs WHERE status='QUEUED' AND deadline_at>clock_timestamp()
                    ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE agent_runs r SET status='RUNNING',claim_token=?,lease_until=clock_timestamp()+interval '10 seconds',
                    started_at=COALESCE(started_at,clock_timestamp()),attempt_count=attempt_count+1
                FROM candidate c WHERE r.id=c.id RETURNING r.id
                """, token);
        if (rows.isEmpty()) return null;
        UUID id = (UUID) rows.get(0).get("id");
        jdbc.update("UPDATE run_steps SET status='RUNNING',started_at=clock_timestamp(),finished_at=NULL,output=NULL WHERE run_id=?", id);
        audit.record("worker","RUN_CLAIMED",id,null);
        return new Claim(get(id), token);
    }

    /** Called immediately before dispatch; consumes one approval and records one attempt. */
    @Transactional
    public boolean authorize(Claim claim) {
        UUID id=claim.run().id();UUID taskId=taskId(id);lockTask(taskId);
        var row=lockedRun(id);
        if (!"RUNNING".equals(row.get("status")) || !claim.token().equals(row.get("claim_token"))) return false;
        if (!jdbc.queryForObject("SELECT lease_until>clock_timestamp() FROM agent_runs WHERE id=?",Boolean.class,id)) return false;
        if ((Boolean)row.get("cancel_requested")) {terminal(id,taskId,"CANCELLED",null,null);return false;}
        if (expired(id)) {terminal(id,taskId,"TIMED_OUT","DEADLINE_EXCEEDED",null);return false;}
        var run=get(id);
        ToolCatalog.ToolDefinition policy;
        try {policy=catalog.require(run.tool());}
        catch (RunException ex) {invalidate(id,taskId,"POLICY_CHANGED");return false;}
        if (policy.version()!=run.toolVersion() || !policy.target().equals(run.target())) {invalidate(id,taskId,"POLICY_CHANGED");return false;}
        if (run.approvalId()!=null && !policy.requiresApproval()) {invalidate(id,taskId,"APPROVAL_INVALIDATED");return false;}
        if (jdbc.queryForObject("SELECT count(*) FROM tool_invocations WHERE claim_token=?",Long.class,claim.token())!=0) return false;
        if (policy.requiresApproval()) {
            var approvals=jdbc.queryForList("SELECT *,expires_at>clock_timestamp() AS valid FROM approval_requests WHERE run_id=? FOR UPDATE",id);
            if (approvals.isEmpty()) {invalidate(id,taskId,"APPROVAL_REQUIRED");return false;}
            var approval=approvals.get(0);
            if (!(Boolean)approval.get("valid")) {terminal(id,taskId,"EXPIRED","APPROVAL_EXPIRED",null);return false;}
            if (!"APPROVED".equals(approval.get("status")) || !run.inputDigest().equals(approval.get("input_digest"))) {
                invalidate(id,taskId,"APPROVAL_INVALIDATED");return false;
            }
            jdbc.update("UPDATE approval_requests SET status='CONSUMED',execution_token=? WHERE run_id=?",claim.token(),id);
            audit.record("worker","APPROVAL_CONSUMED",id,null);
        }
        jdbc.update("INSERT INTO tool_invocations(id,run_id,attempt,claim_token,input_digest,status) VALUES (?,?,?,?,?,'STARTED')",
                UUID.randomUUID(),id,run.attemptCount(),claim.token(),run.inputDigest());
        audit.record("worker","TOOL_DISPATCHED",id,run.tool());
        return true;
    }

    private void invalidate(UUID id,UUID taskId,String code) {
        jdbc.update("UPDATE approval_requests SET status='INVALIDATED' WHERE run_id=? AND status IN ('PENDING','APPROVED')",id);
        terminal(id,taskId,"FAILED",code,null);
    }

    @Transactional
    public void closeForApproval(UUID id,String outcome,String code) {
        if (!List.of("REJECTED","EXPIRED","FAILED").contains(outcome)) throw conflict("Invalid approval outcome");
        UUID taskId=taskId(id);lockTask(taskId);var row=lockedRun(id);
        if (!List.of("WAITING_APPROVAL","QUEUED").contains(row.get("status"))) throw conflict("Run is no longer awaiting authorization");
        terminal(id,taskId,outcome,code,null);
    }

    @Transactional
    public void finish(UUID id, UUID token, String output, boolean failed) {
        finish(id, token, failed ? ToolResult.failure("TOOL_FAILED", null) : ToolResult.success(output));
    }

    /**
     * Publishes a tool result only for the current, unexpired claim. Valid artifacts are stored with the
     * invocation even when cancellation or the deadline decides the final status.
     */
    @Transactional
    public void finish(UUID id, UUID token, ToolResult result) {
        UUID taskId = taskId(id);
        lockTask(taskId);
        var row = lockedRun(id);
        if (!"RUNNING".equals(row.get("status")) || !token.equals(row.get("claim_token"))) return;
        // An expired owner may not publish results even before the recovery scan notices it.
        if (!jdbc.queryForObject("SELECT lease_until>clock_timestamp() FROM agent_runs WHERE id=?", Boolean.class, id)) return;
        var invocations = jdbc.queryForList("SELECT id FROM tool_invocations WHERE claim_token=? AND status='STARTED'", UUID.class, token);
        if (invocations.isEmpty()) {
            terminal(id,taskId,"FAILED","DISPATCH_NOT_AUTHORIZED",null);return;
        }
        String artifactProblem = artifacts.problem(result.artifacts());
        if (artifactProblem == null) artifacts.store(id, ((Number) row.get("attempt_count")).intValue(), invocations.get(0), result.artifacts());
        String output = bounded(result.output());
        if ((Boolean) row.get("cancel_requested")) terminal(id, taskId, "CANCELLED", null, null);
        else if (expired(id)) terminal(id, taskId, "TIMED_OUT", "DEADLINE_EXCEEDED", null);
        else if (artifactProblem != null) terminal(id, taskId, "FAILED", artifactProblem, null);
        else if (result.succeeded()) terminal(id, taskId, "COMPLETED", null, output);
        else terminal(id, taskId, "FAILED", failureCode(result.code()), output);
    }

    /** Keeps a long-running tool's lease alive; an expired or superseded claim is never revived. */
    @Transactional
    public boolean renewLease(Claim claim) {
        return jdbc.update("""
                UPDATE agent_runs SET lease_until=clock_timestamp()+interval '10 seconds'
                WHERE id=? AND claim_token=? AND status='RUNNING' AND lease_until>clock_timestamp()
                """, claim.run().id(), claim.token()) == 1;
    }

    /** True once the tool should stop: cancellation, deadline, or this claim no longer owning the run. */
    public boolean stopRequested(Claim claim) {
        var rows = jdbc.queryForList("""
                SELECT cancel_requested OR status<>'RUNNING' OR claim_token IS DISTINCT FROM ? OR deadline_at<=clock_timestamp()
                FROM agent_runs WHERE id=?
                """, Boolean.class, claim.token(), claim.run().id());
        return rows.isEmpty() || rows.get(0);
    }

    private static String bounded(String output) {
        if (output == null || output.length() <= MAX_OUTPUT_CHARS) return output;
        return output.substring(0, MAX_OUTPUT_CHARS) + "\n[truncated]";
    }
    /** Tools choose their own failure codes but cannot impersonate platform or approval decisions. */
    private static String failureCode(String code) {
        boolean allowed = code != null && code.matches("[A-Z][A-Z0-9_]{1,39}") && !RESERVED_CODES.contains(code)
                && !code.startsWith("APPROVAL_") && !code.startsWith("ARTIFACT_");
        return allowed ? code : "TOOL_FAILED";
    }

    /** Only this side-effect-free tool may be retried, with at most two claims in total. */
    @Transactional
    public void recover() {
        var ids = jdbc.queryForList("""
                SELECT id FROM agent_runs WHERE (status IN ('QUEUED','WAITING_APPROVAL') AND deadline_at<=clock_timestamp())
                OR (status='RUNNING' AND (deadline_at<=clock_timestamp() OR lease_until<=clock_timestamp()))
                ORDER BY task_id,id LIMIT 50
                """, UUID.class);
        for (UUID id : ids) {
            UUID taskId = taskId(id);
            lockTask(taskId);
            var row = lockedRun(id);
            String status = (String) row.get("status");
            if (!List.of("QUEUED","RUNNING","WAITING_APPROVAL").contains(status)) continue;
            if ((Boolean) row.get("cancel_requested")) terminal(id, taskId, "CANCELLED", null, null);
            else if (expired(id)) terminal(id, taskId, status.equals("WAITING_APPROVAL")?"EXPIRED":"TIMED_OUT",
                    status.equals("WAITING_APPROVAL")?"APPROVAL_EXPIRED":"DEADLINE_EXCEEDED", null);
            else if (status.equals("RUNNING") && jdbc.queryForObject("SELECT lease_until<=clock_timestamp() FROM agent_runs WHERE id=?", Boolean.class, id)) {
                var policy = catalog.list().stream().filter(t->t.name().equals(row.get("tool"))).findFirst().orElse(null);
                var executor = registry.find((String) row.get("tool")).orElse(null);
                if (policy==null || executor==null || !executor.retryable() || !policy.retryable()) {
                    terminal(id,taskId,"NEEDS_REVIEW","WORKER_LOST",null);continue;
                }
                if (((Number) row.get("attempt_count")).intValue() >= 2) terminal(id, taskId, "FAILED", "WORKER_LOST", null);
                else {
                    jdbc.update("UPDATE tool_invocations SET status='LOST',finished_at=clock_timestamp(),error_code='WORKER_LOST' WHERE run_id=? AND status='STARTED'",id);
                    audit.record("worker","RUN_REQUEUED",id,"WORKER_LOST");
                    jdbc.update("UPDATE agent_runs SET status='QUEUED',claim_token=NULL,lease_until=NULL WHERE id=?", id);
                    jdbc.update("UPDATE run_steps SET status='QUEUED',started_at=NULL,finished_at=NULL,output=NULL WHERE run_id=?", id);
                }
            }
        }
    }

    private boolean expired(UUID id) { return jdbc.queryForObject("SELECT deadline_at<=clock_timestamp() FROM agent_runs WHERE id=?", Boolean.class, id); }
    private UUID taskId(UUID id) {
        var rows = jdbc.queryForList("SELECT task_id FROM agent_runs WHERE id=?", UUID.class, id);
        if (rows.isEmpty()) throw new RunException(HttpStatus.NOT_FOUND, "Run not found: " + id);
        return rows.get(0);
    }
    private void lockTask(UUID id) { jdbc.queryForList("SELECT id FROM agent_tasks WHERE id=? FOR UPDATE", id); }
    private Map<String,Object> lockedRun(UUID id) { return jdbc.queryForMap("SELECT * FROM agent_runs WHERE id=? FOR UPDATE", id); }
    private void terminal(UUID id, UUID taskId, String status, String error, String output) {
        jdbc.update("UPDATE agent_runs SET status=?,error_code=?,finished_at=clock_timestamp(),lease_until=NULL,claim_token=NULL WHERE id=?", status, error, id);
        jdbc.update("UPDATE run_steps SET status=?,finished_at=clock_timestamp(),output=? WHERE run_id=?", status, output, id);
        jdbc.update("UPDATE tool_invocations SET status=?,finished_at=clock_timestamp(),error_code=? WHERE run_id=? AND status='STARTED'",
                status.equals("NEEDS_REVIEW")?"UNKNOWN":List.of("COMPLETED","CANCELLED","TIMED_OUT").contains(status)?status:"FAILED",error,id);
        String approvalStatus = switch(status) {case "REJECTED"->"REJECTED";case "EXPIRED","TIMED_OUT"->"EXPIRED";case "CANCELLED"->"CANCELLED";default->"INVALIDATED";};
        jdbc.update("UPDATE approval_requests SET status=? WHERE run_id=? AND status IN ('PENDING','APPROVED')",approvalStatus,id);
        audit.record(AuditService.actor().equals("system")?"worker":AuditService.actor(),"RUN_"+status,id,error);
        updateTask(taskId, status.equals("COMPLETED") ? "COMPLETED" : "FAILED");
    }
    private void updateTask(UUID id, String status) {
        jdbc.update("UPDATE agent_tasks SET status=?,updated_at=GREATEST(clock_timestamp(),updated_at+interval '1 microsecond'),version=version+1 WHERE id=?", status, id);
    }
    private RunResponse mapRun(ResultSet rs, int index) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        List<RunResponse.Step> steps = jdbc.query("SELECT * FROM run_steps WHERE run_id=? ORDER BY sequence", (s,i) ->
                new RunResponse.Step(s.getInt("sequence"),s.getString("tool"),s.getString("status"),instant(s,"started_at"),instant(s,"finished_at"),s.getString("output")), id);
        return new RunResponse(id,rs.getObject("task_id",UUID.class),rs.getString("tool"),rs.getString("status"),
                rs.getString("snapshot_title"),rs.getString("snapshot_description"),rs.getInt("timeout_seconds"),
                instant(rs,"created_at"),instant(rs,"started_at"),instant(rs,"finished_at"),instant(rs,"deadline_at"),
                rs.getInt("attempt_count"),rs.getBoolean("cancel_requested"),rs.getString("error_code"),steps,rs.getInt("tool_version"),rs.getString("target"),
                catalog.digest(id,rs.getObject("task_id",UUID.class),rs.getString("tool"),rs.getInt("tool_version"),rs.getString("target"),rs.getString("snapshot_title"),rs.getString("snapshot_description"),rs.getInt("timeout_seconds"),rs.getString("arguments")),
                approvalId(id),CanonicalJson.read(rs.getString("arguments")));
    }
    private UUID approvalId(UUID id) {
        var ids=jdbc.queryForList("SELECT id FROM approval_requests WHERE run_id=?",UUID.class,id);
        return ids.isEmpty()?null:ids.get(0);
    }
    private static Instant instant(ResultSet rs, String field) throws SQLException { var t=rs.getTimestamp(field); return t==null?null:t.toInstant(); }
    private static RunException conflict(String message) { return new RunException(HttpStatus.CONFLICT, message); }
    public record Claim(RunResponse run, UUID token) {}
}
