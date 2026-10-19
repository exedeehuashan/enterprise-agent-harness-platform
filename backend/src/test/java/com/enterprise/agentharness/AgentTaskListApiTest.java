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
class AgentTaskListApiTest {
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

    private void fixture(int id, String state, String time) {
        jdbc.update("insert into agent_tasks (id,title,status,created_at,updated_at) values (?,?,?,?::timestamptz,?::timestamptz)",
                UUID.fromString("00000000-0000-0000-0000-%012d".formatted(id)), "Task " + id, state, time, time);
    }

    @Test
    void emptyListAndMaximumSize() throws Exception {
        mvc.perform(get(API)).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.totalPages").value(0));
        mvc.perform(get(API).param("size", "100")).andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void defaultLimitAndTiedTimestampOrdering() throws Exception {
        for (int i = 1; i <= 21; i++) fixture(i, "PENDING", "2026-10-01T00:00:00Z");
        mvc.perform(get(API)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.items[0].title").value("Task 21"))
                .andExpect(jsonPath("$.items[19].title").value("Task 2"))
                .andExpect(jsonPath("$.totalElements").value(21)).andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void chronologicalOrderPagesAndOutOfRange() throws Exception {
        fixture(1, "PENDING", "2026-10-01T00:00:00Z");
        fixture(2, "PENDING", "2026-10-01T00:00:00Z");
        fixture(3, "PENDING", "2026-10-02T00:00:00Z");
        mvc.perform(get(API).param("size", "2").param("sort", "createdAt,asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].title").value("Task 1"))
                .andExpect(jsonPath("$.items[1].title").value("Task 2"));
        mvc.perform(get(API).param("page", "1").param("size", "2").param("sort", "createdAt,asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Task 3"))
                .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get(API).param("page", "2").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get(API).param("sort", "createdAt,desc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].title").value("Task 3"))
                .andExpect(jsonPath("$.items[1].title").value("Task 2"))
                .andExpect(jsonPath("$.items[2].title").value("Task 1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "RUNNING", "COMPLETED", "FAILED"})
    void filtersBeforePagingWithoutChangingData(String state) throws Exception {
        int i = 1;
        for (String value : new String[]{"PENDING", "RUNNING", "COMPLETED", "FAILED"}) {
            fixture(i++, value, "2026-10-01T00:00:00Z");
            fixture(i++, value, "2026-10-01T00:00:00Z");
        }
        mvc.perform(get(API).param("status", state).param("size", "1").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value(state))
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2));
        assertThat(repository.count()).isEqualTo(8);
        assertThat(jdbc.queryForObject("select count(*) from agent_tasks where status = ?", Integer.class, state)).isEqualTo(2);
    }

    @Test
    void unmatchedFilterReturnsEmpty() throws Exception {
        fixture(1, "PENDING", "2026-10-01T00:00:00Z");
        mvc.perform(get(API).param("status", "FAILED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "page=", "page=abc", "page=1.5", "page=2147483648", "page=2147483647",
            "size=0", "size=-1", "size=101", "size=", "size=2147483648", "status=", "status=pending",
            "status=UNKNOWN", "sort=", "sort=id,asc", "sort=createdAt,DESC", "sort=createdAt", "unknown=1"})
    void invalidQueriesUseStructuredErrors(String parameter) throws Exception {
        String[] parts = parameter.split("=", -1);
        mvc.perform(get(API).param(parts[0], parts[1])).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400)).andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty()).andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value(API)).andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"page", "size", "status", "sort"})
    void rejectsRepeatedParameters(String parameter) throws Exception {
        mvc.perform(get(API).param(parameter, "1", "1")).andExpect(status().isBadRequest());
    }
}
