package com.enterprise.agentharness;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class IdentityApiTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final String ADMIN=UUID.randomUUID().toString(),OPERATOR=UUID.randomUUID().toString(),VIEWER=UUID.randomUUID().toString();
    @Autowired MockMvc mvc;

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().setPort(0).start();
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("eahp.security.admin-token", () -> ADMIN);
        registry.add("eahp.security.operator-token", () -> OPERATOR);
        registry.add("eahp.security.viewer-token", () -> VIEWER);
    }

    @AfterAll
    static void stopPostgres() throws IOException { POSTGRES.close(); }

    @Test
    void reportsAuthenticatedIdentityAndRoles() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("admin")).andExpect(jsonPath("$.roles",contains("APPROVER","OPERATOR","VIEWER")));
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+OPERATOR)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("operator")).andExpect(jsonPath("$.roles",contains("OPERATOR","VIEWER")));
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+VIEWER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles",contains("VIEWER")));
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+UUID.randomUUID())).andExpect(status().isUnauthorized());
    }
}
