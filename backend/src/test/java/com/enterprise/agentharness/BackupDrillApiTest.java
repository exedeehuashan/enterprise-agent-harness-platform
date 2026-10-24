package com.enterprise.agentharness;

import com.enterprise.agentharness.ops.BackupDrills;
import com.enterprise.agentharness.qa.ProcessRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Backup-restore drills with a fake docker: the restored copy is checked against the live database. */
@SpringBootTest(properties = {"eahp.worker.enabled=false", "eahp.backup-drill.network=eahp_default"})
@AutoConfigureMockMvc
class BackupDrillApiTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final Path WORKDIR = tempDir();
    private static final String ADMIN=UUID.randomUUID().toString(),OPERATOR=UUID.randomUUID().toString(),VIEWER=UUID.randomUUID().toString();
    static final List<List<String>> calls = new CopyOnWriteArrayList<>();
    static volatile boolean docker = true;
    static volatile int restoreExit = 0;
    static volatile long missingRows = 0;
    static volatile String pgpass, pgpassMode;
    static volatile JdbcTemplate live;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired BackupDrills drills;

    @TestConfiguration
    static class Fakes {
        @Bean @Primary ProcessRunner fakeDocker() {
            return (argv, logFile, timeout, stop, onStop) -> {
                calls.add(List.copyOf(argv));
                if (argv.contains("version")) return new ProcessRunner.Result(docker ? 0 : 1, false, false);
                if (argv.contains("cp")) {
                    Path file = Path.of(argv.get(2));
                    pgpass = Files.readString(file);
                    pgpassMode = java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
                }
                if (argv.contains("stat")) Files.writeString(logFile, "123456\n");
                if (argv.contains("pg_restore")) return new ProcessRunner.Result(restoreExit, false, false);
                if (argv.contains("psql")) {
                    // What the restored copy contains: the live database now, optionally with rows missing.
                    var row = new StringBuilder(String.valueOf(live.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success", Integer.class)));
                    for (String t : List.of("agent_tasks", "agent_runs", "audit_events", "run_artifacts", "qa_run_ingestions", "qa_test_results")) {
                        long n = live.queryForObject("SELECT count(*) FROM " + t, Long.class);
                        row.append(',').append(t.equals("audit_events") ? n - missingRows : n);
                    }
                    Files.writeString(logFile, row + "\n");
                }
                return new ProcessRunner.Result(0, false, false);
            };
        }
    }

    private static EmbeddedPostgres startPostgres() {
        try { return EmbeddedPostgres.builder().setPort(0).start(); } catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
    }
    private static Path tempDir() {
        try { return Files.createTempDirectory("eahp-drill-test"); } catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "pa:ss");
        registry.add("eahp.security.admin-token", () -> ADMIN);
        registry.add("eahp.security.operator-token", () -> OPERATOR);
        registry.add("eahp.security.viewer-token", () -> VIEWER);
        registry.add("eahp.qa.workdir", WORKDIR::toString);
        registry.add("eahp.qa.docker", () -> "docker");
    }
    @AfterAll static void stop() throws IOException { POSTGRES.close(); }

    @BeforeEach
    void reset() { jdbc.update("DELETE FROM backup_drills"); calls.clear(); docker = true; restoreExit = 0; missingRows = 0; pgpass = null; live = jdbc; }

    private JsonNode drill(String token) throws Exception {
        var started = mapper.readTree(mvc.perform(post("/api/v1/admin/backup-drills").header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        String id = started.get("id").asText();
        for (int i = 0; i < 100; i++) {
            var d = mapper.readTree(mvc.perform(get("/api/v1/admin/backup-drills/" + id).header("Authorization", "Bearer " + ADMIN)).andReturn().getResponse().getContentAsString());
            if (!d.get("status").asText().equals("RUNNING")) return d;
            Thread.sleep(50);
        }
        throw new AssertionError("drill did not finish");
    }

    @Test
    void aDrillDumpsRestoresAndVerifiesInAThrowawayContainer() throws Exception {
        mvc.perform(post("/api/v1/admin/backup-drills").header("Authorization", "Bearer " + OPERATOR)).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO audit_events(actor,action,run_id,code) VALUES ('test','RUN_CREATED',?,NULL)", UUID.randomUUID());
        var d = drill(ADMIN);
        assertThat(d.get("status").asText()).as(d.toString()).isEqualTo("PASSED");
        assertThat(d.get("trigger").asText()).isEqualTo("MANUAL");
        assertThat(d.get("dumpBytes").asLong()).isEqualTo(123456);
        assertThat(d.get("schemaVersion").asInt()).isGreaterThanOrEqualTo(35);
        assertThat(d.at("/restoredRows/audit_events").asLong()).isGreaterThanOrEqualTo(d.at("/expectedRows/audit_events").asLong()).isPositive();

        String container = calls.stream().filter(c -> c.contains("run")).findFirst().orElseThrow().get(4);
        assertThat(container).startsWith("eahp-backup-drill-");
        assertThat(calls.stream().filter(c -> c.contains("run")).findFirst().orElseThrow()).contains("--network", "eahp_default").doesNotContain("-p");
        // The live password only travels in a mode-600 .pgpass file (":" escaped), deleted locally and in the container.
        assertThat(pgpass).endsWith(":postgres:postgres:pa\\:ss\n");
        assertThat(pgpassMode).isEqualTo("rw-------");
        assertThat(calls).noneMatch(c -> String.join(" ", c).contains("pa:ss") || String.join(" ", c).contains("pa\\:ss"));
        assertThat(calls).anyMatch(c -> c.containsAll(List.of("exec", container, "rm", "-f", "/tmp/pgpass")));
        assertThat(calls.get(calls.size() - 1)).containsExactly("docker", "rm", "-f", "-v", container);
        assertThat(Files.exists(WORKDIR.resolve("drills"))).isTrue();
        assertThat(Files.list(WORKDIR.resolve("drills")).count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='BACKUP_DRILL_PASSED'", Long.class)).isEqualTo(1);
        mvc.perform(get("/api/v1/admin/backup-drills").header("Authorization", "Bearer " + VIEWER)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/backup-drills").header("Authorization", "Bearer " + ADMIN)).andExpect(jsonPath("$[0].status").value("PASSED"));
    }

    @Test
    void missingRowsAFailedRestoreOrNoDockerAreReported() throws Exception {
        missingRows = 1_000_000;
        var short1 = drill(ADMIN);
        assertThat(short1.get("status").asText()).isEqualTo("FAILED");
        assertThat(short1.get("error").asText()).contains("audit_events has").contains("expected at least");
        missingRows = 0; restoreExit = 1;
        var broken = drill(ADMIN);
        assertThat(broken.get("error").asText()).isEqualTo("pg_restore failed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='BACKUP_DRILL_FAILED'", Long.class)).isEqualTo(2);
        restoreExit = 0; docker = false;
        var skipped = drill(ADMIN);
        assertThat(skipped.get("status").asText()).isEqualTo("SKIPPED");
        assertThat(skipped.get("error").asText()).contains("Docker is not reachable");
        // Every container that was started was removed again.
        long started = calls.stream().filter(c -> c.contains("run")).count(), removed = calls.stream().filter(c -> c.contains("rm") && c.contains("-v")).count();
        assertThat(removed).isGreaterThanOrEqualTo(started);
    }

    @Test
    void onlyOneDrillRunsAtATime() throws Exception {
        jdbc.update("INSERT INTO backup_drills(id,trigger,actor,status) VALUES (?,?,?,'RUNNING')", UUID.randomUUID(), "MANUAL", "admin");
        mvc.perform(post("/api/v1/admin/backup-drills").header("Authorization", "Bearer " + ADMIN)).andExpect(status().isConflict());
        // A drill left RUNNING by a restart more than an hour ago no longer blocks.
        jdbc.update("UPDATE backup_drills SET started_at = clock_timestamp() - interval '2 hours'");
        assertThat(drill(ADMIN).get("status").asText()).isEqualTo("PASSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backup_drills WHERE error='interrupted'", Long.class)).isEqualTo(1);
    }
}
