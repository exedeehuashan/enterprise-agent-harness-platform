package com.enterprise.agentharness.security;

import java.util.HashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public: how the console can sign in. Only the identity provider's public client settings, never a secret. */
@RestController
public class AuthConfigController {
    private final OidcVerifier oidc;
    public AuthConfigController(OidcVerifier oidc) { this.oidc = oidc; }

    @GetMapping("/api/v1/auth/config")
    public Map<String, Object> config() {
        var body = new HashMap<String, Object>();
        body.put("oidc", oidc.loginConfig());
        return body;
    }
}
