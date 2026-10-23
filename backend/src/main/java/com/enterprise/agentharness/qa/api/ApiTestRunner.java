package com.enterprise.agentharness.qa.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes one API test collection against an environment's services. Cases run in order and independently (captured
 * values are scoped to their case); steps within a case run in order and the first failing step ends the case.
 * Requests only go to the environment's base URLs: the path is appended to the base, redirects are never followed,
 * and a resolved URL on another origin is refused. Every exchange is written to a transcript with credentials
 * redacted and bodies truncated.
 */
public final class ApiTestRunner {
    static final int MAX_TRANSCRIPT_BODY = 2000;
    static final Set<String> REDACTED = Set.of("authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key");
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*(\\$?[A-Za-z][A-Za-z0-9_]*)\\s*}}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Sends one request; implementations must not follow redirects. */
    public interface Transport {
        Response send(String method, URI uri, Map<String, String> headers, byte[] body, Duration timeout) throws IOException, InterruptedException;
    }
    public record Response(int status, Map<String, List<String>> headers, byte[] body) {
        String header(String name) {
            for (var e : headers.entrySet()) if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue().get(0);
            return null;
        }
    }

    public record CaseResult(String id, String name, String status, long durationMs, String failureType, String message, String detail) {}
    public record Result(String collection, List<CaseResult> cases, String transcript, boolean stopped, boolean environmentUnreachable) {
        public long failed() { return cases.stream().filter(c -> !c.status().equals("PASSED")).count(); }
    }

    private final Transport transport;
    private final Map<String, URI> services;
    private final String runId;
    private final StringBuilder transcript = new StringBuilder();

    public ApiTestRunner(Transport transport, Map<String, URI> services, String runId) {
        this.transport = transport; this.services = services; this.runId = runId;
    }

    public Result run(JsonNode collection, Instant deadline, BooleanSupplier stop) throws InterruptedException {
        String key = collection.get("key").asText();
        Map<String, String> variables = new LinkedHashMap<>();
        collection.path("variables").fields().forEachRemaining(e -> variables.put(e.getKey(), e.getValue().asText()));
        List<CaseResult> results = new ArrayList<>();
        boolean stopped = false;
        int unreachable = 0;
        for (JsonNode tc : collection.get("cases")) {
            if (stop.getAsBoolean() || Instant.now().isAfter(deadline)) { stopped = true; break; }
            var result = runCase(collection, tc, variables, deadline);
            if ("UNREACHABLE".equals(result.failureType())) unreachable++;
            results.add(result);
        }
        return new Result(key, results, transcript.toString(), stopped, !results.isEmpty() && unreachable == results.size());
    }

    private CaseResult runCase(JsonNode collection, JsonNode tc, Map<String, String> collectionVariables, Instant deadline) throws InterruptedException {
        String id = tc.get("id").asText(), name = tc.get("name").asText();
        long started = System.nanoTime();
        transcript.append("=== ").append(id).append(' ').append(name).append('\n');
        Map<String, JsonNode> scope = new HashMap<>();
        // Collection variables may themselves use built-ins (for example a per-run customer ID), resolved once per case.
        for (var e : collectionVariables.entrySet()) {
            try { scope.put(e.getKey(), JsonNodeFactory.instance.textNode(substitute(e.getValue(), scope))); }
            catch (StepFailure f) { return result(id, name, started, "ERROR", "TemplateError", f.getMessage(), null); }
        }
        int index = 0;
        for (JsonNode step : tc.get("steps")) {
            index++;
            String label = step.has("name") ? step.get("name").asText() : "step " + index;
            String service = step.has("service") ? step.get("service").asText() : tc.has("service") ? tc.get("service").asText() : collection.get("service").asText();
            try {
                runStep(step, service, scope, deadline);
            } catch (StepFailure f) {
                transcript.append("FAILED ").append(label).append(": ").append(f.getMessage()).append('\n');
                return result(id, name, started, f.error ? "ERROR" : "FAILED", f.type, label + ": " + f.getMessage(), f.detail);
            }
        }
        transcript.append("PASSED\n");
        return result(id, name, started, "PASSED", null, null, null);
    }

    private void runStep(JsonNode step, String service, Map<String, JsonNode> scope, Instant deadline) throws StepFailure, InterruptedException {
        URI base = services.get(service);
        if (base == null) throw new StepFailure(true, "UnknownService", "service '" + service + "' is not defined in this environment", null);
        var request = step.get("request");
        String method = request.get("method").asText();
        String path = substitute(request.get("path").asText(), scope);
        URI uri = resolve(base, path);
        Map<String, String> headers = new LinkedHashMap<>();
        request.path("headers").fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText()));
        for (var e : headers.entrySet()) e.setValue(substitute(e.getValue(), scope));
        byte[] body = null;
        if (request.has("json")) {
            try { body = MAPPER.writeValueAsBytes(render(request.get("json"), scope)); } catch (IOException ex) { throw new StepFailure(true, "TemplateError", "could not encode the JSON body", null); }
            headers.putIfAbsent("Content-Type", "application/json");
        }
        headers.putIfAbsent("Accept", "application/json");
        transcript.append("> ").append(method).append(' ').append(uri).append('\n');
        headers.forEach((k, v) -> transcript.append("> ").append(k).append(": ").append(REDACTED.contains(k.toLowerCase(Locale.ROOT)) ? "[redacted]" : v).append('\n'));
        if (body != null) transcript.append(clip(new String(body, StandardCharsets.UTF_8))).append('\n');
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) throw new StepFailure(true, "Timeout", "the run deadline passed", null);
        long started = System.nanoTime();
        Response response;
        try {
            response = transport.send(method, uri, headers, body, remaining.compareTo(Duration.ofSeconds(30)) < 0 ? remaining : Duration.ofSeconds(30));
        } catch (java.net.http.HttpTimeoutException ex) {
            throw new StepFailure(true, "Timeout", "no response within the time limit", null);
        } catch (IOException ex) {
            throw new StepFailure(true, "UNREACHABLE", "could not reach " + service + " (" + ex.getClass().getSimpleName() + ")", null);
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        String text = new String(response.body(), StandardCharsets.UTF_8);
        transcript.append("< ").append(response.status()).append(" (").append(millis).append(" ms)\n");
        response.headers().forEach((k, vs) -> { if (k != null) transcript.append("< ").append(k).append(": ").append(REDACTED.contains(k.toLowerCase(Locale.ROOT)) ? "[redacted]" : String.join(", ", vs)).append('\n'); });
        if (!text.isEmpty()) transcript.append(clip(text)).append('\n');

        var expect = step.get("expect");
        var status = expect.get("status");
        boolean statusOk = false;
        if (status.isInt()) statusOk = status.intValue() == response.status();
        else for (JsonNode s : status) statusOk |= s.intValue() == response.status();
        if (!statusOk) throw new StepFailure(false, "AssertionError", "expected status " + status + " but was " + response.status(), clip(text));
        if (expect.has("maxMillis") && millis > expect.get("maxMillis").intValue()) {
            throw new StepFailure(false, "AssertionError", "expected a response within " + expect.get("maxMillis").intValue() + " ms but it took " + millis + " ms", null);
        }
        for (var e : iterable(expect.path("headers"))) {
            String actual = response.header(e.getKey());
            check(e.getValue(), actual == null ? null : JsonNodeFactory.instance.textNode(actual), "header " + e.getKey(), scope);
        }
        JsonNode json = null;
        if (expect.has("json") || step.has("capture")) {
            try { json = text.isEmpty() ? null : MAPPER.readTree(text); }
            catch (IOException ex) { if (expect.has("json")) throw new StepFailure(false, "AssertionError", "response body is not JSON", clip(text)); }
        }
        for (JsonNode c : expect.path("json")) {
            String p = c.get("path").asText();
            check(c, json == null ? null : JsonPaths.find(json, p), p, scope);
        }
        for (var e : iterable(step.path("capture"))) {
            String source = e.getValue().asText();
            JsonNode value = source.startsWith("header:")
                    ? (response.header(source.substring(7)) == null ? null : JsonNodeFactory.instance.textNode(response.header(source.substring(7))))
                    : json == null ? null : JsonPaths.find(json, source);
            if (value == null || value.isMissingNode()) throw new StepFailure(false, "AssertionError", "could not capture " + e.getKey() + " from " + source, null);
            scope.put(e.getKey(), value);
        }
    }

    private void check(JsonNode spec, JsonNode actual, String what, Map<String, JsonNode> scope) throws StepFailure {
        boolean present = actual != null && !actual.isMissingNode();
        if (spec.has("exists")) {
            if (spec.get("exists").booleanValue() != present) throw fail(what + (present ? " should not exist" : " is missing"));
            return;
        }
        if (!present) throw fail(what + " is missing");
        if (spec.has("equals")) {
            JsonNode expected = render(spec.get("equals"), scope);
            boolean equal = expected.equals(actual) || (expected.isNumber() && actual.isNumber() && expected.decimalValue().compareTo(actual.decimalValue()) == 0);
            if (!equal) throw fail("expected " + what + " = " + clip(expected.toString()) + " but was " + clip(actual.toString()));
        } else if (spec.has("matches")) {
            String value = actual.isValueNode() ? actual.asText() : actual.toString();
            if (!Pattern.compile(spec.get("matches").asText()).matcher(value).matches()) throw fail("expected " + what + " to match " + spec.get("matches").asText() + " but was " + clip(value));
        } else if (spec.has("type")) {
            String type = actual.isTextual() ? "string" : actual.isNumber() ? "number" : actual.isBoolean() ? "boolean" : actual.isObject() ? "object" : actual.isArray() ? "array" : "null";
            if (!type.equals(spec.get("type").asText())) throw fail("expected " + what + " to be " + spec.get("type").asText() + " but was " + type);
        } else if (spec.has("length")) {
            int size = actual.isArray() || actual.isObject() ? actual.size() : actual.asText().length();
            if (size != spec.get("length").intValue()) throw fail("expected " + what + " to have length " + spec.get("length").intValue() + " but was " + size);
        }
    }

    /** The base URL plus the path; anything that would leave the base's origin or path prefix is refused. */
    static URI resolve(URI base, String path) throws StepFailure {
        String prefix = base.toString().endsWith("/") ? base.toString().substring(0, base.toString().length() - 1) : base.toString();
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("\\") || path.chars().anyMatch(c -> c <= ' ' || c == 0x7f)) {
            throw new StepFailure(true, "InvalidPath", "the resolved path is not an absolute path", null);
        }
        URI uri;
        try { uri = new URI(prefix + path).normalize(); } catch (Exception ex) { throw new StepFailure(true, "InvalidPath", "the resolved path is not a valid URL", null); }
        String basePath = base.getPath() == null || base.getPath().isEmpty() ? "/" : base.getPath();
        if (!uri.getScheme().equals(base.getScheme()) || !uri.getHost().equalsIgnoreCase(base.getHost()) || uri.getPort() != base.getPort()
                || uri.getRawPath() == null || !uri.getRawPath().startsWith(basePath.endsWith("/") ? basePath : basePath + "/") && !uri.getRawPath().equals(basePath)) {
            throw new StepFailure(true, "InvalidPath", "the resolved URL leaves the environment's base URL", null);
        }
        return uri;
    }

    private String substitute(String template, Map<String, JsonNode> scope) throws StepFailure {
        Matcher m = VARIABLE.matcher(template);
        var out = new StringBuilder();
        while (m.find()) {
            JsonNode value = lookup(m.group(1), scope);
            m.appendReplacement(out, Matcher.quoteReplacement(value.isValueNode() ? value.asText() : value.toString()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Renders a JSON template: a string that is exactly one variable keeps the variable's JSON type. */
    private JsonNode render(JsonNode template, Map<String, JsonNode> scope) throws StepFailure {
        if (template.isTextual()) {
            Matcher whole = VARIABLE.matcher(template.textValue());
            if (whole.matches()) return lookup(whole.group(1), scope);
            return JsonNodeFactory.instance.textNode(substitute(template.textValue(), scope));
        }
        if (template.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : template) out.add(render(item, scope));
            return out;
        }
        if (template.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (var e : iterable(template)) out.set(e.getKey(), render(e.getValue(), scope));
            return out;
        }
        return template;
    }

    private JsonNode lookup(String name, Map<String, JsonNode> scope) throws StepFailure {
        return switch (name) {
            case "$uuid" -> JsonNodeFactory.instance.textNode(UUID.randomUUID().toString());
            case "$runId" -> JsonNodeFactory.instance.textNode(runId);
            case "$timestamp" -> JsonNodeFactory.instance.numberNode(System.currentTimeMillis());
            default -> {
                var v = scope.get(name);
                if (v == null) throw new StepFailure(true, "TemplateError", "unknown variable " + name, null);
                yield v;
            }
        };
    }

    private static Iterable<Map.Entry<String, JsonNode>> iterable(JsonNode node) { return node::fields; }
    private static String clip(String s) { return s.length() <= MAX_TRANSCRIPT_BODY ? s : s.substring(0, MAX_TRANSCRIPT_BODY) + "… (" + s.length() + " characters)"; }
    private static StepFailure fail(String message) { return new StepFailure(false, "AssertionError", message, null); }
    private static CaseResult result(String id, String name, long started, String status, String type, String message, String detail) {
        return new CaseResult(id, name, status, (System.nanoTime() - started) / 1_000_000, type, message, detail);
    }

    static final class StepFailure extends Exception {
        final boolean error; final String type; final String detail;
        StepFailure(boolean error, String type, String message, String detail) { super(message, null, false, false); this.error = error; this.type = type; this.detail = detail; }
    }
}
