package com.enterprise.agentharness.ops;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Storage report and on-demand retention for approvers. */
@RestController
@RequestMapping("/api/v1/admin")
public class OpsController {
    private final RetentionService retention;
    public OpsController(RetentionService retention) { this.retention = retention; }

    @GetMapping("/storage") public RetentionService.Report storage() { return retention.report(); }
    @PostMapping("/retention/run") public RetentionService.Result run() { return retention.runNow(); }
}
