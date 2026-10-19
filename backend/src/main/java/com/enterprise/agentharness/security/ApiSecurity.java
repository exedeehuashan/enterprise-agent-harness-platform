package com.enterprise.agentharness.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
public class ApiSecurity {
    @Bean
    SecurityFilterChain apiFilterChain(HttpSecurity http, ObjectMapper mapper,
            @Value("${eahp.security.admin-token:}") String admin,
            @Value("${eahp.security.operator-token:}") String operator,
            @Value("${eahp.security.viewer-token:}") String viewer) throws Exception {
        if (!validToken(admin)) throw new IllegalStateException("Configure a random EAHP_SECURITY_ADMIN_TOKEN (32..256 URL-safe characters)");
        var identities = new ArrayList<Identity>();
        identities.add(identity("admin", admin, "VIEWER", "OPERATOR", "APPROVER"));
        if (!operator.isEmpty()) identities.add(optional("operator",operator,"VIEWER","OPERATOR"));
        if (!viewer.isEmpty()) identities.add(optional("viewer",viewer,"VIEWER"));
        for (int i=0;i<identities.size();i++) for (int j=i+1;j<identities.size();j++) {
            if (MessageDigest.isEqual(identities.get(i).hash(),identities.get(j).hash())) throw new IllegalStateException("API credentials must be distinct");
        }
        http.csrf(c -> c.disable()).formLogin(c -> c.disable()).httpBasic(c -> c.disable()).logout(c -> c.disable())
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .exceptionHandling(c -> c.authenticationEntryPoint((req,res,ex) -> error(mapper,req,res,401))
                        .accessDeniedHandler((req,res,ex) -> error(mapper,req,res,403)))
                .authorizeHttpRequests(c -> c.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/v1/approvals/**","/api/v1/audit-events").hasRole("APPROVER")
                        .requestMatchers(HttpMethod.GET,"/api/v1/**").hasRole("VIEWER")
                        .requestMatchers("/api/v1/**").hasRole("OPERATOR")
                        .anyRequest().denyAll())
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                            throws IOException, ServletException {
                        String header = req.getHeader("Authorization");
                        if (header != null) {
                            if (java.util.Collections.list(req.getHeaders("Authorization")).size()!=1 || !header.regionMatches(true,0,"Bearer ",0,7) || !validToken(header.substring(7))) { error(mapper,req,res,401); return; }
                            byte[] actual = hash(header.substring(7));
                            Identity matched = null;
                            for (Identity candidate : identities) if (MessageDigest.isEqual(actual,candidate.hash())) matched=candidate;
                            if (matched == null) { error(mapper,req,res,401); return; }
                            var auth = new UsernamePasswordAuthenticationToken(matched.name(),null,
                                    matched.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_"+r)).toList());
                            var context = SecurityContextHolder.createEmptyContext();context.setAuthentication(auth);SecurityContextHolder.setContext(context);
                        }
                        chain.doFilter(req,res);
                    }
                }, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    private static Identity optional(String name,String token,String... roles) {
        if (!validToken(token)) throw new IllegalStateException("Invalid configured API credential for " + name);
        return identity(name,token,roles);
    }
    private static Identity identity(String name,String token,String... roles) { return new Identity(name,hash(token),List.of(roles)); }
    private static boolean validToken(String token) { return token.matches("[A-Za-z0-9_-]{32,256}"); }
    private static byte[] hash(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static void error(ObjectMapper mapper,HttpServletRequest req,HttpServletResponse res,int status) throws IOException {
        res.setStatus(status);res.setContentType("application/json");
        if (status==401) res.setHeader("WWW-Authenticate","Bearer");
        String text=status==401?"Unauthorized":"Forbidden";
        mapper.writeValue(res.getOutputStream(),Map.of("timestamp",Instant.now().toString(),"status",status,"error",text,
                "message",text,"path",req.getRequestURI(),"fieldErrors",List.of()));
    }
    private record Identity(String name,byte[] hash,List<String> roles) {}
}
