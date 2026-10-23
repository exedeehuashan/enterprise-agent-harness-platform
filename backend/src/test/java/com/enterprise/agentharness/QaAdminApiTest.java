package com.enterprise.agentharness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class QaAdminApiTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final String ADMIN=UUID.randomUUID().toString(),OPERATOR=UUID.randomUUID().toString(),VIEWER=UUID.randomUUID().toString();
    private static final String BASE="/api/v1/qa/admin/projects";
    private static final String NEW_PROJECT="{\"key\":\"billing\",\"name\":\"Billing\",\"repositoryUrl\":\"https://github.com/example/billing.git\","
            +"\"allowedImages\":[\"maven:3.9.11-eclipse-temurin-17\",\"gradle:8.10.2-jdk17\"],\"maxTimeoutSeconds\":1200}";
    private static final String UNIT_SUITE="{\"name\":\"Unit\",\"image\":\"gradle:8.10.2-jdk17\",\"command\":[\"gradle\",\"test\"],"
            +"\"reportDirs\":[\"build/test-results/test\"],\"network\":\"egress\",\"dockerAccess\":false,\"maxTimeoutSeconds\":900}";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    private static EmbeddedPostgres startPostgres() {
        try { return EmbeddedPostgres.builder().setPort(0).start(); }
        catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
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

    @AfterAll
    static void stopPostgres() throws IOException { POSTGRES.close(); }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM qa_projects WHERE key<>'online-shop'");
        jdbc.update("DELETE FROM qa_admin_events");
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        if (body != null) request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request.header("Authorization", "Bearer " + token));
    }
    private JsonNode json(ResultActions r) throws Exception { return mapper.readTree(r.andReturn().getResponse().getContentAsString()); }

    @Test
    void approversOnboardAProjectWithPolicySuitesTriggersAndGate() throws Exception {
        var created = json(send(post(BASE), ADMIN, NEW_PROJECT).andExpect(status().isCreated()));
        assertThat(created.get("project").get("defaultRef").asText()).isEqualTo("main");
        assertThat(created.get("project").get("allowDockerAccess").asBoolean()).isFalse();
        assertThat(created.get("project").get("repositorySuitesEnabled").asBoolean()).isFalse();
        send(post(BASE), ADMIN, NEW_PROJECT).andExpect(status().isConflict());

        send(put(BASE + "/billing/suites/unit"), ADMIN, UNIT_SUITE).andExpect(status().isOk())
                .andExpect(jsonPath("$.project.suites[0].key").value("unit")).andExpect(jsonPath("$.project.suites[0].command[1]").value("test"));
        send(put(BASE + "/billing/triggers/unit"), ADMIN, "{\"enabled\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.triggers[0].suite").value("unit")).andExpect(jsonPath("$.triggers[0].enabled").value(true));
        send(put(BASE + "/billing/gate"), ADMIN, "{\"requiredSuites\":[\"unit\",\"unit\"]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.gateSuites.length()").value(1));
        send(patch(BASE + "/billing"), ADMIN, "{\"repositorySuitesEnabled\":true,\"defaultRef\":\"develop\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.project.repositorySuitesEnabled").value(true)).andExpect(jsonPath("$.project.defaultRef").value("develop"))
                .andExpect(jsonPath("$.project.name").value("Billing"));
        send(delete(BASE + "/billing/gate"), ADMIN, null).andExpect(status().isOk()).andExpect(jsonPath("$.gateSuites.length()").value(0));
        send(put(BASE + "/billing/triggers/unit"), ADMIN, "{\"enabled\":true}").andExpect(status().isOk()); // no change, no event

        // The new project is immediately part of the normal catalog.
        send(get("/api/v1/qa/projects"), VIEWER, null).andExpect(jsonPath("$[0].key").value("billing"));

        var events = json(send(get("/api/v1/qa/admin/events").param("project", "billing"), ADMIN, null).andExpect(status().isOk()));
        assertThat(events).extracting(e -> e.get("action").asText())
                .containsExactly("GATE_REMOVED", "PROJECT_UPDATED", "GATE_SET", "TRIGGER_SET", "SUITE_CREATED", "PROJECT_CREATED");
        assertThat(events).allMatch(e -> e.get("actor").asText().equals("admin"));
        var update = events.get(1);
        assertThat(update.get("before").get("defaultRef").asText()).isEqualTo("main");
        assertThat(update.get("after").get("defaultRef").asText()).isEqualTo("develop");
    }

    @Test
    void onlyApproversCanAdministerAndEveryCallIsAuthenticated() throws Exception {
        send(post(BASE), OPERATOR, NEW_PROJECT).andExpect(status().isForbidden());
        send(post(BASE), VIEWER, NEW_PROJECT).andExpect(status().isForbidden());
        send(get(BASE + "/online-shop"), VIEWER, null).andExpect(status().isForbidden());
        send(get("/api/v1/qa/admin/events"), OPERATOR, null).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/online-shop")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM qa_projects", Long.class)).isEqualTo(1);
        send(get(BASE + "/online-shop"), ADMIN, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.gateSuites[0]").value("backend-unit")).andExpect(jsonPath("$.triggers.length()").value(2));
    }

    @Test
    void invalidInputIsRejectedWithReasons() throws Exception {
        String[][] badProjects = {
                {"{\"key\":\"Bad Key\",\"name\":\"x\",\"repositoryUrl\":\"https://github.com/a/b\"}", "key must be lowercase"},
                {"{\"key\":\"x2\",\"name\":\"x\",\"repositoryUrl\":\"http://github.com/a/b\"}", "repositoryUrl must be an https URL"},
                {"{\"key\":\"x2\",\"name\":\"x\",\"repositoryUrl\":\"https://github.com/a/../b\"}", "repositoryUrl must be an https URL"},
                {"{\"key\":\"x2\",\"name\":\"x\",\"repositoryUrl\":\"https://github.com/a/b\",\"allowedImages\":[\"alpine\"]}", "image must be name:tag"},
                {"{\"key\":\"x2\",\"name\":\"x\",\"repositoryUrl\":\"https://github.com/a/b\",\"suitesFile\":\"../etc/passwd\"}", "suitesFile must be a relative path"},
                {"{\"key\":\"x2\",\"name\":\"x\",\"repositoryUrl\":\"https://github.com/a/b\",\"maxTimeoutSeconds\":99999}", "maxTimeoutSeconds must be 30..3600"},
                {"{\"key\":\"x2\",\"name\":\"x\",\"repositoryUrl\":\"https://github.com/a/b\",\"shell\":\"rm\"}", "Unknown field: shell"},
                {"{\"key\":\"x2\",\"repositoryUrl\":\"https://github.com/a/b\"}", "name is required"}};
        for (var c : badProjects) {
            send(post(BASE), ADMIN, c[0]).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith(c[1])));
        }
        send(post(BASE), ADMIN, NEW_PROJECT).andExpect(status().isCreated());
        send(put(BASE + "/billing/suites/unit"), ADMIN, UNIT_SUITE.replace("gradle:8.10.2-jdk17", "attacker/miner:latest")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("image attacker/miner:latest is not allowed for this project"));
        send(put(BASE + "/billing/suites/unit"), ADMIN, UNIT_SUITE.replace("\"dockerAccess\":false", "\"dockerAccess\":true")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("this project does not allow Docker access"));
        send(put(BASE + "/billing/suites/unit"), ADMIN, UNIT_SUITE.replace("build/test-results/test", "/etc")).andExpect(status().isBadRequest());
        send(put(BASE + "/billing/suites/Bad"), ADMIN, UNIT_SUITE).andExpect(status().isBadRequest());
        send(put(BASE + "/billing/suites/unit"), ADMIN, UNIT_SUITE).andExpect(status().isOk());
        send(patch(BASE + "/billing"), ADMIN, "{\"allowedImages\":[\"maven:3.9.11-eclipse-temurin-17\"]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("registry suite unit would violate the new policy")));
        send(patch(BASE + "/billing"), ADMIN, "{\"key\":\"other\"}").andExpect(status().isBadRequest());
        send(put(BASE + "/billing/gate"), ADMIN, "{\"requiredSuites\":[]}").andExpect(status().isBadRequest());
        send(put(BASE + "/billing/triggers/unit"), ADMIN, "{\"enabled\":\"yes\"}").andExpect(status().isBadRequest());
        send(patch(BASE + "/nope"), ADMIN, "{\"name\":\"x\"}").andExpect(status().isNotFound());
        send(get("/api/v1/qa/admin/events").param("limit", "0"), ADMIN, null).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM qa_admin_events WHERE project_key='billing'", Long.class)).isEqualTo(2);
    }
}
