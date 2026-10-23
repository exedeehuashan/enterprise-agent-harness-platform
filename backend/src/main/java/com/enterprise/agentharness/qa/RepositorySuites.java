package com.enterprise.agentharness.qa;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses a project's suites file (default {@code .eahp/qa-suites.json}). The file is untrusted input from the
 * repository: every field is checked, unknown fields are rejected, and policy (images, Docker access, network,
 * time) is enforced separately by {@link QaSuiteResolver}. Format:
 *
 * <pre>{"schemaVersion":1,"suites":[{"key":"backend-unit","name":"...","description":"...",
 *   "image":"maven:3.9.11-eclipse-temurin-17","command":["mvn","-B","verify"],
 *   "reportDirs":["target/surefire-reports"],"network":"egress","dockerAccess":false,"timeoutSeconds":1800}]}</pre>
 */
public final class RepositorySuites {
    public static final int MAX_BYTES = 64 * 1024;
    private static final ObjectMapper STRICT = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> FILE_FIELDS = Set.of("schemaVersion", "suites");
    private static final Set<String> SUITE_FIELDS = Set.of("key", "name", "description", "image", "command", "reportDirs", "network", "dockerAccess", "timeoutSeconds");
    private RepositorySuites() {}

    public static List<SuiteDefinition> parse(String json) {
        JsonNode root;
        try { root = STRICT.readTree(json); } catch (Exception ex) { throw invalid("not valid JSON"); }
        fields(root, FILE_FIELDS, "file");
        if (!root.path("schemaVersion").isInt() || root.get("schemaVersion").intValue() != 1) throw invalid("schemaVersion must be 1");
        var suites = root.get("suites");
        if (suites == null || !suites.isArray() || suites.isEmpty() || suites.size() > 20) throw invalid("suites must list 1..20 suites");
        List<SuiteDefinition> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (JsonNode s : suites) {
            fields(s, SUITE_FIELDS, "suite");
            String key = text(s, "key", 60, true);
            if (!key.matches("[a-z0-9][a-z0-9-]{1,59}") || !keys.add(key)) throw invalid("suite key '" + key + "' is invalid or repeated");
            String image = text(s, "image", 200, true);
            if (!image.matches("[a-z0-9][a-z0-9._/-]*:[A-Za-z0-9._-]+")) throw invalid(key + ": image must be name:tag");
            String network = text(s, "network", 10, true);
            if (!network.equals("none") && !network.equals("egress")) throw invalid(key + ": network must be none or egress");
            if (!s.path("dockerAccess").isBoolean()) throw invalid(key + ": dockerAccess must be true or false");
            if (!s.path("timeoutSeconds").isInt() || s.get("timeoutSeconds").intValue() < 30 || s.get("timeoutSeconds").intValue() > 3600) {
                throw invalid(key + ": timeoutSeconds must be 30..3600");
            }
            List<String> command = strings(s.get("command"), 40, 500, key + ": command");
            if (command.isEmpty()) throw invalid(key + ": command must not be empty");
            List<String> reportDirs = strings(s.get("reportDirs"), 10, 200, key + ": reportDirs");
            for (String dir : reportDirs) {
                if (dir.startsWith("/") || dir.contains("..") || dir.contains("\\") || !dir.matches("[A-Za-z0-9._/-]+")) throw invalid(key + ": report directory '" + dir + "' must be a relative path");
            }
            result.add(new SuiteDefinition(SuiteDefinition.REPOSITORY, key, text(s, "name", 120, true), s.has("description") ? text(s, "description", 1000, false) : null,
                    image, command, reportDirs, network, s.get("dockerAccess").booleanValue(), s.get("timeoutSeconds").intValue()));
        }
        return result;
    }

    private static void fields(JsonNode node, Set<String> allowed, String what) {
        if (node == null || !node.isObject()) throw invalid(what + " must be an object");
        var names = node.fieldNames();
        while (names.hasNext()) { String name = names.next(); if (!allowed.contains(name)) throw invalid("unknown " + what + " field '" + clip(name) + "'"); }
    }
    private static String text(JsonNode node, String field, int max, boolean required) {
        JsonNode v = node.get(field);
        if (v == null) { if (required) throw invalid(field + " is required"); return null; }
        if (!v.isTextual() || v.textValue().isBlank() || v.textValue().length() > max || v.textValue().chars().anyMatch(Character::isISOControl)) {
            throw invalid(field + " must be non-empty text up to " + max + " characters");
        }
        return v.textValue();
    }
    private static List<String> strings(JsonNode array, int maxItems, int maxLength, String what) {
        if (array == null || !array.isArray() || array.size() > maxItems) throw invalid(what + " must be an array of up to " + maxItems + " strings");
        List<String> values = new ArrayList<>();
        for (JsonNode v : array) {
            if (!v.isTextual() || v.textValue().isEmpty() || v.textValue().length() > maxLength || v.textValue().chars().anyMatch(Character::isISOControl)) {
                throw invalid(what + " entries must be non-empty text up to " + maxLength + " characters");
            }
            values.add(v.textValue());
        }
        return List.copyOf(values);
    }
    private static String clip(String s) { return s.length() > 40 ? s.substring(0, 40) + "…" : s; }
    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException("invalid suites file: " + reason); }
}
