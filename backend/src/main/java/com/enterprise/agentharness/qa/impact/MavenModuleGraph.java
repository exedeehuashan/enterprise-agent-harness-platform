package com.enterprise.agentharness.qa.impact;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Modules of a Maven multi-module build and their internal dependencies, read from the pom.xml files of one commit.
 * Only dependencies on other modules of the same build matter here; external dependencies are ignored. The POMs are
 * repository content, so they are parsed without DTDs or external entities.
 */
public final class MavenModuleGraph {
    /** {@code dir} is the module directory ("" for the root), {@code coordinates} is groupId:artifactId. */
    public record Module(String dir, String coordinates, Set<String> dependsOn) {}

    private final Map<String, Module> byDir;
    private final Map<String, Module> byCoordinates;

    private MavenModuleGraph(Map<String, Module> byDir) {
        this.byDir = byDir;
        this.byCoordinates = new HashMap<>();
        byDir.values().forEach(m -> byCoordinates.put(m.coordinates(), m));
    }

    /** {@code poms} maps each pom.xml path to its content. Unreadable POMs are skipped. */
    public static MavenModuleGraph parse(Map<String, String> poms) {
        Map<String, Module> modules = new LinkedHashMap<>();
        Map<String, String> parentGroups = new HashMap<>();
        Map<String, Element> roots = new LinkedHashMap<>();
        for (var e : poms.entrySet()) {
            Element root = parseXml(e.getValue());
            if (root != null) roots.put(dirOf(e.getKey()), root);
        }
        for (var e : roots.entrySet()) {
            Element parent = child(e.getValue(), "parent");
            if (parent != null) parentGroups.put(e.getKey(), text(parent, "groupId"));
        }
        Set<String> internal = new HashSet<>();
        Map<String, String> coordinatesByDir = new HashMap<>();
        for (var e : roots.entrySet()) {
            String group = text(e.getValue(), "groupId");
            if (group == null) group = parentGroups.get(e.getKey());
            String artifact = text(e.getValue(), "artifactId");
            if (group == null || artifact == null) continue;
            coordinatesByDir.put(e.getKey(), group + ":" + artifact);
            internal.add(group + ":" + artifact);
        }
        for (var e : roots.entrySet()) {
            String coordinates = coordinatesByDir.get(e.getKey());
            if (coordinates == null) continue;
            Set<String> deps = new HashSet<>();
            Element dependencies = child(e.getValue(), "dependencies");
            if (dependencies != null) {
                for (Node n = dependencies.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (!(n instanceof Element d) || !d.getTagName().equals("dependency")) continue;
                    String group = text(d, "groupId"), artifact = text(d, "artifactId");
                    if (group != null && group.equals("${project.groupId}")) group = coordinates.substring(0, coordinates.indexOf(':'));
                    if (group != null && artifact != null && internal.contains(group + ":" + artifact)) deps.add(group + ":" + artifact);
                }
            }
            modules.put(e.getKey(), new Module(e.getKey(), coordinates, Set.copyOf(deps)));
        }
        return new MavenModuleGraph(modules);
    }

    public List<Module> modules() {
        return byDir.values().stream().sorted(Comparator.comparing(Module::dir)).toList();
    }

    /** The deepest module directory containing {@code path}; the root module ("") when no other module does. */
    public String moduleOf(String path) {
        String best = byDir.containsKey("") ? "" : null;
        for (String dir : byDir.keySet()) {
            if (!dir.isEmpty() && path.startsWith(dir + "/") && (best == null || dir.length() > best.length())) best = dir;
        }
        return best;
    }

    /** Module directories that depend, directly or transitively, on any of {@code dirs} (excluding {@code dirs} themselves). */
    public Set<String> dependentsOf(Set<String> dirs) {
        Map<String, List<String>> reverse = new HashMap<>();
        for (Module m : byDir.values()) {
            for (String dep : m.dependsOn()) reverse.computeIfAbsent(dep, k -> new ArrayList<>()).add(m.dir());
        }
        Set<String> seen = new HashSet<>(dirs);
        var queue = new ArrayDeque<>(dirs);
        Set<String> result = new HashSet<>();
        while (!queue.isEmpty()) {
            Module m = byDir.get(queue.poll());
            if (m == null) continue;
            for (String dependent : reverse.getOrDefault(m.coordinates(), List.of())) {
                if (seen.add(dependent)) { result.add(dependent); queue.add(dependent); }
            }
        }
        return result;
    }

    public boolean isEmpty() { return byDir.isEmpty(); }

    static String dirOf(String pomPath) {
        int slash = pomPath.lastIndexOf('/');
        return slash < 0 ? "" : pomPath.substring(0, slash);
    }

    private static Element parseXml(String xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
        } catch (Exception ex) {
            return null;
        }
    }

    /** Direct child element by name (namespace-agnostic), so nested elements such as plugin artifactIds are not matched. */
    private static Element child(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && (name.equals(e.getLocalName()) || name.equals(e.getTagName()))) return e;
        }
        return null;
    }

    private static String text(Element parent, String name) {
        Element e = child(parent, name);
        if (e == null) return null;
        String value = e.getTextContent().trim();
        return value.isEmpty() ? null : value;
    }
}
