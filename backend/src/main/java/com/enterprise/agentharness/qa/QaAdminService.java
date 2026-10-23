package com.enterprise.agentharness.qa;

import com.enterprise.agentharness.governance.AuditService;
import com.enterprise.agentharness.run.RunException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administration of the QA registry: projects and their policy, registry suites, pull request triggers and release
 * gates. Approver-only at the URL and at this service, strictly validated with the same rules as repository suite
 * files, and every change is audited with its before and after state.
 */
@Service
@Transactional
public class QaAdminService {
    private static final String KEY = "[a-z0-9][a-z0-9-]{1,59}";
    private static final Set<String> PROJECT_FIELDS = Set.of("key", "name", "repositoryUrl", "defaultRef", "enabled", "repositorySuitesEnabled",
            "suitesFile", "allowedImages", "allowDockerAccess", "allowNetworkEgress", "maxTimeoutSeconds");
    private static final Set<String> SUITE_FIELDS = Set.of("name", "description", "image", "command", "reportDirs", "network", "dockerAccess",
            "maxTimeoutSeconds", "enabled");
    private final JdbcTemplate jdbc;
    private final QaRegistry registry;
    private final ObjectMapper mapper;

    public QaAdminService(JdbcTemplate jdbc, QaRegistry registry, ObjectMapper mapper) { this.jdbc = jdbc; this.registry = registry; this.mapper = mapper; }

    public record Trigger(String suite, boolean enabled) {}
    public record ProjectAdmin(QaRegistry.Project project, List<Trigger> triggers, List<String> gateSuites) {}
    public record Event(long id, String actor, String action, String projectKey, String subject, JsonNode before, JsonNode after, Instant occurredAt) {}

    @PreAuthorize("hasRole('APPROVER')")
    @Transactional(readOnly = true)
    public ProjectAdmin get(String key) {
        var project = registry.project(key).orElseThrow(() -> notFound(key));
        return new ProjectAdmin(project, triggers(key), gate(key));
    }

    @PreAuthorize("hasRole('APPROVER')")
    public ProjectAdmin create(JsonNode body) {
        fields(body, PROJECT_FIELDS);
        String key = text(body, "key", true, 60);
        if (!key.matches(KEY)) throw bad("key must be lowercase letters, digits and hyphens (2-60)");
        if (registry.project(key).isPresent()) throw new RunException(HttpStatus.CONFLICT, "Project already exists: " + key);
        var values = projectValues(body, null);
        jdbc.update("""
                INSERT INTO qa_projects(key,name,repository_url,default_ref,enabled,repository_suites_enabled,suites_file,allowed_images,
                    allow_docker_access,allow_network_egress,max_timeout_seconds) VALUES (?,?,?,?,?,?,?,?::jsonb,?,?,?)
                """, key, values.get("name"), values.get("repositoryUrl"), values.get("defaultRef"), values.get("enabled"),
                values.get("repositorySuitesEnabled"), values.get("suitesFile"), json(values.get("allowedImages")), values.get("allowDockerAccess"),
                values.get("allowNetworkEgress"), values.get("maxTimeoutSeconds"));
        audit("PROJECT_CREATED", key, null, null, snapshot(key));
        return get(key);
    }

    @PreAuthorize("hasRole('APPROVER')")
    public ProjectAdmin update(String key, JsonNode body) {
        fields(body, PROJECT_FIELDS);
        if (body.has("key") && !body.get("key").asText().equals(key)) throw bad("key cannot be changed");
        var current = registry.project(key).orElseThrow(() -> notFound(key));
        var before = snapshot(key);
        var values = projectValues(body, current);
        jdbc.update("""
                UPDATE qa_projects SET name=?,repository_url=?,default_ref=?,enabled=?,repository_suites_enabled=?,suites_file=?,allowed_images=?::jsonb,
                    allow_docker_access=?,allow_network_egress=?,max_timeout_seconds=? WHERE key=?
                """, values.get("name"), values.get("repositoryUrl"), values.get("defaultRef"), values.get("enabled"), values.get("repositorySuitesEnabled"),
                values.get("suitesFile"), json(values.get("allowedImages")), values.get("allowDockerAccess"), values.get("allowNetworkEgress"),
                values.get("maxTimeoutSeconds"), key);
        var updated = registry.project(key).orElseThrow();
        for (var s : updated.suites()) {
            if (!s.enabled()) continue;
            try { QaSuiteResolver.checkPolicy(updated, SuiteDefinition.fromRegistry(s)); }
            catch (IllegalArgumentException ex) { throw bad("registry suite " + s.key() + " would violate the new policy: " + ex.getMessage()); }
        }
        audit("PROJECT_UPDATED", key, null, before, snapshot(key));
        return get(key);
    }

