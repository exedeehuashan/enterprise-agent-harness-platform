package com.enterprise.agentharness.qa.impact;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChangeImpactAnalyzerTest {
    /** In-memory repository: one file tree for the head commit and a fixed diff. */
    static final class Repo implements RepositoryView {
        final Map<String, String> files = new HashMap<>();
        final List<String> changed = new ArrayList<>();
        public List<String> changedFiles(String base, String head) { return changed; }
        public List<String> files(String commit) { return new ArrayList<>(files.keySet()); }
        public Optional<String> read(String commit, String path) { return Optional.ofNullable(files.get(path)); }
    }

    private static String pom(String artifact, String... deps) {
        var sb = new StringBuilder("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><parent><groupId>com.example.shop</groupId><artifactId>shop</artifactId>"
                + "<version>1</version></parent><artifactId>" + artifact + "</artifactId><dependencies>");
        for (String d : deps) sb.append("<dependency><groupId>").append(d.startsWith("$") ? "${project.groupId}" : "com.example.shop")
                .append("</groupId><artifactId>").append(d.replace("$", "")).append("</artifactId></dependency>");
        sb.append("<dependency><groupId>org.springframework</groupId><artifactId>spring-web</artifactId></dependency></dependencies>")
                .append("<build><plugins><plugin><groupId>com.example.shop</groupId><artifactId>order-api</artifactId></plugin></plugins></build></project>");
        return sb.toString();
    }

    /** Shaped like online-shop: order and catalog stacks, domain ← application ← persistence ← api. */
    private static Repo shop() {
        var r = new Repo();
        r.files.put("pom.xml", "<project><groupId>com.example.shop</groupId><artifactId>shop</artifactId></project>");
        r.files.put("backend/order-domain/pom.xml", pom("order-domain"));
        r.files.put("backend/order-application/pom.xml", pom("order-application", "order-domain"));
        r.files.put("backend/order-adapters-persistence/pom.xml", pom("order-adapters-persistence", "$order-application"));
        r.files.put("backend/order-api/pom.xml", pom("order-api", "order-adapters-persistence"));
        r.files.put("backend/catalog-domain/pom.xml", pom("catalog-domain"));
        r.files.put("backend/catalog-api/pom.xml", pom("catalog-api", "catalog-domain"));
        r.files.put("backend/order-domain/src/main/java/shop/order/Order.java", "");
        r.files.put("backend/order-domain/src/test/java/shop/order/OrderTest.java", "");
        r.files.put("backend/order-domain/src/test/java/shop/order/OrderFixtures.java", "");
        r.files.put("backend/order-application/src/test/java/shop/order/OrderServiceTests.java", "");
        r.files.put("backend/order-api/src/test/java/shop/order/api/OrderApiIT.java", "");
        r.files.put("backend/catalog-domain/src/test/java/shop/catalog/ProductTest.java", "");
        r.files.put("frontend/src/app.ts", "");
        return r;
    }

    @Test
    void aDomainChangeAffectsEveryModuleThatDependsOnIt() throws IOException {
        var repo = shop();
        repo.changed.add("backend/order-domain/src/main/java/shop/order/Order.java");
        var impact = ChangeImpactAnalyzer.analyze(repo, "base", "head");
        assertThat(impact.buildSystem()).isEqualTo("maven");
        assertThat(impact.modules()).extracting(ChangeImpactAnalyzer.AffectedModule::dir).containsExactly(
                "backend/order-adapters-persistence", "backend/order-api", "backend/order-application", "backend/order-domain");
        assertThat(impact.modules()).filteredOn(m -> m.dir().equals("backend/order-domain")).first()
                .extracting(ChangeImpactAnalyzer.AffectedModule::reason, ChangeImpactAnalyzer.AffectedModule::changedFiles).containsExactly("CHANGED", 1);
        assertThat(impact.modules()).filteredOn(m -> m.dir().equals("backend/order-api")).first()
                .extracting(ChangeImpactAnalyzer.AffectedModule::reason).isEqualTo("DEPENDS_ON_CHANGED");
        assertThat(impact.testClasses()).containsExactlyInAnyOrder("shop.order.OrderTest", "shop.order.OrderServiceTests", "shop.order.api.OrderApiIT");
        assertThat(impact.notes()).isEmpty();
    }

    @Test
    void changesWithoutOwnTestsAndOutsideModulesAreCalledOut() throws IOException {
        var repo = shop();
        repo.changed.addAll(List.of("backend/order-adapters-persistence/src/main/java/shop/Repo.java", "frontend/src/app.ts", ".github/workflows/ci.yml"));
        var impact = ChangeImpactAnalyzer.analyze(repo, "b", "h");
        assertThat(impact.modules()).extracting(ChangeImpactAnalyzer.AffectedModule::dir)
                .containsExactly("backend/order-adapters-persistence", "backend/order-api");
        assertThat(impact.filesOutsideModules()).containsExactly("frontend/src/app.ts", ".github/workflows/ci.yml");
        assertThat(impact.notes()).anyMatch(n -> n.startsWith("backend/order-adapters-persistence changed but has no test classes"))
                .anyMatch(n -> n.startsWith("2 changed file(s) are outside the Maven modules"));
    }

    @Test
    void aRootPomChangeAffectsTheWholeBuild() throws IOException {
        var repo = shop();
        repo.changed.add("pom.xml");
        var impact = ChangeImpactAnalyzer.analyze(repo, "b", "h");
        assertThat(impact.wholeBuild()).isTrue();
        assertThat(impact.modules()).hasSize(6).allMatch(m -> m.reason().equals("BUILD_CHANGED"));
        assertThat(impact.testClasses()).contains("shop.catalog.ProductTest");
    }

    @Test
    void nonMavenRepositoriesAreReportedNotGuessed() throws IOException {
        var repo = new Repo();
        repo.files.put("package.json", "{}");
        repo.changed.add("src/index.js");
        var impact = ChangeImpactAnalyzer.analyze(repo, "b", "h");
        assertThat(impact.buildSystem()).isEqualTo("unknown");
        assertThat(impact.modules()).isEmpty();
        assertThat(impact.changedFiles()).containsExactly("src/index.js");
    }

    @Test
    void pomParsingIgnoresExternalAndPluginArtifactsAndRefusesEntities() {
        var graph = MavenModuleGraph.parse(Map.of(
                "pom.xml", "<project><groupId>g</groupId><artifactId>root</artifactId></project>",
                "a/pom.xml", "<project><parent><groupId>g</groupId></parent><artifactId>a</artifactId></project>",
                "b/pom.xml", "<project><parent><groupId>g</groupId></parent><artifactId>b</artifactId><dependencies><dependency><groupId>g</groupId>"
                        + "<artifactId>a</artifactId></dependency></dependencies><build><plugins><plugin><groupId>g</groupId><artifactId>c</artifactId></plugin></plugins></build></project>",
                "c/pom.xml", "<?xml version=\"1.0\"?><!DOCTYPE p [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><project><artifactId>&x;</artifactId></project>"));
        assertThat(graph.modules()).extracting(MavenModuleGraph.Module::dir).containsExactly("", "a", "b");
        assertThat(graph.dependentsOf(java.util.Set.of("a"))).containsExactly("b");
        assertThat(graph.moduleOf("b/src/main/java/X.java")).isEqualTo("b");
        assertThat(graph.moduleOf("docs/readme.md")).isEmpty();
    }
}
