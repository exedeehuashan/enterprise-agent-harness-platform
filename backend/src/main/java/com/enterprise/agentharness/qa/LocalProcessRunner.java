package com.enterprise.agentharness.qa;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

@Component
public class LocalProcessRunner implements ProcessRunner {
    @Override
    public Result run(List<String> argv, Path logFile, Duration timeout, BooleanSupplier stop, Runnable onStop) throws IOException, InterruptedException {
        var builder = new ProcessBuilder(argv).redirectErrorStream(true).redirectOutput(logFile.toFile());
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process process = builder.start();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!process.waitFor(2, TimeUnit.SECONDS)) {
                boolean stopped = stop.getAsBoolean();
                if (stopped || System.nanoTime() > deadline) {
                    onStop.run();
                    process.destroy();
                    if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
                    return new Result(-1, stopped, !stopped);
                }
            }
            return new Result(process.exitValue(), false, false);
        } catch (InterruptedException ex) {
            onStop.run();
            process.destroyForcibly();
            throw ex;
        }
    }
}
