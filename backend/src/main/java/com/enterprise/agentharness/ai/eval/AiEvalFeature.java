package com.enterprise.agentharness.ai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One AI feature the evaluation set can grade. Modules contribute features as beans, each with its own cases file
 * ({@code {"version": n, "cases": [{"id", "feature", "name", "expect", ...}]}} on the classpath), so the platform's
 * evaluation runner knows no module's code: it reads every feature's cases and hands each case to its feature.
 */
public interface AiEvalFeature {
    /** The {@code feature} value of the cases this grades, for example "failure-analysis". */
    String feature();

    /** Classpath resource holding this feature's cases; features may share one file. */
    String casesResource();

    /**
     * Runs the case through the production code path and records checks; returns {input tokens, output tokens}.
     * {@code result.answer} should hold what the model produced.
     */
    long[] evaluate(JsonNode evalCase, EvalChecks checks, ObjectNode result) throws Exception;
}
