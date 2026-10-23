package com.enterprise.agentharness.tool;

import com.enterprise.agentharness.run.DemoTool;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ToolRegistry {
    private final Map<String, ToolExecutor> tools = new TreeMap<>();

    @Autowired
    public ToolRegistry(List<ToolExecutor> executors) {
        for (ToolExecutor executor : executors) {
            if (!executor.name().matches("[A-Z][A-Z0-9_]{1,39}") || executor.version() < 1
                    || !executor.target().matches("[a-z0-9][a-z0-9.-]{0,79}")) {
                throw new IllegalStateException("Invalid tool contract: " + executor.name());
            }
            if (tools.putIfAbsent(executor.name(), executor) != null) throw new IllegalStateException("Duplicate tool: " + executor.name());
        }
    }

    /** The two demo tools backed by the given implementation, for tests that drive the worker directly. */
    public static ToolRegistry demo(DemoTool tool) {
        return new ToolRegistry(List.of(new DemoTools.Analyze(tool), new DemoTools.Review(tool)));
    }

    public Optional<ToolExecutor> find(String name) { return Optional.ofNullable(name == null ? null : tools.get(name)); }
    public Set<String> names() { return tools.keySet(); }

    /** Plans carry no arguments yet, so only tools that accept none can appear in them. */
    public boolean acceptsNoArguments(String name) {
        return find(name).map(t -> {
            try { t.normalize(null); return true; } catch (IllegalArgumentException ex) { return false; }
        }).orElse(false);
    }
}
