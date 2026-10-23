package com.enterprise.agentharness.qa;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Reads JUnit XML (Surefire/Failsafe format) without trusting it: no DTDs, no external entities, bounded sizes. */
public final class JUnitReports {
    static final int MAX_FILES = 500;
    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    static final int MAX_FAILED_TESTS = 50;
    private JUnitReports() {}

    public record FailedTest(String suite, String name, String kind, String message) {}
    public record Summary(int tests, int failures, int errors, int skipped, int reportFiles, int unreadableFiles, List<FailedTest> failedTests) {}
    /** One executed test case; {@code detail} is the start of the failure's stack trace, for triage. */
    public record TestCase(String className, String name, String status, Integer durationMs, String failureType, String message, String detail) {}

    /** All test cases of one report, parsed with the same protections as {@link #summarize}. */
    public static List<TestCase> cases(java.io.InputStream xml) throws Exception {
        var root = secureFactory().newDocumentBuilder().parse(xml).getDocumentElement();
        NodeList testcases = root.getElementsByTagName("testcase");
        List<TestCase> result = new ArrayList<>();
        for (int i = 0; i < testcases.getLength() && result.size() < 5000; i++) {
            var c = (Element) testcases.item(i);
            String status = "PASSED", type = null, message = null, detail = null;
            for (String kind : List.of("failure", "error", "skipped")) {
                NodeList found = c.getElementsByTagName(kind);
                if (found.getLength() == 0) continue;
                var e = (Element) found.item(0);
                status = kind.equals("failure") ? "FAILED" : kind.equals("error") ? "ERROR" : "SKIPPED";
                type = clip(e.getAttribute("type"), 200);
                message = clip(e.getAttribute("message"), 500);
                detail = clip(e.getTextContent(), 2000);
                break;
            }
            Integer duration = null;
            try { duration = (int) Math.round(Double.parseDouble(c.getAttribute("time").trim()) * 1000); } catch (NumberFormatException ignored) { }
            result.add(new TestCase(clip(c.getAttribute("classname"), 300), clip(c.getAttribute("name"), 300), status, duration,
                    type == null || type.isEmpty() ? null : type, message, detail));
        }
        return result;
    }

    private static DocumentBuilderFactory secureFactory() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    /** Finds {@code TEST-*.xml} under any directory whose path ends with one of {@code reportDirs}. */
    public static List<Path> find(Path root, List<String> reportDirs) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> files = Files.walk(root, 12)) {
            return files.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().matches("TEST-.*\\.xml"))
                    .filter(f -> reportDirs.stream().anyMatch(d -> root.relativize(f.getParent()).toString().replace('\\', '/').endsWith(d)))
                    .sorted().limit(MAX_FILES).toList();
        }
    }

    public static Summary summarize(List<Path> files) {
        int tests = 0, failures = 0, errors = 0, skipped = 0, unreadable = 0;
        List<FailedTest> failed = new ArrayList<>();
        for (Path file : files) {
            try {
                if (Files.size(file) > MAX_FILE_BYTES) { unreadable++; continue; }
                var root = secureFactory().newDocumentBuilder().parse(file.toFile()).getDocumentElement();
                NodeList suites = root.getTagName().equals("testsuite") ? null : root.getElementsByTagName("testsuite");
                List<Element> elements = new ArrayList<>();
                if (suites == null) elements.add(root);
                else for (int i = 0; i < suites.getLength(); i++) elements.add((Element) suites.item(i));
                for (Element suite : elements) {
                    tests += number(suite, "tests");
                    failures += number(suite, "failures");
                    errors += number(suite, "errors");
                    skipped += number(suite, "skipped");
                    NodeList cases = suite.getElementsByTagName("testcase");
                    for (int i = 0; i < cases.getLength() && failed.size() < MAX_FAILED_TESTS; i++) {
                        var testCase = (Element) cases.item(i);
                        for (String kind : List.of("failure", "error")) {
                            NodeList problems = testCase.getElementsByTagName(kind);
                            if (problems.getLength() > 0 && failed.size() < MAX_FAILED_TESTS) {
                                failed.add(new FailedTest(clip(testCase.getAttribute("classname"), 200), clip(testCase.getAttribute("name"), 200), kind,
                                        clip(((Element) problems.item(0)).getAttribute("message"), 300)));
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                unreadable++;
            }
        }
        return new Summary(tests, failures, errors, skipped, files.size(), unreadable, failed);
    }

    /** Zips the report files with paths relative to {@code root}; returns null if the archive would exceed {@code maxBytes}. */
    public static byte[] zip(Path root, List<Path> files, long maxBytes) throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            for (Path file : files) {
                if (Files.size(file) > MAX_FILE_BYTES) continue;
                zip.putNextEntry(new ZipEntry(root.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, zip);
                zip.closeEntry();
                if (buffer.size() > maxBytes) return null;
            }
        }
        return buffer.size() > maxBytes ? null : buffer.toByteArray();
    }

    private static int number(Element e, String attribute) {
        try { return Math.max(0, Integer.parseInt(e.getAttribute(attribute).trim())); } catch (NumberFormatException ex) { return 0; }
    }
    private static String clip(String value, int max) { return value == null ? "" : value.length() <= max ? value : value.substring(0, max) + "…"; }
}
