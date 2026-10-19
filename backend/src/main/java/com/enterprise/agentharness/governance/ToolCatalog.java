package com.enterprise.agentharness.governance;

import com.enterprise.agentharness.run.RunException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ToolCatalog {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public ToolCatalog(JdbcTemplate jdbc,ObjectMapper mapper) { this.jdbc=jdbc;this.mapper=mapper; }
    public record ToolDefinition(String name,int version,String target,boolean enabled,boolean requiresApproval,boolean retryable) {}
    public List<ToolDefinition> list() {
        return jdbc.query("SELECT * FROM tool_definitions ORDER BY name",(r,i)->new ToolDefinition(r.getString("name"),r.getInt("version"),r.getString("target"),r.getBoolean("enabled"),r.getBoolean("requires_approval"),r.getBoolean("retryable")));
    }
    public ToolDefinition require(String name) {
        return list().stream().filter(t->t.name().equals(name) && t.enabled()
                && t.version()==1 && t.target().equals("local-demo")
                && ((t.name().equals("DEMO_ANALYZE") && !t.requiresApproval() && t.retryable())
                    || (t.name().equals("DEMO_REVIEW") && t.requiresApproval() && !t.retryable())))
                .findFirst().orElseThrow(()->new RunException(HttpStatus.CONFLICT,"Tool is disabled or not allowed"));
    }
    public String digest(UUID runId,UUID taskId,String tool,int version,String target,String title,String description,int timeout) {
        try {
            byte[] bytes=mapper.writeValueAsBytes(Arrays.asList(runId.toString(),taskId.toString(),tool,version,target,title,description,timeout));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {throw new IllegalStateException("Cannot calculate execution digest",ex);}
    }
}
