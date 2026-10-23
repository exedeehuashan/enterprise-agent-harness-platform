package com.enterprise.agentharness.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * A commit's QA report in Markdown (方案 4): gate verdict, every suite's latest run at the commit with its scope,
 * the failures with their triage, and, when the commit is a pull request head, how each suite was selected and the
 * change impact. Built from recorded evidence only. Test names, failure messages and pull request titles are
 * untrusted text and are escaped, so the report can be pasted or posted (for example as a pull request comment)
 * without injecting links, mentions or markup.
 */
@Service
public class QaReportService {
    static final int MAX_FAILURES_PER_SUITE = 10;
    private final JdbcTemplate jdbc;
    private final QualityService quality;
    private final QaTriageService triage;
    private final ObjectMapper mapper;
    private final QaComparison comparison;

    public QaReportService(JdbcTemplate jdbc, QualityService quality, QaTriageService triage, ObjectMapper mapper, QaComparison comparison) {
        this.jdbc = jdbc; this.quality = quality; this.triage = triage; this.mapper = mapper; this.comparison = comparison;
    }

    public record Report(String project, String ref, String commit, String verdict, Instant generatedAt, String markdown) {}

    public Report report(String project, String ref, String commit) {
        var gate = quality.gate(project, commit);
        Instant now = Instant.now();
        var md = new StringBuilder();
        md.append("## EAHP QA report: ").append(text(project)).append(" @ `").append(commit, 0, 12).append("`\n\n");
        md.append("**Release gate: ").append(gate.verdict()).append("**");
        if (gate.policy() != null) md.append(" (required: ").append(text(String.join(", ", gate.policy().requiredSuites()))).append(")");
        md.append("\n\n");

        var runs = jdbc.queryForList("""
                SELECT DISTINCT ON (i.suite_key) i.suite_key, i.run_id, i.outcome, i.category, i.test_count, i.failed_count,
                       r.arguments::jsonb->'modules' AS modules, c.lines_covered, c.lines_missed
                FROM qa_run_ingestions i JOIN agent_runs r ON r.id = i.run_id LEFT JOIN qa_coverage c ON c.run_id = i.run_id
                WHERE i.project_key=? AND i.commit_sha=? ORDER BY i.suite_key, r.created_at DESC
                """, project, commit);
        if (runs.isEmpty()) {
            md.append("No suite has a recorded result at this commit yet.\n\n");
        } else {
            md.append("| Suite | Result | Tests | Failed | Line coverage | Scope | Run |\n|---|---|---|---|---|---|---|\n");
            for (var r : runs) {
                List<String> modules = modules(r.get("modules"));
                md.append("| ").append(text((String) r.get("suite_key"))).append(" | ").append(r.get("outcome") == null ? "NO RESULT" : text((String) r.get("outcome")))
                        .append(" | ").append(r.get("test_count")).append(" | ").append(r.get("failed_count")).append(" | ").append(coverage(r))
                        .append(" | ").append(modules.isEmpty() ? "full" : modules.size() + " module" + (modules.size() == 1 ? "" : "s"))
                        .append(" | `").append(r.get("run_id").toString(), 0, 8).append("` |\n");
            }
            md.append('\n');
        }
        if (!gate.reasons().isEmpty() || !gate.notes().isEmpty()) {
            md.append("### Gate details\n\n");
            gate.reasons().forEach(r -> md.append("- ").append(text(r)).append('\n'));
            gate.notes().forEach(n -> md.append("- ").append(text(n)).append('\n'));
            md.append('\n');
        }

        var failures = new ArrayList<String>();
        for (var r : runs) {
            var t = triage.triage((UUID) r.get("run_id"));
            int shown = 0;
            for (var f : t.failures()) {
                if (shown++ == MAX_FAILURES_PER_SUITE) { failures.add("- " + text((String) r.get("suite_key")) + ": " + (t.failures().size() - MAX_FAILURES_PER_SUITE) + " more"); break; }
                String category = f.category() == null ? "" : ", " + label(f.category());
                String history = f.history() == null ? "" : ", " + label(f.history().label());
                failures.add("- " + text((String) r.get("suite_key")) + " `" + code(f.testClass() + (f.testName().isEmpty() ? "" : "#" + f.testName())) + "`"
                        + " (" + f.status().toLowerCase() + category + history + ")" + (f.message() == null ? "" : ": " + text(f.message())));
            }
        }
        if (!failures.isEmpty()) {
            md.append("### Failures\n\n");
            failures.forEach(f -> md.append(f).append('\n'));
            md.append('\n');
        }

        var prRows = jdbc.queryForList("""
                SELECT pr_number, pr_title, suite_key, status, skip_reason, selection, selection_reason, selected_modules::text AS selected, base_sha
                FROM qa_pr_runs WHERE project_key=? AND head_sha=? ORDER BY pr_number, suite_key
                """, project, commit);
        if (!prRows.isEmpty()) {
            var first = prRows.get(0);
            md.append("### Pull request #").append(first.get("pr_number")).append(": ").append(text((String) first.get("pr_title"))).append("\n\n");
            for (var p : prRows) {
                md.append("- ").append(text((String) p.get("suite_key"))).append(": ").append(selection(p)).append('\n');
            }
            impact(project, (String) first.get("base_sha"), commit, md);
            compared(project, (String) first.get("base_sha"), commit, md);
            md.append('\n');
        }
        md.append("_Generated by EAHP from recorded evidence at ").append(now).append("._\n");
        return new Report(project, ref, commit, gate.verdict(), now, md.toString());
    }

