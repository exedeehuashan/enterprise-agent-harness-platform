package com.enterprise.agentharness.run;

import org.springframework.http.HttpStatus;

public class RunException extends RuntimeException {
    private final HttpStatus status;
    public RunException(HttpStatus status, String message) { super(message); this.status = status; }
    public HttpStatus status() { return status; }
}
