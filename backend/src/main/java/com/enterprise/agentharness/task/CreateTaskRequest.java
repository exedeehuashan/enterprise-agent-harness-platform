package com.enterprise.agentharness.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTaskRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 10000) String description) {}
