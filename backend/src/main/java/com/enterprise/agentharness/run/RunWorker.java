package com.enterprise.agentharness.run;

import com.enterprise.agentharness.tool.ToolContext;
import com.enterprise.agentharness.tool.ToolRegistry;
import com.enterprise.agentharness.tool.ToolResult;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name="eahp.worker.enabled", havingValue="true", matchIfMissing=true)
public class RunWorker {
    private static final Logger log = LoggerFactory.getLogger(RunWorker.class);
    /** Renews leases while a tool runs; lease is 10 s, so a 3 s period tolerates two missed beats. */
    private static final ScheduledExecutorService HEARTBEAT = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "run-lease-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private final RunService service;
    private final ToolRegistry tools;

    @Autowired
    public RunWorker(RunService service, ToolRegistry tools) { this.service=service; this.tools=tools; }

    /** Runs the demo tools with the given implementation, for tests that drive the worker directly. */
    public RunWorker(RunService service, DemoTool tool) { this(service, ToolRegistry.demo(tool)); }

    @Scheduled(fixedDelayString="${eahp.worker.poll-ms:500}")
    public void runOnce() {
        service.recover();
        RunService.Claim claim = service.claim();
        if (claim == null || !service.authorize(claim)) return;
        RunResponse run = claim.run();
        var heartbeat = HEARTBEAT.scheduleAtFixedRate(() -> renew(claim), 3, 3, TimeUnit.SECONDS);
        ToolResult result;
        try {
            var executor = tools.find(run.tool()).orElseThrow(() -> new IllegalStateException("Tool not registered"));
            result = executor.execute(new ToolContext(run, run.arguments(), run.deadlineAt(), () -> service.stopRequested(claim)));
            if (result == null) result = ToolResult.failure("TOOL_FAILED", null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            result = ToolResult.failure("TOOL_FAILED", null);
        } catch (Exception ex) {
            log.warn("Tool {} failed for run {}", run.tool(), run.id());
            result = ToolResult.failure("TOOL_FAILED", null);
        } finally {
            heartbeat.cancel(false);
        }
        service.finish(run.id(), claim.token(), result);
    }

    private void renew(RunService.Claim claim) {
        try { service.renewLease(claim); }
        catch (RuntimeException ex) { log.warn("Lease renewal failed for run {}", claim.run().id()); }
    }
}
