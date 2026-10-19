package com.enterprise.agentharness.governance;

import com.enterprise.agentharness.run.RunException;
import com.enterprise.agentharness.run.RunService;
import com.enterprise.agentharness.task.TaskListQuery;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class GovernanceController {
    private final ApprovalService approvals;
    private final ToolCatalog tools;
    private final JdbcTemplate jdbc;
    private final RunService runs;
    public GovernanceController(ApprovalService approvals,ToolCatalog tools,JdbcTemplate jdbc,RunService runs) {
        this.approvals=approvals;this.tools=tools;this.jdbc=jdbc;this.runs=runs;
    }
    public record Page<T>(List<T> items,int page,int size,long totalElements,long totalPages) {}
    public record Event(long id,String actor,String action,UUID runId,Instant occurredAt,String code) {}
    public record Invocation(UUID id,UUID runId,int attempt,String inputDigest,String status,Instant startedAt,Instant finishedAt,String errorCode) {}

    @GetMapping("/tools") public List<ToolCatalog.ToolDefinition> tools() {return tools.list();}
    @GetMapping("/approvals/{id}") public ApprovalService.Approval approval(@PathVariable UUID id) {return approvals.get(id);}
    @GetMapping("/approvals") public Page<ApprovalService.Approval> approvals(@RequestParam MultiValueMap<String,String> params) {
        var q=page(params);return approvals.list(q.pageable().getPageNumber(),q.pageable().getPageSize());
    }
    @PostMapping("/approvals/{id}/decision")
    public ResponseEntity<ApprovalService.Approval> decide(@PathVariable UUID id,@RequestBody JsonNode body) {
        var approval=approvals.decide(id,ApprovalService.Decision.parse(body));
        return ResponseEntity.status(approval.status().equals("EXPIRED")?410:approval.status().equals("INVALIDATED")?409:200).body(approval);
    }
    @org.springframework.transaction.annotation.Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    @GetMapping("/audit-events") public Page<Event> events(@RequestParam MultiValueMap<String,String> params) {
        if(!params.containsKey("runId") || params.get("runId").size()!=1) throw bad("One runId is required");
        UUID id;
        try{id=UUID.fromString(params.getFirst("runId"));}catch(IllegalArgumentException ex){throw bad("Invalid runId");}
        runs.get(id);
        var remainder=new LinkedMultiValueMap<>(params);remainder.remove("runId");
        var q=page(remainder);int p=q.pageable().getPageNumber(),size=q.pageable().getPageSize();
        long count=jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE run_id=?",Long.class,id);
        var items=jdbc.query("SELECT * FROM audit_events WHERE run_id=? ORDER BY id LIMIT ? OFFSET ?",(r,i)->
                new Event(r.getLong("id"),r.getString("actor"),r.getString("action"),r.getObject("run_id",UUID.class),r.getTimestamp("occurred_at").toInstant(),r.getString("code")),id,size,(long)p*size);
        return new Page<>(items,p,size,count,(count+size-1)/size);
    }
    @org.springframework.transaction.annotation.Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    @GetMapping("/agent-runs/{id}/invocations") public List<Invocation> invocations(@PathVariable UUID id) {
        runs.get(id);
        return jdbc.query("SELECT * FROM tool_invocations WHERE run_id=? ORDER BY attempt",(r,i)->new Invocation(r.getObject("id",UUID.class),
                r.getObject("run_id",UUID.class),r.getInt("attempt"),r.getString("input_digest"),r.getString("status"),r.getTimestamp("started_at").toInstant(),
                r.getTimestamp("finished_at")==null?null:r.getTimestamp("finished_at").toInstant(),r.getString("error_code")),id);
    }
    private static TaskListQuery page(MultiValueMap<String,String> params) {
        if(params.keySet().stream().anyMatch(k->!k.equals("page")&&!k.equals("size"))) throw bad("Only page and size are allowed");
        return TaskListQuery.parse(params);
    }
    private static RunException bad(String text){return new RunException(HttpStatus.BAD_REQUEST,text);}
}
