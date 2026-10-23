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

@SpringBootTest(properties = "eahp.worker.enabled=false")
@AutoConfigureMockMvc
class ApiTokenApiTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final String ADMIN=UUID.randomUUID().toString(),OPERATOR=UUID.randomUUID().toString(),VIEWER=UUID.randomUUID().toString();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
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
    @BeforeEach void reset() { jdbc.update("DELETE FROM api_tokens"); }

    private ResultActions issue(String token, String body) throws Exception {
        return mvc.perform(post("/api/v1/admin/tokens").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private JsonNode issued(String body) throws Exception {
        return mapper.readTree(issue(ADMIN, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void approversIssueNamedTokensThatActWithTheirRoles() throws Exception {
        issue(OPERATOR, "{\"name\":\"alice\",\"roles\":[\"OPERATOR\"]}").andExpect(status().isForbidden());
        for (String bad : new String[] {"{\"name\":\"admin\",\"roles\":[]}", "{\"name\":\"Alice\",\"roles\":[]}", "{\"name\":\"alice\",\"roles\":[\"ROOT\"]}",
                "{\"name\":\"alice\",\"roles\":[],\"expiresInDays\":0}", "{\"name\":\"alice\",\"roles\":[],\"x\":1}"}) {
            issue(ADMIN, bad).andExpect(status().isBadRequest());
        }
        var alice = issued("{\"name\":\"alice\",\"description\":\"QA lead\",\"roles\":[\"OPERATOR\"],\"expiresInDays\":30}");
        String secret = alice.get("secret").asText();
        assertThat(secret).startsWith("eahp_").hasSize(48);
        assertThat(alice.get("token").get("roles").toString()).isEqualTo("[\"OPERATOR\",\"VIEWER\"]");
        issue(ADMIN, "{\"name\":\"alice\",\"roles\":[]}").andExpect(status().isConflict());

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + secret)).andExpect(jsonPath("$.name").value("alice"))
                .andExpect(jsonPath("$.roles[0]").value("OPERATOR")).andExpect(jsonPath("$.roles[1]").value("VIEWER"));
        String task = mapper.readTree(mvc.perform(post("/api/v1/agent-tasks").header("Authorization", "Bearer " + secret).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"By alice\",\"description\":null}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        assertThat(task).isNotBlank();
        mvc.perform(get("/api/v1/admin/tokens").header("Authorization", "Bearer " + secret)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/tokens").header("Authorization", "Bearer " + ADMIN)).andExpect(jsonPath("$[0].name").value("alice"))
                .andExpect(jsonPath("$[0].lastUsedAt").isString()).andExpect(jsonPath("$[0].secret").doesNotExist()).andExpect(jsonPath("$[0].tokenHash").doesNotExist());

        mvc.perform(post("/api/v1/admin/tokens/" + alice.get("token").get("id").asText() + "/revoke").header("Authorization", "Bearer " + ADMIN))
                .andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.revokedBy").value("admin"));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + secret)).andExpect(status().isUnauthorized());
        issued("{\"name\":\"alice\",\"roles\":[]}"); // the name is free again after revocation
    }

    @Test
    void expiredAndUnknownTokensAreRejected() throws Exception {
        var bot = issued("{\"name\":\"ci-bot\",\"roles\":[\"VIEWER\"],\"expiresInDays\":1}");
        String secret = bot.get("secret").asText();
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + secret)).andExpect(jsonPath("$.name").value("ci-bot"));
        mvc.perform(post("/api/v1/agent-tasks").header("Authorization", "Bearer " + secret).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"x\",\"description\":null}")).andExpect(status().isForbidden());
        jdbc.update("UPDATE api_tokens SET expires_at=clock_timestamp()-interval '1 second'");
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + secret)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer eahp_" + "x".repeat(43))).andExpect(status().isUnauthorized());
    }
}
