package com.enterprise.agentharness.qa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Resolves refs to exact commits and exports a clean source tree (git archive, no .git directory) from a
 * per-project mirror. The worker runs one tool at a time, so a mirror is never updated concurrently.
 */
@Component
public class GitMirror {
    private static final Duration NETWORK = Duration.ofMinutes(5);
    private static final Duration LOCAL = Duration.ofMinutes(2);
    private final ProcessRunner runner;
    private final QaSettings settings;
    public GitMirror(ProcessRunner runner, QaSettings settings) { this.runner = runner; this.settings = settings; }

    /** A full commit hash is used as-is; a branch or tag name is resolved on the remote. */
    public String resolve(QaRegistry.Project project, String ref) {
        if (ref.matches("[0-9a-f]{40}")) return ref;
        if (!ref.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,99}") || ref.contains("..") || ref.endsWith("/") || ref.endsWith(".lock")) {
            throw new IllegalArgumentException("ref is not a valid branch, tag or commit");
        }
        String out;
        try {
            out = capture(List.of(settings.git(), "ls-remote", "--", project.repositoryUrl(), "refs/heads/" + ref, "refs/tags/" + ref, "refs/tags/" + ref + "^{}"), Duration.ofSeconds(30));
        } catch (IOException ex) {
            throw new IllegalArgumentException("ref could not be resolved");
        }
        String head = null, tag = null, peeled = null;
        for (String line : out.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 2 || !parts[0].matches("[0-9a-f]{40}")) continue;
            if (parts[1].equals("refs/heads/" + ref)) head = parts[0];
            else if (parts[1].equals("refs/tags/" + ref + "^{}")) peeled = parts[0];
            else if (parts[1].equals("refs/tags/" + ref)) tag = parts[0];
        }
        String commit = head != null ? head : peeled != null ? peeled : tag;
        if (commit == null) throw new IllegalArgumentException("ref not found in the project repository");
        return commit;
    }

    /** Exports exactly {@code commit} into {@code target}, fetching the mirror only when the commit is missing. */
    public void export(QaRegistry.Project project, String commit, Path target, Path log) throws IOException, InterruptedException {
        Path mirror = settings.workdir().resolve("mirrors").resolve(project.key() + ".git");
        Files.createDirectories(mirror.getParent());
        if (!Files.isDirectory(mirror)) {
            check(runner.run(List.of(settings.git(), "clone", "--mirror", "--quiet", "--", project.repositoryUrl(), mirror.toString()), log, NETWORK), "clone");
        }
        var hasCommit = runner.run(List.of(settings.git(), "-C", mirror.toString(), "cat-file", "-e", commit + "^{commit}"), log, LOCAL);
        if (hasCommit.exitCode() != 0) {
            check(runner.run(List.of(settings.git(), "-C", mirror.toString(), "fetch", "--prune", "--quiet", "origin"), log, NETWORK), "fetch");
            check(runner.run(List.of(settings.git(), "-C", mirror.toString(), "cat-file", "-e", commit + "^{commit}"), log, LOCAL), "find commit");
        }
        Path archive = target.resolveSibling("source.tar");
        check(runner.run(List.of(settings.git(), "-C", mirror.toString(), "archive", "--format=tar", "-o", archive.toString(), commit), log, LOCAL), "archive");
        Files.createDirectories(target);
        check(runner.run(List.of("tar", "-xf", archive.toString(), "-C", target.toString()), log, LOCAL), "extract");
        Files.deleteIfExists(archive);
    }

    private String capture(List<String> argv, Duration timeout) throws IOException {
        Path out = Files.createTempFile("eahp-git", ".out");
        try {
            var result = runner.run(argv, out, timeout);
            if (result.exitCode() != 0) throw new IOException("git exited with " + result.exitCode());
            byte[] bytes = Files.readAllBytes(out);
            return new String(bytes, 0, Math.min(bytes.length, 1 << 20), StandardCharsets.UTF_8);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", ex);
        } finally {
            Files.deleteIfExists(out);
        }
    }

    private static void check(ProcessRunner.Result result, String step) throws IOException {
        if (result.exitCode() != 0) throw new IOException("git " + step + " failed with exit code " + result.exitCode());
    }

}
