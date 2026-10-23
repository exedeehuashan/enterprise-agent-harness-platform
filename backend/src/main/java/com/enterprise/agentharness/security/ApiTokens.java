package com.enterprise.agentharness.security;

import com.enterprise.agentharness.governance.AuditService;
import com.enterprise.agentharness.run.RunException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Named API tokens for people and integrations, issued and revoked by approvers. Every action a token takes is
 * attributed to its name in the audit trail. Only the SHA-256 hash is stored; the token is returned once. Tokens
 * may expire; revoked or expired tokens stop working on the next request.
 */
@Service
public class ApiTokens {
    /** Names used by the deployment tokens and EAHP's own actors cannot be issued. */
    static final Set<String> RESERVED = Set.of("admin", "operator", "viewer", "system", "scheduler", "github", "qa", "worker", "retention", "planner", "expiry");
    static final List<String> ROLES = List.of("VIEWER", "OPERATOR", "APPROVER");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public ApiTokens(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public record Token(UUID id, String name, String description, List<String> roles, String createdBy, Instant createdAt, Instant expiresAt,
            Instant lastUsedAt, Instant revokedAt, String revokedBy, boolean active) {}
    public record Issued(Token token, String secret) {}
    record Principal(String name, List<String> roles) {}

    /** The active token with this hash, if any; records use at most once a minute. */
    Optional<Principal> authenticate(byte[] hash) {
        var rows = jdbc.query("""
                SELECT id, name, roles, last_used_at FROM api_tokens
                WHERE token_hash=? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > clock_timestamp())
                """, (r, i) -> new Object[] {r.getObject("id", UUID.class), r.getString("name"), r.getString("roles"), r.getTimestamp("last_used_at")}, hash);
        if (rows.isEmpty()) return Optional.empty();
        var row = rows.get(0);
        if (row[3] == null || ((Timestamp) row[3]).toInstant().isBefore(Instant.now().minusSeconds(60))) {
            jdbc.update("UPDATE api_tokens SET last_used_at=clock_timestamp() WHERE id=?", row[0]);
        }
        return Optional.of(new Principal((String) row[1], roles((String) row[2])));
    }

    public List<Token> list() { return jdbc.query("SELECT * FROM api_tokens ORDER BY revoked_at IS NOT NULL, name, created_at DESC", this::map); }

    /** {@code {"name": "alice", "description": "...", "roles": ["OPERATOR"], "expiresInDays": 90}}; VIEWER is always included. */
    @PreAuthorize("hasRole('APPROVER')")
    public Issued issue(JsonNode body) {
        if (body == null || !body.isObject()) throw bad("A JSON object is required");
        for (var it = body.fieldNames(); it.hasNext(); ) if (!Set.of("name", "description", "roles", "expiresInDays").contains(it.next())) throw bad("Unknown field");
        String name = body.path("name").asText("");
        if (!name.matches("[a-z0-9][a-z0-9._-]{1,39}") || RESERVED.contains(name)) throw bad("name must be 2..40 lower-case characters and not a reserved actor name");
        String description = body.hasNonNull("description") ? body.get("description").asText() : null;
        if (description != null && (description.length() > 200 || description.chars().anyMatch(Character::isISOControl))) throw bad("description is up to 200 characters");
        var roles = new TreeSet<String>(List.of("VIEWER"));
        if (!body.path("roles").isArray()) throw bad("roles must be a list such as [\"OPERATOR\"]");
        for (JsonNode r : body.get("roles")) {
            if (!r.isTextual() || !ROLES.contains(r.textValue())) throw bad("roles are VIEWER, OPERATOR and APPROVER");
            roles.add(r.textValue());
        }
        Instant expires = null;
        if (body.hasNonNull("expiresInDays")) {
            if (!body.get("expiresInDays").isInt() || body.get("expiresInDays").intValue() < 1 || body.get("expiresInDays").intValue() > 3650) throw bad("expiresInDays must be 1..3650");
            expires = Instant.now().plus(Duration.ofDays(body.get("expiresInDays").intValue()));
        }
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String secret = "eahp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO api_tokens(id,name,description,token_hash,roles,created_by,expires_at) VALUES (?,?,?,?,?::jsonb,?,?)",
                    id, name, description, ApiSecurity.hash(secret), json(new ArrayList<>(roles)), AuditService.actor(), expires == null ? null : Timestamp.from(expires));
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new RunException(HttpStatus.CONFLICT, "An active token named " + name + " already exists; revoke it first");
        }
        return new Issued(get(id), secret);
    }

    @PreAuthorize("hasRole('APPROVER')")
    public Token revoke(UUID id) {
        get(id);
        jdbc.update("UPDATE api_tokens SET revoked_at=clock_timestamp(), revoked_by=? WHERE id=? AND revoked_at IS NULL", AuditService.actor(), id);
        return get(id);
    }

    public Token get(UUID id) {
        var rows = jdbc.query("SELECT * FROM api_tokens WHERE id=?", this::map, id);
        if (rows.isEmpty()) throw new RunException(HttpStatus.NOT_FOUND, "Token not found: " + id);
        return rows.get(0);
    }

    private List<String> roles(String json) {
        try { return List.of(mapper.readValue(json, String[].class)); } catch (Exception ex) { return List.of(); }
    }
    private String json(Object v) {
        try { return mapper.writeValueAsString(v); } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
    private Token map(ResultSet r, int i) throws SQLException {
        Instant expires = instant(r, "expires_at"), revoked = instant(r, "revoked_at");
        return new Token(r.getObject("id", UUID.class), r.getString("name"), r.getString("description"), roles(r.getString("roles")), r.getString("created_by"),
                instant(r, "created_at"), expires, instant(r, "last_used_at"), revoked, r.getString("revoked_by"),
                revoked == null && (expires == null || expires.isAfter(Instant.now())));
    }
    private static Instant instant(ResultSet r, String k) throws SQLException { var t = r.getTimestamp(k); return t == null ? null : t.toInstant(); }
    private static RunException bad(String m) { return new RunException(HttpStatus.BAD_REQUEST, m); }
}
