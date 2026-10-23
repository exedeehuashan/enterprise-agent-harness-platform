package com.enterprise.agentharness.qa.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/** A deliberately small JSON path subset: {@code $}, {@code .name} and {@code [index]}, for example {@code $.items[0].sku}. */
final class JsonPaths {
    private JsonPaths() {}

    /** Path segments: a String is a field name, an Integer an array index. */
    static List<Object> parse(String path) {
        if (path == null || !path.startsWith("$")) throw ApiCollections.invalid("JSON paths start with $ (got '" + path + "')");
        List<Object> segments = new ArrayList<>();
        int i = 1;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '.') {
                int end = i + 1;
                while (end < path.length() && (Character.isLetterOrDigit(path.charAt(end)) || path.charAt(end) == '_' || path.charAt(end) == '-')) end++;
                if (end == i + 1) throw ApiCollections.invalid("bad JSON path '" + path + "'");
                segments.add(path.substring(i + 1, end));
                i = end;
            } else if (c == '[') {
                int end = path.indexOf(']', i);
                if (end < 0) throw ApiCollections.invalid("bad JSON path '" + path + "'");
                try { segments.add(Integer.parseInt(path.substring(i + 1, end))); }
                catch (NumberFormatException ex) { throw ApiCollections.invalid("bad JSON path index in '" + path + "'"); }
                if ((Integer) segments.get(segments.size() - 1) < 0) throw ApiCollections.invalid("bad JSON path index in '" + path + "'");
                i = end + 1;
            } else {
                throw ApiCollections.invalid("bad JSON path '" + path + "'");
            }
        }
        return segments;
    }

    /** The node at {@code path}, or null when any segment is missing. */
    static JsonNode find(JsonNode root, String path) {
        JsonNode node = root;
        for (Object segment : parse(path)) {
            if (node == null) return null;
            node = segment instanceof Integer index ? (node.isArray() ? node.get(index) : null) : (node.isObject() ? node.get((String) segment) : null);
        }
        return node;
    }
}
