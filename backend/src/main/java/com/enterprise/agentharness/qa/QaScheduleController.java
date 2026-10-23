package com.enterprise.agentharness.qa;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Schedules are readable by viewers; creating, enabling and deleting them is an approver action. */
@RestController
@RequestMapping("/api/v1/qa")
public class QaScheduleController {
    private final QaSchedules schedules;
    public QaScheduleController(QaSchedules schedules) { this.schedules = schedules; }

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
