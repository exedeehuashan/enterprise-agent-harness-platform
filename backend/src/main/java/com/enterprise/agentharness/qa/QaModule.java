package com.enterprise.agentharness.qa;

import com.enterprise.agentharness.module.DomainModule;
import java.util.List;
import org.springframework.stereotype.Component;

/** 方案 4: Enterprise AI Quality Engineering, the first domain agent on EAHP. */
@Component
public class QaModule implements DomainModule {
    public static final String KEY = "qa";
    public String key() { return KEY; }
    public String name() { return "方案 4: AI Quality Engineering"; }
    public String description() {
        return "Test suites and API tests at exact commits, pull request and scheduled runs, change impact and test selection, triage and history, "
                + "release gates, quarantine, reports and comparisons.";
    }
    public List<String> tools() { return List.of("QA_RUN_SUITE", "QA_RUN_PRIVILEGED_SUITE", "QA_RUN_API_TESTS", "QA_START_ENVIRONMENT", "QA_STOP_ENVIRONMENT", "QA_RUN_ENVIRONMENT_TESTS"); }
}
