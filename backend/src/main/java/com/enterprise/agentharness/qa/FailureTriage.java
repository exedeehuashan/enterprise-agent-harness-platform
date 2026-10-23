package com.enterprise.agentharness.qa;

import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Explains why a test or a run failed. This rule-based classifier needs no model and is deterministic, so its
 * results are reproducible evidence; a model-based {@link FailureClassifier} can be added later and must never
 * replace the recorded test outcome itself.
 */
@Component
public class FailureTriage implements FailureClassifier {

    public static final Map<String, String> EXPLANATIONS = Map.ofEntries(
            Map.entry("ENVIRONMENT_DOCKER", "The test needs Docker (Testcontainers) but ran in a suite without Docker access. Move it to a Docker suite or tag it so Docker-free suites exclude it."),
            Map.entry("ENVIRONMENT_NETWORK", "A network call failed (unreachable host, refused connection or stalled download). This usually points at the environment, not the code under test."),
            Map.entry("TIMEOUT", "Something did not finish in time. Check for slow dependencies, deadlocks or timeouts that are too tight."),
            Map.entry("ASSERTION", "An assertion failed: the code produced a different result than the test expects. This is the most likely sign of a real defect or an outdated test."),
            Map.entry("UNEXPECTED_EXCEPTION", "The code under test threw an unexpected exception. Look at the exception type and the first frames of the stack trace."),
            Map.entry("BUILD_COMPILATION", "The build did not compile, so tests could not run."),
            Map.entry("BUILD_DEPENDENCIES", "Build dependencies could not be resolved or downloaded."),
            Map.entry("RUN_STOPPED", "The run was stopped (cancelled, timed out or lost) before the suite finished."),
            Map.entry("RUNNER", "The test runner itself could not start or reach Docker; the code was not tested."),
            Map.entry("NO_TESTS", "The suite ran but no test reports were produced; the test selection may match nothing."),
            Map.entry("UNKNOWN", "No rule matched. Read the failure message and the console log."));

    @Override
    public String classifyTest(String failureType, String message, String detail) {
        String text = (nullToEmpty(failureType) + "\n" + nullToEmpty(message) + "\n" + nullToEmpty(detail)).toLowerCase(Locale.ROOT);
        if (text.contains("could not find a valid docker environment") || text.contains("dockerclientproviderstrategy")
                || text.contains("docker daemon") && text.contains("testcontainers")) return "ENVIRONMENT_DOCKER";
        if (text.contains("unknownhostexception") || text.contains("connection refused") || text.contains("connectexception")
                || text.contains("no route to host") || text.contains("could not transfer artifact")) return "ENVIRONMENT_NETWORK";
        if (text.contains("timeoutexception") || text.contains("timed out") || text.contains("conditiontimeout")
                || text.contains("sockettimeoutexception")) return "TIMEOUT";
        String type = nullToEmpty(failureType).toLowerCase(Locale.ROOT);
        if (type.contains("assertionfailederror") || type.contains("assertionerror") || type.contains("comparisonfailure")
                || type.contains("multiplefailureserror") || text.contains("expected:") || text.contains("expected <")) return "ASSERTION";
        if (type.contains("exception") || type.contains("error")) return "UNEXPECTED_EXCEPTION";
        return "UNKNOWN";
    }

    @Override
    public String classifyRun(String outcome, String consoleTail) {
        String log = nullToEmpty(consoleTail).toLowerCase(Locale.ROOT);
        if (outcome == null) return "UNKNOWN";
        return switch (outcome) {
            case "PASSED" -> null;
            case "STOPPED", "SUITE_TIMEOUT" -> "RUN_STOPPED";
            case "RUNNER_ERROR", "RUNNER_UNAVAILABLE", "SOURCE_UNAVAILABLE" -> "RUNNER";
            case "NO_TESTS_FOUND", "REPORTS_UNREADABLE" -> "NO_TESTS";
            case "BUILD_FAILED" -> log.contains("compilation error") || log.contains("cannot find symbol") ? "BUILD_COMPILATION"
                    : log.contains("could not resolve dependencies") || log.contains("could not transfer artifact") ? "BUILD_DEPENDENCIES" : "UNKNOWN";
            default -> null; // TESTS_FAILED: the per-test categories explain it
        };
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
}
