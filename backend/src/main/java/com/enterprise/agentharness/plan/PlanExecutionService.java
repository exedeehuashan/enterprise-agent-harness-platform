package com.enterprise.agentharness.plan;

import com.enterprise.agentharness.governance.AuditService;
import com.enterprise.agentharness.governance.ToolCatalog;
import com.enterprise.agentharness.model.ModelFailure;
import com.enterprise.agentharness.model.PlanValidator;
import com.enterprise.agentharness.run.RunException;
import com.enterprise.agentharness.run.RunService;
import com.enterprise.agentharness.run.StartRunRequest;
import com.enterprise.agentharness.task.TaskNotFoundException;
import com.enterprise.agentharness.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes a VALIDATED plan as an ordered sequence of ordinary runs, one per step.
 * Each step goes through RunService.start, so claim fencing, approval gates and audit
 * apply unchanged. A step is launched only after the previous run COMPLETED; any other
 * terminal outcome stops the execution. Nothing is retried or resumed automatically.
 */
@Service
@Transactional(readOnly=true)
public class PlanExecutionService {
    private final JdbcTemplate jdbc;
    private final RunService runs;
    private final ToolCatalog catalog;
    private final PlanValidator validator;
    private final AuditService audit;
    private final ToolRegistry registry;
    public PlanExecutionService(JdbcTemplate jdbc,RunService runs,ToolCatalog catalog,PlanValidator validator,AuditService audit,ToolRegistry registry) {
        this.jdbc=jdbc;this.runs=runs;this.catalog=catalog;this.validator=validator;this.audit=audit;this.registry=registry;
    }
    public record Page(List<PlanExecutionResponse> items,int page,int size,long totalElements,long totalPages) {}

