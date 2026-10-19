package com.enterprise.agentharness.task;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "agent_tasks")
public class AgentTask {
    @Id
    private UUID id;
    @Column(nullable = false, length = 255)
    private String title;
    @Column(columnDefinition = "text")
    private String description;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected AgentTask() {}

    public long getVersion() { return version; }

    public void edit(String title, String description) {
        if (java.util.Objects.equals(this.title, title) && java.util.Objects.equals(this.description, description)) return;
        this.title = title;
        this.description = description;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = now.isAfter(updatedAt) ? now : updatedAt.plusNanos(1000);
    }

    public AgentTask(String title, String description) {
        this.id = UUID.randomUUID();
        this.title = title;
        this.description = description;
        this.status = TaskStatus.PENDING;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public TaskStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
