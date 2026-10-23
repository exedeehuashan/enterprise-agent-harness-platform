package com.enterprise.agentharness.governance;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    public AuditService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void record(String actor,String action,UUID runId,String code) {
        jdbc.update("INSERT INTO audit_events(actor,action,run_id,code) VALUES (?,?,?,?)",actor,action,runId,code);
    }
    public static String actor() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        return auth==null?"system":auth.getName();
    }
}
