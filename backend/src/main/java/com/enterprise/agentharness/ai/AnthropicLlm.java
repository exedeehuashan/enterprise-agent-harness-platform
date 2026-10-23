package com.enterprise.agentharness.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;

/**
 * Claude through the official Anthropic Java SDK. Answers are constrained to the request's JSON Schema with structured
 * outputs; effort is configurable (Claude Opus 5.5 defaults to medium). Refusals are handled: the server-side
 * fallback ({@code fallbacks: "default"}, beta {@code server-side-fallback-2026-07-01}) re-runs a declined request on
 * the model Anthropic recommends for that refusal category, and a refusal that still comes back is reported as
 * {@code AI_REFUSED} rather than parsed.
 */
public class AnthropicLlm implements LlmProvider {
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    private final AnthropicClient client;
    private final String model;
    private final OutputConfig.Effort effort;
    private final boolean fallbacks;
    private final ObjectMapper mapper = new ObjectMapper();

    public AnthropicLlm(String apiKey, String model, String effort, String baseUrl, boolean fallbacks, Duration timeout) {
        var builder = AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(timeout).maxRetries(2);
        if (baseUrl != null && !baseUrl.isBlank()) builder.baseUrl(baseUrl);
        this.client = builder.build();
        this.model = model;
        this.effort = OutputConfig.Effort.of(effort.toLowerCase());
        this.fallbacks = fallbacks;
    }

    public String id() { return "anthropic"; }
    public String model() { return model; }

    @Override
    public Completion complete(Request request) throws LlmException {
        var schema = JsonOutputFormat.Schema.builder();
        request.schema().fields().forEachRemaining(e -> schema.putAdditionalProperty(e.getKey(), JsonValue.from(mapper.convertValue(e.getValue(), Object.class))));
        var params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(request.maxOutputTokens())
                .system(request.system())
                .addUserMessage(request.user())
                .outputConfig(OutputConfig.builder().effort(effort).format(JsonOutputFormat.builder().schema(schema.build()).build()).build());
        if (fallbacks) params.putAdditionalHeader("anthropic-beta", FALLBACK_BETA).putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        Message message;
        try {
            message = client.messages().create(params.build());
        } catch (RateLimitException ex) {
            throw new LlmException("AI_RATE_LIMITED", "The model provider is rate limiting requests.");
        } catch (AnthropicServiceException ex) {
            throw new LlmException("AI_PROVIDER_ERROR", "The model provider returned HTTP " + ex.statusCode() + ".");
        } catch (RuntimeException ex) {
            throw new LlmException("AI_UNAVAILABLE", "The model provider could not be reached.");
        }
        if (message.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            throw new LlmException("AI_REFUSED", "The model declined this request.");
        }
        if (message.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent()) {
            throw new LlmException("AI_TRUNCATED", "The answer exceeded the output limit.");
        }
        var text = new StringBuilder();
        message.content().forEach(block -> block.text().ifPresent(t -> text.append(t.text())));
        if (text.isEmpty()) throw new LlmException("AI_EMPTY", "The model returned no answer.");
        return new Completion(text.toString(), message.usage().inputTokens(), message.usage().outputTokens());
    }
}
