package com.enterprise.agentharness.task;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AgentTaskService {
    private final AgentTaskRepository repository;

    public AgentTaskService(AgentTaskRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public TaskResponse create(CreateTaskRequest request) {
        return TaskResponse.from(repository.save(new AgentTask(request.title(), request.description())));
    }

    public TaskResponse get(UUID id) {
        return TaskResponse.from(repository.findById(id).orElseThrow(() -> new TaskNotFoundException(id)));
    }
}
