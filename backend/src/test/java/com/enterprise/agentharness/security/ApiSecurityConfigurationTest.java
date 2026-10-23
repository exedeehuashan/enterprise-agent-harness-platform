package com.enterprise.agentharness.security;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class ApiSecurityConfigurationTest {
    private final WebApplicationContextRunner runner=new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,SecurityAutoConfiguration.class,org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration.class))
            .withUserConfiguration(ApiSecurity.class);
    @Test void missingAdminCredentialFailsClosed() {
        runner.run(context->{assertThat(context).hasFailed();assertThat(context.getStartupFailure()).hasStackTraceContaining("Configure a random EAHP_SECURITY_ADMIN_TOKEN");});
    }
    @Test void invalidOptionalCredentialFailsClosed() {
        runner.withPropertyValues("eahp.security.admin-token="+UUID.randomUUID(),"eahp.security.operator-token=short")
                .run(context->{assertThat(context).hasFailed();assertThat(context.getStartupFailure()).hasStackTraceContaining("Invalid configured API credential");});
    }
    @Test void duplicateCredentialsFailClosed() {
        String token=UUID.randomUUID().toString();
        runner.withPropertyValues("eahp.security.admin-token="+token,"eahp.security.viewer-token="+token)
                .run(context->{assertThat(context).hasFailed();assertThat(context.getStartupFailure()).hasStackTraceContaining("API credentials must be distinct");});
    }
    @Test void randomAdminCredentialBuildsFilterChain() {
        runner.withPropertyValues("eahp.security.admin-token="+UUID.randomUUID())
                .run(context->{assertThat(context).hasNotFailed();assertThat(context).hasSingleBean(org.springframework.security.web.SecurityFilterChain.class);});
    }
}
