package com.enterprise.agentharness.qa;

import com.enterprise.agentharness.runtime.ContainerSettings;
import com.enterprise.agentharness.qa.api.ApiCollections;
import com.enterprise.agentharness.qa.api.ApiTestRunner;
import com.enterprise.agentharness.tool.ToolContext;
import com.enterprise.agentharness.tool.ToolExecutor;
import com.enterprise.agentharness.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Per-commit environment tools (P36). QA_START_ENVIRONMENT starts a template's stack for an exact commit and leaves
 * it running for its TTL; QA_STOP_ENVIRONMENT removes one; QA_RUN_ENVIRONMENT_TESTS starts the stack, runs the
 * template's API test collections against it and always removes it again. Its evidence has the same format as other
 * QA runs, with the template key as suite key, so ingestion, the gate and reports apply unchanged.
 */
@Configuration
public class QaEnvironmentTools {

    @Bean StartTool qaStartEnvironment(QaSuiteResolver resolver, GitMirror git, EphemeralEnvironments envs, ContainerSettings settings, ObjectMapper mapper) {
        return new StartTool(resolver, git, envs, settings, mapper);
    }

    @Bean ToolExecutor qaStopEnvironment(EphemeralEnvironments envs) { return new StopTool(envs); }

    @Bean TestTool qaRunEnvironmentTests(QaSuiteResolver resolver, GitMirror git, EphemeralEnvironments envs, ContainerSettings settings, ObjectMapper mapper) {
        return new TestTool(resolver, git, envs, settings, mapper, QaApiTestTool.httpTransport());
    }

