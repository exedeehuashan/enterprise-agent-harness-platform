package com.enterprise.agentharness;

import com.enterprise.agentharness.qa.GitHubWriteBack;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Write-back against a fake GitHub API: statuses, the gate status and one comment updated in place. */
@SpringBootTest(properties = "eahp.worker.enabled=false")
@AutoConfigureMockMvc
class GitHubWriteBackApiTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final String ADMIN=UUID.randomUUID().toString(),OPERATOR=UUID.randomUUID().toString(),VIEWER=UUID.randomUUID().toString();
    private static final String SHA="c".repeat(40);
    record Call(String method, String path, String auth, String body) {}
    static final List<Call> calls = new CopyOnWriteArrayList<>();
    static final AtomicInteger failWith = new AtomicInteger(0);
    private static final HttpServer GITHUB = startGitHub();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired GitHubWriteBack writeBack;

    private static HttpServer startGitHub() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                calls.add(new Call(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst("Authorization"), body));
                int code = failWith.get() != 0 ? failWith.get() : ex.getRequestMethod().equals("POST") ? 201 : 200;
                byte[] out = "{\"id\":4242}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(code, out.length);
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
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("eahp.security.admin-token", () -> ADMIN);
        registry.add("eahp.security.operator-token", () -> OPERATOR);
        registry.add("eahp.security.viewer-token", () -> VIEWER);
        registry.add("eahp.github.api", () -> "http://127.0.0.1:" + GITHUB.getAddress().getPort());
        registry.add("eahp.github.write-token", () -> "ghp_test_write_token");
        registry.add("eahp.public-url", () -> "https://eahp.example.com/");
    }
    @AfterAll static void stop() throws IOException { GITHUB.stop(0); POSTGRES.close(); }

    @BeforeEach
    void reset() {
        calls.clear(); failWith.set(0);
        jdbc.update("DELETE FROM qa_github_outbox"); jdbc.update("DELETE FROM qa_github_comments"); jdbc.update("DELETE FROM qa_github_write_back");
        jdbc.update("DELETE FROM qa_pr_runs");
        jdbc.update("INSERT INTO qa_pr_runs(id,project_key,pr_number,head_sha,suite_key,pr_title,pr_url,head_ref,status) VALUES (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), "online-shop", 7, SHA, "backend-unit", "Add EAHP", "https://github.com/exedeehuashan/online-shop/pull/7", "feat", "STARTED");
        jdbc.update("INSERT INTO qa_pr_runs(id,project_key,pr_number,head_sha,suite_key,pr_title,pr_url,head_ref,status,skip_reason) VALUES (?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), "online-shop", 8, SHA, "backend-unit", "From a fork", "https://github.com/exedeehuashan/online-shop/pull/8", "x", "SKIPPED", "FORK_NOT_TRUSTED");
    }

    private void enable(boolean on) throws Exception {
        mvc.perform(put("/api/v1/qa/admin/projects/online-shop/github-write-back").header("Authorization", "Bearer " + ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":" + on + "}")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(on));
    }

    @Test
    void nothingIsWrittenUntilAnApproverEnablesWriteBack() throws Exception {
        writeBack.onIngested("online-shop", "backend-unit", SHA, "PASSED", 42, 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM qa_github_outbox", Long.class)).isZero();
        mvc.perform(put("/api/v1/qa/admin/projects/online-shop/github-write-back").header("Authorization", "Bearer " + OPERATOR)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/qa/projects/online-shop/github-write-back").header("Authorization", "Bearer " + VIEWER))
                .andExpect(jsonPath("$.settings.enabled").value(false)).andExpect(jsonPath("$.settings.tokenConfigured").value(true));
    }

    @Test
    void statusesGateAndOneUpdatedCommentAreWrittenForSameRepositoryPullRequests() throws Exception {
        enable(true);
        writeBack.onIngested("online-shop", "backend-unit", SHA, "TESTS_FAILED", 42, 2);
        writeBack.onIngested("online-shop", "frontend-unit", SHA, "PASSED", 46, 0);
        // Two statuses, but only one gate and one comment (for PR 7; the fork PR 8 gets nothing).
        assertThat(jdbc.queryForList("SELECT kind||':'||COALESCE(pr_number::text,'-') FROM qa_github_outbox ORDER BY id", String.class))
                .containsExactly("STATUS:-", "GATE:-", "COMMENT:7", "STATUS:-");
        assertThat(writeBack.sendOnce()).isEqualTo(4);
        assertThat(calls).allMatch(c -> "Bearer ghp_test_write_token".equals(c.auth()));
        assertThat(calls.get(0).path()).isEqualTo("/repos/exedeehuashan/online-shop/statuses/" + SHA);
        assertThat(calls.get(0).body()).contains("\"state\":\"failure\"").contains("\"context\":\"eahp/backend-unit\"")
                .contains("Failed (TESTS_FAILED): 42 tests, 2 failed").contains("\"target_url\":\"https://eahp.example.com/qa/quality\"");
        assertThat(calls.get(1).body()).contains("\"context\":\"eahp/release-gate\"").contains("\"state\":\"pending\"");
        assertThat(calls.get(2).method()).isEqualTo("POST");
        assertThat(calls.get(2).path()).isEqualTo("/repos/exedeehuashan/online-shop/issues/7/comments");
        assertThat(calls.get(2).body()).contains("eahp-qa-report").contains("EAHP QA report: online-shop");
        assertThat(calls.get(3).body()).contains("\"state\":\"success\"").contains("eahp/frontend-unit");

        // The next result updates the same comment instead of posting another one.
        calls.clear();
        writeBack.onIngested("online-shop", "backend-unit", SHA, "PASSED", 42, 0);
        writeBack.sendOnce();
        assertThat(calls).extracting(Call::method).containsExactly("POST", "POST", "PATCH");
        assertThat(calls.get(2).path()).isEqualTo("/repos/exedeehuashan/online-shop/issues/comments/4242");
    }

    @Test
    void failuresAreRetriedLaterAndShown() throws Exception {
        enable(true);
        writeBack.onIngested("online-shop", "backend-unit", SHA, "PASSED", 42, 0);
        failWith.set(502);
        assertThat(writeBack.sendOnce()).isZero();
        assertThat(writeBack.sendOnce()).isZero(); // not due again yet
        assertThat(calls).hasSize(3);
        mvc.perform(get("/api/v1/qa/projects/online-shop/github-write-back").header("Authorization", "Bearer " + VIEWER))
                .andExpect(jsonPath("$.recent[0].attempts").value(1)).andExpect(jsonPath("$.recent[0].lastError").value("GitHub returned HTTP 502"));
        failWith.set(0);
        jdbc.update("UPDATE qa_github_outbox SET next_attempt_at=clock_timestamp()");
        assertThat(writeBack.sendOnce()).isEqualTo(3);
        enable(false);
        writeBack.onIngested("online-shop", "backend-unit", SHA, "PASSED", 42, 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM qa_github_outbox WHERE status='PENDING'", Long.class)).isZero();
    }
}
