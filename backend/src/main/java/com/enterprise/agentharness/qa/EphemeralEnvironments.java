package com.enterprise.agentharness.qa;

import com.enterprise.agentharness.runtime.ContainerSettings;
import com.enterprise.agentharness.runtime.ProcessRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Per-commit test environments (P36): a project's Docker Compose stack, built and started from one exact commit
 * under its own compose project, registered as a temporary {@link QaEnvironments.Environment} and removed again
 * when it is stopped or expires.
 *
 * <p>The compose files are repository content, so before anything starts EAHP renders them with
 * {@code docker compose config} and refuses host-level settings: privileged or host-namespace services, added
 * capabilities, devices, bind mounts, external networks or volumes, fixed container names and build contexts
 * outside the checkout. Published ports are removed, so the stack is reachable only on its own network, which
 * EAHP's container joins while the environment runs. Service URLs use the unique container names.
 */
@Service
public class EphemeralEnvironments {
    private static final Logger log = LoggerFactory.getLogger(EphemeralEnvironments.class);
    private static final Duration COMMAND = Duration.ofMinutes(2);
    /** A top-level {@code include:} or a {@code file:} key (extends, secrets, configs) would make compose read other files. */
    private static final java.util.regex.Pattern FOREIGN_FILES = java.util.regex.Pattern.compile("^(include\\s*:|\\s+file\\s*:)", java.util.regex.Pattern.MULTILINE);
    private final JdbcTemplate jdbc;
    private final GitMirror git;
    private final ProcessRunner runner;
    private final ContainerSettings settings;
    private final QaEnvironments environments;
    private final ObjectMapper mapper;
    private final String selfContainer;

    public EphemeralEnvironments(JdbcTemplate jdbc, GitMirror git, ProcessRunner runner, ContainerSettings settings, QaEnvironments environments, ObjectMapper mapper,
            @Value("${eahp.qa.self-container:}") String selfContainer) {
        this.jdbc = jdbc; this.git = git; this.runner = runner; this.settings = settings; this.environments = environments; this.mapper = mapper;
        this.selfContainer = selfContainer;
    }

    public record Live(UUID id, String projectKey, String template, String commit, String environmentKey, String composeProject, String network,
            String status, UUID runId, String error, Instant createdAt, Instant expiresAt, Instant stoppedAt) {}
    public record Started(UUID id, String environmentKey, Map<String, String> services, String network) {}

