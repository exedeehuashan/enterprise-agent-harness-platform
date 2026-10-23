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
class AgentRunApiTest {
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

    @Autowired com.enterprise.agentharness.run.RunService runs;
    @Autowired com.enterprise.agentharness.run.DemoTool tool;

    private UUID task() {
        return repository.saveAndFlush(new com.enterprise.agentharness.task.AgentTask("Snapshot title", "Snapshot description")).getId();
    }
    private com.enterprise.agentharness.run.RunResponse start(UUID task, String key) {
        return runs.start(task, key, new com.enterprise.agentharness.run.StartRunRequest("DEMO_ANALYZE", 30));
    }
    private void expireLease(UUID id) { jdbc.update("UPDATE agent_runs SET lease_until=now()-interval '1 second' WHERE id=?", id); }
    private void expireDeadline(UUID id) { jdbc.update("UPDATE agent_runs SET deadline_at=now()-interval '1 second' WHERE id=?", id); }

    @Test
    void acceptsDurableRunAndReplaysEvenAfterCompletion() throws Exception {
        UUID task = task();
        var r = mvc.perform(post(API + "/" + task + "/runs").header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.snapshotTitle").value("Snapshot title"))
                .andExpect(jsonPath("$.steps[0].status").value("QUEUED")).andReturn();
        var body = mapper.readTree(r.getResponse().getContentAsString());
        UUID id = UUID.fromString(body.get("id").asText());
        assertThat(r.getResponse().getHeader("Location")).isEqualTo("/api/v1/agent-runs/"+id);
        assertThat(start(task,"key-1").id()).isEqualTo(id);
        assertThat(repository.findById(task).orElseThrow().getStatus().name()).isEqualTo("RUNNING");
        new com.enterprise.agentharness.run.RunWorker(runs,tool).runOnce();
        mvc.perform(get("/api/v1/agent-runs/"+id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps[0].output").isNotEmpty());
        assertThat(start(task,"key-1").id()).isEqualTo(id);
        assertThat(repository.findById(task).orElseThrow().getStatus().name()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runs",Long.class)).isEqualTo(1);
        mvc.perform(post(API + "/" + task + "/runs").header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"timeoutSeconds\":31}"))
                .andExpect(status().isConflict());
    }

    @Test
    void activeRunBlocksNewKeyAndSnapshotsSurviveEditing() throws Exception {
        UUID task = task();
        var run = start(task,"first");
        mvc.perform(post(API+"/"+task+"/runs").header("Idempotency-Key","second")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
        var current = repository.findById(task).orElseThrow();
        mvc.perform(patch(API+"/"+task).header("If-Match","\""+current.getVersion()+"\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Future title\"}"))
                .andExpect(status().isOk());
        assertThat(runs.get(run.id()).snapshotTitle()).isEqualTo("Snapshot title");
        assertThat(start(task,"first").snapshotTitle()).isEqualTo("Snapshot title");
        runs.cancel(run.id());
        assertThat(start(task,"second").snapshotTitle()).isEqualTo("Future title");
    }

    @Test
    void queuedCancellationIsTerminalAndIdempotent() throws Exception {
        var run=start(task(),"cancel");
        mvc.perform(post("/api/v1/agent-runs/"+run.id()+"/cancel"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(runs.claim()).isNull();
        var cancelled=runs.get(run.id());
        assertThat(runs.cancel(run.id()).finishedAt()).isEqualTo(cancelled.finishedAt());
        assertThat(cancelled.steps().get(0).status()).isEqualTo("CANCELLED");
        assertThat(repository.findById(run.taskId()).orElseThrow().getStatus().name()).isEqualTo("FAILED");
    }

    @Test
    void runningCancellationIsCooperative() throws Exception {
        var run=start(task(),"cancel-running");
        var claim=runs.claim();assertThat(runs.authorize(claim)).isTrue();
        mvc.perform(post("/api/v1/agent-runs/"+run.id()+"/cancel"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.cancelRequested").value(true));
        runs.finish(run.id(),claim.token(),"not published",false);
        assertThat(runs.get(run.id()).status()).isEqualTo("CANCELLED");
        assertThat(runs.get(run.id()).steps().get(0).output()).isNull();
    }

    @Test
    void queuedAndRunningDeadlinesBecomeTimedOut() {
        var first=start(task(),"timeout-queued");
        expireDeadline(first.id());runs.recover();
        assertThat(runs.get(first.id()).status()).isEqualTo("TIMED_OUT");
        var second=start(task(),"timeout-running");
        var claim=runs.claim();assertThat(runs.authorize(claim)).isTrue();expireDeadline(second.id());
        runs.finish(second.id(),claim.token(),"late output",false);
        assertThat(runs.get(second.id()).status()).isEqualTo("TIMED_OUT");
        assertThat(runs.get(second.id()).errorCode()).isEqualTo("DEADLINE_EXCEEDED");
    }

    @Test
    void expiredLeaseRetriesOnceAndFencesStaleCompletion() {
        var run=start(task(),"recovery");
        var first=runs.claim();assertThat(runs.authorize(first)).isTrue();expireLease(run.id());
        runs.finish(run.id(),first.token(),"expired",false);
        assertThat(runs.get(run.id()).status()).isEqualTo("RUNNING");
        runs.recover();assertThat(runs.get(run.id()).status()).isEqualTo("QUEUED");
        var second=runs.claim();assertThat(runs.authorize(second)).isTrue();assertThat(second.token()).isNotEqualTo(first.token());
        runs.finish(run.id(),first.token(),"stale",false);
        assertThat(runs.get(run.id()).status()).isEqualTo("RUNNING");
        runs.finish(run.id(),second.token(),"current",false);
        assertThat(runs.get(run.id()).status()).isEqualTo("COMPLETED");
        assertThat(runs.get(run.id()).attemptCount()).isEqualTo(2);
        assertThat(runs.get(run.id()).steps().get(0).output()).isEqualTo("current");
    }

    @Test
    void secondWorkerLossFailsAndCancelledLossIsNotRetried() {
        var first=start(task(),"lost");runs.claim();expireLease(first.id());runs.recover();
        runs.claim();expireLease(first.id());runs.recover();
        assertThat(runs.get(first.id()).errorCode()).isEqualTo("WORKER_LOST");
        assertThat(runs.get(first.id()).status()).isEqualTo("FAILED");
        var second=start(task(),"cancel-lost");runs.claim();runs.cancel(second.id());expireLease(second.id());runs.recover();
        assertThat(runs.get(second.id()).status()).isEqualTo("CANCELLED");
    }

    @Test
    void toolFailureProducesSanitizedFailure() {
        var run=start(task(),"failure");
        var failing=new com.enterprise.agentharness.run.DemoTool() {
            @Override public String execute(com.enterprise.agentharness.run.RunResponse r) { throw new IllegalStateException("sensitive error"); }
        };
        new com.enterprise.agentharness.run.RunWorker(runs,failing).runOnce();
        assertThat(runs.get(run.id()).status()).isEqualTo("FAILED");
        assertThat(runs.get(run.id()).errorCode()).isEqualTo("TOOL_FAILED");
        assertThat(runs.get(run.id()).steps().get(0).output()).isNull();
    }

    @Test
    void historyIsPagedAndCountsAreTaskScoped() throws Exception {
        UUID task=task();var first=start(task,"one");runs.cancel(first.id());var second=start(task,"two");
        start(task(),"other");
        mvc.perform(get(API+"/"+task+"/runs").param("size","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.items[0].id").value(second.id().toString()));
        mvc.perform(get(API+"/"+task+"/runs").param("page","2").param("size","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings={"[]","null","{","{\"tool\":\"SHELL\"}","{\"tool\":null}","{\"timeoutSeconds\":0}",
            "{\"timeoutSeconds\":301}","{\"timeoutSeconds\":1.5}","{\"timeoutSeconds\":null}","{\"timeoutSeconds\":2147483648}","{\"other\":1}"})
    void rejectsInvalidRunRequests(String body) throws Exception {
        mvc.perform(post(API+"/"+task()+"/runs").header("Idempotency-Key","invalid")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runs",Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings={"", "space key", "a,b", "中文"})
    void rejectsInvalidKeys(String key) throws Exception {
        mvc.perform(post(API+"/"+task()+"/runs").header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingLongAndRepeatedKeysAndInvalidHistoryQueries() throws Exception {
        UUID task=task();
        mvc.perform(post(API+"/"+task+"/runs").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(API+"/"+task+"/runs").header("Idempotency-Key","x".repeat(129))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(API+"/"+task+"/runs").header("Idempotency-Key","one","two")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        for(String[] pair:new String[][]{{"page","-1"},{"size","101"},{"sort","createdAt,asc"},{"status","FAILED"}}) {
            mvc.perform(get(API+"/"+task+"/runs").param(pair[0],pair[1])).andExpect(status().isBadRequest());
        }
    }

    @Test
    void missingResourcesAndInvalidUuidUseStructuredErrors() throws Exception {
        UUID id=UUID.randomUUID();
        mvc.perform(post(API+"/"+id+"/runs").header("Idempotency-Key","missing")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(get(API+"/"+id+"/runs")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/agent-runs/"+id)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/agent-runs/"+id+"/cancel")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/agent-runs/invalid")).andExpect(status().isBadRequest());
    }

    @Test
    void concurrentIdenticalStartsCreateOnlyOneRun() throws Exception {
        UUID task=task();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        try {
            var a=pool.submit(() -> {barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);return start(task,"same").id();});
            var b=pool.submit(() -> {barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);return start(task,"same").id();});
            assertThat(a.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(b.get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runs",Long.class)).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }

    @Test
    void concurrentWorkersCannotClaimOneRunTwice() throws Exception {
        start(task(),"claim");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        try {
            var a=pool.submit(() -> {barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);return runs.claim();});
            var b=pool.submit(() -> {barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);return runs.claim();});
            var first=a.get(20,java.util.concurrent.TimeUnit.SECONDS);
            var second=b.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(java.util.stream.Stream.of(first,second).filter(java.util.Objects::nonNull).count()).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
    @Test
    void concurrentDifferentKeysPermitOnlyOneActiveRun() throws Exception {
        UUID task=task();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        try {
            java.util.concurrent.Callable<Boolean> first=() -> {
                barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);
                try { start(task,"first");return true; }
                catch (com.enterprise.agentharness.run.RunException ex) {
                    assertThat(ex.status().value()).isEqualTo(409);return false;
                }
            };
            java.util.concurrent.Callable<Boolean> second=() -> {
                barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);
                try { start(task,"second");return true; }
                catch (com.enterprise.agentharness.run.RunException ex) {
                    assertThat(ex.status().value()).isEqualTo(409);return false;
                }
            };
            var a=pool.submit(first);var b=pool.submit(second);
            assertThat(java.util.List.of(a.get(20,java.util.concurrent.TimeUnit.SECONDS),b.get(20,java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true,false);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runs",Long.class)).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }

    @Test
    void migrationV3PreservesExistingTaskVersionAndData() {
        String schema="v2_upgrade_test";
        try {
            org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource())
                    .schemas(schema).defaultSchema(schema).target("2").load().migrate();
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO "+schema+".agent_tasks(id,title,status,created_at,updated_at,version) VALUES (?,?,'PENDING',now(),now(),7)",id,"Preserved");
            org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource())
                    .schemas(schema).defaultSchema(schema).target("3").load().migrate();
            assertThat(jdbc.queryForObject("SELECT title FROM "+schema+".agent_tasks WHERE id=?",String.class,id)).isEqualTo("Preserved");
            assertThat(jdbc.queryForObject("SELECT version FROM "+schema+".agent_tasks WHERE id=?",Long.class,id)).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+schema+".agent_runs",Long.class)).isZero();
        } finally {jdbc.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
    }

}
