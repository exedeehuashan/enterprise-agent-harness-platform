package com.enterprise.agentharness.qa.impact;

import com.enterprise.agentharness.qa.QaRegistry;
import com.enterprise.agentharness.run.RunException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Change impact for pull requests, computed from the project's git mirror and cached per (project, base, head).
 * Failures (for example an unreachable repository) are cached briefly so a broken repository is not fetched on
 * every request.
 */
@Service
public class ChangeImpactService {
    private static final Logger log = LoggerFactory.getLogger(ChangeImpactService.class);
    static final long ERROR_RETRY_SECONDS = 600;
    private final JdbcTemplate jdbc;
    private final QaRegistry registry;
    private final RepositoryViews views;
    private final ObjectMapper mapper;

    public ChangeImpactService(JdbcTemplate jdbc, QaRegistry registry, RepositoryViews views, ObjectMapper mapper) {
        this.jdbc = jdbc; this.registry = registry; this.views = views; this.mapper = mapper;
    }

    public record Result(String projectKey, int prNumber, String baseSha, String headSha, Instant computedAt,
            ChangeImpactAnalyzer.Impact impact, String error) {}

    /** Impact for one recorded pull request head commit. */
    public Result forPullRequestRun(UUID prRunId) {
        var rows = jdbc.queryForList("SELECT project_key, pr_number, base_sha, head_sha FROM qa_pr_runs WHERE id=?", prRunId);
        if (rows.isEmpty()) throw new RunException(HttpStatus.NOT_FOUND, "Pull request run not found: " + prRunId);
        var row = rows.get(0);
        String project = (String) row.get("project_key"), base = (String) row.get("base_sha"), head = (String) row.get("head_sha");
        int number = ((Number) row.get("pr_number")).intValue();
        if (base == null) return new Result(project, number, null, head, null, null, "BASE_UNKNOWN");
        return compute(project, number, base, head);
    }

    /** Computes impact for recently seen pull request commits that have none yet; called by the poller. */
    public void precompute(int limit) {
        for (UUID id : jdbc.queryForList("""
                SELECT p.id FROM qa_pr_runs p LEFT JOIN qa_change_impacts i
                  ON i.project_key = p.project_key AND i.base_sha = p.base_sha AND i.head_sha = p.head_sha
                WHERE p.base_sha IS NOT NULL AND i.project_key IS NULL ORDER BY p.created_at DESC LIMIT ?
                """, UUID.class, limit)) {
            try { forPullRequestRun(id); } catch (RuntimeException ex) { log.warn("Change impact failed for {}", id); }
        }
    }

    private Result compute(String projectKey, int number, String base, String head) {
        var cached = cached(projectKey, base, head);
        if (cached.isPresent() && (cached.get().error() == null
                || cached.get().computedAt().isAfter(Instant.now().minusSeconds(ERROR_RETRY_SECONDS)))) {
            return withNumber(cached.get(), number);
        }
        var project = registry.project(projectKey).orElse(null);
        if (project == null) return new Result(projectKey, number, base, head, null, null, "PROJECT_UNAVAILABLE");
        String json = null, error = null;
        try {
            var impact = ChangeImpactAnalyzer.analyze(views.of(project), base, head);
            json = mapper.writeValueAsString(impact);
        } catch (IOException ex) {
            error = "REPOSITORY_UNAVAILABLE";
        } catch (RuntimeException ex) {
            log.warn("Change impact analysis failed for {} {}..{}", projectKey, base, head, ex);
            error = "ANALYSIS_FAILED";
        }
        jdbc.update("""
                INSERT INTO qa_change_impacts(project_key, base_sha, head_sha, impact, error) VALUES (?,?,?,?::jsonb,?)
                ON CONFLICT (project_key, base_sha, head_sha) DO UPDATE SET impact=EXCLUDED.impact, error=EXCLUDED.error, computed_at=clock_timestamp()
                """, projectKey, base, head, json, error);
        return withNumber(cached(projectKey, base, head).orElseThrow(), number);
    }

    private Optional<Result> cached(String project, String base, String head) {
        var rows = jdbc.queryForList("SELECT impact::text AS impact, error, computed_at FROM qa_change_impacts WHERE project_key=? AND base_sha=? AND head_sha=?",
                project, base, head);
        if (rows.isEmpty()) return Optional.empty();
        var r = rows.get(0);
        ChangeImpactAnalyzer.Impact impact = null;
        try {
            if (r.get("impact") != null) impact = mapper.readValue((String) r.get("impact"), ChangeImpactAnalyzer.Impact.class);
        } catch (IOException ex) {
            return Optional.empty();
        }
        return Optional.of(new Result(project, 0, base, head, ((java.sql.Timestamp) r.get("computed_at")).toInstant(), impact, (String) r.get("error")));
    }

    private static Result withNumber(Result r, int number) {
        return new Result(r.projectKey(), number, r.baseSha(), r.headSha(), r.computedAt(), r.impact(), r.error());
    }

}
