package com.enterprise.agentharness.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name="eahp.worker.enabled", havingValue="true", matchIfMissing=true)
public class RunWorker {
    private static final Logger log = LoggerFactory.getLogger(RunWorker.class);
    private final RunService service;
    private final DemoTool tool;
    public RunWorker(RunService service, DemoTool tool) { this.service=service; this.tool=tool; }

    @Scheduled(fixedDelayString="${eahp.worker.poll-ms:500}")
    public void runOnce() {
        service.recover();
        RunService.Claim claim = service.claim();
        if (claim == null || !service.authorize(claim)) return;
        String output = null;
        boolean failed = false;
        try { output = tool.execute(claim.run()); }
        catch (RuntimeException ex) {
            failed = true;
            log.warn("Demo tool failed for run {}", claim.run().id());
        }
        service.finish(claim.run().id(), claim.token(), output, failed);
    }
}
