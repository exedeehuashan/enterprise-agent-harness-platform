package com.enterprise.agentharness.qa;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

public class CoverageReportsTest {
    public static final String JACOCO = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?><!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="order-api"><sessioninfo id="x" start="1" dump="2"/>
              <package name="a"><class name="a/B"><counter type="LINE" missed="99" covered="1"/></class><counter type="LINE" missed="99" covered="1"/></package>
              <counter type="INSTRUCTION" missed="10" covered="90"/><counter type="BRANCH" missed="4" covered="6"/><counter type="LINE" missed="25" covered="75"/>
            </report>
            """;

    private static CoverageReports.Module parse(String xml) throws Exception {
        return CoverageReports.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "dir");
    }

    @Test
    void readsReportLevelTotalsFromJacocoAndCobertura() throws Exception {
        var j = parse(JACOCO);
        assertThat(j).isEqualTo(new CoverageReports.Module("order-api", "jacoco", 75, 25, 6, 4));
        var c = parse("<coverage line-rate=\"0.5\" lines-covered=\"40\" lines-valid=\"80\" branches-covered=\"3\" branches-valid=\"4\"><packages/></coverage>");
        assertThat(c).isEqualTo(new CoverageReports.Module("dir", "cobertura", 40, 40, 3, 1));
        assertThat(parse("<testsuite/>")).isNull();
    }

    @Test
    void externalEntitiesAreNeverResolved(@TempDir Path dir) throws Exception {
        Path secret = Files.writeString(dir.resolve("secret.txt"), "TOP SECRET");
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE report [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]><report name=\"&x;\"><counter type=\"LINE\" missed=\"1\" covered=\"1\"/></report>";
        Path file = Files.writeString(dir.resolve("jacoco.xml"), xxe);
        var summary = CoverageReports.summarize(dir, List.of(file));
        // Either the document is rejected or the entity stays unexpanded; the file's content never appears.
        assertThat(summary.modules().stream().map(CoverageReports.Module::name)).noneMatch(n -> n.contains("SECRET"));
    }

    @Test
    void findsReportsBySuffixAndSumsModules(@TempDir Path root) throws Exception {
        for (String m : List.of("backend/a", "backend/b")) {
            Path p = Files.createDirectories(root.resolve(m + "/target/site/jacoco")).resolve("jacoco.xml");
            Files.writeString(p, JACOCO.replace("order-api", m.substring(8)));
        }
        Files.createDirectories(root.resolve("node_modules/x/target/site/jacoco"));
        Files.writeString(root.resolve("node_modules/x/target/site/jacoco/jacoco.xml"), JACOCO);
        Files.writeString(root.resolve("backend/a/target/site/jacoco/broken.xml"), "<report");
        var files = CoverageReports.find(root, List.of("target/site/jacoco/jacoco.xml"));
        assertThat(files).hasSize(2);
        var summary = CoverageReports.summarize(root, files);
        assertThat(summary.linesCovered()).isEqualTo(150);
        assertThat(summary.linePercent()).isEqualTo(75.0);
        assertThat(summary.branchPercent()).isEqualTo(60.0);
        assertThat(CoverageReports.summarize(root, List.of(root.resolve("backend/a/target/site/jacoco/broken.xml"))).unreadable()).isEqualTo(1);
    }
}
