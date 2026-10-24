package com.enterprise.agentharness;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Single sign-on with a real RSA key and a JWKS served locally, the way a Keycloak realm publishes it. */
@SpringBootTest(properties = "eahp.worker.enabled=false")
@AutoConfigureMockMvc
class SsoApiTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final String ADMIN = UUID.randomUUID().toString();
    private static final RSAKey KEY = key(), OTHER = key();
    private static final HttpServer IDP = startIdp();
    private static final String ISSUER = "http://127.0.0.1:" + IDP.getAddress().getPort() + "/realms/eahp";

    @Autowired MockMvc mvc;

    private static RSAKey key() {
        try { return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate(); } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static HttpServer startIdp() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/realms/eahp/protocol/openid-connect/certs", ex -> {
                byte[] out = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            server.start();
            return server;
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    private static EmbeddedPostgres startPostgres() {
        try { return EmbeddedPostgres.builder().setPort(0).start(); } catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("eahp.security.admin-token", () -> ADMIN);
        registry.add("eahp.security.oidc.issuer", () -> ISSUER);
    }
    @AfterAll static void stop() throws IOException { IDP.stop(0); POSTGRES.close(); }

    private static String token(RSAKey key, String issuer, String audience, long expiresInSeconds, String user, List<String> roles) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(List.of(audience, "account")).subject(UUID.randomUUID().toString())
                .claim("preferred_username", user).claim("realm_access", Map.of("roles", roles))
                .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + expiresInSeconds * 1000)).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test
    void theConsoleLearnsHowToSignInWithoutATokenAndNoSecretIsShown() throws Exception {
        mvc.perform(get("/api/v1/auth/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.oidc.issuer").value(ISSUER)).andExpect(jsonPath("$.oidc.clientId").value("eahp-console"))
                .andExpect(jsonPath("$.oidc.scope").value("openid profile"));
    }

    @Test
    void providerTokensMapToEahpRoles() throws Exception {
        String approver = token(KEY, ISSUER, "eahp", 300, "alice", List.of("eahp-approver", "offline_access"));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + approver)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("sso:alice")).andExpect(jsonPath("$.roles.length()").value(3));
        mvc.perform(get("/api/v1/admin/storage").header("Authorization", "Bearer " + approver)).andExpect(status().isOk());

        String operator = token(KEY, ISSUER, "eahp", 300, "bob", List.of("eahp-operator"));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + operator)).andExpect(jsonPath("$.roles.length()").value(2));
        mvc.perform(get("/api/v1/admin/storage").header("Authorization", "Bearer " + operator)).andExpect(status().isForbidden());

        // Signed in, but with none of EAHP's roles: nothing is allowed.
        String nobody = token(KEY, ISSUER, "eahp", 300, "dave", List.of("offline_access"));
        mvc.perform(get("/api/v1/agent-tasks").header("Authorization", "Bearer " + nobody)).andExpect(status().isForbidden());

        // The deployment's own tokens keep working.
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + ADMIN)).andExpect(jsonPath("$.name").value("admin"));
    }

    @Test
    void tokensFromElsewhereOrForSomethingElseAreRejected() throws Exception {
        for (String bad : List.of(
                token(KEY, "http://evil.example/realms/eahp", "eahp", 300, "alice", List.of("eahp-approver")),   // other issuer
                token(KEY, ISSUER, "billing", 300, "alice", List.of("eahp-approver")),                           // not for EAHP
                token(KEY, ISSUER, "eahp", -120, "alice", List.of("eahp-approver")),                             // expired
                token(OTHER, ISSUER, "eahp", 300, "alice", List.of("eahp-approver")),                            // not the provider's key
                "eyJhbGciOiJub25lIn0." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                        ("{\"iss\":\"" + ISSUER + "\",\"aud\":\"eahp\",\"preferred_username\":\"alice\",\"realm_access\":{\"roles\":[\"eahp-approver\"]}}").getBytes(StandardCharsets.UTF_8)) + ".x")) {
            mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + bad)).andExpect(status().isUnauthorized());
        }
    }
}
