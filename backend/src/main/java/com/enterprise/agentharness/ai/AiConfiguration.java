package com.enterprise.agentharness.ai;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the model provider: {@code EAHP_AI_PROVIDER} = {@code mock} (default, no network), {@code anthropic}
 * (Claude; {@code ANTHROPIC_API_KEY}, optional {@code ANTHROPIC_MODEL}, default claude-opus-5-5) or {@code deepseek}
 * ({@code DEEPSEEK_API_KEY}, optional {@code DEEPSEEK_MODEL}, default deepseek-chat). A selected provider without its
 * key fails startup, so a misconfiguration is never silently replaced by the mock.
 */
@Configuration
public class AiConfiguration {
    @Bean
    LlmProvider llmProvider(@Value("${eahp.ai.provider:mock}") String provider,
            @Value("${eahp.ai.anthropic.api-key:}") String anthropicKey, @Value("${eahp.ai.anthropic.model:claude-opus-5-5}") String anthropicModel,
            @Value("${eahp.ai.anthropic.effort:medium}") String anthropicEffort, @Value("${eahp.ai.anthropic.base-url:}") String anthropicBaseUrl,
            @Value("${eahp.ai.anthropic.fallbacks:true}") boolean fallbacks,
            @Value("${eahp.ai.deepseek.api-key:}") String deepseekKey, @Value("${eahp.ai.deepseek.model:deepseek-chat}") String deepseekModel,
            @Value("${eahp.ai.deepseek.base-url:}") String deepseekBaseUrl, @Value("${eahp.ai.timeout-seconds:180}") int timeoutSeconds) {
        var timeout = Duration.ofSeconds(timeoutSeconds);
        return switch (provider.trim().toLowerCase()) {
            case "mock", "" -> new MockLlm();
            case "anthropic", "claude" -> {
                if (anthropicKey.isBlank()) throw new IllegalStateException("EAHP_AI_PROVIDER=anthropic needs ANTHROPIC_API_KEY");
                if (!anthropicEffort.matches("(?i)low|medium|high|xhigh|max")) throw new IllegalStateException("ANTHROPIC_EFFORT must be low, medium, high, xhigh or max");
                yield new AnthropicLlm(anthropicKey, anthropicModel, anthropicEffort, anthropicBaseUrl, fallbacks, timeout);
            }
            case "deepseek" -> {
                if (deepseekKey.isBlank()) throw new IllegalStateException("EAHP_AI_PROVIDER=deepseek needs DEEPSEEK_API_KEY");
                yield new DeepSeekLlm(deepseekKey, deepseekModel, deepseekBaseUrl, timeout);
            }
            default -> throw new IllegalStateException("EAHP_AI_PROVIDER must be mock, anthropic or deepseek");
        };
    }
}
