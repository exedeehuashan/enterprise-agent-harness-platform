package com.enterprise.agentharness;

import com.enterprise.agentharness.qa.EphemeralEnvironments;
import com.enterprise.agentharness.qa.ProcessRunner;
import com.enterprise.agentharness.qa.QaResultIngestor;
import com.enterprise.agentharness.run.RunService;
import com.enterprise.agentharness.run.RunWorker;
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

/** Per-commit environments (P36) with a fake git and docker compose; a local HTTP server plays the started stack. */
@SpringBootTest(properties = {"eahp.worker.enabled=false", "eahp.qa.self-container=eahp-backend", "eahp.qa.environment-reaper-initial-delay-ms=3600000"})
@AutoConfigureMockMvc
class QaEnvironmentApiTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final Path WORKDIR = tempDir();
    private static final String ADMIN=UUID.randomUUID().toString(),OPERATOR=UUID.randomUUID().toString(),VIEWER=UUID.randomUUID().toString();
    private static final String SHA = "e".repeat(40);
    private static final HttpServer SUT = startSut();
    static final List<List<String>> calls = new CopyOnWriteArrayList<>();
    static volatile String envFile, config, override;
    static volatile int upExit;
    static volatile boolean linkOutside;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentTaskRepository tasks;
    @Autowired RunService runs;
    @Autowired ToolRegistry registry;
    @Autowired QaResultIngestor ingestor;
    @Autowired EphemeralEnvironments environments;

    static final String API_FILE = """
            {"schemaVersion":1,"collections":[{"key":"health-smoke","name":"Health","service":"order-api",
              "cases":[{"id":"TC-H-1","name":"Health is up","steps":[{"request":{"method":"GET","path":"/health"},"expect":{"status":200}}]}]}]}
            """;
    static String templates(int port) {
        return "{\"schemaVersion\":1,\"environments\":[{\"key\":\"env-pr\",\"name\":\"PR stack\",\"compose\":[\"compose.yaml\"],"
                + "\"services\":{\"order-api\":{\"service\":\"order-api\",\"port\":" + port + "}},\"apiTests\":[\"health-smoke\"],\"ttlMinutes\":30}]}";
    }
    static final String GOOD_CONFIG = """
            {"name":"x","services":{"order-api":{"image":"shop","ports":[{"target":8080,"published":"8081"}],"networks":{"app":null},
              "volumes":[{"type":"volume","source":"data","target":"/d"}],"build":{"context":"SRC","dockerfile":"Dockerfile"},"env_file":[{"path":"SRC/app.env"}]},
              "postgres":{"image":"postgres","networks":{"app":null}}},
             "networks":{"app":{"name":"PROJECT_app"}},"volumes":{"data":{"name":"PROJECT_data"}}}
            """;

    @TestConfiguration
    static class Fakes {
        @Bean @Primary ProcessRunner fakeRunner() {
            return (argv, logFile, timeout, stop, onStop) -> {
                calls.add(List.copyOf(argv));
                String last = argv.get(argv.size() - 1);
                if (argv.contains("clone")) Files.createDirectories(Path.of(last));
                if (argv.contains("cat-file") && last.contains(":")) {
                    String content = last.endsWith(":.eahp/environments.json") ? envFile : last.endsWith(":.eahp/api-tests.json") ? API_FILE : null;
                    if (content == null) return new ProcessRunner.Result(128, false, false);
                    if (argv.contains("-p")) Files.writeString(logFile, content);
                }
                if (argv.get(0).equals("tar")) {
                    Path target = Path.of(last);
                    Files.createDirectories(target);
                    Files.writeString(target.resolve("compose.yaml"), "services:\n  order-api:\n    build: .\n");
                    if (linkOutside) Files.createSymbolicLink(target.resolve("app.env"), Path.of("/etc/hostname"));
                }
                int compose = argv.indexOf("compose");
                if (compose > 0) {
                    String project = argv.get(argv.indexOf("-p") + 1);
                    String src = argv.contains("--project-directory") ? argv.get(argv.indexOf("--project-directory") + 1) : "";
                    if (argv.contains("config")) Files.writeString(logFile, config.replace("SRC", src).replace("PROJECT", project));
                    if (argv.contains("up")) {
                        override = Files.readString(Path.of(argv.get(argv.lastIndexOf("-f") + 1)));
                        Files.writeString(logFile, "Container " + project + "-order-api-1 Healthy\n");
                        return new ProcessRunner.Result(upExit, false, false);
                    }
                    if (argv.contains("ps")) Files.writeString(logFile, "{\"Service\":\"order-api\",\"Name\":\"127.0.0.1\",\"State\":\"running\"}\n");
                    if (argv.contains("logs")) Files.writeString(logFile, "order-api-1 | Application failed to start\n", java.nio.file.StandardOpenOption.APPEND);
                }
                return new ProcessRunner.Result(0, false, false);
            };
        }
    }

    private static HttpServer startSut() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/health", ex -> {
                byte[] out = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, out.length);
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
        try { return Files.createTempDirectory("eahp-env-test"); } catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
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
    }
    @AfterAll static void stop() throws IOException { SUT.stop(0); POSTGRES.close(); }

    @BeforeEach
    void reset() {
        tasks.deleteAll();
        jdbc.update("DELETE FROM qa_ephemeral_environments"); jdbc.update("DELETE FROM qa_environments");
        jdbc.update("DELETE FROM qa_test_results"); jdbc.update("DELETE FROM qa_run_ingestions");
        jdbc.update("UPDATE qa_projects SET repository_suites_enabled=TRUE, enabled=TRUE");
        calls.clear(); envFile = templates(SUT.getAddress().getPort()); config = GOOD_CONFIG; override = null; upExit = 0; linkOutside = false;
    }

    private JsonNode startRun(String mode) throws Exception {
        var ids = mapper.readTree(mvc.perform(post("/api/v1/qa/projects/online-shop/environment-runs").header("Authorization", "Bearer " + OPERATOR)
                .contentType(MediaType.APPLICATION_JSON).content("{\"environment\":\"env-pr\",\"ref\":\"" + SHA + "\",\"mode\":\"" + mode + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return run(UUID.fromString(ids.get("runId").asText()));
    }
    private JsonNode run(UUID id) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/v1/agent-runs/" + id).header("Authorization", "Bearer " + VIEWER)).andReturn().getResponse().getContentAsString());
    }
    private JsonNode approveAndRun(JsonNode run) throws Exception {
        mvc.perform(post("/api/v1/approvals/" + run.get("approvalId").asText() + "/decision").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("decision", "APPROVE", "expectedDigest", run.get("inputDigest").asText(), "reasonCode", "REVIEWED"))))
                .andExpect(status().isOk());
        new RunWorker(runs, registry).runOnce();
        return run(UUID.fromString(run.get("id").asText()));
    }
    private String artifact(UUID run, String name) throws Exception {
        for (JsonNode a : mapper.readTree(mvc.perform(get("/api/v1/agent-runs/" + run + "/artifacts").header("Authorization", "Bearer " + VIEWER)).andReturn().getResponse().getContentAsString())) {
            if (a.get("name").asText().equals(name)) {
                return mvc.perform(get("/api/v1/artifacts/" + a.get("id").asText() + "/content").header("Authorization", "Bearer " + VIEWER))
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            }
        }
        return null;
    }
    private List<List<String>> compose(String command) { return calls.stream().filter(c -> c.contains("compose") && c.contains(command)).toList(); }

    @Test
    void templatesAreListedAtARef() throws Exception {
        mvc.perform(get("/api/v1/qa/projects/online-shop/environment-templates").param("ref", SHA).header("Authorization", "Bearer " + VIEWER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.file").value("found")).andExpect(jsonPath("$.templates[0].key").value("env-pr"))
                .andExpect(jsonPath("$.templates[0].services[0].composeService").value("order-api"));
        envFile = "{\"schemaVersion\":1,\"environments\":[{\"key\":\"pr\",\"name\":\"x\",\"compose\":[\"compose.yaml\"],\"services\":{\"a\":{\"service\":\"a\",\"port\":1}}}]}";
        mvc.perform(get("/api/v1/qa/projects/online-shop/environment-templates").param("ref", SHA).header("Authorization", "Bearer " + VIEWER))
                .andExpect(jsonPath("$.file").value("invalid")).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("env-pr")));
        envFile = null;
        mvc.perform(get("/api/v1/qa/projects/online-shop/environment-templates").param("ref", SHA).header("Authorization", "Bearer " + VIEWER))
                .andExpect(jsonPath("$.file").value("absent"));
    }

    @Test
    void environmentTestsRunOnAFreshStackThatIsRemovedAfterwards() throws Exception {
        var run = startRun("test");
        assertThat(run.get("status").asText()).isEqualTo("WAITING_APPROVAL");
        assertThat(run.at("/arguments/commit").asText()).isEqualTo(SHA);
        assertThat(run.at("/arguments/suite").asText()).isEqualTo("env-pr");
        assertThat(compose("up")).isEmpty();

        var done = approveAndRun(run);
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        // Compose runs with an empty environment, so ${VAR} cannot read EAHP's secrets; env files stay unresolved for the check.
        var configCall = compose("config").get(0);
        assertThat(configCall.subList(0, 2)).containsExactly("env", "-i");
        assertThat(configCall).contains("--no-env-resolution");
        assertThat(String.join(" ", configCall)).doesNotContain("ADMIN").doesNotContain(ADMIN);
        var up = compose("up").get(0);
        assertThat(up).contains("--build", "--wait", "-d");
        assertThat(override).contains("\"order-api\":\n    ports: !reset []").doesNotContain("postgres");
        assertThat(calls).anyMatch(c -> c.containsAll(List.of("network", "connect")) && c.contains("eahp-backend") && c.get(c.size() - 2).endsWith("_app"));
        var down = compose("down").get(0);
        assertThat(down).contains("-v", "--remove-orphans", "--rmi", "local");
        assertThat(calls).anyMatch(c -> c.containsAll(List.of("network", "disconnect")));

        var summary = mapper.readTree(artifact(UUID.fromString(done.get("id").asText()), "summary.json"));
        assertThat(summary.get("suite").asText()).isEqualTo("env-pr");
        assertThat(summary.get("outcome").asText()).isEqualTo("PASSED");
        assertThat(summary.get("tests").asInt()).isEqualTo(1);
        assertThat(summary.get("environment").asText()).startsWith("eph-");
        assertThat(artifact(UUID.fromString(done.get("id").asText()), "console.log")).contains("Healthy").contains("== health-smoke ==");

        var env = environments.list("online-shop", true).get(0);
        assertThat(env.status()).isEqualTo("STOPPED");
        assertThat(env.commit()).isEqualTo(SHA);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM qa_environments", Long.class)).isZero();
        assertThat(Files.exists(WORKDIR.resolve("envs").resolve(env.id().toString()))).isFalse();

        ingestor.runOnce();
        assertThat(jdbc.queryForMap("SELECT suite_key, outcome, test_count FROM qa_run_ingestions WHERE run_id=?", UUID.fromString(done.get("id").asText())))
                .containsEntry("suite_key", "env-pr").containsEntry("outcome", "PASSED").containsEntry("test_count", 1);
    }

    @Test
    void hostLevelComposeSettingsAreRefusedBeforeAnythingStarts() throws Exception {
        for (String bad : List.of("\"privileged\":true", "\"volumes\":[{\"type\":\"bind\",\"source\":\"/var/run/docker.sock\",\"target\":\"/s\"}]",
                "\"network_mode\":\"host\"", "\"cap_add\":[\"SYS_ADMIN\"]", "\"container_name\":\"fixed\"", "\"env_file\":[{\"path\":\"/proc/1/environ\"}]",
                "\"build\":{\"context\":\"/\",\"dockerfile\":\"Dockerfile\"}")) {
            calls.clear();
            config = "{\"services\":{\"order-api\":{\"image\":\"x\"," + bad + "}},\"networks\":{\"default\":{\"name\":\"p_default\"}}}";
            var done = approveAndRun(startRun("test"));
            assertThat(done.get("errorCode").asText()).as(bad).isEqualTo("ENVIRONMENT_POLICY");
            assertThat(compose("up")).as(bad).isEmpty();
            assertThat(compose("down")).as(bad).hasSize(1);
        }
        assertThat(environments.list("online-shop", true)).allMatch(e -> e.status().equals("FAILED") && e.error() != null);
    }

    @Test
    void symbolicLinksOutOfTheCheckoutAreRefused() throws Exception {
        linkOutside = true;
        var done = approveAndRun(startRun("test"));
        assertThat(done.get("errorCode").asText()).isEqualTo("ENVIRONMENT_POLICY");
        assertThat(compose("config")).isEmpty();
        assertThat(artifact(UUID.fromString(done.get("id").asText()), "summary.json")).contains("ENVIRONMENT_POLICY");
    }

    @Test
    void aStackThatDoesNotBecomeHealthyKeepsItsLogsAndIsRemoved() throws Exception {
        upExit = 1;
        var done = approveAndRun(startRun("test"));
        assertThat(done.get("errorCode").asText()).isEqualTo("ENVIRONMENT_START_FAILED");
        assertThat(artifact(UUID.fromString(done.get("id").asText()), "console.log")).contains("Application failed to start");
        assertThat(compose("down")).hasSize(1);
        assertThat(environments.list("online-shop", true).get(0).status()).isEqualTo("FAILED");
    }

    @Test
    void aStartedEnvironmentRunsUntilStoppedOrExpired() throws Exception {
        var done = approveAndRun(startRun("start"));
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        var live = environments.list("online-shop", false);
        assertThat(live).hasSize(1);
        String key = live.get(0).environmentKey();
        var registered = jdbc.queryForMap("SELECT services::text AS services, network, ephemeral FROM qa_environments WHERE key=?", key);
        assertThat(registered.get("ephemeral")).isEqualTo(true);
        assertThat((String) registered.get("network")).endsWith("_app");
        assertThat((String) registered.get("services")).contains("http://127.0.0.1:" + SUT.getAddress().getPort());
        assertThat(compose("down")).isEmpty();

        mvc.perform(get("/api/v1/qa/temporary-environments").header("Authorization", "Bearer " + VIEWER))
                .andExpect(jsonPath("$[0].environmentKey").value(key)).andExpect(jsonPath("$[0].status").value("RUNNING"));
        // EAHP manages temporary environments; approvers cannot edit or delete them like registered ones.
        mvc.perform(delete("/api/v1/qa/admin/projects/online-shop/environments/" + key).header("Authorization", "Bearer " + ADMIN)).andExpect(status().isConflict());
        mvc.perform(put("/api/v1/qa/admin/projects/online-shop/environments/eph-12345678").header("Authorization", "Bearer " + ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"services\":{\"a\":\"http://h\"}}")).andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/qa/temporary-environments/" + live.get(0).id() + "/stop").header("Authorization", "Bearer " + VIEWER)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/qa/temporary-environments/" + live.get(0).id() + "/stop").header("Authorization", "Bearer " + OPERATOR))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stopped").value(true));
        assertThat(compose("down")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM qa_environments", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM qa_admin_events WHERE action='TEMPORARY_ENVIRONMENT_STOPPED'", Long.class)).isEqualTo(1);

        approveAndRun(startRun("start"));
        jdbc.update("UPDATE qa_ephemeral_environments SET expires_at = clock_timestamp() - interval '1 minute' WHERE status='RUNNING'");
        environments.reap();
        assertThat(environments.list("online-shop", false)).isEmpty();
        assertThat(compose("down")).hasSize(2);
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        mvc.perform(post("/api/v1/qa/projects/online-shop/environment-runs").header("Authorization", "Bearer " + OPERATOR).contentType(MediaType.APPLICATION_JSON)
                .content("{\"environment\":\"env-nope\",\"ref\":\"" + SHA + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/qa/projects/online-shop/environment-runs").header("Authorization", "Bearer " + OPERATOR).contentType(MediaType.APPLICATION_JSON)
                .content("{\"environment\":\"env-pr\",\"mode\":\"forever\"}")).andExpect(status().isBadRequest());
        envFile = templates(1).replace("\"apiTests\":[\"health-smoke\"]", "\"apiTests\":[\"missing\"]");
        mvc.perform(post("/api/v1/qa/projects/online-shop/environment-runs").header("Authorization", "Bearer " + OPERATOR).contentType(MediaType.APPLICATION_JSON)
                .content("{\"environment\":\"env-pr\",\"ref\":\"" + SHA + "\"}")).andExpect(status().isBadRequest());
    }
}
