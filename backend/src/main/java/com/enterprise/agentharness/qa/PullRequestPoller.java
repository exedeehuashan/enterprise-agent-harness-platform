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
    private final com.enterprise.agentharness.qa.impact.ChangeImpactService impacts;
    private final com.enterprise.agentharness.module.ModuleRegistry modules;
    public PullRequestPoller(PullRequestTriggers triggers, com.enterprise.agentharness.qa.impact.ChangeImpactService impacts,
            com.enterprise.agentharness.module.ModuleRegistry modules) {
        this.triggers = triggers; this.impacts = impacts; this.modules = modules;
    }

    @Scheduled(initialDelayString = "${eahp.github.poll.initial-delay-ms:30000}", fixedDelayString = "${eahp.github.poll.interval-ms:300000}")
    public void poll() {
        if (!modules.enabled(QaModule.KEY)) return; // the QA module is switched off
        triggers.runOnce();
        impacts.precompute(5);
    }
}
