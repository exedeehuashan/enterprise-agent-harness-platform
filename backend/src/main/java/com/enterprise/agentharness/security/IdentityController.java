package com.enterprise.agentharness.security;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets clients adapt to the caller's roles; authorization is still enforced per endpoint. */
@RestController
@RequestMapping("/api/v1")
public class IdentityController {
    public record Identity(String name, List<String> roles) {}

    @GetMapping("/me")
    public Identity me(Authentication auth) {
        return new Identity(auth.getName(), auth.getAuthorities().stream()
                .map(a -> a.getAuthority().replaceFirst("^ROLE_", "")).sorted().toList());
    }
}
