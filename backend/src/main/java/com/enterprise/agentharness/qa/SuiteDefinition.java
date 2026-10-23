package com.enterprise.agentharness.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * The exact suite a run executes, from the registry or the project's repository file. It is stored in the run's
 * arguments, so the approval digest covers the image and command, and the executor never re-reads a moving source.
 */
public record SuiteDefinition(String source, String key, String name, String description, String image, List<String> command,
        List<String> reportDirs, String network, boolean dockerAccess, int maxTimeoutSeconds, String selection, List<String> paths) {
    public static final String REGISTRY = "registry";
    public static final String REPOSITORY = "repository";
    /** The suite is a Maven build that may be narrowed to affected modules ({@code -pl ... -am}) for pull requests. */
    public static final String MAVEN_MODULES = "maven-modules";
    /** The suite only needs to run for pull requests that change a file under one of its {@code paths}. */
    public static final String PATHS = "paths";

    public SuiteDefinition {
        paths = paths == null ? List.of() : List.copyOf(paths);
    }

    static SuiteDefinition fromRegistry(QaRegistry.Suite s) {
        return new SuiteDefinition(REGISTRY, s.key(), s.name(), s.description(), s.runnerImage(), s.command(), s.reportDirs(),
                s.network(), s.dockerAccess(), s.maxTimeoutSeconds(), null, List.of());
    }

    public ObjectNode toJson() {
        var node = JsonNodeFactory.instance.objectNode();
        node.put("source", source).put("key", key).put("name", name).put("description", description).put("image", image)
                .put("network", network).put("dockerAccess", dockerAccess).put("maxTimeoutSeconds", maxTimeoutSeconds);
        if (selection != null) node.put("selection", selection); // absent rather than null, so older definitions keep their digest
        if (!paths.isEmpty()) paths.forEach(node.putArray("paths")::add);
        command.forEach(node.putArray("command")::add);
        reportDirs.forEach(node.putArray("reportDirs")::add);
        return node;
    }

    public static SuiteDefinition fromJson(JsonNode n) {
        return new SuiteDefinition(n.get("source").asText(), n.get("key").asText(), n.get("name").asText(),
                n.get("description").isNull() ? null : n.get("description").asText(), n.get("image").asText(), texts(n.get("command")),
                texts(n.get("reportDirs")), n.get("network").asText(), n.get("dockerAccess").asBoolean(), n.get("maxTimeoutSeconds").asInt(),
                n.hasNonNull("selection") ? n.get("selection").asText() : null, n.has("paths") ? texts(n.get("paths")) : List.of());
    }

    private static List<String> texts(JsonNode array) {
        var values = new ArrayList<String>();
        array.forEach(v -> values.add(v.asText()));
        return List.copyOf(values);
    }
}
