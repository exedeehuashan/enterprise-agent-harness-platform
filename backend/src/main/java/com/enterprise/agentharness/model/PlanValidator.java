package com.enterprise.agentharness.model;

import com.enterprise.agentharness.governance.ToolCatalog;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PlanValidator {
    private final ObjectMapper strict;
    private final ToolCatalog tools;
    private final com.enterprise.agentharness.tool.ToolRegistry registry;
    public PlanValidator(ObjectMapper mapper,ToolCatalog tools,com.enterprise.agentharness.tool.ToolRegistry registry) {
        this.registry=registry;
        this.strict=mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.tools=tools;
    }
    public JsonNode validate(String json) {
        if(json==null || json.length()>8192 || json.getBytes(StandardCharsets.UTF_8).length>8192) throw invalid();
        try {
            JsonNode plan=strict.readTree(json);
            fields(plan,Set.of("schemaVersion","summary","steps"));
            if(!plan.get("schemaVersion").isIntegralNumber() || !plan.get("schemaVersion").canConvertToInt() || plan.get("schemaVersion").intValue()!=1) throw invalid();
            var summary=plan.get("summary");
            if(!summary.isTextual() || summary.textValue().isBlank() || summary.textValue().length()>500) throw invalid();
            var steps=plan.get("steps");
            if(!steps.isArray() || steps.isEmpty() || steps.size()>5) throw invalid();
            for(JsonNode step:steps) {
                fields(step,Set.of("tool","toolVersion","target","timeoutSeconds"));
                if(!step.get("tool").isTextual() || !step.get("target").isTextual()) throw invalid();
                var policy=tools.require(step.get("tool").textValue());
                if(!registry.acceptsNoArguments(step.get("tool").textValue())) throw invalid();
                if(!step.get("toolVersion").isIntegralNumber() || !step.get("toolVersion").canConvertToInt()
                        || step.get("toolVersion").intValue()!=policy.version() || !step.get("target").textValue().equals(policy.target())) throw invalid();
                var timeout=step.get("timeoutSeconds");
                if(!timeout.isIntegralNumber() || !timeout.canConvertToInt() || timeout.intValue()<1 || timeout.intValue()>300) throw invalid();
            }
            return plan;
        } catch(ModelFailure ex) {throw ex;}
        catch(Exception ex) {throw invalid();}
    }
    private static void fields(JsonNode node,Set<String> names) {
        if(node==null || !node.isObject() || node.size()!=names.size()) throw invalid();
        var fields=node.fieldNames();while(fields.hasNext()) if(!names.contains(fields.next())) throw invalid();
    }
    private static ModelFailure invalid() {return new ModelFailure("INVALID_PLAN");}
}
