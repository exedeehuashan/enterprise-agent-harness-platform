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
@AutoConfigureMockMvc
class AgentTaskEditApiTest {
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

    @Autowired com.enterprise.agentharness.task.AgentTaskService service;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    private String create() throws Exception {
        var r = mvc.perform(post(API).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Original\",\"description\":\"Keep me\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.version").value(0)).andReturn();
        return API + "/" + mapper.readTree(r.getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void partialEditPreservesOtherFieldsAndRejectsStaleWriter() throws Exception {
        String path = create();
        var before = mapper.readTree(mvc.perform(get(path)).andExpect(header().string("ETag", "\"0\""))
                .andReturn().getResponse().getContentAsString());
        var r = mvc.perform(patch(path).header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Edited\"}"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.title").value("Edited")).andExpect(jsonPath("$.description").value("Keep me"))
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.version").value(1)).andReturn();
        var after = mapper.readTree(r.getResponse().getContentAsString());
        assertThat(after.get("id")).isEqualTo(before.get("id"));
        assertThat(after.get("createdAt")).isEqualTo(before.get("createdAt"));
        assertThat(java.time.Instant.parse(after.get("updatedAt").asText()))
                .isAfter(java.time.Instant.parse(before.get("updatedAt").asText()));
        mvc.perform(patch(path).header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Lost update\"}"))
                .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.status").value(412));
        mvc.perform(get(path)).andExpect(jsonPath("$.title").value("Edited"));
    }

    @Test
    void descriptionCanBeClearedAndNoopDoesNotBumpVersion() throws Exception {
        String path = create();
        mvc.perform(patch(path).header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.description").isEmpty())
                .andExpect(header().string("ETag", "\"1\""));
        mvc.perform(patch(path).header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Original\"}"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{", "{\"status\":\"COMPLETED\"}",
            "{\"version\":1}", "{\"id\":\"x\"}", "{\"title\":null}", "{\"title\":1}",
            "{\"title\":\" \"}", "{\"description\":1}", "{\"description\":{}}"})
    void rejectsInvalidEditsWithoutChanges(String body) throws Exception {
        String path = create();
        mvc.perform(patch(path).header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.path").value(path))
                .andExpect(jsonPath("$.fieldErrors").isArray());
        mvc.perform(get(path)).andExpect(jsonPath("$.title").value("Original")).andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void validatesLengthsAndAcceptsLimits() throws Exception {
        String path = create();
        mvc.perform(patch(path).header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("title", "x".repeat(256), "description", "x".repeat(10001)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.length()").value(2));
        mvc.perform(patch(path).header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("title", "x".repeat(255), "description", "x".repeat(10000)))))
                .andExpect(status().isOk());
    }

    @Test
    void requiresVersionAndHandlesMissingTask() throws Exception {
        String path = create();
        mvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"New\"}"))
                .andExpect(status().is(428)).andExpect(jsonPath("$.status").value(428));
        mvc.perform(patch(API + "/" + UUID.randomUUID()).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"New\"}"))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "*", "W/\"0\"", "\"-1\"", "\"01\"", "\"0\",\"1\"", "\"9223372036854775808\""})
    void rejectsUnsupportedVersionHeaders(String value) throws Exception {
        mvc.perform(patch(create()).header("If-Match", value).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"New\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void databaseVersionPreventsConcurrentLostUpdate() throws Exception {
        String path = create();
        UUID id = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (String title : new String[]{"Writer A", "Writer B"}) {
                futures.add(pool.submit(() -> {
                    try {
                        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                            repository.findById(id).orElseThrow();
                            try { barrier.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                            catch (Exception ex) { throw new RuntimeException(ex); }
                            service.edit(id, 0, new com.enterprise.agentharness.task.TaskPatch(true, title, false, null));
                        });
                        return true;
                    } catch (org.springframework.orm.ObjectOptimisticLockingFailureException ex) { return false; }
                }));
            }
            var results = new java.util.ArrayList<Boolean>();
            for (var f : futures) results.add(f.get(20, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder(true, false);
            assertThat(repository.findById(id).orElseThrow().getVersion()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void migrationPreservesExistingVersionOneData() throws Exception {
        String path = create();
        jdbc.execute("ALTER TABLE agent_tasks DROP COLUMN version");
        jdbc.update("DELETE FROM flyway_schema_history WHERE version = '2'");
        org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).load().migrate();
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Original"))
                .andExpect(jsonPath("$.version").value(0));
    }
}
