package com.enterprise.agentharness.qa.impact;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Modules of one build system in a repository (P45): Gradle projects or npm/pnpm/yarn workspace packages, with their
 * internal dependencies by directory. Like {@link MavenModuleGraph} it only knows links between modules of the same
 * build; external dependencies are ignored.
 *
 * @param system      "gradle" or "npm"
 * @param modules     module directory ("" for the root) to module
 * @param wholeBuild  root-level files that change the whole build of this system (build scripts, lockfiles, …)
 */
public record BuildGraph(String system, Map<String, Module> modules, Set<String> wholeBuild) {
    /** {@code id} is what a selective run names: the Gradle project path (":core") or the package directory. */
    public record Module(String dir, String id, String name, Set<String> dependsOnDirs) {}

    public boolean isEmpty() { return modules.isEmpty(); }

    public List<Module> sorted() { return modules.values().stream().sorted(Comparator.comparing(Module::dir)).toList(); }

    /** The deepest module directory containing {@code path} (the root module only for a root project with sources). */
    public String moduleOf(String path) {
        String best = null;
        for (String dir : modules.keySet()) {
            if (dir.isEmpty()) continue;
            if (path.startsWith(dir + "/") && (best == null || dir.length() > best.length())) best = dir;
        }
        if (best == null && modules.containsKey("") && path.startsWith("src/")) best = "";
        return best;
    }

    /** Whether {@code path} is one of this system's build-wide files. */
    public boolean changesWholeBuild(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return wholeBuild.contains(path) || (system.equals("gradle") && (path.startsWith("gradle/") || path.startsWith("buildSrc/")
                || path.startsWith("build-logic/") || name.equals("gradlew") || name.equals("gradlew.bat")));
    }

    /** Directories that depend, directly or transitively, on any of {@code dirs} (excluding {@code dirs}). */
    public Set<String> dependentsOf(Set<String> dirs) {
        Map<String, List<String>> reverse = new HashMap<>();
        for (Module m : modules.values()) for (String dep : m.dependsOnDirs()) reverse.computeIfAbsent(dep, k -> new ArrayList<>()).add(m.dir());
        Set<String> seen = new HashSet<>(dirs), result = new HashSet<>();
        var queue = new ArrayDeque<>(dirs);
        while (!queue.isEmpty()) {
            for (String dependent : reverse.getOrDefault(queue.poll(), List.of())) {
                if (seen.add(dependent)) { result.add(dependent); queue.add(dependent); }
            }
        }
        return result;
    }

    /** Test files of a module: JVM test classes for Gradle, *.test.* / *.spec.* files for workspace packages. */
    public List<String> testsIn(String dir, List<String> files) {
        String prefix = dir.isEmpty() ? "" : dir + "/";
        List<String> tests = new ArrayList<>();
        for (String f : files) {
            if (!f.startsWith(prefix) || f.contains("/node_modules/")) continue;
            String rest = f.substring(prefix.length());
            // A nested module's files belong to that module.
            if (!dir.equals(moduleOf(f)) && !(dir.isEmpty() && moduleOf(f) == null)) continue;
            if (system.equals("gradle")) {
                var m = java.util.regex.Pattern.compile("^src/test/(java|kotlin|groovy)/(.+)\\.(java|kt|groovy)$").matcher(rest);
                if (m.find()) {
                    String cls = m.group(2).replace('/', '.');
                    String simple = cls.substring(cls.lastIndexOf('.') + 1);
                    if (simple.endsWith("Test") || simple.endsWith("Tests") || simple.endsWith("Spec") || simple.endsWith("IT") || simple.startsWith("Test")) tests.add(cls);
                }
            } else if (rest.matches("(?:.+/)?[^/]+\\.(test|spec)\\.[cm]?[jt]sx?$")) {
                tests.add(f);
            }
        }
        return tests;
    }
}
