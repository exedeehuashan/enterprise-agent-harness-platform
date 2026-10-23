package com.enterprise.agentharness.tool;

import java.util.List;

/**
 * Outcome reported by a tool. A failed result (for example a failing test suite) ends the run
 * FAILED with its output and artifacts kept as evidence; it is never reported as success.
 */
public record ToolResult(boolean succeeded, String code, String output, List<Artifact> artifacts) {
    public record Artifact(String name, String mediaType, byte[] content) {}

    public ToolResult {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }
    public static ToolResult success(String output, Artifact... artifacts) { return new ToolResult(true, null, output, List.of(artifacts)); }
    public static ToolResult failure(String code, String output, Artifact... artifacts) { return new ToolResult(false, code, output, List.of(artifacts)); }
}
