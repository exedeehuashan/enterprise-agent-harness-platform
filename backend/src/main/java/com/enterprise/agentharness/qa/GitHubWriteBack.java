package com.enterprise.agentharness.qa;

import com.enterprise.agentharness.governance.AuditService;
import com.enterprise.agentharness.run.RunException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Writes QA results back to GitHub pull requests (方案 4): a commit status per suite ({@code eahp/<suite>}), a
 * release-gate status ({@code eahp/release-gate}) and one EAHP comment per pull request carrying the commit QA report,
 * updated in place. It needs a token with commit-status and pull-request write access
 * ({@code EAHP_GITHUB_WRITE_TOKEN}, separate from the read-only polling token) and is opt-in per project. Calls go
 * through an outbox and are retried with backoff, so a GitHub outage never affects runs. Fork pull requests are never
 * run, so nothing is written for them.
 */
@Service
public class GitHubWriteBack {
    private static final Logger log = LoggerFactory.getLogger(GitHubWriteBack.class);
    static final String MARKER = "<!-- eahp-qa-report -->";
    static final int MAX_ATTEMPTS = 10;
    private final JdbcTemplate jdbc;
    private final QaRegistry registry;
    private final QualityService quality;
    private final QaReportService reports;
    private final ObjectMapper mapper;
    private final String apiBase;
    private final String token;
    private final String consoleUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    public GitHubWriteBack(JdbcTemplate jdbc, QaRegistry registry, QualityService quality, QaReportService reports, ObjectMapper mapper,
            @Value("${eahp.github.api:https://api.github.com}") String apiBase, @Value("${eahp.github.write-token:}") String token,
            @Value("${eahp.public-url:}") String consoleUrl) {
        this.jdbc = jdbc; this.registry = registry; this.quality = quality; this.reports = reports; this.mapper = mapper;
        this.apiBase = apiBase; this.token = token; this.consoleUrl = consoleUrl.endsWith("/") ? consoleUrl.substring(0, consoleUrl.length() - 1) : consoleUrl;
    }

    public record Settings(String project, boolean enabled, boolean tokenConfigured, String changedBy) {}
    public record OutboxEntry(long id, String kind, String commitSha, Integer prNumber, String status, int attempts, String lastError, Instant createdAt, Instant sentAt) {}

    public boolean tokenConfigured() { return !token.isBlank(); }

    public Settings settings(String project) {
        registry.project(project).orElseThrow(() -> new RunException(HttpStatus.NOT_FOUND, "Project not found: " + project));
        var rows = jdbc.queryForList("SELECT enabled, changed_by FROM qa_github_write_back WHERE project_key=?", project);
        boolean enabled = !rows.isEmpty() && (Boolean) rows.get(0).get("enabled");
        return new Settings(project, enabled, tokenConfigured(), rows.isEmpty() ? null : (String) rows.get(0).get("changed_by"));
    }

    @PreAuthorize("hasRole('APPROVER')")
    public Settings setEnabled(String project, boolean enabled) {
        var project0 = registry.project(project).orElseThrow(() -> new RunException(HttpStatus.NOT_FOUND, "Project not found: " + project));
        if (enabled && GitHubClient.parse(project0.repositoryUrl()).isEmpty()) throw new RunException(HttpStatus.BAD_REQUEST, "Only GitHub projects support write-back");
        var before = settings(project).enabled();
        jdbc.update("""
                INSERT INTO qa_github_write_back(project_key,enabled,changed_by) VALUES (?,?,?)
                ON CONFLICT (project_key) DO UPDATE SET enabled=EXCLUDED.enabled, changed_by=EXCLUDED.changed_by, changed_at=clock_timestamp()
                """, project, enabled, AuditService.actor());
        if (before != enabled) {
            jdbc.update("INSERT INTO qa_admin_events(actor,action,project_key,subject,before_state,after_state) VALUES (?,?,?,?,?::jsonb,?::jsonb)",
                    AuditService.actor(), "GITHUB_WRITE_BACK_" + (enabled ? "ENABLED" : "DISABLED"), project, null,
                    "{\"enabled\":" + before + "}", "{\"enabled\":" + enabled + "}");
        }
        return settings(project);
    }

