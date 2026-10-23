package com.enterprise.agentharness.governance;

import com.enterprise.agentharness.run.RunException;
import com.enterprise.agentharness.tool.ToolRegistry;
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
    private final ToolRegistry registry;
    public ToolCatalog(JdbcTemplate jdbc,ObjectMapper mapper,ToolRegistry registry) { this.jdbc=jdbc;this.mapper=mapper;this.registry=registry; }
    public record ToolDefinition(String name,int version,String target,boolean enabled,boolean requiresApproval,boolean retryable) {}
    public List<ToolDefinition> list() {
        return jdbc.query("SELECT * FROM tool_definitions ORDER BY name",(r,i)->new ToolDefinition(r.getString("name"),r.getInt("version"),r.getString("target"),r.getBoolean("enabled"),r.getBoolean("requires_approval"),r.getBoolean("retryable")));
    }
    /** An enabled definition whose policy exactly matches its code-owned executor. */
    public ToolDefinition require(String name) {
        var executor=registry.find(name).orElseThrow(ToolCatalog::notAllowed);
        return list().stream().filter(t->t.name().equals(name) && t.enabled()
                && t.version()==executor.version() && t.target().equals(executor.target())
                && t.requiresApproval()==executor.requiresApproval() && t.retryable()==executor.retryable())
                .findFirst().orElseThrow(ToolCatalog::notAllowed);
    }
    private static RunException notAllowed() {return new RunException(HttpStatus.CONFLICT,"Tool is disabled or not allowed");}
    public String digest(UUID runId,UUID taskId,String tool,int version,String target,String title,String description,int timeout) {
        return digest(runId,taskId,tool,version,target,title,description,timeout,null);
    }
    /** Arguments are appended only when present, so digests of argument-free runs are unchanged. */
    public String digest(UUID runId,UUID taskId,String tool,int version,String target,String title,String description,int timeout,String arguments) {
        try {
            var fields=new java.util.ArrayList<Object>(Arrays.asList(runId.toString(),taskId.toString(),tool,version,target,title,description,timeout));
            if (arguments!=null) fields.add(arguments);
            byte[] bytes=mapper.writeValueAsBytes(fields);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {throw new IllegalStateException("Cannot calculate execution digest",ex);}
    }
}
