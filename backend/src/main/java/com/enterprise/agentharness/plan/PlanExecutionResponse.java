package com.enterprise.agentharness.plan;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlanExecutionResponse(UUID id, UUID taskId, UUID modelCallId, String status, String requestedBy,
        String planDigest, String errorCode, Instant createdAt, Instant finishedAt, List<Step> steps) {
    public record Step(int sequence, String tool, int toolVersion, String target, int timeoutSeconds,
            String status, UUID runId, String runStatus) {}
}
