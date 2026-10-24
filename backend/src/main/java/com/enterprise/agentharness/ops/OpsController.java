package com.enterprise.agentharness.ops;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Storage report, on-demand retention and backup-restore drills for approvers. */
@RestController
@RequestMapping("/api/v1/admin")
public class OpsController {
    private final RetentionService retention;
    private final BackupDrills drills;
    public OpsController(RetentionService retention, BackupDrills drills) { this.retention = retention; this.drills = drills; }

    @GetMapping("/storage") public RetentionService.Report storage() { return retention.report(); }
    @PostMapping("/retention/run") public RetentionService.Result run() { return retention.runNow(); }
    @GetMapping("/backup-drills") public java.util.List<BackupDrills.Drill> drills(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int limit) { return drills.list(limit); }
    @GetMapping("/backup-drills/{id}") public BackupDrills.Drill drill(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id) { return drills.get(id); }
    /** Starts a drill now; it runs in the background (poll the drill for its result). */
    @PostMapping("/backup-drills")
    public org.springframework.http.ResponseEntity<BackupDrills.Drill> drillNow() { return org.springframework.http.ResponseEntity.accepted().body(drills.runNow()); }
}
