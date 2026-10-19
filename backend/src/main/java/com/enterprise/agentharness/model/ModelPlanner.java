package com.enterprise.agentharness.model;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name="eahp.planner.enabled",havingValue="true",matchIfMissing=true)
public class ModelPlanner {
    private final ModelCallService calls;
    private final ModelGateway gateway;
    public ModelPlanner(ModelCallService calls,ModelGateway gateway) {this.calls=calls;this.gateway=gateway;}
    @Scheduled(fixedDelayString="${eahp.planner.poll-ms:500}")
    public void runOnce() {
        calls.recover();var claim=calls.claim();if(claim==null) return;
        var call=claim.call();
        try {calls.complete(claim,gateway.generate(new ModelProvider.Input(call.snapshotTitle(),call.snapshotDescription()),call.maxOutputTokens(),call.timeoutMillis()));}
        catch(ModelFailure ex) {calls.fail(claim,ex.code());}
    }
}
