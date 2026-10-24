package com.enterprise.agentharness.qa;

import com.enterprise.agentharness.governance.AuditService;
import com.enterprise.agentharness.run.RunException;
import com.enterprise.agentharness.run.RunService;
import com.enterprise.agentharness.run.StartRunRequest;
import com.enterprise.agentharness.task.AgentTaskService;
import com.enterprise.agentharness.task.CreateTaskRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Per-commit environments (P36): the templates at a ref, the temporary environments, starting runs and stopping environments. */
@RestController
@RequestMapping("/api/v1/qa")
public class QaEnvironmentController {
    private final QaSuiteResolver resolver;
    private final GitMirror git;
    private final EphemeralEnvironments envs;
    private final AgentTaskService tasks;
    private final RunService runs;
    private final JdbcTemplate jdbc;

    public QaEnvironmentController(QaSuiteResolver resolver, GitMirror git, EphemeralEnvironments envs, AgentTaskService tasks, RunService runs, JdbcTemplate jdbc) {
        this.resolver = resolver; this.git = git; this.envs = envs; this.tasks = tasks; this.runs = runs; this.jdbc = jdbc;
    }

    public record Templates(String project, String ref, String commit, String file, String error, List<EnvironmentTemplates.Template> templates) {}

    /** The environment templates at a ref: the file is "found", "absent", "disabled" (project policy) or "invalid". */
    @GetMapping("/projects/{key}/environment-templates")
    public Templates templates(@PathVariable String key, @RequestParam(required = false) String ref) {
        try {
            var project = resolver.enabledProject(key);
            String effective = ref == null ? project.defaultRef() : ref;
            String commit = git.resolve(project, effective);
            if (!project.repositorySuitesEnabled()) return new Templates(key, effective, commit, "disabled", null, List.of());
            try {
                var text = git.readFile(project, commit, EnvironmentTemplates.FILE, EnvironmentTemplates.MAX_BYTES);
                if (text.isEmpty()) return new Templates(key, effective, commit, "absent", null, List.of());
                return new Templates(key, effective, commit, "found", null, EnvironmentTemplates.parse(text.get()));
            } catch (java.io.IOException ex) {
                return new Templates(key, effective, commit, "invalid", "could not read " + EnvironmentTemplates.FILE, List.of());
            } catch (IllegalArgumentException ex) {
                return new Templates(key, effective, commit, "invalid", ex.getMessage(), List.of());
            }
        } catch (IllegalArgumentException ex) {
            throw new RunException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/temporary-environments")
    public List<EphemeralEnvironments.Live> list(@RequestParam(required = false) String project, @RequestParam(defaultValue = "false") boolean all) {
        if (project != null && !project.matches("[a-z0-9][a-z0-9-]{1,59}")) throw new RunException(HttpStatus.BAD_REQUEST, "Invalid project");
        return envs.list(project, all);
    }

    /**
     * {environment, ref?, mode: "test" | "start"} creates a task and a run that waits for approval: "test" runs the
     * template's API tests on a fresh environment and removes it, "start" leaves it running for its TTL.
     */
    @PostMapping("/projects/{key}/environment-runs")
    public Map<String, UUID> start(@PathVariable String key, @RequestBody JsonNode body) {
        if (body == null || !body.isObject()) throw bad("A JSON object is required");
        for (var it = body.fieldNames(); it.hasNext(); ) if (!Set.of("environment", "ref", "mode").contains(it.next())) throw bad("Unknown field");
        String mode = body.path("mode").asText("test");
        if (!mode.equals("test") && !mode.equals("start")) throw bad("mode must be test or start");
        if (!body.path("environment").isTextual()) throw bad("environment is required");
        var args = JsonNodeFactory.instance.objectNode().put("project", key).put("environment", body.get("environment").textValue());
        if (body.has("ref")) {
            if (!body.get("ref").isTextual()) throw bad("ref must be text");
            args.put("ref", body.get("ref").textValue());
        }
        String tool = mode.equals("test") ? "QA_RUN_ENVIRONMENT_TESTS" : "QA_START_ENVIRONMENT";
        String title = PullRequestTriggers.clean((mode.equals("test") ? "Environment tests: " : "Temporary environment: ") + body.get("environment").textValue()
                + " on " + key + "@" + args.path("ref").asText("default branch"), 255);
        var task = tasks.create(new CreateTaskRequest(title, "Started from the Test projects page."));
        var run = runs.start(task.id(), "environment-" + UUID.randomUUID(), new StartRunRequest(tool, 3600, args), AuditService.actor());
        return Map.of("taskId", task.id(), "runId", run.id());
    }

    @PostMapping("/temporary-environments/{id}/stop")
    public Map<String, Boolean> stop(@PathVariable UUID id) {
        var live = envs.find(id).orElseThrow(() -> new RunException(HttpStatus.NOT_FOUND, "Environment not found: " + id));
        boolean stopped = envs.stop(id);
        if (stopped) {
            jdbc.update("INSERT INTO qa_admin_events(actor,action,project_key,subject) VALUES (?,?,?,?)", AuditService.actor(), "TEMPORARY_ENVIRONMENT_STOPPED",
                    live.projectKey(), live.environmentKey());
        }
        return Map.of("stopped", stopped);
    }

    private static RunException bad(String m) { return new RunException(HttpStatus.BAD_REQUEST, m); }
}
