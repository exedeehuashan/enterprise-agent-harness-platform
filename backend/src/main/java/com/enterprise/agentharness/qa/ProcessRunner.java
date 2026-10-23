package com.enterprise.agentharness.qa;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Runs a program from an argv list (never through a shell) and writes its combined output to a log file. */
public interface ProcessRunner {
    record Result(int exitCode, boolean stopped, boolean timedOut) {}

    /** Polls {@code stop} while running; when it turns true, calls {@code onStop} and ends the process. */
    Result run(List<String> argv, Path logFile, Duration timeout, BooleanSupplier stop, Runnable onStop) throws IOException, InterruptedException;

    default Result run(List<String> argv, Path logFile, Duration timeout) throws IOException, InterruptedException {
        return run(argv, logFile, timeout, () -> false, () -> { });
    }
}
