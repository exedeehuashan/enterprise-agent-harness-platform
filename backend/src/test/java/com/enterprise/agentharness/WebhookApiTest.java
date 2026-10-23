package com.enterprise.agentharness;

import com.enterprise.agentharness.notify.Webhooks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** Webhooks against a real local receiver: ordering, filtering, signatures, retries and pausing. */
@SpringBootTest(properties = "eahp.worker.enabled=false")
@AutoConfigureMockMvc
class WebhookApiTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final String ADMIN=UUID.randomUUID().toString(),OPERATOR=UUID.randomUUID().toString(),VIEWER=UUID.randomUUID().toString();
    static final List<Map<String, String>> received = new CopyOnWriteArrayList<>();
    static final AtomicInteger status = new AtomicInteger(200);
    private static final HttpServer RECEIVER = startReceiver();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired Webhooks webhooks;

    private static HttpServer startReceiver() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/hook", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                received.add(Map.of("event", exchange.getRequestHeaders().getFirst("X-EAHP-Event"), "signature", exchange.getRequestHeaders().getFirst("X-EAHP-Signature"),
                        "body", new String(body)));
                exchange.sendResponseHeaders(status.get(), -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
    }
    private static EmbeddedPostgres startPostgres() {
        try { return EmbeddedPostgres.builder().setPort(0).start(); } catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
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
    @AfterAll static void stop() throws IOException { RECEIVER.stop(0); POSTGRES.close(); }

    @BeforeEach
    void reset() { jdbc.update("DELETE FROM webhook_subscriptions"); received.clear(); status.set(200); }

    private ResultActions create(String token, String body) throws Exception {
        return mvc.perform(post("/api/v1/admin/webhooks").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private String hook() { return "http://127.0.0.1:" + RECEIVER.getAddress().getPort() + "/hook"; }
    private void event(String action, String code) {
        jdbc.update("INSERT INTO audit_events(actor,action,run_id,code) VALUES ('test',?,?,?)", action, UUID.randomUUID(), code);
    }

    @Test
    void subscriptionsAreApproverOnlyAndValidated() throws Exception {
        create(OPERATOR, "{\"name\":\"x\",\"url\":\"" + hook() + "\",\"events\":[\"*\"]}").andExpect(status().isForbidden());
        for (String bad : List.of("{\"name\":\"x\",\"url\":\"ftp://x\",\"events\":[\"*\"]}", "{\"name\":\"x\",\"url\":\"http://u:p@x/\",\"events\":[\"*\"]}",
                "{\"name\":\"x\",\"url\":\"" + hook() + "\",\"events\":[]}", "{\"name\":\"x\",\"url\":\"" + hook() + "\",\"events\":[\"run_*\"]}",
                "{\"name\":\"x\",\"url\":\"" + hook() + "\",\"events\":[\"*\"],\"extra\":1}")) {
            create(ADMIN, bad).andExpect(status().isBadRequest());
        }
        String body = create(ADMIN, "{\"name\":\"Chat\",\"url\":\"" + hook() + "\",\"events\":[\"RUN_*\"]}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").isString()).andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/v1/admin/webhooks").header("Authorization", "Bearer " + ADMIN)).andExpect(jsonPath("$[0].name").value("Chat"))
                .andExpect(jsonPath("$[0].secret").doesNotExist());
        mvc.perform(get("/api/v1/admin/webhooks").header("Authorization", "Bearer " + VIEWER)).andExpect(status().isForbidden());
        assertThat(body).contains("\"events\":[\"RUN_*\"]");
    }

    @Test
    void eventsAreDeliveredInOrderSignedFilteredAndRetried() throws Exception {
        event("RUN_CREATED", null); // before the subscription: not delivered
        JsonNode created = mapper.readTree(create(ADMIN, "{\"name\":\"Chat\",\"url\":\"" + hook() + "\",\"events\":[\"RUN_*\",\"QA_RESULT_INGESTED\"]}")
                .andReturn().getResponse().getContentAsString());
        String id = created.get("subscription").get("id").asText(), secret = created.get("secret").asText();
        event("RUN_CREATED", null); event("APPROVAL_REQUESTED", null); event("RUN_COMPLETED", null); event("QA_RESULT_INGESTED", "TESTS_FAILED");
        assertThat(webhooks.dispatchOnce()).isEqualTo(3);
        assertThat(received).extracting(m -> m.get("event")).containsExactly("RUN_CREATED", "RUN_COMPLETED", "QA_RESULT_INGESTED");
        for (var m : received) assertThat(m.get("signature")).isEqualTo("sha256=" + Webhooks.sign(secret, m.get("body").getBytes()));
        assertThat(mapper.readTree(received.get(2).get("body")).get("code").asText()).isEqualTo("TESTS_FAILED");

        // The receiver fails: the event is kept, retried later, and the subscription pauses after repeated failures.
        received.clear(); status.set(500);
        event("RUN_FAILED", "BOOM");
        assertThat(webhooks.dispatchOnce()).isZero();
        mvc.perform(get("/api/v1/admin/webhooks").header("Authorization", "Bearer " + ADMIN))
                .andExpect(jsonPath("$[0].consecutiveFailures").value(1)).andExpect(jsonPath("$[0].lastError").value("HTTP 500"))
                .andExpect(jsonPath("$[0].nextAttemptAt").isString());
        assertThat(webhooks.dispatchOnce()).isZero(); // not due yet
        assertThat(received).hasSize(1);
        jdbc.update("UPDATE webhook_subscriptions SET consecutive_failures=19, next_attempt_at=NULL");
        webhooks.dispatchOnce();
        mvc.perform(get("/api/v1/admin/webhooks").header("Authorization", "Bearer " + ADMIN)).andExpect(jsonPath("$[0].enabled").value(false));

        // Resuming retries the same event first.
        status.set(204); received.clear();
        mvc.perform(put("/api/v1/admin/webhooks/" + id + "/enabled").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(jsonPath("$.consecutiveFailures").value(0));
        assertThat(webhooks.dispatchOnce()).isEqualTo(1);
        assertThat(received).extracting(m -> m.get("event")).containsExactly("RUN_FAILED");
        mvc.perform(get("/api/v1/admin/webhooks/" + id + "/deliveries").header("Authorization", "Bearer " + ADMIN))
                .andExpect(jsonPath("$[0].statusCode").value(204)).andExpect(jsonPath("$[1].error").value("HTTP 500"));
        mvc.perform(delete("/api/v1/admin/webhooks/" + id).header("Authorization", "Bearer " + ADMIN)).andExpect(status().isNoContent());
    }
}
