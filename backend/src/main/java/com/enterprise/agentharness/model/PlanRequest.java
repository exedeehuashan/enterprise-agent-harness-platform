package com.enterprise.agentharness.model;

import com.enterprise.agentharness.run.RunException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import org.springframework.http.HttpStatus;

public record PlanRequest(int maxOutputTokens,int timeoutMillis) {
    public static PlanRequest parse(JsonNode body) {
        if(body==null || !body.isObject()) throw invalid();
        var fields=body.fieldNames();
        while(fields.hasNext()) if(!Set.of("maxOutputTokens","timeoutMillis").contains(fields.next())) throw invalid();
        int tokens=integer(body,"maxOutputTokens",512),timeout=integer(body,"timeoutMillis",2000);
        if(tokens<64 || tokens>2048 || timeout<100 || timeout>5000) throw invalid();
        return new PlanRequest(tokens,timeout);
    }
    private static int integer(JsonNode body,String key,int fallback) {
        if(!body.has(key)) return fallback;
        var value=body.get(key);
        if(!value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
        return value.intValue();
    }
    private static RunException invalid() {return new RunException(HttpStatus.BAD_REQUEST,"Use maxOutputTokens 64..2048 and timeoutMillis 100..5000 only");}
}
