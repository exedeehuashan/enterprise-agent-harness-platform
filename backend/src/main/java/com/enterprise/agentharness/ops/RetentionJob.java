package com.enterprise.agentharness.ops;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Applies retention nightly (UTC) alongside the worker. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "eahp.worker.enabled", havingValue = "true", matchIfMissing = true)
public class RetentionJob {
    private final RetentionService retention;
    public RetentionJob(RetentionService retention) { this.retention = retention; }

    @Scheduled(cron = "${eahp.retention.cron:0 30 3 * * *}", zone = "UTC")
    public void run() { retention.run("retention"); }
}
