package com.enterprise.agentharness.qa;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs with the worker: ingests finished QA runs shortly after they finish (and any older ones not yet ingested). */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "eahp.worker.enabled", havingValue = "true", matchIfMissing = true)
public class QaResultIngestionJob {
    private final QaResultIngestor ingestor;
    public QaResultIngestionJob(QaResultIngestor ingestor) { this.ingestor = ingestor; }

    @Scheduled(fixedDelayString = "${eahp.qa.ingest-interval-ms:10000}", initialDelayString = "${eahp.qa.ingest-initial-delay-ms:15000}")
    public void ingest() { ingestor.runOnce(); }
}
