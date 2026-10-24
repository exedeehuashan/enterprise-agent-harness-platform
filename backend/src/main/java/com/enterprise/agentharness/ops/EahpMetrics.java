package com.enterprise.agentharness.ops;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * EAHP's own gauges for Prometheus ({@code /actuator/prometheus}, VIEWER): the run queue, approvals waiting for a
 * person, webhook health and recent QA outcomes. Each is a small count query evaluated at scrape time, so the values
 * are always current and nothing has to be updated at the places where events happen.
 */
@Component
public class EahpMetrics implements MeterBinder {
    static final List<String> RUN_STATUSES = List.of("QUEUED", "RUNNING", "WAITING_APPROVAL", "NEEDS_REVIEW", "COMPLETED", "FAILED", "CANCELLED",
            "TIMED_OUT", "REJECTED", "EXPIRED");
    static final List<String> QA_OUTCOMES = List.of("PASSED", "TESTS_FAILED", "BUILD_FAILED", "ENVIRONMENT_UNAVAILABLE", "SUITE_TIMEOUT", "RUNNER_ERROR");
    private final JdbcTemplate jdbc;
    public EahpMetrics(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String status : RUN_STATUSES) {
            Gauge.builder("eahp.runs", () -> count("SELECT count(*) FROM agent_runs WHERE status=?", status))
                    .description("Agent runs by status").tag("status", status).register(registry);
        }
        Gauge.builder("eahp.runs.oldest.queued.seconds", () -> count("SELECT COALESCE(EXTRACT(EPOCH FROM clock_timestamp()-min(created_at)),0) FROM agent_runs WHERE status='QUEUED'"))
                .description("Age of the oldest queued run").baseUnit("seconds").register(registry);
        Gauge.builder("eahp.approvals.pending", () -> count("SELECT count(*) FROM agent_runs WHERE status='WAITING_APPROVAL'"))
                .description("Runs waiting for an approver").register(registry);
        Gauge.builder("eahp.webhooks.failing", () -> count("SELECT count(*) FROM webhook_subscriptions WHERE enabled AND consecutive_failures > 0"))
                .description("Enabled webhooks whose last delivery failed").register(registry);
        Gauge.builder("eahp.webhooks.paused", () -> count("SELECT count(*) FROM webhook_subscriptions WHERE NOT enabled"))
                .description("Paused webhooks").register(registry);
        Gauge.builder("eahp.backup.drill.last.success", () -> count("SELECT COALESCE(EXTRACT(EPOCH FROM max(finished_at)),0) FROM backup_drills WHERE status='PASSED'"))
                .description("When the last backup-restore drill passed (Unix time; 0 = never)").baseUnit("seconds").register(registry);
        Gauge.builder("eahp.backup.drill.last.failed", () -> count("SELECT count(*) FROM (SELECT status FROM backup_drills WHERE status IN ('PASSED','FAILED') ORDER BY started_at DESC LIMIT 1) d WHERE status='FAILED'"))
                .description("1 when the most recent backup-restore drill failed").register(registry);
        for (String outcome : QA_OUTCOMES) {
            Gauge.builder("eahp.qa.results.24h", () -> count("SELECT count(*) FROM qa_run_ingestions WHERE outcome=? AND ingested_at > clock_timestamp()-interval '1 day'", outcome))
                    .description("QA results ingested in the last 24 hours by outcome").tag("outcome", outcome).register(registry);
        }
    }

    private double count(String sql, Object... args) {
        try {
            Number n = jdbc.queryForObject(sql, Number.class, args);
            return n == null ? 0 : n.doubleValue();
        } catch (RuntimeException ex) {
            return Double.NaN; // the database is unavailable; Prometheus records a gap instead of a misleading zero
        }
    }
}
