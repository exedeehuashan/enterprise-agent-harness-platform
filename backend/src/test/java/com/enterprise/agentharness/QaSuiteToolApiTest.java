package com.enterprise.agentharness;

import com.enterprise.agentharness.qa.ProcessRunner;
import com.enterprise.agentharness.run.RunService;
import com.enterprise.agentharness.run.RunWorker;
import com.enterprise.agentharness.task.AgentTask;
import com.enterprise.agentharness.task.AgentTaskRepository;
import com.enterprise.agentharness.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
class QaSuiteToolApiTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final Path WORKDIR = tempDir();
    private static final String ADMIN=UUID.randomUUID().toString(),OPERATOR=UUID.randomUUID().toString(),VIEWER=UUID.randomUUID().toString();
    private static final String MAIN_SHA="a".repeat(40);
    private static final String PASS="<testsuite name=\"OrderTest\" tests=\"2\" failures=\"0\" errors=\"0\" skipped=\"0\"><testcase classname=\"OrderTest\" name=\"creates\"/><testcase classname=\"OrderTest\" name=\"lists\"/></testsuite>";
    private static final String FAIL="<testsuite name=\"StockTest\" tests=\"2\" failures=\"1\" errors=\"0\" skipped=\"0\"><testcase classname=\"StockTest\" name=\"ok\"/><testcase classname=\"StockTest\" name=\"preventsOversell\"><failure message=\"expected 0 but was -1\"/></testcase></testsuite>";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AgentTaskRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired RunService runs;
    @Autowired ToolRegistry registry;
    @Autowired com.enterprise.agentharness.qa.QaResultIngestor ingestor;
    @Autowired com.enterprise.agentharness.qa.QaSchedules schedules;

    /** What the fake "docker run" leaves in the workspace and how it exits. */
    static volatile String reportXml;
    static volatile int dockerExit;
    static volatile int cloneExit;
    static volatile Runnable duringRun;
    static volatile boolean dockerAvailable;
    /** Content of .eahp/qa-suites.json at any commit, or null when the file does not exist. */
    static volatile String repoFile;
    /** Console output written by the fake "docker run". */
    static volatile String consoleText;
    /** A JaCoCo report the fake build leaves under backend/order-api/target/site/jacoco, or null for none. */
    static volatile String coverageXml;
    /** Commit that "git ls-remote" reports for any branch. */
    static volatile String remoteSha;
    static final List<List<String>> calls = new CopyOnWriteArrayList<>();

    /** Simulates git, tar and docker by argv, so every outcome is deterministic and no container runs. */
    static class FakeRunner implements ProcessRunner {
        public Result run(List<String> argv, Path logFile, Duration timeout, BooleanSupplier stop, Runnable onStop) throws IOException {
            calls.add(List.copyOf(argv));
            if (argv.get(0).equals("git") && argv.contains("ls-remote")) {
                String ref=argv.get(argv.size()-3).replace("refs/heads/","");
                Files.writeString(logFile, ref.equals("missing") ? "" : remoteSha+"\trefs/heads/"+ref+"\n");
                return new Result(0,false,false);
            }
            if (argv.get(0).equals("git") && argv.contains("clone")) { Files.createDirectories(Path.of(argv.get(argv.size()-1))); return new Result(cloneExit,false,false); }
            if (argv.get(0).equals("git") && argv.contains("cat-file") && argv.get(argv.size()-1).contains(":")) {
                if (repoFile == null || argv.get(argv.size()-1).contains("missing-module")) return new Result(128,false,false);
                if (argv.contains("-p")) Files.writeString(logFile, repoFile);
                return new Result(0,false,false);
            }
            if (argv.get(0).equals("git") && argv.contains("archive")) { Files.writeString(Path.of(argv.get(argv.indexOf("-o")+1)), "tar"); return new Result(0,false,false); }
            if (argv.get(0).equals("tar")) { Files.writeString(Path.of(argv.get(argv.size()-1)).resolve("pom.xml"), "<project/>"); return new Result(0,false,false); }
            if (argv.get(0).equals("docker") && argv.get(1).equals("version")) {
                if (!dockerAvailable) Files.writeString(logFile, "Cannot connect to the Docker daemon\n");
                return new Result(dockerAvailable ? 0 : 1,false,false);
            }
            if (argv.get(0).equals("docker") && argv.get(1).equals("run")) {
                Files.writeString(logFile, consoleText);
                Path src=Path.of(argv.get(argv.indexOf("-w")+1));
                if (reportXml != null) {
                    Path reports=Files.createDirectories(src.resolve("backend/order-api/target/surefire-reports"));
                    Files.writeString(reports.resolve("TEST-com.example.Test.xml"), reportXml);
                }
                if (coverageXml != null) Files.writeString(Files.createDirectories(src.resolve("backend/order-api/target/site/jacoco")).resolve("jacoco.xml"), coverageXml);
                if (duringRun != null) duringRun.run();
                if (stop.getAsBoolean()) { onStop.run(); return new Result(-1,true,false); }
                return new Result(dockerExit,false,false);
            }
            return new Result(0,false,false);
        }
    }

    @TestConfiguration
    static class FakeProcesses {
        @Bean @Primary ProcessRunner fakeRunner() { return new FakeRunner(); }
    }

    private static EmbeddedPostgres startPostgres() {
        try { return EmbeddedPostgres.builder().setPort(0).start(); }
        catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
    }
    private static Path tempDir() {
        try { return Files.createTempDirectory("eahp-qa-test"); }
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
        registry.add("eahp.qa.workdir", WORKDIR::toString);
        registry.add("eahp.qa.git", () -> "git");
        registry.add("eahp.qa.docker", () -> "docker");
        registry.add("eahp.qa.docker-socket", () -> "/nonexistent/docker.sock");
    }

    @AfterAll
    static void stopPostgres() throws IOException { POSTGRES.close(); }

    @BeforeEach
    void reset() {
        repository.deleteAll();
        jdbc.update("UPDATE qa_test_suites SET enabled=TRUE");
        jdbc.update("UPDATE qa_projects SET enabled=TRUE");
        reportXml=PASS;dockerExit=0;cloneExit=0;duringRun=null;dockerAvailable=true;repoFile=null;consoleText="[INFO] BUILD output\n";remoteSha=MAIN_SHA;calls.clear();coverageXml=null;
        jdbc.update("DELETE FROM qa_schedules WHERE id<>'6b8c0f2e-7f3a-4c1e-9a54-3d2f0e5b7a10'");
        jdbc.update("UPDATE qa_schedules SET enabled=FALSE, last_commit=NULL, last_run_id=NULL, task_id=NULL, last_error=NULL");
        jdbc.update("DELETE FROM qa_test_results");jdbc.update("DELETE FROM qa_run_ingestions");jdbc.update("DELETE FROM qa_quarantines");
        jdbc.update("UPDATE qa_projects SET repository_suites_enabled=TRUE, allow_docker_access=TRUE, allow_network_egress=TRUE, max_timeout_seconds=2400, allowed_images='[\"maven:3.9.11-eclipse-temurin-17\"]'");
    }

    private static String auth(String token){return "Bearer "+token;}
    private UUID task(){return repository.saveAndFlush(new AgentTask("Test online-shop","Run the backend suite")).getId();}
    private void worker(){new RunWorker(runs,registry).runOnce();}
    private ResultActions start(UUID task,String key,String body) throws Exception {
        return mvc.perform(post("/api/v1/agent-tasks/"+task+"/runs").header("Authorization",auth(OPERATOR)).header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private UUID unitRun() throws Exception {
        var body=start(task(),"unit","{\"tool\":\"QA_RUN_SUITE\",\"timeoutSeconds\":900,\"arguments\":{\"project\":\"online-shop\",\"suite\":\"backend-unit\"}}")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(body).get("id").asText());
    }
    private JsonNode artifact(UUID run,String name) throws Exception {
        for (JsonNode a:mapper.readTree(mvc.perform(get("/api/v1/agent-runs/"+run+"/artifacts").header("Authorization",auth(VIEWER)))
                .andReturn().getResponse().getContentAsString())) if (a.get("name").asText().equals(name)) return a;
        return null;
    }
    private byte[] content(JsonNode artifact) throws Exception {
        return mvc.perform(get("/api/v1/artifacts/"+artifact.get("id").asText()+"/content").header("Authorization",auth(VIEWER)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }
    private JsonNode summary(UUID run) throws Exception {return mapper.readTree(content(artifact(run,"summary.json")));}
    private List<String> dockerRun() {return calls.stream().filter(c->c.get(0).equals("docker")&&c.get(1).equals("run")).findFirst().orElseThrow();}

    @Test
    void passingSuiteRecordsExitCodeReportsAndLogsAtTheResolvedCommit() throws Exception {
        UUID run=unitRun();
        var started=runs.get(run);
        assertThat(started.arguments().get("commit").asText()).isEqualTo(MAIN_SHA);
        assertThat(started.arguments().get("ref").asText()).isEqualTo("main");
        worker();
        var done=runs.get(run);
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.steps().get(0).output()).startsWith("online-shop/backend-unit @ aaaaaaaaaaaa: exit 0, 2 tests, 0 failures, 0 errors, 0 skipped");
        var summary=summary(run);
        assertThat(summary.get("outcome").asText()).isEqualTo("PASSED");
        assertThat(summary.get("exitCode").asInt()).isZero();
        assertThat(summary.get("commit").asText()).isEqualTo(MAIN_SHA);
        assertThat(summary.get("tests").asInt()).isEqualTo(2);
        assertThat(summary.get("startedAt").asText()).isNotBlank();
        try (var zip=new ZipInputStream(new ByteArrayInputStream(content(artifact(run,"junit-reports.zip"))))) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("backend/order-api/target/surefire-reports/TEST-com.example.Test.xml");
        }
        assertThat(new String(content(artifact(run,"console.log")),StandardCharsets.UTF_8)).contains("BUILD output");

        var argv=dockerRun();
        assertThat(argv).containsSequence("--cap-drop","ALL").containsSequence("--security-opt","no-new-privileges")
                .containsSequence("--network","bridge").contains("--user","maven:3.9.11-eclipse-temurin-17","-Dtest=!CatalogApiTest,!PostgresPersistenceTest,!OrderApiTest,!IdentityPersistenceTest");
        assertThat(String.join(" ",argv)).doesNotContain("docker.sock").doesNotContain("TESTCONTAINERS");
        assertThat(argv).containsSequence("-v",WORKDIR+":"+WORKDIR).contains("HOME="+WORKDIR.resolve("cache/online-shop"))
                .anyMatch(a->a.startsWith("MAVEN_OPTS=")&&a.contains("-Daether.connector.requestTimeout=120000"));
        assertThat(calls.stream().filter(c->c.contains("archive")).findFirst().orElseThrow()).contains(MAIN_SHA);
        try (var left=Files.list(WORKDIR.resolve("runs"))) { assertThat(left).isEmpty(); }
    }

    @Test
    void failingTestsAreFailedWithTheFailingTestsNamed() throws Exception {
        reportXml=FAIL;dockerExit=1;
        UUID run=unitRun();worker();
        var r=runs.get(run);
        assertThat(r.status()).isEqualTo("FAILED");assertThat(r.errorCode()).isEqualTo("TESTS_FAILED");
        assertThat(r.steps().get(0).output()).contains("exit 1, 2 tests, 1 failures");
        var summary=summary(run);
        assertThat(summary.get("exitCode").asInt()).isEqualTo(1);
        assertThat(summary.get("failedTests").get(0).get("name").asText()).isEqualTo("preventsOversell");
        assertThat(summary.get("failedTests").get(0).get("message").asText()).isEqualTo("expected 0 but was -1");
        assertThat(repository.findById(r.taskId()).orElseThrow().getStatus().name()).isEqualTo("FAILED");
    }

    @Test
    void passingReportsCannotHideANonZeroExitAndMissingReportsAreNotSuccess() throws Exception {
        dockerExit=1;UUID build=unitRun();worker();
        assertThat(runs.get(build).errorCode()).isEqualTo("BUILD_FAILED");
        repository.deleteAll();reportXml=null;dockerExit=0;UUID none=unitRun();worker();
        assertThat(runs.get(none).errorCode()).isEqualTo("NO_TESTS_FOUND");
        repository.deleteAll();dockerExit=125;UUID runner=unitRun();worker();
        assertThat(runs.get(runner).errorCode()).isEqualTo("RUNNER_ERROR");
        assertThat(artifact(runner,"junit-reports.zip")).isNull();
        assertThat(artifact(runner,"summary.json")).isNotNull();
    }

    @Test
    void reportsWithExternalEntitiesAreNotExpanded() throws Exception {
        Path secret=Files.writeString(WORKDIR.resolve("secret.txt"),"TOP-SECRET");
        reportXml="<?xml version=\"1.0\"?><!DOCTYPE t [<!ENTITY x SYSTEM \"file://"+secret+"\">]>"
                +"<testsuite tests=\"1\" failures=\"1\"><testcase classname=\"X\" name=\"y\"><failure message=\"&x;\"/></testcase></testsuite>";
        UUID run=unitRun();worker();
        var summary=summary(run);
        assertThat(summary.toString()).doesNotContain("TOP-SECRET");
        assertThat(summary.get("unreadableReportFiles").asInt()).isEqualTo(1);
        assertThat(runs.get(run).errorCode()).isEqualTo("NO_TESTS_FOUND");
    }

    @Test
    void cancellationDuringTheSuiteKillsTheContainer() throws Exception {
        UUID run=unitRun();
        duringRun=()->runs.cancel(run);
        worker();
        assertThat(runs.get(run).status()).isEqualTo("CANCELLED");
        assertThat(calls).anyMatch(c->c.get(0).equals("docker")&&c.get(1).equals("kill")&&c.get(2).startsWith("eahp-qa-"+run));
    }

    @Test
    void unreachableDockerDaemonIsReportedBeforeAnyWork() throws Exception {
        dockerAvailable=false;
        UUID run=unitRun();worker();
        assertThat(runs.get(run).errorCode()).isEqualTo("RUNNER_UNAVAILABLE");
        assertThat(new String(content(artifact(run,"docker-check.log")),StandardCharsets.UTF_8)).contains("Cannot connect");
        assertThat(calls).noneMatch(c->c.contains("clone")||c.contains("archive")||c.get(1).equals("run"));
    }

    @Test
    void sourceExportFailureIsReportedWithSetupLog() throws Exception {
        cloneExit=128;
        // Registry-only project: no repository file is read at start, so the clone first happens during execution.
        jdbc.update("UPDATE qa_projects SET repository_suites_enabled=FALSE");
        try (var mirrors=Files.list(WORKDIR)) { for (Path p:mirrors.toList()) if (p.endsWith("mirrors")) deleteTree(p); }
        UUID run=unitRun();worker();
        assertThat(runs.get(run).errorCode()).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(artifact(run,"setup.log")).isNotNull();
        assertThat(calls).noneMatch(c->c.get(0).equals("docker")&&c.get(1).equals("run"));
    }

    @Test
    void argumentsAreValidatedAgainstTheRegistry() throws Exception {
        UUID task=task();
        String[] bad={
            "{\"tool\":\"QA_RUN_SUITE\",\"arguments\":{\"project\":\"other\",\"suite\":\"backend-unit\"}}",
            "{\"tool\":\"QA_RUN_SUITE\",\"arguments\":{\"project\":\"online-shop\",\"suite\":\"nope\"}}",
            "{\"tool\":\"QA_RUN_SUITE\",\"arguments\":{\"project\":\"online-shop\",\"suite\":\"backend-unit\",\"command\":\"rm\"}}",
            "{\"tool\":\"QA_RUN_SUITE\",\"arguments\":{\"project\":\"online-shop\",\"suite\":\"backend-integration\"}}",
            "{\"tool\":\"QA_RUN_PRIVILEGED_SUITE\",\"arguments\":{\"project\":\"online-shop\",\"suite\":\"backend-unit\"}}",
            "{\"tool\":\"QA_RUN_SUITE\",\"arguments\":{\"project\":\"online-shop\",\"suite\":\"backend-unit\",\"ref\":\"../etc\"}}",
            "{\"tool\":\"QA_RUN_SUITE\",\"arguments\":{\"project\":\"online-shop\",\"suite\":\"backend-unit\",\"ref\":\"--upload-pack=x\"}}",
            "{\"tool\":\"QA_RUN_SUITE\",\"arguments\":{\"project\":\"online-shop\",\"suite\":\"backend-unit\",\"ref\":\"missing\"}}",
            "{\"tool\":\"QA_RUN_SUITE\",\"timeoutSeconds\":1801,\"arguments\":{\"project\":\"online-shop\",\"suite\":\"backend-unit\"}}",
            "{\"tool\":\"DEMO_ANALYZE\",\"timeoutSeconds\":301}",
            "{\"tool\":\"QA_RUN_SUITE\",\"timeoutSeconds\":3601,\"arguments\":{\"project\":\"online-shop\",\"suite\":\"backend-unit\"}}"};
        for (String body:bad) start(task,"bad",body).andExpect(status().isBadRequest());
        jdbc.update("UPDATE qa_test_suites SET enabled=FALSE WHERE key='backend-unit'");
        start(task,"bad","{\"tool\":\"QA_RUN_SUITE\",\"arguments\":{\"project\":\"online-shop\",\"suite\":\"backend-unit\"}}").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runs",Long.class)).isZero();
        jdbc.update("UPDATE qa_test_suites SET enabled=TRUE");
        calls.clear();
        start(task,"pinned","{\"tool\":\"QA_RUN_SUITE\",\"timeoutSeconds\":1800,\"arguments\":{\"project\":\"online-shop\",\"suite\":\"backend-unit\",\"ref\":\""+"b".repeat(40)+"\"}}")
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.arguments.commit").value("b".repeat(40)));
        assertThat(calls).noneMatch(c->c.contains("ls-remote"));
    }

    @Test
    void suiteDisabledAfterStartIsNotRun() throws Exception {
        UUID run=unitRun();
        jdbc.update("UPDATE qa_test_suites SET enabled=FALSE WHERE key='backend-unit'");
        worker();
        assertThat(runs.get(run).errorCode()).isEqualTo("SUITE_UNAVAILABLE");
        assertThat(calls).noneMatch(c->c.get(0).equals("docker"));
    }

    @Test
    void dockerAccessSuitesNeedApprovalBoundToTheCommit() throws Exception {
        var body=mapper.readTree(start(task(),"int","{\"tool\":\"QA_RUN_PRIVILEGED_SUITE\",\"timeoutSeconds\":2400,\"arguments\":{\"project\":\"online-shop\",\"suite\":\"backend-integration\"}}")
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("WAITING_APPROVAL")).andReturn().getResponse().getContentAsString());
        UUID run=UUID.fromString(body.get("id").asText());
        worker();
        assertThat(calls).noneMatch(c->c.get(0).equals("docker"));
        var r=runs.get(run);
        mvc.perform(post("/api/v1/approvals/"+r.approvalId()+"/decision").header("Authorization",auth(ADMIN)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\",\"expectedDigest\":\""+r.inputDigest()+"\",\"reasonCode\":\"REVIEWED\"}")).andExpect(status().isOk());
        worker();
        assertThat(runs.get(run).status()).isEqualTo("COMPLETED");
        assertThat(dockerRun()).contains("/nonexistent/docker.sock:/var/run/docker.sock","TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal")
                .contains("-pl","backend/order-adapters-persistence,backend/order-api,backend/catalog-api");
    }

    @Test
    void projectCatalogIsReadableByViewers() throws Exception {
        mvc.perform(get("/api/v1/qa/projects").header("Authorization",auth(VIEWER))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("online-shop"))
                .andExpect(jsonPath("$[0].repositoryUrl").value("https://github.com/exedeehuashan/online-shop.git"))
                .andExpect(jsonPath("$[0].suites.length()").value(2))
                .andExpect(jsonPath("$[0].suites[0].dockerAccess").value(true))
                .andExpect(jsonPath("$[0].suites[1].key").value("backend-unit"));
        mvc.perform(get("/api/v1/qa/projects")).andExpect(status().isUnauthorized());
    }

    private static final String REPO_FILE="""
            {"schemaVersion":1,"suites":[
              {"key":"backend-unit","name":"Unit (declared in repo)","image":"maven:3.9.11-eclipse-temurin-17",
               "command":["mvn","-B","verify","-DexcludedGroups=docker"],"reportDirs":["target/surefire-reports"],
               "network":"egress","dockerAccess":false,"timeoutSeconds":1200},
              {"key":"smoke","name":"Smoke","description":"Fast checks","image":"maven:3.9.11-eclipse-temurin-17",
               "command":["mvn","-B","-pl","backend/order-domain","test"],"reportDirs":["target/surefire-reports"],
               "network":"none","dockerAccess":false,"timeoutSeconds":600}]}
            """;

    @Test
    void repositoryDeclaredSuiteOverridesTheRegistryAndIsBoundIntoTheRun() throws Exception {
        repoFile=REPO_FILE;
        UUID run=UUID.fromString(mapper.readTree(start(task(),"repo","{\"tool\":\"QA_RUN_SUITE\",\"timeoutSeconds\":1200,\"arguments\":{\"project\":\"online-shop\",\"suite\":\"backend-unit\"}}")
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.arguments.definition.source").value("repository"))
                .andExpect(jsonPath("$.arguments.definition.command[3]").value("-DexcludedGroups=docker"))
                .andReturn().getResponse().getContentAsString()).get("id").asText());
        start(task(),"long","{\"tool\":\"QA_RUN_SUITE\",\"timeoutSeconds\":1500,\"arguments\":{\"project\":\"online-shop\",\"suite\":\"backend-unit\"}}")
                .andExpect(status().isBadRequest());
        repoFile=null; // the run executes what was resolved at start, not the file as it is now
        worker();
        assertThat(runs.get(run).status()).isEqualTo("COMPLETED");
        assertThat(dockerRun()).contains("-DexcludedGroups=docker").doesNotContain("-Dtest=!CatalogApiTest,!PostgresPersistenceTest,!OrderApiTest,!IdentityPersistenceTest")
                .containsSequence("--network","bridge");
        assertThat(summary(run).get("suiteSource").asText()).isEqualTo("repository");
    }

    @Test
    void selectiveSuitesRunOnlyTheRequestedModulesAndTheGateSaysSo() throws Exception {
        repoFile=REPO_FILE.replace("\"timeoutSeconds\":1200}","\"timeoutSeconds\":1200,\"selection\":\"maven-modules\"}");
        String c="c".repeat(40);
        UUID run=UUID.fromString(mapper.readTree(start(task(),"sel","{\"tool\":\"QA_RUN_SUITE\",\"timeoutSeconds\":900,\"arguments\":{\"project\":\"online-shop\",\"suite\":\"backend-unit\",\"ref\":\""+c+"\","
                +"\"modules\":[\"backend/order-api\",\"backend/catalog-api\",\"backend/order-api\"]}}")
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.arguments.modules.length()").value(2))
                .andExpect(jsonPath("$.arguments.modules[0]").value("backend/catalog-api"))
                .andReturn().getResponse().getContentAsString()).get("id").asText());
        worker();
        assertThat(runs.get(run).status()).isEqualTo("COMPLETED");
        assertThat(dockerRun()).endsWith("-DexcludedGroups=docker","-pl","backend/catalog-api,backend/order-api","-am");
        assertThat(summary(run).get("scope").asText()).isEqualTo("MODULES");
        assertThat(summary(run).get("modules").get(1).asText()).isEqualTo("backend/order-api");
        ingestor.runOnce();
        var gate=gate(c);
        assertThat(gate.get("verdict").asText()).isEqualTo("PASS");
        assertThat(gate.get("suites").get(0).get("modules").get(0).asText()).isEqualTo("backend/catalog-api");
        assertThat(gate.get("notes").get(0).asText()).isEqualTo("backend-unit ran only the affected modules at this commit: backend/catalog-api, backend/order-api.");

        for (String modules : List.of("[]","\"backend/order-api\"","[\"../etc\"]","[\"/abs\"]","[\"backend/missing-module\"]","[1]")) {
            start(task(),"bad"+modules.hashCode(),"{\"tool\":\"QA_RUN_SUITE\",\"timeoutSeconds\":900,\"arguments\":{\"project\":\"online-shop\",\"suite\":\"backend-unit\",\"modules\":"+modules+"}}")
                    .andExpect(status().isBadRequest());
        }
        start(task(),"smoke-sel","{\"tool\":\"QA_RUN_SUITE\",\"timeoutSeconds\":600,\"arguments\":{\"project\":\"online-shop\",\"suite\":\"smoke\",\"modules\":[\"backend/order-api\"]}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("does not support module selection")));
        repoFile=REPO_FILE.replace("\"timeoutSeconds\":600}","\"timeoutSeconds\":600,\"selection\":\"all\"}");
        mvc.perform(get("/api/v1/qa/projects/online-shop/suites").header("Authorization",auth(VIEWER)))
                .andExpect(jsonPath("$.repositoryFile").value("invalid")).andExpect(jsonPath("$.repositoryError").value("invalid suites file: smoke: selection must be maven-modules or paths"));
        for (String bad : List.of("\"selection\":\"paths\"}", "\"paths\":[\"frontend/\"]}", "\"selection\":\"paths\",\"paths\":[\"../x\"]}", "\"selection\":\"paths\",\"paths\":[\"/abs\"]}")) {
            repoFile=REPO_FILE.replace("\"timeoutSeconds\":600}","\"timeoutSeconds\":600,"+bad);
            mvc.perform(get("/api/v1/qa/projects/online-shop/suites").header("Authorization",auth(VIEWER))).andExpect(jsonPath("$.repositoryFile").value("invalid"));
        }
        repoFile=REPO_FILE.replace("\"timeoutSeconds\":600}","\"timeoutSeconds\":600,\"selection\":\"paths\",\"paths\":[\"frontend/order-web/\",\"docs\"]}");
        mvc.perform(get("/api/v1/qa/projects/online-shop/suites").header("Authorization",auth(VIEWER))).andExpect(jsonPath("$.repositoryFile").value("found"))
                .andExpect(jsonPath("$.suites[?(@.key=='smoke')].paths[1]").value("docs"));
    }

    @Test
    void exitCodeSuitesRecordTheirExitCodeAsOneTestCase() throws Exception {
        repoFile=REPO_FILE.replace("\"command\":[\"mvn\",\"-B\",\"-pl\",\"backend/order-domain\",\"test\"],\"reportDirs\":[\"target/surefire-reports\"]",
                "\"command\":[\"node\",\"tests/contracts/verify.mjs\"],\"reportDirs\":[],\"results\":\"exit-code\"");
        reportXml=FAIL; // reports a JUnit file in the workspace would leave are ignored for exit-code suites
        String body="{\"tool\":\"QA_RUN_SUITE\",\"timeoutSeconds\":600,\"arguments\":{\"project\":\"online-shop\",\"suite\":\"smoke\"}}";
        UUID passed=UUID.fromString(mapper.readTree(start(task(),"ec1",body).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.arguments.definition.results").value("exit-code")).andReturn().getResponse().getContentAsString()).get("id").asText());
        worker();
        assertThat(runs.get(passed).status()).isEqualTo("COMPLETED");
        assertThat(summary(passed).get("tests").asInt()).isEqualTo(1);
        dockerExit=3;consoleText="x".repeat(300_000)+"\u00ff\ncontract mismatch: OrderResponse.total\n"; // large, with a non-ASCII byte
        UUID failed=UUID.fromString(mapper.readTree(start(task(),"ec2",body).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).get("id").asText());
        worker();
        assertThat(runs.get(failed).errorCode()).isEqualTo("TESTS_FAILED");
        assertThat(summary(failed).get("failedTests").get(0).get("message").asText()).isEqualTo("exit code 3");
        ingestor.runOnce();
        assertThat(jdbc.queryForObject("SELECT test_class||'#'||status FROM qa_test_results WHERE run_id=?",String.class,failed)).isEqualTo("smoke#FAILED");
        dockerExit=127;
        UUID broken=UUID.fromString(mapper.readTree(start(task(),"ec3",body).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).get("id").asText());
        worker();
        assertThat(runs.get(broken).errorCode()).isEqualTo("RUNNER_ERROR");

        repoFile=REPO_FILE.replace("\"test\"],\"reportDirs\":[\"target/surefire-reports\"]","\"test\"],\"reportDirs\":[\"target/surefire-reports\"],\"results\":\"exit-code\"");
        mvc.perform(get("/api/v1/qa/projects/online-shop/suites").header("Authorization",auth(VIEWER))).andExpect(jsonPath("$.repositoryError").value("invalid suites file: smoke: exit-code suites have no reportDirs"));
        repoFile=REPO_FILE.replace("\"test\"],\"reportDirs\":[\"target/surefire-reports\"]","\"test\"],\"reportDirs\":[]");
        mvc.perform(get("/api/v1/qa/projects/online-shop/suites").header("Authorization",auth(VIEWER))).andExpect(jsonPath("$.repositoryError").value("invalid suites file: smoke: reportDirs must list at least one directory"));
    }

    @Test
    void repositoryOnlySuitesRunAndTheCatalogShowsBothSources() throws Exception {
        repoFile=REPO_FILE;
        mvc.perform(get("/api/v1/qa/projects/online-shop/suites").header("Authorization",auth(VIEWER))).andExpect(status().isOk())
                .andExpect(jsonPath("$.commit").value(MAIN_SHA)).andExpect(jsonPath("$.repositoryFile").value("found"))
                .andExpect(jsonPath("$.suites[?(@.key=='smoke')].source").value("repository"))
                .andExpect(jsonPath("$.suites[?(@.key=='backend-integration')].source").value("registry"))
                .andExpect(jsonPath("$.suites[?(@.key=='backend-unit')].source").value("repository"));
        UUID run=UUID.fromString(mapper.readTree(start(task(),"smoke","{\"tool\":\"QA_RUN_SUITE\",\"timeoutSeconds\":600,\"arguments\":{\"project\":\"online-shop\",\"suite\":\"smoke\"}}")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).get("id").asText());
        worker();
        assertThat(runs.get(run).status()).isEqualTo("COMPLETED");
        assertThat(dockerRun()).containsSequence("--network","none").contains("backend/order-domain");
        mvc.perform(get("/api/v1/qa/projects/online-shop/suites").param("ref","../x").header("Authorization",auth(VIEWER))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/qa/projects/online-shop/suites")).andExpect(status().isUnauthorized());
    }

    @Test
    void projectPolicyLimitsWhatARepositoryFileCanDeclare() throws Exception {
        UUID task=task();
        String smoke="{\"tool\":\"QA_RUN_SUITE\",\"timeoutSeconds\":600,\"arguments\":{\"project\":\"online-shop\",\"suite\":\"smoke\"}}";
        repoFile=REPO_FILE.replace("\"network\":\"none\",\"dockerAccess\":false,\"timeoutSeconds\":600","\"network\":\"none\",\"dockerAccess\":false,\"timeoutSeconds\":3000");
        start(task,"p1",smoke).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid arguments: suite timeout exceeds the project's maximum of 2400"));
        repoFile=REPO_FILE.replace("\"command\":[\"mvn\",\"-B\",\"-pl\"","\"image\":\"x\",\"command\":[\"mvn\",\"-B\",\"-pl\"");
        start(task,"p2",smoke).andExpect(status().isBadRequest());
        repoFile=REPO_FILE.replace("\"Fast checks\",\"image\":\"maven:3.9.11-eclipse-temurin-17\"","\"Fast checks\",\"image\":\"attacker/miner:latest\"");
        start(task,"p3",smoke).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid arguments: image attacker/miner:latest is not allowed for this project"));
        repoFile=REPO_FILE.replace("\"network\":\"none\",\"dockerAccess\":false","\"network\":\"none\",\"dockerAccess\":true");
        jdbc.update("UPDATE qa_projects SET allow_docker_access=FALSE");
        start(task,"p4","{\"tool\":\"QA_RUN_PRIVILEGED_SUITE\",\"timeoutSeconds\":600,\"arguments\":{\"project\":\"online-shop\",\"suite\":\"smoke\"}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid arguments: this project does not allow Docker access"));
        repoFile=REPO_FILE;jdbc.update("UPDATE qa_projects SET allow_network_egress=FALSE");
        start(task,"p5","{\"tool\":\"QA_RUN_SUITE\",\"timeoutSeconds\":600,\"arguments\":{\"project\":\"online-shop\",\"suite\":\"backend-unit\"}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid arguments: this project does not allow network egress"));
        jdbc.update("UPDATE qa_test_suites SET enabled=FALSE WHERE key='backend-unit'");jdbc.update("UPDATE qa_projects SET allow_network_egress=TRUE");
        start(task,"p6","{\"tool\":\"QA_RUN_SUITE\",\"timeoutSeconds\":600,\"arguments\":{\"project\":\"online-shop\",\"suite\":\"backend-unit\"}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid arguments: unknown or disabled suite"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runs",Long.class)).isZero();
    }

    @Test
    void invalidRepositoryFilesAreRejectedAndReportedWithoutHidingRegistrySuites() throws Exception {
        String[] invalid={"not json","{\"schemaVersion\":2,\"suites\":[]}","{\"schemaVersion\":1,\"suites\":[],\"x\":1}",
                REPO_FILE.replace("\"reportDirs\":[\"target/surefire-reports\"]","\"reportDirs\":[\"/etc\"]"),
                REPO_FILE.replace("\"key\":\"smoke\"","\"key\":\"backend-unit\""),
                REPO_FILE.replace("\"name\":\"Smoke\"","\"name\":\"Smoke\",\"name\":\"Again\""),
                REPO_FILE.replace("\"name\":\"Smoke\"","\"name\":\"Smoke\",\"shell\":\"rm -rf /\""),
                REPO_FILE.replace("\"Fast checks\"","\"Fast\\u0000checks\"")};
        UUID task=task();int i=0;
        for (String file:invalid) {
            repoFile=file;
            start(task,"bad-"+(i++),"{\"tool\":\"QA_RUN_SUITE\",\"timeoutSeconds\":600,\"arguments\":{\"project\":\"online-shop\",\"suite\":\"backend-unit\"}}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Invalid arguments: invalid suites file:")));
        }
        repoFile="not json";
        mvc.perform(get("/api/v1/qa/projects/online-shop/suites").header("Authorization",auth(VIEWER))).andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryFile").value("invalid")).andExpect(jsonPath("$.repositoryError").value("invalid suites file: not valid JSON"))
                .andExpect(jsonPath("$.suites.length()").value(2));
        repoFile=null;
        mvc.perform(get("/api/v1/qa/projects/online-shop/suites").header("Authorization",auth(VIEWER))).andExpect(jsonPath("$.repositoryFile").value("absent"));
    }

    @Test
    void executionRechecksProjectPolicy() throws Exception {
        repoFile=REPO_FILE;
        UUID run=UUID.fromString(mapper.readTree(start(task(),"later","{\"tool\":\"QA_RUN_SUITE\",\"timeoutSeconds\":600,\"arguments\":{\"project\":\"online-shop\",\"suite\":\"smoke\"}}")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).get("id").asText());
        jdbc.update("UPDATE qa_projects SET allowed_images='[\"maven:3.9.12-eclipse-temurin-21\"]'");
        worker();
        assertThat(runs.get(run).errorCode()).isEqualTo("SUITE_POLICY_CHANGED");
        assertThat(calls).noneMatch(c->c.get(0).equals("docker")&&c.get(1).equals("run"));
    }

    private static final String ORDER_FAIL="<testsuite name=\"OrderTest\" tests=\"2\" failures=\"1\"><testcase classname=\"OrderTest\" name=\"creates\" time=\"0.25\">"
            +"<failure message=\"expected: &lt;1&gt; but was: &lt;2&gt;\" type=\"org.opentest4j.AssertionFailedError\">at OrderTest.creates</failure></testcase>"
            +"<testcase classname=\"OrderTest\" name=\"lists\" time=\"0.01\"/></testsuite>";
    private static final String DOCKER_ERROR="<testsuite name=\"IdentityPersistenceTest\" tests=\"1\" errors=\"1\"><testcase classname=\"IdentityPersistenceTest\" name=\"\">"
            +"<error message=\"Could not find a valid Docker environment. Please see logs\" type=\"java.lang.IllegalStateException\"/></testcase></testsuite>";

    private UUID runAt(UUID task,String key,String commit,String report,int exit) throws Exception {
        reportXml=report;dockerExit=exit;
        var body=start(task,key,"{\"tool\":\"QA_RUN_SUITE\",\"timeoutSeconds\":900,\"arguments\":{\"project\":\"online-shop\",\"suite\":\"backend-unit\",\"ref\":\""+commit+"\"}}")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID run=UUID.fromString(mapper.readTree(body).get("id").asText());
        worker();
        return run;
    }
    private JsonNode triage(UUID run) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/v1/qa/runs/"+run+"/triage").header("Authorization",auth(VIEWER)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void triageClassifiesFailuresAndTracksEachTestsHistory() throws Exception {
        String a="a".repeat(40),b="b".repeat(40),c="c".repeat(40);
        UUID task=task();
        UUID r1=runAt(task,"t1",a,PASS,0);
        assertThat(triage(r1).get("ingested").asBoolean()).isFalse();
        assertThat(ingestor.runOnce()).isEqualTo(1);
        assertThat(ingestor.runOnce()).isZero();
        var passed=triage(r1);
        assertThat(passed.get("tests").asInt()).isEqualTo(2);assertThat(passed.get("failed").asInt()).isZero();assertThat(passed.get("category").isNull()).isTrue();

        UUID r2=runAt(task,"t2",b,ORDER_FAIL,1);ingestor.runOnce();
        var t2=triage(r2);
        assertThat(t2.get("category").asText()).isEqualTo("ASSERTION");
        assertThat(t2.get("explanation").asText()).contains("assertion failed");
        var f=t2.get("failures").get(0);
        assertThat(f.get("testClass").asText()).isEqualTo("OrderTest");assertThat(f.get("testName").asText()).isEqualTo("creates");
        assertThat(f.get("failureType").asText()).isEqualTo("org.opentest4j.AssertionFailedError");
        assertThat(f.get("history").get("label").asText()).isEqualTo("NEW_FAILURE");
        assertThat(f.get("history").get("lastPassedCommit").asText()).isEqualTo(a);

        runAt(task,"t3",b,PASS,0);
        UUID r4=runAt(task,"t4",b,ORDER_FAIL,1);ingestor.runOnce();
        assertThat(triage(r4).get("failures").get(0).get("history").get("label").asText()).isEqualTo("FLAKY");

        UUID r5=runAt(task,"t5",c,ORDER_FAIL,1);ingestor.runOnce();
        var persistent=triage(r5).get("failures").get(0).get("history");
        assertThat(persistent.get("label").asText()).isEqualTo("PERSISTENT");
        assertThat(persistent.get("previousCommit").asText()).isEqualTo(b);
        assertThat(persistent.get("recentFailures").asInt()).isEqualTo(3);

        UUID r6=runAt(task,"t6",c,DOCKER_ERROR,1);ingestor.runOnce();
        var docker=triage(r6);
        assertThat(docker.get("category").asText()).isEqualTo("ENVIRONMENT_DOCKER");
        assertThat(docker.get("explanation").asText()).contains("needs Docker");
        assertThat(docker.get("failures").get(0).get("history").get("label").asText()).isEqualTo("FIRST_SEEN");

        mvc.perform(get("/api/v1/qa/tests/history").header("Authorization",auth(VIEWER)).param("project","online-shop").param("suite","backend-unit")
                .param("testClass","OrderTest").param("testName","creates")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5)).andExpect(jsonPath("$[0].status").value("FAILED")).andExpect(jsonPath("$[0].durationMs").value(250));
        mvc.perform(get("/api/v1/qa/tests/history").header("Authorization",auth(VIEWER)).param("project","online-shop").param("suite","backend-unit")
                .param("testClass","OrderTest").param("limit","0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/qa/runs/"+UUID.randomUUID()+"/triage").header("Authorization",auth(VIEWER))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/qa/runs/"+r6+"/triage")).andExpect(status().isUnauthorized());
    }

    @Test
    void runLevelFailuresAreCategorizedAndUnfinishedRunsWait() throws Exception {
        UUID task=task();
        consoleText="[ERROR] COMPILATION ERROR :\n[ERROR] cannot find symbol\n";
        UUID build=runAt(task,"b1","d".repeat(40),null,1);
        consoleText="[INFO] BUILD output\n";
        var waiting=start(task(),"w1","{\"tool\":\"QA_RUN_PRIVILEGED_SUITE\",\"timeoutSeconds\":900,\"arguments\":{\"project\":\"online-shop\",\"suite\":\"backend-integration\"}}")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertThat(ingestor.runOnce()).isEqualTo(1);
        var t=triage(build);
        assertThat(t.get("outcome").asText()).isEqualTo("BUILD_FAILED");
        assertThat(t.get("category").asText()).isEqualTo("BUILD_COMPILATION");
        assertThat(t.get("tests").asInt()).isZero();
        assertThat(triage(UUID.fromString(mapper.readTree(waiting).get("id").asText())).get("ingested").asBoolean()).isFalse();
    }

    private JsonNode gate(String ref) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/v1/qa/projects/online-shop/gate").param("ref",ref).header("Authorization",auth(VIEWER)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions quarantine(String token,String body) throws Exception {
        return mvc.perform(post("/api/v1/qa/admin/quarantines").header("Authorization",auth(token)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void quarantinedFailuresDoNotBlockTheGateWhileTheRestOfTheSuiteRan() throws Exception {
        String a="a".repeat(40),b="b".repeat(40),c="c".repeat(40);
        UUID task=task();
        runAt(task,"q1",a,PASS,0);ingestor.runOnce();
        runAt(task,"q2",b,ORDER_FAIL,1);ingestor.runOnce();
        assertThat(gate(b).get("verdict").asText()).isEqualTo("FAIL");

        String body="{\"project\":\"online-shop\",\"suite\":\"backend-unit\",\"testClass\":\"OrderTest\",\"testName\":\"creates\",\"reason\":\"Flaky clock in CI, tracked in issue 12\",\"days\":7}";
        quarantine(OPERATOR,body).andExpect(status().isForbidden());
        quarantine(ADMIN,body.replace("\"days\":7","\"days\":31")).andExpect(status().isBadRequest());
        quarantine(ADMIN,body.replace("Flaky clock in CI, tracked in issue 12","flaky")).andExpect(status().isBadRequest());
        quarantine(ADMIN,body.replace("\"days\":7","\"days\":7,\"extra\":1")).andExpect(status().isBadRequest());
        quarantine(ADMIN,body.replace("online-shop","nope")).andExpect(status().isNotFound());
        String id=mapper.readTree(quarantine(ADMIN,body).andExpect(status().isCreated()).andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdBy").value("admin")).andReturn().getResponse().getContentAsString()).get("id").asText();
        quarantine(ADMIN,body).andExpect(status().isConflict());

        var waived=gate(b);
        assertThat(waived.get("verdict").asText()).isEqualTo("PASS");
        assertThat(waived.get("suites").get(0).get("status").asText()).isEqualTo("PASSED");
        assertThat(waived.get("suites").get(0).get("outcome").asText()).isEqualTo("TESTS_FAILED");
        assertThat(waived.get("notes").toString()).contains("backend-unit failed only in quarantined tests, which do not block the gate: OrderTest#creates.");

        // Fewer tests than the last passing run: something after the failure may not have run, so the gate still fails.
        runAt(task,"q3",c,"<testsuite name=\"OrderTest\" tests=\"1\" failures=\"1\"><testcase classname=\"OrderTest\" name=\"creates\"><failure message=\"x\"/></testcase></testsuite>",1);
        ingestor.runOnce();
        var partial=gate(c);
        assertThat(partial.get("verdict").asText()).isEqualTo("FAIL");
        assertThat(partial.get("reasons").get(0).asText()).contains("executed 1 tests, fewer than the 2 of its last passing run");

        mvc.perform(get("/api/v1/qa/quarantines").param("project","online-shop").header("Authorization",auth(VIEWER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].testName").value("creates"));
        mvc.perform(post("/api/v1/qa/admin/quarantines/"+id+"/release").header("Authorization",auth(OPERATOR))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/qa/admin/quarantines/"+id+"/release").header("Authorization",auth(ADMIN))).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.releasedBy").value("admin"));
        assertThat(gate(b).get("verdict").asText()).isEqualTo("FAIL");
        mvc.perform(get("/api/v1/qa/quarantines").header("Authorization",auth(VIEWER))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/qa/quarantines").param("all","true").header("Authorization",auth(VIEWER))).andExpect(jsonPath("$.length()").value(1));
        assertThat(jdbc.queryForList("SELECT action FROM qa_admin_events WHERE action LIKE 'QUARANTINE%' ORDER BY id",String.class))
                .containsExactly("QUARANTINE_CREATED","QUARANTINE_RELEASED");

        // An expired quarantine stops applying and can be replaced.
        String again=mapper.readTree(quarantine(ADMIN,body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        jdbc.update("UPDATE qa_quarantines SET created_at=clock_timestamp()-interval '9 days', expires_at=clock_timestamp()-interval '1 day' WHERE id=?::uuid",again);
        assertThat(gate(b).get("verdict").asText()).isEqualTo("FAIL");
        quarantine(ADMIN,body).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT released_by FROM qa_quarantines WHERE id=?::uuid",String.class,again)).isEqualTo("expiry");
        mvc.perform(get("/api/v1/qa/quarantines")).andExpect(status().isUnauthorized());
    }

    @Test
    void commitReportSummarizesGateSuitesFailuresAndThePullRequest() throws Exception {
        String a="a".repeat(40),b="b".repeat(40);
        UUID task=task();
        runAt(task,"r1",a,PASS,0);
        UUID failed=runAt(task,"r2",b,ORDER_FAIL,1);ingestor.runOnce();
        jdbc.update("""
                INSERT INTO qa_pr_runs(id,project_key,pr_number,head_sha,suite_key,pr_title,pr_url,head_ref,status,run_id,selection,selection_reason,selected_modules,base_sha)
                VALUES (gen_random_uuid(),'online-shop',61,?,'backend-unit','Fix @admin [x](http://evil) <b>','https://github.com/exedeehuashan/online-shop/pull/61','f','STARTED',?,'MODULES','AFFECTED_MODULES','["backend/order-api"]'::jsonb,?)
                """,b,failed,a);
        try {
            var report=mapper.readTree(mvc.perform(get("/api/v1/qa/projects/online-shop/report").param("ref",b).header("Authorization",auth(VIEWER)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(report.get("verdict").asText()).isEqualTo("FAIL");
            assertThat(report.get("commit").asText()).isEqualTo(b);
            String md=report.get("markdown").asText();
            assertThat(md).startsWith("## EAHP QA report: online-shop @ `bbbbbbbbbbbb`\n\n**Release gate: FAIL** (required: backend-unit)")
                    .contains("| backend-unit | TESTS\\_FAILED | 2 | 1 | — | full | `"+failed.toString().substring(0,8)+"` |")
                    .contains("- backend-unit failed \\(TESTS\\_FAILED, ASSERTION\\).")
                    .contains("- backend-unit `OrderTest#creates` (failed, assertion, new failure): expected: &lt;1&gt; but was: &lt;2&gt;")
                    .contains("### Pull request #61: Fix @\u200Badmin \\[x\\]\\(http://evil\\) &lt;b&gt;")
                    .contains("- backend-unit: affected modules only: `backend/order-api`")
                    .contains("Compared with base `aaaaaaaaaaaa`:\n- backend-unit: 1 new failure(s), 0 fixed, 0 still failing, 0 added, 0 removed\n  - new failure: `OrderTest#creates`")
                    .doesNotContain("<b>");
            mvc.perform(get("/api/v1/qa/projects/online-shop/report").param("ref","c".repeat(40)).header("Authorization",auth(VIEWER)))
                    .andExpect(jsonPath("$.verdict").value("PENDING")).andExpect(jsonPath("$.markdown").value(org.hamcrest.Matchers.containsString("No suite has a recorded result at this commit yet.")));
            mvc.perform(get("/api/v1/qa/projects/online-shop/report").param("ref","../x").header("Authorization",auth(VIEWER))).andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/qa/projects/nope/report").param("ref",b).header("Authorization",auth(VIEWER))).andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/qa/projects/online-shop/report").param("ref",b)).andExpect(status().isUnauthorized());
        } finally {
            jdbc.update("DELETE FROM qa_pr_runs");
        }
    }

    @Test
    void comparisonShowsNewFailuresFixesAndAddedOrRemovedTests() throws Exception {
        String a="a".repeat(40),b="b".repeat(40),c="c".repeat(40);
        UUID task=task();
        runAt(task,"c1",a,PASS,0);
        runAt(task,"c2",b,ORDER_FAIL,1);
        runAt(task,"c3",c,"<testsuite name=\"OrderTest\" tests=\"2\"><testcase classname=\"OrderTest\" name=\"creates\"/><testcase classname=\"OrderTest\" name=\"cancels\"/></testsuite>",0);
        ingestor.runOnce();
        mvc.perform(get("/api/v1/qa/projects/online-shop/compare").param("base",a).param("head",b).header("Authorization",auth(VIEWER))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].suite").value("backend-unit")).andExpect(jsonPath("$[0].status").value("COMPARED"))
                .andExpect(jsonPath("$[0].newFailures[0]").value("OrderTest#creates")).andExpect(jsonPath("$[0].fixedCount").value(0))
                .andExpect(jsonPath("$[0].base.outcome").value("PASSED")).andExpect(jsonPath("$[0].head.outcome").value("TESTS_FAILED"));
        mvc.perform(get("/api/v1/qa/projects/online-shop/compare").param("base",b).param("head",c).param("suite","backend-unit").header("Authorization",auth(VIEWER)))
                .andExpect(jsonPath("$[0].fixed[0]").value("OrderTest#creates")).andExpect(jsonPath("$[0].added[0]").value("OrderTest#cancels"))
                .andExpect(jsonPath("$[0].removed[0]").value("OrderTest#lists")).andExpect(jsonPath("$[0].sameScope").value(true))
                .andExpect(jsonPath("$[0].newFailureCount").value(0));
        mvc.perform(get("/api/v1/qa/projects/online-shop/compare").param("base","d".repeat(40)).param("head",c).header("Authorization",auth(VIEWER)))
                .andExpect(jsonPath("$[0].status").value("NO_BASE_RESULT")).andExpect(jsonPath("$[0].head.tests").value(2));
        mvc.perform(get("/api/v1/qa/projects/online-shop/compare").param("base",a).param("head",c).param("suite","Bad Suite").header("Authorization",auth(VIEWER)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/qa/projects/online-shop/compare").param("base",a).param("head",c)).andExpect(status().isUnauthorized());
    }

    @Test
    void coverageIsRecordedReportedAndEnforcedByTheGate() throws Exception {
        repoFile=REPO_FILE.replace("\"timeoutSeconds\":1200}","\"timeoutSeconds\":1200,\"coverage\":[\"target/site/jacoco/jacoco.xml\"]}");
        coverageXml=com.enterprise.agentharness.qa.CoverageReportsTest.JACOCO; // 75% lines, 60% branches
        String a="a".repeat(40),b="b".repeat(40);
        UUID run=runAt(task(),"cov1",a,PASS,0);
        var cov=mapper.readTree(content(artifact(run,"coverage.json")));
        assertThat(cov.get("linesCovered").asLong()).isEqualTo(75);
        assertThat(cov.get("modules").get(0).get("name").asText()).isEqualTo("order-api");
        ingestor.runOnce();
        assertThat(jdbc.queryForObject("SELECT lines_covered||'/'||lines_missed FROM qa_coverage WHERE run_id=?",String.class,run)).isEqualTo("75/25");

        mvc.perform(put("/api/v1/qa/admin/projects/online-shop/gate").header("Authorization",auth(ADMIN)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requiredSuites\":[\"backend-unit\"],\"minLineCoverage\":{\"backend-unit\":80}}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.gateCoverage.backend-unit").value(80.0));
        try {
            var low=gate(a);
            assertThat(low.get("verdict").asText()).isEqualTo("FAIL");
            assertThat(low.get("reasons").get(0).asText()).isEqualTo("backend-unit line coverage 75.0% is below the gate's 80.0%.");
            assertThat(low.get("suites").get(0).get("lineCoverage").asDouble()).isEqualTo(75.0);
            mvc.perform(put("/api/v1/qa/admin/projects/online-shop/gate").header("Authorization",auth(ADMIN)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"requiredSuites\":[\"backend-unit\"],\"minLineCoverage\":{\"backend-unit\":70}}")).andExpect(status().isOk());
            assertThat(gate(a).get("verdict").asText()).isEqualTo("PASS");
            String md=mapper.readTree(mvc.perform(get("/api/v1/qa/projects/online-shop/report").param("ref",a).header("Authorization",auth(VIEWER)))
                    .andReturn().getResponse().getContentAsString()).get("markdown").asText();
            assertThat(md).contains("| backend-unit | PASSED | 2 | 0 | 75.0% | full |");

            coverageXml=null; // a passing run without a coverage report fails a gate that requires coverage
            runAt(task(),"cov2",b,PASS,0);ingestor.runOnce();
            assertThat(gate(b).get("reasons").get(0).asText()).isEqualTo("backend-unit passed but reported no coverage; the gate requires at least 70.0% line coverage.");
            for (String bad : List.of("{\"requiredSuites\":[\"backend-unit\"],\"minLineCoverage\":{\"other\":50}}",
                    "{\"requiredSuites\":[\"backend-unit\"],\"minLineCoverage\":{\"backend-unit\":101}}")) {
                mvc.perform(put("/api/v1/qa/admin/projects/online-shop/gate").header("Authorization",auth(ADMIN)).contentType(MediaType.APPLICATION_JSON).content(bad))
                        .andExpect(status().isBadRequest());
            }
        } finally {
            jdbc.update("UPDATE qa_quality_gates SET min_line_coverage='{}'::jsonb");
        }
        repoFile=REPO_FILE.replace("\"timeoutSeconds\":1200}","\"timeoutSeconds\":1200,\"coverage\":[\"/etc/passwd.xml\"]}");
        mvc.perform(get("/api/v1/qa/projects/online-shop/suites").header("Authorization",auth(VIEWER))).andExpect(jsonPath("$.repositoryFile").value("invalid"));
    }

    @Test
    void releaseGateFollowsTheEvidenceAtACommit() throws Exception {
        String a="a".repeat(40),b="b".repeat(40),c="c".repeat(40);
        assertThat(gate(a).get("verdict").asText()).isEqualTo("PENDING");
        assertThat(gate(a).get("reasons").get(0).asText()).isEqualTo("backend-unit has not run at this commit.");
        UUID task=task();
        runAt(task,"g1",a,PASS,0);ingestor.runOnce();
        var pass=gate(a);
        assertThat(pass.get("verdict").asText()).isEqualTo("PASS");
        assertThat(pass.get("suites").get(0).get("status").asText()).isEqualTo("PASSED");
        assertThat(pass.get("policy").get("requiredSuites").get(0).asText()).isEqualTo("backend-unit");
        runAt(task,"g2",b,ORDER_FAIL,1);ingestor.runOnce();
        var fail=gate(b);
        assertThat(fail.get("verdict").asText()).isEqualTo("FAIL");
        assertThat(fail.get("reasons").get(0).asText()).isEqualTo("backend-unit failed (TESTS_FAILED, ASSERTION).");
        assertThat(fail.get("notes").get(0).asText()).isEqualTo("New failure in backend-unit: OrderTest#creates (last passed at aaaaaaa).");
        reportXml=PASS;dockerExit=0;
        start(task(),"g3","{\"tool\":\"QA_RUN_SUITE\",\"timeoutSeconds\":900,\"arguments\":{\"project\":\"online-shop\",\"suite\":\"backend-unit\",\"ref\":\""+c+"\"}}")
                .andExpect(status().isAccepted());
        assertThat(gate(c).get("reasons").get(0).asText()).isEqualTo("backend-unit is running at this commit.");
        jdbc.update("DELETE FROM qa_quality_gates");
        try {
            assertThat(gate(a).get("verdict").asText()).isEqualTo("NOT_CONFIGURED");
        } finally {
            jdbc.update("INSERT INTO qa_quality_gates VALUES ('online-shop','[\"backend-unit\"]')");
        }
        mvc.perform(get("/api/v1/qa/projects/online-shop/gate").param("ref","../x").header("Authorization",auth(VIEWER))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/qa/projects/nope/gate").param("ref",a).header("Authorization",auth(VIEWER))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/qa/projects/online-shop/gate").param("ref",a)).andExpect(status().isUnauthorized());
    }

    @Test
    void dashboardSummarizesPassRateFailuresFlakinessAndSpeed() throws Exception {
        String a="a".repeat(40),b="b".repeat(40);
        UUID task=task();
        runAt(task,"d1",a,PASS,0);
        runAt(task,"d2",b,ORDER_FAIL,1);
        runAt(task,"d3",b,PASS,0);
        runAt(task,"d4",b,ORDER_FAIL,1);
        ingestor.runOnce();
        var d=mapper.readTree(mvc.perform(get("/api/v1/qa/projects/online-shop/dashboard").header("Authorization",auth(VIEWER)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(d.get("runs").asInt()).isEqualTo(4);assertThat(d.get("passed").asInt()).isEqualTo(2);assertThat(d.get("passRate").asDouble()).isEqualTo(50.0);
        var suite=d.get("suites").get(0);
        assertThat(suite.get("suite").asText()).isEqualTo("backend-unit");
        assertThat(suite.get("lastOutcome").asText()).isEqualTo("TESTS_FAILED");
        assertThat(suite.get("daily").get(0).get("runs").asInt()).isEqualTo(4);
        var failing=d.get("failingNow").get(0);
        assertThat(failing.get("testClass").asText()+"#"+failing.get("testName").asText()).isEqualTo("OrderTest#creates");
        assertThat(failing.get("consecutiveFailures").asInt()).isEqualTo(1);
        assertThat(failing.get("category").asText()).isEqualTo("ASSERTION");
        assertThat(d.get("flaky").get(0).get("testName").asText()).isEqualTo("creates");
        assertThat(d.get("flaky").get(0).get("flakyCommits").asInt()).isEqualTo(1);
        assertThat(d.get("slowest")).isNotEmpty();
        // A test that failed earlier but no longer runs in the suite's latest run is not "failing now".
        runAt(task,"d5",b,DOCKER_ERROR.replace("IdentityPersistenceTest","OrderTest").replace("name=\"\"","name=\"creates\""),1);
        runAt(task,"d6",b,"<testsuite name=\"X\" tests=\"1\"><testcase classname=\"OtherTest\" name=\"ok\"/></testsuite>",0);
        ingestor.runOnce();
        var later=mapper.readTree(mvc.perform(get("/api/v1/qa/projects/online-shop/dashboard").header("Authorization",auth(VIEWER)))
                .andReturn().getResponse().getContentAsString());
        assertThat(later.get("failingNow")).isEmpty();
        assertThat(d.get("gate").get("requiredSuites").get(0).asText()).isEqualTo("backend-unit");
        mvc.perform(get("/api/v1/qa/projects/online-shop/dashboard").param("days","0").header("Authorization",auth(VIEWER))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/qa/projects/nope/dashboard").header("Authorization",auth(VIEWER))).andExpect(status().isNotFound());
    }

    private JsonNode schedule(String id) throws Exception {
        for (JsonNode s : mapper.readTree(mvc.perform(get("/api/v1/qa/schedules").header("Authorization",auth(VIEWER))).andReturn().getResponse().getContentAsString()))
            if (s.get("id").asText().equals(id)) return s;
        return null;
    }

    @Test
    void onChangeScheduleRunsOncePerNewCommit() throws Exception {
        String id="6b8c0f2e-7f3a-4c1e-9a54-3d2f0e5b7a10";
        jdbc.update("UPDATE qa_schedules SET enabled=TRUE WHERE id=?::uuid",id);
        schedules.runOnce();
        var s=schedule(id);
        assertThat(s.get("lastCommit").asText()).isEqualTo(MAIN_SHA);
        UUID first=UUID.fromString(s.get("lastRunId").asText());
        assertThat(runs.get(first).arguments().get("commit").asText()).isEqualTo(MAIN_SHA);
        assertThat(jdbc.queryForObject("SELECT actor FROM audit_events WHERE run_id=? AND action='RUN_CREATED'",String.class,first)).isEqualTo("scheduler");
        assertThat(jdbc.queryForObject("SELECT title FROM agent_tasks WHERE id=?",String.class,runs.get(first).taskId())).isEqualTo("On change: backend-unit on online-shop@main");
        schedules.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runs",Long.class)).isEqualTo(1);

        remoteSha="d".repeat(40);
        schedules.runOnce();
        assertThat(schedule(id).get("lastError").asText()).isEqualTo("PREVIOUS_RUN_ACTIVE");
        worker();
        schedules.runOnce();
        s=schedule(id);
        assertThat(s.get("lastCommit").asText()).isEqualTo("d".repeat(40));
        assertThat(s.get("lastError").isNull()).isTrue();
        assertThat(runs.get(UUID.fromString(s.get("lastRunId").asText())).taskId()).isEqualTo(runs.get(first).taskId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runs",Long.class)).isEqualTo(2);
    }

    @Test
    void cronSchedulesRunWhenDueAndApproversManageThem() throws Exception {
        String body="{\"project\":\"online-shop\",\"suite\":\"backend-unit\",\"kind\":\"CRON\",\"cron\":\"0 0 2 * * *\"}";
        mvc.perform(post("/api/v1/qa/admin/schedules").header("Authorization",auth(OPERATOR)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        String id=mapper.readTree(mvc.perform(post("/api/v1/qa/admin/schedules").header("Authorization",auth(ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.ref").value("main")).andReturn().getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/api/v1/qa/admin/schedules").header("Authorization",auth(ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        schedules.runOnce();
        assertThat(schedule(id).get("nextRunAt").isNull()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runs",Long.class)).isZero();
        jdbc.update("UPDATE qa_schedules SET next_run_at=clock_timestamp()-interval '1 minute' WHERE id=?::uuid",id);
        schedules.runOnce();
        var s=schedule(id);
        assertThat(s.get("lastRunId").isNull()).isFalse();
        assertThat(java.time.Instant.parse(s.get("nextRunAt").asText())).isAfter(java.time.Instant.now());
        schedules.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runs",Long.class)).isEqualTo(1);

        mvc.perform(put("/api/v1/qa/admin/schedules/"+id+"/enabled").header("Authorization",auth(ADMIN)).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(delete("/api/v1/qa/admin/schedules/"+id).header("Authorization",auth(ADMIN))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForList("SELECT action FROM qa_admin_events WHERE action LIKE 'SCHEDULE_%' ORDER BY id",String.class))
                .containsExactly("SCHEDULE_CREATED","SCHEDULE_DISABLED","SCHEDULE_DELETED");

        String[] bad={"{\"project\":\"online-shop\",\"suite\":\"backend-unit\",\"kind\":\"CRON\",\"cron\":\"0 * * * * *\"}",
                "{\"project\":\"online-shop\",\"suite\":\"backend-unit\",\"kind\":\"CRON\",\"cron\":\"nightly\"}",
                "{\"project\":\"online-shop\",\"suite\":\"backend-unit\",\"kind\":\"ON_CHANGE\",\"cron\":\"0 0 2 * * *\"}",
                "{\"project\":\"online-shop\",\"suite\":\"backend-unit\",\"kind\":\"WEEKLY\"}",
                "{\"project\":\"online-shop\",\"suite\":\"backend-unit\",\"kind\":\"ON_CHANGE\",\"ref\":\"../x\"}",
                "{\"project\":\"online-shop\",\"suite\":\"backend-unit\",\"kind\":\"ON_CHANGE\",\"shell\":\"x\"}"};
        for (String b:bad) mvc.perform(post("/api/v1/qa/admin/schedules").header("Authorization",auth(ADMIN)).contentType(MediaType.APPLICATION_JSON).content(b)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/qa/admin/schedules").header("Authorization",auth(ADMIN)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"project\":\"nope\",\"suite\":\"backend-unit\",\"kind\":\"ON_CHANGE\"}")).andExpect(status().isNotFound());
    }

    @Test
    void schedulesForSuitesThatDoNotResolveRecordTheReason() throws Exception {
        jdbc.update("INSERT INTO qa_schedules(id,project_key,suite_key,ref,kind) VALUES (?,'online-shop','nope','main','ON_CHANGE')",UUID.randomUUID());
        schedules.runOnce();
        assertThat(jdbc.queryForObject("SELECT last_error FROM qa_schedules WHERE suite_key='nope'",String.class)).isEqualTo("unknown or disabled suite");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runs",Long.class)).isZero();
    }

    @Test
    void retentionExpiresOldArtifactContentButKeepsEvidenceAndTheLatestRuns() throws Exception {
        String a="a".repeat(40),b="b".repeat(40);
        UUID task=task();
        UUID older=runAt(task,"r1",a,PASS,0), newer=runAt(task,"r2",a,PASS,0), other=runAt(task,"r3",b,PASS,0);
        ingestor.runOnce();
        jdbc.update("UPDATE run_artifacts SET created_at=clock_timestamp()-interval '40 days'");
        jdbc.update("UPDATE qa_test_results SET run_created_at=clock_timestamp()-interval '200 days' WHERE run_id=?",older);
        long olderBytes=jdbc.queryForObject("SELECT sum(size_bytes) FROM run_artifacts WHERE run_id=?",Long.class,older);
        mvc.perform(get("/api/v1/admin/storage").header("Authorization",auth(OPERATOR))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/retention/run").header("Authorization",auth(OPERATOR))).andExpect(status().isForbidden());
        var result=mapper.readTree(mvc.perform(post("/api/v1/admin/retention/run").header("Authorization",auth(ADMIN)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(result.get("actor").asText()).isEqualTo("admin");
        assertThat(result.get("artifactsExpired").asInt()).isEqualTo(3);
        assertThat(result.get("bytesFreed").asLong()).isEqualTo(olderBytes);
        assertThat(result.get("testResultsDeleted").asInt()).isEqualTo(2);

        var expired=artifact(older,"summary.json");
        assertThat(expired.get("expiredAt").isNull()).isFalse();
        assertThat(expired.get("sha256").asText()).hasSize(64);
        assertThat(expired.get("sizeBytes").asInt()).isPositive();
        mvc.perform(get("/api/v1/artifacts/"+expired.get("id").asText()+"/content").header("Authorization",auth(VIEWER))).andExpect(status().isGone());
        assertThat(content(artifact(newer,"summary.json"))).isNotEmpty();
        assertThat(content(artifact(other,"summary.json"))).isNotEmpty();
        assertThat(triage(older).get("tests").asInt()).isEqualTo(2); // ingestion record keeps its counts

        var report=mapper.readTree(mvc.perform(get("/api/v1/admin/storage").header("Authorization",auth(ADMIN)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(report.get("expiredArtifacts").asInt()).isEqualTo(3);
        assertThat(report.get("liveArtifacts").asInt()).isEqualTo(6);
        assertThat(report.get("tableBytes").get("run_artifacts").asLong()).isPositive();
        assertThat(report.get("lastRun").get("artifactsExpired").asInt()).isEqualTo(3);

        // A run that finished but was not ingested before its artifacts expired is ingested without error.
        UUID late=runAt(task,"r4",b,PASS,0);
        jdbc.update("UPDATE run_artifacts SET created_at=clock_timestamp()-interval '40 days' WHERE run_id=?",other);
        jdbc.update("UPDATE run_artifacts SET content=''::bytea, expired_at=clock_timestamp() WHERE run_id=?",late);
        ingestor.runOnce();
        assertThat(triage(late).get("ingested").asBoolean()).isTrue();
        assertThat(triage(late).get("error").isNull()).isTrue();
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var paths=Files.walk(dir)) { for (Path p:new ArrayList<>(paths.sorted(java.util.Comparator.reverseOrder()).toList())) Files.deleteIfExists(p); }
    }
}
