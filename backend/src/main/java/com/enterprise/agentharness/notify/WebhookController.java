package com.enterprise.agentharness.notify;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Webhook administration; everything under /api/v1/admin is approver-only. */
@RestController
@RequestMapping("/api/v1/admin/webhooks")
public class WebhookController {
    private final Webhooks webhooks;
    public WebhookController(Webhooks webhooks) { this.webhooks = webhooks; }

    @GetMapping public List<Webhooks.Subscription> list() { return webhooks.list(); }
    @PostMapping public ResponseEntity<Webhooks.Created> create(@RequestBody JsonNode body) { return ResponseEntity.status(HttpStatus.CREATED).body(webhooks.create(body)); }
    @PutMapping("/{id}/enabled")
    public Webhooks.Subscription enabled(@PathVariable UUID id, @RequestBody JsonNode body) {
        if (body == null || !body.path("enabled").isBoolean() || body.size() != 1) throw new com.enterprise.agentharness.run.RunException(HttpStatus.BAD_REQUEST, "Body must be {\"enabled\": true|false}");
        return webhooks.setEnabled(id, body.get("enabled").booleanValue());
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id) { webhooks.delete(id); return ResponseEntity.noContent().build(); }
    @GetMapping("/{id}/deliveries") public List<Webhooks.Delivery> deliveries(@PathVariable UUID id, @RequestParam(defaultValue = "50") int limit) { return webhooks.deliveries(id, limit); }
}
