package com.enterprise.agentharness.run;

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
public class RunController {
    private final RunService service;
    public RunController(RunService service) { this.service = service; }

    @PostMapping("/agent-tasks/{taskId}/runs")
    public ResponseEntity<RunResponse> start(@PathVariable UUID taskId,
            @RequestHeader(value="Idempotency-Key", required=false) String key, @RequestBody JsonNode body) {
        RunResponse run = service.start(taskId, key, StartRunRequest.parse(body));
        return ResponseEntity.accepted().location(URI.create("/api/v1/agent-runs/" + run.id())).body(run);
    }

    @GetMapping("/agent-tasks/{taskId}/runs")
    public RunPageResponse history(@PathVariable UUID taskId, @RequestParam MultiValueMap<String,String> params) {
        if (params.keySet().stream().anyMatch(k -> !k.equals("page") && !k.equals("size"))) {
            throw new RunException(HttpStatus.BAD_REQUEST, "Only page and size are allowed for run history");
        }
        var query = TaskListQuery.parse(params);
        return service.history(taskId, query.pageable().getPageNumber(), query.pageable().getPageSize());
    }

    @GetMapping("/agent-runs/{id}")
    public RunResponse get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping("/agent-runs/{id}/cancel")
    public ResponseEntity<RunResponse> cancel(@PathVariable UUID id) {
        RunResponse run = service.cancel(id);
        return ResponseEntity.status(run.status().equals("RUNNING") ? 202 : 200).body(run);
    }
}
