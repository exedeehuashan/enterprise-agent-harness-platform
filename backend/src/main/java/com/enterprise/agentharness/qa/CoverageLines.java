package com.enterprise.agentharness.qa;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

/**
 * Line-level coverage from JaCoCo and Cobertura XML reports (P39), for new-code coverage: which lines of which source
 * file were covered or missed. JaCoCo names files by package path ({@code com/example/Order.java}) and Cobertura by
 * its source-root-relative {@code filename}; both are matched against repository paths as suffixes, preferring files
 * under the module directory that contains the report. Parsing has the same safeguards as {@link CoverageReports}.
 */
public final class CoverageLines {
    /** Upper bound on files kept per run, so a huge report cannot exhaust memory or storage. */
    static final int MAX_FILES = 20_000;

    private CoverageLines() {}

    /**
     * A source file's executable lines: {@code module} is the report's module directory relative to the checkout
     * (for example {@code backend/order-api/}, empty at the root) and {@code path} the report's own file name.
     */
    public record FileLines(String module, String path, TreeSet<Integer> covered, TreeSet<Integer> missed) {}

    public static List<FileLines> read(Path root, List<Path> reports) {
        List<FileLines> result = new ArrayList<>();
        for (Path report : reports) {
            try {
                if (Files.size(report) > CoverageReports.MAX_FILE_BYTES) continue;
                try (InputStream in = Files.newInputStream(report)) { result.addAll(parse(in, module(root, report))); }
            } catch (IOException | XMLStreamException | RuntimeException ex) {
                // An unreadable report only loses its line data; totals are reported separately.
            }
            if (result.size() >= MAX_FILES) return List.copyOf(result.subList(0, MAX_FILES));
        }
        return List.copyOf(result);
    }

    /** The directory above {@code target/}, {@code build/} or {@code coverage/} that holds the report, with a trailing slash. */
    static String module(Path root, Path report) {
        String rel = root.relativize(report).toString().replace('\\', '/');
        for (String marker : List.of("target/", "build/", "coverage/")) {
            int i = rel.startsWith(marker) ? 0 : rel.indexOf("/" + marker);
            if (i == 0) return "";
            if (i > 0) return rel.substring(0, i + 1);
        }
        int slash = rel.lastIndexOf('/');
        return slash < 0 ? "" : rel.substring(0, slash + 1);
    }

    static List<FileLines> parse(InputStream in, String module) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        var reader = factory.createXMLStreamReader(in);
        Map<String, FileLines> files = new LinkedHashMap<>();
        try {
            String root = null, pkg = "", current = null;
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT) continue;
                String element = reader.getLocalName();
                if (root == null) {
                    root = element;
                    if (!root.equals("report") && !root.equals("coverage")) return List.of();
                    continue;
                }
                if (root.equals("report")) {
                    // JaCoCo: <package name="com/x"><sourcefile name="A.java"><line nr= mi= ci=/>
                    if (element.equals("package")) pkg = attr(reader.getAttributeValue(null, "name"));
                    else if (element.equals("sourcefile")) current = (pkg.isEmpty() ? "" : pkg + "/") + attr(reader.getAttributeValue(null, "name"));
                    else if (element.equals("line") && current != null) {
                        int nr = number(reader.getAttributeValue(null, "nr"));
                        long ci = number(reader.getAttributeValue(null, "ci")), mi = number(reader.getAttributeValue(null, "mi"));
                        if (nr > 0 && ci + mi > 0) add(files, module, current, nr, ci > 0);
                    }
                } else {
                    // Cobertura: <class filename="src/a.py"><lines><line number= hits=/>
                    if (element.equals("class")) current = attr(reader.getAttributeValue(null, "filename"));
                    else if (element.equals("line") && current != null && !current.isEmpty()) {
                        int nr = number(reader.getAttributeValue(null, "number"));
                        if (nr > 0) add(files, module, current, nr, number(reader.getAttributeValue(null, "hits")) > 0);
                    }
                }
                if (files.size() >= MAX_FILES) break;
            }
        } finally {
            reader.close();
        }
        return new ArrayList<>(files.values());
    }

    private static void add(Map<String, FileLines> files, String module, String path, int line, boolean covered) {
        var f = files.computeIfAbsent(path, p -> new FileLines(module, p, new TreeSet<>(), new TreeSet<>()));
        // A line reported twice (Cobertura repeats lines per method) is covered if any report covered it.
        if (covered) { f.covered().add(line); f.missed().remove(line); }
        else if (!f.covered().contains(line)) f.missed().add(line);
    }

    /** Whether repository path {@code repoPath} is the source file a report entry names. */
    public static boolean matches(String repoPath, String module, String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        return repoPath.startsWith(module) && (repoPath.equals(p) || repoPath.endsWith("/" + p));
    }

    private static String attr(String v) { return v == null ? "" : v.replace('\\', '/').strip(); }
    private static int number(String value) {
        if (value == null) return 0;
        try { long v = Long.parseLong(value.trim()); return v < 0 ? 0 : (int) Math.min(v, Integer.MAX_VALUE); } catch (NumberFormatException ex) { return 0; }
    }
}
