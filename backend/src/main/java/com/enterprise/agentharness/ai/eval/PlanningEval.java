package com.enterprise.agentharness.ai.eval;

import com.enterprise.agentharness.model.ModelProvider;
import com.enterprise.agentharness.model.PlanValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import static com.enterprise.agentharness.ai.eval.EvalChecks.clip;
import static com.enterprise.agentharness.ai.eval.EvalChecks.strings;

/** The platform's own AI feature: task planning, graded on a valid plan with the expected tools and limits. */
@Component
@Order(100)
public class PlanningEval implements AiEvalFeature {
    private final ModelProvider planner;
    private final PlanValidator plans;
    private final ObjectMapper mapper;

    public PlanningEval(ModelProvider planner, PlanValidator plans, ObjectMapper mapper) { this.planner = planner; this.plans = plans; this.mapper = mapper; }

    @Override public String feature() { return "planning"; }
    @Override public String casesResource() { return "/ai-evals/planning.json"; }

    @Override
    public long[] evaluate(JsonNode c, EvalChecks checks, ObjectNode result) throws Exception {
        var generated = planner.generate(new ModelProvider.Input(c.path("title").asText(), c.path("description").asText()), 2000);
        var expect = c.path("expect");
        JsonNode plan = null;
        String reason = null;
        try { plan = plans.validate(generated.json()); } catch (RuntimeException ex) { reason = ex.getMessage(); }
        result.set("answer", plan == null ? mapper.createObjectNode().put("error", reason).put("raw", clip(generated.json(), 2000)) : plan);
        checks.check("valid plan", plan != null, reason);
        if (plan != null) {
            List<String> tools = new ArrayList<>();
            for (JsonNode s : plan.path("steps")) tools.add(s.path("tool").asText());
            if (expect.has("maxSteps")) checks.check("at most " + expect.get("maxSteps").asInt() + " steps", tools.size() <= expect.get("maxSteps").asInt(), tools.size() + " steps");
            if (expect.has("toolsAnyOf")) checks.check("uses " + strings(expect.get("toolsAnyOf")), tools.stream().anyMatch(strings(expect.get("toolsAnyOf"))::contains), "tools " + tools);
            for (String banned : strings(expect.path("stepsMustNotUse"))) checks.check("no " + banned + " step", !tools.contains(banned), "tools " + tools);
            if (expect.has("maxStepTimeoutSeconds")) {
                int max = 0;
                for (JsonNode s : plan.path("steps")) max = Math.max(max, s.path("timeoutSeconds").asInt());
                checks.check("step timeouts at most " + expect.get("maxStepTimeoutSeconds").asInt() + " s", max <= expect.get("maxStepTimeoutSeconds").asInt(), "longest " + max + " s");
            }
            if (expect.has("toolsInOrder")) checks.check("tools in order " + strings(expect.get("toolsInOrder")), inOrder(tools, strings(expect.get("toolsInOrder"))), "tools " + tools);
        }
        checks.forbidden(expect, generated.json());
        return new long[] { generated.inputTokens(), generated.outputTokens() };
    }

    static boolean inOrder(List<String> tools, List<String> expected) {
        int i = 0;
        for (String t : tools) if (i < expected.size() && t.equals(expected.get(i))) i++;
        return i == expected.size();
    }
}
