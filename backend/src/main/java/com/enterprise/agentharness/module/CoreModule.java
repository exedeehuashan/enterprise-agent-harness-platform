package com.enterprise.agentharness.module;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CoreModule implements DomainModule {
    public String key() { return "core"; }
    public String name() { return "EAHP core"; }
    public String description() { return "Tasks, runs, approvals, model planning and plan execution, artifacts, audit, webhooks and API tokens."; }
    public List<String> tools() { return List.of("DEMO_ANALYZE", "DEMO_REVIEW"); }
    @Override public boolean canDisable() { return false; }
}
