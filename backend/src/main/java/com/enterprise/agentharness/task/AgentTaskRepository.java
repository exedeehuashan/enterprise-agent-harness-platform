package com.enterprise.agentharness.task;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AgentTaskRepository extends JpaRepository<AgentTask, UUID> {
    Page<AgentTask> findByStatus(TaskStatus status, Pageable pageable);
}
