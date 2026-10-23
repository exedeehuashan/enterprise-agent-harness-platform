package com.enterprise.agentharness.qa.impact;

import java.util.List;

/**
 * Decides how much of a suite a pull request run needs, from its change impact. Anything uncertain runs the
 * whole suite: selection may only make a run smaller when the impact is complete and every changed file that can
 * influence the build is attributed to a module.
 */
public final class ImpactSelection {
    public enum Mode { FULL, MODULES, NOT_AFFECTED }
    public record Decision(Mode mode, List<String> modules, String reason) {
        public static Decision full(String reason) { return new Decision(Mode.FULL, List.of(), reason); }
    }
    static final int MAX_MODULES = 50;

    private ImpactSelection() {}

    public static Decision decide(ChangeImpactAnalyzer.Impact impact, String suitesFile) {
        if (impact == null) return Decision.full("IMPACT_UNAVAILABLE");
        if (!impact.buildSystem().equals("maven")) return Decision.full("NOT_MAVEN");
        if (impact.truncated()) return Decision.full("TOO_MANY_CHANGES");
        if (impact.wholeBuild()) return Decision.full("BUILD_CHANGED");
        for (String path : impact.filesOutsideModules()) {
            if (affectsBuild(path, suitesFile)) return Decision.full("BUILD_CHANGED");
        }
        // The listed outside files are capped; if some were cut off, they cannot be checked.
        if (impact.filesOutsideModules().size() >= ChangeImpactAnalyzer.MAX_LISTED) return Decision.full("TOO_MANY_CHANGES");
        var modules = impact.modules().stream().map(ChangeImpactAnalyzer.AffectedModule::dir).toList();
        if (modules.contains("")) return Decision.full("ROOT_MODULE");
        if (modules.isEmpty()) return new Decision(Mode.NOT_AFFECTED, List.of(), "NO_MODULE_CHANGED");
        if (modules.size() > MAX_MODULES) return Decision.full("TOO_MANY_MODULES");
        return new Decision(Mode.MODULES, modules, "AFFECTED_MODULES");
    }

    /**
     * For a {@code paths} suite: run in full when a changed file is under one of {@code paths}, skip otherwise. Works for
     * any build system, since only the changed file list is used. A change to the suites file always runs.
     */
    public static Decision decidePaths(ChangeImpactAnalyzer.Impact impact, String suitesFile, List<String> paths) {
        if (impact == null) return Decision.full("IMPACT_UNAVAILABLE");
        // Only part of the list may be stored; the unlisted files could match.
        if (impact.truncated() || impact.changedFileCount() > impact.changedFiles().size()) return Decision.full("TOO_MANY_CHANGES");
        for (String file : impact.changedFiles()) {
            if (file.equals(suitesFile) || file.startsWith(".eahp/")) return Decision.full("BUILD_CHANGED");
        }
        for (String file : impact.changedFiles()) {
            if (paths.stream().anyMatch(p -> under(file, p))) return Decision.full("PATHS_CHANGED");
        }
        return new Decision(Mode.NOT_AFFECTED, List.of(), "NO_PATH_CHANGED");
    }

    static boolean under(String file, String path) {
        String dir = path.endsWith("/") ? path : path + "/";
        return file.equals(path) || file.startsWith(dir);
    }

    /** Files outside the modules that still change how Maven builds or what the suite runs. */
    static boolean affectsBuild(String path, String suitesFile) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.equals("pom.xml") || path.startsWith(".mvn/") || name.equals("mvnw") || name.equals("mvnw.cmd")
                || path.equals(suitesFile) || path.startsWith(".eahp/");
    }
}
