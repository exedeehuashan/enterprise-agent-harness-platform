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
    private final QaTriageService triageService;
    private final com.enterprise.agentharness.run.RunService runs;
    private final com.enterprise.agentharness.qa.impact.ChangeImpactService impacts;
    private final QualityService quality;
    private final GitMirror git;
    private final QaReportService reports;
    private final QaComparison comparison;
    public QaController(QaRegistry registry, PullRequestTriggers pullRequests, QaSuiteResolver resolver, QaTriageService triageService,
            com.enterprise.agentharness.run.RunService runs, com.enterprise.agentharness.qa.impact.ChangeImpactService impacts,
            QualityService quality, GitMirror git, QaReportService reports, QaComparison comparison) {
        this.registry = registry; this.pullRequests = pullRequests; this.resolver = resolver; this.triageService = triageService;
        this.runs = runs; this.impacts = impacts; this.quality = quality; this.git = git; this.reports = reports; this.comparison = comparison;
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

    /** Why a QA run failed: a category and explanation per failing test, with each test's history in this suite. */
    @GetMapping("/runs/{runId}/triage")
    public QaTriageService.Triage triage(@org.springframework.web.bind.annotation.PathVariable java.util.UUID runId) {
        runs.get(runId);
        return triageService.triage(runId);
    }

    @GetMapping("/tests/history")
    public java.util.List<QaTriageService.TestResult> testHistory(@RequestParam String project, @RequestParam String suite,
            @RequestParam String testClass, @RequestParam(defaultValue = "") String testName, @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 200) throw new RunException(HttpStatus.BAD_REQUEST, "limit must be 1..200");
        if (testClass.isBlank() || testClass.length() > 300 || testName.length() > 300) throw new RunException(HttpStatus.BAD_REQUEST, "Invalid test");
        return triageService.history(project, suite, testClass, testName, limit);
    }

    /** What a pull request's head commit changes, which modules that affects and which test classes are in scope. */
    @GetMapping("/pull-requests/{id}/impact")
    public com.enterprise.agentharness.qa.impact.ChangeImpactService.Result impact(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
        return impacts.forPullRequestRun(id);
    }

    /** Release gate verdict for a commit (or a branch/tag, resolved to its current commit), from recorded evidence only. */
    @GetMapping("/projects/{key}/gate")
    public QualityService.Gate gate(@org.springframework.web.bind.annotation.PathVariable String key, @RequestParam String ref) {
        var project = registry.project(key).orElseThrow(() -> new RunException(HttpStatus.NOT_FOUND, "Project not found: " + key));
        String commit;
        try { commit = git.resolve(project, ref); }
        catch (IllegalArgumentException ex) { throw new RunException(HttpStatus.BAD_REQUEST, ex.getMessage()); }
        return quality.gate(key, commit);
    }

    /** The commit's QA report in Markdown (gate, suites, failures, pull request selection and impact). */
    @GetMapping("/projects/{key}/report")
    public QaReportService.Report report(@org.springframework.web.bind.annotation.PathVariable String key, @RequestParam String ref) {
        var project = registry.project(key).orElseThrow(() -> new RunException(HttpStatus.NOT_FOUND, "Project not found: " + key));
        String commit;
        try { commit = git.resolve(project, ref); }
        catch (IllegalArgumentException ex) { throw new RunException(HttpStatus.BAD_REQUEST, ex.getMessage()); }
        return reports.report(key, ref, commit);
    }

    /** Test-level difference between two commits (refs are resolved), per suite with a result at {@code head}. */
    @GetMapping("/projects/{key}/compare")
    public List<QaComparison.Diff> compare(@org.springframework.web.bind.annotation.PathVariable String key, @RequestParam String base,
            @RequestParam String head, @RequestParam(required = false) String suite) {
        var project = registry.project(key).orElseThrow(() -> new RunException(HttpStatus.NOT_FOUND, "Project not found: " + key));
        if (suite != null && !suite.matches("[a-z0-9][a-z0-9-]{1,59}")) throw new RunException(HttpStatus.BAD_REQUEST, "invalid suite key");
        try { return comparison.compare(key, git.resolve(project, base), git.resolve(project, head), suite); }
        catch (IllegalArgumentException ex) { throw new RunException(HttpStatus.BAD_REQUEST, ex.getMessage()); }
    }

    @GetMapping("/projects/{key}/dashboard")
    public QualityService.Dashboard dashboard(@org.springframework.web.bind.annotation.PathVariable String key, @RequestParam(defaultValue = "14") int days) {
        if (days < 1 || days > 90) throw new RunException(HttpStatus.BAD_REQUEST, "days must be 1..90");
        registry.project(key).orElseThrow(() -> new RunException(HttpStatus.NOT_FOUND, "Project not found: " + key));
        return quality.dashboard(key, days);
    }

    @GetMapping("/github-polls")
    public List<PullRequestTriggers.Poll> polls() { return pullRequests.polls(); }
}
