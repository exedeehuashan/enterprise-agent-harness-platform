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
class ApprovalSecurityApiTest {
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
        registry.add("eahp.security.admin-token", () -> ADMIN);
        registry.add("eahp.security.operator-token", () -> OPERATOR);
        registry.add("eahp.security.viewer-token", () -> VIEWER);
    }

    @AfterAll
    static void stopPostgres() throws IOException { POSTGRES.close(); }

    @BeforeEach
    void cleanDatabase() { repository.deleteAll(); }

    private static final String ADMIN=UUID.randomUUID().toString();
    private static final String OPERATOR=UUID.randomUUID().toString();
    private static final String VIEWER=UUID.randomUUID().toString();
    @Autowired com.enterprise.agentharness.run.RunService runs;
    @Autowired com.enterprise.agentharness.run.DemoTool tool;
    @Autowired com.enterprise.agentharness.governance.ApprovalService approvalService;
    private static String bearer(String token) {return "Bearer "+token;}
    private com.fasterxml.jackson.databind.JsonNode gated() throws Exception {
        var task=mvc.perform(post(API).header("Authorization",bearer(OPERATOR)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Sensitive task title\",\"description\":\"Never put this text in audit events\"}"))
                .andExpect(status().isCreated()).andReturn();
        String id=mapper.readTree(task.getResponse().getContentAsString()).get("id").asText();
        var result=mvc.perform(post(API+"/"+id+"/runs").header("Authorization",bearer(OPERATOR)).header("Idempotency-Key","review")
                .contentType(MediaType.APPLICATION_JSON).content("{\"tool\":\"DEMO_REVIEW\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("WAITING_APPROVAL")).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }
    private String decision(com.fasterxml.jackson.databind.JsonNode run,String decision) throws Exception {
        return mapper.writeValueAsString(Map.of("decision",decision,"expectedDigest",run.get("inputDigest").asText(),"reasonCode","REVIEWED"));
    }
    private void approve(com.fasterxml.jackson.databind.JsonNode run) throws Exception {
        mvc.perform(post("/api/v1/approvals/"+run.get("approvalId").asText()+"/decision").header("Authorization",bearer(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(decision(run,"APPROVE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
    }
    private UUID runId(com.fasterxml.jackson.databind.JsonNode run){return UUID.fromString(run.get("id").asText());}
    private void worker(){new com.enterprise.agentharness.run.RunWorker(runs,tool).runOnce();}
    private long invocationCount(UUID id){return jdbc.queryForObject("SELECT count(*) FROM tool_invocations WHERE run_id=?",Long.class,id);}

    @org.junit.jupiter.api.AfterEach
    void restorePolicies() {
        jdbc.update("UPDATE tool_definitions SET version=1,target='local-demo',enabled=TRUE,requires_approval=(name='DEMO_REVIEW'),retryable=(name='DEMO_ANALYZE')");
    }

    @Test
    void anonymousAndBadCredentialsAreRejected() throws Exception {
        mvc.perform(get(API)).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate","Bearer"))
                .andExpect(jsonPath("$.status").value(401)).andExpect(jsonPath("$.fieldErrors").isArray());
        mvc.perform(post(API).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        for(String header:new String[]{"Basic abc","Bearer invalid","Bearer "+UUID.randomUUID()}) {
            mvc.perform(get(API).header("Authorization",header)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get(API).header("Authorization",bearer(ADMIN),bearer(OPERATOR))).andExpect(status().isUnauthorized());
        mvc.perform(get(API).param("token",ADMIN)).andExpect(status().isUnauthorized());
    }

    @Test
    void viewerCannotWriteAndOperatorCannotApproveOrReadAudit() throws Exception {
        mvc.perform(get(API).header("Authorization",bearer(VIEWER))).andExpect(status().isOk());
        mvc.perform(post(API).header("Authorization",bearer(VIEWER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"x\"}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        var run=gated();String id=run.get("approvalId").asText();
        mvc.perform(post("/api/v1/approvals/"+id+"/decision").header("Authorization",bearer(OPERATOR))
                .contentType(MediaType.APPLICATION_JSON).content(decision(run,"APPROVE"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/approvals/"+id).header("Authorization",bearer(OPERATOR))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit-events").header("Authorization",bearer(OPERATOR)).param("runId",run.get("id").asText()))
                .andExpect(status().isForbidden());
        assertThat(invocationCount(runId(run))).isZero();
    }

    @Test
    void humanApprovalIsConsumedOnceAndAuditUsesTrustedActors() throws Exception {
        var run=gated();UUID id=runId(run);
        worker();assertThat(runs.get(id).status()).isEqualTo("WAITING_APPROVAL");assertThat(invocationCount(id)).isZero();
        mvc.perform(get("/api/v1/tools").header("Authorization",bearer(VIEWER))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        approve(run);worker();
        assertThat(runs.get(id).status()).isEqualTo("COMPLETED");assertThat(invocationCount(id)).isEqualTo(1);
        mvc.perform(get("/api/v1/approvals/"+run.get("approvalId").asText()).header("Authorization",bearer(ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONSUMED"))
                .andExpect(jsonPath("$.decidedBy").value("admin"));
        mvc.perform(post("/api/v1/approvals/"+run.get("approvalId").asText()+"/decision").header("Authorization",bearer(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(decision(run,"APPROVE"))).andExpect(status().isConflict());
        var events=mvc.perform(get("/api/v1/audit-events").header("Authorization",bearer(ADMIN)).param("runId",id.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(events).contains("operator","admin","worker","APPROVAL_CONSUMED","TOOL_DISPATCHED","RUN_COMPLETED")
                .doesNotContain(ADMIN,OPERATOR,VIEWER,"Sensitive task title","Never put this text");
        var inv=mvc.perform(get("/api/v1/agent-runs/"+id+"/invocations").header("Authorization",bearer(VIEWER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("COMPLETED")).andReturn().getResponse().getContentAsString();
        assertThat(inv).doesNotContain("claimToken","executionToken");
    }

    @Test
    void rejectionStopsExecutionAndReapprovalIsNotAllowed() throws Exception {
        var run=gated();
        mvc.perform(post("/api/v1/approvals/"+run.get("approvalId").asText()+"/decision").header("Authorization",bearer(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(decision(run,"REJECT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        worker();assertThat(runs.get(runId(run)).status()).isEqualTo("REJECTED");assertThat(invocationCount(runId(run))).isZero();
    }

    @Test
    void expiryAtDecisionPersistsTerminalState() throws Exception {
        var run=gated();jdbc.update("UPDATE approval_requests SET expires_at=now()-interval '1 second' WHERE run_id=?",runId(run));
        mvc.perform(post("/api/v1/approvals/"+run.get("approvalId").asText()+"/decision").header("Authorization",bearer(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(decision(run,"APPROVE")))
                .andExpect(status().isGone()).andExpect(jsonPath("$.status").value("EXPIRED"));
        assertThat(runs.get(runId(run)).status()).isEqualTo("EXPIRED");worker();assertThat(invocationCount(runId(run))).isZero();
    }

    @Test
    void approvedButExpiredBeforeDispatchDoesNotExecute() throws Exception {
        var run=gated();approve(run);jdbc.update("UPDATE approval_requests SET expires_at=now()-interval '1 second' WHERE run_id=?",runId(run));
        worker();assertThat(runs.get(runId(run)).status()).isEqualTo("EXPIRED");assertThat(invocationCount(runId(run))).isZero();
    }

    @Test
    void changedSnapshotInvalidatesApproval() throws Exception {
        var run=gated();approve(run);jdbc.update("UPDATE agent_runs SET snapshot_title='tampered' WHERE id=?",runId(run));
        worker();assertThat(runs.get(runId(run)).errorCode()).isEqualTo("APPROVAL_INVALIDATED");assertThat(invocationCount(runId(run))).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings={"enabled=FALSE","version=2","target='other'","requires_approval=FALSE","retryable=TRUE"})
    void changedPolicyCannotWeakenGate(String mutation) throws Exception {
        var run=gated();approve(run);jdbc.update("UPDATE tool_definitions SET "+mutation+" WHERE name='DEMO_REVIEW'");
        worker();assertThat(runs.get(runId(run)).errorCode()).isEqualTo("POLICY_CHANGED");assertThat(invocationCount(runId(run))).isZero();
    }

    @Test
    void bypassingQueueStateCannotBypassApproval() throws Exception {
        var run=gated();jdbc.update("UPDATE agent_runs SET status='QUEUED',deadline_at=now()+interval '1 minute' WHERE id=?",runId(run));
        worker();assertThat(runs.get(runId(run)).errorCode()).isEqualTo("APPROVAL_INVALIDATED");assertThat(invocationCount(runId(run))).isZero();
    }

    @Test
    void missingApprovalCannotDispatch() throws Exception {
        var run=gated();jdbc.update("DELETE FROM approval_requests WHERE run_id=?",runId(run));
        jdbc.update("UPDATE agent_runs SET status='QUEUED' WHERE id=?",runId(run));
        worker();assertThat(runs.get(runId(run)).errorCode()).isEqualTo("APPROVAL_REQUIRED");assertThat(invocationCount(runId(run))).isZero();
    }

    @Test
    void crossRunDigestIsRejectedWithoutChangingApproval() throws Exception {
        var first=gated();var second=gated();
        var body=Map.of("decision","APPROVE","expectedDigest",second.get("inputDigest").asText(),"reasonCode","REVIEWED");
        mvc.perform(post("/api/v1/approvals/"+first.get("approvalId").asText()+"/decision").header("Authorization",bearer(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body))).andExpect(status().isConflict());
        assertThat(runs.get(runId(first)).status()).isEqualTo("WAITING_APPROVAL");
        approve(first);
    }

    @Test
    void pendingCancellationAndExpiryPreventExecution() throws Exception {
        var first=gated();runs.cancel(runId(first));
        assertThat(jdbc.queryForObject("SELECT status FROM approval_requests WHERE run_id=?",String.class,runId(first))).isEqualTo("CANCELLED");
        var second=gated();jdbc.update("UPDATE agent_runs SET deadline_at=now()-interval '1 second' WHERE id=?",runId(second));runs.recover();
        assertThat(runs.get(runId(second)).status()).isEqualTo("EXPIRED");worker();assertThat(invocationCount(runId(first))).isZero();assertThat(invocationCount(runId(second))).isZero();
    }

    @Test
    void waitingApprovalBlocksAnotherRunAndInputEditsDoNotChangeSnapshot() throws Exception {
        var run=gated();String task=run.get("taskId").asText();
        mvc.perform(post(API+"/"+task+"/runs").header("Authorization",bearer(OPERATOR)).header("Idempotency-Key","new")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isConflict());
        var current=repository.findById(UUID.fromString(task)).orElseThrow();
        mvc.perform(patch(API+"/"+task).header("Authorization",bearer(OPERATOR)).header("If-Match","\""+current.getVersion()+"\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Changed task\"}"))
                .andExpect(status().isOk());
        assertThat(runs.get(runId(run)).inputDigest()).isEqualTo(run.get("inputDigest").asText());approve(run);worker();
        assertThat(runs.get(runId(run)).snapshotTitle()).isEqualTo("Sensitive task title");
    }

    @Test
    void consumedApprovalIsNotRetriedAfterWorkerLoss() throws Exception {
        var run=gated();approve(run);var claim=runs.claim();assertThat(runs.authorize(claim)).isTrue();
        jdbc.update("UPDATE agent_runs SET lease_until=now()-interval '1 second' WHERE id=?",runId(run));runs.recover();
        assertThat(runs.get(runId(run)).status()).isEqualTo("NEEDS_REVIEW");assertThat(runs.claim()).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM tool_invocations WHERE run_id=?",String.class,runId(run))).isEqualTo("UNKNOWN");
        runs.finish(runId(run),claim.token(),"stale",false);assertThat(runs.get(runId(run)).status()).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void duplicateDispatchIsFenced() throws Exception {
        var run=gated();approve(run);var claim=runs.claim();
        assertThat(runs.authorize(claim)).isTrue();assertThat(runs.authorize(claim)).isFalse();assertThat(invocationCount(runId(run))).isEqualTo(1);
        runs.finish(runId(run),claim.token(),"safe",false);assertThat(runs.get(runId(run)).status()).isEqualTo("COMPLETED");
    }

    @ParameterizedTest
    @ValueSource(strings={"{}","null","[]","{\"decision\":\"APPROVE\"}","{\"actor\":\"admin\"}"})
    void rejectsMalformedDecision(String body) throws Exception {
        var run=gated();mvc.perform(post("/api/v1/approvals/"+run.get("approvalId").asText()+"/decision").header("Authorization",bearer(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        assertThat(invocationCount(runId(run))).isZero();
    }

    @Test
    void approvalListAuditPaginationAndMissingResources() throws Exception {
        var run=gated();
        mvc.perform(get("/api/v1/approvals").header("Authorization",bearer(ADMIN)).param("size","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/v1/audit-events").header("Authorization",bearer(ADMIN)).param("runId",run.get("id").asText()).param("size","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/v1/audit-events").header("Authorization",bearer(ADMIN))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/approvals").header("Authorization",bearer(ADMIN)).param("size","101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/approvals/"+UUID.randomUUID()).header("Authorization",bearer(ADMIN))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/agent-runs/"+UUID.randomUUID()+"/invocations").header("Authorization",bearer(VIEWER))).andExpect(status().isNotFound());
    }

    @Test
    void policyChangeBeforeDecisionInvalidatesWithoutRollback() throws Exception {
        var run=gated();jdbc.update("UPDATE tool_definitions SET enabled=FALSE WHERE name='DEMO_REVIEW'");
        mvc.perform(post("/api/v1/approvals/"+run.get("approvalId").asText()+"/decision").header("Authorization",bearer(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(decision(run,"APPROVE")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value("INVALIDATED"));
        assertThat(runs.get(runId(run)).status()).isEqualTo("FAILED");assertThat(invocationCount(runId(run))).isZero();
    }

    @Test
    void concurrentDecisionsOnlyAcceptOne() throws Exception {
        var run=gated();String body=decision(run,"APPROVE");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var barrier=new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.Callable<Integer> call=()->{
            barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);
            return mvc.perform(post("/api/v1/approvals/"+run.get("approvalId").asText()+"/decision").header("Authorization",bearer(ADMIN))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
        };
        try {var a=pool.submit(call);var b=pool.submit(call);
            assertThat(java.util.List.of(a.get(20,java.util.concurrent.TimeUnit.SECONDS),b.get(20,java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200,409);
        } finally {pool.shutdownNow();}
        worker();assertThat(invocationCount(runId(run))).isEqualTo(1);
    }
    @Test
    void migrationV4PreservesQueuedV3RunAndItsSnapshot() {
        String schema="v3_upgrade_test";
        try {
            org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource())
                    .schemas(schema).defaultSchema(schema).target("3").load().migrate();
            UUID task=UUID.randomUUID(),run=UUID.randomUUID();
            jdbc.update("INSERT INTO "+schema+".agent_tasks(id,title,status,created_at,updated_at,version) VALUES (?,?,'RUNNING',now(),now(),3)",task,"Original task");
            jdbc.update("INSERT INTO "+schema+".agent_runs(id,task_id,idempotency_key,timeout_seconds,tool,snapshot_title,status,deadline_at) VALUES (?,?,'old-key',30,'DEMO_ANALYZE','Original snapshot','QUEUED',now()+interval '1 minute')",run,task);
            jdbc.update("INSERT INTO "+schema+".run_steps(run_id,sequence,tool,status) VALUES (?,1,'DEMO_ANALYZE','QUEUED')",run);
            org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource())
                    .schemas(schema).defaultSchema(schema).target("4").load().migrate();
            assertThat(jdbc.queryForObject("SELECT snapshot_title FROM "+schema+".agent_runs WHERE id=?",String.class,run)).isEqualTo("Original snapshot");
            assertThat(jdbc.queryForObject("SELECT tool_version FROM "+schema+".agent_runs WHERE id=?",Integer.class,run)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT target FROM "+schema+".agent_runs WHERE id=?",String.class,run)).isEqualTo("local-demo");
            assertThat(jdbc.queryForObject("SELECT version FROM "+schema+".agent_tasks WHERE id=?",Long.class,task)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+schema+".tool_definitions",Long.class)).isEqualTo(2);
        } finally {jdbc.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
    }

    @Test
    void changedSnapshotBeforeDecisionInvalidatesWithoutExecution() throws Exception {
        var run=gated();jdbc.update("UPDATE agent_runs SET timeout_seconds=31 WHERE id=?",runId(run));
        mvc.perform(post("/api/v1/approvals/"+run.get("approvalId").asText()+"/decision").header("Authorization",bearer(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(decision(run,"APPROVE")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value("INVALIDATED"));
        worker();assertThat(invocationCount(runId(run))).isZero();
    }

    @Test
    void workerWithoutHumanIdentityCannotCallDecisionService() throws Exception {
        var run=gated();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->approvalService.decide(UUID.fromString(run.get("approvalId").asText()),
                new com.enterprise.agentharness.governance.ApprovalService.Decision("APPROVE",run.get("inputDigest").asText(),"REVIEWED")))
                .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
        assertThat(runs.get(runId(run)).status()).isEqualTo("WAITING_APPROVAL");
    }

    @Test
    void changingControlledToolToUngatedToolInvalidatesExistingApproval() throws Exception {
        var run=gated();approve(run);jdbc.update("UPDATE agent_runs SET tool='DEMO_ANALYZE' WHERE id=?",runId(run));
        worker();assertThat(runs.get(runId(run)).errorCode()).isEqualTo("APPROVAL_INVALIDATED");assertThat(invocationCount(runId(run))).isZero();
    }

}