    /** Creates or replaces a registry suite; it must satisfy the project's policy. Disable with "enabled": false. */
    @PreAuthorize("hasRole('APPROVER')")
    public ProjectAdmin putSuite(String key, String suiteKey, JsonNode body) {
        fields(body, SUITE_FIELDS);
        if (!suiteKey.matches(KEY)) throw bad("suite key must be lowercase letters, digits and hyphens (2-60)");
        var project = registry.project(key).orElseThrow(() -> notFound(key));
        // Same validation as a repository suites file, so both sources follow one rule set.
        ObjectNode asFileSuite = JsonNodeFactory.instance.objectNode().put("key", suiteKey);
        for (String f : List.of("name", "description", "image", "command", "reportDirs", "network", "dockerAccess")) if (body.has(f)) asFileSuite.set(f, body.get(f));
        if (body.has("maxTimeoutSeconds")) asFileSuite.set("timeoutSeconds", body.get("maxTimeoutSeconds"));
        ObjectNode file = JsonNodeFactory.instance.objectNode().put("schemaVersion", 1);
        file.putArray("suites").add(asFileSuite);
        SuiteDefinition suite;
        try { suite = RepositorySuites.parse(file.toString()).get(0); }
        catch (IllegalArgumentException ex) { throw bad(ex.getMessage().replace("invalid suites file: ", "")); }
        boolean enabled = !body.has("enabled") || bool(body, "enabled");
        if (enabled) {
            try { QaSuiteResolver.checkPolicy(project, suite); } catch (IllegalArgumentException ex) { throw bad(ex.getMessage()); }
        }
        var before = registry.suite(key, suiteKey).map(this::toJson).orElse(null);
        jdbc.update("""
                INSERT INTO qa_test_suites(project_key,key,name,description,runner_image,command,report_dirs,network,docker_access,max_timeout_seconds,enabled)
                VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?)
                ON CONFLICT (project_key,key) DO UPDATE SET name=EXCLUDED.name,description=EXCLUDED.description,runner_image=EXCLUDED.runner_image,
                    command=EXCLUDED.command,report_dirs=EXCLUDED.report_dirs,network=EXCLUDED.network,docker_access=EXCLUDED.docker_access,
                    max_timeout_seconds=EXCLUDED.max_timeout_seconds,enabled=EXCLUDED.enabled
                """, key, suiteKey, suite.name(), suite.description(), suite.image(), json(suite.command()), json(suite.reportDirs()), suite.network(),
                suite.dockerAccess(), suite.maxTimeoutSeconds(), enabled);
        audit(before == null ? "SUITE_CREATED" : "SUITE_UPDATED", key, suiteKey, before, registry.suite(key, suiteKey).map(this::toJson).orElse(null));
        return get(key);
    }

    @PreAuthorize("hasRole('APPROVER')")
    public ProjectAdmin putTrigger(String key, String suiteKey, JsonNode body) {
        fields(body, Set.of("enabled"));
        if (!suiteKey.matches(KEY)) throw bad("suite key must be lowercase letters, digits and hyphens (2-60)");
        registry.project(key).orElseThrow(() -> notFound(key));
        boolean enabled = bool(body, "enabled");
        var before = jdbc.queryForList("SELECT enabled FROM qa_pr_triggers WHERE project_key=? AND suite_key=?", Boolean.class, key, suiteKey);
        jdbc.update("INSERT INTO qa_pr_triggers VALUES (?,?,?) ON CONFLICT (project_key,suite_key) DO UPDATE SET enabled=EXCLUDED.enabled", key, suiteKey, enabled);
        audit("TRIGGER_SET", key, suiteKey, before.isEmpty() ? null : flag(before.get(0)), flag(enabled));
        return get(key);
    }

