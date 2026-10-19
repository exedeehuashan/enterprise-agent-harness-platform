package com.enterprise.agentharness.task;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/agent-tasks")
public class AgentTaskController {
    private final AgentTaskService service;

    public AgentTaskController(AgentTaskService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TaskResponse> create(@Valid @RequestBody CreateTaskRequest request) {
        TaskResponse task = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/agent-tasks/" + task.id())).eTag(Long.toString(task.version())).body(task);
    }

    @GetMapping
    public TaskPageResponse list(@RequestParam MultiValueMap<String, String> params) {
        return service.list(TaskListQuery.parse(params));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TaskResponse> get(@PathVariable UUID id) {
        TaskResponse task = service.get(id);
        return ResponseEntity.ok().eTag(Long.toString(task.version())).body(task);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<TaskResponse> edit(@PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        long expectedVersion = TaskPatch.expectedVersion(ifMatch);
        TaskResponse task = service.edit(id, expectedVersion, TaskPatch.parse(body));
        return ResponseEntity.ok().eTag(Long.toString(task.version())).body(task);
    }
}
