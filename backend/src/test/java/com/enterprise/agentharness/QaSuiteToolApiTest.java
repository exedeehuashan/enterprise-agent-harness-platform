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

    /** What the fake "docker run" leaves in the workspace and how it exits. */
    static volatile String reportXml;
    static volatile int dockerExit;
    static volatile int cloneExit;
    static volatile Runnable duringRun;
    static volatile boolean dockerAvailable;
    /** Content of .eahp/qa-suites.json at any commit, or null when the file does not exist. */
    static volatile String repoFile;
    static final List<List<String>> calls = new CopyOnWriteArrayList<>();

    /** Simulates git, tar and docker by argv, so every outcome is deterministic and no container runs. */
    static class FakeRunner implements ProcessRunner {
        public Result run(List<String> argv, Path logFile, Duration timeout, BooleanSupplier stop, Runnable onStop) throws IOException {
            calls.add(List.copyOf(argv));
            if (argv.get(0).equals("git") && argv.contains("ls-remote")) {
                String ref=argv.get(argv.size()-3).replace("refs/heads/","");
                Files.writeString(logFile, ref.equals("missing") ? "" : MAIN_SHA+"\trefs/heads/"+ref+"\n");
                return new Result(0,false,false);
            }
            if (argv.get(0).equals("git") && argv.contains("clone")) { Files.createDirectories(Path.of(argv.get(argv.size()-1))); return new Result(cloneExit,false,false); }
            if (argv.get(0).equals("git") && argv.contains("cat-file") && argv.get(argv.size()-1).contains(":")) {
                if (repoFile == null) return new Result(128,false,false);
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
                Files.writeString(logFile, "[INFO] BUILD output\n");
                Path src=Path.of(argv.get(argv.indexOf("-w")+1));
                if (reportXml != null) {
                    Path reports=Files.createDirectories(src.resolve("backend/order-api/target/surefire-reports"));
                    Files.writeString(reports.resolve("TEST-com.example.Test.xml"), reportXml);
                }
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
        reportXml=PASS;dockerExit=0;cloneExit=0;duringRun=null;dockerAvailable=true;repoFile=null;calls.clear();
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

    private static void deleteTree(Path dir) throws IOException {
        try (var paths=Files.walk(dir)) { for (Path p:new ArrayList<>(paths.sorted(java.util.Comparator.reverseOrder()).toList())) Files.deleteIfExists(p); }
    }
}
