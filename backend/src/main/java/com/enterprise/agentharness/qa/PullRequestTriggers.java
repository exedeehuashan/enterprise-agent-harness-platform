package com.enterprise.agentharness.qa;

import com.enterprise.agentharness.run.RunException;
import com.enterprise.agentharness.run.RunService;
import com.enterprise.agentharness.run.StartRunRequest;
import com.enterprise.agentharness.task.AgentTaskService;
import com.enterprise.agentharness.task.CreateTaskRequest;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Turns open pull requests into QA runs (方案 4, event-driven testing). For each registered GitHub project and
 * each enabled trigger suite, every new PR head commit gets one run pinned to that commit. A newer commit
 * supersedes the previous one: its pending row is marked SUPERSEDED and its active run is cancelled. Pull
 * requests from forks are recorded but never run, because their code is untrusted. Nothing is written to GitHub.
 */
@Service
public class PullRequestTriggers {
    private static final Logger log = LoggerFactory.getLogger(PullRequestTriggers.class);
    static final String ACTOR = "github";
    private final JdbcTemplate jdbc;
    private final QaRegistry registry;
    private final GitHubClient github;
    private final AgentTaskService tasks;
    private final RunService runs;
    private final QaSuiteResolver resolver;

    public PullRequestTriggers(JdbcTemplate jdbc, QaRegistry registry, GitHubClient github, AgentTaskService tasks, RunService runs, QaSuiteResolver resolver) {
        this.jdbc = jdbc; this.registry = registry; this.github = github; this.tasks = tasks; this.runs = runs; this.resolver = resolver;
    }

    public record PullRequestRun(UUID id, String projectKey, int prNumber, String headSha, String suiteKey, String prTitle, String prUrl,
            String headRef, String status, String skipReason, UUID taskId, UUID runId, String runStatus, String runErrorCode,
            Instant createdAt, Instant startedAt, String baseSha) {}
    public record Poll(String projectKey, Instant lastPolledAt, Instant lastSuccessAt, Integer openPullRequests, String lastError) {}

    /** One polling cycle: record new PR head commits, then start whatever can start now. */
    public void runOnce() {
        for (var project : registry.projects()) {
            if (!project.enabled()) continue;
            var repository = GitHubClient.parse(project.repositoryUrl()).orElse(null);
            List<String> suites = triggerSuites(project);
            if (repository == null || suites.isEmpty()) continue;
            try {
                var pulls = github.openPullRequests(repository);
                for (var pr : pulls) for (String suite : suites) record(project.key(), pr, suite);
                recordPoll(project.key(), pulls.size(), null);
            } catch (java.io.IOException ex) {
                log.warn("Polling pull requests failed for {}", project.key());
                recordPoll(project.key(), null, ex.getMessage() == null ? "GitHub request failed" : ex.getMessage());
            } catch (RuntimeException ex) {
                // Internal errors may carry SQL or stack details; only a bounded code is exposed through the API.
                log.warn("Recording pull requests failed for {}", project.key(), ex);
                recordPoll(project.key(), null, "POLL_FAILED");
            }
        }
        for (UUID id : jdbc.queryForList("SELECT id FROM qa_pr_runs WHERE status='PENDING' ORDER BY created_at, id", UUID.class)) {
            try { start(id); }
            catch (RuntimeException ex) { log.warn("Could not start pull request run {}", id); }
        }
    }

    public List<PullRequestRun> list(String projectKey, int limit) {
        return jdbc.query("""
                SELECT p.*, r.status AS run_status, r.error_code AS run_error FROM qa_pr_runs p LEFT JOIN agent_runs r ON r.id = p.run_id
                WHERE (?::text IS NULL OR p.project_key = ?) ORDER BY p.created_at DESC, p.id DESC LIMIT ?
                """, this::map, projectKey, projectKey, limit);
    }

    public List<Poll> polls() {
        return jdbc.query("SELECT * FROM qa_github_polls ORDER BY project_key", (r, i) -> new Poll(r.getString("project_key"),
                instant(r, "last_polled_at"), instant(r, "last_success_at"), (Integer) r.getObject("open_pull_requests"), r.getString("last_error")));
    }

    private List<String> triggerSuites(QaRegistry.Project project) {
        var enabled = jdbc.queryForList("SELECT suite_key FROM qa_pr_triggers WHERE project_key=? AND enabled ORDER BY suite_key", String.class, project.key());
        // Suites may live in the repository file, so only a registry suite explicitly disabled excludes a trigger here;
        // whether the suite exists at the PR's commit is decided when the run starts.
        return enabled.stream().filter(key -> project.suites().stream().noneMatch(s -> s.key().equals(key) && !s.enabled())).toList();
    }

    private void record(String project, GitHubClient.PullRequest pr, String suite) {
        if (pr.number() <= 0 || !pr.headSha().matches("[0-9a-f]{40}")) return;
        String status = pr.sameRepository() ? "PENDING" : "SKIPPED";
        int inserted = jdbc.update("""
                INSERT INTO qa_pr_runs(id,project_key,pr_number,head_sha,suite_key,pr_title,pr_url,head_ref,status,skip_reason)
                VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT (project_key,pr_number,head_sha,suite_key) DO NOTHING
                """, UUID.randomUUID(), project, pr.number(), pr.headSha(), suite, clean(pr.title(), 255), clean(pr.url(), 300),
                clean(pr.headRef(), 255), status, pr.sameRepository() ? null : "FORK_NOT_TRUSTED");
        if (pr.baseSha() != null && pr.baseSha().matches("[0-9a-f]{40}")) {
            // The base moves as the target branch advances; the latest one seen is what the PR currently changes against.
            jdbc.update("UPDATE qa_pr_runs SET base_sha=? WHERE project_key=? AND pr_number=? AND head_sha=?", pr.baseSha(), project, pr.number(), pr.headSha());
        }
        if (inserted == 1) {
            jdbc.update("UPDATE qa_pr_runs SET status='SUPERSEDED' WHERE project_key=? AND pr_number=? AND suite_key=? AND head_sha<>? AND status='PENDING'",
                    project, pr.number(), suite, pr.headSha());
        }
    }

