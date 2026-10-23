package com.enterprise.agentharness.notify;

import com.enterprise.agentharness.governance.AuditService;
import com.enterprise.agentharness.run.RunException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outgoing webhooks for any EAHP event recorded in {@code audit_events} (run lifecycle, approvals, QA results). A
 * subscription keeps a cursor into the audit log, so delivery is ordered and at least once: an event is only passed
 * when the receiver answers 2xx, otherwise the same event is retried with exponential backoff (10 s up to 1 h), and
 * after {@link #PAUSE_AFTER} consecutive failures the subscription pauses until an approver resumes it. Each request
 * is signed with HMAC-SHA256 over the body ({@code X-EAHP-Signature: sha256=<hex>}) using a secret shown once, at
 * creation. Only approvers register URLs; redirects are never followed.
 */
@Service
public class Webhooks {
    private static final Logger log = LoggerFactory.getLogger(Webhooks.class);
    static final int PAUSE_AFTER = 20;
    static final int BATCH = 20;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    private final SecureRandom random = new SecureRandom();

    public Webhooks(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public record Subscription(UUID id, String name, String url, List<String> events, boolean enabled, long cursorId, int consecutiveFailures,
            Instant nextAttemptAt, Instant lastDeliveryAt, String lastError, String createdBy, Instant createdAt) {}
    /** Returned once, at creation: the only time the signing secret is shown. */
    public record Created(Subscription subscription, String secret) {}
    public record Delivery(long id, long auditEventId, String action, Integer statusCode, String error, Integer durationMs, Instant attemptedAt) {}

    public List<Subscription> list() { return jdbc.query("SELECT * FROM webhook_subscriptions ORDER BY created_at", this::map); }

    public Subscription get(UUID id) {
        var rows = jdbc.query("SELECT * FROM webhook_subscriptions WHERE id=?", this::map, id);
        if (rows.isEmpty()) throw new RunException(HttpStatus.NOT_FOUND, "Webhook not found: " + id);
        return rows.get(0);
    }

    /** {@code {"name": "...", "url": "https://...", "events": ["RUN_*", "APPROVAL_REQUESTED", "QA_*"]}}; starts after the current last event. */
    @Transactional
    @PreAuthorize("hasRole('APPROVER')")
    public Created create(JsonNode body) {
        if (body == null || !body.isObject()) throw bad("A JSON object is required");
        for (var it = body.fieldNames(); it.hasNext(); ) if (!Set.of("name", "url", "events").contains(it.next())) throw bad("Unknown field");
        if (!body.path("name").isTextual() || body.get("name").textValue().isBlank() || body.get("name").textValue().length() > 120) throw bad("name is required text up to 120 characters");
        String url = url(body.get("url"));
        List<String> events = events(body.get("events"));
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        UUID id = UUID.randomUUID();
        Long last = jdbc.queryForObject("SELECT COALESCE(max(id),0) FROM audit_events", Long.class);
        jdbc.update("INSERT INTO webhook_subscriptions(id,name,url,secret,events,cursor_id,created_by) VALUES (?,?,?,?,?::jsonb,?,?)",
                id, body.get("name").textValue().strip(), url, secret, json(events), last, AuditService.actor());
        return new Created(get(id), secret);
    }

    /** Pause or resume; resuming clears the failure count and retries the event the subscription stopped at. */
    @PreAuthorize("hasRole('APPROVER')")
    public Subscription setEnabled(UUID id, boolean enabled) {
        get(id);
        jdbc.update("UPDATE webhook_subscriptions SET enabled=?, consecutive_failures=CASE WHEN ? THEN 0 ELSE consecutive_failures END, next_attempt_at=NULL WHERE id=?",
                enabled, enabled, id);
        return get(id);
    }

    @PreAuthorize("hasRole('APPROVER')")
    public void delete(UUID id) {
        get(id);
        jdbc.update("DELETE FROM webhook_subscriptions WHERE id=?", id);
    }

    public List<Delivery> deliveries(UUID id, int limit) {
        get(id);
        return jdbc.query("SELECT * FROM webhook_deliveries WHERE subscription_id=? ORDER BY id DESC LIMIT ?", (r, i) -> new Delivery(r.getLong("id"),
                r.getLong("audit_event_id"), r.getString("action"), (Integer) r.getObject("status_code"), r.getString("error"),
                (Integer) r.getObject("duration_ms"), r.getTimestamp("attempted_at").toInstant()), id, Math.max(1, Math.min(limit, 200)));
    }

    /** One dispatch cycle over every enabled, due subscription; returns the number of events delivered. */
    public int dispatchOnce() {
        int delivered = 0;
        for (var s : jdbc.query("SELECT * FROM webhook_subscriptions WHERE enabled AND (next_attempt_at IS NULL OR next_attempt_at <= clock_timestamp())", this::map)) {
            try { delivered += dispatch(s); } catch (RuntimeException ex) { log.warn("Webhook {} dispatch failed", s.id(), ex); }
        }
        return delivered;
    }

    private int dispatch(Subscription s) {
        var events = jdbc.queryForList("""
                SELECT e.id, e.actor, e.action, e.run_id, e.code, e.occurred_at, r.task_id, r.tool, r.status, r.error_code,
                       r.arguments::jsonb->>'project' AS project, r.arguments::jsonb->>'suite' AS suite, r.arguments::jsonb->>'commit' AS commit
                FROM audit_events e LEFT JOIN agent_runs r ON r.id = e.run_id WHERE e.id > ? ORDER BY e.id LIMIT ?
                """, s.cursorId(), BATCH);
        String secret = jdbc.queryForObject("SELECT secret FROM webhook_subscriptions WHERE id=?", String.class, s.id());
        int delivered = 0;
        for (var e : events) {
            long eventId = ((Number) e.get("id")).longValue();
            String action = (String) e.get("action");
            if (!matches(s.events(), action)) { advance(s.id(), eventId, false); continue; }
            byte[] body = payload(e);
            long started = System.nanoTime();
            Integer status = null;
            String error = null;
            try {
                var request = HttpRequest.newBuilder(URI.create(s.url())).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json").header("User-Agent", "EAHP-Webhooks/1")
                        .header("X-EAHP-Event", action).header("X-EAHP-Delivery", s.id() + ":" + eventId)
                        .header("X-EAHP-Signature", "sha256=" + sign(secret, body))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status < 200 || status >= 300) error = "HTTP " + status;
            } catch (java.io.IOException ex) {
                error = ex.getClass().getSimpleName();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return delivered;
            }
            int ms = (int) ((System.nanoTime() - started) / 1_000_000);
            jdbc.update("INSERT INTO webhook_deliveries(subscription_id,audit_event_id,action,status_code,error,duration_ms) VALUES (?,?,?,?,?,?)",
                    s.id(), eventId, action, status, error, ms);
            if (error != null) { fail(s, error); return delivered; }
            advance(s.id(), eventId, true);
            delivered++;
        }
        return delivered;
    }

    private void advance(UUID id, long eventId, boolean delivered) {
        jdbc.update("UPDATE webhook_subscriptions SET cursor_id=?, consecutive_failures=0, next_attempt_at=NULL, last_error=NULL"
                + (delivered ? ", last_delivery_at=clock_timestamp()" : "") + " WHERE id=?", eventId, id);
    }

    private void fail(Subscription s, String error) {
        int failures = s.consecutiveFailures() + 1;
        long backoff = Math.min(3600, 10L << Math.min(failures - 1, 9));
        boolean pause = failures >= PAUSE_AFTER;
        jdbc.update("UPDATE webhook_subscriptions SET consecutive_failures=?, last_error=?, next_attempt_at=?, enabled=? WHERE id=?",
                failures, error, Timestamp.from(Instant.now().plusSeconds(backoff)), !pause, s.id());
        if (pause) log.warn("Webhook {} paused after {} consecutive failures", s.id(), failures);
    }

    byte[] payload(Map<String, Object> e) {
        var body = new LinkedHashMap<String, Object>();
        body.put("id", e.get("id"));
        body.put("action", e.get("action"));
        body.put("actor", e.get("actor"));
        body.put("code", e.get("code"));
        body.put("occurredAt", ((Timestamp) e.get("occurred_at")).toInstant().toString());
        if (e.get("tool") != null) {
            var run = new LinkedHashMap<String, Object>();
            run.put("id", e.get("run_id").toString());
            run.put("taskId", e.get("task_id") == null ? null : e.get("task_id").toString());
            run.put("tool", e.get("tool"));
            run.put("status", e.get("status"));
            run.put("errorCode", e.get("error_code"));
            if (e.get("project") != null) {
                run.put("project", e.get("project"));
                run.put("suite", e.get("suite"));
                run.put("commit", e.get("commit"));
            }
            body.put("run", run);
        }
        try { return mapper.writeValueAsBytes(body); } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }

    /** {@code *} matches everything, {@code PREFIX_*} a prefix, anything else an exact action. */
    static boolean matches(List<String> patterns, String action) {
        for (String p : patterns) {
            if (p.equals("*") || p.equals(action) || (p.endsWith("*") && action.startsWith(p.substring(0, p.length() - 1)))) return true;
        }
        return false;
    }

    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    static String url(JsonNode value) {
        if (value == null || !value.isTextual() || value.textValue().length() > 500) throw bad("url is required");
        URI uri;
        try { uri = new URI(value.textValue().strip()); } catch (Exception ex) { throw bad("invalid url"); }
        if (uri.getScheme() == null || !(uri.getScheme().equals("https") || uri.getScheme().equals("http")) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw bad("url must be http(s)://host/path without credentials or fragment");
        }
        return uri.toString();
    }

    static List<String> events(JsonNode value) {
        if (value == null || !value.isArray() || value.isEmpty() || value.size() > 30) throw bad("events must list 1..30 event patterns, for example [\"RUN_*\"]");
        List<String> events = new ArrayList<>();
        for (JsonNode v : value) {
            if (!v.isTextual() || !v.textValue().matches("\\*|[A-Z][A-Z0-9_]{0,78}\\*?")) throw bad("event patterns look like RUN_COMPLETED, RUN_* or *");
            events.add(v.textValue());
        }
        return List.copyOf(events);
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
    private Subscription map(ResultSet r, int i) throws SQLException {
        List<String> events;
        try { events = List.of(mapper.readValue(r.getString("events"), String[].class)); } catch (Exception ex) { events = List.of(); }
        return new Subscription(r.getObject("id", UUID.class), r.getString("name"), r.getString("url"), events, r.getBoolean("enabled"),
                r.getLong("cursor_id"), r.getInt("consecutive_failures"), instant(r, "next_attempt_at"), instant(r, "last_delivery_at"),
                r.getString("last_error"), r.getString("created_by"), instant(r, "created_at"));
    }
    private static Instant instant(ResultSet r, String k) throws SQLException { var t = r.getTimestamp(k); return t == null ? null : t.toInstant(); }
    private static RunException bad(String m) { return new RunException(HttpStatus.BAD_REQUEST, m); }
}
