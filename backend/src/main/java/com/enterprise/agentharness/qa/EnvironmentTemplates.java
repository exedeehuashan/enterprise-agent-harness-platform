package com.enterprise.agentharness.qa;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Parses a project's environment templates ({@code .eahp/environments.json}): how to start the system under test
 * from a commit with Docker Compose, which compose services EAHP should reach, and which API test collections an
 * environment test run executes. The file is untrusted repository content; everything is checked here, and the
 * compose files themselves only ever run after an approver approved the exact commit.
 *
 * <pre>{"schemaVersion":1,"environments":[{"key":"env-pr","name":"PR stack","compose":["compose.yaml"],
 *   "services":{"order-api":{"service":"order-api","port":8080}},"apiTests":["order-api-smoke"],
 *   "startupTimeoutSeconds":900,"ttlMinutes":120}]}</pre>
 */
public final class EnvironmentTemplates {
    public static final String FILE = ".eahp/environments.json";
    public static final int MAX_BYTES = 64 * 1024;
    private static final ObjectMapper STRICT = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public record Service(String name, String composeService, int port) {}
    public record Template(String key, String name, List<String> compose, List<Service> services, List<String> apiTests, int startupTimeoutSeconds, int ttlMinutes) {}

    private EnvironmentTemplates() {}

    public static List<Template> parse(String json) {
        JsonNode root;
        try { root = STRICT.readTree(json); } catch (Exception ex) { throw invalid("not valid JSON"); }
        fields(root, Set.of("schemaVersion", "environments"), "file");
        if (!root.path("schemaVersion").isInt() || root.get("schemaVersion").intValue() != 1) throw invalid("schemaVersion must be 1");
        var envs = root.get("environments");
        if (envs == null || !envs.isArray() || envs.isEmpty() || envs.size() > 10) throw invalid("environments must list 1..10 templates");
        List<Template> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (JsonNode e : envs) {
            fields(e, Set.of("key", "name", "compose", "services", "apiTests", "startupTimeoutSeconds", "ttlMinutes"), "environment");
            String key = e.path("key").asText("");
            if (!key.matches("env-[a-z0-9][a-z0-9-]{0,55}") || !keys.add(key)) throw invalid("environment key '" + clip(key) + "' must look like env-pr and be unique");
            String name = e.path("name").asText("");
            if (name.isBlank() || name.length() > 120) throw invalid(key + ": name is required text up to 120 characters");
            List<String> compose = new ArrayList<>();
            if (!e.path("compose").isArray() || e.get("compose").isEmpty() || e.get("compose").size() > 5) throw invalid(key + ": compose must list 1..5 files");
            for (JsonNode f : e.get("compose")) {
                String path = f.asText("");
                if (!f.isTextual() || !path.matches("[A-Za-z0-9._/-]{1,200}\\.ya?ml") || path.startsWith("/") || path.contains("..")) throw invalid(key + ": compose files are relative .yaml/.yml paths");
                compose.add(path);
            }
            var services = new ArrayList<Service>();
            if (!e.path("services").isObject() || e.get("services").isEmpty() || e.get("services").size() > 20) throw invalid(key + ": services must map 1..20 service names");
            for (Iterator<String> it = e.get("services").fieldNames(); it.hasNext(); ) {
                String svc = it.next();
                JsonNode s = e.get("services").get(svc);
                fields(s, Set.of("service", "port"), "service");
                String composeService = s.path("service").asText("");
                if (!svc.matches("[a-z0-9][a-z0-9-]{0,59}") || !composeService.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,62}")) throw invalid(key + ": service names look like order-api");
                if (!s.path("port").isInt() || s.get("port").intValue() < 1 || s.get("port").intValue() > 65535) throw invalid(key + ": port must be 1..65535");
                services.add(new Service(svc, composeService, s.get("port").intValue()));
            }
            List<String> apiTests = new ArrayList<>();
            if (e.has("apiTests")) {
                if (!e.get("apiTests").isArray() || e.get("apiTests").size() > 20) throw invalid(key + ": apiTests must list up to 20 collections");
                for (JsonNode c : e.get("apiTests")) {
                    if (!c.isTextual() || !c.asText().matches("[a-z0-9][a-z0-9-]{1,59}")) throw invalid(key + ": apiTests are collection keys");
                    apiTests.add(c.asText());
                }
            }
            int startup = e.has("startupTimeoutSeconds") ? e.get("startupTimeoutSeconds").asInt(-1) : 900;
            if (startup < 30 || startup > 1800) throw invalid(key + ": startupTimeoutSeconds must be 30..1800");
            int ttl = e.has("ttlMinutes") ? e.get("ttlMinutes").asInt(-1) : 120;
            if (ttl < 5 || ttl > 1440) throw invalid(key + ": ttlMinutes must be 5..1440");
            result.add(new Template(key, name, List.copyOf(compose), List.copyOf(services), List.copyOf(apiTests), startup, ttl));
        }
        return result;
    }

    private static void fields(JsonNode node, Set<String> allowed, String what) {
        if (node == null || !node.isObject()) throw invalid(what + " must be an object");
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String n = it.next();
            if (!allowed.contains(n)) throw invalid("unknown " + what + " field '" + clip(n) + "'");
        }
    }
    private static String clip(String s) { return s.length() > 40 ? s.substring(0, 40) + "…" : s; }
    static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException("invalid environments file: " + reason); }
}
