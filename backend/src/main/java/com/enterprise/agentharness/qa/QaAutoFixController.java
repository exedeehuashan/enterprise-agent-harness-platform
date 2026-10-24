package com.enterprise.agentharness.qa;

import com.enterprise.agentharness.run.RunException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Auto-fix of failed QA runs by the code agent (P43). */
@RestController
@RequestMapping("/api/v1/qa")
public class QaAutoFixController {
    private final QaAutoFixes fixes;
    public QaAutoFixController(QaAutoFixes fixes) { this.fixes = fixes; }

    @GetMapping("/auto-fixes")
    public List<QaAutoFixes.AutoFix> list(@RequestParam(required = false) String project) { return fixes.list(project); }

    @GetMapping("/auto-fixes/{id}")
    public QaAutoFixes.AutoFix get(@PathVariable UUID id) { return fixes.get(id); }

    /** Hands this failed run to the code agent; the code run waits for an approver. */
    @PostMapping("/runs/{runId}/auto-fix")
    public QaAutoFixes.AutoFix request(@PathVariable UUID runId) { return fixes.request(runId); }

    @GetMapping("/projects/{key}/auto-fix")
    public Map<String, Boolean> settings(@PathVariable String key) { return Map.of("enabled", fixes.autoEnabled(key)); }

    @PutMapping("/admin/projects/{key}/auto-fix")
    public Map<String, Boolean> setSettings(@PathVariable String key, @RequestBody JsonNode body) {
        if (body == null || body.size() != 1 || !body.path("enabled").isBoolean()) throw new RunException(HttpStatus.BAD_REQUEST, "Body must be {\"enabled\": true|false}");
        return Map.of("enabled", fixes.setAuto(key, body.get("enabled").booleanValue()));
    }
}
