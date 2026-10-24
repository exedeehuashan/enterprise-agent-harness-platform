package com.enterprise.agentharness.qa.impact;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Works out what a change could break in a multi-module build: the modules containing changed files, every module
 * that depends on them, and the tests in those modules. Maven builds, Gradle builds and npm/pnpm/yarn workspaces
 * (P45) are understood, also side by side in one repository; each module says which build it belongs to. It
 * explains scope and risk; whether a run may be narrowed is decided separately and conservatively by
 * {@link ImpactSelection}.
 */
public final class ChangeImpactAnalyzer {
    static final int MAX_CHANGED_FILES = 2000;
    static final int MAX_LISTED = 500;

    /** {@code buildSystem} is maven, gradle or npm (null in impacts recorded before P45: maven); {@code coordinates} names the module in it. */
    public record AffectedModule(String dir, String coordinates, String reason, int changedFiles, int testClasses, String buildSystem) {
        public AffectedModule(String dir, String coordinates, String reason, int changedFiles, int testClasses) { this(dir, coordinates, reason, changedFiles, testClasses, "maven"); }
        public String system() { return buildSystem == null ? "maven" : buildSystem; }
    }
    /** {@code buildSystem} joins the builds found ("maven", "gradle+npm", …); {@code wholeBuildSystems} lists those whose build-wide files changed. */
    public record Impact(String buildSystem, int changedFileCount, List<String> changedFiles, boolean truncated, boolean wholeBuild,
            List<AffectedModule> modules, List<String> testClasses, List<String> filesOutsideModules, List<String> notes, List<String> wholeBuildSystems) {
        public Impact(String buildSystem, int changedFileCount, List<String> changedFiles, boolean truncated, boolean wholeBuild,
                List<AffectedModule> modules, List<String> testClasses, List<String> filesOutsideModules, List<String> notes) {
            this(buildSystem, changedFileCount, changedFiles, truncated, wholeBuild, modules, testClasses, filesOutsideModules, notes,
                    wholeBuild && "maven".equals(buildSystem) ? List.of("maven") : List.of());
        }
        public boolean has(String system) { return buildSystem != null && List.of(buildSystem.split("\\+")).contains(system); }
        public boolean wholeBuildOf(String system) { return wholeBuildSystems == null ? wholeBuild && "maven".equals(system) : wholeBuildSystems.contains(system); }
    }

    private ChangeImpactAnalyzer() {}

    public static Impact analyze(RepositoryView repo, String base, String head) throws IOException {
        List<String> changed = repo.changedFiles(base, head);
        List<String> files = repo.files(head);
        Map<String, String> poms = new LinkedHashMap<>();
        for (String f : files) {
            if ((f.equals("pom.xml") || f.endsWith("/pom.xml")) && poms.size() < 200) repo.read(head, f).ifPresent(c -> poms.put(f, c));
        }
        BuildGraphs.Reader reader = path -> repo.read(head, path);
        List<BuildGraph> others = new ArrayList<>();
        BuildGraph gradle = BuildGraphs.gradle(files, reader), npm = BuildGraphs.workspaces(files, reader);
        if (gradle != null) others.add(gradle);
        if (npm != null) others.add(npm);
        List<String> notes = new ArrayList<>();
        boolean truncated = changed.size() > MAX_CHANGED_FILES;
        if (truncated) changed = changed.subList(0, MAX_CHANGED_FILES);
        if (poms.isEmpty() && others.isEmpty()) {
            notes.add("No Maven, Gradle or workspace build found at this commit; module impact is not available for this build system yet.");
            return new Impact("unknown", changed.size(), limit(changed), truncated, false, List.of(), List.of(), List.of(), notes, List.of());
        }
        List<String> systems = new ArrayList<>();
        List<String> whole = new ArrayList<>();
        List<AffectedModule> modules = new ArrayList<>();
        List<String> tests = new ArrayList<>();
        List<String> outside = new ArrayList<>(changed);
        if (!poms.isEmpty()) {
            systems.add("maven");
            outside = maven(poms, files, changed, modules, tests, notes, whole);
        }
        for (BuildGraph graph : others) {
            systems.add(graph.system());
            outside = other(graph, files, outside, modules, tests, notes, whole);
        }
        String label = systems.size() == 1 && systems.get(0).equals("maven") ? "the Maven modules" : "the build modules";
        if (!outside.isEmpty()) notes.add(outside.size() + " changed file(s) are outside " + label + " (for example frontend, CI or docs); backend suites do not cover them.");
        if (modules.isEmpty() && outside.isEmpty() && whole.isEmpty()) notes.add("No module is affected.");
        return new Impact(String.join("+", systems), changed.size(), limit(changed), truncated, !whole.isEmpty(), modules, limit(tests), limit(outside), notes, List.copyOf(whole));
    }

