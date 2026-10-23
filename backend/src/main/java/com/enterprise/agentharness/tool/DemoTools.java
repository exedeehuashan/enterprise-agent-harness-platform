package com.enterprise.agentharness.tool;

import com.enterprise.agentharness.run.DemoTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The two P2/P3 demo tools as executors: the same pure calculation, with and without an approval gate. */
@Configuration
public class DemoTools {
    @Bean Analyze demoAnalyze(DemoTool tool) { return new Analyze(tool); }
    @Bean Review demoReview(DemoTool tool) { return new Review(tool); }

    public record Analyze(DemoTool tool) implements ToolExecutor {
        public String name() { return "DEMO_ANALYZE"; }
        public int version() { return 1; }
        public String target() { return "local-demo"; }
        public boolean requiresApproval() { return false; }
        public boolean retryable() { return true; }
        public ToolResult execute(ToolContext context) { return ToolResult.success(tool.execute(context.run())); }
    }

    public record Review(DemoTool tool) implements ToolExecutor {
        public String name() { return "DEMO_REVIEW"; }
        public int version() { return 1; }
        public String target() { return "local-demo"; }
        public boolean requiresApproval() { return true; }
        public boolean retryable() { return false; }
        public ToolResult execute(ToolContext context) { return ToolResult.success(tool.execute(context.run())); }
    }
}
