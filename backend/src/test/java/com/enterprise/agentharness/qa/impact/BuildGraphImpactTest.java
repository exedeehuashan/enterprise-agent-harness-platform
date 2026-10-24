package com.enterprise.agentharness.qa.impact;

import com.enterprise.agentharness.qa.SuiteDefinition;
import com.enterprise.agentharness.qa.QaSuiteToolsAccess;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Gradle builds and npm/pnpm workspaces (P45): graphs, impact, selection and the narrowed command. */
class BuildGraphImpactTest {
    /** A Gradle build: core ← service ← web (Kotlin DSL, project() and accessor references), plus a nested tools:cli. */
    private static ChangeImpactAnalyzerTest.Repo gradle() {
        var r = new ChangeImpactAnalyzerTest.Repo();
        r.files.put("settings.gradle.kts", "rootProject.name = \"shop\"\n// include(\":old\")\ninclude(\":core\", \":service\")\ninclude(\":web\")\ninclude(\":tools:cli\")\n");
        r.files.put("build.gradle.kts", "plugins { java }");
        r.files.put("core/build.gradle.kts", "dependencies { implementation(\"org.slf4j:slf4j-api:2.0.16\") }");
        r.files.put("core/src/main/java/shop/core/Money.java", "");
        r.files.put("core/src/test/java/shop/core/MoneyTest.java", "");
        r.files.put("core/src/test/java/shop/core/Fixtures.java", "");
        r.files.put("service/build.gradle.kts", "dependencies { implementation(project(\":core\")) }");
        r.files.put("service/src/main/kotlin/shop/service/Orders.kt", "");
        r.files.put("service/src/test/kotlin/shop/service/OrdersTest.kt", "");
        r.files.put("web/build.gradle.kts", "dependencies { implementation(projects.service) }");
        r.files.put("web/src/test/java/shop/web/ApiIT.java", "");
        r.files.put("tools/cli/build.gradle", "dependencies { implementation project(path: ':core') }");
        r.files.put("tools/cli/src/main/java/shop/cli/Main.java", "");
        r.files.put("gradle/libs.versions.toml", "");
        r.files.put("README.md", "");
        return r;
    }

    /** npm workspaces under packages/* and apps/*: ui ← shop-web; utils used by both; one excluded package. */
    private static ChangeImpactAnalyzerTest.Repo npm() {
        var r = new ChangeImpactAnalyzerTest.Repo();
        r.files.put("package.json", "{\"name\":\"root\",\"private\":true,\"workspaces\":[\"packages/*\",\"apps/*\",\"!packages/legacy\"]}");
        r.files.put("package-lock.json", "{}");
        r.files.put("packages/utils/package.json", "{\"name\":\"@shop/utils\"}");
        r.files.put("packages/utils/src/money.ts", "");
        r.files.put("packages/utils/src/money.test.ts", "");
        r.files.put("packages/ui/package.json", "{\"name\":\"@shop/ui\",\"dependencies\":{\"@shop/utils\":\"*\",\"react\":\"18\"}}");
        r.files.put("packages/ui/src/Button.tsx", "");
        r.files.put("packages/ui/src/Button.spec.tsx", "");
        r.files.put("apps/shop-web/package.json", "{\"name\":\"shop-web\",\"devDependencies\":{\"@shop/ui\":\"workspace:*\"}}");
        r.files.put("apps/shop-web/src/App.test.tsx", "");
        r.files.put("packages/legacy/package.json", "{\"name\":\"legacy\",\"dependencies\":{\"@shop/utils\":\"*\"}}");
        return r;
    }

    private static List<String> dirs(ChangeImpactAnalyzer.Impact i) { return i.modules().stream().map(ChangeImpactAnalyzer.AffectedModule::dir).toList(); }

