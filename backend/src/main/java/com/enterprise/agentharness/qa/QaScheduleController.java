package com.enterprise.agentharness.qa;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Schedules and environments are readable by viewers; changing them is an approver action. */
@RestController
@RequestMapping("/api/v1/qa")
public class QaScheduleController {
    private final QaSchedules schedules;
    private final QaEnvironments environments;
    private final QaApiTestTool apiTests;
    private final GitHubWriteBack writeBack;
    public QaScheduleController(QaSchedules schedules, QaEnvironments environments, QaApiTestTool apiTests, GitHubWriteBack writeBack) {
        this.schedules = schedules; this.environments = environments; this.apiTests = apiTests; this.writeBack = writeBack;
    }

    public record WriteBackView(GitHubWriteBack.Settings settings, List<GitHubWriteBack.OutboxEntry> recent) {}

    /** Whether QA results are written back to this project's pull requests, and the latest GitHub calls. */
    @GetMapping("/projects/{project}/github-write-back")
    public WriteBackView writeBack(@PathVariable String project) { return new WriteBackView(writeBack.settings(project), writeBack.outbox(project, 20)); }

    @PutMapping("/admin/projects/{project}/github-write-back")
    public GitHubWriteBack.Settings setWriteBack(@PathVariable String project, @RequestBody JsonNode body) {
        if (body == null || !body.path("enabled").isBoolean() || body.size() != 1) throw new com.enterprise.agentharness.run.RunException(HttpStatus.BAD_REQUEST, "Body must be {\"enabled\": true|false}");
        return writeBack.setEnabled(project, body.get("enabled").booleanValue());
    }

    /** API test collections declared in the repository at a ref, plus the environments they can run against. */
    @GetMapping("/projects/{project}/api-tests")
    public QaApiTestTool.Catalog apiTests(@PathVariable String project, @RequestParam(required = false) String ref) {
        try { return apiTests.catalog(project, ref); }
        catch (IllegalArgumentException ex) { throw new com.enterprise.agentharness.run.RunException(HttpStatus.BAD_REQUEST, ex.getMessage()); }
    }

    @GetMapping("/environments")
    public List<QaEnvironments.Environment> environments(@RequestParam(required = false) String project) { return environments.list(project); }

    @PutMapping("/admin/projects/{project}/environments/{key}")
    public QaEnvironments.Environment putEnvironment(@PathVariable String project, @PathVariable String key, @RequestBody JsonNode body) {
        return environments.put(project, key, body);
    }

    @DeleteMapping("/admin/projects/{project}/environments/{key}")
    public ResponseEntity<Void> deleteEnvironment(@PathVariable String project, @PathVariable String key) {
        environments.delete(project, key);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/schedules")
    public List<QaSchedules.Schedule> list(@RequestParam(required = false) String project) { return schedules.list(project); }

    @PostMapping("/admin/schedules")
    public ResponseEntity<QaSchedules.Schedule> create(@RequestBody JsonNode body) { return ResponseEntity.status(HttpStatus.CREATED).body(schedules.create(body)); }

    @PutMapping("/admin/schedules/{id}/enabled")
    public QaSchedules.Schedule enabled(@PathVariable UUID id, @RequestBody JsonNode body) {
        if (body == null || !body.path("enabled").isBoolean() || body.size() != 1) throw new com.enterprise.agentharness.run.RunException(HttpStatus.BAD_REQUEST, "Body must be {\"enabled\": true|false}");
        return schedules.setEnabled(id, body.get("enabled").booleanValue());
    }

    @DeleteMapping("/admin/schedules/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) { schedules.delete(id); return ResponseEntity.noContent().build(); }
}
