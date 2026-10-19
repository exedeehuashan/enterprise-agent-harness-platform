package com.enterprise.agentharness.model;

import java.nio.charset.StandardCharsets;

/** Deterministic fixture provider. Does not interpret instructions or perform network IO. */
public class MockModelProvider implements ModelProvider {
    public String id() {return "mock";}
    public String model() {return "mock-planner-v1";}
    public Result generate(Input input,int maxOutputTokens) {
        String json="""
                {"schemaVersion":1,"summary":"Review task using the controlled local demo tool.","steps":[{"tool":"DEMO_REVIEW","toolVersion":1,"target":"local-demo","timeoutSeconds":30}]}
                """;
        return new Result(json,input.estimatedTokens(),(json.getBytes(StandardCharsets.UTF_8).length+3L)/4);
    }
}
