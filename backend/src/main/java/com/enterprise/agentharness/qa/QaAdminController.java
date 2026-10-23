package com.enterprise.agentharness.qa;

import com.enterprise.agentharness.run.RunException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Registry administration for approvers: projects, policy, registry suites, PR triggers and release gates. */
@RestController
@RequestMapping("/api/v1/qa/admin")
public class QaAdminController {
    private final QaAdminService admin;
    public QaAdminController(QaAdminService admin) { this.admin = admin; }

    @PostMapping("/projects")
    public ResponseEntity<QaAdminService.ProjectAdmin> create(@RequestBody JsonNode body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(admin.create(body));
    }
    @GetMapping("/projects/{key}") public QaAdminService.ProjectAdmin get(@PathVariable String key) { return admin.get(key); }
    @PatchMapping("/projects/{key}") public QaAdminService.ProjectAdmin update(@PathVariable String key, @RequestBody JsonNode body) { return admin.update(key, body); }
    @PutMapping("/projects/{key}/suites/{suite}")
    public QaAdminService.ProjectAdmin suite(@PathVariable String key, @PathVariable String suite, @RequestBody JsonNode body) { return admin.putSuite(key, suite, body); }
    @PutMapping("/projects/{key}/triggers/{suite}")
    public QaAdminService.ProjectAdmin trigger(@PathVariable String key, @PathVariable String suite, @RequestBody JsonNode body) { return admin.putTrigger(key, suite, body); }
    @PutMapping("/projects/{key}/gate") public QaAdminService.ProjectAdmin gate(@PathVariable String key, @RequestBody JsonNode body) { return admin.putGate(key, body); }
    @DeleteMapping("/projects/{key}/gate") public QaAdminService.ProjectAdmin deleteGate(@PathVariable String key) { return admin.deleteGate(key); }

    @GetMapping("/events")
    public List<QaAdminService.Event> events(@RequestParam(required = false) String project, @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 200) throw new RunException(HttpStatus.BAD_REQUEST, "limit must be 1..200");
        return admin.events(project, limit);
    }
}