    /** A start that did not produce a running environment; {@code code} is the run's error code. */
    public static final class StartFailed extends Exception {
        private final String code;
        StartFailed(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    public List<Live> list(String project, boolean includeStopped) {
        return jdbc.query("""
                SELECT * FROM qa_ephemeral_environments WHERE (?::text IS NULL OR project_key=?) AND (? OR status IN ('STARTING','RUNNING'))
                ORDER BY created_at DESC LIMIT 100
                """, this::map, project, project, includeStopped);
    }

    public Optional<Live> find(UUID id) {
        return jdbc.query("SELECT * FROM qa_ephemeral_environments WHERE id=?", this::map, id).stream().findFirst();
    }

    /**
     * Exports {@code commit}, starts the template's stack and waits until every service is healthy. The start log
     * (including service logs on failure) goes to {@code logFile}. On any failure the stack is removed again.
     */
    public Started start(QaRegistry.Project project, EnvironmentTemplates.Template template, String commit, UUID runId, BooleanSupplier stop, Path logFile)
            throws StartFailed, InterruptedException {
        UUID id = UUID.randomUUID();
        String shortId = id.toString().substring(0, 8);
        String composeProject = "eahp-env-" + shortId;
        String key = "eph-" + shortId;
        // Until it runs, the environment may live through its startup; afterwards it lives for its TTL.
        jdbc.update("""
                INSERT INTO qa_ephemeral_environments(id,project_key,template,commit_sha,environment_key,compose_project,status,run_id,expires_at)
                VALUES (?,?,?,?,?,?,'STARTING',?, clock_timestamp() + make_interval(secs => ?))
                """, id, project.key(), template.key(), commit, key, composeProject, runId, template.startupTimeoutSeconds() + template.ttlMinutes() * 60);
        Path dir = directory(id);
        try {
            Files.createDirectories(dir);
            Path source = dir.resolve("src");
            try { git.export(project, commit, source, logFile); }
            catch (IOException ex) { throw new StartFailed("SOURCE_UNAVAILABLE", "Could not export commit " + commit + " of " + project.key() + "."); }
            for (String file : template.compose()) {
                if (!Files.isRegularFile(source.resolve(file), java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new StartFailed("ENVIRONMENT_INVALID", "Compose file " + file + " is not in the commit.");
            }
            try { checkSource(source, template); }
            catch (IllegalArgumentException ex) { throw new StartFailed("ENVIRONMENT_POLICY", ex.getMessage()); }
            Path config = dir.resolve("config.json");
            // Env files stay unresolved here so their paths can be checked before anything reads them.
            if (compose(composeProject, source, template, null, List.of("config", "--format", "json", "--no-env-resolution"), config, COMMAND).exitCode() != 0) {
                Files.writeString(logFile, Files.readString(config, StandardCharsets.UTF_8), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
                throw new StartFailed("ENVIRONMENT_INVALID", "docker compose could not read the compose files.");
            }
            JsonNode model;
            try { model = mapper.readTree(config.toFile()); } catch (IOException ex) { throw new StartFailed("ENVIRONMENT_INVALID", "docker compose config returned no model."); }
            String network;
            try { network = checkPolicy(model, template, source); }
            catch (IllegalArgumentException ex) { throw new StartFailed("ENVIRONMENT_POLICY", ex.getMessage()); }
            Path override = dir.resolve("eahp-override.yaml");
            Files.writeString(override, override(model));
            if (stop.getAsBoolean()) throw new StartFailed("STOPPED", "The run was stopped before the environment started.");
            var up = compose(composeProject, source, template, override, List.of("up", "-d", "--build", "--wait", "--wait-timeout", String.valueOf(template.startupTimeoutSeconds())),
                    logFile, Duration.ofSeconds(template.startupTimeoutSeconds() + 600L), stop);
            if (up.stopped()) throw new StartFailed("STOPPED", "The run was stopped while the environment started.");
            if (up.timedOut() || up.exitCode() != 0) {
                compose(composeProject, source, template, override, List.of("logs", "--no-color", "--tail", "200"), logFile, COMMAND);
                throw new StartFailed("ENVIRONMENT_START_FAILED", "The environment did not become healthy (exit " + (up.timedOut() ? "timeout" : up.exitCode()) + ").");
            }
            Path ps = dir.resolve("ps.json");
            compose(composeProject, source, template, override, List.of("ps", "--format", "json"), ps, COMMAND);
            Map<String, String> containers = containers(Files.readString(ps, StandardCharsets.UTF_8));
            Map<String, String> services = new LinkedHashMap<>();
            for (var s : template.services()) {
                String container = containers.get(s.composeService());
                if (container == null) throw new StartFailed("ENVIRONMENT_START_FAILED", "Service " + s.composeService() + " has no running container.");
                services.put(s.name(), "http://" + container + ":" + s.port());
            }
            if (!selfContainer.isBlank()) {
                var connect = runner.run(List.of(settings.docker(), "network", "connect", network, selfContainer), logFile, COMMAND);
                if (connect.exitCode() != 0) throw new StartFailed("ENVIRONMENT_START_FAILED", "EAHP could not join the environment's network.");
            }
            environments.registerEphemeral(project.key(), key, "Temporary " + template.key() + " @ " + commit.substring(0, 12), services, network);
            jdbc.update("""
                    UPDATE qa_ephemeral_environments SET status='RUNNING', network=?, expires_at=clock_timestamp() + make_interval(mins => ?) WHERE id=?
                    """, network, template.ttlMinutes(), id);
            return new Started(id, key, services, network);
        } catch (StartFailed ex) {
            fail(id, ex.getMessage());
            throw ex;
        } catch (IOException | RuntimeException ex) {
            log.warn("Starting environment {} failed", id, ex);
            fail(id, "Internal error while starting the environment.");
            throw new StartFailed("ENVIRONMENT_START_FAILED", "Internal error while starting the environment.");
        }
    }

    /** Stops and removes an environment (containers, its volumes, images built for it, the network) unless it is already gone. */
    public boolean stop(UUID id) {
        var claimed = jdbc.queryForList("""
                UPDATE qa_ephemeral_environments SET status='STOPPED', stopped_at=clock_timestamp() WHERE id=? AND status IN ('STARTING','RUNNING')
                RETURNING project_key, environment_key, compose_project, network
                """, id);
        if (claimed.isEmpty()) return false;
        var row = claimed.get(0);
        teardown(id, (String) row.get("compose_project"), (String) row.get("network"));
        environments.removeEphemeral((String) row.get("project_key"), (String) row.get("environment_key"));
        return true;
    }

    /** Removes environments past their expiry, including ones a restart left behind. */
    @Scheduled(initialDelayString = "${eahp.qa.environment-reaper-initial-delay-ms:60000}", fixedDelayString = "${eahp.qa.environment-reaper-interval-ms:60000}")
    public void reap() {
        for (UUID id : jdbc.queryForList("SELECT id FROM qa_ephemeral_environments WHERE status IN ('STARTING','RUNNING') AND expires_at < clock_timestamp()", UUID.class)) {
            try { if (stop(id)) log.info("Stopped expired environment {}", id); }
            catch (RuntimeException ex) { log.warn("Could not stop expired environment {}", id, ex); }
        }
    }

    private void fail(UUID id, String error) {
        var row = jdbc.queryForList("SELECT compose_project, network FROM qa_ephemeral_environments WHERE id=?", id);
        teardown(id, (String) row.get(0).get("compose_project"), (String) row.get(0).get("network"));
        jdbc.update("UPDATE qa_ephemeral_environments SET status='FAILED', error=?, stopped_at=clock_timestamp() WHERE id=?",
                error.length() > 300 ? error.substring(0, 299) + "…" : error, id);
    }

    private void teardown(UUID id, String composeProject, String network) {
        Path dir = directory(id);
        try {
            Path logFile = Files.createTempFile("eahp-env", ".log");
            try {
                if (network != null && !selfContainer.isBlank()) runner.run(List.of(settings.docker(), "network", "disconnect", "--force", network, selfContainer), logFile, COMMAND);
                var argv = cleanEnvironment(dir);
                argv.addAll(List.of(settings.docker(), "compose", "-p", composeProject));
                var files = composeFiles(dir);
                if (!files.isEmpty()) {
                    argv.addAll(List.of("--project-directory", dir.resolve("src").toString()));
                    for (Path f : files) argv.addAll(List.of("-f", f.toString()));
                }
                argv.addAll(List.of("down", "-v", "--remove-orphans"));
                if (!files.isEmpty()) argv.addAll(List.of("--rmi", "local"));
                if (runner.run(argv, logFile, Duration.ofMinutes(5)).exitCode() != 0) log.warn("docker compose down failed for {}", composeProject);
            } finally {
                Files.deleteIfExists(logFile);
            }
            QaSuiteTools.deleteTree(dir);
        } catch (IOException ex) {
            log.warn("Could not remove environment {}", id);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** The compose files a start used, recorded so that teardown can also remove the images it built. */
    private List<Path> composeFiles(Path dir) {
        Path list = dir.resolve("compose-files.txt");
        try {
            if (!Files.exists(list)) return List.of();
            return Files.readAllLines(list).stream().filter(l -> !l.isBlank()).map(Path::of).filter(Files::exists).toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    private ProcessRunner.Result compose(String project, Path source, EnvironmentTemplates.Template template, Path override, List<String> command, Path logFile,
            Duration timeout) throws IOException, InterruptedException {
        return compose(project, source, template, override, command, logFile, timeout, () -> false);
    }

    private ProcessRunner.Result compose(String project, Path source, EnvironmentTemplates.Template template, Path override, List<String> command, Path logFile,
            Duration timeout, BooleanSupplier stop) throws IOException, InterruptedException {
        var argv = cleanEnvironment(source.getParent());
        argv.addAll(List.of(settings.docker(), "compose", "-p", project, "--project-directory", source.toString()));
        var files = new ArrayList<String>();
        for (String f : template.compose()) files.add(source.resolve(f).toString());
        if (override != null) files.add(override.toString());
        for (String f : files) argv.addAll(List.of("-f", f));
        Files.write(source.getParent().resolve("compose-files.txt"), files);
        argv.addAll(command);
        return runner.run(argv, logFile, timeout, stop, () -> { });
    }

    /**
     * Compose interpolates {@code ${VAR}} from its own environment, which would hand EAHP's secrets to the stack.
     * It therefore runs with an empty environment apart from PATH, a private HOME and the Docker endpoint.
     */
    private ArrayList<String> cleanEnvironment(Path dir) {
        var argv = new ArrayList<>(List.of("env", "-i", "PATH=" + System.getenv().getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin"),
                "HOME=" + dir, "DOCKER_CONFIG=" + dir.resolve(".docker")));
        String host = System.getenv("DOCKER_HOST");
        if (host != null && !host.isBlank()) argv.add("DOCKER_HOST=" + host);
        return argv;
    }

    /**
     * Checks the checkout before compose reads it: no symbolic link may point outside it (an env file, Dockerfile or
     * {@code .env} could otherwise read EAHP's own files), and compose files may not include or extend other files.
     */
    static void checkSource(Path source, EnvironmentTemplates.Template template) {
        Path root = source.toAbsolutePath().normalize();
        try (var paths = Files.walk(root)) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                if (!Files.isSymbolicLink(p)) continue;
                Path target = p.getParent().resolve(Files.readSymbolicLink(p)).normalize();
                if (!target.startsWith(root)) throw new IllegalArgumentException("symbolic link " + root.relativize(p) + " points outside the repository");
            }
            for (String f : template.compose()) {
                String text = Files.readString(root.resolve(f), StandardCharsets.UTF_8);
                if (FOREIGN_FILES.matcher(text).find()) {
                    throw new IllegalArgumentException(f + ": include and extends from other files are not supported");
                }
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("the checkout could not be checked");
        }
    }

    /**
     * Refuses host-level settings in the rendered compose model and returns the Docker network all target services
     * share. Compose has already resolved paths, so build contexts can be checked against the checkout.
     */
    static String checkPolicy(JsonNode model, EnvironmentTemplates.Template template, Path source) {
        var services = model.path("services");
        if (!services.isObject() || services.isEmpty()) throw new IllegalArgumentException("the compose files define no services");
        if (model.path("secrets").size() > 0 || model.path("configs").size() > 0) throw new IllegalArgumentException("secrets and configs are not supported");
        for (var it = model.path("networks").fields(); it.hasNext(); ) {
            var n = it.next();
            if (n.getValue().path("external").asBoolean(false)) throw new IllegalArgumentException("network " + n.getKey() + " is external");
            if (n.getValue().has("driver") && !n.getValue().path("driver").asText().equals("bridge")) throw new IllegalArgumentException("network " + n.getKey() + " must use the bridge driver");
        }
        for (var it = model.path("volumes").fields(); it.hasNext(); ) {
            var v = it.next();
            if (v.getValue().path("external").asBoolean(false) || v.getValue().has("driver_opts")) throw new IllegalArgumentException("volume " + v.getKey() + " must be a plain named volume");
        }
        for (var it = services.fields(); it.hasNext(); ) {
            var e = it.next();
            String name = e.getKey();
            JsonNode s = e.getValue();
            if (s.path("privileged").asBoolean(false)) throw new IllegalArgumentException(name + ": privileged is not allowed");
            for (String field : List.of("cap_add", "devices", "device_cgroup_rules", "volumes_from", "container_name", "network_mode", "userns_mode", "cgroup_parent", "runtime", "pid", "ipc", "uts")) {
                if (s.has(field) && !s.get(field).isNull() && !(s.get(field).isContainerNode() && s.get(field).isEmpty())) throw new IllegalArgumentException(name + ": " + field + " is not allowed");
            }
            for (String field : List.of("env_file", "label_file")) {
                for (JsonNode f : s.path(field)) {
                    String path = f.isTextual() ? f.asText() : f.path("path").asText("");
                    if (!Path.of(path).toAbsolutePath().normalize().startsWith(source.toAbsolutePath().normalize())) throw new IllegalArgumentException(name + ": " + field + " must be inside the repository");
                }
            }
            for (JsonNode opt : s.path("security_opt")) if (opt.asText().contains("unconfined")) throw new IllegalArgumentException(name + ": unconfined security options are not allowed");
            for (JsonNode v : s.path("volumes")) {
                String type = v.path("type").asText("");
                if (!type.equals("volume") && !type.equals("tmpfs")) throw new IllegalArgumentException(name + ": only named volumes and tmpfs mounts are allowed");
            }
            if (s.has("build")) {
                JsonNode b = s.get("build");
                for (String field : List.of("additional_contexts", "ssh", "secrets", "network", "privileged", "entitlements")) {
                    if (b.has(field) && !b.get(field).isNull() && !(b.get(field).isContainerNode() && b.get(field).isEmpty())) throw new IllegalArgumentException(name + ": build " + field + " is not allowed");
                }
                String context = b.path("context").asText("");
                Path resolved = Path.of(context).toAbsolutePath().normalize();
                if (context.isEmpty() || !resolved.startsWith(source.toAbsolutePath().normalize())) throw new IllegalArgumentException(name + ": the build context must be inside the repository");
                String dockerfile = b.path("dockerfile").asText("Dockerfile");
                if (b.has("dockerfile_inline") || !resolved.resolve(dockerfile).normalize().startsWith(resolved)) throw new IllegalArgumentException(name + ": the Dockerfile must be inside the build context");
            }
        }
        Set<String> shared = null;
        for (var t : template.services()) {
            JsonNode s = services.get(t.composeService());
            if (s == null) throw new IllegalArgumentException("compose service " + t.composeService() + " does not exist");
            Set<String> nets = new LinkedHashSet<>();
            if (s.path("networks").isObject() && !s.get("networks").isEmpty()) s.get("networks").fieldNames().forEachRemaining(nets::add);
            else nets.add("default");
            if (shared == null) shared = nets; else shared.retainAll(nets);
        }
        if (shared == null || shared.isEmpty()) throw new IllegalArgumentException("the target services share no network");
        String key = shared.iterator().next();
        String network = model.path("networks").path(key).path("name").asText("");
        if (!network.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,119}")) throw new IllegalArgumentException("network " + key + " has no usable name");
        return network;
    }

    /** Removes every published port, so only containers on the environment's network can reach it. */
    static String override(JsonNode model) {
        var yaml = new StringBuilder("services:\n");
        boolean any = false;
        for (Iterator<Map.Entry<String, JsonNode>> it = model.path("services").fields(); it.hasNext(); ) {
            var e = it.next();
            if (e.getValue().path("ports").isEmpty()) continue;
            yaml.append("  \"").append(e.getKey().replace("\"", "")).append("\":\n    ports: !reset []\n");
            any = true;
        }
        return any ? yaml.toString() : "services: {}\n";
    }

    /** {@code docker compose ps --format json} prints a JSON array (older releases) or one object per line. */
    static Map<String, String> containers(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        var mapper = new ObjectMapper();
        List<JsonNode> rows = new ArrayList<>();
        String text = json.strip();
        try {
            if (text.startsWith("[")) mapper.readTree(text).forEach(rows::add);
            else for (String line : text.split("\n")) if (line.strip().startsWith("{")) rows.add(mapper.readTree(line));
        } catch (IOException ex) {
            return result;
        }
        for (JsonNode r : rows) {
            String service = r.path("Service").asText(""), name = r.path("Name").asText("");
            if (!service.isEmpty() && name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}") && "running".equalsIgnoreCase(r.path("State").asText("running"))) result.putIfAbsent(service, name);
        }
        return result;
    }

    private Path directory(UUID id) { return settings.workdir().resolve("envs").resolve(id.toString()); }

    private Live map(ResultSet r, int i) throws SQLException {
        return new Live(r.getObject("id", UUID.class), r.getString("project_key"), r.getString("template"), r.getString("commit_sha"), r.getString("environment_key"),
                r.getString("compose_project"), r.getString("network"), r.getString("status"), r.getObject("run_id", UUID.class), r.getString("error"),
                instant(r, "created_at"), instant(r, "expires_at"), instant(r, "stopped_at"));
    }
    private static Instant instant(ResultSet r, String key) throws SQLException { var t = r.getTimestamp(key); return t == null ? null : t.toInstant(); }
}
