package com.enterprise.agentharness.task;

import org.springframework.http.HttpStatus;

public class TaskEditException extends RuntimeException {
    private final HttpStatus status;
    public TaskEditException(HttpStatus status, String message) { super(message); this.status = status; }
    public HttpStatus status() { return status; }
}
