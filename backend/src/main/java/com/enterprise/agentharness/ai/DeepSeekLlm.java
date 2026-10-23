package com.enterprise.agentharness.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek through its HTTP chat completions API ({@code POST /chat/completions}, Bearer key). DeepSeek has no schema
 * enforcement, so it runs in JSON mode ({@code response_format: json_object}) with the schema in the system prompt;
 * callers validate every answer anyway.
 */
public class DeepSeekLlm implements LlmProvider {
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey, model, baseUrl;
    private final Duration timeout;

    public DeepSeekLlm(String apiKey, String model, String baseUrl, Duration timeout) {
        this.apiKey = apiKey; this.model = model; this.timeout = timeout;
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.deepseek.com" : baseUrl.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public String id() { return "deepseek"; }
    public String model() { return model; }

    @Override
    public Completion complete(Request request) throws LlmException {
        String system = request.system() + "\n\nAnswer with a single JSON object only, no prose, that follows this JSON Schema:\n" + request.schema();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", request.user())));
        body.put("response_format", Map.of("type", "json_object"));
        body.put("max_tokens", request.maxOutputTokens());
        body.put("stream", false);
        HttpResponse<String> response;
        try {
            var http = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions")).timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            response = this.http.send(http, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new LlmException("AI_UNAVAILABLE", "The model provider could not be reached.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LlmException("AI_UNAVAILABLE", "Interrupted.");
        }
        if (response.statusCode() == 429) throw new LlmException("AI_RATE_LIMITED", "The model provider is rate limiting requests.");
        if (response.statusCode() != 200) throw new LlmException("AI_PROVIDER_ERROR", "The model provider returned HTTP " + response.statusCode() + ".");
        JsonNode json;
        try { json = mapper.readTree(response.body()); } catch (IOException ex) { throw new LlmException("AI_PROVIDER_ERROR", "The model provider returned an unreadable answer."); }
        JsonNode choice = json.path("choices").path(0);
        String finish = choice.path("finish_reason").asText("");
        long in = json.path("usage").path("prompt_tokens").asLong(), out = json.path("usage").path("completion_tokens").asLong();
        if (finish.equals("length")) throw new LlmException("AI_TRUNCATED", "The answer exceeded the output limit.", in, out);
        if (finish.equals("content_filter")) throw new LlmException("AI_REFUSED", "The model declined this request.", in, out);
        String text = choice.path("message").path("content").asText("");
        if (text.isBlank()) throw new LlmException("AI_EMPTY", "The model returned no answer.");
        return new Completion(text, json.path("usage").path("prompt_tokens").asLong(), json.path("usage").path("completion_tokens").asLong());
    }
}
