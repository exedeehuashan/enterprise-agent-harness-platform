package com.enterprise.agentharness.module;

import java.util.List;

/**
 * A domain agent plugged into EAHP (for example 方案 4, AI quality engineering). A module contributes tools (as
 * {@link com.enterprise.agentharness.tool.ToolExecutor} beans) and may run background jobs; it declares them here so
 * operators can see what each domain brings and an approver can switch a whole domain off. The platform itself
 * (tasks, runs, approvals, plans, artifacts, webhooks, tokens) is the "core" module and cannot be disabled.
 */
public interface DomainModule {
    String key();
    String name();
    String description();
    /** Tool names this module contributes; they are disabled and re-enabled with the module. */
    List<String> tools();
    default boolean canDisable() { return true; }
}