    /** The Maven part, unchanged from before P45; returns the changed files outside the Maven modules. */
    private static List<String> maven(Map<String, String> poms, List<String> files, List<String> changed, List<AffectedModule> modules, List<String> tests,
            List<String> notes, List<String> whole) {
        var graph = MavenModuleGraph.parse(poms);
        Map<String, Integer> changedPerModule = new LinkedHashMap<>();
        List<String> outside = new ArrayList<>();
        boolean wholeBuild = false;
        // The root counts as a module of its own only for its own sources (a single-module project); other
        // root-level files (frontend, CI, docs) are outside the build.
        boolean rootHasSources = graph.modules().stream().anyMatch(m -> m.dir().isEmpty()) && files.stream().anyMatch(f -> f.startsWith("src/"));
        for (String path : changed) {
            if (path.equals("pom.xml")) { wholeBuild = true; continue; }
            String module = graph.moduleOf(path);
            if (module == null || (module.isEmpty() && !(rootHasSources && path.startsWith("src/")))) outside.add(path);
            else changedPerModule.merge(module, 1, Integer::sum);
        }
        Set<String> direct = changedPerModule.keySet();
        Set<String> affected = new TreeSet<>(direct);
        Set<String> dependents = graph.dependentsOf(direct);
        if (wholeBuild) {
            whole.add("maven");
            graph.modules().stream().map(MavenModuleGraph.Module::dir).filter(d -> !d.isEmpty() || rootHasSources).forEach(affected::add);
            notes.add("The root pom.xml changed, so every module is affected.");
        } else {
            affected.addAll(dependents);
        }
        Map<String, List<String>> testsPerModule = new LinkedHashMap<>();
        for (String f : files) {
            String module = graph.moduleOf(f);
            if (module == null || !affected.contains(module)) continue;
            String prefix = (module.isEmpty() ? "" : module + "/") + "src/test/java/";
            if (f.startsWith(prefix) && f.endsWith(".java") && isTestClassName(f)) {
                testsPerModule.computeIfAbsent(module, k -> new ArrayList<>())
                        .add(f.substring(prefix.length(), f.length() - ".java".length()).replace('/', '.'));
            }
        }
        for (String dir : affected) {
            var module = graph.modules().stream().filter(m -> m.dir().equals(dir)).findFirst().orElse(null);
            if (module == null) continue;
            String reason = direct.contains(dir) ? "CHANGED" : wholeBuild && !dependents.contains(dir) ? "BUILD_CHANGED" : "DEPENDS_ON_CHANGED";
            List<String> moduleTests = testsPerModule.getOrDefault(dir, List.of());
            modules.add(new AffectedModule(dir, module.coordinates(), reason, changedPerModule.getOrDefault(dir, 0), moduleTests.size(), "maven"));
            tests.addAll(moduleTests);
            if (direct.contains(dir) && moduleTests.isEmpty()) notes.add(dir + " changed but has no test classes of its own; it is only covered through dependent modules, if at all.");
        }
        return outside;
    }

    /** A Gradle or workspace build: attributes the files no earlier build claimed; returns those still unclaimed. */
    private static List<String> other(BuildGraph graph, List<String> files, List<String> candidates, List<AffectedModule> modules, List<String> tests,
            List<String> notes, List<String> whole) {
        Map<String, Integer> changedPerModule = new LinkedHashMap<>();
        List<String> outside = new ArrayList<>();
        boolean wholeBuild = false;
        for (String path : candidates) {
            if (graph.changesWholeBuild(path)) { wholeBuild = true; continue; }
            String module = graph.moduleOf(path);
            if (module == null) outside.add(path); else changedPerModule.merge(module, 1, Integer::sum);
        }
        Set<String> direct = changedPerModule.keySet();
        Set<String> dependents = graph.dependentsOf(direct);
        Set<String> affected = new TreeSet<>(direct);
        if (wholeBuild) {
            whole.add(graph.system());
            affected.addAll(graph.modules().keySet());
            notes.add("A build-wide " + (graph.system().equals("gradle") ? "Gradle" : "workspace") + " file changed, so every " + graph.system() + " module is affected.");
        } else {
            affected.addAll(dependents);
        }
        for (String dir : affected) {
            var module = graph.modules().get(dir);
            if (module == null) continue;
            String reason = direct.contains(dir) ? "CHANGED" : wholeBuild && !dependents.contains(dir) ? "BUILD_CHANGED" : "DEPENDS_ON_CHANGED";
            List<String> moduleTests = graph.testsIn(dir, files);
            modules.add(new AffectedModule(dir, module.id().equals(dir) ? module.name() : module.id(), reason, changedPerModule.getOrDefault(dir, 0), moduleTests.size(), graph.system()));
            tests.addAll(moduleTests);
            if (direct.contains(dir) && moduleTests.isEmpty()) notes.add((dir.isEmpty() ? "The root project" : dir) + " changed but has no tests of its own; it is only covered through dependent modules, if at all.");
        }
        return outside;
    }

    private static boolean isTestClassName(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1, path.length() - ".java".length());
        // Maven Surefire's default includes (Test*, *Test, *Tests, *TestCase) plus Failsafe's *IT.
        return name.endsWith("Test") || name.endsWith("Tests") || name.endsWith("TestCase") || name.endsWith("IT") || name.startsWith("Test");
    }

    private static List<String> limit(List<String> values) {
        return values.size() <= MAX_LISTED ? List.copyOf(values) : List.copyOf(values.subList(0, MAX_LISTED));
    }
}
