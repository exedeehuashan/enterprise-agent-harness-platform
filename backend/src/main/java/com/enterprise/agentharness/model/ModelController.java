package com.enterprise.agentharness.model;

import com.enterprise.agentharness.run.RunException;
import com.enterprise.agentharness.task.TaskListQuery;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ModelController {
    private final ModelCallService calls;
    private final ModelProvider provider;
    public ModelController(ModelCallService calls,ModelProvider provider) {this.calls=calls;this.provider=provider;}
    @PostMapping("/agent-tasks/{taskId}/plans")
    public ResponseEntity<ModelCallResponse> start(@PathVariable UUID taskId,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody JsonNode body) {
        var call=calls.start(taskId,key,PlanRequest.parse(body));
        return ResponseEntity.accepted().location(URI.create("/api/v1/model-calls/"+call.id())).body(call);
    }
    @GetMapping("/model-calls/{id}") public ModelCallResponse get(@PathVariable UUID id) {return calls.get(id);}
    @GetMapping("/agent-tasks/{taskId}/plans") public ModelCallService.Page history(@PathVariable UUID taskId,@RequestParam MultiValueMap<String,String> params) {
        if(params.keySet().stream().anyMatch(k->!k.equals("page")&&!k.equals("size"))) throw new RunException(HttpStatus.BAD_REQUEST,"Only page and size are allowed");
        var q=TaskListQuery.parse(params);return calls.history(taskId,q.pageable().getPageNumber(),q.pageable().getPageSize());
    }
    @GetMapping("/model-provider") public Map<String,Object> provider() {
        return Map.of("provider",provider.id(),"model",provider.model(),"networkCalls",false,"realModel",false,"usageEstimated",true);
    }
    @PreAuthorize("hasRole('APPROVER')")
    @GetMapping("/model-budget") public ModelCallService.Budget budget() {return calls.budget();}
}
