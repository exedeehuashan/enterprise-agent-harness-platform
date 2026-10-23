package com.enterprise.agentharness.qa;

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
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Component;

/**
 * 方案 4 API testing (Sprint 1 D-001): runs a repository-declared API test collection at an exact commit against a
 * registered test environment and returns JUnit XML, a summary and the HTTP transcript, in the same evidence format
 * as suite runs, so ingestion, history, triage, the release gate and reports apply unchanged. The run's suite key is
 * the collection key. The collection and the environment's base URLs are bound into the arguments, so an approval
 * or audit always shows exactly what was sent where.
 */
@Component
public class QaApiTestTool implements ToolExecutor {
    static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final QaSuiteResolver resolver;
    private final GitMirror git;
    private final QaEnvironments environments;
    private final ObjectMapper mapper;
    private final ApiTestRunner.Transport transport;

    @org.springframework.beans.factory.annotation.Autowired
    public QaApiTestTool(QaSuiteResolver resolver, GitMirror git, QaEnvironments environments, ObjectMapper mapper) {
        this(resolver, git, environments, mapper, httpTransport());
    }

    QaApiTestTool(QaSuiteResolver resolver, GitMirror git, QaEnvironments environments, ObjectMapper mapper, ApiTestRunner.Transport transport) {
        this.resolver = resolver; this.git = git; this.environments = environments; this.mapper = mapper; this.transport = transport;
    }

    public String name() { return "QA_RUN_API_TESTS"; }
    public int version() { return 1; }
    public String target() { return "qa-runner"; }
    public boolean requiresApproval() { return false; }
    /** Test cases may create data in the environment; repeating them after a lost worker is left to a person. */
    public boolean retryable() { return false; }

    /** {project, collection, environment, ref?} becomes {project, suite, collection, environment, ref, commit, definition, services}. */
    @Override
    public JsonNode normalize(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) throw new IllegalArgumentException("project, collection and environment are required");
        for (var it = arguments.fieldNames(); it.hasNext(); ) if (!Set.of("project", "collection", "environment", "ref").contains(it.next())) throw new IllegalArgumentException("unknown argument");
        for (String f : List.of("project", "collection", "environment")) if (!arguments.path(f).isTextual()) throw new IllegalArgumentException(f + " is required");
        if (arguments.has("ref") && !arguments.get("ref").isTextual()) throw new IllegalArgumentException("ref must be text");
        var project = resolver.enabledProject(arguments.get("project").textValue());
        String ref = arguments.has("ref") ? arguments.get("ref").textValue() : project.defaultRef();
        String commit = git.resolve(project, ref);
        if (!project.repositorySuitesEnabled()) throw new IllegalArgumentException("this project does not allow repository-declared tests");
        String file;
        try {
            file = git.readFile(project, commit, ApiCollections.FILE, ApiCollections.MAX_BYTES)
                    .orElseThrow(() -> new IllegalArgumentException("no " + ApiCollections.FILE + " at " + commit.substring(0, 12)));
        } catch (IOException ex) {
            throw new IllegalArgumentException("could not read " + ApiCollections.FILE + " at " + commit.substring(0, 12));
        }
        String key = arguments.get("collection").textValue();
        JsonNode collection = ApiCollections.parse(file).stream().filter(c -> c.get("key").asText().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown API test collection: " + key));
        var environment = environments.find(project.key(), arguments.get("environment").textValue()).filter(QaEnvironments.Environment::enabled)
                .orElseThrow(() -> new IllegalArgumentException("unknown or disabled environment"));
        ObjectNode normalized = JsonNodeFactory.instance.objectNode();
        normalized.put("project", project.key()).put("suite", key).put("collection", key).put("environment", environment.key())
                .put("ref", ref).put("commit", commit);
        normalized.set("definition", collection);
        normalized.set("services", mapper.valueToTree(environment.services()));
        return normalized;
    }

    public record CollectionInfo(String key, String name, String description, String service, int cases) {}
    public record Catalog(String project, String ref, String commit, String file, String error, List<CollectionInfo> collections,
            List<QaEnvironments.Environment> environments) {}

    /** The API test collections at a ref ("found", "absent", "disabled" or "invalid" file) and the project's environments. */
    public Catalog catalog(String projectKey, String ref) {
        var project = resolver.enabledProject(projectKey);
        String effective = ref == null ? project.defaultRef() : ref;
        String commit = git.resolve(project, effective);
        var envs = environments.list(project.key());
        if (!project.repositorySuitesEnabled()) return new Catalog(project.key(), effective, commit, "disabled", null, List.of(), envs);
        try {
            var text = git.readFile(project, commit, ApiCollections.FILE, ApiCollections.MAX_BYTES);
            if (text.isEmpty()) return new Catalog(project.key(), effective, commit, "absent", null, List.of(), envs);
            var collections = ApiCollections.parse(text.get()).stream().map(c -> new CollectionInfo(c.get("key").asText(), c.get("name").asText(),
                    c.has("description") ? c.get("description").asText() : null, c.get("service").asText(), c.get("cases").size())).toList();
            return new Catalog(project.key(), effective, commit, "found", null, collections, envs);
        } catch (IOException ex) {
            return new Catalog(project.key(), effective, commit, "invalid", "could not read " + ApiCollections.FILE, List.of(), envs);
        } catch (IllegalArgumentException ex) {
            return new Catalog(project.key(), effective, commit, "invalid", ex.getMessage(), List.of(), envs);
        }
    }

    @Override
    public void checkTimeout(JsonNode arguments, int timeoutSeconds) {
        if (timeoutSeconds > 1800) throw new IllegalArgumentException("timeoutSeconds must be 1..1800 for API tests");
    }