    /** {project, environment, ref?} becomes {project, environment, suite, ref, commit, template}: the template is bound as read at the commit. */
    static ObjectNode normalizeTemplate(JsonNode arguments, QaSuiteResolver resolver, GitMirror git) {
        if (arguments == null || !arguments.isObject()) throw new IllegalArgumentException("project and environment are required");
        for (var it = arguments.fieldNames(); it.hasNext(); ) if (!Set.of("project", "environment", "ref").contains(it.next())) throw new IllegalArgumentException("unknown argument");
        for (String f : List.of("project", "environment")) if (!arguments.path(f).isTextual()) throw new IllegalArgumentException(f + " is required");
        if (arguments.has("ref") && !arguments.get("ref").isTextual()) throw new IllegalArgumentException("ref must be text");
        var project = resolver.enabledProject(arguments.get("project").textValue());
        if (!project.repositorySuitesEnabled()) throw new IllegalArgumentException("this project does not allow repository-declared environments");
        String ref = arguments.has("ref") ? arguments.get("ref").textValue() : project.defaultRef();
        String commit = git.resolve(project, ref);
        var template = templates(project, commit, git).stream().filter(t -> t.key().equals(arguments.get("environment").textValue())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown environment template: " + arguments.get("environment").textValue()));
        ObjectNode normalized = JsonNodeFactory.instance.objectNode();
        normalized.put("project", project.key()).put("environment", template.key()).put("suite", template.key()).put("ref", ref).put("commit", commit);
        normalized.set("template", new ObjectMapper().valueToTree(template));
        return normalized;
    }

    static List<EnvironmentTemplates.Template> templates(QaRegistry.Project project, String commit, GitMirror git) {
        try {
            String file = git.readFile(project, commit, EnvironmentTemplates.FILE, EnvironmentTemplates.MAX_BYTES)
                    .orElseThrow(() -> new IllegalArgumentException("no " + EnvironmentTemplates.FILE + " at " + commit.substring(0, 12)));
            return EnvironmentTemplates.parse(file);
        } catch (IOException ex) {
            throw new IllegalArgumentException("could not read " + EnvironmentTemplates.FILE + " at " + commit.substring(0, 12));
        }
    }

    private static QaRegistry.Project project(QaSuiteResolver resolver, JsonNode args) {
        try {
            var project = resolver.enabledProject(args.get("project").asText());
            return project.repositorySuitesEnabled() ? project : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static EnvironmentTemplates.Template template(ObjectMapper mapper, JsonNode args) {
        return mapper.convertValue(args.get("template"), EnvironmentTemplates.Template.class);
    }

    private static Path runDir(ContainerSettings settings, ToolContext context) {
        return settings.workdir().resolve("runs").resolve(context.run().id() + "-" + context.run().attemptCount());
    }

    public static final class StartTool implements ToolExecutor {
        private final QaSuiteResolver resolver;
        private final GitMirror git;
        private final EphemeralEnvironments envs;
        private final ContainerSettings settings;
        private final ObjectMapper mapper;

        StartTool(QaSuiteResolver resolver, GitMirror git, EphemeralEnvironments envs, ContainerSettings settings, ObjectMapper mapper) {
            this.resolver = resolver; this.git = git; this.envs = envs; this.settings = settings; this.mapper = mapper;
        }

        public String name() { return "QA_START_ENVIRONMENT"; }
        public int version() { return 1; }
        public String target() { return "qa-runner"; }
        /** It builds and runs a commit's code through the host's Docker daemon. */
        public boolean requiresApproval() { return true; }
        public boolean retryable() { return false; }

        @Override public JsonNode normalize(JsonNode arguments) { return normalizeTemplate(arguments, resolver, git); }

        @Override
        public void checkTimeout(JsonNode arguments, int timeoutSeconds) {
            if (timeoutSeconds > 3600) throw new IllegalArgumentException("timeoutSeconds must be 1..3600 for environments");
        }

        @Override
        public ToolResult execute(ToolContext context) throws Exception {
            var args = context.arguments();
            var project = project(resolver, args);
            if (project == null) return ToolResult.failure("SUITE_UNAVAILABLE", "The project is no longer available.");
            Path dir = runDir(settings, context);
            Path logFile = dir.resolve("environment.log");
            try {
                Files.createDirectories(dir);
                var started = envs.start(project, template(mapper, args), args.get("commit").asText(), context.run().id(), context::stopRequested, logFile);
                var live = envs.find(started.id()).orElseThrow();
                var info = new LinkedHashMap<String, Object>();
                info.put("id", started.id()); info.put("environment", started.environmentKey()); info.put("template", args.get("environment").asText());
                info.put("commit", args.get("commit").asText()); info.put("services", started.services()); info.put("network", started.network());
                info.put("expiresAt", live.expiresAt().toString());
                String text = String.format("Environment %s (%s @ %s) is running until %s", started.environmentKey(), args.get("environment").asText(),
                        args.get("commit").asText().substring(0, 12), live.expiresAt());
                return ToolResult.success(text, new ToolResult.Artifact("environment.json", "application/json", mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(info)),
                        QaSuiteTools.tail("console.log", logFile));
            } catch (EphemeralEnvironments.StartFailed ex) {
                return ToolResult.failure(ex.code(), ex.getMessage(), QaSuiteTools.tail("console.log", logFile));
            } finally {
                QaSuiteTools.deleteTree(dir);
            }
        }
    }

    public static final class StopTool implements ToolExecutor {
        private final EphemeralEnvironments envs;
        StopTool(EphemeralEnvironments envs) { this.envs = envs; }

        public String name() { return "QA_STOP_ENVIRONMENT"; }
        public int version() { return 1; }
        public String target() { return "qa-runner"; }
        public boolean requiresApproval() { return false; }
        public boolean retryable() { return false; }

        /** {id}: an environment that is still starting or running. */
        @Override
        public JsonNode normalize(JsonNode arguments) {
            if (arguments == null || !arguments.isObject() || arguments.size() != 1 || !arguments.path("id").isTextual()) throw new IllegalArgumentException("id is required");
            UUID id;
            try { id = UUID.fromString(arguments.get("id").textValue()); } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("id must be an environment id"); }
            var live = envs.find(id).filter(e -> e.status().equals("STARTING") || e.status().equals("RUNNING"))
                    .orElseThrow(() -> new IllegalArgumentException("no running environment with this id"));
            return JsonNodeFactory.instance.objectNode().put("id", id.toString()).put("project", live.projectKey()).put("environment", live.environmentKey());
        }

        @Override
        public ToolResult execute(ToolContext context) {
            UUID id = UUID.fromString(context.arguments().get("id").asText());
            return envs.stop(id) ? ToolResult.success("Environment " + context.arguments().get("environment").asText() + " was removed.")
                    : ToolResult.success("Environment " + context.arguments().get("environment").asText() + " was already gone.");
        }
    }

    public static final class TestTool implements ToolExecutor {
        private final QaSuiteResolver resolver;
        private final GitMirror git;
        private final EphemeralEnvironments envs;
        private final ContainerSettings settings;
        private final ObjectMapper mapper;
        private final ApiTestRunner.Transport transport;

        TestTool(QaSuiteResolver resolver, GitMirror git, EphemeralEnvironments envs, ContainerSettings settings, ObjectMapper mapper, ApiTestRunner.Transport transport) {
            this.resolver = resolver; this.git = git; this.envs = envs; this.settings = settings; this.mapper = mapper; this.transport = transport;
        }

        public String name() { return "QA_RUN_ENVIRONMENT_TESTS"; }
        public int version() { return 1; }
        public String target() { return "qa-runner"; }
        public boolean requiresApproval() { return true; }
        public boolean retryable() { return false; }

        /** As for starting, plus the template's collections, which must exist in the commit's API test file. */
        @Override
        public JsonNode normalize(JsonNode arguments) {
            var normalized = normalizeTemplate(arguments, resolver, git);
            var template = mapper.convertValue(normalized.get("template"), EnvironmentTemplates.Template.class);
            if (template.apiTests().isEmpty()) throw new IllegalArgumentException("environment template " + template.key() + " lists no apiTests");
            var project = resolver.enabledProject(normalized.get("project").asText());
            var keys = collections(project, normalized.get("commit").asText()).keySet();
            for (String c : template.apiTests()) if (!keys.contains(c)) throw new IllegalArgumentException("unknown API test collection: " + c);
            return normalized;
        }

        private Map<String, JsonNode> collections(QaRegistry.Project project, String commit) {
            try {
                String file = git.readFile(project, commit, ApiCollections.FILE, ApiCollections.MAX_BYTES)
                        .orElseThrow(() -> new IllegalArgumentException("no " + ApiCollections.FILE + " at " + commit.substring(0, 12)));
                Map<String, JsonNode> result = new LinkedHashMap<>();
                for (JsonNode c : ApiCollections.parse(file)) result.put(c.get("key").asText(), c);
                return result;
            } catch (IOException ex) {
                throw new IllegalArgumentException("could not read " + ApiCollections.FILE + " at " + commit.substring(0, 12));
            }
        }

        @Override
        public void checkTimeout(JsonNode arguments, int timeoutSeconds) {
            if (timeoutSeconds > 3600) throw new IllegalArgumentException("timeoutSeconds must be 1..3600 for environment tests");
        }

        @Override
        public ToolResult execute(ToolContext context) throws Exception {
            var args = context.arguments();
            var project = project(resolver, args);
            if (project == null) return ToolResult.failure("SUITE_UNAVAILABLE", "The project is no longer available.");
            var template = template(mapper, args);
            String commit = args.get("commit").asText();
            Map<String, JsonNode> collections;
            try { collections = collections(project, commit); }
            catch (IllegalArgumentException ex) { return ToolResult.failure("SUITE_UNAVAILABLE", ex.getMessage()); }
            Path dir = runDir(settings, context);
            Path logFile = dir.resolve("environment.log");
            Instant started = Instant.now();
            EphemeralEnvironments.Started env = null;
            try {
                Files.createDirectories(dir);
                try {
                    env = envs.start(project, template, commit, context.run().id(), context::stopRequested, logFile);
                } catch (EphemeralEnvironments.StartFailed ex) {
                    return ToolResult.failure(ex.code(), ex.getMessage(), summary(args, template, null, List.of(), ex.code(), started, Instant.now()), QaSuiteTools.tail("console.log", logFile));
                }
                Map<String, URI> services = new LinkedHashMap<>();
                env.services().forEach((name, url) -> services.put(name, URI.create(url)));
                var results = new ArrayList<ApiTestRunner.Result>();
                var transcript = new StringBuilder();
                for (String key : template.apiTests()) {
                    if (context.stopRequested()) break;
                    var result = new ApiTestRunner(transport, services, context.run().id().toString()).run(collections.get(key), context.deadline(), context::stopRequested);
                    results.add(result);
                    transcript.append("== ").append(key).append(" ==\n").append(result.transcript()).append('\n');
                }
                Instant finished = Instant.now();
                long failed = results.stream().mapToLong(ApiTestRunner.Result::failed).sum();
                boolean stopped = context.stopRequested() || results.stream().anyMatch(ApiTestRunner.Result::stopped);
                boolean unreachable = results.stream().anyMatch(ApiTestRunner.Result::environmentUnreachable);
                String code = stopped ? "STOPPED" : unreachable ? "ENVIRONMENT_UNAVAILABLE" : failed > 0 ? "TESTS_FAILED" : null;
                byte[] startLog = Files.exists(logFile) ? Files.readAllBytes(logFile) : new byte[0];
                Files.write(logFile, transcript.toString().getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND, java.nio.file.StandardOpenOption.CREATE);
                long cases = results.stream().mapToLong(r -> r.cases().size()).sum();
                String text = String.format("%s/%s @ %s on a fresh environment: %d cases, %d failed in %d s (startup log %d bytes)", project.key(), template.key(),
                        commit.substring(0, 12), cases, failed, Duration.between(started, finished).toSeconds(), startLog.length);
                var artifacts = new ToolResult.Artifact[] { summary(args, template, env, results, code, started, finished),
                        new ToolResult.Artifact("junit-reports.zip", "application/zip", zip(results, finished)), QaSuiteTools.tail("console.log", logFile) };
                return code == null ? ToolResult.success(text, artifacts) : ToolResult.failure(code, text, artifacts);
            } finally {
                if (env != null) envs.stop(env.id());
                QaSuiteTools.deleteTree(dir);
            }
        }

        private ToolResult.Artifact summary(JsonNode args, EnvironmentTemplates.Template template, EphemeralEnvironments.Started env, List<ApiTestRunner.Result> results,
                String code, Instant started, Instant finished) throws IOException {
            var summary = new LinkedHashMap<String, Object>();
            summary.put("project", args.get("project").asText()); summary.put("suite", template.key()); summary.put("ref", args.get("ref").asText());
            summary.put("commit", args.get("commit").asText()); summary.put("suiteSource", "environment"); summary.put("template", template.key());
            summary.put("environment", env == null ? null : env.environmentKey()); summary.put("services", env == null ? Map.of() : env.services());
            summary.put("collections", template.apiTests()); summary.put("scope", "FULL"); summary.put("outcome", code == null ? "PASSED" : code);
            summary.put("startedAt", started.toString()); summary.put("finishedAt", finished.toString());
            summary.put("durationSeconds", Duration.between(started, finished).toSeconds());
            var cases = results.stream().flatMap(r -> r.cases().stream().map(c -> Map.entry(r.collection(), c))).toList();
            summary.put("tests", cases.size());
            summary.put("failures", cases.stream().filter(c -> c.getValue().status().equals("FAILED")).count());
            summary.put("errors", cases.stream().filter(c -> c.getValue().status().equals("ERROR")).count()); summary.put("skipped", 0);
            summary.put("failedTests", cases.stream().filter(c -> !c.getValue().status().equals("PASSED")).limit(50).map(c -> Map.of("suite", c.getKey(),
                    "name", c.getValue().id() + " " + c.getValue().name(), "kind", c.getValue().status().toLowerCase(),
                    "message", c.getValue().message() == null ? "" : c.getValue().message())).toList());
            return new ToolResult.Artifact("summary.json", "application/json", mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(summary));
        }

        private static byte[] zip(List<ApiTestRunner.Result> results, Instant timestamp) throws IOException {
            var bytes = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(bytes)) {
                for (var r : results) {
                    zip.putNextEntry(new ZipEntry("TEST-api-" + r.collection() + ".xml"));
                    zip.write(QaApiTestTool.junit(r, timestamp));
                    zip.closeEntry();
                }
            }
            return bytes.toByteArray();
        }
    }
}
