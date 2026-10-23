package com.enterprise.agentharness;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Prometheus metrics and the domain module registry. */
@SpringBootTest(properties = "eahp.worker.enabled=false")
@AutoConfigureMockMvc
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability // tests disable metric export by default
class OpsModulesApiTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final String ADMIN=UUID.randomUUID().toString(),OPERATOR=UUID.randomUUID().toString(),VIEWER=UUID.randomUUID().toString();
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private static EmbeddedPostgres startPostgres() {
        try { return EmbeddedPostgres.builder().setPort(0).start(); } catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("eahp.security.admin-token", () -> ADMIN);
        registry.add("eahp.security.operator-token", () -> OPERATOR);
        registry.add("eahp.security.viewer-token", () -> VIEWER);
    }
    @AfterAll static void stop() throws IOException { POSTGRES.close(); }

    @Test
    void prometheusMetricsNeedAViewerAndIncludeEahpGauges() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env").header("Authorization", "Bearer " + ADMIN)).andExpect(status().is4xxClientError());
        mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + VIEWER)).andExpect(status().isOk())
                .andExpect(content().string(containsString("eahp_runs{application=\"eahp\",status=\"QUEUED\"}")))
                .andExpect(content().string(containsString("eahp_approvals_pending")))
                .andExpect(content().string(containsString("eahp_runs{application=\"eahp\",status=\"TIMED_OUT\"}")))
                .andExpect(content().string(containsString("eahp_runs{application=\"eahp\",status=\"REJECTED\"}")))
                .andExpect(content().string(containsString("eahp_qa_results_24h{application=\"eahp\",outcome=\"PASSED\"}")))
                .andExpect(content().string(containsString("jvm_memory_used_bytes")));
    }

    @Test
    void modulesListTheirToolsAndApproversCanSwitchADomainOff() throws Exception {
        mvc.perform(get("/api/v1/modules").header("Authorization", "Bearer " + VIEWER)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("core")).andExpect(jsonPath("$[0].canDisable").value(false))
                .andExpect(jsonPath("$[1].key").value("qa")).andExpect(jsonPath("$[1].tools.length()").value(3))
                .andExpect(jsonPath("$[1].enabled").value(true));
        mvc.perform(put("/api/v1/admin/modules/qa/enabled").header("Authorization", "Bearer " + OPERATOR).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/modules/core/enabled").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/admin/modules/qa/enabled").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.tools[0].enabled").value(false))
                .andExpect(jsonPath("$.changedBy").value("admin"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tool_definitions WHERE name LIKE 'QA_%' AND enabled", Long.class)).isZero();
        mvc.perform(put("/api/v1/admin/modules/qa/enabled").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(jsonPath("$.enabled").value(true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tool_definitions WHERE name LIKE 'QA_%' AND enabled", Long.class)).isEqualTo(3);
        mvc.perform(put("/api/v1/admin/modules/nope/enabled").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isNotFound());
    }
}
