package com.enterprise.agentharness.ai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** The AI evaluation set and its runs (P44); under /api/v1/admin, so approvers only. */
@RestController
@RequestMapping("/api/v1/admin/ai/evals")
public class AiEvalController {
    private final AiEvals evals;
    public AiEvalController(AiEvals evals) { this.evals = evals; }

    @GetMapping("/cases") public JsonNode cases() { return evals.cases(); }
    @GetMapping public List<AiEvals.EvalRun> list() { return evals.list(); }
    @GetMapping("/{id}") public AiEvals.EvalRun get(@PathVariable UUID id) { return evals.get(id); }

    /** {@code {"cases": ["FA-01", ...]}} runs only those; an empty body runs the whole set. */
    @PostMapping
    public ResponseEntity<AiEvals.EvalRun> start(@RequestBody(required = false) JsonNode body) {
        List<String> only = new ArrayList<>();
        if (body != null && body.has("cases")) body.get("cases").forEach(c -> only.add(c.asText()));
        return ResponseEntity.accepted().body(evals.start(only));
    }
}