    public List<OutboxEntry> outbox(String project, int limit) {
        return jdbc.query("SELECT * FROM qa_github_outbox WHERE project_key=? ORDER BY id DESC LIMIT ?", (r, i) -> new OutboxEntry(r.getLong("id"),
                r.getString("kind"), r.getString("commit_sha"), (Integer) r.getObject("pr_number"), r.getString("status"), r.getInt("attempts"),
                r.getString("last_error"), r.getTimestamp("created_at").toInstant(), r.getTimestamp("sent_at") == null ? null : r.getTimestamp("sent_at").toInstant()),
                project, Math.max(1, Math.min(limit, 200)));
    }

    /**
     * Called after a QA run's evidence is ingested. When the commit is the head of a same-repository pull request of a
     * project with write-back on, queues the suite's status, the gate status and a refresh of each PR's comment.
     */
    public void onIngested(String project, String suite, String commit, String outcome, int tests, int failed) {
        if (!tokenConfigured() || !settings(project).enabled()) return;
        // Fork pull requests are never run (their rows are skipped as FORK_NOT_TRUSTED), so nothing is written to them.
        var prs = jdbc.queryForList("SELECT DISTINCT pr_number FROM qa_pr_runs WHERE project_key=? AND head_sha=? AND COALESCE(skip_reason,'')<>'FORK_NOT_TRUSTED'",
                Integer.class, project, commit);
        if (prs.isEmpty()) return;
        var payload = new LinkedHashMap<String, Object>();
        payload.put("context", "eahp/" + suite);
        payload.put("state", "PASSED".equals(outcome) ? "success" : "failure");
        payload.put("description", ("PASSED".equals(outcome) ? "Passed" : "Failed (" + outcome + ")") + ": " + tests + " tests, " + failed + " failed");
        jdbc.update("INSERT INTO qa_github_outbox(project_key,kind,commit_sha,payload) VALUES (?,?,?,?::jsonb)", project, "STATUS", commit, json(payload));
        enqueueOnce(project, "GATE", commit, null);
        for (Integer pr : prs) enqueueOnce(project, "COMMENT", commit, pr);
    }

    /** GATE and COMMENT are computed when sent, so one pending entry per commit (and pull request) is enough. */
    private void enqueueOnce(String project, String kind, String commit, Integer pr) {
        Long pending = jdbc.queryForObject("SELECT count(*) FROM qa_github_outbox WHERE project_key=? AND kind=? AND commit_sha=? AND pr_number IS NOT DISTINCT FROM ? AND status='PENDING'",
                Long.class, project, kind, commit, pr);
        if (pending == 0) jdbc.update("INSERT INTO qa_github_outbox(project_key,kind,commit_sha,pr_number) VALUES (?,?,?,?)", project, kind, commit, pr);
    }

