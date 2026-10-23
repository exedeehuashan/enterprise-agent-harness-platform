package com.enterprise.agentharness.model;

import java.nio.charset.StandardCharsets;

/** Provider implementations receive task data only, never platform credentials or execution handles. */
public interface ModelProvider {
    String id();
    String model();
    Result generate(Input input, int maxOutputTokens) throws Exception;

    record Input(String title, String description) {
        public long estimatedTokens() {
            long bytes=title.getBytes(StandardCharsets.UTF_8).length
                    +(description==null?0:description.getBytes(StandardCharsets.UTF_8).length)+128L;
            return (bytes+3)/4;
        }
    }
    record Result(String json, long inputTokens, long outputTokens) {}
}
