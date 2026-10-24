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
    /** What the fake chat robot answers (DingTalk/WeCom style), and what the fake SMTP server received. */
    static volatile String robotAnswer = "{\"errcode\":0,\"errmsg\":\"ok\"}";
    static final List<String> robot = new CopyOnWriteArrayList<>();
    static final List<String> mails = new CopyOnWriteArrayList<>();
    private static final java.net.ServerSocket SMTP = startSmtp();

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
            server.createContext("/robot", exchange -> {
                robot.add(exchange.getRequestURI().getRawQuery() + " " + new String(exchange.getRequestBody().readAllBytes()));
                byte[] out = robotAnswer.getBytes();
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
    }

    /** A minimal SMTP server: accepts every command and records each message's DATA. */
    private static java.net.ServerSocket startSmtp() {
        try {
            var server = new java.net.ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress());
            var t = new Thread(() -> {
                while (!server.isClosed()) {
                    try (var socket = server.accept()) {
                        var in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                        var out = new java.io.PrintWriter(socket.getOutputStream(), true);
                        out.print("220 fake\r\n"); out.flush();
                        String line;
                        while ((line = in.readLine()) != null) {
                            String cmd = line.toUpperCase();
                            if (cmd.startsWith("EHLO") || cmd.startsWith("HELO")) out.print("250 fake\r\n");
                            else if (cmd.equals("DATA")) {
                                out.print("354 go\r\n"); out.flush();
                                var data = new StringBuilder();
                                while ((line = in.readLine()) != null && !line.equals(".")) data.append(line).append('\n');
                                mails.add(data.toString());
                                out.print("250 queued\r\n");
                            } else if (cmd.equals("QUIT")) { out.print("221 bye\r\n"); out.flush(); break; }
                            else out.print("250 ok\r\n");
                            out.flush();
                        }
                    } catch (IOException ignored) { /* closed */ }
                }
            });
            t.setDaemon(true);
            t.start();
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
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> String.valueOf(SMTP.getLocalPort()));
        registry.add("spring.mail.properties.mail.smtp.auth", () -> "false");
        registry.add("spring.mail.properties.mail.smtp.starttls.enable", () -> "false");
        registry.add("eahp.notify.mail-from", () -> "eahp@example.com");
        registry.add("eahp.public-url", () -> "https://eahp.example.com");
    }
    @AfterAll static void stop() throws IOException { RECEIVER.stop(0); SMTP.close(); POSTGRES.close(); }

    @BeforeEach
    void reset() { jdbc.update("DELETE FROM webhook_subscriptions"); received.clear(); status.set(200); robot.clear(); mails.clear(); robotAnswer = "{\"errcode\":0,\"errmsg\":\"ok\"}"; }

    @Test
    void chatAndEmailSubscriptionsAreValidatedPerPlatform() throws Exception {
        for (String bad : List.of(
                "{\"name\":\"x\",\"format\":\"slack\",\"url\":\"https://evil.example/hook\",\"events\":[\"*\"]}",
                "{\"name\":\"x\",\"format\":\"dingtalk\",\"url\":\"http://oapi.dingtalk.com/robot/send?access_token=t\",\"events\":[\"*\"]}",
                "{\"name\":\"x\",\"format\":\"wecom\",\"url\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=k\",\"secret\":\"s\",\"events\":[\"*\"]}",
                "{\"name\":\"x\",\"format\":\"teams\",\"url\":\"https://x/\",\"events\":[\"*\"]}",
                "{\"name\":\"x\",\"format\":\"email\",\"recipients\":[\"not-an-address\"],\"events\":[\"*\"]}",
                "{\"name\":\"x\",\"format\":\"email\",\"url\":\"https://x/\",\"recipients\":[\"qa@example.com\"],\"events\":[\"*\"]}",
                "{\"name\":\"x\",\"url\":\"" + hook() + "\",\"secret\":\"mine\",\"events\":[\"*\"]}")) {
            create(ADMIN, bad).andExpect(status().isBadRequest());
        }
        create(ADMIN, "{\"name\":\"钉钉 QA\",\"format\":\"dingtalk\",\"url\":\"https://oapi.dingtalk.com/robot/send?access_token=t\",\"secret\":\"SECabc\",\"events\":[\"QA_*\"]}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.secret").doesNotExist()).andExpect(jsonPath("$.subscription.format").value("dingtalk"));
        create(ADMIN, "{\"name\":\"Feishu\",\"format\":\"feishu\",\"url\":\"https://open.feishu.cn/open-apis/bot/v2/hook/x\",\"events\":[\"*\"]}").andExpect(status().isCreated());
        create(ADMIN, "{\"name\":\"Slack\",\"format\":\"slack\",\"url\":\"https://hooks.slack.com/services/T/B/x\",\"events\":[\"*\"]}").andExpect(status().isCreated());
        create(ADMIN, "{\"name\":\"WeCom\",\"format\":\"wecom\",\"url\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=k\",\"events\":[\"*\"]}").andExpect(status().isCreated());
        create(ADMIN, "{\"name\":\"Mail\",\"format\":\"email\",\"recipients\":[\"qa@example.com\",\"lead@example.com\"],\"events\":[\"QA_*\"]}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.subscription.url").value("mailto:qa@example.com,lead@example.com"));
        assertThat(jdbc.queryForObject("SELECT secret FROM webhook_subscriptions WHERE format='dingtalk'", String.class)).isEqualTo("SECabc");
    }

    @Test
    void chatRobotsGetSignedMessagesAndTheirRejectionsCountAsFailures() throws Exception {
        // Inserted directly: the API only accepts the platforms' own hosts, the test robot listens locally.
        UUID id = UUID.randomUUID();
        long last = jdbc.queryForObject("SELECT COALESCE(max(id),0) FROM audit_events", Long.class);
        jdbc.update("INSERT INTO webhook_subscriptions(id,name,format,url,secret,events,cursor_id,created_by) VALUES (?,?,?,?,?,?::jsonb,?,?)", id, "Ding", "dingtalk",
                "http://127.0.0.1:" + RECEIVER.getAddress().getPort() + "/robot?access_token=t", "SECabc", "[\"QA_*\"]", last, "admin");
        UUID run = UUID.randomUUID();
        jdbc.update("INSERT INTO audit_events(actor,action,run_id,code) VALUES ('qa','QA_RESULT_INGESTED',?,'TESTS_FAILED')", run);
        assertThat(webhooks.dispatchOnce()).isEqualTo(1);
        assertThat(robot).hasSize(1);
        String query = robot.get(0).substring(0, robot.get(0).indexOf(' '));
        var body = mapper.readTree(robot.get(0).substring(robot.get(0).indexOf(' ') + 1));
        assertThat(query).startsWith("access_token=t&timestamp=").contains("&sign=");
        assertThat(body.get("msgtype").asText()).isEqualTo("markdown");
        assertThat(body.at("/markdown/title").asText()).startsWith("❌ QA tests failed");
        assertThat(body.at("/markdown/text").asText()).contains("https://eahp.example.com/runs/" + run);
        // DingTalk answers 200 with an error code when it rejects a message: that is a failed delivery, retried later.
        robotAnswer = "{\"errcode\":310000,\"errmsg\":\"sign not match\"}";
        jdbc.update("INSERT INTO audit_events(actor,action,run_id,code) VALUES ('qa','QA_RESULT_INGESTED',?,'PASSED')", UUID.randomUUID());
        assertThat(webhooks.dispatchOnce()).isZero();
        assertThat(webhooks.get(id).lastError()).isEqualTo("error 310000: sign not match");
        assertThat(webhooks.get(id).consecutiveFailures()).isEqualTo(1);
    }

    @Test
    void emailSubscriptionsSendOneMessagePerEventAndTestMessagesCanBeSent() throws Exception {
        String id = mapper.readTree(create(ADMIN, "{\"name\":\"Mail\",\"format\":\"email\",\"recipients\":[\"qa@example.com\"],\"events\":[\"QA_QUARANTINE_SUGGESTED\"]}")
                .andReturn().getResponse().getContentAsString()).at("/subscription/id").asText();
        event("QA_QUARANTINE_SUGGESTED", "backend-unit");
        event("RUN_COMPLETED", null);
        assertThat(webhooks.dispatchOnce()).isEqualTo(1);
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0)).contains("To: qa@example.com").contains("From: eahp@example.com").contains("Subject: [EAHP]")
                .contains("Flaky test suggested for quarantine");
        mvc.perform(post("/api/v1/admin/webhooks/" + id + "/test").header("Authorization", "Bearer " + OPERATOR)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/webhooks/" + id + "/test").header("Authorization", "Bearer " + ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("EAHP_TEST")).andExpect(jsonPath("$.error").doesNotExist());
        assertThat(mails).hasSize(2);
    }

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
