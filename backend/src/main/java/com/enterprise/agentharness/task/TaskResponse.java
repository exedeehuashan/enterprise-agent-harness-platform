package com.enterprise.agentharness.task;

import java.time.Instant;
import java.util.UUID;

public record TaskResponse(UUID id, String title, String description, TaskStatus status,
                           Instant createdAt, Instant updatedAt, long version) {
    public static TaskResponse from(AgentTask task) {
        return new TaskResponse(task.getId(), task.getTitle(), task.getDescription(),
                task.getStatus(), task.getCreatedAt(), task.getUpdatedAt(), task.getVersion());
    }
}
