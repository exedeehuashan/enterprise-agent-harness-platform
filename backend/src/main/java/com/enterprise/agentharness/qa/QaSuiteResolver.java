package com.enterprise.agentharness.qa;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Finds the suite to run at an exact commit. A suite declared in the project's repository file (when the project
 * enables it) takes precedence over a registry suite with the same key, so test selection evolves with the code.
 * A registry suite marked disabled blocks its key from every source. Whatever the source, the suite must satisfy
 * the project's policy. Messages are safe to return to API clients.
 */
@Service
public class QaSuiteResolver {
    private final QaRegistry registry;
    private final GitMirror git;
    public QaSuiteResolver(QaRegistry registry, GitMirror git) { this.registry = registry; this.git = git; }

    public record Resolved(QaRegistry.Project project, String ref, String commit, SuiteDefinition suite) {}
    public record Catalog(String project, String ref, String commit, String repositoryFile, String repositoryError, List<SuiteDefinition> suites) {}

    public Resolved resolve(String projectKey, String suiteKey, String ref) {
        var project = enabledProject(projectKey);
        String effectiveRef = ref == null ? project.defaultRef() : ref;
        String commit = git.resolve(project, effectiveRef);
        var registered = registry.suite(project.key(), suiteKey);
        if (registered.isPresent() && !registered.get().enabled()) throw new IllegalArgumentException("unknown or disabled suite");
        var declared = repositorySuites(project, commit).stream().filter(s -> s.key().equals(suiteKey)).findFirst();
        var suite = declared.or(() -> registered.map(SuiteDefinition::fromRegistry))
                .orElseThrow(() -> new IllegalArgumentException("unknown or disabled suite"));
        checkPolicy(project, suite);
        return new Resolved(project, effectiveRef, commit, suite);
    }

    /** All suites available at a ref, for clients choosing what to run. Invalid repository files are reported, not hidden. */
    public Catalog catalog(String projectKey, String ref) {
        var project = enabledProject(projectKey);
        String effectiveRef = ref == null ? project.defaultRef() : ref;
        String commit = git.resolve(project, effectiveRef);
        Map<String, SuiteDefinition> suites = new LinkedHashMap<>();
        project.suites().stream().filter(QaRegistry.Suite::enabled).forEach(s -> suites.put(s.key(), SuiteDefinition.fromRegistry(s)));
        String file = project.repositorySuitesEnabled() ? "absent" : "disabled", error = null;
        try {
            var text = repositoryFile(project, commit);
            if (text.isPresent()) file = "found";
            for (var s : text.map(RepositorySuites::parse).orElse(List.of())) {
                boolean blocked = project.suites().stream().anyMatch(r -> r.key().equals(s.key()) && !r.enabled());
                if (!blocked) suites.put(s.key(), s);
            }
        } catch (IllegalArgumentException ex) {
            file = "invalid";
            error = ex.getMessage();
        }
        List<SuiteDefinition> allowed = new ArrayList<>();
        for (var s : suites.values()) {
            try { checkPolicy(project, s); allowed.add(s); } catch (IllegalArgumentException ignored) { /* outside policy: not offered */ }
        }
        return new Catalog(project.key(), effectiveRef, commit, file, error, allowed);
    }

    /** The suite must stay within the project's image allowlist, Docker access, network and time limits. */
    public static void checkPolicy(QaRegistry.Project project, SuiteDefinition suite) {
        if (!project.allowedImages().contains(suite.image())) throw new IllegalArgumentException("image " + suite.image() + " is not allowed for this project");
        if (suite.dockerAccess() && !project.allowDockerAccess()) throw new IllegalArgumentException("this project does not allow Docker access");
        if (suite.network().equals("egress") && !project.allowNetworkEgress()) throw new IllegalArgumentException("this project does not allow network egress");
        if (suite.maxTimeoutSeconds() > project.maxTimeoutSeconds()) throw new IllegalArgumentException("suite timeout exceeds the project's maximum of " + project.maxTimeoutSeconds());
    }

    QaRegistry.Project enabledProject(String key) {
        return registry.project(key).filter(QaRegistry.Project::enabled).orElseThrow(() -> new IllegalArgumentException("unknown or disabled project"));
    }

    private List<SuiteDefinition> repositorySuites(QaRegistry.Project project, String commit) {
        return repositoryFile(project, commit).map(RepositorySuites::parse).orElse(List.of());
    }

    private java.util.Optional<String> repositoryFile(QaRegistry.Project project, String commit) {
        if (!project.repositorySuitesEnabled()) return java.util.Optional.empty();
        try {
            return git.readFile(project, commit, project.suitesFile(), RepositorySuites.MAX_BYTES);
        } catch (IOException ex) {
            throw new IllegalArgumentException("could not read " + project.suitesFile() + " at " + commit.substring(0, 12));
        }
    }
}
