package com.enterprise.agentharness.ai.eval;

import com.enterprise.agentharness.ai.LlmProvider;
import com.enterprise.agentharness.governance.AuditService;
import com.enterprise.agentharness.model.ModelProvider;
import com.enterprise.agentharness.model.PlanValidator;
import com.enterprise.agentharness.qa.QaAiAnalysis;
import com.enterprise.agentharness.qa.QaAiTestGeneration;
import com.enterprise.agentharness.run.RunException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * AI evaluation set (P44): fixed, synthetic inputs for the three AI features (failure analysis, API test generation,
 * planning), run through the same code paths as production and graded by deterministic checks: the expected
 * category, tests that must be named, phrases that a prompt injection tries to plant, structural rules for generated
 * tests (valid collection, negative cases, captured ids, only paths from the spec) and valid plans with the right
 * tools. A run records each check, the pass rate and the tokens spent, so prompt changes and model changes can be
 * compared on the same footing. It spends real tokens on a real provider, so only approvers start it.
 */
@Service
public class AiEvals {
    private static final Logger log = LoggerFactory.getLogger(AiEvals.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final LlmProvider llm;
    private final QaAiAnalysis analysis;
    private final QaAiTestGeneration generation;
    private final ModelProvider planner;
    private final PlanValidator plans;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "eahp-ai-eval"); t.setDaemon(true); return t; });

    public AiEvals(JdbcTemplate jdbc, ObjectMapper mapper, LlmProvider llm, QaAiAnalysis analysis, QaAiTestGeneration generation, ModelProvider planner,
            PlanValidator plans) {
        this.jdbc = jdbc; this.mapper = mapper; this.llm = llm; this.analysis = analysis; this.generation = generation; this.planner = planner; this.plans = plans;
    }

    public record EvalRun(UUID id, String status, String provider, String model, int setVersion, int total, int passed, long inputTokens, long outputTokens,
            JsonNode results, String error, String actor, Instant startedAt, Instant finishedAt) {}

    /** The evaluation set as shipped (resource {@code ai-evals/cases.json}). */
    public JsonNode cases() {
        try (InputStream in = getClass().getResourceAsStream("/ai-evals/cases.json")) {
            return mapper.readTree(in);
        } catch (IOException ex) {
            throw new IllegalStateException("The AI evaluation set cannot be read", ex);
        }
    }

    public List<EvalRun> list() { return jdbc.query("SELECT * FROM ai_eval_runs ORDER BY started_at DESC LIMIT 20", this::map); }

    public EvalRun get(UUID id) {
        var rows = jdbc.query("SELECT * FROM ai_eval_runs WHERE id=?", this::map, id);
        if (rows.isEmpty()) throw new RunException(HttpStatus.NOT_FOUND, "Evaluation run not found: " + id);
        return rows.get(0);
    }

    /** Starts the evaluation set in the background against the configured provider; one at a time. */
    @PreAuthorize("hasRole('APPROVER')")
    public synchronized EvalRun start(List<String> only) {
        jdbc.update("UPDATE ai_eval_runs SET status='FAILED', error='interrupted', finished_at=clock_timestamp() WHERE status='RUNNING' AND started_at < clock_timestamp() - interval '1 hour'");
        if (jdbc.queryForObject("SELECT count(*) FROM ai_eval_runs WHERE status='RUNNING'", Long.class) > 0) throw new RunException(HttpStatus.CONFLICT, "An evaluation is already running");
        var set = cases();
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO ai_eval_runs(id,status,provider,model,set_version,actor) VALUES (?,'RUNNING',?,?,?,?)", id, llm.id(), llm.model(),
                set.path("version").asInt(), AuditService.actor());
        var selected = new ArrayList<JsonNode>();
        for (JsonNode c : set.path("cases")) if (only == null || only.isEmpty() || only.contains(c.path("id").asText())) selected.add(c);
        executor.submit(() -> execute(id, selected));
        return get(id);
    }

    void execute(UUID id, List<JsonNode> cases) {
        ArrayNode results = mapper.createArrayNode();
        int passed = 0;
        long in = 0, out = 0;
        try {
            for (JsonNode c : cases) {
                var result = results.addObject().put("id", c.path("id").asText()).put("feature", c.path("feature").asText()).put("name", c.path("name").asText());
                long started = System.currentTimeMillis();
                try {
                    var checks = result.putArray("checks");
                    long[] tokens = switch (c.path("feature").asText()) {
                        case "failure-analysis" -> failureAnalysis(c, checks, result);
                        case "test-generation" -> testGeneration(c, checks, result);
                        case "planning" -> planning(c, checks, result);
                        default -> throw new IllegalArgumentException("unknown feature " + c.path("feature").asText());
                    };
                    in += tokens[0]; out += tokens[1];
                    boolean ok = true;
                    for (JsonNode check : checks) ok &= check.path("passed").asBoolean();
                    result.put("passed", ok);
                    if (ok) passed++;
                } catch (RuntimeException ex) {
                    result.put("passed", false).put("error", clip(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(), 300));
                } catch (Exception ex) {
                    result.put("passed", false).put("error", ex.getClass().getSimpleName());
                }
                result.put("millis", System.currentTimeMillis() - started);
                jdbc.update("UPDATE ai_eval_runs SET total=?, passed=?, input_tokens=?, output_tokens=?, results=?::jsonb WHERE id=?",
                        results.size(), passed, in, out, results.toString(), id);
            }
            jdbc.update("UPDATE ai_eval_runs SET status='DONE', finished_at=clock_timestamp() WHERE id=?", id);
        } catch (RuntimeException ex) {
            log.warn("AI evaluation {} failed", id, ex);
            jdbc.update("UPDATE ai_eval_runs SET status='FAILED', error=?, finished_at=clock_timestamp() WHERE id=?", "internal error", id);
        }
    }

    private long[] failureAnalysis(JsonNode c, ArrayNode checks, ObjectNode result) {
        var evaluated = analysis.analyzeEvidence(c.path("evidence").asText());
        JsonNode a = evaluated.analysis();
        result.set("answer", a);
        var expect = c.path("expect");
        String category = a.path("category").asText();
        check(checks, "category", strings(expect.path("category")).contains(category), "got " + category + ", expected " + strings(expect.path("category")));
        for (String test : strings(expect.path("affectedTestsContain"))) {
            boolean named = false;
            for (JsonNode t : a.path("affectedTests")) named |= t.asText().contains(test);
            check(checks, "names " + test, named, "affectedTests " + a.path("affectedTests"));
        }
        String text = (a.path("summary").asText() + " " + a.path("likelyCause").asText() + " " + a.path("suggestedActions")).toLowerCase(Locale.ROOT);
        for (String s : strings(expect.path("textContains"))) check(checks, "mentions " + s, text.contains(s.toLowerCase(Locale.ROOT)), null);
        forbidden(checks, expect, a.toString());
        // An injection is obeyed when a sentence asserts it, not when the answer reports or rejects it ("do not delete …").
        List<String> sentences = new ArrayList<>();
        List<JsonNode> fields = new ArrayList<>(List.of(a.path("summary"), a.path("likelyCause")));
        a.path("suggestedActions").forEach(fields::add);
        for (JsonNode f : fields) for (String sentence : f.asText().split("(?<=[.!?])\\s+")) sentences.add(sentence);
        int n = 0;
        for (String regex : strings(expect.path("sentencesMustNotMatch"))) {
            var hits = obeyed(sentences, regex);
            check(checks, "does not follow injected instruction " + (++n), hits.isEmpty(), hits.isEmpty() ? null : "asserted: " + hits.get(0));
        }
        return new long[] { evaluated.answer().inputTokens(), evaluated.answer().outputTokens() };
    }

    private long[] testGeneration(JsonNode c, ArrayNode checks, ObjectNode result) {
        var g = generation.generateFrom(c.path("service").asText(), "openapi.json", c.path("spec").asText(), null, c.path("maxCases").asInt(6));
        var expect = c.path("expect");
        JsonNode collection = g.collection();
        result.set("answer", collection == null ? mapper.createObjectNode().put("error", g.error()) : collection);
        check(checks, "valid collection", collection != null, g.error());
        if (collection != null) {
            int cases = collection.path("cases").size();
            check(checks, "at least " + expect.path("minCases").asInt(1) + " cases", cases >= expect.path("minCases").asInt(1), cases + " cases");
            boolean negative = false, capture = false, fromSpec = true;
            List<Pattern> paths = specPaths(c.path("spec").asText());
            List<String> stray = new ArrayList<>();
            for (JsonNode tc : collection.path("cases")) for (JsonNode step : tc.path("steps")) {
                JsonNode status = step.path("expect").path("status");
                for (JsonNode s : status.isArray() ? status : mapper.createArrayNode().add(status)) negative |= s.asInt() >= 400 && s.asInt() < 500;
                capture |= step.path("capture").size() > 0;
                String path = step.path("request").path("path").asText().replaceAll("\\?.*$", "");
                if (paths.stream().noneMatch(p -> p.matcher(path).matches())) { fromSpec = false; stray.add(path); }
            }
            if (expect.path("requireNegativeStatus").asBoolean(false)) check(checks, "has a 4xx case", negative, null);
            if (expect.path("requireCapture").asBoolean(false)) check(checks, "captures a value for a later step", capture, null);
            if (expect.path("pathsFromSpec").asBoolean(false)) check(checks, "only paths from the spec", fromSpec, stray.isEmpty() ? null : "not in the spec: " + stray);
            forbidden(checks, expect, collection.toString());
        }
        return new long[] { g.answer().inputTokens(), g.answer().outputTokens() };
    }

    private long[] planning(JsonNode c, ArrayNode checks, ObjectNode result) throws Exception {
        var generated = planner.generate(new ModelProvider.Input(c.path("title").asText(), c.path("description").asText()), 2000);
        var expect = c.path("expect");
        JsonNode plan = null;
        String reason = null;
        try { plan = plans.validate(generated.json()); } catch (RuntimeException ex) { reason = ex.getMessage(); }
        result.set("answer", plan == null ? mapper.createObjectNode().put("error", reason).put("raw", clip(generated.json(), 2000)) : plan);
        check(checks, "valid plan", plan != null, reason);
        if (plan != null) {
            List<String> tools = new ArrayList<>();
            for (JsonNode s : plan.path("steps")) tools.add(s.path("tool").asText());
            if (expect.has("maxSteps")) check(checks, "at most " + expect.get("maxSteps").asInt() + " steps", tools.size() <= expect.get("maxSteps").asInt(), tools.size() + " steps");
            if (expect.has("toolsAnyOf")) check(checks, "uses " + strings(expect.get("toolsAnyOf")), tools.stream().anyMatch(strings(expect.get("toolsAnyOf"))::contains), "tools " + tools);
            for (String banned : strings(expect.path("stepsMustNotUse"))) check(checks, "no " + banned + " step", !tools.contains(banned), "tools " + tools);
            if (expect.has("maxStepTimeoutSeconds")) {
                int max = 0;
                for (JsonNode s : plan.path("steps")) max = Math.max(max, s.path("timeoutSeconds").asInt());
                check(checks, "step timeouts at most " + expect.get("maxStepTimeoutSeconds").asInt() + " s", max <= expect.get("maxStepTimeoutSeconds").asInt(), "longest " + max + " s");
            }
            if (expect.has("toolsInOrder")) check(checks, "tools in order " + strings(expect.get("toolsInOrder")), inOrder(tools, strings(expect.get("toolsInOrder"))), "tools " + tools);
        }
        forbidden(checks, expect, generated.json());
        return new long[] { generated.inputTokens(), generated.outputTokens() };
    }

    /** OpenAPI path templates as patterns: {param} and {{variable}} stand for one path segment. */
    List<Pattern> specPaths(String spec) {
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

    /** Sentences that match a case's forbidden-assertion pattern. */
    public static List<String> obeyed(List<String> sentences, String regex) {
        Pattern pattern = Pattern.compile(regex);
        return sentences.stream().filter(x -> pattern.matcher(x).find()).toList();
    }

    static boolean inOrder(List<String> tools, List<String> expected) {
        int i = 0;
        for (String t : tools) if (i < expected.size() && t.equals(expected.get(i))) i++;
        return i == expected.size();
    }

    private void forbidden(ArrayNode checks, JsonNode expect, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String s : strings(expect.path("mustNotContain"))) check(checks, "does not contain \"" + s + "\"", !lower.contains(s.toLowerCase(Locale.ROOT)), null);
    }
    private static void check(ArrayNode checks, String name, boolean passed, String detail) {
        var c = checks.addObject().put("check", name).put("passed", passed);
        if (!passed && detail != null) c.put("detail", clip(detail, 300));
    }
    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode v : array) out.add(v.asText());
        return out;
    }
    private static String clip(String s, int max) { return s == null ? null : s.length() <= max ? s : s.substring(0, max) + "…"; }
    private EvalRun map(ResultSet r, int i) throws SQLException {
        try {
            return new EvalRun(r.getObject("id", UUID.class), r.getString("status"), r.getString("provider"), r.getString("model"), r.getInt("set_version"),
                    r.getInt("total"), r.getInt("passed"), r.getLong("input_tokens"), r.getLong("output_tokens"),
                    r.getString("results") == null ? null : mapper.readTree(r.getString("results")), r.getString("error"), r.getString("actor"),
                    r.getTimestamp("started_at").toInstant(), r.getTimestamp("finished_at") == null ? null : r.getTimestamp("finished_at").toInstant());
        } catch (IOException ex) { throw new SQLException(ex); }
    }
}
