package com.enterprise.agentharness.security;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Named API tokens; /api/v1/admin is approver-only. */
@RestController
@RequestMapping("/api/v1/admin/tokens")
public class ApiTokenController {
    private final ApiTokens tokens;
    public ApiTokenController(ApiTokens tokens) { this.tokens = tokens; }

    @GetMapping public List<ApiTokens.Token> list() { return tokens.list(); }
    @PostMapping public ResponseEntity<ApiTokens.Issued> issue(@RequestBody JsonNode body) { return ResponseEntity.status(HttpStatus.CREATED).body(tokens.issue(body)); }
    @PostMapping("/{id}/revoke") public ApiTokens.Token revoke(@PathVariable UUID id) { return tokens.revoke(id); }
}
