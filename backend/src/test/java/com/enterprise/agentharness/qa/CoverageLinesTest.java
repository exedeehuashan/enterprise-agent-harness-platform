package com.enterprise.agentharness.qa;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class CoverageLinesTest {
    /** order-api: Order.java lines 10 (covered), 11 (missed), 12 (partly covered = covered); 13 has no code. */
    public static final String JACOCO_LINES = """
            <?xml version="1.0" encoding="UTF-8"?><!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="order-api"><package name="com/example/order"><sourcefile name="Order.java">
              <line nr="10" mi="0" ci="4" mb="0" cb="0"/><line nr="11" mi="3" ci="0" mb="0" cb="0"/><line nr="12" mi="1" ci="2" mb="1" cb="1"/>
              <line nr="13" mi="0" ci="0" mb="0" cb="0"/>
              <counter type="LINE" missed="1" covered="2"/></sourcefile><counter type="LINE" missed="1" covered="2"/></package>
              <counter type="LINE" missed="1" covered="2"/></report>
            """;

    private static List<CoverageLines.FileLines> parse(String xml, String module) throws Exception {
        return CoverageLines.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), module);
    }

    @Test
    void readsLinesFromJacocoAndCobertura() throws Exception {
        var j = parse(JACOCO_LINES, "backend/order-api/");
        assertThat(j).hasSize(1);
        assertThat(j.get(0).path()).isEqualTo("com/example/order/Order.java");
        assertThat(j.get(0).covered()).containsExactly(10, 12);
        assertThat(j.get(0).missed()).containsExactly(11);
        // Cobertura repeats lines per method: covered anywhere means covered.
        var c = parse("""
                <coverage><packages><package><classes><class filename="src/cart.ts"><methods><method><lines><line number="5" hits="0"/></lines></method></methods>
                <lines><line number="5" hits="2"/><line number="6" hits="0"/></lines></class></classes></package></packages></coverage>
                """, "frontend/order-web/");
        assertThat(c.get(0).covered()).containsExactly(5);
        assertThat(c.get(0).missed()).containsExactly(6);
        assertThat(parse("<testsuite/>", "")).isEmpty();
    }

    @Test
    void reportsBelongToTheModuleAboveTheirBuildDirectory() {
        Path root = Path.of("/w/src");
        assertThat(CoverageLines.module(root, root.resolve("backend/order-api/target/site/jacoco/jacoco.xml"))).isEqualTo("backend/order-api/");
        assertThat(CoverageLines.module(root, root.resolve("target/site/jacoco/jacoco.xml"))).isEmpty();
        assertThat(CoverageLines.module(root, root.resolve("frontend/order-web/coverage/cobertura-coverage.xml"))).isEqualTo("frontend/order-web/");
    }

    @Test
    void repositoryPathsMatchByModuleAndSuffix() {
        String path = "backend/order-api/src/main/java/com/example/order/Order.java";
        assertThat(CoverageLines.matches(path, "backend/order-api/", "com/example/order/Order.java")).isTrue();
        assertThat(CoverageLines.matches(path, "backend/catalog-api/", "com/example/order/Order.java")).isFalse();
        assertThat(CoverageLines.matches(path, "", "example/order/Order.java")).isTrue();
        assertThat(CoverageLines.matches(path, "", "der/Order.java")).isFalse();
        var specific = new CoverageLines.FileLines("backend/order-api/", "com/example/order/Order.java", new java.util.TreeSet<>(), new java.util.TreeSet<>());
        var loose = new CoverageLines.FileLines("", "order/Order.java", new java.util.TreeSet<>(), new java.util.TreeSet<>());
        assertThat(NewCodeCoverage.match(path, List.of(loose, specific))).isSameAs(specific);
    }
}
