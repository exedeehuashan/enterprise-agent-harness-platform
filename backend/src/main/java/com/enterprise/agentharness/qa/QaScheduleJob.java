package com.enterprise.agentharness.qa;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Checks QA schedules once a minute, alongside the worker. ON_CHANGE checks resolve the ref with one git ls-remote. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "eahp.worker.enabled", havingValue = "true", matchIfMissing = true)
public class QaScheduleJob {
    private final QaSchedules schedules;
    public QaScheduleJob(QaSchedules schedules) { this.schedules = schedules; }

    @Scheduled(initialDelayString = "${eahp.qa.schedule-initial-delay-ms:45000}", fixedDelayString = "${eahp.qa.schedule-interval-ms:60000}")
    public void run() { schedules.runOnce(); }
}