    @Transactional
    public PlanExecutionResponse start(UUID callId,String key) {
        StartRunRequest.validateKey(key);
        var owners=jdbc.queryForList("SELECT task_id FROM model_calls WHERE id=?",UUID.class,callId);
        if(owners.isEmpty()) throw new RunException(HttpStatus.NOT_FOUND,"Model call not found: "+callId);
        UUID taskId=owners.get(0);
        lockTask(taskId);
        var call=jdbc.queryForMap("SELECT status,plan::text AS plan_text FROM model_calls WHERE id=? FOR UPDATE",callId);
        var existing=jdbc.queryForList("SELECT id,idempotency_key FROM plan_executions WHERE model_call_id=?",callId);
        if(!existing.isEmpty()) {
            if(!key.equals(existing.get(0).get("idempotency_key"))) throw conflict("Plan has already been executed");
            return get((UUID)existing.get(0).get("id"));
        }
        if(!"VALIDATED".equals(call.get("status"))) throw conflict("Only VALIDATED plans can be executed");
        JsonNode plan;
        try {plan=validator.validate((String)call.get("plan_text"));}
        catch(ModelFailure ex) {throw conflict("Plan no longer satisfies tool policy");}
        if(jdbc.queryForObject("SELECT count(*) FROM plan_executions WHERE task_id=? AND status='RUNNING'",Long.class,taskId)!=0) {
            throw conflict("Task already has an active plan execution");
        }
        UUID id=UUID.randomUUID();
        var first=plan.get("steps").get(0);
        String blocked=blocker(taskId,callId,id,1,first.get("tool").textValue(),first.get("toolVersion").intValue(),first.get("target").textValue());
        if(blocked!=null) throw conflict("Plan cannot start: "+blocked);
        jdbc.update("INSERT INTO plan_executions(id,task_id,model_call_id,idempotency_key,requested_by,plan_digest,status) VALUES (?,?,?,?,?,?,'RUNNING')",
                id,taskId,callId,key,AuditService.actor(),sha256(plan.toString()));
        int sequence=0;
        for(JsonNode step:plan.get("steps")) {
            jdbc.update("INSERT INTO plan_execution_steps(execution_id,sequence,tool,tool_version,target,timeout_seconds,status) VALUES (?,?,?,?,?,?,'PENDING')",
                    id,++sequence,step.get("tool").textValue(),step.get("toolVersion").intValue(),step.get("target").textValue(),step.get("timeoutSeconds").intValue());
        }
        launch(id,taskId,1,AuditService.actor());
        return get(id);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PlanExecutionResponse get(UUID id) {
        var rows=jdbc.query("SELECT * FROM plan_executions WHERE id=?",this::map,id);
        if(rows.isEmpty()) throw new RunException(HttpStatus.NOT_FOUND,"Plan execution not found: "+id);
        return rows.get(0);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page history(UUID taskId,int page,int size) {
        if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_tasks WHERE id=?)",Boolean.class,taskId)) throw new TaskNotFoundException(taskId);
        long total=jdbc.queryForObject("SELECT count(*) FROM plan_executions WHERE task_id=?",Long.class,taskId);
        return new Page(jdbc.query("SELECT * FROM plan_executions WHERE task_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                this::map,taskId,size,(long)page*size),page,size,total,(total+size-1)/size);
    }

    /** Stops the execution now; the current run is cancelled through the ordinary cooperative path. */
    @Transactional
    public PlanExecutionResponse cancel(UUID id) {
        var owners=jdbc.queryForList("SELECT task_id FROM plan_executions WHERE id=?",UUID.class,id);
        if(owners.isEmpty()) throw new RunException(HttpStatus.NOT_FOUND,"Plan execution not found: "+id);
        lockTask(owners.get(0));
        if("RUNNING".equals(lockedExecution(id).get("status"))) {
            finish(id,"CANCELLED",null);
            for(UUID run:jdbc.queryForList("SELECT run_id FROM plan_execution_steps WHERE execution_id=? AND status='LAUNCHED'",UUID.class,id)) runs.cancel(run);
            settleLaunched(id);
        }
        return get(id);
    }

    /** Trusted advancement: settles finished step runs and launches the next step when allowed. */
    @Transactional
    public void advance() {
        var rows=jdbc.queryForList("""
                SELECT s.execution_id,e.task_id FROM plan_execution_steps s
                JOIN agent_runs r ON r.id=s.run_id JOIN plan_executions e ON e.id=s.execution_id
                WHERE s.status='LAUNCHED' AND r.status NOT IN ('QUEUED','RUNNING','WAITING_APPROVAL')
                ORDER BY e.created_at,e.id LIMIT 20
                """);
        for(var row:rows) {
            UUID id=(UUID)row.get("execution_id"),taskId=(UUID)row.get("task_id");
            lockTask(taskId);
            var execution=lockedExecution(id);
            var settled=settleLaunched(id);
            if(settled.isEmpty() || !"RUNNING".equals(execution.get("status"))) continue;
            int sequence=((Number)settled.get(0).get("sequence")).intValue();
            String runStatus=(String)settled.get(0).get("run_status");
            if(!runStatus.equals("COMPLETED")) {finish(id,"FAILED","STEP_"+runStatus);continue;}
            var next=jdbc.queryForList("SELECT * FROM plan_execution_steps WHERE execution_id=? AND sequence=?",id,sequence+1);
            if(next.isEmpty()) {finish(id,"COMPLETED",null);continue;}
            var step=next.get(0);
            String blocked=blocker(taskId,(UUID)execution.get("model_call_id"),id,sequence+1,(String)step.get("tool"),
                    ((Number)step.get("tool_version")).intValue(),(String)step.get("target"));
            if(blocked!=null) finish(id,"FAILED",blocked);
            else launch(id,taskId,sequence+1,"worker");
        }
    }

    /** Checked before every launch, under the task lock, so RunService.start cannot fail and roll back the batch. */
    private String blocker(UUID taskId,UUID callId,UUID executionId,int sequence,String tool,int version,String target) {
        boolean unchanged=jdbc.queryForObject("""
                SELECT t.title=c.snapshot_title AND t.description IS NOT DISTINCT FROM c.snapshot_description
                FROM agent_tasks t JOIN model_calls c ON c.task_id=t.id WHERE c.id=?
                """,Boolean.class,callId);
        if(!unchanged) return "TASK_CHANGED";
        ToolCatalog.ToolDefinition policy;
        try {policy=catalog.require(tool);} catch(RunException ex) {return "POLICY_CHANGED";}
        if(policy.version()!=version || !policy.target().equals(target)) return "POLICY_CHANGED";
        if(!registry.acceptsNoArguments(tool)) return "ARGUMENTS_REQUIRED";
        if(jdbc.queryForObject("SELECT count(*) FROM agent_runs WHERE task_id=? AND (status IN ('QUEUED','RUNNING','WAITING_APPROVAL') OR idempotency_key=?)",
                Long.class,taskId,runKey(executionId,sequence))!=0) return "TASK_BUSY";
        return null;
    }
    private void launch(UUID id,UUID taskId,int sequence,String actor) {
        var step=jdbc.queryForMap("SELECT tool,timeout_seconds FROM plan_execution_steps WHERE execution_id=? AND sequence=?",id,sequence);
        var run=runs.start(taskId,runKey(id,sequence),new StartRunRequest((String)step.get("tool"),((Number)step.get("timeout_seconds")).intValue()),actor);
        jdbc.update("UPDATE plan_execution_steps SET status='LAUNCHED',run_id=? WHERE execution_id=? AND sequence=?",run.id(),id,sequence);
        audit.record(actor,"PLAN_STEP_LAUNCHED",run.id(),"STEP_"+sequence);
    }
    private List<Map<String,Object>> settleLaunched(UUID id) {
        return jdbc.queryForList("""
                UPDATE plan_execution_steps s SET status=CASE r.status WHEN 'COMPLETED' THEN 'COMPLETED' WHEN 'CANCELLED' THEN 'CANCELLED' ELSE 'FAILED' END
                FROM agent_runs r WHERE r.id=s.run_id AND s.execution_id=? AND s.status='LAUNCHED'
                AND r.status NOT IN ('QUEUED','RUNNING','WAITING_APPROVAL') RETURNING s.sequence,r.status AS run_status
                """,id);
    }
    private void finish(UUID id,String status,String code) {
        jdbc.update("UPDATE plan_executions SET status=?,error_code=?,finished_at=clock_timestamp() WHERE id=?",status,code,id);
        jdbc.update("UPDATE plan_execution_steps SET status='SKIPPED' WHERE execution_id=? AND status='PENDING'",id);
    }
    private void lockTask(UUID id) {jdbc.queryForList("SELECT id FROM agent_tasks WHERE id=? FOR UPDATE",id);}
    private Map<String,Object> lockedExecution(UUID id) {return jdbc.queryForMap("SELECT * FROM plan_executions WHERE id=? FOR UPDATE",id);}
    private static String runKey(UUID id,int sequence) {return "plan-"+id+"-"+sequence;}
    private static String sha256(String text) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception ex) {throw new IllegalStateException("Cannot calculate plan digest",ex);}
    }
    private PlanExecutionResponse map(ResultSet r,int i) throws SQLException {
        UUID id=r.getObject("id",UUID.class);
        var steps=jdbc.query("""
                SELECT s.*,a.status AS run_status FROM plan_execution_steps s LEFT JOIN agent_runs a ON a.id=s.run_id
                WHERE s.execution_id=? ORDER BY s.sequence
                """,(s,j)->new PlanExecutionResponse.Step(s.getInt("sequence"),s.getString("tool"),s.getInt("tool_version"),s.getString("target"),
                s.getInt("timeout_seconds"),s.getString("status"),s.getObject("run_id",UUID.class),s.getString("run_status")),id);
        return new PlanExecutionResponse(id,r.getObject("task_id",UUID.class),r.getObject("model_call_id",UUID.class),r.getString("status"),
                r.getString("requested_by"),r.getString("plan_digest"),r.getString("error_code"),instant(r,"created_at"),instant(r,"finished_at"),steps);
    }
    private static Instant instant(ResultSet r,String key) throws SQLException {var value=r.getTimestamp(key);return value==null?null:value.toInstant();}
    private static RunException conflict(String text) {return new RunException(HttpStatus.CONFLICT,text);}
}
