package com.enterprise.agentharness.qa.impact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Gradle and JavaScript workspace module graphs from a commit (P45). Build files are repository content, so
 * they are never executed: Gradle scripts are scanned with patterns for the common forms ({@code include(":a")},
 * {@code project(":a")}, {@code projects.a} accessors), workspace manifests are parsed as JSON. Anything a scan
 * cannot see (for example projects relocated in settings) is a known limit; the selection layer falls back to a
 * full run whenever a build-wide file changes.
 */
public final class BuildGraphs {
    static final int MAX_FILES = 300;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern INCLUDE = Pattern.compile("\\binclude\\s*\\(?([^\\n)]*)");
    private static final Pattern QUOTED = Pattern.compile("[\"']([:A-Za-z0-9_.\\-]+)[\"']");
    private static final Pattern PROJECT_REF = Pattern.compile("\\bproject\\s*\\(\\s*(?:path\\s*[:=]\\s*)?[\"'](:[A-Za-z0-9_.:\\-]+)[\"']");
    private static final Pattern ACCESSOR = Pattern.compile("\\bprojects\\.([A-Za-z0-9_.]+)");

    private BuildGraphs() {}

    public interface Reader { Optional<String> read(String path) throws IOException; }

    /** The Gradle build, or null when there is no settings.gradle(.kts). */
    public static BuildGraph gradle(List<String> files, Reader reader) throws IOException {
        String settingsPath = files.contains("settings.gradle.kts") ? "settings.gradle.kts" : files.contains("settings.gradle") ? "settings.gradle" : null;
        if (settingsPath == null) return null;
        String settings = reader.read(settingsPath).orElse("");
        Map<String, String> dirByPath = new LinkedHashMap<>();
        dirByPath.put(":", "");
        Matcher inc = INCLUDE.matcher(stripComments(settings));
        while (inc.find()) {
            Matcher q = QUOTED.matcher(inc.group(1));
            while (q.find()) {
                String path = q.group(1).startsWith(":") ? q.group(1) : ":" + q.group(1);
                // ":a:b" lives in a/b, and its parents ":a" exist as projects too.
                String[] parts = path.substring(1).split(":");
                for (int i = 1; i <= parts.length; i++) {
                    String p = ":" + String.join(":", java.util.Arrays.copyOf(parts, i));
                    dirByPath.putIfAbsent(p, String.join("/", java.util.Arrays.copyOf(parts, i)));
                }
            }
        }
        Map<String, String> pathByAccessor = new HashMap<>();
        for (String path : dirByPath.keySet()) {
            if (path.equals(":")) continue;
            pathByAccessor.put(accessor(path), path);
        }
        Map<String, BuildGraph.Module> modules = new LinkedHashMap<>();
        for (var e : dirByPath.entrySet()) {
            String dir = e.getValue(), prefix = dir.isEmpty() ? "" : dir + "/";
            Set<String> deps = new HashSet<>();
            for (String script : List.of(prefix + "build.gradle.kts", prefix + "build.gradle")) {
                if (!files.contains(script)) continue;
                String text = stripComments(reader.read(script).orElse(""));
                Matcher ref = PROJECT_REF.matcher(text);
                while (ref.find()) { String d = dirByPath.get(ref.group(1)); if (d != null && !d.equals(dir)) deps.add(d); }
                Matcher acc = ACCESSOR.matcher(text);
                while (acc.find()) { String p = pathByAccessor.get(acc.group(1)); if (p != null && !dirByPath.get(p).equals(dir)) deps.add(dirByPath.get(p)); }
            }
            // The root project counts as a module only when it has its own sources.
            if (dir.isEmpty() && files.stream().noneMatch(f -> f.startsWith("src/"))) continue;
            if (!dir.isEmpty() && files.stream().noneMatch(f -> f.startsWith(prefix))) continue; // declared but absent
            modules.put(dir, new BuildGraph.Module(dir, e.getKey(), e.getKey(), Set.copyOf(deps)));
        }
        Set<String> whole = new HashSet<>(Set.of("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts", "gradle.properties"));
        return new BuildGraph("gradle", modules, whole);
    }

