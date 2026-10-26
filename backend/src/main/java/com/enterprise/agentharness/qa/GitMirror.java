package com.enterprise.agentharness.qa;

import com.enterprise.agentharness.runtime.ContainerSettings;
import com.enterprise.agentharness.runtime.ProcessRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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
    private final ContainerSettings settings;
    public GitMirror(ProcessRunner runner, ContainerSettings settings) { this.runner = runner; this.settings = settings; }

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
        Path mirror = ensureCommit(project, commit, log);
        Path archive = target.resolveSibling("source.tar");
        check(runner.run(List.of(settings.git(), "-C", mirror.toString(), "archive", "--format=tar", "-o", archive.toString(), commit), log, LOCAL), "archive");
        Files.createDirectories(target);
        check(runner.run(List.of("tar", "-xf", archive.toString(), "-C", target.toString()), log, LOCAL), "extract");
        Files.deleteIfExists(archive);
    }

    /**
     * Reads one file at {@code commit}; empty when the commit does not contain it. Content larger than
     * {@code maxBytes} is rejected rather than truncated, so a partial file is never parsed.
     */
    public Optional<String> readFile(QaRegistry.Project project, String commit, String path, int maxBytes) throws IOException {
        Path log = Files.createTempFile("eahp-git", ".log");
        try {
            Path mirror = ensureCommit(project, commit, log);
            String object = commit + ":" + path;
            if (runner.run(List.of(settings.git(), "-C", mirror.toString(), "cat-file", "-e", object), log, LOCAL).exitCode() != 0) return Optional.empty();
            Path out = Files.createTempFile("eahp-git", ".out");
            try {
                check(runner.run(List.of(settings.git(), "-C", mirror.toString(), "cat-file", "-p", object), out, LOCAL), "read file");
                if (Files.size(out) > maxBytes) throw new IOException(path + " is larger than " + maxBytes + " bytes");
                return Optional.of(Files.readString(out, StandardCharsets.UTF_8));
            } finally {
                Files.deleteIfExists(out);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", ex);
        } finally {
            Files.deleteIfExists(log);
        }
    }

    /** Paths changed from the merge base of {@code base} and {@code head} to {@code head}, as a pull request diff shows them. */
    public List<String> changedFiles(QaRegistry.Project project, String base, String head) throws IOException {
        return lines(project, List.of("diff", "--name-only", "--no-renames", base + "..." + head), base, head);
    }

    /**
     * Lines added or changed from the merge base of {@code base} and {@code head} to {@code head}, per path at head
     * (new-code coverage, P39). Deleted files and pure deletions contribute nothing.
     */
    public java.util.Map<String, java.util.TreeSet<Integer>> changedLines(QaRegistry.Project project, String base, String head) throws IOException {
        var result = new java.util.TreeMap<String, java.util.TreeSet<Integer>>();
        String file = null;
        for (String line : lines(project, List.of("diff", "-U0", "--no-renames", "--no-color", "--no-ext-diff", base + "..." + head), base, head)) {
            if (line.startsWith("+++ ")) {
                file = line.equals("+++ /dev/null") ? null : line.startsWith("+++ b/") ? line.substring(6) : null;
            } else if (line.startsWith("@@") && file != null) {
                var m = HUNK.matcher(line);
                if (!m.find()) continue;
                int start = Integer.parseInt(m.group(1)), count = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
                if (count == 0) continue;
                var set = result.computeIfAbsent(file, f -> new java.util.TreeSet<>());
                for (int i = start; i < start + Math.min(count, 100_000); i++) set.add(i);
            }
        }
        return result;
    }
    private static final java.util.regex.Pattern HUNK = java.util.regex.Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");

    /** All file paths at {@code commit}. */
    public List<String> listFiles(QaRegistry.Project project, String commit) throws IOException {
        return lines(project, List.of("ls-tree", "-r", "--name-only", commit), commit);
    }

    private List<String> lines(QaRegistry.Project project, List<String> gitArgs, String... commits) throws IOException {
        Path log = Files.createTempFile("eahp-git", ".log");
        Path out = Files.createTempFile("eahp-git", ".out");
        try {
            Path mirror = null;
            for (String commit : commits) mirror = ensureCommit(project, commit, log);
            var argv = new java.util.ArrayList<>(List.of(settings.git(), "-C", mirror.toString()));
            argv.addAll(gitArgs);
            check(runner.run(argv, out, LOCAL), gitArgs.get(0));
            if (Files.size(out) > 16L * 1024 * 1024) throw new IOException("git output too large");
            return Files.readAllLines(out, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).toList();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", ex);
        } finally {
            Files.deleteIfExists(log);
            Files.deleteIfExists(out);
        }
    }

    /** Clones the mirror on first use and fetches only when {@code commit} is missing. */
    private Path ensureCommit(QaRegistry.Project project, String commit, Path log) throws IOException, InterruptedException {
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
        return mirror;
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
