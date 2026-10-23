package com.enterprise.agentharness.qa;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/qa")
public class QaQuarantineController {
    private final QaQuarantines quarantines;
    public QaQuarantineController(QaQuarantines quarantines) { this.quarantines = quarantines; }

    @GetMapping("/quarantines")
    public List<QaQuarantines.Quarantine> list(@RequestParam(required = false) String project, @RequestParam(defaultValue = "false") boolean all) {
        return quarantines.list(project, all);
    }

    @PostMapping("/admin/quarantines")
    public ResponseEntity<QaQuarantines.Quarantine> create(@RequestBody JsonNode body) { return ResponseEntity.status(HttpStatus.CREATED).body(quarantines.create(body)); }

    @PostMapping("/admin/quarantines/{id}/release")
    public QaQuarantines.Quarantine release(@PathVariable UUID id) { return quarantines.release(id); }
}
