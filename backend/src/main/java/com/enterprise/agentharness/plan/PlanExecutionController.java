package com.enterprise.agentharness.plan;

import com.enterprise.agentharness.run.RunException;
import com.enterprise.agentharness.task.TaskListQuery;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PlanExecutionController {
    private final PlanExecutionService executions;
    public PlanExecutionController(PlanExecutionService executions) {this.executions=executions;}

    @PostMapping("/model-calls/{callId}/executions")
    public ResponseEntity<PlanExecutionResponse> start(@PathVariable UUID callId,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody JsonNode body) {
        if(body==null || !body.isObject() || !body.isEmpty()) throw new RunException(HttpStatus.BAD_REQUEST,"An empty JSON object is required");
        var execution=executions.start(callId,key);
        return ResponseEntity.accepted().location(URI.create("/api/v1/plan-executions/"+execution.id())).body(execution);
    }
    @GetMapping("/plan-executions/{id}") public PlanExecutionResponse get(@PathVariable UUID id) {return executions.get(id);}
    @GetMapping("/agent-tasks/{taskId}/plan-executions")
    public PlanExecutionService.Page history(@PathVariable UUID taskId,@RequestParam MultiValueMap<String,String> params) {
        if(params.keySet().stream().anyMatch(k->!k.equals("page")&&!k.equals("size"))) throw new RunException(HttpStatus.BAD_REQUEST,"Only page and size are allowed");
        var q=TaskListQuery.parse(params);return executions.history(taskId,q.pageable().getPageNumber(),q.pageable().getPageSize());
    }
    @PostMapping("/plan-executions/{id}/cancel") public PlanExecutionResponse cancel(@PathVariable UUID id) {return executions.cancel(id);}
}
