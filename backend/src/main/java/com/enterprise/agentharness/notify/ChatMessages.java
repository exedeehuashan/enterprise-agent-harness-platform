package com.enterprise.agentharness.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Notification messages for chat platforms and email (P41): one short, human-readable summary of an EAHP event, in
 * each platform's incoming-webhook format, plus the platforms' own request signing and success checks. Event fields
 * come from EAHP's records; free text (task titles, failure messages) is never included, so a message cannot carry
 * prompt or markup injection from repository content beyond project, suite and tool names, which are validated keys.
 */
public final class ChatMessages {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChatMessages() {}

    /** A title and body lines, rendered per platform. */
    public record Message(String title, String body, String link) {
        public String text() { return title + "\n" + body + (link == null ? "" : "\n" + link); }
    }

    /** The summary of one audit event (as {@link Webhooks} loads it, with its run). */
    public static Message summarize(Map<String, Object> e, String publicUrl) {
        String action = (String) e.get("action"), code = (String) e.get("code");
        String tool = (String) e.get("tool"), project = (String) e.get("project"), suite = (String) e.get("suite"), commit = (String) e.get("commit");
        String target = project == null ? (tool == null ? "" : tool) : project + (suite == null ? "" : "/" + suite) + (commit == null ? "" : " @ " + commit.substring(0, Math.min(12, commit.length())));
        String title, body;
        switch (action) {
            case "QA_RESULT_INGESTED" -> {
                boolean passed = "PASSED".equals(code);
                title = (passed ? "✅ QA passed: " : "❌ QA " + label(code) + ": ") + target;
                body = "Result of " + tool + " recorded by EAHP.";
            }
            case "QA_QUARANTINE_SUGGESTED" -> {
                title = "⚠️ Flaky test suggested for quarantine: " + (project == null ? "" : project + "/") + code;
                body = "A test passed and failed at the same commit repeatedly. An approver can confirm or dismiss it on the Quality page.";
            }
            case "APPROVAL_REQUESTED" -> {
                title = "🔐 Approval needed: " + tool + (target.isEmpty() || target.equals(tool) ? "" : " for " + target);
                body = "A run waits for an approver.";
            }
            default -> {
                if (action.startsWith("RUN_")) {
                    String status = action.substring(4);
                    title = (status.equals("COMPLETED") ? "✅ " : status.equals("FAILED") || status.equals("NEEDS_REVIEW") ? "❌ " : "ℹ️ ")
                            + "Run " + label(status) + ": " + (tool == null ? "" : tool) + (project == null ? "" : " " + target);
                    body = e.get("error_code") == null ? "Status " + e.get("status") + "." : "Error " + e.get("error_code") + ".";
                } else {
                    title = "EAHP " + label(action) + (code == null ? "" : ": " + code);
                    body = target.isEmpty() ? "Event recorded by EAHP." : target;
                }
            }
        }
        String link = publicUrl == null || publicUrl.isBlank() || e.get("run_id") == null ? null
                : publicUrl.replaceAll("/+$", "") + "/runs/" + e.get("run_id");
        return new Message(title, body, link);
    }

    /** The request body for a chat platform. */
    public static byte[] body(String format, Message m, String secret, long epochSeconds) {
        ObjectNode root = MAPPER.createObjectNode();
        switch (format) {
            case "slack" -> root.put("text", "*" + m.title() + "*\n" + m.body() + (m.link() == null ? "" : "\n<" + m.link() + "|Open in EAHP>"));
            case "dingtalk" -> {
                root.put("msgtype", "markdown");
                root.putObject("markdown").put("title", m.title()).put("text", "### " + m.title() + "\n\n" + m.body() + (m.link() == null ? "" : "\n\n[Open in EAHP](" + m.link() + ")"));
            }
            case "wecom" -> {
                root.put("msgtype", "markdown");
                root.putObject("markdown").put("content", "**" + m.title() + "**\n" + m.body() + (m.link() == null ? "" : "\n[Open in EAHP](" + m.link() + ")"));
            }
            case "feishu" -> {
                if (secret != null && !secret.isBlank()) root.put("timestamp", String.valueOf(epochSeconds)).put("sign", feishuSign(secret, epochSeconds));
                root.put("msg_type", "text");
                root.putObject("content").put("text", m.text());
            }
            default -> throw new IllegalArgumentException("not a chat format: " + format);
        }
        try { return MAPPER.writeValueAsBytes(root); } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }

    /** DingTalk's optional signing ("加签") adds timestamp (ms) and sign to the URL. */
    public static String signedUrl(String format, String url, String secret, long epochMillis) {
        if (!format.equals("dingtalk") || secret == null || secret.isBlank()) return url;
        String sign = Base64.getEncoder().encodeToString(hmac(secret.getBytes(StandardCharsets.UTF_8), (epochMillis + "\n" + secret).getBytes(StandardCharsets.UTF_8)));
        return url + (url.contains("?") ? "&" : "?") + "timestamp=" + epochMillis + "&sign=" + URLEncoder.encode(sign, StandardCharsets.UTF_8);
    }

    /** Feishu signs with the key {@code timestamp + "\n" + secret} over an empty message. */
    static String feishuSign(String secret, long epochSeconds) {
        return Base64.getEncoder().encodeToString(hmac((epochSeconds + "\n" + secret).getBytes(StandardCharsets.UTF_8), new byte[0]));
    }

    /**
     * Chat platforms answer HTTP 200 even when they reject a message; null when the response means success,
     * otherwise a short error.
     */
    public static String failure(String format, int status, String responseBody) {
        if (status < 200 || status >= 300) return "HTTP " + status;
        if (format.equals("slack")) return null;
        try {
            JsonNode r = MAPPER.readTree(responseBody == null || responseBody.isBlank() ? "{}" : responseBody);
            int code = format.equals("feishu") ? r.path("code").asInt(r.path("StatusCode").asInt(0)) : r.path("errcode").asInt(0);
            if (code == 0) return null;
            String msg = format.equals("feishu") ? r.path("msg").asText("") : r.path("errmsg").asText("");
            return clip("error " + code + (msg.isEmpty() ? "" : ": " + msg));
        } catch (java.io.IOException ex) {
            return "unreadable response";
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
    private static String label(String s) { return s == null ? "unknown" : s.replace('_', ' ').toLowerCase(); }
    private static String clip(String s) { return s.length() > 200 ? s.substring(0, 200) : s; }
}
