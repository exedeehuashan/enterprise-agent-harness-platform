package com.enterprise.agentharness.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class ModelConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(ModelConfiguration.class);
    @ParameterizedTest @ValueSource(strings={"deepseek","openai","claude"})
    void unsupportedProvidersCannotSilentlyUseMock(String name) {
        runner.withPropertyValues("eahp.model.provider="+name).run(context->{
            assertThat(context).hasFailed();assertThat(context.getStartupFailure()).hasStackTraceContaining("Only the mock model provider is implemented");
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
