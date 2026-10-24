package com.enterprise.agentharness.qa;

import java.util.List;

/** Test access to package-private runner helpers from other packages. */
public final class QaSuiteToolsAccess {
    private QaSuiteToolsAccess() {}
    public static List<String> selectiveCommand(SuiteDefinition suite, List<String> selected) { return QaSuiteTools.SuiteTool.selectiveCommand(suite, selected); }
}
