package com.enterprise.agentharness.qa;

/** Classifies failures for triage. Implementations explain outcomes; they never change the recorded test result. */
public interface FailureClassifier {
    /** Category for one failed test, from its exception type, message and stack excerpt. */
    String classifyTest(String failureType, String message, String detail);
    /** Category for a run that failed without (or beyond) test failures, from its outcome and console tail; null if none. */
    String classifyRun(String outcome, String consoleTail);
}