    @Override
    public ToolResult execute(ToolContext context) throws Exception {
        var args = context.arguments();
        String project = args.get("project").asText(), key = args.get("collection").asText(), commit = args.get("commit").asText();
        Map<String, String> bound = mapper.convertValue(args.get("services"), new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, String>>() {});
        var environment = environments.find(project, args.get("environment").asText()).orElse(null);
        if (environment == null || !environment.enabled() || !environment.services().equals(bound)) {
            // The approved target changed after the run was created; never send requests somewhere it did not show.
            return ToolResult.failure("ENVIRONMENT_CHANGED", "The environment was changed, disabled or removed after this run was created.");
        }
        Map<String, URI> services = new LinkedHashMap<>();
        bound.forEach((name, url) -> services.put(name, URI.create(url)));
        Instant started = Instant.now();
        var result = new ApiTestRunner(transport, services, context.run().id().toString()).run(args.get("definition"), context.deadline(), context::stopRequested);
        Instant finished = Instant.now();
        long failed = result.failed();
        String code = result.stopped() ? "STOPPED" : result.environmentUnreachable() ? "ENVIRONMENT_UNAVAILABLE" : failed > 0 ? "TESTS_FAILED" : null;

        var summary = new LinkedHashMap<String, Object>();
        summary.put("project", project); summary.put("suite", key); summary.put("ref", args.get("ref").asText()); summary.put("commit", commit);
        summary.put("suiteSource", "api-tests"); summary.put("environment", environment.key()); summary.put("services", bound);
        summary.put("scope", "FULL"); summary.put("outcome", code == null ? "PASSED" : code);
        summary.put("startedAt", started.toString()); summary.put("finishedAt", finished.toString());
        summary.put("durationSeconds", Duration.between(started, finished).toSeconds());
        summary.put("tests", result.cases().size()); summary.put("failures", result.cases().stream().filter(c -> c.status().equals("FAILED")).count());
        summary.put("errors", result.cases().stream().filter(c -> c.status().equals("ERROR")).count()); summary.put("skipped", 0);
        summary.put("failedTests", result.cases().stream().filter(c -> !c.status().equals("PASSED")).limit(50)
                .map(c -> Map.of("suite", key, "name", c.id() + " " + c.name(), "kind", c.status().toLowerCase(), "message", c.message() == null ? "" : c.message())).toList());
        var artifacts = new ToolResult.Artifact[] {
                new ToolResult.Artifact("summary.json", "application/json", mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(summary)),
                new ToolResult.Artifact("junit-reports.zip", "application/zip", zip("TEST-api-" + key + ".xml", junit(result, finished))),
                new ToolResult.Artifact("console.log", "text/plain", result.transcript().getBytes(StandardCharsets.UTF_8)) };
        String text = String.format("%s/%s @ %s on %s: %d cases, %d failed in %d s", project, key, commit.substring(0, 12), environment.key(),
                result.cases().size(), failed, Duration.between(started, finished).toSeconds());
        return code == null ? ToolResult.success(text, artifacts) : ToolResult.failure(code, text, artifacts);
    }

    /** JUnit XML: one testsuite per collection, one testcase per case (classname = collection key). */
    static byte[] junit(ApiTestRunner.Result result, Instant timestamp) {
        long failures = result.cases().stream().filter(c -> c.status().equals("FAILED")).count();
        long errors = result.cases().stream().filter(c -> c.status().equals("ERROR")).count();
        var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<testsuite name=\"").append(esc(result.collection())).append("\" tests=\"").append(result.cases().size()).append("\" failures=\"").append(failures)
                .append("\" errors=\"").append(errors).append("\" skipped=\"0\" timestamp=\"").append(timestamp).append("\">\n");
        for (var c : result.cases()) {
            xml.append("  <testcase classname=\"").append(esc(result.collection())).append("\" name=\"").append(esc(c.id() + " " + c.name()))
                    .append("\" time=\"").append(String.format(java.util.Locale.ROOT, "%.3f", c.durationMs() / 1000.0)).append('"');
            if (c.status().equals("PASSED")) { xml.append("/>\n"); continue; }
            String tag = c.status().equals("ERROR") ? "error" : "failure";
            xml.append(">\n    <").append(tag).append(" type=\"").append(esc(c.failureType())).append("\" message=\"").append(esc(c.message())).append("\">")
                    .append(esc(c.detail() == null ? c.message() : c.detail())).append("</").append(tag).append(">\n  </testcase>\n");
        }
        return xml.append("</testsuite>\n").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String esc(String s) {
        if (s == null) return "";
        var out = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;"); case '<' -> out.append("&lt;"); case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;"); case '\'' -> out.append("&apos;");
                default -> { if (c >= 0x20 || c == '\n' || c == '\t') out.append(c); }
            }
        }
        return out.toString();
    }

    private static byte[] zip(String name, byte[] content) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) { zip.putNextEntry(new ZipEntry(name)); zip.write(content); zip.closeEntry(); }
        return bytes.toByteArray();
    }

    /** java.net.http without redirects; response bodies are read up to {@link #MAX_RESPONSE_BYTES}. */
    static ApiTestRunner.Transport httpTransport() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
        return (method, uri, headers, body, timeout) -> {
            var builder = HttpRequest.newBuilder(uri).timeout(timeout)
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(builder::header);
            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] content;
            try (InputStream in = response.body()) { content = in.readNBytes(MAX_RESPONSE_BYTES); }
            return new ApiTestRunner.Response(response.statusCode(), response.headers().map(), content);
        };
    }
}