    @PreAuthorize("hasRole('APPROVER')")
    public ProjectAdmin putGate(String key, JsonNode body) {
        fields(body, Set.of("requiredSuites"));
        registry.project(key).orElseThrow(() -> notFound(key));
        var suites = strings(body, "requiredSuites", 20);
        if (suites.isEmpty()) throw bad("requiredSuites must name at least one suite; delete the gate to remove it");
        for (String s : suites) if (!s.matches(KEY)) throw bad("invalid suite key: " + s);
        var before = gate(key);
        jdbc.update("INSERT INTO qa_quality_gates VALUES (?, ?::jsonb) ON CONFLICT (project_key) DO UPDATE SET required_suites=EXCLUDED.required_suites", key, json(suites));
        audit("GATE_SET", key, null, before.isEmpty() ? null : mapper.valueToTree(before), mapper.valueToTree(suites));
        return get(key);
    }

    @PreAuthorize("hasRole('APPROVER')")
    public ProjectAdmin deleteGate(String key) {
        registry.project(key).orElseThrow(() -> notFound(key));
        var before = gate(key);
        if (!before.isEmpty()) {
            jdbc.update("DELETE FROM qa_quality_gates WHERE project_key=?", key);
            audit("GATE_REMOVED", key, null, mapper.valueToTree(before), null);
        }
        return get(key);
    }

    @PreAuthorize("hasRole('APPROVER')")
    @Transactional(readOnly = true)
    public List<Event> events(String key, int limit) {
        return jdbc.query("SELECT * FROM qa_admin_events WHERE (?::text IS NULL OR project_key=?) ORDER BY id DESC LIMIT ?", (r, i) -> {
            try {
                return new Event(r.getLong("id"), r.getString("actor"), r.getString("action"), r.getString("project_key"), r.getString("subject"),
                        r.getString("before_state") == null ? null : mapper.readTree(r.getString("before_state")),
                        r.getString("after_state") == null ? null : mapper.readTree(r.getString("after_state")), r.getTimestamp("occurred_at").toInstant());
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }, key, key, limit);
    }

    private Map<String, Object> projectValues(JsonNode body, QaRegistry.Project current) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("name", body.has("name") ? text(body, "name", true, 120) : current == null ? required("name") : current.name());
        String url = body.has("repositoryUrl") ? text(body, "repositoryUrl", true, 300) : current == null ? required("repositoryUrl") : current.repositoryUrl();
        if (!url.matches("https://[A-Za-z0-9.-]+/[A-Za-z0-9._/-]+") || url.contains("..")) throw bad("repositoryUrl must be an https URL of a git repository");
        v.put("repositoryUrl", url);
        String ref = body.has("defaultRef") ? text(body, "defaultRef", true, 100) : current == null ? "main" : current.defaultRef();
        if (!ref.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,99}") || ref.contains("..")) throw bad("defaultRef must be a branch, tag or commit");
        v.put("defaultRef", ref);
        v.put("enabled", body.has("enabled") ? bool(body, "enabled") : current == null || current.enabled());
        v.put("repositorySuitesEnabled", body.has("repositorySuitesEnabled") ? bool(body, "repositorySuitesEnabled") : current != null && current.repositorySuitesEnabled());
        String file = body.has("suitesFile") ? text(body, "suitesFile", true, 200) : current == null ? ".eahp/qa-suites.json" : current.suitesFile();
        if (!file.matches("[A-Za-z0-9._-][A-Za-z0-9._/-]{0,199}") || file.contains("..")) throw bad("suitesFile must be a relative path inside the repository");
        v.put("suitesFile", file);
        List<String> images = body.has("allowedImages") ? strings(body, "allowedImages", 20)
                : current == null ? List.of("maven:3.9.11-eclipse-temurin-17") : current.allowedImages();
        if (images.isEmpty()) throw bad("allowedImages must list at least one image");
        for (String i : images) if (!i.matches("[a-z0-9][a-z0-9._/-]*:[A-Za-z0-9._-]+")) throw bad("image must be name:tag: " + i);
        v.put("allowedImages", images);
        v.put("allowDockerAccess", body.has("allowDockerAccess") ? bool(body, "allowDockerAccess") : current != null && current.allowDockerAccess());
        v.put("allowNetworkEgress", body.has("allowNetworkEgress") ? bool(body, "allowNetworkEgress") : current == null || current.allowNetworkEgress());
        int max = body.has("maxTimeoutSeconds") ? integer(body, "maxTimeoutSeconds") : current == null ? 1800 : current.maxTimeoutSeconds();
        if (max < 30 || max > 3600) throw bad("maxTimeoutSeconds must be 30..3600");
        v.put("maxTimeoutSeconds", max);
        return v;
    }

