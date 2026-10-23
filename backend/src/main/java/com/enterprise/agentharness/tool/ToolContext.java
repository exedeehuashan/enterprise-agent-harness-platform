package com.enterprise.agentharness.tool;

import com.enterprise.agentharness.run.RunResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.function.BooleanSupplier;

/** What a tool may see: the run snapshot, its normalized arguments, the deadline and a stop signal. */
public record ToolContext(RunResponse run, JsonNode arguments, Instant deadline, BooleanSupplier stopSignal) {
    /** True after cancellation, deadline expiry or loss of this worker's claim. */
    public boolean stopRequested() { return stopSignal.getAsBoolean(); }
}