    @Test
    void gradleProjectsDependentsAndTestsAreFound() throws Exception {
        var r = gradle();
        r.changed.add("core/src/main/java/shop/core/Money.java");
        var impact = ChangeImpactAnalyzer.analyze(r, "b", "h");
        assertThat(impact.buildSystem()).isEqualTo("gradle");
        assertThat(dirs(impact)).containsExactly("core", "service", "tools/cli", "web");
        assertThat(impact.modules()).allMatch(m -> m.buildSystem().equals("gradle"));
        assertThat(impact.modules().get(0).coordinates()).isEqualTo(":core");
        assertThat(impact.modules().get(0).reason()).isEqualTo("CHANGED");
        assertThat(impact.modules().get(3).reason()).isEqualTo("DEPENDS_ON_CHANGED");
        // JVM test classes by naming convention; fixtures are not tests.
        assertThat(impact.testClasses()).containsExactlyInAnyOrder("shop.core.MoneyTest", "shop.service.OrdersTest", "shop.web.ApiIT");

        var decision = ImpactSelection.decide(impact, ".eahp/qa-suites.json", "gradle");
        assertThat(decision.mode()).isEqualTo(ImpactSelection.Mode.MODULES);
        assertThat(decision.modules()).containsExactly(":core", ":service", ":tools:cli", ":web");
        assertThat(ImpactSelection.decide(impact, ".eahp/qa-suites.json", "maven").reason()).isEqualTo("NOT_MAVEN");

        // A leaf change affects only itself.
        r.changed.clear();
        r.changed.add("web/src/main/java/shop/web/Api.java");
        assertThat(ImpactSelection.decide(ChangeImpactAnalyzer.analyze(r, "b", "h"), ".eahp/qa-suites.json", "gradle").modules()).containsExactly(":web");
    }

    @Test
    void gradleBuildWideFilesRunEverything() throws Exception {
        for (String file : List.of("settings.gradle.kts", "build.gradle.kts", "gradle/libs.versions.toml", "buildSrc/src/main/kotlin/conventions.gradle.kts", "gradle.properties")) {
            var r = gradle();
            r.changed.add(file);
            var impact = ChangeImpactAnalyzer.analyze(r, "b", "h");
            assertThat(impact.wholeBuildOf("gradle")).as(file).isTrue();
            assertThat(ImpactSelection.decide(impact, ".eahp/qa-suites.json", "gradle").reason()).as(file).isEqualTo("BUILD_CHANGED");
        }
        var docs = gradle();
        docs.changed.add("README.md");
        var impact = ChangeImpactAnalyzer.analyze(docs, "b", "h");
        assertThat(ImpactSelection.decide(impact, ".eahp/qa-suites.json", "gradle").mode()).isEqualTo(ImpactSelection.Mode.NOT_AFFECTED);
    }

    @Test
    void npmWorkspacesFollowPackageDependencies() throws Exception {
        var r = npm();
        r.changed.add("packages/utils/src/money.ts");
        var impact = ChangeImpactAnalyzer.analyze(r, "b", "h");
        assertThat(impact.buildSystem()).isEqualTo("npm");
        // legacy is excluded by "!packages/legacy", so it is neither a module nor a dependent.
        assertThat(dirs(impact)).containsExactly("apps/shop-web", "packages/ui", "packages/utils");
        assertThat(impact.modules().stream().filter(m -> m.dir().equals("packages/ui")).findFirst().orElseThrow().coordinates()).isEqualTo("@shop/ui");
        assertThat(impact.testClasses()).containsExactlyInAnyOrder("packages/utils/src/money.test.ts", "packages/ui/src/Button.spec.tsx", "apps/shop-web/src/App.test.tsx");
        assertThat(ImpactSelection.decide(impact, ".eahp/qa-suites.json", "npm").modules()).containsExactly("apps/shop-web", "packages/ui", "packages/utils");

        var lock = npm();
        lock.changed.add("package-lock.json");
        assertThat(ImpactSelection.decide(ChangeImpactAnalyzer.analyze(lock, "b", "h"), ".eahp/qa-suites.json", "npm").reason()).isEqualTo("BUILD_CHANGED");
    }

