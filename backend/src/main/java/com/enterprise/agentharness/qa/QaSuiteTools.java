package com.enterprise.agentharness.qa;

import com.enterprise.agentharness.runtime.ContainerSettings;
import com.enterprise.agentharness.runtime.ProcessRunner;
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

    @Bean SuiteTool qaRunSuite(QaRegistry registry, QaSuiteResolver resolver, GitMirror git, ProcessRunner runner, ContainerSettings settings, ObjectMapper mapper,
            QaEnvironments environments) {
        return new SuiteTool("QA_RUN_SUITE", false, registry, resolver, git, runner, settings, mapper, environments);
    }

    @Bean SuiteTool qaRunPrivilegedSuite(QaRegistry registry, QaSuiteResolver resolver, GitMirror git, ProcessRunner runner, ContainerSettings settings, ObjectMapper mapper,
            QaEnvironments environments) {
        return new SuiteTool("QA_RUN_PRIVILEGED_SUITE", true, registry, resolver, git, runner, settings, mapper, environments);
    }

    public static final class SuiteTool implements ToolExecutor {
        private final String name;
        private final boolean privileged;
        private final QaRegistry registry;
        private final QaSuiteResolver resolver;
        private final GitMirror git;
        private final ProcessRunner runner;
        private final ContainerSettings settings;
        private final ObjectMapper mapper;
        private final QaEnvironments environments;

        SuiteTool(String name, boolean privileged, QaRegistry registry, QaSuiteResolver resolver, GitMirror git, ProcessRunner runner,
                ContainerSettings settings, ObjectMapper mapper, QaEnvironments environments) {
            this.name = name; this.privileged = privileged; this.registry = registry; this.resolver = resolver; this.git = git;
            this.runner = runner; this.settings = settings; this.mapper = mapper; this.environments = environments;
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
            while (fields.hasNext()) if (!Set.of("project", "suite", "ref", "modules", "environment").contains(fields.next())) throw new IllegalArgumentException("unknown argument");
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
            var suite = resolved.suite();
            if (suite.needsEnvironment() != arguments.has("environment")) {
                throw new IllegalArgumentException(suite.needsEnvironment() ? "this suite runs against an environment; choose one" : "this suite does not use an environment");
            }
            if (suite.needsEnvironment()) {
                if (!arguments.get("environment").isTextual()) throw new IllegalArgumentException("environment must be text");
                var env = environments.find(resolved.project().key(), arguments.get("environment").textValue()).filter(QaEnvironments.Environment::enabled)
                        .orElseThrow(() -> new IllegalArgumentException("unknown or disabled environment"));
                var bound = normalized.putObject("services");
                for (var e : new java.util.TreeMap<>(suite.environment()).entrySet()) {
                    String url = env.services().get(e.getValue());
                    if (url == null) throw new IllegalArgumentException("environment " + env.key() + " has no service " + e.getValue());
                    bound.put(e.getKey(), url);
                }
                normalized.put("environment", env.key());
                // A temporary environment (P36) is reachable only on its own Docker network.
                if (env.ephemeral() && env.network() != null) normalized.put("network", env.network());
            }
            return normalized;
        }

        private JsonNode modules(JsonNode value, QaSuiteResolver.Resolved resolved) {
            String system = resolved.suite().moduleSystem();
            if (system == null) throw new IllegalArgumentException("this suite does not support module selection");
            if (!value.isArray() || value.isEmpty() || value.size() > 50) throw new IllegalArgumentException("modules must list 1..50 modules");
            if (system.equals("gradle")) return gradleProjects(value, resolved);
            String manifest = system.equals("npm") ? "package.json" : "pom.xml";
            var dirs = new java.util.TreeSet<String>();
            for (JsonNode m : value) {
                String dir = m.isTextual() ? m.textValue() : "";
                if (!dir.matches("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*") || dir.contains("..") || dir.length() > 200) {
                    throw new IllegalArgumentException("modules must be relative module directories");
                }
                try {
                    if (git.readFile(resolved.project(), resolved.commit(), dir + "/" + manifest, 1024 * 1024).isEmpty()) {
                        throw new IllegalArgumentException("module " + dir + " has no " + manifest + " at " + resolved.commit().substring(0, 12));
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

        /** Gradle project paths (":core"), each a project of the build's settings at the commit. */
        private JsonNode gradleProjects(JsonNode value, QaSuiteResolver.Resolved resolved) {
            com.enterprise.agentharness.qa.impact.BuildGraph graph;
            try {
                var files = git.listFiles(resolved.project(), resolved.commit());
                graph = com.enterprise.agentharness.qa.impact.BuildGraphs.gradle(files,
                        path -> git.readFile(resolved.project(), resolved.commit(), path, 1024 * 1024));
            } catch (IOException ex) {
                throw new IllegalArgumentException("could not read the Gradle build at " + resolved.commit().substring(0, 12));
            }
            if (graph == null) throw new IllegalArgumentException("no Gradle build (settings.gradle) at " + resolved.commit().substring(0, 12));
            var known = graph.modules().values().stream().map(com.enterprise.agentharness.qa.impact.BuildGraph.Module::id).collect(java.util.stream.Collectors.toSet());
            var paths = new java.util.TreeSet<String>();
            for (JsonNode m : value) {
                String path = m.isTextual() ? m.textValue() : "";
                if (!path.matches("(:[A-Za-z0-9_.-]+)+") || !known.contains(path)) throw new IllegalArgumentException("modules must be Gradle project paths of this build, such as :core");
                paths.add(path);
            }
            var array = JsonNodeFactory.instance.arrayNode();
            paths.forEach(array::add);
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
            if (suite.needsEnvironment()) {
                // The URLs the run was created with must still be the environment's; never send traffic somewhere it did not show.
                var env = environments.find(project.key(), args.path("environment").asText()).orElse(null);
                boolean same = env != null && env.enabled();
                if (same) for (var e : suite.environment().entrySet()) same &= args.path("services").path(e.getKey()).asText().equals(env.services().get(e.getValue()));
                if (same) same = java.util.Objects.equals(args.path("network").asText(null), env.ephemeral() ? env.network() : null);
                if (!same) return ToolResult.failure("ENVIRONMENT_CHANGED", "The environment was changed, disabled or removed after this run was created.");
            }
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
                Path reportRoot = source;
                if (suite.exitCodeResults()) {
                    reportRoot = runDir.resolve("results");
                    Files.createDirectories(reportRoot);
                    exitCodeReport(suite, result, consoleLog, reportRoot.resolve("TEST-" + suite.key() + ".xml"));
                }
                var reports = suite.exitCodeResults() ? JUnitReports.find(reportRoot, List.of("")) : JUnitReports.find(source, suite.reportDirs());
                var summary = JUnitReports.summarize(reports);
                byte[] zip = reports.isEmpty() ? null : JUnitReports.zip(reportRoot, reports, MAX_REPORT_ZIP);
                String code = outcome(result, summary);
                var evidence = new ArrayList<ToolResult.Artifact>();
                evidence.add(new ToolResult.Artifact("summary.json", "application/json",
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(summaryJson(project, suite, args, result, summary, code, started, finished, zip == null && !reports.isEmpty()))));
                if (zip != null) evidence.add(new ToolResult.Artifact("junit-reports.zip", "application/zip", zip));
                evidence.add(tail("console.log", consoleLog));
                if (!suite.coverage().isEmpty()) {
                    var coverageReports = CoverageReports.find(source, suite.coverage());
                    var coverage = CoverageReports.summarize(source, coverageReports);
                    evidence.add(new ToolResult.Artifact("coverage.json", "application/json", mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(coverage)));
                    // Line-level data for new-code coverage (P39); left out when it would be too large to keep as evidence.
                    byte[] lines = mapper.writeValueAsBytes(Map.of("files", CoverageLines.read(source, coverageReports)));
                    if (lines.length <= MAX_REPORT_ZIP) evidence.add(new ToolResult.Artifact("coverage-lines.json", "application/json", lines));
                }
                if (suite.reportsPerformance()) {
                    evidence.add(new ToolResult.Artifact("performance.json", "application/json",
                            mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(PerformanceReports.read(source, suite.performance()))));
                }
                String text = String.format("%s/%s @ %s: exit %s, %d tests, %d failures, %d errors, %d skipped in %d s",
                        project.key(), suite.key(), commit.substring(0, 12), result.timedOut() || result.stopped() ? "n/a" : String.valueOf(result.exitCode()),
                        summary.tests(), summary.failures(), summary.errors(), summary.skipped(), Duration.between(started, finished).toSeconds());
                var artifacts = evidence.toArray(ToolResult.Artifact[]::new);
                return code == null ? ToolResult.success(text, artifacts) : ToolResult.failure(code, text, artifacts);
            } finally {
                try { deleteTree(runDir); } catch (IOException ex) { log.warn("Could not clean up workspace for run {}", context.run().id()); }
            }
        }

        /**
         * For exit-code suites: one test case named after the suite, failed with the console tail when the command
         * exited non-zero. Nothing is written when the run was stopped, timed out or the runner itself failed
         * (125..127), so those keep their own outcome.
         */
        static void exitCodeReport(SuiteDefinition suite, ProcessRunner.Result result, Path consoleLog, Path target) throws IOException {
            int exit = result.exitCode();
            if (result.stopped() || result.timedOut() || exit == 125 || exit == 126 || exit == 127) return;
            String key = xml(suite.key());
            var report = new StringBuilder("<testsuite name=\"").append(key).append("\" tests=\"1\" failures=\"").append(exit == 0 ? 0 : 1)
                    .append("\" errors=\"0\" skipped=\"0\"><testcase classname=\"").append(key).append("\" name=\"").append(key).append('"');
            if (exit == 0) report.append("/>");
            else {
                String tail = "";
                if (Files.exists(consoleLog)) {
                    // Only the last 16 KiB is read (the log can be arbitrarily large), and malformed bytes are replaced, not fatal.
                    try (var raf = new RandomAccessFile(consoleLog.toFile(), "r")) {
                        long start = Math.max(0, raf.length() - 16 * 1024);
                        byte[] bytes = new byte[(int) (raf.length() - start)];
                        raf.seek(start);
                        raf.readFully(bytes);
                        String text = new String(bytes, StandardCharsets.UTF_8);
                        tail = text.substring(Math.max(0, text.length() - 4000));
                    }
                }
                report.append("><failure type=\"ExitCode\" message=\"exit code ").append(exit).append("\">").append(xml(tail)).append("</failure></testcase>");
            }
            Files.writeString(target, report.append("</testsuite>").toString());
        }

        private static String xml(String s) {
            var out = new StringBuilder();
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '&' -> out.append("&amp;"); case '<' -> out.append("&lt;"); case '>' -> out.append("&gt;"); case '"' -> out.append("&quot;");
                    default -> { if (c >= 0x20 || c == '\n' || c == '\t') out.append(c); }
                }
            }
            return out.toString();
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
                    "--network", suite.network().equals("none") ? "none" : context.arguments().path("network").asText("bridge"),
                    "--user", user(),
                    "-e", "HOME=" + home, "-e", "MAVEN_CONFIG=" + home.resolve(".m2"), "-e", "MAVEN_OPTS=-Duser.home=" + home + MAVEN_NETWORK_TIMEOUTS,
                    "-e", "CI=true",
                    "-v", settings.workdirMountSource() + ":" + settings.workdir(), "-w", source.toString()));
            var services = context.arguments().get("services");
            if (services != null) {
                // Suites against an environment (for example performance tests) get its URLs and can reach services published on the host.
                services.fields().forEachRemaining(e -> argv.addAll(List.of("-e", e.getKey() + "=" + e.getValue().asText())));
                if (!suite.dockerAccess() && !context.arguments().has("network")) argv.addAll(List.of("--add-host", "host.docker.internal:host-gateway"));
            }
            if (suite.dockerAccess()) {
                argv.addAll(List.of("-v", settings.dockerSocket() + ":/var/run/docker.sock",
                        "--add-host", "host.docker.internal:host-gateway", "-e", "TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal"));
                String group = socketGroup();
                if (group != null) argv.addAll(List.of("--group-add", group));
            }
            argv.add(suite.image());
            var modules = context.arguments().get("modules");
            var selected = new ArrayList<String>();
            if (modules != null) modules.forEach(m -> selected.add(m.textValue()));
            argv.addAll(selectiveCommand(suite, selected));
            return argv;
        }

        /** The suite command, narrowed to the selected modules when there are any (P19, P45). */
        static List<String> selectiveCommand(SuiteDefinition suite, List<String> selected) {
            var command = new ArrayList<>(suite.command());
            if (selected.isEmpty()) return command;
            switch (suite.selection()) {
                // Only the selected modules and what they depend on; tests of those dependencies run too, which keeps it a superset.
                case SuiteDefinition.MAVEN_MODULES -> command.addAll(List.of("-pl", String.join(",", selected), "-am"));
                // The task of each selected project instead of the task of every project; Gradle still builds what they depend on.
                case SuiteDefinition.GRADLE_PROJECTS -> {
                    String task = command.remove(command.size() - 1);
                    selected.forEach(p -> command.add(p + ":" + task));
                }
                case SuiteDefinition.NPM_WORKSPACES -> selected.forEach(d -> command.add("--workspace=" + d));
                case SuiteDefinition.PNPM_WORKSPACES -> {
                    var filters = new ArrayList<String>();
                    selected.forEach(d -> filters.addAll(List.of("--filter", "./" + d)));
                    command.addAll(1, filters);
                }
                default -> { }
            }
            return command;
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
