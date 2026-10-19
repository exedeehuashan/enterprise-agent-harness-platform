package com.enterprise.agentharness.governance;

import com.enterprise.agentharness.run.RunException;
import com.enterprise.agentharness.run.RunService;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class ApprovalService {
    private final JdbcTemplate jdbc;
    private final RunService runs;
    private final ToolCatalog catalog;
    private final AuditService audit;
    public ApprovalService(JdbcTemplate jdbc,RunService runs,ToolCatalog catalog,AuditService audit) {
        this.jdbc=jdbc;this.runs=runs;this.catalog=catalog;this.audit=audit;
    }
    public record Approval(UUID id,UUID runId,String inputDigest,String status,Instant requestedAt,Instant expiresAt,
            Instant decidedAt,String decidedBy,String reasonCode) {}
    public record Decision(String decision,String expectedDigest,String reasonCode) {
        public static Decision parse(JsonNode body) {
            if (body==null || !body.isObject()) throw invalid("A decision object is required");
            var fields=body.fieldNames();
            while(fields.hasNext()) if(!Set.of("decision","expectedDigest","reasonCode").contains(fields.next())) throw invalid("Unknown decision field");
            for(String field:Set.of("decision","expectedDigest","reasonCode")) if(!body.hasNonNull(field) || !body.get(field).isTextual()) throw invalid("Decision fields must be strings");
            String decision=body.get("decision").textValue(),digest=body.get("expectedDigest").textValue(),reason=body.get("reasonCode").textValue();
            if(!Set.of("APPROVE","REJECT").contains(decision) || !digest.matches("[a-f0-9]{64}")
                    || !Set.of("REVIEWED","RISK_NOT_ACCEPTED").contains(reason)) throw invalid("Invalid decision, digest or reasonCode");
            return new Decision(decision,digest,reason);
        }
    }
    public Approval get(UUID id) {
        var rows=jdbc.query("SELECT * FROM approval_requests WHERE id=?",this::map,id);
        if(rows.isEmpty()) throw new RunException(HttpStatus.NOT_FOUND,"Approval not found: "+id);
        return rows.get(0);
    }
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public GovernanceController.Page<Approval> list(int page,int size) {
        long count=jdbc.queryForObject("SELECT count(*) FROM approval_requests",Long.class);
        var items=jdbc.query("SELECT * FROM approval_requests ORDER BY requested_at DESC,id DESC LIMIT ? OFFSET ?",this::map,size,(long)page*size);
        return new GovernanceController.Page<>(items,page,size,count,(count+size-1)/size);
    }
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('APPROVER')")
    public Approval decide(UUID id,Decision decision) {
        var approval=get(id);
        var before=runs.get(approval.runId());
        jdbc.queryForList("SELECT id FROM agent_tasks WHERE id=? FOR UPDATE",before.taskId());
        jdbc.queryForList("SELECT id FROM agent_runs WHERE id=? FOR UPDATE",before.id());
        jdbc.queryForList("SELECT id FROM approval_requests WHERE id=? FOR UPDATE",id);
        approval=get(id);
        var run=runs.get(approval.runId());
        if(!approval.status().equals("PENDING") || !run.status().equals("WAITING_APPROVAL")) throw conflict("Approval is no longer pending");
        if(!approval.inputDigest().equals(decision.expectedDigest())) throw conflict("Decision digest does not match the approval");
        if(!jdbc.queryForObject("SELECT expires_at>clock_timestamp() FROM approval_requests WHERE id=?",Boolean.class,id)) {
            runs.closeForApproval(run.id(),"EXPIRED","APPROVAL_EXPIRED");
            return get(id);
        }
        ToolCatalog.ToolDefinition policy=null;
        try {policy=catalog.require(run.tool());} catch(RunException ignored) { }
        if(policy==null || !policy.requiresApproval() || policy.version()!=run.toolVersion()
                || !policy.target().equals(run.target()) || !run.inputDigest().equals(approval.inputDigest())) {
            jdbc.update("UPDATE approval_requests SET status='INVALIDATED' WHERE id=?",id);
            runs.closeForApproval(run.id(),"FAILED","APPROVAL_INVALIDATED");
            return get(id);
        }
        String status=decision.decision().equals("APPROVE")?"APPROVED":"REJECTED";
        jdbc.update("UPDATE approval_requests SET status=?,decided_at=clock_timestamp(),decided_by=?,reason_code=? WHERE id=?",
                status,AuditService.actor(),decision.reasonCode(),id);
        audit.record(AuditService.actor(),"APPROVAL_"+status,run.id(),decision.reasonCode());
        if(status.equals("REJECTED")) runs.closeForApproval(run.id(),"REJECTED","APPROVAL_REJECTED");
        else {
            jdbc.update("UPDATE agent_runs SET status='QUEUED',deadline_at=clock_timestamp()+(timeout_seconds*interval '1 second') WHERE id=?",run.id());
            jdbc.update("UPDATE run_steps SET status='QUEUED' WHERE run_id=?",run.id());
        }
        return get(id);
    }
    private Approval map(ResultSet r,int i) throws SQLException {
        return new Approval(r.getObject("id",UUID.class),r.getObject("run_id",UUID.class),r.getString("input_digest"),r.getString("status"),
                instant(r,"requested_at"),instant(r,"expires_at"),instant(r,"decided_at"),r.getString("decided_by"),r.getString("reason_code"));
    }
    private static Instant instant(ResultSet r,String name) throws SQLException {var t=r.getTimestamp(name);return t==null?null:t.toInstant();}
    private static RunException invalid(String text) {return new RunException(HttpStatus.BAD_REQUEST,text);}
    private static RunException conflict(String text) {return new RunException(HttpStatus.CONFLICT,text);}
}
