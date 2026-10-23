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
    public QaController(QaRegistry registry, PullRequestTriggers pullRequests) { this.registry = registry; this.pullRequests = pullRequests; }

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

    @GetMapping("/github-polls")
    public List<PullRequestTriggers.Poll> polls() { return pullRequests.polls(); }
}