    /** Sends due outbox entries; returns how many were sent. */
    public int sendOnce() {
        if (!tokenConfigured()) return 0;
        int sent = 0;
        for (var e : jdbc.queryForList("SELECT * FROM qa_github_outbox WHERE status='PENDING' AND next_attempt_at <= clock_timestamp() ORDER BY id LIMIT 20")) {
            long id = ((Number) e.get("id")).longValue();
            String project = (String) e.get("project_key");
            try {
                var p = registry.project(project).orElse(null);
                var repo = p == null ? null : GitHubClient.parse(p.repositoryUrl()).orElse(null);
                if (repo == null || !settings(project).enabled()) { finish(id, "SKIPPED", null); continue; }
                String path = "/repos/" + repo.owner() + "/" + repo.name();
                String commit = (String) e.get("commit_sha");
                switch ((String) e.get("kind")) {
                    case "STATUS" -> {
                        JsonNode payload = mapper.readTree(e.get("payload").toString());
                        status(path, commit, payload.get("state").asText(), payload.get("context").asText(), payload.get("description").asText());
                    }
                    case "GATE" -> {
                        var gate = quality.gate(project, commit);
                        if (gate.verdict().equals("NOT_CONFIGURED")) { finish(id, "SKIPPED", null); continue; }
                        String state = switch (gate.verdict()) { case "PASS" -> "success"; case "FAIL" -> "failure"; default -> "pending"; };
                        String description = gate.verdict().equals("PASS") ? "All required suites passed" : gate.reasons().isEmpty() ? gate.verdict() : gate.reasons().get(0);
                        status(path, commit, state, "eahp/release-gate", description);
                    }
                    case "COMMENT" -> comment(path, project, ((Number) e.get("pr_number")).intValue(), commit);
                    default -> { finish(id, "SKIPPED", null); continue; }
                }
                finish(id, "SENT", null);
                sent++;
            } catch (IOException | RuntimeException ex) {
                int attempts = ((Number) e.get("attempts")).intValue() + 1;
                String error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                if (attempts >= MAX_ATTEMPTS) finish(id, "FAILED", error);
                else jdbc.update("UPDATE qa_github_outbox SET attempts=?, last_error=?, next_attempt_at=? WHERE id=?", attempts, clip(error),
                        Timestamp.from(Instant.now().plusSeconds(Math.min(3600, 30L << Math.min(attempts - 1, 7)))), id);
                log.warn("GitHub write-back {} failed (attempt {}): {}", id, attempts, clip(error));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return sent;
            }
        }
        return sent;
    }

    private void status(String repoPath, String commit, String state, String context, String description) throws IOException, InterruptedException {
        var body = new LinkedHashMap<String, Object>();
        body.put("state", state);
        body.put("context", context);
        body.put("description", description.length() > 140 ? description.substring(0, 139) + "…" : description);
        if (!consoleUrl.isEmpty()) body.put("target_url", consoleUrl + "/qa/quality");
        send("POST", repoPath + "/statuses/" + commit, body);
    }

    private void comment(String repoPath, String project, int pr, String commit) throws IOException, InterruptedException {
        String markdown = MARKER + "\n" + reports.report(project, commit, commit).markdown();
        var body = Map.<String, Object>of("body", markdown);
        var existing = jdbc.queryForList("SELECT comment_id FROM qa_github_comments WHERE project_key=? AND pr_number=?", Long.class, project, pr);
        if (!existing.isEmpty()) {
            int code = sendCode("PATCH", repoPath + "/issues/comments/" + existing.get(0), body);
            if (code == 200) { jdbc.update("UPDATE qa_github_comments SET updated_at=clock_timestamp() WHERE project_key=? AND pr_number=?", project, pr); return; }
            if (code != 404) throw new IOException("GitHub returned HTTP " + code);
            jdbc.update("DELETE FROM qa_github_comments WHERE project_key=? AND pr_number=?", project, pr); // deleted on GitHub: post a new one
        }
        JsonNode created = send("POST", repoPath + "/issues/" + pr + "/comments", body);
        jdbc.update("INSERT INTO qa_github_comments(project_key,pr_number,comment_id) VALUES (?,?,?) ON CONFLICT (project_key,pr_number) DO UPDATE SET comment_id=EXCLUDED.comment_id",
                project, pr, created.path("id").asLong());
    }

    private JsonNode send(String method, String path, Object body) throws IOException, InterruptedException {
        var response = exchange(method, path, body);
        if (response.statusCode() / 100 != 2) throw new IOException("GitHub returned HTTP " + response.statusCode());
        return response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
    }
    private int sendCode(String method, String path, Object body) throws IOException, InterruptedException { return exchange(method, path, body).statusCode(); }

    private HttpResponse<String> exchange(String method, String path, Object body) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(apiBase + path)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "eahp-qa")
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json(body))).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void finish(long id, String status, String error) {
        jdbc.update("UPDATE qa_github_outbox SET status=?, last_error=?, sent_at=CASE WHEN ?='SENT' THEN clock_timestamp() END WHERE id=?", status, clip(error), status, id);
    }
    private String json(Object v) {
        try { return mapper.writeValueAsString(v); } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
    private static String clip(String s) { return s == null ? null : s.length() <= 200 ? s : s.substring(0, 200); }
}
