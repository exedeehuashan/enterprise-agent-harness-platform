package com.enterprise.agentharness.task;

import java.util.UUID;

public class TaskNotFoundException extends RuntimeException {
    public TaskNotFoundException(UUID id) {
        super("Agent task " + id + " was not found");
    }
}