    /** npm, yarn or pnpm workspaces, or null when the root declares none. */
    public static BuildGraph workspaces(List<String> files, Reader reader) throws IOException {
        List<String> patterns = new ArrayList<>();
        if (files.contains("package.json")) {
            JsonNode root = json(reader.read("package.json").orElse("{}"));
            JsonNode ws = root.path("workspaces");
            if (ws.isObject()) ws = ws.path("packages");
            for (JsonNode p : ws) if (p.isTextual()) patterns.add(p.asText());
        }
        if (files.contains("pnpm-workspace.yaml")) {
            for (String line : reader.read("pnpm-workspace.yaml").orElse("").split("\n")) {
                Matcher m = Pattern.compile("^\\s*-\\s*[\"']?([^\"'#]+?)[\"']?\\s*(#.*)?$").matcher(line);
                if (m.find()) patterns.add(m.group(1).trim());
            }
        }
        if (patterns.isEmpty()) return null;
        List<Pattern> include = new ArrayList<>(), exclude = new ArrayList<>();
        for (String p : patterns) (p.startsWith("!") ? exclude : include).add(glob(p.startsWith("!") ? p.substring(1) : p));
        Map<String, String> dirByName = new LinkedHashMap<>();
        Map<String, JsonNode> manifests = new LinkedHashMap<>();
        for (String f : files) {
            if (!f.endsWith("/package.json") || f.contains("node_modules/")) continue;
            String dir = f.substring(0, f.length() - "/package.json".length());
            if (include.stream().noneMatch(p -> p.matcher(dir).matches()) || exclude.stream().anyMatch(p -> p.matcher(dir).matches())) continue;
            if (manifests.size() >= MAX_FILES) break;
            JsonNode manifest = json(reader.read(f).orElse("{}"));
            manifests.put(dir, manifest);
            if (manifest.path("name").isTextual()) dirByName.put(manifest.get("name").asText(), dir);
        }
        Map<String, BuildGraph.Module> modules = new LinkedHashMap<>();
        for (var e : manifests.entrySet()) {
            Set<String> deps = new HashSet<>();
            for (String field : List.of("dependencies", "devDependencies", "peerDependencies", "optionalDependencies")) {
                for (var it = e.getValue().path(field).fieldNames(); it.hasNext(); ) {
                    String d = dirByName.get(it.next());
                    if (d != null && !d.equals(e.getKey())) deps.add(d);
                }
            }
            String name = e.getValue().path("name").asText(e.getKey());
            modules.put(e.getKey(), new BuildGraph.Module(e.getKey(), e.getKey(), name, Set.copyOf(deps)));
        }
        Set<String> whole = new HashSet<>(Set.of("package.json", "package-lock.json", "npm-shrinkwrap.json", "yarn.lock", "pnpm-lock.yaml",
                "pnpm-workspace.yaml", ".npmrc", ".yarnrc.yml", "tsconfig.json", "tsconfig.base.json"));
        return new BuildGraph("npm", modules, whole);
    }

    /** The type-safe accessor name Gradle generates for a project path: ":core-api:web" → "coreApi.web". */
    static String accessor(String path) {
        List<String> parts = new ArrayList<>();
        for (String part : path.substring(1).split(":")) {
            StringBuilder b = new StringBuilder();
            boolean upper = false;
            for (char c : part.toCharArray()) {
                if (c == '-' || c == '_' || c == '.') { upper = true; continue; }
                b.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
            parts.add(b.toString());
        }
        return String.join(".", parts);
    }

    /** A workspace glob: "*" matches one path segment, "**" any number. */
    static Pattern glob(String pattern) {
        String p = pattern.replaceAll("^\\./", "").replaceAll("/+$", "");
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '*' && i + 1 < p.length() && p.charAt(i + 1) == '*') { regex.append(".*"); i++; }
            else if (c == '*') regex.append("[^/]*");
            else regex.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(regex.toString());
    }

    private static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
    private static JsonNode json(String text) {
        try { return JSON.readTree(text); } catch (IOException ex) { return JSON.createObjectNode(); }
    }
}
