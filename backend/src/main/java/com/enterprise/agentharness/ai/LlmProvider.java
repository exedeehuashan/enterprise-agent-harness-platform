package com.enterprise.agentharness.ai;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A language model behind EAHP's AI features (planning, failure analysis, test generation). Implementations receive
 * only the prompt they are given, never platform credentials or execution handles, and return text that callers
 * validate as untrusted data before using it.
 */
public interface LlmProvider {
    String id();
    String model();

    /**
     * One completion. {@code schema} is a JSON Schema the answer must follow; providers that can enforce it do
     * (structured outputs), the others are instructed to answer with a JSON object only.
     */
    Completion complete(Request request) throws LlmException;

    record Request(String system, String user, ObjectNode schema, int maxOutputTokens) {}
    record Completion(String text, long inputTokens, long outputTokens) {}

    /** A failure with a bounded code that is safe to show (never the provider's raw message, which may echo input). */
    class LlmException extends Exception {
        private final String code;
        private final long inputTokens, outputTokens;
        public LlmException(String code, String message) { this(code, message, 0, 0); }
        /** For failures after the provider answered (refusal, truncation): the tokens it still charged. */
        public LlmException(String code, String message, long inputTokens, long outputTokens) {
            super(message); this.code = code; this.inputTokens = inputTokens; this.outputTokens = outputTokens;
        }
        public String code() { return code; }
        public long inputTokens() { return inputTokens; }
        public long outputTokens() { return outputTokens; }
    }
}
