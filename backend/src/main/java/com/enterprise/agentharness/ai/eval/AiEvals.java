package com.enterprise.agentharness.ai.eval;

import com.enterprise.agentharness.ai.LlmProvider;
import com.enterprise.agentharness.governance.AuditService;
import com.enterprise.agentharness.run.RunException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * AI evaluation set (P44): fixed, synthetic inputs for every AI feature, run through the same code paths as
 * production and graded by deterministic checks. The features and their cases come from the modules
 * ({@link AiEvalFeature} beans: planning from the platform, failure analysis and test generation from 方案 4), so
 * this runner depends on no module. A run records each check, the pass rate and the tokens spent, so prompt changes
 * and model changes can be compared on the same footing. It spends real tokens on a real provider, so only
 * approvers start it.
 */
@Service
public class AiEvals {
    private static final Logger log = LoggerFactory.getLogger(AiEvals.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final LlmProvider llm;
    private final Map<String, AiEvalFeature> features = new LinkedHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "eahp-ai-eval"); t.setDaemon(true); return t; });

    public AiEvals(JdbcTemplate jdbc, ObjectMapper mapper, LlmProvider llm, List<AiEvalFeature> features) {
        this.jdbc = jdbc; this.mapper = mapper; this.llm = llm;
        for (AiEvalFeature f : features) {
            if (this.features.putIfAbsent(f.feature(), f) != null) throw new IllegalStateException("Two modules grade AI feature " + f.feature());
        }
    }

    public record EvalRun(UUID id, String status, String provider, String model, int setVersion, int total, int passed, long inputTokens, long outputTokens,
            JsonNode results, String error, String actor, Instant startedAt, Instant finishedAt) {}

    /**
     * The evaluation set as shipped: every feature's cases, in feature order. Its version is the highest version of
     * the case files, so a module bumps its file's version when it changes its cases.
     */
    public JsonNode cases() {
        var set = mapper.createObjectNode();
        var cases = mapper.createArrayNode();
        int version = 0;
        for (String resource : new LinkedHashSet<>(features.values().stream().map(AiEvalFeature::casesResource).toList())) {
            JsonNode file;
            try (InputStream in = getClass().getResourceAsStream(resource)) {
                if (in == null) throw new IllegalStateException("AI evaluation cases not found: " + resource);
                file = mapper.readTree(in);
            } catch (IOException ex) {
                throw new IllegalStateException("AI evaluation cases cannot be read: " + resource, ex);
            }
            version = Math.max(version, file.path("version").asInt());
            for (JsonNode c : file.path("cases")) if (features.containsKey(c.path("feature").asText())) cases.add(c);
        }
        set.put("version", version).put("description", "EAHP AI evaluation set (P44): fixed inputs for "
                + String.join(", ", features.keySet()) + ", graded automatically. Synthetic data only.");
        set.set("cases", cases);
        return set;
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
                    var checks = new EvalChecks(result.putArray("checks"));
                    var feature = features.get(c.path("feature").asText());
                    if (feature == null) throw new IllegalArgumentException("unknown feature " + c.path("feature").asText());
                    long[] tokens = feature.evaluate(c, checks, result);
                    in += tokens[0]; out += tokens[1];
                    boolean ok = checks.allPassed();
                    result.put("passed", ok);
                    if (ok) passed++;
                } catch (RuntimeException ex) {
                    result.put("passed", false).put("error", EvalChecks.clip(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(), 300));
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

    private EvalRun map(ResultSet r, int i) throws SQLException {
        try {
            return new EvalRun(r.getObject("id", UUID.class), r.getString("status"), r.getString("provider"), r.getString("model"), r.getInt("set_version"),
                    r.getInt("total"), r.getInt("passed"), r.getLong("input_tokens"), r.getLong("output_tokens"),
                    r.getString("results") == null ? null : mapper.readTree(r.getString("results")), r.getString("error"), r.getString("actor"),
                    r.getTimestamp("started_at").toInstant(), r.getTimestamp("finished_at") == null ? null : r.getTimestamp("finished_at").toInstant());
        } catch (IOException ex) { throw new SQLException(ex); }
    }
}
