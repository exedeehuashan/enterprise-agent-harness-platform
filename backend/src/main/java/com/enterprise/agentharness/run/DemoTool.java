package com.enterprise.agentharness.run;

import org.springframework.stereotype.Component;

/** Pure local calculation: no network, shell, file writes or model calls. */
@Component
public class DemoTool {
    public String execute(RunResponse run) {
        return "{\"toolVersion\":1,\"titleLength\":" + run.snapshotTitle().length()
                + ",\"descriptionLength\":" + (run.snapshotDescription() == null ? 0 : run.snapshotDescription().length()) + "}";
    }
}
