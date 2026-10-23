package com.enterprise.agentharness.plan;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name="eahp.worker.enabled",havingValue="true",matchIfMissing=true)
public class PlanAdvancer {
    private final PlanExecutionService executions;
    public PlanAdvancer(PlanExecutionService executions) {this.executions=executions;}
    @Scheduled(fixedDelayString="${eahp.worker.poll-ms:500}")
    public void runOnce() {executions.advance();}
}