    private void start(UUID id) {
        var row = jdbc.queryForMap("SELECT * FROM qa_pr_runs WHERE id=?", id);
        String project = (String) row.get("project_key"), suiteKey = (String) row.get("suite_key"), sha = (String) row.get("head_sha");
        int number = ((Number) row.get("pr_number")).intValue();
        SuiteDefinition suite;
        try {
            suite = resolver.resolve(project, suiteKey, sha).suite();
        } catch (IllegalArgumentException ex) {
            skip(id, ex.getMessage() != null && ex.getMessage().startsWith("invalid suites file") ? "INVALID_SUITES_FILE" : "SUITE_UNAVAILABLE");
            return;
        }

        var previous = jdbc.queryForList("""
                SELECT p.task_id, p.run_id, p.id FROM qa_pr_runs p WHERE p.project_key=? AND p.pr_number=? AND p.suite_key=? AND p.task_id IS NOT NULL AND p.id<>?
                ORDER BY p.created_at DESC LIMIT 1
                """, project, number, suiteKey, id);
        UUID taskId = previous.isEmpty() ? null : (UUID) previous.get(0).get("task_id");
        if (taskId != null) {
            var active = jdbc.queryForList("SELECT id FROM agent_runs WHERE task_id=? AND status IN ('QUEUED','RUNNING','WAITING_APPROVAL')", UUID.class, taskId);
            if (!active.isEmpty()) {
                // The older commit's run is superseded; cancellation is cooperative, so this row starts on a later cycle.
                runs.cancel(active.get(0));
                jdbc.update("UPDATE qa_pr_runs SET status='SUPERSEDED' WHERE run_id=? AND head_sha<>?", active.get(0), sha);
                return;
            }
        } else {
            String title = clean("PR #" + number + " " + suiteKey + ": " + row.get("pr_title"), 255);
            String description = "Automatic " + suiteKey + " run for " + row.get("pr_url") + " (branch " + row.get("head_ref") + ").";
            taskId = tasks.create(new CreateTaskRequest(title, description)).id();
        }
        var args = JsonNodeFactory.instance.objectNode().put("project", project).put("suite", suiteKey).put("ref", sha);
        String tool = suite.dockerAccess() ? "QA_RUN_PRIVILEGED_SUITE" : "QA_RUN_SUITE";
        String key = "github-pr-" + number + "-" + sha.substring(0, 12) + "-" + suiteKey;
        try {
            var run = runs.start(taskId, key, new StartRunRequest(tool, Math.min(1800, suite.maxTimeoutSeconds()), args), ACTOR);
            jdbc.update("UPDATE qa_pr_runs SET status='STARTED', task_id=?, run_id=?, started_at=clock_timestamp() WHERE id=?", taskId, run.id(), id);
        } catch (RunException ex) {
            jdbc.update("UPDATE qa_pr_runs SET task_id=? WHERE id=?", taskId, id);
            skip(id, "START_REJECTED_" + ex.status().value());
        }
    }

    private void skip(UUID id, String reason) {
        jdbc.update("UPDATE qa_pr_runs SET status='SKIPPED', skip_reason=? WHERE id=?", reason, id);
    }

    private void recordPoll(String project, Integer open, String error) {
        jdbc.update("""
                INSERT INTO qa_github_polls(project_key,last_polled_at,last_success_at,open_pull_requests,last_error)
                VALUES (?, clock_timestamp(), CASE WHEN ?::text IS NULL THEN clock_timestamp() END, ?, ?)
                ON CONFLICT (project_key) DO UPDATE SET last_polled_at=EXCLUDED.last_polled_at,
                    last_success_at=COALESCE(EXCLUDED.last_success_at, qa_github_polls.last_success_at),
                    open_pull_requests=COALESCE(EXCLUDED.open_pull_requests, qa_github_polls.open_pull_requests), last_error=EXCLUDED.last_error
                """, project, error, open, error == null ? null : clean(error, 200));
    }

    /** PR titles and branch names are untrusted text: strip control characters and bound the length. */
    static String clean(Object value, int max) {
        String text = value == null ? "" : value.toString().replaceAll("\\p{Cntrl}", " ").strip();
        if (text.isEmpty()) text = "(untitled)";
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private PullRequestRun map(ResultSet r, int i) throws SQLException {
        return new PullRequestRun(r.getObject("id", UUID.class), r.getString("project_key"), r.getInt("pr_number"), r.getString("head_sha"),
                r.getString("suite_key"), r.getString("pr_title"), r.getString("pr_url"), r.getString("head_ref"), r.getString("status"),
                r.getString("skip_reason"), r.getObject("task_id", UUID.class), r.getObject("run_id", UUID.class), r.getString("run_status"),
                r.getString("run_error"), instant(r, "created_at"), instant(r, "started_at"), r.getString("base_sha"));
    }
    private static Instant instant(ResultSet r, String key) throws SQLException { var t = r.getTimestamp(key); return t == null ? null : t.toInstant(); }
}
