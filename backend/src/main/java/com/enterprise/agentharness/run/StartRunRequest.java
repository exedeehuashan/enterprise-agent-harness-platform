package com.enterprise.agentharness.run;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import org.springframework.http.HttpStatus;

public record StartRunRequest(String tool, int timeoutSeconds, JsonNode arguments) {
    public StartRunRequest(String tool, int timeoutSeconds) { this(tool, timeoutSeconds, null); }

    /** Tool names are checked against the registered executors; arguments are validated by the tool itself. */
    public static StartRunRequest parse(JsonNode body, Set<String> tools) {
        if (body == null || !body.isObject()) throw bad("A JSON object is required");
        var fields = body.fieldNames();
        while (fields.hasNext()) if (!Set.of("tool", "timeoutSeconds", "arguments").contains(fields.next())) throw bad("Unknown run field");
        String tool = "DEMO_ANALYZE";
        if (body.has("tool")) {
            if (!body.get("tool").isTextual() || !tools.contains(body.get("tool").textValue())) throw bad("Tool is not allowed");
            tool = body.get("tool").textValue();
        }
        JsonNode arguments = body.get("arguments");
        if (arguments != null && arguments.isNull()) arguments = null;
        if (arguments != null && !arguments.isObject()) throw bad("arguments must be a JSON object");
        int timeout = 30;
        if (body.has("timeoutSeconds")) {
            var value = body.get("timeoutSeconds");
            if (!value.isIntegralNumber() || !value.canConvertToInt()) throw bad("Invalid timeoutSeconds");
            timeout = value.intValue();
        }
        if (timeout < 1 || timeout > 300) throw bad("timeoutSeconds must be 1..300");
        return new StartRunRequest(tool, timeout, arguments);
    }
    public static void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw bad("Idempotency-Key must be 1..128 ASCII letters, digits or ._:-");
    }
    private static RunException bad(String message) { return new RunException(HttpStatus.BAD_REQUEST, message); }
}
