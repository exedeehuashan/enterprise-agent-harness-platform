package com.enterprise.agentharness.ai;

/**
 * Deterministic provider for development and tests: answers by the schema's {@code title} with fixed, valid JSON and
 * performs no network IO. Selected when no real provider is configured.
 */
public class MockLlm implements LlmProvider {
    public String id() { return "mock"; }
    public String model() { return "mock-v1"; }

    @Override
    public Completion complete(Request request) {
        String title = request.schema().path("title").asText("");
        String json = switch (title) {
            case "failure_analysis" -> """
                    {"summary":"Mock analysis: the failures share one cause.","likelyCause":"A recent change altered the behaviour these tests assert.",
                     "category":"PRODUCT_DEFECT","confidence":"low","suggestedActions":["Compare the failing assertion with the latest change to the code under test."],
                     "affectedTests":[]}""";
            case "api_test_collection" -> """
                    {"key":"ai-generated","name":"AI-generated API tests","service":"%s","cases":[{"id":"TC-AI-001","name":"Health of the first documented path",
                     "steps":[{"request":{"method":"GET","path":"/"},"expect":{"status":[200,401,404]}}]}]}""";
            case "plan" -> """
                    {"schemaVersion":1,"summary":"Review the task with the controlled local demo tool.","steps":[{"tool":"DEMO_REVIEW","toolVersion":1,"target":"local-demo","timeoutSeconds":30}]}""";
            default -> "{}";
        };
        if (title.equals("api_test_collection")) json = json.formatted(request.user().contains("Service name: ") ? request.user().replaceAll("(?s).*Service name: ([a-z0-9-]+).*", "$1") : "api");
        return new Completion(json, (request.system().length() + request.user().length()) / 4, json.length() / 4);
    }
}
