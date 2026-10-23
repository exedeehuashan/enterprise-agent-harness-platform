package com.enterprise.agentharness.ai;

import com.enterprise.agentharness.qa.QaAiAnalysis;
import com.enterprise.agentharness.qa.QaAiTestGeneration;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** AI status for clients and the QA failure-analysis endpoints. Writing (asking the model) is an OPERATOR action. */
@RestController
@RequestMapping("/api/v1")
public class AiController {
    private final AiService ai;
    private final QaAiAnalysis analysis;
    private final QaAiTestGeneration generation;
    public AiController(AiService ai, QaAiAnalysis analysis, QaAiTestGeneration generation) { this.ai = ai; this.analysis = analysis; this.generation = generation; }

    /** Drafts an API test collection from an OpenAPI document in the project's repository. */
    @PostMapping("/qa/projects/{project}/ai/api-tests")
    public QaAiTestGeneration.Draft generate(@PathVariable String project, @RequestBody JsonNode body) { return generation.generate(project, body); }
    @GetMapping("/qa/projects/{project}/ai/api-tests") public List<QaAiTestGeneration.Draft> drafts(@PathVariable String project) { return generation.list(project); }
    @GetMapping("/qa/projects/{project}/ai/api-tests/{id}") public QaAiTestGeneration.Draft draft(@PathVariable String project, @PathVariable UUID id) { return generation.get(project, id); }

    /** Runs a valid draft against an environment: {@code {"environment": "uat"}}. */
    @PostMapping("/qa/projects/{project}/ai/api-tests/{id}/run")
    public Map<String, UUID> runDraft(@PathVariable String project, @PathVariable UUID id, @RequestBody JsonNode body) {
        if (body == null || !body.path("environment").isTextual() || body.size() != 1) {
            throw new com.enterprise.agentharness.run.RunException(org.springframework.http.HttpStatus.BAD_REQUEST, "Body must be {\"environment\": \"<key>\"}");
        }
        return Map.of("runId", generation.run(project, id, body.get("environment").asText()));
    }

    @GetMapping("/ai/status") public AiService.Status status() { return ai.status(); }
    @GetMapping("/qa/runs/{runId}/ai-analysis") public QaAiAnalysis.Analysis latest(@PathVariable UUID runId) { return analysis.latest(runId); }
    @PostMapping("/qa/runs/{runId}/ai-analysis") public QaAiAnalysis.Analysis analyze(@PathVariable UUID runId) { return analysis.analyze(runId); }
}
