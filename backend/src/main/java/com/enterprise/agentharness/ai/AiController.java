package com.enterprise.agentharness.ai;

import com.enterprise.agentharness.qa.QaAiAnalysis;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** AI status for clients and the QA failure-analysis endpoints. Writing (asking the model) is an OPERATOR action. */
@RestController
@RequestMapping("/api/v1")
public class AiController {
    private final AiService ai;
    private final QaAiAnalysis analysis;
    public AiController(AiService ai, QaAiAnalysis analysis) { this.ai = ai; this.analysis = analysis; }

    @GetMapping("/ai/status") public AiService.Status status() { return ai.status(); }
    @GetMapping("/qa/runs/{runId}/ai-analysis") public QaAiAnalysis.Analysis latest(@PathVariable UUID runId) { return analysis.latest(runId); }
    @PostMapping("/qa/runs/{runId}/ai-analysis") public QaAiAnalysis.Analysis analyze(@PathVariable UUID runId) { return analysis.analyze(runId); }
}
