package com.enterprise.agentharness.qa;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FailureTriageTest {
    private final FailureTriage triage = new FailureTriage();

    @Test
    void classifiesTestFailuresByCause() {
        assertThat(triage.classifyTest("java.lang.IllegalStateException", "Could not find a valid Docker environment. Please see logs", null)).isEqualTo("ENVIRONMENT_DOCKER");
        assertThat(triage.classifyTest("java.net.ConnectException", "Connection refused", null)).isEqualTo("ENVIRONMENT_NETWORK");
        assertThat(triage.classifyTest("java.util.concurrent.TimeoutException", null, null)).isEqualTo("TIMEOUT");
        assertThat(triage.classifyTest("org.opentest4j.AssertionFailedError", "expected: <1> but was: <2>", null)).isEqualTo("ASSERTION");
        assertThat(triage.classifyTest(null, "expected:<0> but was:<-1>", null)).isEqualTo("ASSERTION");
        assertThat(triage.classifyTest("java.lang.NullPointerException", null, "at com.example.Order.total")).isEqualTo("UNEXPECTED_EXCEPTION");
        assertThat(triage.classifyTest(null, "something odd", null)).isEqualTo("UNKNOWN");
        // Environment causes win over the exception type that carries them.
        assertThat(triage.classifyTest("org.opentest4j.AssertionFailedError", "x", "Caused by: java.net.UnknownHostException: db")).isEqualTo("ENVIRONMENT_NETWORK");
    }

    @Test
    void classifiesRunOutcomes() {
        assertThat(triage.classifyRun("PASSED", null)).isNull();
        assertThat(triage.classifyRun("TESTS_FAILED", "")).isNull();
        assertThat(triage.classifyRun("BUILD_FAILED", "[ERROR] COMPILATION ERROR :")).isEqualTo("BUILD_COMPILATION");
        assertThat(triage.classifyRun("BUILD_FAILED", "Could not resolve dependencies for project")).isEqualTo("BUILD_DEPENDENCIES");
        assertThat(triage.classifyRun("BUILD_FAILED", "")).isEqualTo("UNKNOWN");
        assertThat(triage.classifyRun("STOPPED", null)).isEqualTo("RUN_STOPPED");
        assertThat(triage.classifyRun("RUNNER_UNAVAILABLE", null)).isEqualTo("RUNNER");
        assertThat(triage.classifyRun("NO_TESTS_FOUND", null)).isEqualTo("NO_TESTS");
        assertThat(FailureTriage.EXPLANATIONS).containsKeys("ENVIRONMENT_DOCKER", "ASSERTION", "UNKNOWN", "BUILD_COMPILATION");
    }
}
