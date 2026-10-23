package com.enterprise.agentharness.qa;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only catalog of projects under test and their suites, so clients can offer valid choices. */
@RestController
@RequestMapping("/api/v1/qa")
public class QaController {
    private final QaRegistry registry;
    public QaController(QaRegistry registry) { this.registry = registry; }

    @GetMapping("/projects")
    public List<QaRegistry.Project> projects() { return registry.projects(); }
}
