package com.enterprise.agentharness.qa;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Sends queued GitHub write-back calls with the worker; does nothing without a write token. */
@Component
@ConditionalOnProperty(name = "eahp.worker.enabled", havingValue = "true", matchIfMissing = true)
public class GitHubWriteBackJob {
    private final GitHubWriteBack writeBack;
    public GitHubWriteBackJob(GitHubWriteBack writeBack) { this.writeBack = writeBack; }

    @Scheduled(initialDelayString = "${eahp.github.write-back.initial-delay-ms:25000}", fixedDelayString = "${eahp.github.write-back.interval-ms:15000}")
    public void run() { writeBack.sendOnce(); }
}
