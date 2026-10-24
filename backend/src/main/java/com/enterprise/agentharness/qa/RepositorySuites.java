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
 *   "reportDirs":["target/surefire-reports"],"network":"egress","dockerAccess":false,"timeoutSeconds":1800,
 *   "selection":"maven-modules"}]}</pre>
 * {@code selection} is optional: {@code maven-modules} lets pull request runs build and test only the affected modules;
 * {@code paths} (with a {@code paths} list of directory prefixes such as {@code "frontend/order-web/"}) runs the suite
 * on a pull request only when it changes a file under one of them. {@code results} is {@code junit} (default) or
 * {@code exit-code} for checks that write no JUnit reports (then {@code reportDirs} is empty). {@code environment} maps
 * variable names to service names; such a suite runs against a registered environment whose URLs are passed in those
 * variables (for example performance tests). {@code coverage} lists
 * JaCoCo or Cobertura XML report paths (matched as path suffixes, for example {@code target/site/jacoco/jacoco.xml}).
 * {@code performance} names the k6 {@code --summary-export} file the command writes (a relative .json path), whose
 * latency, throughput and error rate EAHP records for trends and the release gate.
 */
public final class RepositorySuites {
    public static final int MAX_BYTES = 64 * 1024;
    private static final ObjectMapper STRICT = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    /** Variables the runner sets itself; a suite cannot redirect them to an environment URL. */
    static final Set<String> RESERVED_VARIABLES = Set.of("HOME", "PATH", "CI", "USER", "SHELL", "LD_PRELOAD", "LD_LIBRARY_PATH", "JAVA_TOOL_OPTIONS", "NODE_OPTIONS");
    private static final Set<String> FILE_FIELDS = Set.of("schemaVersion", "suites");
    private static final Set<String> SUITE_FIELDS = Set.of("key", "name", "description", "image", "command", "reportDirs", "network", "dockerAccess", "timeoutSeconds", "selection", "paths", "results", "coverage", "environment", "performance");
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
            String selection = s.has("selection") ? text(s, "selection", 20, true) : null;
            if (selection != null && !Set.of(SuiteDefinition.MAVEN_MODULES, SuiteDefinition.PATHS, SuiteDefinition.GRADLE_PROJECTS,
                    SuiteDefinition.NPM_WORKSPACES, SuiteDefinition.PNPM_WORKSPACES).contains(selection)) {
                throw invalid(key + ": selection must be maven-modules, gradle-projects, npm-workspaces, pnpm-workspaces or paths");
            }
            if (SuiteDefinition.MAVEN_MODULES.equals(selection) && !command.get(0).equals("mvn")) throw invalid(key + ": maven-modules selection needs an mvn command");
            if (SuiteDefinition.GRADLE_PROJECTS.equals(selection) && (!Set.of("gradle", "./gradlew", "gradlew").contains(command.get(0)) || command.size() < 2
                    || !command.get(command.size() - 1).matches("[A-Za-z][A-Za-z0-9]{0,59}"))) {
                throw invalid(key + ": gradle-projects selection needs a gradle or ./gradlew command ending with the task name, for example [\"./gradlew\", \"test\"]");
            }
            if (SuiteDefinition.NPM_WORKSPACES.equals(selection) && !command.get(0).equals("npm")) throw invalid(key + ": npm-workspaces selection needs an npm command");
            if (SuiteDefinition.PNPM_WORKSPACES.equals(selection) && !command.get(0).equals("pnpm")) throw invalid(key + ": pnpm-workspaces selection needs a pnpm command");
            List<String> paths = s.has("paths") ? strings(s.get("paths"), 20, 200, key + ": paths") : List.of();
            if (SuiteDefinition.PATHS.equals(selection) != !paths.isEmpty()) throw invalid(key + ": paths is required with, and only allowed with, selection paths");
            for (String path : paths) {
                if (!path.matches("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*/?") || path.contains("..")) throw invalid(key + ": path '" + path + "' must be a relative directory or file");
            }
            String results = s.has("results") ? text(s, "results", 20, true) : null;
            if (results != null && !results.equals("junit") && !results.equals(SuiteDefinition.EXIT_CODE)) throw invalid(key + ": results must be junit or exit-code");
            if (SuiteDefinition.EXIT_CODE.equals(results) && !reportDirs.isEmpty()) throw invalid(key + ": exit-code suites have no reportDirs");
            if (!SuiteDefinition.EXIT_CODE.equals(results) && reportDirs.isEmpty()) throw invalid(key + ": reportDirs must list at least one directory");
            List<String> coverage = s.has("coverage") ? strings(s.get("coverage"), 10, 200, key + ": coverage") : List.of();
            for (String c : coverage) {
                if (c.startsWith("/") || c.contains("..") || c.contains("\\") || !c.matches("[A-Za-z0-9._/-]+\\.xml")) throw invalid(key + ": coverage report '" + c + "' must be a relative .xml path");
            }
            String performance = null;
            if (s.has("performance")) {
                performance = s.get("performance").asText("");
                if (!s.get("performance").isTextual() || performance.startsWith("/") || performance.contains("..") || !performance.matches("[A-Za-z0-9._/-]{1,200}\\.json")) {
                    throw invalid(key + ": performance must be a relative .json path (a k6 --summary-export file)");
                }
            }
            var environment = new java.util.TreeMap<String, String>();
            if (s.has("environment")) {
                var env = s.get("environment");
                if (!env.isObject() || env.isEmpty() || env.size() > 20) throw invalid(key + ": environment must map 1..20 variable names to service names");
                for (var it = env.fields(); it.hasNext(); ) {
                    var e = it.next();
                    if (!e.getKey().matches("[A-Z][A-Z0-9_]{0,59}") || RESERVED_VARIABLES.contains(e.getKey()) || e.getKey().startsWith("MAVEN_") || e.getKey().startsWith("TESTCONTAINERS_")) {
                        throw invalid(key + ": environment variable '" + e.getKey() + "' is not allowed");
                    }
                    if (!e.getValue().isTextual() || !e.getValue().textValue().matches("[a-z0-9][a-z0-9-]{0,59}")) throw invalid(key + ": environment values are service names such as order-api");
                    environment.put(e.getKey(), e.getValue().textValue());
                }
                if (!network.equals("egress")) throw invalid(key + ": a suite that uses an environment needs network egress");
                if (selection != null) throw invalid(key + ": a suite that uses an environment cannot declare selection");
            }
            result.add(new SuiteDefinition(SuiteDefinition.REPOSITORY, key, text(s, "name", 120, true), s.has("description") ? text(s, "description", 1000, false) : null,
                    image, command, reportDirs, network, s.get("dockerAccess").booleanValue(), s.get("timeoutSeconds").intValue(), selection, paths,
                    SuiteDefinition.EXIT_CODE.equals(results) ? results : null, coverage, environment, performance));
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