    @Test
    void pnpmWorkspaceFilesAreRead() throws Exception {
        var r = new ChangeImpactAnalyzerTest.Repo();
        r.files.put("pnpm-workspace.yaml", "packages:\n  - 'libs/*'   # libraries\n  - \"apps/web\"\n");
        r.files.put("libs/a/package.json", "{\"name\":\"a\"}");
        r.files.put("apps/web/package.json", "{\"name\":\"web\",\"dependencies\":{\"a\":\"workspace:*\"}}");
        r.changed.add("libs/a/index.js");
        assertThat(dirs(ChangeImpactAnalyzer.analyze(r, "b", "h"))).containsExactly("apps/web", "libs/a");
    }

    @Test
    void mavenAndWorkspacesSideBySideStaySeparate() throws Exception {
        var r = npm();
        r.files.put("pom.xml", "<project><groupId>g</groupId><artifactId>root</artifactId><modules><module>backend/api</module></modules></project>");
        r.files.put("backend/api/pom.xml", "<project><parent><groupId>g</groupId><artifactId>root</artifactId></parent><artifactId>api</artifactId></project>");
        r.changed.add("packages/ui/src/Button.tsx");
        var impact = ChangeImpactAnalyzer.analyze(r, "b", "h");
        assertThat(impact.buildSystem()).isEqualTo("maven+npm");
        assertThat(impact.filesOutsideModules()).isEmpty();
        // The backend suite is not affected by a frontend package change; the workspace suite is.
        assertThat(ImpactSelection.decide(impact, ".eahp/qa-suites.json", "maven").mode()).isEqualTo(ImpactSelection.Mode.NOT_AFFECTED);
        assertThat(ImpactSelection.decide(impact, ".eahp/qa-suites.json", "npm").modules()).containsExactly("apps/shop-web", "packages/ui");
    }

    @Test
    void accessorsAndGlobsFollowGradleAndWorkspaceRules() {
        assertThat(BuildGraphs.accessor(":core-api:web_ui")).isEqualTo("coreApi.webUi");
        assertThat(BuildGraphs.glob("packages/*").matcher("packages/ui").matches()).isTrue();
        assertThat(BuildGraphs.glob("packages/*").matcher("packages/ui/sub").matches()).isFalse();
        assertThat(BuildGraphs.glob("./apps/**").matcher("apps/a/b").matches()).isTrue();
    }

    @Test
    void selectiveRunsNarrowTheCommandPerBuildSystem() {
        var gradle = suite(SuiteDefinition.GRADLE_PROJECTS, List.of("./gradlew", "--no-daemon", "test"));
        assertThat(QaSuiteToolsAccess.selectiveCommand(gradle, List.of(":core", ":web"))).containsExactly("./gradlew", "--no-daemon", ":core:test", ":web:test");
        assertThat(QaSuiteToolsAccess.selectiveCommand(gradle, List.of())).containsExactly("./gradlew", "--no-daemon", "test");
        var npm = suite(SuiteDefinition.NPM_WORKSPACES, List.of("npm", "test"));
        assertThat(QaSuiteToolsAccess.selectiveCommand(npm, List.of("packages/ui"))).containsExactly("npm", "test", "--workspace=packages/ui");
        var pnpm = suite(SuiteDefinition.PNPM_WORKSPACES, List.of("pnpm", "-r", "test"));
        assertThat(QaSuiteToolsAccess.selectiveCommand(pnpm, List.of("libs/a", "apps/web"))).containsExactly("pnpm", "--filter", "./libs/a", "--filter", "./apps/web", "-r", "test");
        var maven = suite(SuiteDefinition.MAVEN_MODULES, List.of("mvn", "verify"));
        assertThat(QaSuiteToolsAccess.selectiveCommand(maven, List.of("a", "b"))).containsExactly("mvn", "verify", "-pl", "a,b", "-am");
    }

    private static SuiteDefinition suite(String selection, List<String> command) {
        return new SuiteDefinition(SuiteDefinition.REPOSITORY, "s", "S", null, "img", command, List.of("r"), "egress", false, 600, selection, List.of(), null,
                List.of(), Map.of(), null);
    }
}
