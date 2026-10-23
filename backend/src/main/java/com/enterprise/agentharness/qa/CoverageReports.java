package com.enterprise.agentharness.qa;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

/**
 * Reads line and branch coverage totals from JaCoCo ({@code <report>}) and Cobertura ({@code <coverage>}) XML reports.
 * Reports are untrusted build output: parsing is streaming (reports can be large), DTDs and external entities are
 * never loaded, and only the report-level totals are read.
 */
public final class CoverageReports {
    static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    static final int MAX_FILES = 200;

    private CoverageReports() {}

    /** One report: {@code name} is the module (JaCoCo report name) or the file's directory. */
    public record Module(String name, String format, long linesCovered, long linesMissed, long branchesCovered, long branchesMissed) {}
    public record Summary(List<Module> modules, long linesCovered, long linesMissed, long branchesCovered, long branchesMissed, int unreadable) {
        public Double linePercent() { return percent(linesCovered, linesMissed); }
        public Double branchPercent() { return percent(branchesCovered, branchesMissed); }
        static Double percent(long covered, long missed) { return covered + missed == 0 ? null : Math.round(1000.0 * covered / (covered + missed)) / 10.0; }
    }

    /** Files under {@code root} whose relative path ends with one of {@code patterns} (for example {@code target/site/jacoco/jacoco.xml}). */
    public static List<Path> find(Path root, List<String> patterns) throws IOException {
        if (patterns.isEmpty() || !Files.isDirectory(root)) return List.of();
        try (Stream<Path> files = Files.walk(root, 30)) {
            return files.filter(Files::isRegularFile)
                    .filter(f -> { String rel = root.relativize(f).toString().replace('\\', '/'); return patterns.stream().anyMatch(p -> rel.equals(p) || rel.endsWith("/" + p)); })
                    .filter(f -> !root.relativize(f).toString().contains("node_modules"))
                    .sorted(Comparator.naturalOrder()).limit(MAX_FILES).toList();
        }
    }

    public static Summary summarize(Path root, List<Path> files) {
        List<Module> modules = new ArrayList<>();
        int unreadable = 0;
        for (Path f : files) {
            try {
                if (Files.size(f) > MAX_FILE_BYTES) { unreadable++; continue; }
                String dir = root.relativize(f).getParent() == null ? "" : root.relativize(f).getParent().toString().replace('\\', '/');
                try (InputStream in = Files.newInputStream(f)) {
                    var m = parse(in, dir);
                    if (m == null) unreadable++; else modules.add(m);
                }
            } catch (IOException | XMLStreamException | RuntimeException ex) {
                unreadable++;
            }
        }
        long lc = 0, lm = 0, bc = 0, bm = 0;
        for (var m : modules) { lc += m.linesCovered(); lm += m.linesMissed(); bc += m.branchesCovered(); bm += m.branchesMissed(); }
        return new Summary(List.copyOf(modules), lc, lm, bc, bm, unreadable);
    }

    /** Report-level totals, or null when the document is neither JaCoCo nor Cobertura. */
    static Module parse(InputStream in, String fallbackName) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        var reader = factory.createXMLStreamReader(in);
        try {
            int depth = 0;
            String root = null, name = fallbackName;
            long lc = 0, lm = 0, bc = 0, bm = 0;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    String element = reader.getLocalName();
                    if (depth == 1) {
                        root = element;
                        if (root.equals("coverage")) {
                            // Cobertura keeps its totals as attributes on the root element.
                            long linesValid = number(reader.getAttributeValue(null, "lines-valid")), linesCovered = number(reader.getAttributeValue(null, "lines-covered"));
                            long branchesValid = number(reader.getAttributeValue(null, "branches-valid")), branchesCovered = number(reader.getAttributeValue(null, "branches-covered"));
                            return new Module(fallbackName, "cobertura", linesCovered, Math.max(0, linesValid - linesCovered), branchesCovered, Math.max(0, branchesValid - branchesCovered));
                        }
                        if (!root.equals("report")) return null;
                        String reportName = reader.getAttributeValue(null, "name");
                        if (reportName != null && !reportName.isBlank()) name = reportName.length() > 120 ? reportName.substring(0, 120) : reportName;
                    } else if (depth == 2 && element.equals("counter")) {
                        // JaCoCo: the report's own counters are its direct children (deeper counters are per package/class).
                        String type = reader.getAttributeValue(null, "type");
                        long missed = number(reader.getAttributeValue(null, "missed")), covered = number(reader.getAttributeValue(null, "covered"));
                        if ("LINE".equals(type)) { lc = covered; lm = missed; }
                        else if ("BRANCH".equals(type)) { bc = covered; bm = missed; }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    depth--;
                }
            }
            return "report".equals(root) ? new Module(name, "jacoco", lc, lm, bc, bm) : null;
        } finally {
            reader.close();
        }
    }

    private static long number(String value) {
        if (value == null) return 0;
        try { return Math.max(0, Long.parseLong(value.trim())); } catch (NumberFormatException ex) { return 0; }
    }
}
