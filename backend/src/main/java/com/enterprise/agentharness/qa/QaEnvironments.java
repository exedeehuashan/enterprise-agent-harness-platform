package com.enterprise.agentharness.qa;

import com.enterprise.agentharness.governance.AuditService;
import com.enterprise.agentharness.run.RunException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test environments of a project: named services and their base URLs (for example a UAT deployment's order-api).
 * Only approvers register them, because they decide where EAHP sends requests; repository-declared API tests can
 * name a service but never a host. Changes are audited in {@code qa_admin_events}.
 */
@Service
public class QaEnvironments {
    private final JdbcTemplate jdbc;
    private final QaRegistry registry;
    private final ObjectMapper mapper;

    public QaEnvironments(JdbcTemplate jdbc, QaRegistry registry, ObjectMapper mapper) { this.jdbc = jdbc; this.registry = registry; this.mapper = mapper; }

    /** {@code ephemeral} environments are per-commit stacks (P36): EAHP registers and removes them; {@code network} is their Docker network. */
    public record Environment(String projectKey, String key, String name, Map<String, String> services, boolean enabled, String network, boolean ephemeral) {}

    public List<Environment> list(String project) {
        return jdbc.query("SELECT * FROM qa_environments WHERE (?::text IS NULL OR project_key=?) ORDER BY project_key, key", this::map, project, project);
    }

    public Optional<Environment> find(String project, String key) {
        return jdbc.query("SELECT * FROM qa_environments WHERE project_key=? AND key=?", this::map, project, key).stream().findFirst();
    }

    /** Creates or replaces an environment: {@code {"name": "...", "services": {"order-api": "http://host:8081"}, "enabled": true}}. */
    @Transactional
    @PreAuthorize("hasRole('APPROVER')")
    public Environment put(String project, String key, JsonNode body) {
        if (registry.project(project).isEmpty()) throw new RunException(HttpStatus.NOT_FOUND, "Project not found: " + project);
        if (!key.matches("[a-z0-9][a-z0-9-]{1,59}")) throw bad("invalid environment key");
        if (key.startsWith("eph-")) throw bad("eph- keys are reserved for temporary environments");
        if (body == null || !body.isObject()) throw bad("A JSON object is required");
        for (Iterator<String> it = body.fieldNames(); it.hasNext(); ) if (!Set.of("name", "services", "enabled").contains(it.next())) throw bad("Unknown field");
        if (!body.path("name").isTextual() || body.get("name").textValue().isBlank() || body.get("name").textValue().length() > 120) throw bad("name is required text up to 120 characters");
        var services = body.get("services");
        if (services == null || !services.isObject() || services.isEmpty() || services.size() > 20) throw bad("services must map 1..20 service names to base URLs");
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Iterator<String> it = services.fieldNames(); it.hasNext(); ) {
            String service = it.next();
            if (!service.matches("[a-z0-9][a-z0-9-]{0,59}")) throw bad("service names look like order-api");
            normalized.put(service, baseUrl(services.get(service)));
        }
        boolean enabled = !body.has("enabled") || body.get("enabled").asBoolean(true);
        var before = find(project, key).orElse(null);
        if (before != null && before.ephemeral()) throw new RunException(HttpStatus.CONFLICT, "Temporary environments are managed by EAHP");
        String json = write(normalized);
        jdbc.update("""
                INSERT INTO qa_environments(project_key,key,name,services,enabled) VALUES (?,?,?,?::jsonb,?)
                ON CONFLICT (project_key,key) DO UPDATE SET name=EXCLUDED.name, services=EXCLUDED.services, enabled=EXCLUDED.enabled
                """, project, key, body.get("name").textValue().strip(), json, enabled);
        var after = find(project, key).orElseThrow();
        if (!after.equals(before)) audit(before == null ? "ENVIRONMENT_CREATED" : "ENVIRONMENT_UPDATED", project, key, before, after);
        return after;
    }

    @Transactional
    @PreAuthorize("hasRole('APPROVER')")
    public void delete(String project, String key) {
        var before = find(project, key).orElseThrow(() -> new RunException(HttpStatus.NOT_FOUND, "Environment not found: " + key));
        if (before.ephemeral()) throw new RunException(HttpStatus.CONFLICT, "Stop the temporary environment instead");
        jdbc.update("DELETE FROM qa_environments WHERE project_key=? AND key=?", project, key);
        audit("ENVIRONMENT_DELETED", project, key, before, null);
    }

    /** Registers a temporary environment started by EAHP itself (no approver: the start was approved). */
    void registerEphemeral(String project, String key, String name, Map<String, String> services, String network) {
        jdbc.update("INSERT INTO qa_environments(project_key,key,name,services,enabled,network,ephemeral) VALUES (?,?,?,?::jsonb,TRUE,?,TRUE)",
                project, key, name, write(services), network);
    }

    void removeEphemeral(String project, String key) {
        jdbc.update("DELETE FROM qa_environments WHERE project_key=? AND key=? AND ephemeral", project, key);
    }

    /** http(s) with a host, no credentials, query or fragment; a trailing slash is removed. */
    static String baseUrl(JsonNode value) {
        if (value == null || !value.isTextual() || value.textValue().length() > 300) throw bad("base URLs must be text");
        URI uri;
        try { uri = new URI(value.textValue().strip()); } catch (Exception ex) { throw bad("invalid base URL"); }
        if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https")) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw bad("base URLs must be http(s)://host[:port][/path] without credentials, query or fragment");
        }
        String s = uri.toString();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private void audit(String action, String project, String key, Environment before, Environment after) {
        jdbc.update("INSERT INTO qa_admin_events(actor,action,project_key,subject,before_state,after_state) VALUES (?,?,?,?,?::jsonb,?::jsonb)",
                AuditService.actor(), action, project, key, before == null ? null : write(before), after == null ? null : write(after));
    }
    private String write(Object value) {
        try { return mapper.writeValueAsString(value); } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
    private Environment map(ResultSet r, int i) throws SQLException {
        Map<String, String> services;
        try { services = mapper.readValue(r.getString("services"), new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, String>>() {}); }
        catch (Exception ex) { throw new IllegalStateException("Invalid environment services", ex); }
        return new Environment(r.getString("project_key"), r.getString("key"), r.getString("name"), services, r.getBoolean("enabled"),
                r.getString("network"), r.getBoolean("ephemeral"));
    }
    private static RunException bad(String m) { return new RunException(HttpStatus.BAD_REQUEST, m); }
}
