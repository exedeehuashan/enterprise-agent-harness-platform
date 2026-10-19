package com.enterprise.agentharness.run;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RunResponse(UUID id, UUID taskId, String tool, String status,
        String snapshotTitle, String snapshotDescription, int timeoutSeconds,
        Instant createdAt, Instant startedAt, Instant finishedAt, Instant deadlineAt,
        int attemptCount, boolean cancelRequested, String errorCode, List<Step> steps, int toolVersion, String target, String inputDigest, UUID approvalId) {
    public record Step(int sequence, String tool, String status, Instant startedAt, Instant finishedAt, String output) {}
}
