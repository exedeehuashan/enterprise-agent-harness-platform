package com.enterprise.agentharness;

import com.enterprise.agentharness.qa.ProcessRunner;
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
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The code agent end to end with a fake git/docker and a fake GitHub API: approval, isolation, diff, push and draft PR. */
@SpringBootTest(properties = "eahp.worker.enabled=false")
@AutoConfigureMockMvc
class CodeAgentApiTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final Path WORKDIR = tempDir();
    private static final String ADMIN=UUID.randomUUID().toString(),OPERATOR=UUID.randomUUID().toString(),VIEWER=UUID.randomUUID().toString();
    private static final String SHA = "d".repeat(40);
    static final List<List<String>> calls = new CopyOnWriteArrayList<>();
    static final List<String> pulls = new CopyOnWriteArrayList<>();
    static volatile boolean agentChanges = true, agentError = false;
    static volatile String mavenSettings;
    static volatile int pushExit = 0;
    private static final HttpServer GITHUB = startGitHub();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentTaskRepository tasks;
    @Autowired RunService runs;
    @Autowired ToolRegistry registry;

    @TestConfiguration
    static class Fakes {
        @Bean @Primary ProcessRunner fakeRunner() {
            return (argv, logFile, timeout, stop, onStop) -> {
                calls.add(List.copyOf(argv));
                if (argv.contains("ls-remote")) Files.writeString(logFile, SHA + "\trefs/heads/main\n");
                if (argv.contains("clone")) Files.createDirectories(Path.of(argv.get(argv.size() - 1)));
                if (argv.get(0).equals("docker") && argv.get(1).equals("inspect")) Files.writeString(logFile, "172.30.9.7\n");
                if (argv.get(0).equals("docker") && argv.get(1).equals("run")) {
                    Path src = Path.of(argv.get(argv.indexOf("-w") + 1));
                    String home = argv.stream().filter(a -> a.startsWith("HOME=")).findFirst().orElseThrow().substring(5);
                    Path settings = Path.of(home, ".m2", "settings.xml");
                    mavenSettings = Files.exists(settings) ? Files.readString(settings) : null;
                    String result = argv.stream().filter(a -> a.startsWith("EAHP_RESULT=")).findFirst().orElseThrow().substring(12);
                    Files.writeString(Path.of(result), "{\"type\":\"result\",\"is_error\":" + agentError + ",\"result\":\"Fixed the rounding in OrderTotals.\",\"num_turns\":6,\"total_cost_usd\":0.42}");
                    if (agentChanges) Files.writeString(src.resolve("OrderTotals.java"), "class OrderTotals {}");
                    Files.writeString(logFile, "agent done\n");
                }
                if (argv.contains("diff") && argv.contains("--stat") && agentChanges) Files.writeString(logFile, " OrderTotals.java | 1 +\n");
                if (argv.contains("diff") && argv.contains("--no-ext-diff") && agentChanges) Files.writeString(logFile, "+++ b/OrderTotals.java\n+class OrderTotals {}\n");
                if (argv.contains("push")) { Files.writeString(logFile, "remote: Permission to exedeehuashan/online-shop.git denied.\n"); return new ProcessRunner.Result(pushExit, false, false); }
                return new ProcessRunner.Result(0, false, false);
            };
        }
    }

    private static HttpServer startGitHub() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/repos/exedeehuashan/online-shop/pulls", ex -> {
                pulls.add(ex.getRequestHeaders().getFirst("Authorization") + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] out = "{\"number\":77,\"html_url\":\"https://github.com/exedeehuashan/online-shop/pull/77\"}".getBytes(StandardCharsets.UTF_8);
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
        try { return Files.createTempDirectory("eahp-code-test"); } catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
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
    void reset() { tasks.deleteAll(); jdbc.update("DELETE FROM code_repositories"); calls.clear(); pulls.clear(); agentChanges = true; agentError = false; mavenSettings = null; pushExit = 0; }

    @Test
    void aFailedPushKeepsTheDiffAndTheGitOutputAsEvidence() throws Exception {
        putRepo(ADMIN, REPO).andExpect(status().isOk());
        pushExit = 128;
        var run = start("{\"repository\":\"online-shop\",\"instructions\":\"Fix the rounding of order totals in OrderTotals.\"}");
        approve(run);
        var done = runs.get(UUID.fromString(run.get("id").asText()));
        assertThat(done.errorCode()).isEqualTo("PUSH_FAILED");
        var names = mapper.readTree(mvc.perform(get("/api/v1/agent-runs/" + done.id() + "/artifacts").header("Authorization", "Bearer " + VIEWER))
                .andReturn().getResponse().getContentAsString()).findValuesAsText("name");
        assertThat(names).contains("diff.patch", "changes.txt", "setup.log", "agent-result.json", "egress.log");
        assertThat(pulls).isEmpty();
    }

    private ResultActions putRepo(String token, String body) throws Exception {
        return mvc.perform(put("/api/v1/admin/code-repositories/online-shop").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private static final String REPO = "{\"name\":\"Online shop\",\"repositoryUrl\":\"https://github.com/exedeehuashan/online-shop.git\",\"defaultRef\":\"main\",\"allowPush\":true}";

    private JsonNode start(String args) throws Exception {
        UUID task = tasks.saveAndFlush(new AgentTask("Fix rounding", "Fix the order total rounding")).getId();
        return mapper.readTree(mvc.perform(post("/api/v1/agent-tasks/" + task + "/runs").header("Authorization", "Bearer " + OPERATOR)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tool\":\"CODE_AGENT_RUN\",\"timeoutSeconds\":1800,\"arguments\":" + args + "}")).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());
    }
    private void approve(JsonNode run) throws Exception {
        mvc.perform(post("/api/v1/approvals/" + run.get("approvalId").asText() + "/decision").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("decision", "APPROVE", "expectedDigest", run.get("inputDigest").asText(), "reasonCode", "REVIEWED"))))
                .andExpect(status().isOk());
        new RunWorker(runs, registry).runOnce();
    }
    private List<String> dockerRun() { return calls.stream().filter(c -> c.get(0).equals("docker") && c.get(1).equals("run")).findFirst().orElseThrow(); }

    @Test
    void repositoriesAreApproverManagedAndValidated() throws Exception {
        putRepo(OPERATOR, REPO).andExpect(status().isForbidden());
        for (String url : List.of("http://github.com/x/y", "https://user:pw@github.com/x/y", "ftp://x/y", "https://github.com/x/y?a=1")) {
            putRepo(ADMIN, REPO.replace("https://github.com/exedeehuashan/online-shop.git", url)).andExpect(status().isBadRequest());
        }
        putRepo(ADMIN, REPO).andExpect(status().isOk()).andExpect(jsonPath("$.allowPush").value(true)).andExpect(jsonPath("$.changedBy").value("admin"));
        mvc.perform(get("/api/v1/code-repositories").header("Authorization", "Bearer " + VIEWER)).andExpect(jsonPath("$[0].key").value("online-shop"));
        mvc.perform(get("/api/v1/modules").header("Authorization", "Bearer " + VIEWER)).andExpect(jsonPath("$[?(@.key=='code')].tools[0].name").value("CODE_AGENT_RUN"));
    }

    @Test
    void anApprovedRunRecordsTheDiffPushesABranchAndOpensADraftPullRequest() throws Exception {
        putRepo(ADMIN, REPO).andExpect(status().isOk());
        var run = start("{\"repository\":\"online-shop\",\"instructions\":\"Fix the rounding of order totals in OrderTotals.\",\"maxTurns\":25}");
        assertThat(run.get("status").asText()).isEqualTo("WAITING_APPROVAL");
        assertThat(run.get("arguments").get("commit").asText()).isEqualTo(SHA);
        assertThat(run.get("arguments").get("push").asBoolean()).isTrue();
        assertThat(calls).noneMatch(c -> c.get(0).equals("docker")); // nothing runs before approval
        approve(run);
        var done = runs.get(UUID.fromString(run.get("id").asText()));
        assertThat(done.status()).as(done.errorCode() + " " + done.steps()).isEqualTo("COMPLETED");

        var docker = dockerRun();
        assertThat(docker).contains("--cap-drop", "ALL", "no-new-privileges", "ANTHROPIC_API_KEY", "EAHP_MAX_TURNS=25")
                .noneMatch(a -> a.contains("sk-ant-test-only")).noneMatch(a -> a.contains("ghp_test_write"));
        assertThat(docker.get(docker.size() - 1)).contains("claude --bare -p --output-format json --permission-mode acceptEdits --permission-prompts none");
        // Egress: an internal network of its own, joined by EAHP, and only the allowlisting proxy on EAHP's address as a way out.
        String net = "eahp-agent-" + run.get("id").asText().substring(0, 8) + "-1";
        assertThat(calls).anyMatch(c -> c.equals(List.of("docker", "network", "create", "--internal", "--label", "eahp.run=" + run.get("id").asText(), net)));
        assertThat(calls).anyMatch(c -> c.equals(List.of("docker", "network", "connect", net, "eahp-backend")));
        assertThat(docker.get(docker.indexOf("--network") + 1)).isEqualTo(net);
        assertThat(docker).anyMatch(a -> a.matches("HTTPS_PROXY=http://172\\.30\\.9\\.7:\\d+")).anyMatch(a -> a.startsWith("MAVEN_OPTS=-Duser.home="));
        assertThat(mavenSettings).contains("<host>172.30.9.7</host>").contains("<protocol>https</protocol>");
        assertThat(calls).anyMatch(c -> c.equals(List.of("docker", "network", "rm", net)));
        assertThat(calls.indexOf(calls.stream().filter(c -> c.contains("rm") && c.contains(net)).findFirst().orElseThrow())).isGreaterThan(calls.indexOf(docker));
        // The push uses an askpass helper: no token in any command line.
        var push = calls.stream().filter(c -> c.contains("push")).findFirst().orElseThrow();
        assertThat(push).contains("HEAD:refs/heads/eahp/code-agent/" + run.get("id").asText().substring(0, 8)).noneMatch(a -> a.contains("ghp_test_write"));
        assertThat(calls).noneMatch(c -> String.join(" ", c).contains("ghp_test_write") || String.join(" ", c).contains("sk-ant-test-only"));
        assertThat(pulls).hasSize(1);
        assertThat(pulls.get(0)).startsWith("Bearer ghp_test_write ").contains("\"draft\":true").contains("\"base\":\"main\"").contains("Fixed the rounding in OrderTotals.");

        var artifacts = mapper.readTree(mvc.perform(get("/api/v1/agent-runs/" + done.id() + "/artifacts").header("Authorization", "Bearer " + VIEWER))
                .andReturn().getResponse().getContentAsString());
        assertThat(artifacts.findValuesAsText("name")).contains("summary.json", "diff.patch", "changes.txt", "agent-result.json", "console.log", "egress.log");
        String summaryId = null;
        for (JsonNode a : artifacts) if (a.get("name").asText().equals("summary.json")) summaryId = a.get("id").asText();
        var summary = mapper.readTree(mvc.perform(get("/api/v1/artifacts/" + summaryId + "/content").header("Authorization", "Bearer " + VIEWER)).andReturn().getResponse().getContentAsString());
        assertThat(summary.at("/egress/mode").asText()).isEqualTo("proxy");
        assertThat(summary.at("/egress/allow").toString()).contains("api.anthropic.com").contains("registry.npmjs.org");
        assertThat(Files.list(WORKDIR.resolve("code-runs")).count()).isZero(); // the workspace is removed
    }

    @Test
    void withoutPushOrChangesOrSuccessNothingIsWrittenToGitHub() throws Exception {
        putRepo(ADMIN, REPO.replace("\"allowPush\":true", "\"allowPush\":false")).andExpect(status().isOk());
        var run = start("{\"repository\":\"online-shop\",\"instructions\":\"Refactor the order totals helper.\"}");
        assertThat(run.get("arguments").get("push").asBoolean()).isFalse();
        approve(run);
        assertThat(runs.get(UUID.fromString(run.get("id").asText())).status()).isEqualTo("COMPLETED");
        assertThat(calls).noneMatch(c -> c.contains("push"));
        assertThat(pulls).isEmpty();

        putRepo(ADMIN, REPO).andExpect(status().isOk());
        agentError = true;
        var failed = start("{\"repository\":\"online-shop\",\"instructions\":\"Refactor the order totals helper.\"}");
        approve(failed);
        assertThat(runs.get(UUID.fromString(failed.get("id").asText())).errorCode()).isEqualTo("CODE_AGENT_FAILED");
        assertThat(pulls).isEmpty();

        agentError = false; agentChanges = false;
        var noop = start("{\"repository\":\"online-shop\",\"instructions\":\"Refactor the order totals helper.\"}");
        approve(noop);
        assertThat(runs.get(UUID.fromString(noop.get("id").asText())).status()).isEqualTo("COMPLETED");
        assertThat(pulls).isEmpty();

        // Unknown repositories, short instructions and bad turn limits are rejected before anything runs.
        for (String bad : List.of("{\"repository\":\"nope\",\"instructions\":\"Refactor the order totals helper.\"}",
                "{\"repository\":\"online-shop\",\"instructions\":\"fix\"}", "{\"repository\":\"online-shop\",\"instructions\":\"Refactor the order totals helper.\",\"maxTurns\":500}")) {
            UUID task = tasks.saveAndFlush(new AgentTask("x", null)).getId();
            mvc.perform(post("/api/v1/agent-tasks/" + task + "/runs").header("Authorization", "Bearer " + OPERATOR).header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"tool\":\"CODE_AGENT_RUN\",\"timeoutSeconds\":600,\"arguments\":" + bad + "}"))
                    .andExpect(status().isBadRequest());
        }
    }
}
