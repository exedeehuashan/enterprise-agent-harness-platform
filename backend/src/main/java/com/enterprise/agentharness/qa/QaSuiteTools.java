package com.enterprise.agentharness.qa;

import com.enterprise.agentharness.tool.ToolContext;
import com.enterprise.agentharness.tool.ToolExecutor;
import com.enterprise.agentharness.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 方案 4 QA tools: run a registered project's predefined test suite at an exact commit in a disposable,
 * resource-limited container, and return the real exit code, JUnit reports and logs as evidence.
 * QA_RUN_SUITE only runs suites without Docker access; QA_RUN_PRIVILEGED_SUITE runs suites that need the
 * Docker socket (host-level access) and therefore requires approval.
 */
@Configuration
public class QaSuiteTools {
    private static final Logger log = LoggerFactory.getLogger(QaSuiteTools.class);
    static final int MAX_LOG_TAIL = 2 * 1024 * 1024;
    static final long MAX_REPORT_ZIP = 5L * 1024 * 1024;
    /** Maven otherwise waits up to 30 minutes on a stalled download, silently consuming the whole suite deadline. */
    static final String MAVEN_NETWORK_TIMEOUTS = " -Daether.connector.connectTimeout=30000 -Daether.connector.requestTimeout=120000";

    @Bean SuiteTool qaRunSuite(QaRegistry registry, QaSuiteResolver resolver, GitMirror git, ProcessRunner runner, QaSettings settings, ObjectMapper mapper) {
        return new SuiteTool("QA_RUN_SUITE", false, registry, resolver, git, runner, settings, mapper);
    }

    @Bean SuiteTool qaRunPrivilegedSuite(QaRegistry registry, QaSuiteResolver resolver, GitMirror git, ProcessRunner runner, QaSettings settings, ObjectMapper mapper) {
        return new SuiteTool("QA_RUN_PRIVILEGED_SUITE", true, registry, resolver, git, runner, settings, mapper);
    }

    public static final class SuiteTool implements ToolExecutor {
        private final String name;
        private final boolean privileged;
        private final QaRegistry registry;
        private final QaSuiteResolver resolver;
        private final GitMirror git;
        private final ProcessRunner runner;
        private final QaSettings settings;
        private final ObjectMapper mapper;

        SuiteTool(String name, boolean privileged, QaRegistry registry, QaSuiteResolver resolver, GitMirror git, ProcessRunner runner,
                QaSettings settings, ObjectMapper mapper) {
            this.name = name; this.privileged = privileged; this.registry = registry; this.resolver = resolver; this.git = git;
            this.runner = runner; this.settings = settings; this.mapper = mapper;
        }

        public String name() { return name; }
        public int version() { return 1; }
        public String target() { return "qa-runner"; }
        public boolean requiresApproval() { return privileged; }
        /** Re-running a suite after a lost worker could repeat side effects on shared resources, so it goes to review. */
        public boolean retryable() { return false; }

        /**
         * {project, suite, ref?, modules?} becomes {project, suite, ref, commit, definition, modules?}: the exact commit, the
         * resolved suite (registry or repository file at that commit) and any module selection are what an approval binds
         * and what the executor runs. {@code modules} narrows a {@code maven-modules} suite to those modules and what they
         * depend on ({@code -pl ... -am}); each must be a module directory with a pom.xml at the commit.
         */
        @Override
        public JsonNode normalize(JsonNode arguments) {
            if (arguments == null || !arguments.isObject()) throw new IllegalArgumentException("project and suite are required");
            var fields = arguments.fieldNames();
            while (fields.hasNext()) if (!Set.of("project", "suite", "ref", "modules").contains(fields.next())) throw new IllegalArgumentException("unknown argument");
            for (String field : List.of("project", "suite")) {
                if (!arguments.path(field).isTextual()) throw new IllegalArgumentException(field + " is required");
            }
            if (arguments.has("ref") && !arguments.get("ref").isTextual()) throw new IllegalArgumentException("ref must be text");
            var resolved = resolver.resolve(arguments.get("project").textValue(), arguments.get("suite").textValue(),
                    arguments.has("ref") ? arguments.get("ref").textValue() : null);
            if (resolved.suite().dockerAccess() != privileged) {
                throw new IllegalArgumentException(privileged ? "suite does not need Docker access; use QA_RUN_SUITE" : "suite needs Docker access; use QA_RUN_PRIVILEGED_SUITE");
            }
            ObjectNode normalized = JsonNodeFactory.instance.objectNode();
            normalized.put("project", resolved.project().key()).put("suite", resolved.suite().key()).put("ref", resolved.ref()).put("commit", resolved.commit());
            normalized.set("definition", resolved.suite().toJson());
            if (arguments.has("modules")) normalized.set("modules", modules(arguments.get("modules"), resolved));
            return normalized;
        }

