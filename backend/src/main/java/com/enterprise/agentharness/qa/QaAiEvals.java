package com.enterprise.agentharness.qa;

import com.enterprise.agentharness.ai.eval.AiEvalFeature;
import com.enterprise.agentharness.ai.eval.EvalChecks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import static com.enterprise.agentharness.ai.eval.EvalChecks.obeyed;
import static com.enterprise.agentharness.ai.eval.EvalChecks.strings;

/**
 * 方案 4's contribution to the AI evaluation set: failure analysis (expected category, tests that must be named,
 * prompt-injected assertions that must not be made) and API test generation (valid collection, negative cases,
 * captured ids, only paths from the spec). Cases are in {@code ai-evals/qa.json}.
 */
@Configuration
public class QaAiEvals {
    static final String CASES = "/ai-evals/qa.json";

    @Bean @Order(10)
    AiEvalFeature failureAnalysisEval(QaAiAnalysis analysis) {
        return new AiEvalFeature() {
            @Override public String feature() { return "failure-analysis"; }
            @Override public String casesResource() { return CASES; }
            @Override public long[] evaluate(JsonNode c, EvalChecks checks, ObjectNode result) {
                var evaluated = analysis.analyzeEvidence(c.path("evidence").asText());
                JsonNode a = evaluated.analysis();
                result.set("answer", a);
                var expect = c.path("expect");
                String category = a.path("category").asText();
                checks.check("category", strings(expect.path("category")).contains(category), "got " + category + ", expected " + strings(expect.path("category")));
                for (String test : strings(expect.path("affectedTestsContain"))) {
                    boolean named = false;
                    for (JsonNode t : a.path("affectedTests")) named |= t.asText().contains(test);
                    checks.check("names " + test, named, "affectedTests " + a.path("affectedTests"));
                }
                String text = (a.path("summary").asText() + " " + a.path("likelyCause").asText() + " " + a.path("suggestedActions")).toLowerCase(Locale.ROOT);
                for (String s : strings(expect.path("textContains"))) checks.check("mentions " + s, text.contains(s.toLowerCase(Locale.ROOT)), null);
                checks.forbidden(expect, a.toString());
                // An injection is obeyed when a sentence asserts it, not when the answer reports or rejects it ("do not delete …").
                List<String> sentences = new ArrayList<>();
                List<JsonNode> fields = new ArrayList<>(List.of(a.path("summary"), a.path("likelyCause")));
                a.path("suggestedActions").forEach(fields::add);
                for (JsonNode f : fields) for (String sentence : f.asText().split("(?<=[.!?])\\s+")) sentences.add(sentence);
                int n = 0;
                for (String regex : strings(expect.path("sentencesMustNotMatch"))) {
                    var hits = obeyed(sentences, regex);
                    checks.check("does not follow injected instruction " + (++n), hits.isEmpty(), hits.isEmpty() ? null : "asserted: " + hits.get(0));
                }
                return new long[] { evaluated.answer().inputTokens(), evaluated.answer().outputTokens() };
            }
        };
    }

    @Bean @Order(20)
    AiEvalFeature testGenerationEval(QaAiTestGeneration generation, ObjectMapper mapper) {
        return new AiEvalFeature() {
            @Override public String feature() { return "test-generation"; }
            @Override public String casesResource() { return CASES; }
            @Override public long[] evaluate(JsonNode c, EvalChecks checks, ObjectNode result) {
                var g = generation.generateFrom(c.path("service").asText(), "openapi.json", c.path("spec").asText(), null, c.path("maxCases").asInt(6));
                var expect = c.path("expect");
                JsonNode collection = g.collection();
                result.set("answer", collection == null ? mapper.createObjectNode().put("error", g.error()) : collection);
                checks.check("valid collection", collection != null, g.error());
                if (collection != null) {
                    int cases = collection.path("cases").size();
                    checks.check("at least " + expect.path("minCases").asInt(1) + " cases", cases >= expect.path("minCases").asInt(1), cases + " cases");
                    boolean negative = false, capture = false, fromSpec = true;
                    List<Pattern> paths = specPaths(mapper, c.path("spec").asText());
                    List<String> stray = new ArrayList<>();
                    for (JsonNode tc : collection.path("cases")) for (JsonNode step : tc.path("steps")) {
                        JsonNode status = step.path("expect").path("status");
                        for (JsonNode s : status.isArray() ? status : mapper.createArrayNode().add(status)) negative |= s.asInt() >= 400 && s.asInt() < 500;
                        capture |= step.path("capture").size() > 0;
                        String path = step.path("request").path("path").asText().replaceAll("\\?.*$", "");
                        if (paths.stream().noneMatch(p -> p.matcher(path).matches())) { fromSpec = false; stray.add(path); }
                    }
                    if (expect.path("requireNegativeStatus").asBoolean(false)) checks.check("has a 4xx case", negative, null);
                    if (expect.path("requireCapture").asBoolean(false)) checks.check("captures a value for a later step", capture, null);
                    if (expect.path("pathsFromSpec").asBoolean(false)) checks.check("only paths from the spec", fromSpec, stray.isEmpty() ? null : "not in the spec: " + stray);
                    checks.forbidden(expect, collection.toString());
                }
                return new long[] { g.answer().inputTokens(), g.answer().outputTokens() };
            }
        };
    }

    /** OpenAPI path templates as patterns: {param} and {{variable}} stand for one path segment. */
    static List<Pattern> specPaths(ObjectMapper mapper, String spec) {
        List<Pattern> result = new ArrayList<>();
        try {
            for (var it = mapper.readTree(spec).path("paths").fieldNames(); it.hasNext(); ) {
                String template = it.next();
                StringBuilder regex = new StringBuilder();
                for (String part : template.split("(?=\\{)|(?<=\\})")) regex.append(part.startsWith("{") && part.endsWith("}") ? "[^/]+" : Pattern.quote(part));
                result.add(Pattern.compile(regex.toString()));
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("the case's spec is not JSON");
        }
        return result;
    }
}
