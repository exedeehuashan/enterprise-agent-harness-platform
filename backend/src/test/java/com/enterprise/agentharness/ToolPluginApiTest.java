package com.enterprise.agentharness;

import com.enterprise.agentharness.governance.ToolCatalog;
import com.enterprise.agentharness.model.ModelFailure;
import com.enterprise.agentharness.model.PlanValidator;
import com.enterprise.agentharness.run.RunResponse;
import com.enterprise.agentharness.run.RunService;
import com.enterprise.agentharness.run.RunWorker;
import com.enterprise.agentharness.task.AgentTask;
import com.enterprise.agentharness.task.AgentTaskRepository;
import com.enterprise.agentharness.tool.ToolContext;
import com.enterprise.agentharness.tool.ToolExecutor;
import com.enterprise.agentharness.tool.ToolRegistry;
import com.enterprise.agentharness.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ToolPluginApiTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final String ADMIN=UUID.randomUUID().toString(),OPERATOR=UUID.randomUUID().toString(),VIEWER=UUID.randomUUID().toString();
    private static final byte[] REPORT="<testsuite tests=\"2\" failures=\"0\"/>".getBytes(StandardCharsets.UTF_8);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AgentTaskRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired RunService runs;
    @Autowired ToolRegistry registry;
    @Autowired ToolCatalog catalog;
    @Autowired PlanValidator validator;

    /** Test-only executor: behaviour is selected by its validated "mode" argument. */
    record Probe(String name, boolean requiresApproval) implements ToolExecutor {
        static final Set<String> MODES=Set.of("pass","fail","spoof","throw","big","badname","dup");
        public int version() { return 1; }
        public String target() { return "local-test"; }
        public boolean retryable() { return false; }
        public JsonNode normalize(JsonNode arguments) {
            if (arguments==null || !arguments.isObject()) throw new IllegalArgumentException("mode is required");
            var names=arguments.fieldNames();
            while (names.hasNext()) if (!Set.of("mode","note").contains(names.next())) throw new IllegalArgumentException("unknown field");
            if (!arguments.path("mode").isTextual() || !MODES.contains(arguments.get("mode").textValue())) throw new IllegalArgumentException("unsupported mode");
            if (arguments.has("note") && !arguments.get("note").isTextual()) throw new IllegalArgumentException("note must be text");
            return arguments;
        }
        public ToolResult execute(ToolContext context) {
            var report=new ToolResult.Artifact("report.xml","application/xml",REPORT);
            return switch (context.arguments().get("mode").textValue()) {
                case "pass" -> ToolResult.success("2 passed", report, new ToolResult.Artifact("run.log","text/plain","ok\n".getBytes(StandardCharsets.UTF_8)));
                case "fail" -> ToolResult.failure("TESTS_FAILED", "1 of 2 tests failed", report);
                case "spoof" -> ToolResult.failure("WORKER_LOST", "pretending", report);
                case "throw" -> throw new IllegalStateException("secret detail");
                case "big" -> ToolResult.success("x", new ToolResult.Artifact("big.bin","application/octet-stream",new byte[5*1024*1024+1]));
                case "badname" -> ToolResult.success("x", new ToolResult.Artifact("../escape","text/plain",new byte[1]));
                default -> ToolResult.success("x", report, report);
            };
        }
    }

    @TestConfiguration
    static class TestTools {
        @Bean ToolExecutor probeTool() { return new Probe("TEST_PROBE", false); }
        @Bean ToolExecutor gatedProbeTool() { return new Probe("TEST_GATED", true); }
    }

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
    void reset() {
        repository.deleteAll();
        jdbc.update("DELETE FROM tool_definitions WHERE name LIKE 'TEST_%' OR name='GHOST'");
        jdbc.update("INSERT INTO tool_definitions VALUES ('TEST_PROBE',1,'local-test',TRUE,FALSE,FALSE),('TEST_GATED',1,'local-test',TRUE,TRUE,FALSE)");
    }

    private static String auth(String token){return "Bearer "+token;}
    private UUID task(){return repository.saveAndFlush(new AgentTask("Probe task","input")).getId();}
    private void worker(){new RunWorker(runs,registry).runOnce();}
    private ResultActions start(UUID task,String key,String body) throws Exception {
        return mvc.perform(post("/api/v1/agent-tasks/"+task+"/runs").header("Authorization",auth(OPERATOR)).header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private UUID started(UUID task,String mode) throws Exception {
        var body=start(task,"k-"+mode,"{\"tool\":\"TEST_PROBE\",\"arguments\":{\"mode\":\""+mode+"\"}}").andExpect(status().isAccepted()).andReturn();
        return UUID.fromString(mapper.readTree(body.getResponse().getContentAsString()).get("id").asText());
    }
    private JsonNode artifacts(UUID run) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/v1/agent-runs/"+run+"/artifacts").header("Authorization",auth(VIEWER)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void passingToolKeepsOutputAndDownloadableArtifactsWithDigests() throws Exception {
        UUID task=task();
        start(task,"pass","{\"tool\":\"TEST_PROBE\",\"arguments\":{\"note\":\"n\",\"mode\":\"pass\"}}").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.arguments.mode").value("pass"));
        UUID run=runs.history(task,0,1).items().get(0).id();
        assertThat(jdbc.queryForObject("SELECT arguments FROM agent_runs WHERE id=?",String.class,run)).isEqualTo("{\"mode\":\"pass\",\"note\":\"n\"}");
        worker();
        RunResponse done=runs.get(run);
        assertThat(done.status()).isEqualTo("COMPLETED");assertThat(done.steps().get(0).output()).isEqualTo("2 passed");

        JsonNode list=artifacts(run);
        assertThat(list).hasSize(2);
        JsonNode report=list.get(1).get("name").asText().equals("report.xml")?list.get(1):list.get(0);
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(REPORT));
        assertThat(report.get("sha256").asText()).isEqualTo(sha);
        assertThat(report.get("sizeBytes").asInt()).isEqualTo(REPORT.length);
        assertThat(report.get("attempt").asInt()).isEqualTo(1);
        assertThat(report.get("invocationId").asText()).isEqualTo(jdbc.queryForObject("SELECT id::text FROM tool_invocations WHERE run_id=?",String.class,run));
        var content=mvc.perform(get("/api/v1/artifacts/"+report.get("id").asText()+"/content").header("Authorization",auth(VIEWER)))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type","application/xml"))
                .andExpect(header().string("Content-Disposition","attachment; filename=\"report.xml\""))
                .andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(header().string("Content-Security-Policy","sandbox; default-src 'none'"))
                .andExpect(header().string("ETag","\""+sha+"\"")).andReturn();
        assertThat(content.getResponse().getContentAsByteArray()).isEqualTo(REPORT);
        mvc.perform(get("/api/v1/artifacts/"+report.get("id").asText()).header("Authorization",auth(VIEWER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("report.xml"));
        mvc.perform(get("/api/v1/artifacts/"+UUID.randomUUID()+"/content").header("Authorization",auth(VIEWER))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/agent-runs/"+UUID.randomUUID()+"/artifacts").header("Authorization",auth(VIEWER))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/agent-runs/"+run+"/artifacts")).andExpect(status().isUnauthorized());
    }

    @Test
    void failingToolIsNeverReportedAsSuccessAndKeepsEvidence() throws Exception {
        UUID task=task();UUID run=started(task,"fail");worker();
        var failed=runs.get(run);
        assertThat(failed.status()).isEqualTo("FAILED");assertThat(failed.errorCode()).isEqualTo("TESTS_FAILED");
        assertThat(failed.steps().get(0).output()).isEqualTo("1 of 2 tests failed");
        assertThat(artifacts(run)).hasSize(1);
        assertThat(repository.findById(task).orElseThrow().getStatus().name()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM tool_invocations WHERE run_id=?",String.class,run)).isEqualTo("FAILED");
    }

    @Test
    void toolsCannotSpoofPlatformCodesAndExceptionsAreSanitized() throws Exception {
        UUID spoof=started(task(),"spoof");worker();
        assertThat(runs.get(spoof).errorCode()).isEqualTo("TOOL_FAILED");
        UUID thrown=started(task(),"throw");worker();
        var run=runs.get(thrown);
        assertThat(run.errorCode()).isEqualTo("TOOL_FAILED");assertThat(run.steps().get(0).output()).isNull();
        assertThat(artifacts(thrown)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings={"big","badname","dup"})
    void invalidArtifactsFailTheRunAndAreNotStored(String mode) throws Exception {
        UUID run=started(task(),mode);worker();
        assertThat(runs.get(run).status()).isEqualTo("FAILED");
        assertThat(runs.get(run).errorCode()).isEqualTo(mode.equals("big")?"ARTIFACT_LIMIT":"ARTIFACT_INVALID");
        assertThat(artifacts(run)).isEmpty();
    }

    @Test
    void argumentsAreValidatedByTheToolAndBoundToIdempotency() throws Exception {
        UUID task=task();
        start(task,"a","{\"tool\":\"GHOST\"}").andExpect(status().isBadRequest());
        start(task,"a","{\"tool\":\"TEST_PROBE\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid arguments: mode is required"));
        start(task,"a","{\"tool\":\"TEST_PROBE\",\"arguments\":[]}").andExpect(status().isBadRequest());
        start(task,"a","{\"tool\":\"TEST_PROBE\",\"arguments\":{\"mode\":\"rm -rf\"}}").andExpect(status().isBadRequest());
        start(task,"a","{\"tool\":\"TEST_PROBE\",\"arguments\":{\"mode\":\"pass\",\"cmd\":\"x\"}}").andExpect(status().isBadRequest());
        start(task,"a","{\"tool\":\"DEMO_ANALYZE\",\"arguments\":{\"mode\":\"pass\"}}").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runs",Long.class)).isZero();

        String first=mapper.readTree(start(task,"same","{\"tool\":\"TEST_PROBE\",\"arguments\":{\"mode\":\"pass\",\"note\":\"a\"}}")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).get("id").asText();
        start(task,"same","{\"tool\":\"TEST_PROBE\",\"arguments\":{\"note\":\"a\",\"mode\":\"pass\"}}").andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(first));
        start(task,"same","{\"tool\":\"TEST_PROBE\",\"arguments\":{\"mode\":\"fail\",\"note\":\"a\"}}").andExpect(status().isConflict());
        start(task,"demo","{\"tool\":\"DEMO_ANALYZE\",\"arguments\":null}").andExpect(status().isConflict());
    }

    @Test
    void approvalDigestBindsArgumentsAndTamperingInvalidatesIt() throws Exception {
        UUID task=task();
        var body=mapper.readTree(start(task,"gated","{\"tool\":\"TEST_GATED\",\"arguments\":{\"mode\":\"pass\"}}")
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("WAITING_APPROVAL")).andReturn().getResponse().getContentAsString());
        UUID run=UUID.fromString(body.get("id").asText());String digest=body.get("inputDigest").asText();
        var r=runs.get(run);
        assertThat(digest).isNotEqualTo(catalog.digest(run,task,r.tool(),r.toolVersion(),r.target(),r.snapshotTitle(),r.snapshotDescription(),r.timeoutSeconds()));
        assertThat(jdbc.queryForObject("SELECT input_digest FROM approval_requests WHERE run_id=?",String.class,run)).isEqualTo(digest);
        mvc.perform(post("/api/v1/approvals/"+r.approvalId()+"/decision").header("Authorization",auth(ADMIN)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\",\"expectedDigest\":\""+digest+"\",\"reasonCode\":\"REVIEWED\"}")).andExpect(status().isOk());
        jdbc.update("UPDATE agent_runs SET arguments='{\"mode\":\"fail\"}' WHERE id=?",run);
        worker();
        assertThat(runs.get(run).status()).isEqualTo("FAILED");assertThat(runs.get(run).errorCode()).isEqualTo("APPROVAL_INVALIDATED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tool_invocations WHERE run_id=?",Long.class,run)).isZero();
    }

    @Test
    void codeOwnsToolPolicyAndConfigurationCannotWeakenIt() throws Exception {
        jdbc.update("UPDATE tool_definitions SET requires_approval=FALSE WHERE name='TEST_GATED'");
        start(task(),"weak","{\"tool\":\"TEST_GATED\",\"arguments\":{\"mode\":\"pass\"}}").andExpect(status().isConflict());
        jdbc.update("DELETE FROM tool_definitions WHERE name='TEST_PROBE'");
        start(task(),"missing","{\"tool\":\"TEST_PROBE\",\"arguments\":{\"mode\":\"pass\"}}").andExpect(status().isConflict());
        jdbc.update("INSERT INTO tool_definitions VALUES ('GHOST',1,'local-test',TRUE,FALSE,TRUE)");
        start(task(),"ghost","{\"tool\":\"GHOST\"}").andExpect(status().isBadRequest());
        assertThat(registry.names()).contains("DEMO_ANALYZE","DEMO_REVIEW","TEST_PROBE","TEST_GATED").doesNotContain("GHOST");
        assertThatThrownBy(() -> new ToolRegistry(java.util.List.of(new Probe("TEST_PROBE",false),new Probe("TEST_PROBE",true))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ToolRegistry(java.util.List.of(new Probe("rm -rf",false)))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void staleClaimCannotPublishResultsOrArtifacts() throws Exception {
        UUID run=started(task(),"pass");
        var claim=runs.claim();assertThat(runs.authorize(claim)).isTrue();
        jdbc.update("UPDATE agent_runs SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",run);
        new Probe("TEST_PROBE",false).execute(new ToolContext(claim.run(),claim.run().arguments(),claim.run().deadlineAt(),()->false));
        runs.finish(run,claim.token(),ToolResult.success("late",new ToolResult.Artifact("late.txt","text/plain",new byte[1])));
        assertThat(runs.get(run).status()).isEqualTo("RUNNING");
        assertThat(artifacts(run)).isEmpty();
        runs.recover();
        assertThat(runs.get(run).status()).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void heartbeatRenewsOnlyALiveClaimAndStopSignalFollowsCancellation() throws Exception {
        UUID run=started(task(),"pass");
        var claim=runs.claim();assertThat(runs.authorize(claim)).isTrue();
        jdbc.update("UPDATE agent_runs SET lease_until=clock_timestamp()+interval '2 seconds' WHERE id=?",run);
        assertThat(runs.renewLease(claim)).isTrue();
        assertThat(jdbc.queryForObject("SELECT lease_until>clock_timestamp()+interval '9 seconds' FROM agent_runs WHERE id=?",Boolean.class,run)).isTrue();
        assertThat(runs.stopRequested(claim)).isFalse();
        assertThat(runs.renewLease(new RunService.Claim(claim.run(),UUID.randomUUID()))).isFalse();
        runs.cancel(run);
        assertThat(runs.stopRequested(claim)).isTrue();
        jdbc.update("UPDATE agent_runs SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",run);
        assertThat(runs.renewLease(claim)).isFalse();
    }

    @Test
    void plansCannotUseToolsThatRequireArguments() {
        String plan="{\"schemaVersion\":1,\"summary\":\"s\",\"steps\":[{\"tool\":\"TEST_PROBE\",\"toolVersion\":1,\"target\":\"local-test\",\"timeoutSeconds\":30}]}";
        assertThatThrownBy(() -> validator.validate(plan)).isInstanceOf(ModelFailure.class);
        assertThat(registry.acceptsNoArguments("DEMO_ANALYZE")).isTrue();
        assertThat(registry.acceptsNoArguments("TEST_PROBE")).isFalse();
        assertThat(registry.acceptsNoArguments("GHOST")).isFalse();
    }

    @Test
    void argumentFreeDigestsAreUnchanged() {
        UUID run=UUID.randomUUID(),task=UUID.randomUUID();
        assertThat(catalog.digest(run,task,"DEMO_REVIEW",1,"local-demo","t",null,30))
                .isEqualTo(catalog.digest(run,task,"DEMO_REVIEW",1,"local-demo","t",null,30,null))
                .isNotEqualTo(catalog.digest(run,task,"DEMO_REVIEW",1,"local-demo","t",null,30,JsonNodeFactory.instance.objectNode().toString()));
    }
}