        private JsonNode modules(JsonNode value, QaSuiteResolver.Resolved resolved) {
            if (!SuiteDefinition.MAVEN_MODULES.equals(resolved.suite().selection())) throw new IllegalArgumentException("this suite does not support module selection");
            if (!value.isArray() || value.isEmpty() || value.size() > 50) throw new IllegalArgumentException("modules must list 1..50 module directories");
            var dirs = new java.util.TreeSet<String>();
            for (JsonNode m : value) {
                String dir = m.isTextual() ? m.textValue() : "";
                if (!dir.matches("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*") || dir.contains("..") || dir.length() > 200) {
                    throw new IllegalArgumentException("modules must be relative module directories");
                }
                try {
                    if (git.readFile(resolved.project(), resolved.commit(), dir + "/pom.xml", 1024 * 1024).isEmpty()) {
                        throw new IllegalArgumentException("module " + dir + " has no pom.xml at " + resolved.commit().substring(0, 12));
                    }
                } catch (IOException ex) {
                    throw new IllegalArgumentException("could not read module " + dir + " at " + resolved.commit().substring(0, 12));
                }
                dirs.add(dir);
            }
            var array = JsonNodeFactory.instance.arrayNode();
            dirs.forEach(array::add);
            return array;
        }

        @Override
        public void checkTimeout(JsonNode arguments, int timeoutSeconds) {
            var suite = SuiteDefinition.fromJson(arguments.get("definition"));
            if (timeoutSeconds > suite.maxTimeoutSeconds()) throw new IllegalArgumentException("timeoutSeconds exceeds this suite's maximum of " + suite.maxTimeoutSeconds());
        }

