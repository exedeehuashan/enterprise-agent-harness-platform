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

    public static Decision decide(ChangeImpactAnalyzer.Impact impact, String suitesFile) { return decide(impact, suitesFile, "maven"); }

    /**
     * For a selective suite of one build system (maven, gradle or npm): the modules of that build the change affects.
     * The returned identifiers are what the runner passes on: Maven module directories, Gradle project paths (":core")
     * or workspace package directories. Changes in other builds' modules do not concern the suite.
     */
    public static Decision decide(ChangeImpactAnalyzer.Impact impact, String suitesFile, String system) {
        if (impact == null) return Decision.full("IMPACT_UNAVAILABLE");
        if (!impact.has(system)) return Decision.full(switch (system) { case "gradle" -> "NOT_GRADLE"; case "npm" -> "NOT_WORKSPACES"; default -> "NOT_MAVEN"; });
        if (impact.truncated()) return Decision.full("TOO_MANY_CHANGES");
        if (impact.wholeBuildOf(system)) return Decision.full("BUILD_CHANGED");
        for (String path : impact.filesOutsideModules()) {
            if (affectsBuild(path, suitesFile, system)) return Decision.full("BUILD_CHANGED");
        }
        // The listed outside files are capped; if some were cut off, they cannot be checked.
        if (impact.filesOutsideModules().size() >= ChangeImpactAnalyzer.MAX_LISTED) return Decision.full("TOO_MANY_CHANGES");
        var own = impact.modules().stream().filter(m -> m.system().equals(system)).toList();
        if (own.stream().anyMatch(m -> m.dir().isEmpty())) return Decision.full("ROOT_MODULE");
        if (own.isEmpty()) return new Decision(Mode.NOT_AFFECTED, List.of(), "NO_MODULE_CHANGED");
        if (own.size() > MAX_MODULES) return Decision.full("TOO_MANY_MODULES");
        var ids = own.stream().map(m -> system.equals("gradle") ? m.coordinates() : m.dir()).toList();
        return new Decision(Mode.MODULES, ids, "AFFECTED_MODULES");
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
    static boolean affectsBuild(String path, String suitesFile) { return affectsBuild(path, suitesFile, "maven"); }

    /** Files outside the modules that still change how the given build works or what the suite runs. */
    static boolean affectsBuild(String path, String suitesFile, String system) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (path.equals(suitesFile) || path.startsWith(".eahp/")) return true;
        return switch (system) {
            case "gradle" -> name.endsWith(".gradle") || name.endsWith(".gradle.kts") || name.equals("gradle.properties") || path.startsWith("gradle/")
                    || path.startsWith("buildSrc/") || path.startsWith("build-logic/") || name.startsWith("gradlew");
            case "npm" -> name.equals("package.json") || name.equals("package-lock.json") || name.equals("pnpm-lock.yaml") || name.equals("yarn.lock")
                    || name.equals("pnpm-workspace.yaml") || name.equals(".npmrc") || name.startsWith("tsconfig");
            default -> name.equals("pom.xml") || path.startsWith(".mvn/") || name.equals("mvnw") || name.equals("mvnw.cmd");
        };
    }
}
