package com.enterprise.agentharness.qa.impact;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Works out what a change could break in a Maven multi-module build: the modules containing changed files, every
 * module that depends on them, and the test classes in those modules. It is advisory: it explains scope and risk,
 * it does not decide which tests may be skipped.
 */
public final class ChangeImpactAnalyzer {
    static final int MAX_CHANGED_FILES = 2000;
    static final int MAX_LISTED = 500;

    public record AffectedModule(String dir, String coordinates, String reason, int changedFiles, int testClasses) {}
    public record Impact(String buildSystem, int changedFileCount, List<String> changedFiles, boolean truncated, boolean wholeBuild,
            List<AffectedModule> modules, List<String> testClasses, List<String> filesOutsideModules, List<String> notes) {}

    private ChangeImpactAnalyzer() {}

    public static Impact analyze(RepositoryView repo, String base, String head) throws IOException {
        List<String> changed = repo.changedFiles(base, head);
        List<String> files = repo.files(head);
        Map<String, String> poms = new LinkedHashMap<>();
        for (String f : files) {
            if ((f.equals("pom.xml") || f.endsWith("/pom.xml")) && poms.size() < 200) repo.read(head, f).ifPresent(c -> poms.put(f, c));
        }
        List<String> notes = new ArrayList<>();
        boolean truncated = changed.size() > MAX_CHANGED_FILES;
        if (truncated) changed = changed.subList(0, MAX_CHANGED_FILES);
        if (poms.isEmpty()) {
            notes.add("No Maven build found at this commit; module impact is not available for this build system yet.");
            return new Impact("unknown", changed.size(), limit(changed), truncated, false, List.of(), List.of(), List.of(), notes);
        }
        var graph = MavenModuleGraph.parse(poms);
        Map<String, Integer> changedPerModule = new LinkedHashMap<>();
        List<String> outside = new ArrayList<>();
        boolean wholeBuild = false;
        for (String path : changed) {
            if (path.equals("pom.xml")) { wholeBuild = true; continue; }
            String module = graph.moduleOf(path);
            if (module == null || module.isEmpty()) outside.add(path);
            else changedPerModule.merge(module, 1, Integer::sum);
        }
        Set<String> direct = changedPerModule.keySet();
        Set<String> affected = new TreeSet<>(direct);
        Set<String> dependents = graph.dependentsOf(direct);
        if (wholeBuild) {
            graph.modules().stream().map(MavenModuleGraph.Module::dir).filter(d -> !d.isEmpty()).forEach(affected::add);
            notes.add("The root pom.xml changed, so every module is affected.");
        } else {
            affected.addAll(dependents);
        }
        Map<String, List<String>> testsPerModule = new LinkedHashMap<>();
        for (String f : files) {
            String module = graph.moduleOf(f);
            if (module == null || module.isEmpty() || !affected.contains(module)) continue;
            String prefix = module + "/src/test/java/";
            if (f.startsWith(prefix) && f.endsWith(".java") && isTestClassName(f)) {
                testsPerModule.computeIfAbsent(module, k -> new ArrayList<>())
                        .add(f.substring(prefix.length(), f.length() - ".java".length()).replace('/', '.'));
            }
        }
        List<AffectedModule> modules = new ArrayList<>();
        List<String> tests = new ArrayList<>();
        for (String dir : affected) {
            var module = graph.modules().stream().filter(m -> m.dir().equals(dir)).findFirst().orElse(null);
            if (module == null) continue;
            String reason = direct.contains(dir) ? "CHANGED" : wholeBuild && !dependents.contains(dir) ? "BUILD_CHANGED" : "DEPENDS_ON_CHANGED";
            List<String> moduleTests = testsPerModule.getOrDefault(dir, List.of());
            modules.add(new AffectedModule(dir, module.coordinates(), reason, changedPerModule.getOrDefault(dir, 0), moduleTests.size()));
            tests.addAll(moduleTests);
            if (direct.contains(dir) && moduleTests.isEmpty()) notes.add(dir + " changed but has no test classes of its own; it is only covered through dependent modules, if at all.");
        }
        if (!outside.isEmpty()) notes.add(outside.size() + " changed file(s) are outside the Maven modules (for example frontend, CI or docs); backend suites do not cover them.");
        if (affected.isEmpty() && outside.isEmpty() && !wholeBuild) notes.add("No module is affected.");
        return new Impact("maven", changed.size(), limit(changed), truncated, wholeBuild, modules, limit(tests), limit(outside), notes);
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