        @Override
        public ToolResult execute(ToolContext context) throws Exception {
            var args = context.arguments();
            var project = registry.project(args.get("project").textValue()).filter(QaRegistry.Project::enabled).orElse(null);
            var suite = SuiteDefinition.fromJson(args.get("definition"));
            boolean blocked = project == null || registry.suite(project.key(), suite.key()).map(s -> !s.enabled()).orElse(false)
                    || (suite.source().equals(SuiteDefinition.REGISTRY) && registry.suite(project.key(), suite.key()).isEmpty());
            if (blocked || suite.dockerAccess() != privileged) {
                return ToolResult.failure("SUITE_UNAVAILABLE", "The project or suite is no longer available.");
            }
            try { QaSuiteResolver.checkPolicy(project, suite); }
            catch (IllegalArgumentException ex) { return ToolResult.failure("SUITE_POLICY_CHANGED", "The suite no longer satisfies project policy: " + ex.getMessage()); }
            String commit = args.get("commit").textValue();
            Path runDir = settings.workdir().resolve("runs").resolve(context.run().id() + "-" + context.run().attemptCount());
            Path source = runDir.resolve("src");
            Path setupLog = runDir.resolve("setup.log");
            Path consoleLog = runDir.resolve("console.log");
            Instant started = Instant.now();
            try {
                deleteTree(runDir);
                Files.createDirectories(runDir);
                Files.createDirectories(settings.workdir().resolve("cache").resolve(project.key()));
                Path dockerCheck = runDir.resolve("docker-check.log");
                if (runner.run(List.of(settings.docker(), "version", "--format", "{{.Server.Version}}"), dockerCheck, Duration.ofSeconds(30)).exitCode() != 0) {
                    return ToolResult.failure("RUNNER_UNAVAILABLE",
                            "The Docker daemon is not reachable from EAHP. Enable the QA runner (docker-compose.qa.yml) to run suites.",
                            tail("docker-check.log", dockerCheck));
                }
                try {
                    git.export(project, commit, source, setupLog);
                } catch (IOException ex) {
                    log.warn("Source export failed for run {}", context.run().id());
                    return ToolResult.failure("SOURCE_UNAVAILABLE", "Could not export commit " + commit + " of " + project.key() + ".",
                            tail("setup.log", setupLog));
                }
                Duration remaining = Duration.between(Instant.now(), context.deadline());
                if (remaining.isNegative() || remaining.isZero() || context.stopRequested()) {
                    return ToolResult.failure("STOPPED", "The run was stopped before the suite started.");
                }
                String container = "eahp-qa-" + context.run().id() + "-" + context.run().attemptCount();
                var result = runner.run(dockerArgs(container, project, suite, source, context), consoleLog, remaining,
                        context::stopRequested, () -> kill(container));
                Instant finished = Instant.now();
                var reports = JUnitReports.find(source, suite.reportDirs());
                var summary = JUnitReports.summarize(reports);
                byte[] zip = reports.isEmpty() ? null : JUnitReports.zip(source, reports, MAX_REPORT_ZIP);
                String code = outcome(result, summary);
                var evidence = new ArrayList<ToolResult.Artifact>();
                evidence.add(new ToolResult.Artifact("summary.json", "application/json",
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(summaryJson(project, suite, args, result, summary, code, started, finished, zip == null && !reports.isEmpty()))));
                if (zip != null) evidence.add(new ToolResult.Artifact("junit-reports.zip", "application/zip", zip));
                evidence.add(tail("console.log", consoleLog));
                String text = String.format("%s/%s @ %s: exit %s, %d tests, %d failures, %d errors, %d skipped in %d s",
                        project.key(), suite.key(), commit.substring(0, 12), result.timedOut() || result.stopped() ? "n/a" : String.valueOf(result.exitCode()),
                        summary.tests(), summary.failures(), summary.errors(), summary.skipped(), Duration.between(started, finished).toSeconds());
                var artifacts = evidence.toArray(ToolResult.Artifact[]::new);
                return code == null ? ToolResult.success(text, artifacts) : ToolResult.failure(code, text, artifacts);
            } finally {
                try { deleteTree(runDir); } catch (IOException ex) { log.warn("Could not clean up workspace for run {}", context.run().id()); }
            }
        }

        /** Success needs a zero exit code, at least one executed test and no failures or errors. */
        static String outcome(ProcessRunner.Result result, JUnitReports.Summary summary) {
            if (result.stopped()) return "STOPPED";
            if (result.timedOut()) return "SUITE_TIMEOUT";
            if (summary.failures() + summary.errors() > 0) return "TESTS_FAILED";
            if (result.exitCode() == 125 || result.exitCode() == 126 || result.exitCode() == 127) return "RUNNER_ERROR";
            if (result.exitCode() != 0) return "BUILD_FAILED";
            if (summary.tests() == 0) return "NO_TESTS_FOUND";
            if (summary.unreadableFiles() > 0) return "REPORTS_UNREADABLE";
            return null;
        }

        List<String> dockerArgs(String container, QaRegistry.Project project, SuiteDefinition suite, Path source, ToolContext context) {
            Path home = settings.workdir().resolve("cache").resolve(project.key());
            var argv = new ArrayList<>(List.of(settings.docker(), "run", "--rm", "--name", container,
                    "--label", "eahp.run=" + context.run().id(), "--label", "eahp.project=" + project.key(),
                    "--cpus", settings.cpus(), "--memory", settings.memory(), "--pids-limit", "2048",
                    "--security-opt", "no-new-privileges", "--cap-drop", "ALL",
                    "--network", suite.network().equals("none") ? "none" : "bridge",
                    "--user", user(),
                    "-e", "HOME=" + home, "-e", "MAVEN_CONFIG=" + home.resolve(".m2"), "-e", "MAVEN_OPTS=-Duser.home=" + home + MAVEN_NETWORK_TIMEOUTS,
                    "-e", "CI=true",
                    "-v", settings.workdirMountSource() + ":" + settings.workdir(), "-w", source.toString()));
            if (suite.dockerAccess()) {
                argv.addAll(List.of("-v", settings.dockerSocket() + ":/var/run/docker.sock",
                        "--add-host", "host.docker.internal:host-gateway", "-e", "TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal"));
                String group = socketGroup();
                if (group != null) argv.addAll(List.of("--group-add", group));
            }
            argv.add(suite.image());
            argv.addAll(suite.command());
            var modules = context.arguments().get("modules");
            if (modules != null) {
                // Only the selected modules and what they depend on; tests of those dependencies run too, which keeps it a superset.
                var dirs = new ArrayList<String>();
                modules.forEach(m -> dirs.add(m.textValue()));
                argv.addAll(List.of("-pl", String.join(",", dirs), "-am"));
            }
            return argv;
        }

