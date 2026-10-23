package com.enterprise.agentharness.model;

import com.enterprise.agentharness.ai.AiConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class ModelConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(AiConfiguration.class, ModelConfiguration.class);
    @ParameterizedTest @ValueSource(strings={"openai","gpt","gemini"})
    void unknownProvidersFailStartup(String name) {
        runner.withPropertyValues("eahp.ai.provider="+name).run(context->{
            assertThat(context).hasFailed();assertThat(context.getStartupFailure()).hasStackTraceContaining("EAHP_AI_PROVIDER must be mock, anthropic or deepseek");
        });
    }
    @ParameterizedTest @ValueSource(strings={"anthropic","deepseek"})
    void aRealProviderWithoutItsKeyCannotSilentlyUseMock(String name) {
        runner.withPropertyValues("eahp.ai.provider="+name).run(context->{
            assertThat(context).hasFailed();assertThat(context.getStartupFailure()).hasStackTraceContaining("_API_KEY");
        });
    }
    @ParameterizedTest @ValueSource(strings={"anthropic","deepseek"})
    void aConfiguredRealProviderPlansWithTheModel(String name) {
        runner.withPropertyValues("eahp.ai.provider="+name, "eahp.ai.anthropic.api-key=test-key", "eahp.ai.deepseek.api-key=test-key").run(context->{
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ModelProvider.class)).isInstanceOf(LlmPlanProvider.class);
            assertThat(context.getBean(ModelProvider.class).id()).isEqualTo(name);
        });
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","1000000001"})
    void invalidBudgetFailsStartup(String value) {
        runner.withPropertyValues("eahp.model.daily-token-budget="+value).run(context->{
            assertThat(context).hasFailed();assertThat(context.getStartupFailure()).hasStackTraceContaining("Model daily token budget must be");
        });
    }
    @Test void defaultProviderIsExplicitlyMock() {
        runner.run(context->{assertThat(context).hasNotFailed();assertThat(context.getBean(ModelProvider.class)).isInstanceOf(MockModelProvider.class);});
    }
}
