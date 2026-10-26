package com.enterprise.agentharness.ai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** The checks of one evaluated case, and the grading helpers features share. */
public final class EvalChecks {
    private final ArrayNode checks;

    public EvalChecks(ArrayNode checks) { this.checks = checks; }

    public void check(String name, boolean passed, String detail) {
        var c = checks.addObject().put("check", name).put("passed", passed);
        if (!passed && detail != null) c.put("detail", clip(detail, 300));
    }

    /** One check per phrase in {@code expect.mustNotContain}, case-insensitive. */
    public void forbidden(JsonNode expect, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String s : strings(expect.path("mustNotContain"))) check("does not contain \"" + s + "\"", !lower.contains(s.toLowerCase(Locale.ROOT)), null);
    }

    public boolean allPassed() {
        boolean ok = true;
        for (JsonNode check : checks) ok &= check.path("passed").asBoolean();
        return ok;
    }

    /** Sentences that match a case's forbidden-assertion pattern. */
    public static List<String> obeyed(List<String> sentences, String regex) {
        Pattern pattern = Pattern.compile(regex);
        return sentences.stream().filter(x -> pattern.matcher(x).find()).toList();
    }

    public static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode v : array) out.add(v.asText());
        return out;
    }

    public static String clip(String s, int max) { return s == null ? null : s.length() <= max ? s : s.substring(0, max) + "…"; }
}