    private List<Trigger> triggers(String key) {
        return jdbc.query("SELECT suite_key, enabled FROM qa_pr_triggers WHERE project_key=? ORDER BY suite_key",
                (r, i) -> new Trigger(r.getString("suite_key"), r.getBoolean("enabled")), key);
    }
    private List<String> gate(String key) {
        var rows = jdbc.queryForList("SELECT required_suites::text FROM qa_quality_gates WHERE project_key=?", String.class, key);
        if (rows.isEmpty()) return List.of();
        try { return List.of(mapper.readValue(rows.get(0), String[].class)); } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private JsonNode snapshot(String key) {
        var p = registry.project(key).orElseThrow();
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("name", p.name()).put("repositoryUrl", p.repositoryUrl()).put("defaultRef", p.defaultRef()).put("enabled", p.enabled())
                .put("repositorySuitesEnabled", p.repositorySuitesEnabled()).put("suitesFile", p.suitesFile()).put("allowDockerAccess", p.allowDockerAccess())
                .put("allowNetworkEgress", p.allowNetworkEgress()).put("maxTimeoutSeconds", p.maxTimeoutSeconds());
        n.set("allowedImages", mapper.valueToTree(p.allowedImages()));
        return n;
    }
    private JsonNode toJson(QaRegistry.Suite s) {
        var n = SuiteDefinition.fromRegistry(s).toJson();
        n.remove("source");
        n.put("enabled", s.enabled());
        return n;
    }
    /** Records a change; a call that changed nothing leaves no event. */
    private void audit(String action, String project, String subject, JsonNode before, JsonNode after) {
        if (before != null && before.equals(after)) return;
        jdbc.update("INSERT INTO qa_admin_events(actor,action,project_key,subject,before_state,after_state) VALUES (?,?,?,?,?::jsonb,?::jsonb)",
                AuditService.actor(), action, project, subject, before == null ? null : before.toString(), after == null ? null : after.toString());
    }

    private static JsonNode flag(boolean value) { return JsonNodeFactory.instance.objectNode().put("enabled", value); }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private static void fields(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject()) throw bad("A JSON object is required");
        var names = body.fieldNames();
        while (names.hasNext()) { String n = names.next(); if (!allowed.contains(n)) throw bad("Unknown field: " + (n.length() > 40 ? n.substring(0, 40) : n)); }
    }
    private static String text(JsonNode body, String field, boolean required, int max) {
        JsonNode v = body.get(field);
        if (v == null || v.isNull()) { if (required) throw bad(field + " is required"); return null; }
        if (!v.isTextual() || v.textValue().isBlank() || v.textValue().length() > max || v.textValue().chars().anyMatch(Character::isISOControl)) {
            throw bad(field + " must be non-empty text up to " + max + " characters");
        }
        return v.textValue().strip();
    }
    private static String required(String field) { throw bad(field + " is required"); }
    private static boolean bool(JsonNode body, String field) {
        if (!body.path(field).isBoolean()) throw bad(field + " must be true or false");
        return body.get(field).booleanValue();
    }
    private static int integer(JsonNode body, String field) {
        if (!body.path(field).isInt()) throw bad(field + " must be an integer");
        return body.get(field).intValue();
    }
    private static List<String> strings(JsonNode body, String field, int max) {
        JsonNode v = body.get(field);
        if (v == null || !v.isArray() || v.size() > max) throw bad(field + " must be an array of up to " + max + " strings");
        List<String> out = new ArrayList<>();
        for (JsonNode s : v) {
            if (!s.isTextual() || s.textValue().isBlank() || s.textValue().length() > 200) throw bad(field + " entries must be non-empty text");
            if (!out.contains(s.textValue())) out.add(s.textValue());
        }
        return out;
    }
    private static RunException bad(String message) { return new RunException(HttpStatus.BAD_REQUEST, message); }
    private static RunException notFound(String key) { return new RunException(HttpStatus.NOT_FOUND, "Project not found: " + key); }
}
