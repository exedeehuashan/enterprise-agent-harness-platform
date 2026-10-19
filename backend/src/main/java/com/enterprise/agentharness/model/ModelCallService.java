package com.enterprise.agentharness.model;

import com.enterprise.agentharness.governance.AuditService;
import com.enterprise.agentharness.run.RunException;
import com.enterprise.agentharness.run.StartRunRequest;
import com.enterprise.agentharness.task.TaskNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class ModelCallService {
    private final JdbcTemplate jdbc;
    private final ModelProvider provider;
    private final ModelConfiguration.Policy policy;
    private final PlanValidator validator;
    private final ObjectMapper mapper;
    public ModelCallService(JdbcTemplate jdbc,ModelProvider provider,ModelConfiguration.Policy policy,PlanValidator validator,ObjectMapper mapper) {
        this.jdbc=jdbc;this.provider=provider;this.policy=policy;this.validator=validator;this.mapper=mapper;
    }
    public record Page(java.util.List<ModelCallResponse> items,int page,int size,long totalElements,long totalPages) {}
    public record Budget(LocalDate utcDay,long dailyTokenBudget,long reservedTokens,long remainingTokens,boolean usageEstimated) {}
    public record Claim(ModelCallResponse call,UUID token) {}

    @Transactional
    public ModelCallResponse start(UUID taskId,String key,PlanRequest request) {
        StartRunRequest.validateKey(key);
        var tasks=jdbc.queryForList("SELECT title,description,version FROM agent_tasks WHERE id=? FOR UPDATE",taskId);
        if(tasks.isEmpty()) throw new TaskNotFoundException(taskId);
        var existing=jdbc.queryForList("SELECT id,max_output_tokens,timeout_millis FROM model_calls WHERE task_id=? AND idempotency_key=?",taskId,key);
        if(!existing.isEmpty()) {
            var row=existing.get(0);
            if(((Number)row.get("max_output_tokens")).intValue()!=request.maxOutputTokens()
                    || ((Number)row.get("timeout_millis")).intValue()!=request.timeoutMillis()) throw conflict("Planning key already used for a different request");
            return get((UUID)row.get("id"));
        }
        if(jdbc.queryForObject("SELECT count(*) FROM model_calls WHERE task_id=? AND status IN ('QUEUED','RUNNING')",Long.class,taskId)!=0) throw conflict("Task already has an active planning call");
        var task=tasks.get(0);
        String title=(String)task.get("title"),description=(String)task.get("description");
        long version=((Number)task.get("version")).longValue();
        long reserved=new ModelProvider.Input(title,description).estimatedTokens()+request.maxOutputTokens();
        LocalDate day=utcDay();
        jdbc.update("INSERT INTO model_daily_budgets(budget_day) VALUES (?) ON CONFLICT DO NOTHING",day);
        long used=jdbc.queryForObject("SELECT reserved_tokens FROM model_daily_budgets WHERE budget_day=? FOR UPDATE",Long.class,day);
        if(used>policy.dailyTokenBudget()-reserved) throw new RunException(HttpStatus.TOO_MANY_REQUESTS,"Daily model token reservation exhausted");
        jdbc.update("UPDATE model_daily_budgets SET reserved_tokens=reserved_tokens+?,request_count=request_count+1 WHERE budget_day=?",reserved,day);
        UUID id=UUID.randomUUID();
        String digest=digest(taskId,title,description,version,request);
        jdbc.update("""
                INSERT INTO model_calls(id,task_id,idempotency_key,provider,model,requested_by,status,snapshot_title,snapshot_description,
                    snapshot_task_version,input_digest,max_output_tokens,timeout_millis,reserved_tokens,budget_day,deadline_at)
                VALUES (?,?,?,?,?,?,'QUEUED',?,?,?,?,?,?,?,?,clock_timestamp()+interval '60 seconds')
                """,id,taskId,key,provider.id(),provider.model(),AuditService.actor(),title,description,version,digest,
                request.maxOutputTokens(),request.timeoutMillis(),reserved,day);
        return get(id);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ModelCallResponse get(UUID id) {
        var rows=jdbc.query("SELECT * FROM model_calls WHERE id=?",this::map,id);
        if(rows.isEmpty()) throw new RunException(HttpStatus.NOT_FOUND,"Model call not found: "+id);
        return rows.get(0);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page history(UUID taskId,int page,int size) {
        if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_tasks WHERE id=?)",Boolean.class,taskId)) throw new TaskNotFoundException(taskId);
        long total=jdbc.queryForObject("SELECT count(*) FROM model_calls WHERE task_id=?",Long.class,taskId);
        return new Page(jdbc.query("SELECT * FROM model_calls WHERE task_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                this::map,taskId,size,(long)page*size),page,size,total,(total+size-1)/size);
    }
    public Budget budget() {
        LocalDate day=utcDay();
        var used=jdbc.queryForList("SELECT reserved_tokens FROM model_daily_budgets WHERE budget_day=?",Long.class,day);
        long reserved=used.isEmpty()?0:used.get(0);
        return new Budget(day,policy.dailyTokenBudget(),reserved,Math.max(0,policy.dailyTokenBudget()-reserved),true);
    }

    @Transactional
    public Claim claim() {
        UUID token=UUID.randomUUID();
        var ids=jdbc.queryForList("""
                WITH candidate AS (
                    SELECT id FROM model_calls WHERE status='QUEUED' AND deadline_at>clock_timestamp()
                    ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1
                ) UPDATE model_calls c SET status='RUNNING',claim_token=?,started_at=clock_timestamp(),
                    deadline_at=clock_timestamp()+(timeout_millis*interval '1 millisecond')
                FROM candidate q WHERE c.id=q.id RETURNING c.id
                """,UUID.class,token);
        return ids.isEmpty()?null:new Claim(get(ids.get(0)),token);
    }

    @Transactional
    public void complete(Claim claim,ModelProvider.Result result) {
        var row=owned(claim);if(row==null) return;
        if(expired(claim.call().id())) {failure(claim.call().id(),"PROVIDER_TIMEOUT");return;}
        if(result==null) {failure(claim.call().id(),"PROVIDER_ERROR");return;}
        long reserved=((Number)row.get("reserved_tokens")).longValue();
        int max=((Number)row.get("max_output_tokens")).intValue();
        if(result.inputTokens()<0 || result.outputTokens()<0 || result.inputTokens()>reserved-result.outputTokens()) {
            failure(claim.call().id(),"USAGE_INVALID");return;
        }
        if(result.outputTokens()>max || (result.json()!=null && (result.json().length()>8192
                || (result.json().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+3L)/4>max))) {
            failure(claim.call().id(),"OUTPUT_LIMIT");return;
        }
        jdbc.update("UPDATE model_calls SET input_tokens=?,output_tokens=? WHERE id=?",result.inputTokens(),result.outputTokens(),claim.call().id());
        com.fasterxml.jackson.databind.JsonNode plan;
        try {plan=validator.validate(result.json());}
        catch(ModelFailure ex) {failure(claim.call().id(),ex.code());return;}
        if(expired(claim.call().id())) {failure(claim.call().id(),"PROVIDER_TIMEOUT");return;}
        jdbc.update("UPDATE model_calls SET status='VALIDATED',plan=?::jsonb,finished_at=clock_timestamp(),claim_token=NULL WHERE id=?",plan.toString(),claim.call().id());
    }
    @Transactional
    public void fail(Claim claim,String code) {if(owned(claim)!=null) failure(claim.call().id(),code);}

    @Transactional
    public void recover() {
        var rows=jdbc.queryForList("SELECT id,status FROM model_calls WHERE status IN ('QUEUED','RUNNING') AND deadline_at<=clock_timestamp() ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 50");
        for(var row:rows) failure((UUID)row.get("id"),row.get("status").equals("QUEUED")?"QUEUE_TIMEOUT":"PROVIDER_TIMEOUT");
    }
    private Map<String,Object> owned(Claim claim) {
        var rows=jdbc.queryForList("SELECT * FROM model_calls WHERE id=? FOR UPDATE",claim.call().id());
        if(rows.isEmpty() || !rows.get(0).get("status").equals("RUNNING") || !claim.token().equals(rows.get(0).get("claim_token"))) return null;
        return rows.get(0);
    }
    private boolean expired(UUID id) {return jdbc.queryForObject("SELECT deadline_at<=clock_timestamp() FROM model_calls WHERE id=?",Boolean.class,id);}
    private void failure(UUID id,String code) {
        if(!Set.of("INVALID_PLAN","USAGE_INVALID","OUTPUT_LIMIT","QUEUE_TIMEOUT","PROVIDER_TIMEOUT","CALL_INTERRUPTED","PROVIDER_BUSY","PROVIDER_ERROR").contains(code)) code="PROVIDER_ERROR";
        jdbc.update("UPDATE model_calls SET status='FAILED',error_code=?,finished_at=clock_timestamp(),claim_token=NULL WHERE id=?",code,id);
    }
    private LocalDate utcDay() {return jdbc.queryForObject("SELECT (clock_timestamp() AT TIME ZONE 'UTC')::date",LocalDate.class);}
    private String digest(UUID task,String title,String description,long version,PlanRequest request) {
        try {
            byte[] input=mapper.writeValueAsBytes(Arrays.asList(task.toString(),title,description,version,provider.id(),provider.model(),request.maxOutputTokens(),request.timeoutMillis()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        }catch(Exception ex){throw new IllegalStateException("Cannot calculate planning digest",ex);}
    }
    private ModelCallResponse map(ResultSet r,int i) throws SQLException {
        com.fasterxml.jackson.databind.JsonNode plan=null;
        if(r.getString("plan")!=null) try {plan=mapper.readTree(r.getString("plan"));} catch(Exception ex){throw new SQLException("Invalid stored plan",ex);}
        return new ModelCallResponse(r.getObject("id",UUID.class),r.getObject("task_id",UUID.class),r.getString("provider"),r.getString("model"),r.getString("requested_by"),r.getString("status"),
                r.getString("snapshot_title"),r.getString("snapshot_description"),r.getLong("snapshot_task_version"),r.getString("input_digest"),r.getInt("max_output_tokens"),r.getInt("timeout_millis"),
                r.getLong("reserved_tokens"),r.getObject("input_tokens",Long.class),r.getObject("output_tokens",Long.class),r.getBoolean("usage_estimated"),r.getBigDecimal("cost_usd"),r.getObject("budget_day",LocalDate.class),
                instant(r,"created_at"),instant(r,"started_at"),instant(r,"finished_at"),instant(r,"deadline_at"),r.getString("error_code"),plan);
    }
    private static Instant instant(ResultSet r,String key) throws SQLException {var value=r.getTimestamp(key);return value==null?null:value.toInstant();}
    private static RunException conflict(String text) {return new RunException(HttpStatus.CONFLICT,text);}
}
