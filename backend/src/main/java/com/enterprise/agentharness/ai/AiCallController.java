package com.enterprise.agentharness.ai;

import com.enterprise.agentharness.run.RunException;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/ai/calls")
public class AiCallController {
    private static final Set<String> PARAMS = Set.of("feature", "status", "sourceType", "sourceId", "page", "size");
    private final AiCallLog log;
    public AiCallController(AiCallLog log) { this.log = log; }

    @GetMapping
    public AiCallLog.Page list(@RequestParam MultiValueMap<String, String> params) {
        if (!PARAMS.containsAll(params.keySet())) throw new RunException(HttpStatus.BAD_REQUEST, "Allowed parameters: " + String.join(", ", PARAMS));
        return log.list(new AiCallLog.Query(text(params, "feature"), text(params, "status"), text(params, "sourceType"), text(params, "sourceId"),
                number(params, "page", 0), number(params, "size", 20)));
    }
    @GetMapping("/features") public List<String> features() { return log.features(); }
    @GetMapping("/{id}") public AiCallLog.Detail get(@PathVariable long id) { return log.get(id); }

    private static String text(MultiValueMap<String, String> params, String key) {
        String v = params.getFirst(key);
        if (v == null || v.isBlank()) return null;
        if (v.length() > 120) throw new RunException(HttpStatus.BAD_REQUEST, key + " is too long");
        return v;
    }
    private static int number(MultiValueMap<String, String> params, String key, int fallback) {
        String v = params.getFirst(key);
        if (v == null) return fallback;
        try { return Integer.parseInt(v); } catch (NumberFormatException ex) { throw new RunException(HttpStatus.BAD_REQUEST, key + " must be a number"); }
    }
}
