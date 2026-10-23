package com.enterprise.agentharness.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ModelCallResponse(UUID id,UUID taskId,String provider,String model,String requestedBy,String status,
        String snapshotTitle,String snapshotDescription,long snapshotTaskVersion,String inputDigest,
        int maxOutputTokens,int timeoutMillis,long reservedTokens,Long inputTokens,Long outputTokens,
        boolean usageEstimated,BigDecimal costUsd,LocalDate budgetDay,Instant createdAt,Instant startedAt,
        Instant finishedAt,Instant deadlineAt,String errorCode,JsonNode plan) {}
