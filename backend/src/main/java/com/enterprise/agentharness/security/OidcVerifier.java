package com.enterprise.agentharness.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Single sign-on (P46): verifies OIDC access tokens from the organisation's identity provider and maps them to EAHP
 * roles. A token is accepted only when its signature checks against the provider's published keys (JWKS), its issuer
 * is exactly the configured one, its audience includes EAHP's and it is within its validity. Roles come from a token
 * claim (by default Keycloak's {@code realm_access.roles}) through a fixed mapping; a user with none of the mapped
 * roles is authenticated but may do nothing. Disabled unless {@code eahp.security.oidc.issuer} is set.
 */
@Component
public class OidcVerifier {
    private static final Logger log = LoggerFactory.getLogger(OidcVerifier.class);
    private final String issuer, audience, clientId, rolesClaim, usernameClaim;
    private final Map<String, String> roleMapping;
    private final JwtDecoder decoder;

    @org.springframework.beans.factory.annotation.Autowired
    public OidcVerifier(@Value("${eahp.security.oidc.issuer:}") String issuer, @Value("${eahp.security.oidc.jwks-uri:}") String jwksUri,
            @Value("${eahp.security.oidc.audience:eahp}") String audience, @Value("${eahp.security.oidc.client-id:eahp-console}") String clientId,
            @Value("${eahp.security.oidc.roles-claim:realm_access.roles}") String rolesClaim,
            @Value("${eahp.security.oidc.username-claim:preferred_username}") String usernameClaim,
            @Value("${eahp.security.oidc.role-viewer:eahp-viewer}") String viewerRole, @Value("${eahp.security.oidc.role-operator:eahp-operator}") String operatorRole,
            @Value("${eahp.security.oidc.role-approver:eahp-approver}") String approverRole) {
        this.issuer = issuer.strip(); this.audience = audience; this.clientId = clientId; this.rolesClaim = rolesClaim; this.usernameClaim = usernameClaim;
        this.roleMapping = Map.of(viewerRole, "VIEWER", operatorRole, "OPERATOR", approverRole, "APPROVER");
        if (this.issuer.isEmpty()) { this.decoder = null; return; }
        if (!this.issuer.matches("https?://[^\\s?#]+")) throw new IllegalStateException("eahp.security.oidc.issuer must be an http(s) URL");
        // The keys may be fetched on an internal address (for example a container name) while the issuer is the public URL.
        String keys = jwksUri.isBlank() ? this.issuer.replaceAll("/+$", "") + "/protocol/openid-connect/certs" : jwksUri.strip();
        var nimbus = NimbusJwtDecoder.withJwkSetUri(keys).build();
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(this.issuer), jwt -> {
            List<String> aud = jwt.getAudience() == null ? List.of() : jwt.getAudience();
            return aud.contains(audience) ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "The token is not meant for EAHP", null));
        }));
        this.decoder = nimbus;
    }

    /** For tests: a decoder with a given key source. */
    OidcVerifier(String issuer, String audience, JwtDecoder decoder) {
        this.issuer = issuer; this.audience = audience; this.clientId = "eahp-console"; this.rolesClaim = "realm_access.roles";
        this.usernameClaim = "preferred_username"; this.decoder = decoder;
        this.roleMapping = Map.of("eahp-viewer", "VIEWER", "eahp-operator", "OPERATOR", "eahp-approver", "APPROVER");
    }

    public record Principal(String name, List<String> roles) {}
    /** What the console needs to start a login: null when SSO is off. */
    public record LoginConfig(String issuer, String clientId, String scope) {}

    public boolean enabled() { return decoder != null; }

    public LoginConfig loginConfig() { return enabled() ? new LoginConfig(issuer, clientId, "openid profile") : null; }

    /** The principal for a valid token, or empty (invalid, expired, wrong issuer or audience, unreachable keys). */
    public Optional<Principal> verify(String token) {
        if (decoder == null || token.length() > 16_384) return Optional.empty();
        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtException ex) {
            log.debug("Rejected OIDC token: {}", ex.getMessage());
            return Optional.empty();
        }
        String user = jwt.getClaimAsString(usernameClaim);
        if (user == null || user.isBlank()) user = jwt.getSubject();
        if (user == null || !user.matches("[\\p{L}\\p{N}._@+-]{1,80}")) return Optional.empty();
        return Optional.of(new Principal("sso:" + user, roles(claim(jwt.getClaims(), rolesClaim))));
    }

    /** EAHP roles from the provider's role names; a higher role includes the lower ones. */
    List<String> roles(Collection<?> granted) {
        Set<String> mapped = new LinkedHashSet<>();
        for (Object g : granted) { String r = roleMapping.get(String.valueOf(g)); if (r != null) mapped.add(r); }
        var out = new ArrayList<String>();
        if (mapped.contains("APPROVER") || mapped.contains("OPERATOR") || mapped.contains("VIEWER")) out.add("VIEWER");
        if (mapped.contains("APPROVER") || mapped.contains("OPERATOR")) out.add("OPERATOR");
        if (mapped.contains("APPROVER")) out.add("APPROVER");
        return List.copyOf(out);
    }

    /** A dotted path into the claims ("realm_access.roles"), as a collection; empty when absent. */
    static Collection<?> claim(Map<String, Object> claims, String path) {
        Object value = claims;
        for (String part : path.split("\\.")) {
            if (!(value instanceof Map<?, ?> map)) return List.of();
            value = map.get(part);
        }
        if (value instanceof Collection<?> c) return c;
        if (value instanceof String s) return List.of(s.split("[ ,]+"));
        return List.of();
    }
}
