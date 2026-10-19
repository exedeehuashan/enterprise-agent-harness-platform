package com.enterprise.agentharness;

import com.enterprise.agentharness.task.AgentTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class AgentTaskApiTest {
    private static final String API = "/api/v1/agent-tasks";
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AgentTaskRepository repository;
    @Autowired JdbcTemplate jdbc;

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().setPort(0).start();
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @AfterAll
    static void stopPostgres() throws IOException { POSTGRES.close(); }

    @BeforeEach
    void cleanDatabase() { repository.deleteAll(); }

    @Test
    void appliesFlywayMigrationToPostgres() {
        assertThat(jdbc.queryForObject("select version()", String.class)).startsWith("PostgreSQL");
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success = true",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void createsAndLooksUpPersistedTask() throws Exception {
        var result = mvc.perform(post(API).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Run analysis\",\"description\":\"Inspect a report\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Run analysis"))
                .andExpect(jsonPath("$.description").value("Inspect a report"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty()).andReturn();
        var created = mapper.readTree(result.getResponse().getContentAsString());
        UUID id = UUID.fromString(created.get("id").asText());
        assertThat(repository.findById(id)).isPresent();
        assertThat(created.get("createdAt")).isEqualTo(created.get("updatedAt"));
        String location = API + "/" + id;
        assertThat(result.getResponse().getHeader("Location")).isEqualTo(location);
        var lookup = mvc.perform(get(location)).andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(lookup.getResponse().getContentAsString())).isEqualTo(created);
    }

    @Test
    void descriptionIsOptional() throws Exception {
        mvc.perform(post(API).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Task\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"title\":null}", "{\"title\":\"\"}", "{\"title\":\"   \"}"})
    void rejectsMissingOrBlankTitle(String body) throws Exception {
        mvc.perform(post(API).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.path").value(API))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("title"));
        assertThat(repository.count()).isZero();
    }

    @Test
    void rejectsOversizedFields() throws Exception {
        String body = mapper.writeValueAsString(Map.of("title", "x".repeat(256),
                "description", "x".repeat(10001)));
        mvc.perform(post(API).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));
        assertThat(repository.count()).isZero();
    }

    @Test
    void acceptsMaximumFieldLengths() throws Exception {
        String body = mapper.writeValueAsString(Map.of("title", "x".repeat(255),
                "description", "x".repeat(10000)));
        mvc.perform(post(API).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void returnsStructuredNotFound() throws Exception {
        String path = API + "/" + UUID.randomUUID();
        mvc.perform(get(path)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.path").value(path))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void rejectsInvalidUuid() throws Exception {
        mvc.perform(get(API + "/invalid")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "", "null"})
    void rejectsMalformedOrMissingBody(String body) throws Exception {
        mvc.perform(post(API).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed or missing JSON request body"));
    }

    @Test
    void unsupportedMethodUsesStructuredError() throws Exception {
        mvc.perform(delete(API + "/" + UUID.randomUUID()))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.status").value(405));
    }
}
