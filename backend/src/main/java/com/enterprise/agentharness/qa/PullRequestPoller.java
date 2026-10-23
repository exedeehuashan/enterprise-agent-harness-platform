package com.enterprise.agentharness.qa;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls GitHub instead of receiving webhooks, so a local EAHP needs no public endpoint. Off by default. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "eahp.github.poll.enabled", havingValue = "true")
public class PullRequestPoller {
    private final PullRequestTriggers triggers;
    public PullRequestPoller(PullRequestTriggers triggers) { this.triggers = triggers; }

    @Scheduled(initialDelayString = "${eahp.github.poll.initial-delay-ms:30000}", fixedDelayString = "${eahp.github.poll.interval-ms:300000}")
    public void poll() { triggers.runOnce(); }
}
