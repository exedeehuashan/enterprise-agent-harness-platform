package com.enterprise.agentharness.code;

import com.enterprise.agentharness.module.DomainModule;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The code agent: Claude Code working on a registered repository in an isolated container. A generic EAHP capability;
 * domains such as 方案 4 (fixing failing tests) or 方案 1 (implementing changes) decide what to ask it.
 */
@Component
public class CodeModule implements DomainModule {
    public String key() { return "code"; }
    public String name() { return "Code agent (Claude Code)"; }
    public String description() { return "Runs Claude Code on a registered repository at an exact commit, records the diff, and can open a draft pull request."; }
    public List<String> tools() { return List.of("CODE_AGENT_RUN"); }
}
