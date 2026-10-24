package com.enterprise.agentharness.ops;

import com.enterprise.agentharness.governance.AuditService;
import com.enterprise.agentharness.qa.ProcessRunner;
import com.enterprise.agentharness.qa.QaSettings;
import com.enterprise.agentharness.run.RunException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Backup-restore drills (P42): proves that EAHP's database can actually be restored, on a schedule and on demand.
 * A drill starts a throwaway Postgres container (no published port) on the database's Docker network, takes a fresh
 * {@code pg_dump} of the live database from inside it, restores the dump into an empty database there and checks the
 * result against the live database: the same schema version, and at least the row counts read just before the dump
 * for tables that only grow between those two moments. The container and its volume are always removed. The live
 * password reaches the container only as a {@code .pgpass} file copied in for the dump and deleted right after; it is
 * never in a command line. Results are kept in {@code backup_drills}, exported as metrics and announced as
 * {@code BACKUP_DRILL_PASSED}/{@code BACKUP_DRILL_FAILED} events (webhooks and notifications).
 */
@Service
public class BackupDrills {
    private static final Logger log = LoggerFactory.getLogger(BackupDrills.class);
    static final List<String> TABLES = List.of("agent_tasks", "agent_runs", "audit_events", "run_artifacts", "qa_run_ingestions", "qa_test_results");
    private static final Duration STEP = Duration.ofMinutes(10);
    private final JdbcTemplate jdbc;
    private final ProcessRunner runner;
    private final QaSettings settings;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String network, image, dbUrl, dbUser, dbPassword;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "eahp-backup-drill"); t.setDaemon(true); return t; });
    private final SecureRandom random = new SecureRandom();

    public BackupDrills(JdbcTemplate jdbc, ProcessRunner runner, QaSettings settings, ObjectMapper mapper,
            @Value("${eahp.backup-drill.enabled:false}") boolean enabled, @Value("${eahp.backup-drill.network:}") String network,
            @Value("${eahp.backup-drill.image:postgres:16}") String image, @Value("${spring.datasource.url}") String dbUrl,
            @Value("${spring.datasource.username}") String dbUser, @Value("${spring.datasource.password:}") String dbPassword) {
        this.jdbc = jdbc; this.runner = runner; this.settings = settings; this.mapper = mapper; this.enabled = enabled; this.network = network;
        this.image = image; this.dbUrl = dbUrl; this.dbUser = dbUser; this.dbPassword = dbPassword;
    }

    public record Drill(UUID id, String trigger, String actor, String status, Instant startedAt, Instant finishedAt, Integer schemaVersion,
            JsonNode expectedRows, JsonNode restoredRows, Long dumpBytes, String error) {}

    public List<Drill> list(int limit) {
        return jdbc.query("SELECT * FROM backup_drills ORDER BY started_at DESC LIMIT ?", this::map, Math.max(1, Math.min(limit, 100)));
    }

    public Drill get(UUID id) {
        var rows = jdbc.query("SELECT * FROM backup_drills WHERE id=?", this::map, id);
        if (rows.isEmpty()) throw new RunException(HttpStatus.NOT_FOUND, "Drill not found: " + id);
        return rows.get(0);
    }

    /** Starts a drill in the background; one at a time. */
    @PreAuthorize("hasRole('APPROVER')")
    public Drill runNow() { return start("MANUAL", AuditService.actor()); }

    @Scheduled(cron = "${eahp.backup-drill.cron:0 15 4 * * *}", zone = "UTC")
    public void scheduled() {
        if (!enabled) return;
        try { start("SCHEDULE", "scheduler"); } catch (RunException ex) { log.info("Scheduled backup drill skipped: {}", ex.getMessage()); }
    }

    synchronized Drill start(String trigger, String actor) {
        // A drill interrupted by a restart never finishes; it must not block later ones.
        jdbc.update("UPDATE backup_drills SET status='FAILED', finished_at=clock_timestamp(), error='interrupted' WHERE status='RUNNING' AND started_at < clock_timestamp() - interval '1 hour'");
        if (jdbc.queryForObject("SELECT count(*) FROM backup_drills WHERE status='RUNNING'", Long.class) > 0) throw new RunException(HttpStatus.CONFLICT, "A backup drill is already running");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO backup_drills(id,trigger,actor,status) VALUES (?,?,?,'RUNNING')", id, trigger, actor);
        executor.submit(() -> {
            try { execute(id); }
            catch (RuntimeException ex) { log.warn("Backup drill {} failed", id, ex); finish(id, "FAILED", null, null, null, null, "internal error"); }
        });
        return get(id);
    }

    void execute(UUID id) {
        Path dir = settings.workdir().resolve("drills").resolve(id.toString());
        String container = "eahp-backup-drill-" + id.toString().substring(0, 8);
        try {
            Files.createDirectories(dir);
            if (network.isBlank()) { finish(id, "SKIPPED", null, null, null, null, "EAHP_BACKUP_DRILL_NETWORK is not set"); return; }
            if (run(dir, List.of(settings.docker(), "version", "--format", "{{.Server.Version}}")).exitCode() != 0) {
                finish(id, "SKIPPED", null, null, null, null, "Docker is not reachable (enable the QA runner)");
                return;
            }
            URI db = URI.create(dbUrl.substring("jdbc:".length()));
            String host = db.getHost(), name = db.getPath().substring(1);
            int port = db.getPort() < 0 ? 5432 : db.getPort();
            // Read before the dump: the restored copy must have at least these rows and exactly this schema version.
            Integer version = jdbc.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success", Integer.class);
            Map<String, Long> expected = new LinkedHashMap<>();
            for (String t : TABLES) expected.put(t, jdbc.queryForObject("SELECT count(*) FROM " + t, Long.class));

            byte[] secret = new byte[18];
            random.nextBytes(secret);
            if (run(dir, List.of(settings.docker(), "run", "-d", "--name", container, "--label", "eahp.backup-drill=" + id, "--network", network,
                    "--security-opt", "no-new-privileges", "-e", "POSTGRES_PASSWORD=" + HexFormat.of().formatHex(secret), image)).exitCode() != 0) {
                finish(id, "FAILED", version, expected, null, null, "could not start the drill container");
                return;
            }
            boolean ready = false;
            for (int i = 0; i < 60 && !ready; i++) {
                ready = run(dir, List.of(settings.docker(), "exec", container, "pg_isready", "-U", "postgres", "-q")).exitCode() == 0;
                if (!ready) Thread.sleep(1000);
            }
            if (!ready) { finish(id, "FAILED", version, expected, null, null, "the drill database did not start"); return; }

            Path pgpass = dir.resolve("pgpass");
            Files.writeString(pgpass, host + ":" + port + ":" + name + ":" + dbUser + ":" + dbPassword.replace("\\", "\\\\").replace(":", "\\:") + "\n");
            Files.setPosixFilePermissions(pgpass, PosixFilePermissions.fromString("rw-------"));
            int copied = run(dir, List.of(settings.docker(), "cp", pgpass.toString(), container + ":/tmp/pgpass")).exitCode();
            Files.deleteIfExists(pgpass);
            int dumped = copied != 0 ? copied : run(dir, List.of(settings.docker(), "exec", "-e", "PGPASSFILE=/tmp/pgpass", container, "sh", "-c",
                    "chmod 600 /tmp/pgpass && pg_dump -h \"$0\" -p \"$1\" -U \"$2\" -d \"$3\" -Fc -f /tmp/drill.dump", host, String.valueOf(port), dbUser, name)).exitCode();
            run(dir, List.of(settings.docker(), "exec", container, "rm", "-f", "/tmp/pgpass"));
            if (dumped != 0) { finish(id, "FAILED", version, expected, null, null, "pg_dump of the live database failed"); return; }
            Long bytes = number(capture(dir, List.of(settings.docker(), "exec", container, "stat", "-c", "%s", "/tmp/drill.dump")));

            if (run(dir, List.of(settings.docker(), "exec", container, "createdb", "-U", "postgres", "drill")).exitCode() != 0
                    || run(dir, List.of(settings.docker(), "exec", container, "pg_restore", "-U", "postgres", "-d", "drill", "--no-owner", "--no-privileges",
                    "--exit-on-error", "/tmp/drill.dump")).exitCode() != 0) {
                finish(id, "FAILED", version, expected, null, bytes, "pg_restore failed");
                return;
            }
            var select = new StringBuilder("SELECT (SELECT max(version::int) FROM flyway_schema_history WHERE success)");
            for (String t : TABLES) select.append(", (SELECT count(*) FROM ").append(t).append(')');
            String[] values = capture(dir, List.of(settings.docker(), "exec", container, "psql", "-U", "postgres", "-d", "drill", "-tA", "-F", ",", "-c", select.toString())).strip().split(",");
            if (values.length != TABLES.size() + 1) { finish(id, "FAILED", version, expected, null, bytes, "the restored database could not be read"); return; }
            Map<String, Long> restored = new LinkedHashMap<>();
            for (int i = 0; i < TABLES.size(); i++) restored.put(TABLES.get(i), number(values[i + 1]));
            Long restoredVersion = number(values[0]);
            List<String> problems = new ArrayList<>();
            if (restoredVersion == null || restoredVersion.intValue() != version) problems.add("schema V" + restoredVersion + " instead of V" + version);
            for (var e : expected.entrySet()) {
                Long r = restored.get(e.getKey());
                if (r == null || r < e.getValue()) problems.add(e.getKey() + " has " + r + " rows, expected at least " + e.getValue());
            }
            finish(id, problems.isEmpty() ? "PASSED" : "FAILED", version, expected, restored, bytes, problems.isEmpty() ? null : String.join("; ", problems));
        } catch (IOException ex) {
            finish(id, "FAILED", null, null, null, null, "I/O error: " + ex.getClass().getSimpleName());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            finish(id, "FAILED", null, null, null, null, "interrupted");
        } finally {
            try { run(dir, List.of(settings.docker(), "rm", "-f", "-v", container)); } catch (Exception ex) { log.warn("Could not remove drill container {}", container); }
            try { deleteTree(dir); } catch (IOException ex) { log.warn("Could not remove drill workspace {}", dir); }
        }
    }

    private void finish(UUID id, String status, Integer version, Map<String, Long> expected, Map<String, Long> restored, Long bytes, String error) {
        jdbc.update("""
                UPDATE backup_drills SET status=?, finished_at=clock_timestamp(), schema_version=?, expected_rows=?::jsonb, restored_rows=?::jsonb, dump_bytes=?, error=?
                WHERE id=?
                """, status, version, json(expected), json(restored), bytes, error == null ? null : error.length() > 300 ? error.substring(0, 299) + "…" : error, id);
        if (!status.equals("SKIPPED")) {
            jdbc.update("INSERT INTO audit_events(actor,action,run_id,code) VALUES ('backup',?,?,?)", "BACKUP_DRILL_" + status, id,
                    error == null ? null : error.length() > 80 ? error.substring(0, 80) : error);
        }
    }

    private ProcessRunner.Result run(Path dir, List<String> argv) throws IOException, InterruptedException {
        return runner.run(argv, dir.resolve("drill.log"), STEP);
    }
    private String capture(Path dir, List<String> argv) throws IOException, InterruptedException {
        Path out = dir.resolve("capture.out");
        Files.deleteIfExists(out);
        if (runner.run(argv, out, STEP).exitCode() != 0) return "";
        byte[] bytes = Files.readAllBytes(out);
        return new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.UTF_8);
    }
    private static Long number(String s) {
        try { return Long.parseLong(s.strip()); } catch (RuntimeException ex) { return null; }
    }
    private String json(Object value) {
        if (value == null) return null;
        try { return mapper.writeValueAsString(value); } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var paths = Files.walk(dir)) { for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
    }
    private Drill map(ResultSet r, int i) throws SQLException {
        try {
            return new Drill(r.getObject("id", UUID.class), r.getString("trigger"), r.getString("actor"), r.getString("status"), r.getTimestamp("started_at").toInstant(),
                    r.getTimestamp("finished_at") == null ? null : r.getTimestamp("finished_at").toInstant(), (Integer) r.getObject("schema_version"),
                    r.getString("expected_rows") == null ? null : mapper.readTree(r.getString("expected_rows")),
                    r.getString("restored_rows") == null ? null : mapper.readTree(r.getString("restored_rows")), (Long) r.getObject("dump_bytes"), r.getString("error"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new SQLException(ex); }
    }
}
