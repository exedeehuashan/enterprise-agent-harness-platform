package com.enterprise.agentharness.code;

import com.enterprise.agentharness.governance.AuditService;
import com.enterprise.agentharness.run.RunException;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Repositories the code agent may work on. Only approvers register them, and pushing a branch (and opening a draft
 * pull request) is a separate opt-in per repository. Repository URLs must be https without credentials.
 */
@Service
public class CodeRepositories {
    private final JdbcTemplate jdbc;
    public CodeRepositories(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Repository(String key, String name, String repositoryUrl, String defaultRef, boolean enabled, boolean allowPush, String changedBy) {}

    public List<Repository> list() {
        return jdbc.query("SELECT * FROM code_repositories ORDER BY key", (r, i) -> map(r));
    }

    public Optional<Repository> find(String key) {
        return jdbc.query("SELECT * FROM code_repositories WHERE key=?", (r, i) -> map(r), key).stream().findFirst();
    }

    /** {@code {"name": "...", "repositoryUrl": "https://github.com/o/r.git", "defaultRef": "main", "enabled": true, "allowPush": false}} */
    @PreAuthorize("hasRole('APPROVER')")
    public Repository put(String key, JsonNode body) {
        if (!key.matches("[a-z0-9][a-z0-9-]{1,59}")) throw bad("invalid repository key");
        if (body == null || !body.isObject()) throw bad("A JSON object is required");
        for (var it = body.fieldNames(); it.hasNext(); ) if (!Set.of("name", "repositoryUrl", "defaultRef", "enabled", "allowPush").contains(it.next())) throw bad("Unknown field");
        String name = body.path("name").asText("");
        if (name.isBlank() || name.length() > 120) throw bad("name is required text up to 120 characters");
        String url = body.path("repositoryUrl").asText("");
        URI uri;
        try { uri = new URI(url); } catch (Exception ex) { throw bad("invalid repository URL"); }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || url.length() > 300) {
            throw bad("repositoryUrl must be https://host/path without credentials or query");
        }
        String ref = body.has("defaultRef") ? body.get("defaultRef").asText() : "main";
        if (!ref.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,99}") || ref.contains("..")) throw bad("defaultRef must be a branch or tag");
        boolean enabled = !body.has("enabled") || body.get("enabled").asBoolean(true);
        boolean allowPush = body.has("allowPush") && body.get("allowPush").asBoolean(false);
        jdbc.update("""
                INSERT INTO code_repositories(key,name,repository_url,default_ref,enabled,allow_push,changed_by) VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (key) DO UPDATE SET name=EXCLUDED.name, repository_url=EXCLUDED.repository_url, default_ref=EXCLUDED.default_ref,
                    enabled=EXCLUDED.enabled, allow_push=EXCLUDED.allow_push, changed_by=EXCLUDED.changed_by, changed_at=clock_timestamp()
                """, key, name.strip(), url, ref, enabled, allowPush, AuditService.actor());
        return find(key).orElseThrow();
    }

    @PreAuthorize("hasRole('APPROVER')")
    public void delete(String key) {
        if (jdbc.update("DELETE FROM code_repositories WHERE key=?", key) == 0) throw new RunException(HttpStatus.NOT_FOUND, "Repository not found: " + key);
    }

    private static Repository map(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Repository(r.getString("key"), r.getString("name"), r.getString("repository_url"), r.getString("default_ref"),
                r.getBoolean("enabled"), r.getBoolean("allow_push"), r.getString("changed_by"));
    }
    private static RunException bad(String m) { return new RunException(HttpStatus.BAD_REQUEST, m); }
}