        private String user() {
            if (!settings.containerUser().isBlank()) return settings.containerUser();
            try {
                Object uid = Files.getAttribute(settings.workdir(), "unix:uid");
                Object gid = Files.getAttribute(settings.workdir(), "unix:gid");
                return uid + ":" + gid;
            } catch (Exception ex) {
                return "65534:65534";
            }
        }

        private String socketGroup() {
            try { return String.valueOf(Files.getAttribute(Path.of(settings.dockerSocket()), "unix:gid")); }
            catch (Exception ex) { return null; }
        }

        private void kill(String container) {
            try { runner.run(List.of(settings.docker(), "kill", container), Files.createTempFile("eahp-kill", ".log"), Duration.ofSeconds(30)); }
            catch (Exception ex) { log.warn("Could not stop container {}", container); }
        }

        private Map<String, Object> summaryJson(QaRegistry.Project project, SuiteDefinition suite, JsonNode args, ProcessRunner.Result result,
                JUnitReports.Summary summary, String code, Instant started, Instant finished, boolean reportsTruncated) {
            var json = new LinkedHashMap<String, Object>();
            json.put("project", project.key());
            json.put("suite", suite.key());
            json.put("ref", args.get("ref").textValue());
            json.put("commit", args.get("commit").textValue());
            json.put("suiteSource", suite.source());
            json.put("runnerImage", suite.image());
            json.put("command", suite.command());
            json.put("dockerAccess", suite.dockerAccess());
            var modules = new ArrayList<String>();
            if (args.has("modules")) args.get("modules").forEach(m -> modules.add(m.textValue()));
            json.put("scope", modules.isEmpty() ? "FULL" : "MODULES");
            json.put("modules", modules);
            json.put("exitCode", result.stopped() || result.timedOut() ? null : result.exitCode());
            json.put("stopped", result.stopped());
            json.put("timedOut", result.timedOut());
            json.put("outcome", code == null ? "PASSED" : code);
            json.put("startedAt", started.toString());
            json.put("finishedAt", finished.toString());
            json.put("durationSeconds", Duration.between(started, finished).toSeconds());
            json.put("tests", summary.tests());
            json.put("failures", summary.failures());
            json.put("errors", summary.errors());
            json.put("skipped", summary.skipped());
            json.put("reportFiles", summary.reportFiles());
            json.put("unreadableReportFiles", summary.unreadableFiles());
            json.put("reportsTruncated", reportsTruncated);
            json.put("failedTests", summary.failedTests());
            return json;
        }
    }

    static ToolResult.Artifact tail(String name, Path file) {
        try {
            if (!Files.exists(file)) return new ToolResult.Artifact(name, "text/plain", new byte[0]);
            try (var raf = new RandomAccessFile(file.toFile(), "r")) {
                long length = raf.length();
                long start = Math.max(0, length - MAX_LOG_TAIL);
                byte[] bytes = new byte[(int) (length - start)];
                raf.seek(start);
                raf.readFully(bytes);
                if (start == 0) return new ToolResult.Artifact(name, "text/plain", bytes);
                byte[] marker = ("[earlier output truncated; showing last " + MAX_LOG_TAIL + " bytes]\n").getBytes(StandardCharsets.UTF_8);
                byte[] combined = new byte[marker.length + bytes.length];
                System.arraycopy(marker, 0, combined, 0, marker.length);
                System.arraycopy(bytes, 0, combined, marker.length, bytes.length);
                return new ToolResult.Artifact(name, "text/plain", combined);
            }
        } catch (IOException ex) {
            return new ToolResult.Artifact(name, "text/plain", "log unavailable\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
