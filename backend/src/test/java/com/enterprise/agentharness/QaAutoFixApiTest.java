package com.enterprise.agentharness;

import com.enterprise.agentharness.qa.ProcessRunner;
import com.enterprise.agentharness.qa.QaAutoFixes;
import com.enterprise.agentharness.qa.QaResultIngestor;
import com.enterprise.agentharness.run.RunService;
import com.enterprise.agentharness.run.RunWorker;
import com.enterprise.agentharness.task.AgentTask;
import com.enterprise.agentharness.task.AgentTaskRepository;
import com.enterprise.agentharness.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 方案 4 auto-fix end to end with a fake git, docker and GitHub: a failing suite run is handed to the code agent, its
 * draft pull request is verified by running the suite at the branch's commit, and the verdict is commented on the PR.
 */
@SpringBootTest(properties = {"eahp.worker.enabled=false", "eahp.qa.auto-fix.initial-delay-ms=3600000", "eahp.qa.ingest-initial-delay-ms=3600000"})
@AutoConfigureMockMvc
class QaAutoFixApiTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final Path WORKDIR = tempDir();
    private static final String ADMIN=UUID.randomUUID().toString(),OPERATOR=UUID.randomUUID().toString(),VIEWER=UUID.randomUUID().toString();
    static final String MAIN = "a".repeat(40), FIX = "f".repeat(40);
    static final String FAILING = "<testsuite name=\"OrderTotalsTest\" tests=\"2\" failures=\"1\"><testcase classname=\"com.example.OrderTotalsTest\" name=\"roundsHalfUp\">"
            + "<failure type=\"org.opentest4j.AssertionFailedError\" message=\"expected: 12.35 but was: 12.34\"/></testcase>"
            + "<testcase classname=\"com.example.OrderTotalsTest\" name=\"sums\"/></testsuite>";
    static final String DOCKER_ONLY = "<testsuite name=\"RepoTest\" tests=\"1\" errors=\"1\"><testcase classname=\"com.example.RepoTest\" name=\"saves\">"
            + "<error type=\"java.lang.IllegalStateException\" message=\"Could not find a valid Docker environment\"/></testcase></testsuite>";
    static final String PASSING = "<testsuite name=\"OrderTotalsTest\" tests=\"2\" failures=\"0\"><testcase classname=\"com.example.OrderTotalsTest\" name=\"roundsHalfUp\"/>"
            + "<testcase classname=\"com.example.OrderTotalsTest\" name=\"sums\"/></testsuite>";
    static final List<List<String>> calls = new CopyOnWriteArrayList<>();
    static final List<String> comments = new CopyOnWriteArrayList<>();
    static volatile String report = FAILING;
    static volatile String instructions;
    private static final HttpServer GITHUB = startGitHub();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentTaskRepository tasks;
    @Autowired RunService runs;
    @Autowired ToolRegistry registry;
    @Autowired QaResultIngestor ingestor;
    @Autowired QaAutoFixes fixes;

    @TestConfiguration
    static class Fakes {
        @Bean @Primary ProcessRunner fakeRunner() {
            return (argv, logFile, timeout, stop, onStop) -> {
                calls.add(List.copyOf(argv));
                String last = argv.get(argv.size() - 1);
                if (argv.contains("ls-remote")) Files.writeString(logFile, (argv.stream().anyMatch(a -> a.contains("eahp/code-agent/")) ? FIX : MAIN) + "\t" + argv.get(argv.size() - 3) + "\n");
                if (argv.contains("clone")) Files.createDirectories(Path.of(last));
                if (argv.contains("cat-file") && last.contains(":")) return new ProcessRunner.Result(128, false, false); // no repository suites file
                if (argv.get(0).equals("git") && argv.contains("archive")) Files.writeString(Path.of(argv.get(argv.indexOf("-o") + 1)), "tar");
                if (argv.get(0).equals("tar")) Files.createDirectories(Path.of(last));
                if (argv.get(0).equals("docker") && argv.get(1).equals("inspect")) Files.writeString(logFile, "172.30.9.7\n");
                if (argv.get(0).equals("docker") && argv.get(1).equals("run")) {
                    Path src = Path.of(argv.get(argv.indexOf("-w") + 1));
                    if (last.contains("claude --bare")) {
                        String task = argv.stream().filter(a -> a.startsWith("EAHP_TASK=")).findFirst().orElseThrow().substring(10);
                        instructions = Files.readString(Path.of(task));
                        String result = argv.stream().filter(a -> a.startsWith("EAHP_RESULT=")).findFirst().orElseThrow().substring(12);
                        Files.writeString(Path.of(result), "{\"type\":\"result\",\"is_error\":false,\"result\":\"Rounding used HALF_DOWN; switched to HALF_UP.\",\"num_turns\":7,\"total_cost_usd\":0.2}");
                        Files.writeString(src.resolve("OrderTotals.java"), "class OrderTotals {}");
                    } else {
                        Path reports = Files.createDirectories(src.resolve("backend/order-api/target/surefire-reports"));
                        Files.writeString(reports.resolve("TEST-OrderTotalsTest.xml"), report);
                        return new ProcessRunner.Result(report.equals(PASSING) ? 0 : 1, false, false);
                    }
                }
                if (argv.contains("diff") && argv.contains("--stat")) Files.writeString(logFile, " OrderTotals.java | 1 +\n");
                if (argv.contains("diff") && argv.contains("--no-ext-diff")) Files.writeString(logFile, "+++ b/OrderTotals.java\n+class OrderTotals {}\n");
                return new ProcessRunner.Result(0, false, false);
            };
        }
    }

    private static HttpServer startGitHub() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/repos/exedeehuashan/online-shop/", ex -> {
                String path = ex.getRequestURI().getPath();
                byte[] in = ex.getRequestBody().readAllBytes();
                byte[] out;
                if (path.endsWith("/pulls")) out = "{\"number\":77,\"html_url\":\"https://github.com/exedeehuashan/online-shop/pull/77\"}".getBytes(StandardCharsets.UTF_8);
                else { comments.add(path + " " + new String(in, StandardCharsets.UTF_8)); out = "{\"id\":1}".getBytes(StandardCharsets.UTF_8); }
                ex.sendResponseHeaders(201, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            server.start();
            return server;
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    private static EmbeddedPostgres startPostgres() {
        try { return EmbeddedPostgres.builder().setPort(0).start(); } catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
    }
    private static Path tempDir() {
        try { return Files.createTempDirectory("eahp-autofix-test"); } catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
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
        registry.add("eahp.ai.anthropic.api-key", () -> "sk-ant-test-only");
        registry.add("eahp.github.write-token", () -> "ghp_test_write");
        registry.add("eahp.github.api", () -> "http://127.0.0.1:" + GITHUB.getAddress().getPort());
        registry.add("eahp.qa.self-container", () -> "eahp-backend");
        registry.add("eahp.egress.enabled", () -> "true");
        registry.add("eahp.egress.port", () -> "0");
    }
    @AfterAll static void stop() throws IOException { GITHUB.stop(0); POSTGRES.close(); }

    @BeforeEach
    void reset() {
        tasks.deleteAll();
        jdbc.update("DELETE FROM qa_auto_fixes"); jdbc.update("DELETE FROM qa_auto_fix_settings"); jdbc.update("DELETE FROM code_repositories");
        jdbc.update("DELETE FROM qa_test_results"); jdbc.update("DELETE FROM qa_run_ingestions");
        jdbc.update("UPDATE qa_projects SET enabled=TRUE, repository_suites_enabled=TRUE");
        calls.clear(); comments.clear(); report = FAILING; instructions = null;
    }

    private void worker() { new RunWorker(runs, registry).runOnce(); }
    private void registerRepository(boolean allowPush) throws Exception {
        mvc.perform(put("/api/v1/admin/code-repositories/online-shop").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Online shop\",\"repositoryUrl\":\"https://github.com/exedeehuashan/online-shop\",\"defaultRef\":\"main\",\"allowPush\":" + allowPush + "}"))
                .andExpect(status().isOk());
    }
    private UUID failedRun(String key) throws Exception {
        UUID task = tasks.saveAndFlush(new AgentTask("Test online-shop", "Run the backend suite")).getId();
        var body = mvc.perform(post("/api/v1/agent-tasks/" + task + "/runs").header("Authorization", "Bearer " + OPERATOR).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"tool\":\"QA_RUN_SUITE\",\"timeoutSeconds\":900,\"arguments\":{\"project\":\"online-shop\",\"suite\":\"backend-unit\",\"ref\":\"main\"}}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        worker();
        ingestor.runOnce();
        return UUID.fromString(mapper.readTree(body).get("id").asText());
    }
    private JsonNode autoFix(UUID run, String token, int status) throws Exception {
        String body = mvc.perform(post("/api/v1/qa/runs/" + run + "/auto-fix").header("Authorization", "Bearer " + token)).andExpect(status().is(status))
                .andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? null : mapper.readTree(body);
    }
    private void approve(UUID runId) throws Exception {
        var run = runs.get(runId);
        mvc.perform(post("/api/v1/approvals/" + run.approvalId() + "/decision").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("decision", "APPROVE", "expectedDigest", run.inputDigest(), "reasonCode", "REVIEWED")))).andExpect(status().isOk());
        worker();
    }

    @Test
    void aFailedSuiteIsFixedByTheCodeAgentAndVerifiedOnItsPullRequest() throws Exception {
        registerRepository(true);
        UUID failed = failedRun("fail-1");
        assertThat(jdbc.queryForObject("SELECT outcome FROM qa_run_ingestions WHERE run_id=?", String.class, failed)).isEqualTo("TESTS_FAILED");

        autoFix(failed, VIEWER, 403);
        var fix = autoFix(failed, OPERATOR, 200);
        assertThat(fix.get("status").asText()).isEqualTo("CODING");
        assertThat(fix.get("trigger").asText()).isEqualTo("MANUAL");
        var codeRun = runs.get(UUID.fromString(fix.get("codeRunId").asText()));
        assertThat(codeRun.status()).isEqualTo("WAITING_APPROVAL"); // like every code agent run, it needs an approver
        assertThat(codeRun.arguments().get("ref").asText()).isEqualTo("main");
        assertThat(codeRun.arguments().get("commit").asText()).isEqualTo(MAIN);
        autoFix(failed, OPERATOR, 409);

        approve(codeRun.id());
        assertThat(instructions).contains("The QA suite \"backend-unit\" fails at this commit (tests fail)")
                .contains("mvn -B -ntp verify").contains("- com.example.OrderTotalsTest#roundsHalfUp (ASSERTION): \"expected: 12.35 but was: 12.34\"")
                .contains("Do not delete, skip or weaken tests").doesNotContain("OrderTotalsTest#sums");

        fixes.advance();
        var verifying = fixes.get(UUID.fromString(fix.get("id").asText()));
        assertThat(verifying.status()).isEqualTo("VERIFYING");
        assertThat(verifying.branch()).isEqualTo("eahp/code-agent/" + codeRun.id().toString().substring(0, 8));
        assertThat(verifying.prNumber()).isEqualTo(77);
        assertThat(verifying.verifyCommit()).isEqualTo(FIX);

        report = PASSING; // the agent's change makes the suite pass
        worker();
        ingestor.runOnce();
        assertThat(runs.get(verifying.verifyRunId()).arguments().get("commit").asText()).isEqualTo(FIX);
        fixes.advance();
        var done = fixes.get(verifying.id());
        assertThat(done.status()).isEqualTo("FIXED");
        assertThat(done.result().get("fixed").asInt()).isEqualTo(1);
        assertThat(done.result().get("newFailures").asInt()).isZero();
        assertThat(comments).hasSize(1);
        assertThat(comments.get(0)).startsWith("/repos/exedeehuashan/online-shop/issues/77/comments").contains("Fixed").contains("| Tests fixed | 1 |");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='QA_AUTO_FIX_FIXED'", Long.class)).isEqualTo(1);

        // The verification run is never itself handed to the code agent.
        jdbc.update("UPDATE qa_run_ingestions SET outcome='TESTS_FAILED' WHERE run_id=?", done.verifyRunId());
        autoFix(done.verifyRunId(), OPERATOR, 400);
        mvc.perform(get("/api/v1/qa/auto-fixes").param("project", "online-shop").header("Authorization", "Bearer " + VIEWER))
                .andExpect(jsonPath("$[0].status").value("FIXED")).andExpect(jsonPath("$[0].pullRequestUrl").value("https://github.com/exedeehuashan/online-shop/pull/77"));
    }

    @Test
    void stillFailingAfterTheFixIsReportedAsNotFixed() throws Exception {
        registerRepository(true);
        var fix = autoFix(failedRun("fail-2"), OPERATOR, 200);
        approve(UUID.fromString(fix.get("codeRunId").asText()));
        fixes.advance();
        worker(); // the suite still fails at the agent's commit
        ingestor.runOnce();
        fixes.advance();
        var done = fixes.get(UUID.fromString(fix.get("id").asText()));
        assertThat(done.status()).isEqualTo("NOT_FIXED");
        assertThat(done.result().get("stillFailing").asInt()).isEqualTo(1);
        assertThat(comments.get(0)).contains("Not fixed");
    }

    @Test
    void ineligibleRunsAreRefusedWithAReason() throws Exception {
        UUID failed = failedRun("fail-3");
        String noRepo = mapper.readTree(mvc.perform(post("/api/v1/qa/runs/" + failed + "/auto-fix").header("Authorization", "Bearer " + OPERATOR))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString()).get("message").asText();
        assertThat(noRepo).contains("Register https://github.com/exedeehuashan/online-shop.git for the code agent");
        registerRepository(false);
        autoFix(failed, OPERATOR, 400); // pushing is required to verify on a pull request

        registerRepository(true);
        report = DOCKER_ONLY;
        UUID environment = failedRun("fail-env");
        String reason = mapper.readTree(mvc.perform(post("/api/v1/qa/runs/" + environment + "/auto-fix").header("Authorization", "Bearer " + OPERATOR))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString()).get("message").asText();
        assertThat(reason).contains("environment or runner problems");
        report = PASSING;
        autoFix(failedRun("pass"), OPERATOR, 400);
    }

    @Test
    void automaticProposalsAreOptInAndWaitForAnApprover() throws Exception {
        registerRepository(true);
        failedRun("auto-1");
        fixes.scheduled();
        assertThat(fixes.list("online-shop")).isEmpty(); // off by default
        mvc.perform(put("/api/v1/qa/admin/projects/online-shop/auto-fix").header("Authorization", "Bearer " + OPERATOR).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/qa/admin/projects/online-shop/auto-fix").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(jsonPath("$.enabled").value(true));
        fixes.scheduled();
        var proposed = fixes.list("online-shop");
        assertThat(proposed).hasSize(1);
        assertThat(proposed.get(0).trigger()).isEqualTo("AUTO");
        assertThat(runs.get(proposed.get(0).codeRunId()).status()).isEqualTo("WAITING_APPROVAL");
        fixes.scheduled();
        assertThat(fixes.list("online-shop")).hasSize(1); // one per failed run
    }
}
