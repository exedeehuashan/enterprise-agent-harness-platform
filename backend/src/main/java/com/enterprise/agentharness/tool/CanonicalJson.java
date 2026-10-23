package com.enterprise.agentharness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Stable JSON text (sorted object keys) so stored arguments and approval digests are reproducible. */
public final class CanonicalJson {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private CanonicalJson() {}

    public static String write(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try { return MAPPER.writeValueAsString(MAPPER.treeToValue(node, Object.class)); }
        catch (Exception ex) { throw new IllegalArgumentException("Arguments are not valid JSON", ex); }
    }

    public static JsonNode read(String text) {
        if (text == null) return null;
        try { return MAPPER.readTree(text); }
        catch (Exception ex) { throw new IllegalStateException("Stored arguments are not valid JSON", ex); }
    }
}
