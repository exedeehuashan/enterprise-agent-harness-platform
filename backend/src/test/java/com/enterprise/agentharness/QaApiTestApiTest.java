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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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

/** API test runs end to end: a fake git serves the repository file, a real local HTTP server plays the system under test. */
@SpringBootTest
@AutoConfigureMockMvc
class QaApiTestApiTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final String ADMIN=UUID.randomUUID().toString(),OPERATOR=UUID.randomUUID().toString(),VIEWER=UUID.randomUUID().toString();
    private static final String SHA="a".repeat(40);
    private static final HttpServer SUT = startSut();
    static final List<String> requests = new CopyOnWriteArrayList<>();
    static volatile String apiFile;
    static volatile String specFile;
    /** When set, the AI provider answers with this JSON instead of the mock's. */
    static volatile String aiAnswer;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentTaskRepository repository;
    @Autowired RunService runs;
    @Autowired ToolRegistry registry;
    @Autowired com.enterprise.agentharness.qa.QaResultIngestor ingestor;
    @Autowired com.enterprise.agentharness.qa.QaSchedules schedules;

    @TestConfiguration
    static class FakeGit {
        @Bean @Primary com.enterprise.agentharness.ai.LlmProvider scriptedAi() {
            var mock = new com.enterprise.agentharness.ai.MockLlm();
            return new com.enterprise.agentharness.ai.LlmProvider() {
                public String id() { return "mock"; }
                public String model() { return "mock-v1"; }
                public Completion complete(Request r) { return aiAnswer == null ? mock.complete(r) : new Completion(aiAnswer, 10, 10); }
            };
        }

        @Bean @Primary ProcessRunner fakeGit() {
            return (argv, logFile, timeout, stop, onStop) -> {
                String last = argv.get(argv.size() - 1);
                if (argv.contains("clone")) Files.createDirectories(Path.of(last));
                if (argv.contains("cat-file") && last.contains(":")) {
                    String content = last.endsWith(":.eahp/api-tests.json") ? apiFile : last.endsWith(":openapi.json") ? specFile : null;
                    if (content == null) return new ProcessRunner.Result(128, false, false);
                    if (argv.contains("-p")) Files.writeString(logFile, content);
                }
                return new ProcessRunner.Result(0, false, false);
            };
        }
    }

    /** A tiny order API: create (201 + Location + ETag), detail, and a redirect that must never be followed. */
    private static HttpServer startSut() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                requests.add(exchange.getRequestMethod() + " " + path + " auth=" + exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body; int status;
                if (exchange.getRequestMethod().equals("POST") && path.matches("/shop/customers/[^/]+/orders")) {
                    String id = UUID.randomUUID().toString();
                    exchange.getResponseHeaders().add("Location", path + "/" + id);
                    exchange.getResponseHeaders().add("ETag", "\"0\"");
                    exchange.getResponseHeaders().add("Set-Cookie", "session=secret");
                    status = 201;
                    body = ("{\"id\":\"" + id + "\",\"status\":\"DRAFT\",\"total\":12.50,\"items\":[{\"sku\":\"SKU-1\"}]}").getBytes(StandardCharsets.UTF_8);
                } else if (exchange.getRequestMethod().equals("GET") && path.matches("/shop/customers/[^/]+/orders/[^/]+")) {
                    status = 200;
                    body = ("{\"id\":\"" + path.substring(path.lastIndexOf('/') + 1) + "\",\"status\":\"DRAFT\"}").getBytes(StandardCharsets.UTF_8);
                } else if (path.equals("/shop/moved")) {
                    exchange.getResponseHeaders().add("Location", "http://example.invalid/steal");
                    status = 302; body = new byte[0];
                } else {
                    status = 404; body = "{\"title\":\"Not Found\"}".getBytes(StandardCharsets.UTF_8);
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                if (body.length > 0) exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
    }

    private static EmbeddedPostgres startPostgres() {
        try { return EmbeddedPostgres.builder().setPort(0).start(); }
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
    }

    @AfterAll
    static void stop() throws IOException { SUT.stop(0); POSTGRES.close(); }

    @BeforeEach
    void reset() {
        repository.deleteAll();
        jdbc.update("DELETE FROM qa_schedules WHERE environment IS NOT NULL");
        jdbc.update("DELETE FROM qa_environments");
        jdbc.update("DELETE FROM qa_test_results"); jdbc.update("DELETE FROM qa_run_ingestions");
        jdbc.update("UPDATE qa_projects SET repository_suites_enabled=TRUE, enabled=TRUE");
        apiFile = FILE; requests.clear(); specFile = "{\"openapi\":\"3.0.1\",\"paths\":{\"/customers/{id}/orders\":{\"get\":{}}}}"; aiAnswer = null;
        jdbc.update("DELETE FROM qa_ai_test_drafts");
    }

    private static final String FILE = """
            {"schemaVersion":1,"collections":[{"key":"order-api-smoke","name":"Order API smoke","service":"order-api",
              "variables":{"customerId":"eahp-{{$runId}}"},
              "cases":[
                {"id":"TC-001","name":"Create and read an order","steps":[
                  {"name":"create","request":{"method":"POST","path":"/customers/{{customerId}}/orders",
                     "headers":{"Idempotency-Key":"{{$uuid}}","Authorization":"Bearer test-only"},"json":{"customerId":"{{customerId}}","lines":[{"sku":"SKU-1","quantity":2}]}},
                   "expect":{"status":201,"headers":{"Location":{"matches":"/shop/customers/.+/orders/.+"},"ETag":{"equals":"\\"0\\""}},
                     "json":[{"path":"$.status","equals":"DRAFT"},{"path":"$.total","equals":12.5},{"path":"$.items","length":1},{"path":"$.id","type":"string"}],"maxMillis":5000},
                   "capture":{"orderId":"$.id"}},
                  {"name":"read","request":{"method":"GET","path":"/customers/{{customerId}}/orders/{{orderId}}"},
                   "expect":{"status":200,"json":[{"path":"$.id","equals":"{{orderId}}"}]}}]},
                {"id":"TC-002","name":"Wrong expectation","steps":[
                  {"request":{"method":"POST","path":"/customers/x/orders","json":{}},"expect":{"status":200}}]},
                {"id":"TC-003","name":"Redirects are not followed","steps":[
                  {"request":{"method":"GET","path":"/moved"},"expect":{"status":302,"headers":{"Location":{"exists":true}}}}]},
                {"id":"TC-004","name":"Unknown variable","steps":[
                  {"request":{"method":"GET","path":"/customers/{{nobody}}/orders"},"expect":{"status":200}}]}]}]}
            """;

    private static String auth(String t) { return "Bearer " + t; }
    private ResultActions putEnv(String token, String body) throws Exception {
        return mvc.perform(put("/api/v1/qa/admin/projects/online-shop/environments/uat").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private String env(int port) { return "{\"name\":\"UAT\",\"services\":{\"order-api\":\"http://127.0.0.1:" + port + "/shop/\"}}"; }
    private ResultActions start(String key, String args) throws Exception {
        UUID task = repository.saveAndFlush(new AgentTask("API tests", "Run API tests")).getId();
        return mvc.perform(post("/api/v1/agent-tasks/" + task + "/runs").header("Authorization", auth(OPERATOR)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"tool\":\"QA_RUN_API_TESTS\",\"timeoutSeconds\":120,\"arguments\":" + args + "}"));
    }
    private UUID started(ResultActions r) throws Exception {
        return UUID.fromString(mapper.readTree(r.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).get("id").asText());
    }
    private JsonNode artifact(UUID run, String name) throws Exception {
        for (JsonNode a : mapper.readTree(mvc.perform(get("/api/v1/agent-runs/" + run + "/artifacts").header("Authorization", auth(VIEWER))).andReturn().getResponse().getContentAsString())) {
            if (a.get("name").asText().equals(name)) return a;
        }
        return null;
    }
    private byte[] content(UUID run, String name) throws Exception {
        return mvc.perform(get("/api/v1/artifacts/" + artifact(run, name).get("id").asText() + "/content").header("Authorization", auth(VIEWER)))
                .andReturn().getResponse().getContentAsByteArray();
    }
    private static final String ARGS = "{\"project\":\"online-shop\",\"collection\":\"order-api-smoke\",\"environment\":\"uat\",\"ref\":\"" + SHA + "\"}";

    @Test
    void environmentsAreApproverManagedAndValidated() throws Exception {
        putEnv(OPERATOR, env(1)).andExpect(status().isForbidden());
        for (String url : List.of("ftp://x", "http://user:pw@x", "http://x/?q=1", "not a url", "http://x#f")) {
            putEnv(ADMIN, "{\"name\":\"UAT\",\"services\":{\"order-api\":\"" + url + "\"}}").andExpect(status().isBadRequest());
        }
        putEnv(ADMIN, "{\"name\":\"UAT\",\"services\":{\"Order API\":\"http://x\"}}").andExpect(status().isBadRequest());
        putEnv(ADMIN, env(SUT.getAddress().getPort())).andExpect(status().isOk()).andExpect(jsonPath("$.services.order-api").value("http://127.0.0.1:" + SUT.getAddress().getPort() + "/shop"));
        putEnv(ADMIN, env(SUT.getAddress().getPort())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/qa/environments").param("project", "online-shop").header("Authorization", auth(VIEWER)))
                .andExpect(jsonPath("$[0].key").value("uat")).andExpect(jsonPath("$[0].enabled").value(true));
        assertThat(jdbc.queryForList("SELECT action FROM qa_admin_events WHERE action LIKE 'ENVIRONMENT%' AND subject='uat' ORDER BY id DESC LIMIT 1", String.class))
                .containsExactly("ENVIRONMENT_CREATED");
        mvc.perform(get("/api/v1/qa/projects/online-shop/api-tests").param("ref", SHA).header("Authorization", auth(VIEWER)))
                .andExpect(jsonPath("$.file").value("found")).andExpect(jsonPath("$.collections[0].key").value("order-api-smoke"))
                .andExpect(jsonPath("$.collections[0].cases").value(4)).andExpect(jsonPath("$.environments[0].key").value("uat"));
        apiFile = "{}";
        mvc.perform(get("/api/v1/qa/projects/online-shop/api-tests").param("ref", SHA).header("Authorization", auth(VIEWER)))
                .andExpect(jsonPath("$.file").value("invalid")).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("invalid API test file")));
        apiFile = null;
        mvc.perform(get("/api/v1/qa/projects/online-shop/api-tests").param("ref", SHA).header("Authorization", auth(VIEWER))).andExpect(jsonPath("$.file").value("absent"));
        mvc.perform(get("/api/v1/qa/projects/online-shop/api-tests").param("ref", "../x").header("Authorization", auth(VIEWER))).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/qa/admin/projects/online-shop/environments/uat").header("Authorization", auth(ADMIN))).andExpect(status().isNoContent());
    }

    @Test
    void collectionRunsAgainstTheEnvironmentAndProducesIngestibleEvidence() throws Exception {
        putEnv(ADMIN, env(SUT.getAddress().getPort())).andExpect(status().isOk());
        UUID run = started(start("api-1", ARGS).andExpect(jsonPath("$.arguments.suite").value("order-api-smoke"))
                .andExpect(jsonPath("$.arguments.commit").value(SHA))
                .andExpect(jsonPath("$.arguments.services.order-api").value("http://127.0.0.1:" + SUT.getAddress().getPort() + "/shop"))
                .andExpect(jsonPath("$.arguments.definition.cases.length()").value(4)));
        new RunWorker(runs, registry).runOnce();
        var result = runs.get(run);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("TESTS_FAILED");

        var summary = mapper.readTree(content(run, "summary.json"));
        assertThat(summary.get("tests").asInt()).isEqualTo(4);
        assertThat(summary.get("failures").asInt()).isEqualTo(1);
        assertThat(summary.get("errors").asInt()).isEqualTo(1);
        assertThat(summary.get("outcome").asText()).isEqualTo("TESTS_FAILED");
        String xml;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(content(run, "junit-reports.zip")))) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("TEST-api-order-api-smoke.xml");
            xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(xml).contains("name=\"TC-001 Create and read an order\"").contains("expected status 200 but was 201").contains("unknown variable nobody");
        String transcript = new String(content(run, "console.log"), StandardCharsets.UTF_8);
        assertThat(transcript).contains("> Authorization: [redacted]").contains("< set-cookie: [redacted]").doesNotContain("test-only").doesNotContain("session=secret");
        // The customer ID came from the run ID, the created order was read back by its captured ID, and the redirect was not followed.
        assertThat(requests).anyMatch(r -> r.startsWith("POST /shop/customers/eahp-" + run + "/orders"))
                .anyMatch(r -> r.matches("GET /shop/customers/eahp-" + run + "/orders/[0-9a-f-]{36} auth=null"))
                .noneMatch(r -> r.contains("steal"));

        assertThat(ingestor.runOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT suite_key||'/'||outcome||'/'||test_count||'/'||failed_count FROM qa_run_ingestions WHERE run_id=?", String.class, run))
                .isEqualTo("order-api-smoke/TESTS_FAILED/4/2");
        assertThat(jdbc.queryForList("SELECT test_name FROM qa_test_results WHERE run_id=? AND status='PASSED' ORDER BY test_name", String.class, run))
                .containsExactly("TC-001 Create and read an order", "TC-003 Redirects are not followed");
    }

    @Test
    void schedulesRunApiCollectionsAgainstAnEnvironment() throws Exception {
        String body = "{\"project\":\"online-shop\",\"suite\":\"order-api-smoke\",\"ref\":\"" + SHA + "\",\"kind\":\"ON_CHANGE\",\"environment\":\"uat\"}";
        mvc.perform(post("/api/v1/qa/admin/schedules").header("Authorization", auth(ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("unknown environment: uat"));
        putEnv(ADMIN, env(SUT.getAddress().getPort())).andExpect(status().isOk());
        String id = mapper.readTree(mvc.perform(post("/api/v1/qa/admin/schedules").header("Authorization", auth(ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.environment").value("uat")).andReturn().getResponse().getContentAsString()).get("id").asText();
        schedules.runOnce();
        UUID run = jdbc.queryForObject("SELECT last_run_id FROM qa_schedules WHERE id=?::uuid", UUID.class, id);
        var created = runs.get(run);
        assertThat(created.tool()).isEqualTo("QA_RUN_API_TESTS");
        assertThat(created.arguments().get("collection").asText()).isEqualTo("order-api-smoke");
        assertThat(created.arguments().get("environment").asText()).isEqualTo("uat");
        new RunWorker(runs, registry).runOnce();
        assertThat(runs.get(run).errorCode()).isEqualTo("TESTS_FAILED"); // the fixture has failing cases
        schedules.runOnce(); // same commit: nothing new
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runs WHERE tool='QA_RUN_API_TESTS'", Long.class)).isEqualTo(1);
    }

    private ResultActions generate(String token, String body) throws Exception {
        return mvc.perform(post("/api/v1/qa/projects/online-shop/ai/api-tests").header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void aiDraftsApiTestsFromOpenApiValidatesThemAndRunsValidDrafts() throws Exception {
        String body = "{\"specPath\":\"openapi.json\",\"service\":\"order-api\",\"ref\":\"" + SHA + "\",\"instructions\":\"Focus on orders\"}";
        generate(VIEWER, body).andExpect(status().isForbidden());
        generate(OPERATOR, body.replace("openapi.json", "../etc/passwd.json")).andExpect(status().isBadRequest());
        generate(OPERATOR, body.replace("openapi.json", "missing.json")).andExpect(status().isBadRequest());
        generate(OPERATOR, body.replace("order-api", "Order API")).andExpect(status().isBadRequest());
        String id = mapper.readTree(generate(OPERATOR, body).andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.collection.key").value("ai-generated")).andExpect(jsonPath("$.collection.service").value("order-api"))
                .andExpect(jsonPath("$.commitSha").value(SHA)).andExpect(jsonPath("$.notes").value("Mock draft: one smoke case."))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        // A draft that tries to reach another host, or targets another service, is stored as invalid and cannot run.
        aiAnswer = "{\"collection\":\"{\\\"key\\\":\\\"x\\\",\\\"name\\\":\\\"Evil\\\",\\\"service\\\":\\\"elsewhere\\\",\\\"cases\\\":[{\\\"id\\\":\\\"T1\\\",\\\"name\\\":\\\"x\\\",\\\"steps\\\":[{\\\"request\\\":{\\\"method\\\":\\\"GET\\\",\\\"path\\\":\\\"http://evil.example/steal\\\"},\\\"expect\\\":{\\\"status\\\":200}}]}]}\",\"notes\":\"\"}";
        String bad = mapper.readTree(generate(OPERATOR, body).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("absolute path"))).andReturn().getResponse().getContentAsString()).get("id").asText();
        // Nested service overrides are removed, so a valid draft can only reach the requested service.
        aiAnswer = "{\"collection\":\"{\\\"key\\\":\\\"x\\\",\\\"name\\\":\\\"Sneaky\\\",\\\"service\\\":\\\"order-api\\\",\\\"cases\\\":[{\\\"id\\\":\\\"T1\\\",\\\"name\\\":\\\"x\\\",\\\"category\\\":\\\"happy-path\\\",\\\"service\\\":\\\"billing-api\\\",\\\"steps\\\":[{\\\"service\\\":\\\"billing-api\\\",\\\"request\\\":{\\\"method\\\":\\\"DELETE\\\",\\\"path\\\":\\\"/all\\\"},\\\"expect\\\":{\\\"status\\\":200}}]}]}\",\"notes\":\"\"}";
        generate(OPERATOR, body).andExpect(jsonPath("$.valid").value(true)).andExpect(jsonPath("$.collection.cases[0].service").doesNotExist())
                .andExpect(jsonPath("$.collection.cases[0].steps[0].service").doesNotExist());
        // Every AI-generated case names its category; a missing or unknown one makes the draft invalid.
        aiAnswer = "{\"collection\":\"{\\\"key\\\":\\\"x\\\",\\\"name\\\":\\\"Unlabelled\\\",\\\"service\\\":\\\"order-api\\\",\\\"cases\\\":[{\\\"id\\\":\\\"T1\\\",\\\"name\\\":\\\"x\\\",\\\"steps\\\":[{\\\"request\\\":{\\\"method\\\":\\\"GET\\\",\\\"path\\\":\\\"/a\\\"},\\\"expect\\\":{\\\"status\\\":200}}]}]}\",\"notes\":\"\"}";
        generate(OPERATOR, body).andExpect(jsonPath("$.valid").value(false)).andExpect(jsonPath("$.error").value("case T1 has no category"));
        aiAnswer = aiAnswer.replace("\\\"name\\\":\\\"x\\\",", "\\\"name\\\":\\\"x\\\",\\\"category\\\":\\\"smoke\\\",");
        generate(OPERATOR, body).andExpect(jsonPath("$.valid").value(false)).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("category must be one of")));
        aiAnswer = aiAnswer.replace("smoke", "not-found");
        generate(OPERATOR, body).andExpect(jsonPath("$.valid").value(true)).andExpect(jsonPath("$.collection.cases[0].category").value("not-found"));
        // A draft too large to fit in a run's arguments is not marked runnable.
        String bigCase = "{\\\"id\\\":\\\"T%d\\\",\\\"name\\\":\\\"" + "x".repeat(190) + "\\\",\\\"category\\\":\\\"validation\\\",\\\"steps\\\":[{\\\"request\\\":{\\\"method\\\":\\\"GET\\\",\\\"path\\\":\\\"/" + "y".repeat(500) + "\\\"},\\\"expect\\\":{\\\"status\\\":200}}]}";
        StringBuilder cases = new StringBuilder();
        for (int i = 0; i < 25; i++) cases.append(i == 0 ? "" : ",").append(String.format(bigCase, i));
        aiAnswer = "{\"collection\":\"{\\\"key\\\":\\\"big\\\",\\\"name\\\":\\\"Big\\\",\\\"service\\\":\\\"order-api\\\",\\\"cases\\\":[" + cases + "]}\",\"notes\":\"\"}";
        generate(OPERATOR, body.replace("\"instructions\"", "\"maxCases\":30,\"instructions\"")).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("too large to run")));
        aiAnswer = "not json";
        generate(OPERATOR, body).andExpect(status().isBadGateway()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("AI_INVALID_ANSWER")));
        aiAnswer = null;

        putEnv(ADMIN, env(SUT.getAddress().getPort())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/qa/projects/online-shop/ai/api-tests/" + bad + "/run").header("Authorization", auth(OPERATOR)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"environment\":\"uat\"}")).andExpect(status().isBadRequest());
        String runId = mapper.readTree(mvc.perform(post("/api/v1/qa/projects/online-shop/ai/api-tests/" + id + "/run").header("Authorization", auth(OPERATOR))
                .contentType(MediaType.APPLICATION_JSON).content("{\"environment\":\"uat\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("runId").asText();
        new RunWorker(runs, registry).runOnce();
        var run = runs.get(UUID.fromString(runId));
        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(run.arguments().get("draft").asText()).isEqualTo(id);
        assertThat(run.arguments().get("suite").asText()).isEqualTo("ai-generated");
        assertThat(requests).anyMatch(r -> r.startsWith("GET /shop/"));
        mvc.perform(get("/api/v1/qa/projects/online-shop/ai/api-tests").header("Authorization", auth(VIEWER))).andExpect(jsonPath("$.length()").value(7));
        // The paged case list: only valid drafts' cases, filtered by category or text, and every draft for the generation history.
        String all = mvc.perform(get("/api/v1/qa/ai/test-cases?project=online-shop&size=100").header("Authorization", auth(VIEWER))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long caseCount = mapper.readTree(all).get("totalElements").asLong();
        assertThat(caseCount).isGreaterThanOrEqualTo(3);
        mvc.perform(get("/api/v1/qa/ai/test-cases?project=online-shop&size=1&page=1").header("Authorization", auth(VIEWER)))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.totalPages").value(caseCount));
        mvc.perform(get("/api/v1/qa/ai/test-cases?category=not-found").header("Authorization", auth(VIEWER)))
                .andExpect(jsonPath("$.items[0].category").value("not-found")).andExpect(jsonPath("$.items[0].caseId").value("T1"))
                .andExpect(jsonPath("$.items[0].steps").value(1)).andExpect(jsonPath("$.items[0].projectKey").value("online-shop"));
        mvc.perform(get("/api/v1/qa/ai/test-cases?q=tc-ai").header("Authorization", auth(VIEWER)))
                .andExpect(jsonPath("$.items[0].caseId").value("TC-AI-001")).andExpect(jsonPath("$.items[0].collectionKey").value("ai-generated"));
        mvc.perform(get("/api/v1/qa/ai/test-cases?q=50%25").header("Authorization", auth(VIEWER))).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/qa/ai/test-cases?category=smoke").header("Authorization", auth(VIEWER))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/qa/ai/test-cases?size=500").header("Authorization", auth(VIEWER))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/qa/ai/drafts?project=online-shop&size=2").header("Authorization", auth(VIEWER)))
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.totalElements").value(7)).andExpect(jsonPath("$.totalPages").value(4));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_usage WHERE feature='qa-test-generation'", Long.class)).isGreaterThanOrEqualTo(3);
    }

    @Test
    void unsafeOrStaleTargetsAreRefused() throws Exception {
        start("no-env", ARGS).andExpect(status().isBadRequest());
        putEnv(ADMIN, env(SUT.getAddress().getPort())).andExpect(status().isOk());
        start("no-collection", ARGS.replace("order-api-smoke", "missing")).andExpect(status().isBadRequest());
        apiFile = FILE.replace("\"path\":\"/moved\"", "\"path\":\"//evil.example/x\"");
        start("bad-file", ARGS).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("absolute path")));
        apiFile = FILE;

        // The environment moves after the run was created: nothing is sent to the new target.
        UUID moved = started(start("moved", ARGS));
        putEnv(ADMIN, env(1)).andExpect(status().isOk());
        new RunWorker(runs, registry).runOnce();
        assertThat(runs.get(moved).errorCode()).isEqualTo("ENVIRONMENT_CHANGED");
        assertThat(requests).isEmpty();

        // Nothing listens on the environment's port: the run says the environment is unavailable.
        int closed;
        try (var socket = new java.net.ServerSocket(0)) { closed = socket.getLocalPort(); }
        putEnv(ADMIN, env(closed)).andExpect(status().isOk());
        apiFile = FILE.replace("{{nobody}}", "nobody"); // every case now fails only because the service cannot be reached
        UUID down = started(start("down", ARGS));
        new RunWorker(runs, registry).runOnce();
        assertThat(runs.get(down).errorCode()).isEqualTo("ENVIRONMENT_UNAVAILABLE");
        mvc.perform(get("/api/v1/qa/environments")).andExpect(status().isUnauthorized());
    }
}
