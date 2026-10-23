package com.enterprise.agentharness.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Both real providers against local fake APIs: the request each sends, and how answers, refusals and errors map. */
class LlmProvidersTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<com.sun.net.httpserver.Headers> headers = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply = "{}";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            headers.set(ex.getRequestHeaders());
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }

    private String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    private LlmProvider.Request request() {
        var schema = mapper.createObjectNode().put("title", "failure_analysis").put("type", "object").put("additionalProperties", false);
        schema.putObject("properties").putObject("summary").put("type", "string");
        schema.putArray("required").add("summary");
        return new LlmProvider.Request("You analyse failures.", "<evidence>x</evidence>", schema, 1000);
    }
    private static String message(String stopReason, String text) {
        return """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[{"type":"text","text":%s}],
                 "stop_reason":"%s","stop_sequence":null,"usage":{"input_tokens":120,"output_tokens":30}}""".formatted(new ObjectMapper().valueToTree(text).toString(), stopReason);
    }

    @Test
    void claudeSendsStructuredOutputEffortAndFallbacksThroughTheSdk() throws Exception {
        reply = message("end_turn", "{\"summary\":\"ok\"}");
        var llm = new AnthropicLlm("sk-ant-test", "claude-opus-5-5", "medium", url(), true, Duration.ofSeconds(10));
        var completion = llm.complete(request());
        assertThat(completion.text()).isEqualTo("{\"summary\":\"ok\"}");
        assertThat(completion.inputTokens()).isEqualTo(120);
        assertThat(completion.outputTokens()).isEqualTo(30);
        assertThat(headers.get().getFirst("x-api-key")).isEqualTo("sk-ant-test");
        assertThat(headers.get().getFirst("anthropic-version")).isNotBlank();
        assertThat(headers.get().get("anthropic-beta").toString()).contains("server-side-fallback-2026-07-01");
        JsonNode sent = mapper.readTree(body.get());
        assertThat(sent.get("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(sent.get("fallbacks").asText()).isEqualTo("default");
        assertThat(sent.get("system").asText()).isEqualTo("You analyse failures.");
        assertThat(sent.get("max_tokens").asInt()).isEqualTo(1000);
        assertThat(sent.path("output_config").path("effort").asText()).isEqualTo("medium");
        assertThat(sent.path("output_config").path("format").path("type").asText()).isEqualTo("json_schema");
        assertThat(sent.path("output_config").path("format").path("schema").path("properties").has("summary")).isTrue();
        assertThat(sent.has("thinking")).isFalse(); // Claude Opus 5.5 thinks adaptively; disabling it is a 400
    }

    @Test
    void claudeRefusalsTruncationAndErrorsBecomeBoundedCodes() {
        var llm = new AnthropicLlm("sk-ant-test", "claude-opus-5-5", "medium", url(), false, Duration.ofSeconds(10));
        reply = message("refusal", "");
        assertThatThrownBy(() -> llm.complete(request())).isInstanceOf(LlmProvider.LlmException.class).extracting(e -> ((LlmProvider.LlmException) e).code()).isEqualTo("AI_REFUSED");
        reply = message("max_tokens", "{\"summ");
        assertThatThrownBy(() -> llm.complete(request())).extracting(e -> ((LlmProvider.LlmException) e).code()).isEqualTo("AI_TRUNCATED");
        assertThatThrownBy(() -> llm.complete(request())).extracting(e -> ((LlmProvider.LlmException) e).outputTokens()).isEqualTo(30L); // still charged
        status = 400; reply = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"echo of the prompt\"}}";
        assertThatThrownBy(() -> llm.complete(request())).extracting(e -> ((LlmProvider.LlmException) e).code()).isEqualTo("AI_PROVIDER_ERROR");
        assertThat(headers.get().get("anthropic-beta")).isNull(); // fallbacks switched off: no beta header
    }

    @Test
    void deepSeekUsesJsonModeWithTheSchemaInTheSystemPrompt() throws Exception {
        reply = """
                {"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"{\\"summary\\":\\"ok\\"}"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":90,"completion_tokens":12}}""";
        var llm = new DeepSeekLlm("ds-test", "deepseek-chat", url(), Duration.ofSeconds(10));
        var completion = llm.complete(request());
        assertThat(completion.text()).isEqualTo("{\"summary\":\"ok\"}");
        assertThat(completion.inputTokens()).isEqualTo(90);
        assertThat(headers.get().getFirst("Authorization")).isEqualTo("Bearer ds-test");
        JsonNode sent = mapper.readTree(body.get());
        assertThat(sent.get("model").asText()).isEqualTo("deepseek-chat");
        assertThat(sent.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(sent.path("messages").get(0).path("content").asText()).contains("JSON Schema").contains("\"summary\"");
        assertThat(sent.path("messages").get(1).path("content").asText()).isEqualTo("<evidence>x</evidence>");

        reply = "{\"choices\":[{\"message\":{\"content\":\"{\"},\"finish_reason\":\"length\"}]}";
        assertThatThrownBy(() -> llm.complete(request())).extracting(e -> ((LlmProvider.LlmException) e).code()).isEqualTo("AI_TRUNCATED");
        status = 429;
        assertThatThrownBy(() -> llm.complete(request())).extracting(e -> ((LlmProvider.LlmException) e).code()).isEqualTo("AI_RATE_LIMITED");
        status = 500;
        assertThatThrownBy(() -> llm.complete(request())).extracting(e -> ((LlmProvider.LlmException) e).code()).isEqualTo("AI_PROVIDER_ERROR");
    }

    @Test
    void secretsAreRedactedBeforeAnyPromptLeaves() {
        String text = "Authorization: Bearer abcdefghijklmnopqrstuvwxyz password=hunter22 key sk-ant-api03-abcdefgh ghp_" + "a".repeat(30)
                + " jdbc:postgresql://user:pa55word@db/x eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop";
        String safe = AiService.redact(text);
        assertThat(safe).doesNotContain("abcdefghijklmnopqrstuvwxyz").doesNotContain("hunter22").doesNotContain("sk-ant-api03").doesNotContain("ghp_aaaa")
                .doesNotContain("pa55word").doesNotContain("eyJhbGci").contains("[REDACTED]").contains("://user:[REDACTED]@db/x");
        String json = AiService.redact("{\"user\":\"ada\",\"password\":\"hunter22\",\"token\":\"opaque-credential\",\"client_secret\":\"s3\"}");
        assertThat(json).isEqualTo("{\"user\":\"ada\",\"password\":\"[REDACTED]\",\"token\":\"[REDACTED]\",\"client_secret\":\"[REDACTED]\"}");
    }
}
