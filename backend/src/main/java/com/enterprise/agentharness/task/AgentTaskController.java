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
        return ResponseEntity.created(URI.create("/api/v1/agent-tasks/" + task.id())).body(task);
    }

    @GetMapping
    public TaskPageResponse list(@RequestParam MultiValueMap<String, String> params) {
        return service.list(TaskListQuery.parse(params));
    }

    @GetMapping("/{id}")
    public TaskResponse get(@PathVariable UUID id) {
        return service.get(id);
    }
}
