package com.enterprise.agentharness.task;

import java.util.List;
import org.springframework.data.domain.Page;

public record TaskPageResponse(List<TaskResponse> items, int page, int size,
                               long totalElements, int totalPages) {
    public static TaskPageResponse from(Page<AgentTask> tasks) {
        return new TaskPageResponse(tasks.getContent().stream().map(TaskResponse::from).toList(),
                tasks.getNumber(), tasks.getSize(), tasks.getTotalElements(), tasks.getTotalPages());
    }
}
