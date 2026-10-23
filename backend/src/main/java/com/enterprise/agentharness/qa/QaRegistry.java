package com.enterprise.agentharness.qa;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Projects under test and their predefined suites. Registered as data so new projects need no code change. */
@Service
public class QaRegistry {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public QaRegistry(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    /** A project and the policy every one of its suites must satisfy, whether registered here or declared in its repository. */
    public record Project(String key, String name, String repositoryUrl, String defaultRef, boolean enabled, List<Suite> suites,
            boolean repositorySuitesEnabled, String suitesFile, List<String> allowedImages, boolean allowDockerAccess,
            boolean allowNetworkEgress, int maxTimeoutSeconds) {}
    public record Suite(String projectKey, String key, String name, String description, String runnerImage, List<String> command,
            List<String> reportDirs, String network, boolean dockerAccess, int maxTimeoutSeconds, boolean enabled) {}

    public List<Project> projects() {
        return jdbc.query("SELECT * FROM qa_projects ORDER BY key", (r, i) -> {
            try {
                return new Project(r.getString("key"), r.getString("name"), r.getString("repository_url"), r.getString("default_ref"),
                        r.getBoolean("enabled"), suites(r.getString("key")), r.getBoolean("repository_suites_enabled"), r.getString("suites_file"),
                        mapper.readValue(r.getString("allowed_images"), new TypeReference<List<String>>() {}), r.getBoolean("allow_docker_access"),
                        r.getBoolean("allow_network_egress"), r.getInt("max_timeout_seconds"));
            } catch (java.io.IOException ex) {
                throw new SQLException("Invalid project policy", ex);
            }
        });
    }

    public Optional<Project> project(String key) {
        return projects().stream().filter(p -> p.key().equals(key)).findFirst();
    }

    public Optional<Suite> suite(String projectKey, String suiteKey) {
        return suites(projectKey).stream().filter(s -> s.key().equals(suiteKey)).findFirst();
    }

    private List<Suite> suites(String projectKey) {
        return jdbc.query("SELECT * FROM qa_test_suites WHERE project_key=? ORDER BY key", this::suite, projectKey);
    }

    private Suite suite(ResultSet r, int i) throws SQLException {
        try {
            return new Suite(r.getString("project_key"), r.getString("key"), r.getString("name"), r.getString("description"),
                    r.getString("runner_image"), mapper.readValue(r.getString("command"), new TypeReference<List<String>>() {}),
                    mapper.readValue(r.getString("report_dirs"), new TypeReference<List<String>>() {}), r.getString("network"),
                    r.getBoolean("docker_access"), r.getInt("max_timeout_seconds"), r.getBoolean("enabled"));
        } catch (Exception ex) {
            throw new SQLException("Invalid suite definition", ex);
        }
    }
}
