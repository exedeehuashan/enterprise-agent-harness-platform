package com.enterprise.agentharness;

import com.enterprise.agentharness.model.ModelCallService;
import com.enterprise.agentharness.model.ModelGateway;
import com.enterprise.agentharness.model.ModelPlanner;
import com.enterprise.agentharness.model.ModelProvider;
import com.enterprise.agentharness.model.PlanRequest;
import com.enterprise.agentharness.plan.PlanExecutionService;
import com.enterprise.agentharness.run.DemoTool;
import com.enterprise.agentharness.run.RunService;
import com.enterprise.agentharness.run.RunWorker;
import com.enterprise.agentharness.task.AgentTask;
import com.enterprise.agentharness.task.AgentTaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
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
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PlanExecutionApiTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final String ADMIN=UUID.randomUUID().toString(),OPERATOR=UUID.randomUUID().toString(),VIEWER=UUID.randomUUID().toString();
    private static final String ANALYZE="{\"tool\":\"DEMO_ANALYZE\",\"toolVersion\":1,\"target\":\"local-demo\",\"timeoutSeconds\":30}";
    private static final String REVIEW="{\"tool\":\"DEMO_REVIEW\",\"toolVersion\":1,\"target\":\"local-demo\",\"timeoutSeconds\":30}";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AgentTaskRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModelCallService calls;
    @Autowired RunService runs;
    @Autowired DemoTool tool;
    @Autowired PlanExecutionService executions;

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
    void cleanDatabase() {
        repository.deleteAll();
        jdbc.update("DELETE FROM model_daily_budgets");
        jdbc.update("UPDATE tool_definitions SET enabled=TRUE,version=1,target='local-demo'");
    }

    private static String auth(String token){return "Bearer "+token;}
    private UUID task(){return repository.saveAndFlush(new AgentTask("Plan input","Task data")).getId();}
    private void worker(){new RunWorker(runs,tool).runOnce();}

    /** Produces a VALIDATED model call whose plan contains the given steps. */
    private UUID plan(UUID task,String... steps) {
        String json="{\"schemaVersion\":1,\"summary\":\"Test plan\",\"steps\":["+String.join(",",steps)+"]}";
        var call=calls.start(task,"plan-"+UUID.randomUUID(),new PlanRequest(512,2000));
        var provider=new ModelProvider(){
            public String id(){return "mock";}public String model(){return "mock-planner-v1";}
            public Result generate(Input input,int max){return new Result(json,10,(json.getBytes(StandardCharsets.UTF_8).length+3L)/4);}
        };
        var executor=Executors.newSingleThreadExecutor();
        try{new ModelPlanner(calls,new ModelGateway(provider,executor)).runOnce();}
        finally{executor.shutdownNow();}
        assertThat(calls.get(call.id()).status()).isEqualTo("VALIDATED");
        return call.id();
    }
    private ResultActions execute(UUID call,String key,String token) throws Exception {
        return mvc.perform(post("/api/v1/model-calls/"+call+"/executions").header("Authorization",auth(token))
                .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content("{}"));
    }
    private JsonNode started(UUID call) throws Exception {
        return mapper.readTree(execute(call,"exec-1",OPERATOR).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode view(String id) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/v1/plan-executions/"+id).header("Authorization",auth(VIEWER)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private UUID stepRun(String execution,int index) throws Exception {return UUID.fromString(view(execution).get("steps").get(index).get("runId").asText());}
    private void decide(UUID run,String decision) throws Exception {
        var detail=runs.get(run);
        mvc.perform(post("/api/v1/approvals/"+detail.approvalId()+"/decision").header("Authorization",auth(ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\""+decision+"\",\"expectedDigest\":\""+detail.inputDigest()
                        +"\",\"reasonCode\":\""+(decision.equals("APPROVE")?"REVIEWED":"RISK_NOT_ACCEPTED")+"\"}"))
                .andExpect(status().isOk());
    }
    private long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}

    @Test
    void multiStepPlanRunsInOrderAndEveryGatedStepNeedsItsOwnApproval() throws Exception {
        UUID task=task();UUID call=plan(task,ANALYZE,REVIEW,ANALYZE);
        var result=execute(call,"exec-1",OPERATOR).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING")).andExpect(jsonPath("$.requestedBy").value("operator"))
                .andExpect(jsonPath("$.planDigest").isNotEmpty())
                .andExpect(jsonPath("$.steps[0].status").value("LAUNCHED")).andExpect(jsonPath("$.steps[0].runStatus").value("QUEUED"))
                .andExpect(jsonPath("$.steps[1].status").value("PENDING")).andExpect(jsonPath("$.steps[1].runId").isEmpty())
                .andExpect(jsonPath("$.steps[2].status").value("PENDING")).andReturn();
        String id=mapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/v1/plan-executions/"+id);
        assertThat(count("agent_runs")).isEqualTo(1);

        worker();executions.advance();
        var afterFirst=view(id);
        assertThat(afterFirst.get("steps").get(0).get("status").asText()).isEqualTo("COMPLETED");
        assertThat(afterFirst.get("steps").get(1).get("runStatus").asText()).isEqualTo("WAITING_APPROVAL");
        worker();executions.advance();
        assertThat(view(id).get("steps").get(2).get("status").asText()).isEqualTo("PENDING");
        assertThat(count("tool_invocations")).isEqualTo(1);

        decide(stepRun(id,1),"APPROVE");worker();executions.advance();
        assertThat(view(id).get("steps").get(2).get("status").asText()).isEqualTo("LAUNCHED");
        worker();executions.advance();
        var done=view(id);
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");assertThat(done.get("finishedAt").isNull()).isFalse();
        for(JsonNode step:done.get("steps")) assertThat(step.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(repository.findById(task).orElseThrow().getStatus().name()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForList("""
                SELECT a.actor FROM audit_events a JOIN plan_execution_steps s ON s.run_id=a.run_id
                WHERE a.action='PLAN_STEP_LAUNCHED' AND s.execution_id=? ORDER BY a.id
                """,String.class,UUID.fromString(id))).containsExactly("operator","worker","worker");
        assertThat(jdbc.queryForObject("SELECT actor FROM audit_events WHERE action='RUN_CREATED' AND run_id=?",String.class,stepRun(id,0))).isEqualTo("operator");

        execute(call,"exec-1",OPERATOR).andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(id));
        execute(call,"exec-2",OPERATOR).andExpect(status().isConflict());
        assertThat(count("agent_runs")).isEqualTo(3);
    }

    @Test
    void rejectedApprovalStopsExecutionAndSkipsLaterSteps() throws Exception {
        String id=started(plan(task(),REVIEW,ANALYZE)).get("id").asText();
        decide(stepRun(id,0),"REJECT");executions.advance();
        var view=view(id);
        assertThat(view.get("status").asText()).isEqualTo("FAILED");assertThat(view.get("errorCode").asText()).isEqualTo("STEP_REJECTED");
        assertThat(view.get("steps").get(0).get("status").asText()).isEqualTo("FAILED");
        assertThat(view.get("steps").get(1).get("status").asText()).isEqualTo("SKIPPED");
        assertThat(count("agent_runs")).isEqualTo(1);
    }

    @Test
    void timedOutStepStopsExecutionWithoutRetry() throws Exception {
        String id=started(plan(task(),ANALYZE,ANALYZE)).get("id").asText();
        jdbc.update("UPDATE agent_runs SET deadline_at=clock_timestamp()-interval '1 second'");
        runs.recover();executions.advance();
        assertThat(view(id).get("errorCode").asText()).isEqualTo("STEP_TIMED_OUT");
        assertThat(count("agent_runs")).isEqualTo(1);
    }

    @Test
    void taskEditBetweenStepsStopsBeforeTheNextLaunch() throws Exception {
        UUID task=task();String id=started(plan(task,ANALYZE,ANALYZE)).get("id").asText();
        worker();
        jdbc.update("UPDATE agent_tasks SET description='changed after planning' WHERE id=?",task);
        executions.advance();
        var view=view(id);
        assertThat(view.get("errorCode").asText()).isEqualTo("TASK_CHANGED");
        assertThat(view.get("steps").get(0).get("status").asText()).isEqualTo("COMPLETED");
        assertThat(view.get("steps").get(1).get("status").asText()).isEqualTo("SKIPPED");
        assertThat(count("agent_runs")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings={"enabled=FALSE","version=2","target='production'"})
    void policyChangeBetweenStepsStopsBeforeTheNextLaunch(String mutation) throws Exception {
        String id=started(plan(task(),ANALYZE,REVIEW)).get("id").asText();
        worker();
        jdbc.update("UPDATE tool_definitions SET "+mutation+" WHERE name='DEMO_REVIEW'");
        executions.advance();
        assertThat(view(id).get("errorCode").asText()).isEqualTo("POLICY_CHANGED");
        assertThat(count("approval_requests")).isZero();
    }

    @Test
    void manualRunBetweenStepsMakesTheTaskBusy() throws Exception {
        UUID task=task();String id=started(plan(task,ANALYZE,ANALYZE)).get("id").asText();
        worker();
        mvc.perform(post("/api/v1/agent-tasks/"+task+"/runs").header("Authorization",auth(OPERATOR)).header("Idempotency-Key","manual")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isAccepted());
        executions.advance();
        assertThat(view(id).get("errorCode").asText()).isEqualTo("TASK_BUSY");
        assertThat(count("agent_runs")).isEqualTo(2);
    }

    @Test
    void cancellingWhileWaitingForApprovalCancelsRunAndApproval() throws Exception {
        String id=started(plan(task(),REVIEW,ANALYZE)).get("id").asText();
        mvc.perform(post("/api/v1/plan-executions/"+id+"/cancel").header("Authorization",auth(OPERATOR)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.steps[0].status").value("CANCELLED")).andExpect(jsonPath("$.steps[0].runStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.steps[1].status").value("SKIPPED"));
        assertThat(jdbc.queryForObject("SELECT status FROM approval_requests",String.class)).isEqualTo("CANCELLED");
        mvc.perform(post("/api/v1/plan-executions/"+id+"/cancel").header("Authorization",auth(OPERATOR)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        executions.advance();assertThat(count("agent_runs")).isEqualTo(1);
    }

    @Test
    void cancellingDuringARunningStepIsCooperativeAndLaunchesNothingMore() throws Exception {
        String id=started(plan(task(),ANALYZE,ANALYZE)).get("id").asText();
        var claim=runs.claim();assertThat(runs.authorize(claim)).isTrue();
        var cancelled=executions.cancel(UUID.fromString(id));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.steps().get(0).status()).isEqualTo("LAUNCHED");
        assertThat(runs.get(claim.run().id()).cancelRequested()).isTrue();
        runs.finish(claim.run().id(),claim.token(),"{}",false);executions.advance();
        var view=view(id);
        assertThat(view.get("steps").get(0).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(view.get("steps").get(1).get("status").asText()).isEqualTo("SKIPPED");
        assertThat(count("agent_runs")).isEqualTo(1);
    }

    @Test
    void externalRunCancellationStopsTheExecution() throws Exception {
        String id=started(plan(task(),ANALYZE,ANALYZE)).get("id").asText();
        mvc.perform(post("/api/v1/agent-runs/"+stepRun(id,0)+"/cancel").header("Authorization",auth(OPERATOR))).andExpect(status().isOk());
        executions.advance();
        assertThat(view(id).get("errorCode").asText()).isEqualTo("STEP_CANCELLED");
    }

    @Test
    void preconditionsRejectStaleUnvalidatedOrConflictingPlans() throws Exception {
        UUID task=task();
        UUID queued=calls.start(task,"queued",new PlanRequest(512,2000)).id();
        execute(queued,"exec-1",OPERATOR).andExpect(status().isConflict());
        new ModelPlanner(calls,new ModelGateway(new ModelProvider(){
            public String id(){return "mock";}public String model(){return "mock-planner-v1";}
            public Result generate(Input input,int max){return new Result("{\"schemaVersion\":2}",10,5);}
        },Executors.newSingleThreadExecutor())).runOnce();
        assertThat(calls.get(queued).status()).isEqualTo("FAILED");
        execute(queued,"exec-1",OPERATOR).andExpect(status().isConflict());

        UUID stale=plan(task,ANALYZE);
        jdbc.update("UPDATE agent_tasks SET title='edited' WHERE id=?",task);
        execute(stale,"exec-1",OPERATOR).andExpect(status().isConflict());

        UUID other=task();UUID disabled=plan(other,REVIEW);
        jdbc.update("UPDATE tool_definitions SET enabled=FALSE WHERE name='DEMO_REVIEW'");
        execute(disabled,"exec-1",OPERATOR).andExpect(status().isConflict());
        jdbc.update("UPDATE tool_definitions SET enabled=TRUE");

        UUID busy=plan(other,ANALYZE);
        runs.start(other,"manual",new com.enterprise.agentharness.run.StartRunRequest("DEMO_ANALYZE",30));
        execute(busy,"exec-1",OPERATOR).andExpect(status().isConflict());
        assertThat(count("plan_executions")).isZero();
        assertThat(count("agent_runs")).isEqualTo(1);
    }

    @Test
    void onlyOneActiveExecutionPerTask() throws Exception {
        UUID task=task();UUID first=plan(task,REVIEW),second=plan(task,ANALYZE);
        started(first);
        execute(second,"exec-2",OPERATOR).andExpect(status().isConflict());
        assertThat(count("plan_executions")).isEqualTo(1);
    }

    @Test
    void authenticationRolesAndRequestValidation() throws Exception {
        UUID task=task();UUID call=plan(task,ANALYZE);String path="/api/v1/model-calls/"+call+"/executions";
        mvc.perform(post(path).header("Idempotency-Key","k").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        execute(call,"exec-1",VIEWER).andExpect(status().isForbidden());
        mvc.perform(post(path).header("Authorization",auth(OPERATOR)).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        execute(call,"bad key!",OPERATOR).andExpect(status().isBadRequest());
        for(String body:new String[]{"{\"steps\":[]}","[]","\"x\""}) {
            mvc.perform(post(path).header("Authorization",auth(OPERATOR)).header("Idempotency-Key","k").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        execute(UUID.randomUUID(),"exec-1",OPERATOR).andExpect(status().isNotFound());
        assertThat(count("plan_executions")).isZero();

        String id=started(call).get("id").asText();
        mvc.perform(post("/api/v1/plan-executions/"+id+"/cancel").header("Authorization",auth(VIEWER))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/plan-executions/"+UUID.randomUUID()).header("Authorization",auth(VIEWER))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/agent-tasks/"+task+"/plan-executions").header("Authorization",auth(VIEWER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].id").value(id));
        mvc.perform(get("/api/v1/agent-tasks/"+task+"/plan-executions?status=RUNNING").header("Authorization",auth(VIEWER))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/agent-tasks/"+UUID.randomUUID()+"/plan-executions").header("Authorization",auth(VIEWER))).andExpect(status().isNotFound());
    }
}
