package com.enterprise.agentharness.module;

import com.enterprise.agentharness.governance.AuditService;
import com.enterprise.agentharness.run.RunException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The domain modules plugged into this deployment. Disabling a module disables its tools (so no new runs of them
 * start) and stops its background jobs; enabling it re-enables them. Modules never share a tool.
 */
@Service
public class ModuleRegistry {
    private final List<DomainModule> modules;
    private final JdbcTemplate jdbc;

    public ModuleRegistry(List<DomainModule> modules, JdbcTemplate jdbc) {
        var keys = new HashSet<String>();
        var tools = new HashSet<String>();
        for (var m : modules) {
            if (!keys.add(m.key())) throw new IllegalStateException("Duplicate domain module " + m.key());
            for (String t : m.tools()) if (!tools.add(t)) throw new IllegalStateException("Tool " + t + " belongs to more than one module");
        }
        this.modules = modules.stream().sorted((a, b) -> a.key().equals("core") ? -1 : b.key().equals("core") ? 1 : a.key().compareTo(b.key())).toList();
        this.jdbc = jdbc;
    }

    public record ToolState(String name, boolean enabled) {}
    public record Module(String key, String name, String description, boolean enabled, boolean canDisable, List<ToolState> tools, String changedBy) {}

    public List<Module> list() { return modules.stream().map(this::describe).toList(); }

    public boolean enabled(String key) {
        var rows = jdbc.queryForList("SELECT enabled FROM domain_modules WHERE key=?", Boolean.class, key);
        return rows.isEmpty() || rows.get(0);
    }

    @Transactional
    @PreAuthorize("hasRole('APPROVER')")
    public Module setEnabled(String key, boolean enabled) {
        var module = modules.stream().filter(m -> m.key().equals(key)).findFirst()
                .orElseThrow(() -> new RunException(HttpStatus.NOT_FOUND, "Module not found: " + key));
        if (!module.canDisable() && !enabled) throw new RunException(HttpStatus.BAD_REQUEST, "The " + key + " module cannot be disabled");
        jdbc.update("""
                INSERT INTO domain_modules(key,enabled,changed_by) VALUES (?,?,?)
                ON CONFLICT (key) DO UPDATE SET enabled=EXCLUDED.enabled, changed_by=EXCLUDED.changed_by, changed_at=clock_timestamp()
                """, key, enabled, AuditService.actor());
        for (String tool : module.tools()) jdbc.update("UPDATE tool_definitions SET enabled=? WHERE name=?", enabled, tool);
        return describe(module);
    }

    private Module describe(DomainModule m) {
        Map<String, Boolean> state = new java.util.HashMap<>();
        for (var row : jdbc.queryForList("SELECT name, enabled FROM tool_definitions")) state.put((String) row.get("name"), (Boolean) row.get("enabled"));
        var changed = jdbc.queryForList("SELECT changed_by FROM domain_modules WHERE key=?", String.class, m.key());
        return new Module(m.key(), m.name(), m.description(), enabled(m.key()), m.canDisable(),
                m.tools().stream().map(t -> new ToolState(t, state.getOrDefault(t, false))).toList(), changed.isEmpty() ? null : changed.get(0));
    }
}
