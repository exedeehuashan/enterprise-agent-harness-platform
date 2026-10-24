package com.enterprise.agentharness.qa;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/qa")
public class QaQuarantineController {
    private final QaQuarantines quarantines;
    private final QaQuarantineSuggestions suggestions;
    public QaQuarantineController(QaQuarantines quarantines, QaQuarantineSuggestions suggestions) { this.quarantines = quarantines; this.suggestions = suggestions; }

    /** Flaky-test quarantine suggestions (P40), pending ones first. */
    @GetMapping("/quarantine-suggestions")
    public List<QaQuarantineSuggestions.Suggestion> suggestions(@RequestParam(required = false) String project, @RequestParam(required = false) String status) {
        if (status != null && !Set.of("PENDING", "ACCEPTED", "DISMISSED", "RESOLVED").contains(status)) throw new com.enterprise.agentharness.run.RunException(HttpStatus.BAD_REQUEST, "Invalid status");
        return suggestions.list(project, status);
    }

    @PostMapping("/admin/quarantine-suggestions/{id}/accept")
    public QaQuarantineSuggestions.Suggestion accept(@PathVariable UUID id, @RequestBody(required = false) JsonNode body) { return suggestions.accept(id, body); }

    @PostMapping("/admin/quarantine-suggestions/{id}/dismiss")
    public QaQuarantineSuggestions.Suggestion dismiss(@PathVariable UUID id, @RequestBody(required = false) JsonNode body) { return suggestions.dismiss(id, body); }

    @GetMapping("/quarantines")
    public List<QaQuarantines.Quarantine> list(@RequestParam(required = false) String project, @RequestParam(defaultValue = "false") boolean all) {
        return quarantines.list(project, all);
    }

    @PostMapping("/admin/quarantines")
    public ResponseEntity<QaQuarantines.Quarantine> create(@RequestBody JsonNode body) { return ResponseEntity.status(HttpStatus.CREATED).body(quarantines.create(body)); }

    @PostMapping("/admin/quarantines/{id}/release")
    public QaQuarantines.Quarantine release(@PathVariable UUID id) { return quarantines.release(id); }
}
