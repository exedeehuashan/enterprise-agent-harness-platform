package com.enterprise.agentharness.module;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ModuleController {
    private final ModuleRegistry modules;
    public ModuleController(ModuleRegistry modules) { this.modules = modules; }

    @GetMapping("/modules") public List<ModuleRegistry.Module> list() { return modules.list(); }

    @PutMapping("/admin/modules/{key}/enabled")
    public ModuleRegistry.Module enabled(@PathVariable String key, @RequestBody JsonNode body) {
        if (body == null || !body.path("enabled").isBoolean() || body.size() != 1) throw new com.enterprise.agentharness.run.RunException(HttpStatus.BAD_REQUEST, "Body must be {\"enabled\": true|false}");
        return modules.setEnabled(key, body.get("enabled").booleanValue());
    }
}
