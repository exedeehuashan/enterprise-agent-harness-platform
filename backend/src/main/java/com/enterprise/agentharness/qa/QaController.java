package com.enterprise.agentharness.qa;

import com.enterprise.agentharness.run.RunException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only catalog of projects under test and their suites, so clients can offer valid choices. */
@RestController
@RequestMapping("/api/v1/qa")
public class QaController {
    private final QaRegistry registry;
    private final PullRequestTriggers pullRequests;
    private final QaSuiteResolver resolver;
    public QaController(QaRegistry registry, PullRequestTriggers pullRequests, QaSuiteResolver resolver) {
        this.registry = registry; this.pullRequests = pullRequests; this.resolver = resolver;
    }

    @GetMapping("/projects")
    public List<QaRegistry.Project> projects() { return registry.projects(); }

    /** Pull request head commits seen by the poller and what happened to each (started, superseded, skipped). */
    @GetMapping("/pull-requests")
    public List<PullRequestTriggers.PullRequestRun> pullRequests(@RequestParam(required = false) String project,
            @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 200) throw new RunException(HttpStatus.BAD_REQUEST, "limit must be 1..200");
        if (project != null && !project.matches("[a-z0-9][a-z0-9-]{1,59}")) throw new RunException(HttpStatus.BAD_REQUEST, "Invalid project");
        return pullRequests.list(project, limit);
    }

    /** Suites available at a ref: registry suites plus those declared in the repository file at that commit, within policy. */
    @GetMapping("/projects/{key}/suites")
    public QaSuiteResolver.Catalog suites(@org.springframework.web.bind.annotation.PathVariable String key, @RequestParam(required = false) String ref) {
        try { return resolver.catalog(key, ref); }
        catch (IllegalArgumentException ex) { throw new RunException(HttpStatus.BAD_REQUEST, ex.getMessage()); }
    }

    @GetMapping("/github-polls")
    public List<PullRequestTriggers.Poll> polls() { return pullRequests.polls(); }
}