    private static String coverage(Map<String, Object> r) {
        if (r.get("lines_covered") == null) return "—";
        long covered = ((Number) r.get("lines_covered")).longValue(), missed = ((Number) r.get("lines_missed")).longValue();
        return covered + missed == 0 ? "—" : Math.round(1000.0 * covered / (covered + missed)) / 10.0 + "%";
    }

    private String selection(Map<String, Object> p) {
        String status = (String) p.get("status"), sel = (String) p.get("selection"), reason = (String) p.get("selection_reason");
        if ("SKIPPED".equals(status)) return "skipped (" + label((String) p.get("skip_reason")) + ")";
        if ("MODULES".equals(sel)) {
            var modules = modules(p.get("selected"));
            return "affected modules only: " + modules.stream().map(m -> "`" + code(m) + "`").reduce((a, b) -> a + ", " + b).orElse("");
        }
        if ("FULL".equals(sel) && reason != null && !reason.equals("SUITE_NOT_SELECTIVE")) return "full suite (" + label(reason) + ")";
        return status.equals("SUPERSEDED") ? "superseded by a newer push" : "full suite";
    }

    private void impact(String project, String base, String head, StringBuilder md) {
        if (base == null) return;
        var rows = jdbc.queryForList("SELECT impact::text AS impact FROM qa_change_impacts WHERE project_key=? AND base_sha=? AND head_sha=? AND impact IS NOT NULL",
                String.class, project, base, head);
        if (rows.isEmpty()) return;
        try {
            JsonNode impact = mapper.readTree(rows.get(0));
            md.append("\nChange impact: ").append(impact.path("changedFileCount").asInt()).append(" changed file(s), ")
                    .append(impact.path("modules").size()).append(" affected module(s), ").append(impact.path("testClasses").size()).append(" test class(es) in scope.\n");
            for (JsonNode note : impact.path("notes")) md.append("- ").append(text(note.asText())).append('\n');
        } catch (java.io.IOException ex) {
            // A malformed cache row only loses the impact section.
        }
    }

    /** How the pull request's results differ from its base commit's, suite by suite. */
    private void compared(String project, String base, String head, StringBuilder md) {
        if (base == null) return;
        var diffs = comparison.compare(project, base, head, null);
        if (diffs.isEmpty()) return;
        md.append("\nCompared with base `").append(base, 0, 12).append("`:\n");
        for (var d : diffs) {
            md.append("- ").append(text(d.suite())).append(": ");
            if (!d.status().equals("COMPARED")) { md.append("no result at the base commit to compare with\n"); continue; }
            md.append(d.newFailureCount()).append(" new failure(s), ").append(d.fixedCount()).append(" fixed, ").append(d.stillFailingCount())
                    .append(" still failing, ").append(d.addedCount()).append(" added");
            md.append(d.sameScope() ? ", " + d.removedCount() + " removed" : " (different scope, removed tests not compared)").append('\n');
            d.newFailures().stream().limit(MAX_FAILURES_PER_SUITE).forEach(t -> md.append("  - new failure: `").append(code(t)).append("`\n"));
        }
    }

    private List<String> modules(Object json) {
        if (json == null) return List.of();
        try { return List.of(mapper.readValue(json.toString(), String[].class)); } catch (Exception ex) { return List.of(); }
    }

    private static String label(String code) { return code == null ? "unknown" : code.replace('_', ' ').toLowerCase(); }

    /**
     * Untrusted text as inert Markdown: one line, bounded, with HTML, table, emphasis, link and reference syntax
     * escaped and {@code @} mentions broken, so nothing renders as markup or notifies anyone.
     */
    static String text(String value) {
        if (value == null) return "";
        String s = value.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        if (s.length() > 300) s = s.substring(0, 299) + "…";
        var out = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '@' -> out.append("@​");
                case '\\', '`', '*', '_', '[', ']', '(', ')', '#', '|', '~', '!' -> out.append('\\').append(c);
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Text inside a code span: one line, bounded, no backticks to break out of the span. */
    static String code(String value) {
        String s = value == null ? "" : value.replaceAll("[\\p{Cntrl}\\s]+", " ").replace('`', '\'').strip();
        return s.length() > 300 ? s.substring(0, 299) + "…" : s;
    }
}
