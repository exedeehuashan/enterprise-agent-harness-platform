package com.enterprise.agentharness.notify;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Delivers webhook events with the worker. */
@Component
@ConditionalOnProperty(name = "eahp.worker.enabled", havingValue = "true", matchIfMissing = true)
public class WebhookJob {
    private final Webhooks webhooks;
    public WebhookJob(Webhooks webhooks) { this.webhooks = webhooks; }

    @Scheduled(initialDelayString = "${eahp.webhooks.initial-delay-ms:20000}", fixedDelayString = "${eahp.webhooks.interval-ms:5000}")
    public void run() { webhooks.dispatchOnce(); }
}
