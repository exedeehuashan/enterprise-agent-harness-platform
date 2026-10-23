package com.enterprise.agentharness.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A code-owned tool implementation. The database tool definition may disable a tool but must
 * match every policy value declared here, so configuration can never weaken an approval gate
 * or add an executable tool. Domain agents (for example QA) contribute executors as Spring beans.
 */
public interface ToolExecutor {
    String name();
    int version();
    String target();
    boolean requiresApproval();
    /** Only side-effect-free tools may be retried after a lost worker. */
    boolean retryable();

    /**
     * Validates and normalizes run arguments; return null when the tool takes none. Throw
     * IllegalArgumentException with a message that is safe to return to the caller.
     */
    default JsonNode normalize(JsonNode arguments) {
        if (arguments != null && !arguments.isNull()) throw new IllegalArgumentException("This tool takes no arguments");
        return null;
    }

    /** Rejects run deadlines this tool does not support; most tools allow at most five minutes. */
    default void checkTimeout(JsonNode normalizedArguments, int timeoutSeconds) {
        if (timeoutSeconds > 300) throw new IllegalArgumentException("timeoutSeconds must be 1..300 for this tool");
    }

    /**
     * Runs outside any database transaction. Long-running tools should poll
     * {@link ToolContext#stopRequested()} and stop promptly when it returns true.
     */
    ToolResult execute(ToolContext context) throws Exception;
}
