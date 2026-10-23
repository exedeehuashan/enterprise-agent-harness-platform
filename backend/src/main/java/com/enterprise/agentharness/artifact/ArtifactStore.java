package com.enterprise.agentharness.artifact;

import com.enterprise.agentharness.run.RunException;
import com.enterprise.agentharness.tool.ToolResult;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Evidence produced by tools (test reports, logs), stored with the invocation that produced it.
 * Content lives in PostgreSQL with strict size limits; an object store can replace it later
 * without changing the API, because callers only see metadata, digests and a download endpoint.
 */
@Service
public class ArtifactStore {
    public static final int MAX_COUNT = 20;
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 20L * 1024 * 1024;
    public static final Set<String> MEDIA_TYPES = Set.of("text/plain", "application/json", "application/xml", "application/zip", "application/octet-stream");

    private final JdbcTemplate jdbc;
    public ArtifactStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Artifact(UUID id, UUID runId, UUID invocationId, int attempt, String name, String mediaType,
            int sizeBytes, String sha256, Instant createdAt) {}
    public record Content(Artifact artifact, byte[] bytes) {}

    /** Returns a bounded failure code when the tool's artifacts break the limits, otherwise null. */
    public String problem(List<ToolResult.Artifact> artifacts) {
        if (artifacts.size() > MAX_COUNT) return "ARTIFACT_LIMIT";
        long total = 0;
        Set<String> names = new HashSet<>();
        for (var a : artifacts) {
            if (a == null || a.name() == null || a.content() == null || a.mediaType() == null) return "ARTIFACT_INVALID";
            if (!a.name().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}") || !names.add(a.name()) || !MEDIA_TYPES.contains(a.mediaType())) return "ARTIFACT_INVALID";
            if (a.content().length > MAX_BYTES) return "ARTIFACT_LIMIT";
            total += a.content().length;
        }
        return total > MAX_TOTAL_BYTES ? "ARTIFACT_LIMIT" : null;
    }

    /** Called inside the run's finish transaction, so artifacts appear only with an accepted result. */
    public void store(UUID runId, int attempt, UUID invocationId, List<ToolResult.Artifact> artifacts) {
        for (var a : artifacts) {
            jdbc.update("""
                    INSERT INTO run_artifacts(id,run_id,invocation_id,attempt,name,media_type,size_bytes,sha256,content)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, UUID.randomUUID(), runId, invocationId, attempt, a.name(), a.mediaType(), a.content().length, sha256(a.content()), a.content());
        }
    }

    public List<Artifact> list(UUID runId) {
        return jdbc.query("SELECT * FROM run_artifacts WHERE run_id=? ORDER BY attempt,name", this::map, runId);
    }

    public Artifact get(UUID id) {
        var rows = jdbc.query("SELECT * FROM run_artifacts WHERE id=?", this::map, id);
        if (rows.isEmpty()) throw new RunException(HttpStatus.NOT_FOUND, "Artifact not found: " + id);
        return rows.get(0);
    }

    public Content content(UUID id) {
        var rows = jdbc.query("SELECT * FROM run_artifacts WHERE id=?", (r, i) -> new Content(map(r, i), r.getBytes("content")), id);
        if (rows.isEmpty()) throw new RunException(HttpStatus.NOT_FOUND, "Artifact not found: " + id);
        return rows.get(0);
    }

    private Artifact map(ResultSet r, int i) throws SQLException {
        return new Artifact(r.getObject("id", UUID.class), r.getObject("run_id", UUID.class), r.getObject("invocation_id", UUID.class),
                r.getInt("attempt"), r.getString("name"), r.getString("media_type"), r.getInt("size_bytes"), r.getString("sha256"),
                r.getTimestamp("created_at").toInstant());
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception ex) { throw new IllegalStateException("Cannot calculate artifact digest", ex); }
    }
}
