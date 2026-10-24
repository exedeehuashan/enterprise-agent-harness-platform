package com.enterprise.agentharness.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads a k6 {@code --summary-export} file into EAHP's performance summary (P38): request count and rate, latency
 * (avg, p90, p95, max in milliseconds), the HTTP error rate and the check pass rate. The file is written by
 * repository code, so it is size-limited and every value is checked to be a finite, non-negative number.
 */
public final class PerformanceReports {
    static final long MAX_BYTES = 1024 * 1024;

    private PerformanceReports() {}

    /** The summary, or a {@code {"error": ...}} map when the file is missing or unusable. */
    public static Map<String, Object> read(Path checkout, String relative) {
        Path file = checkout.resolve(relative).normalize();
        try {
            if (!file.startsWith(checkout) || !Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return error("no " + relative + " was written");
            if (Files.size(file) > MAX_BYTES) return error(relative + " is larger than 1 MiB");
            return fromK6(new ObjectMapper().readTree(file.toFile()));
        } catch (IOException ex) {
            return error(relative + " is not valid JSON");
        } catch (IllegalArgumentException ex) {
            return error(ex.getMessage());
        }
    }

    static Map<String, Object> fromK6(JsonNode root) {
        var metrics = root.path("metrics");
        var duration = metrics.path("http_req_duration");
        var reqs = metrics.path("http_reqs");
        if (!duration.isObject() || !reqs.isObject()) throw new IllegalArgumentException("the summary has no http_req_duration or http_reqs metrics");
        var out = new LinkedHashMap<String, Object>();
        out.put("format", "k6");
        out.put("requests", (long) number(reqs, "count"));
        out.put("requestsPerSecond", number(reqs, "rate"));
        out.put("avgMs", number(duration, "avg"));
        out.put("p90Ms", number(duration, "p(90)"));
        out.put("p95Ms", number(duration, "p(95)"));
        out.put("maxMs", number(duration, "max"));
        // Rate metrics carry their rate in "value" (for http_req_failed: the share of failed requests).
        out.put("errorRate", metrics.path("http_req_failed").isObject() ? rate(metrics.get("http_req_failed")) : null);
        out.put("checksRate", metrics.path("checks").isObject() ? rate(metrics.get("checks")) : null);
        return out;
    }

    private static double number(JsonNode node, String field) {
        var v = node.get(field);
        if (v == null || !v.isNumber() || !Double.isFinite(v.doubleValue()) || v.doubleValue() < 0) throw new IllegalArgumentException("the summary has no valid " + field);
        return v.doubleValue();
    }
    private static double rate(JsonNode node) {
        double v = number(node, "value");
        if (v > 1) throw new IllegalArgumentException("a rate is above 1");
        return v;
    }
    private static Map<String, Object> error(String message) { return Map.of("error", message); }
}
