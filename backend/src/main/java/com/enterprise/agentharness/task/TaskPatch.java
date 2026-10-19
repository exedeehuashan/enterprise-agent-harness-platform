package com.enterprise.agentharness.task;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import org.springframework.http.HttpStatus;

public record TaskPatch(boolean hasTitle, String title, boolean hasDescription, String description) {
    public static TaskPatch parse(JsonNode body) {
        if (body == null || !body.isObject() || body.isEmpty()) throw invalid("Provide title or description");
        var fields = body.fieldNames();
        while (fields.hasNext()) {
            if (!Set.of("title", "description").contains(fields.next())) throw invalid("Only title and description may be edited");
        }
        if (body.has("title") && !body.get("title").isTextual()) throw invalid("title must be a string");
        if (body.has("description") && !body.get("description").isNull() && !body.get("description").isTextual()) {
            throw invalid("description must be a string or null");
        }
        return new TaskPatch(body.has("title"), body.has("title") ? body.get("title").textValue() : null,
                body.has("description"), body.hasNonNull("description") ? body.get("description").textValue() : null);
    }

    public static long expectedVersion(String header) {
        if (header == null) throw new TaskEditException(HttpStatus.PRECONDITION_REQUIRED, "If-Match is required");
        if (!header.matches("\"(0|[1-9][0-9]*)\"")) throw invalid("If-Match must contain one quoted numeric version");
        try { return Long.parseLong(header.substring(1, header.length() - 1)); }
        catch (NumberFormatException ex) { throw invalid("Invalid If-Match version"); }
    }

    private static TaskEditException invalid(String message) { return new TaskEditException(HttpStatus.BAD_REQUEST, message); }
}
