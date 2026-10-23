package com.enterprise.agentharness.qa.impact;

import com.enterprise.agentharness.qa.impact.ChangeImpactAnalyzer.AffectedModule;
import com.enterprise.agentharness.qa.impact.ChangeImpactAnalyzer.Impact;
import com.enterprise.agentharness.qa.impact.ImpactSelection.Mode;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImpactSelectionTest {
    private static final String FILE = ".eahp/qa-suites.json";

    private static Impact impact(List<String> modules, List<String> outside) {
        return new Impact("maven", modules.size() + outside.size(), List.of(), false, false,
                modules.stream().map(d -> new AffectedModule(d, "g:" + d, "CHANGED", 1, 1)).toList(), List.of(), outside, List.of());
    }

    @Test
    void affectedModulesAreSelectedWhenEveryBuildRelevantChangeIsInAModule() {
        var d = ImpactSelection.decide(impact(List.of("backend/catalog-api", "backend/catalog-domain"), List.of("frontend/src/App.tsx", "docs/x.md")), FILE);
        assertThat(d.mode()).isEqualTo(Mode.MODULES);
        assertThat(d.modules()).containsExactly("backend/catalog-api", "backend/catalog-domain");
    }

    @Test
    void changesOutsideEveryModuleSkipTheSuite() {
        var d = ImpactSelection.decide(impact(List.of(), List.of("frontend/src/App.tsx", "README.md")), FILE);
        assertThat(d.mode()).isEqualTo(Mode.NOT_AFFECTED);
        assertThat(d.reason()).isEqualTo("NO_MODULE_CHANGED");
    }

    @Test
    void anythingUncertainRunsTheWholeSuite() {
        assertThat(ImpactSelection.decide(null, FILE).reason()).isEqualTo("IMPACT_UNAVAILABLE");
        assertThat(ImpactSelection.decide(new Impact("unknown", 1, List.of(), false, false, List.of(), List.of(), List.of(), List.of()), FILE).reason()).isEqualTo("NOT_MAVEN");
        assertThat(ImpactSelection.decide(new Impact("maven", 3000, List.of(), true, false, List.of(), List.of(), List.of(), List.of()), FILE).reason()).isEqualTo("TOO_MANY_CHANGES");
        assertThat(ImpactSelection.decide(new Impact("maven", 1, List.of(), false, true, List.of(), List.of(), List.of(), List.of()), FILE).reason()).isEqualTo("BUILD_CHANGED");
        for (String path : List.of(".mvn/maven.config", "mvnw", "tools/mvnw.cmd", FILE, ".eahp/other.json", "build/pom.xml")) {
            var d = ImpactSelection.decide(impact(List.of("backend/order-api"), List.of(path)), FILE);
            assertThat(d.mode()).as(path).isEqualTo(Mode.FULL);
            assertThat(d.reason()).isEqualTo("BUILD_CHANGED");
        }
        assertThat(ImpactSelection.decide(impact(List.of("", "lib"), List.of()), FILE).reason()).isEqualTo("ROOT_MODULE");
        assertThat(ImpactSelection.decide(impact(java.util.stream.IntStream.range(0, 51).mapToObj(i -> "m" + i).toList(), List.of()), FILE).reason()).isEqualTo("TOO_MANY_MODULES");
        var outside = java.util.stream.IntStream.range(0, ChangeImpactAnalyzer.MAX_LISTED).mapToObj(i -> "docs/" + i + ".md").toList();
        assertThat(ImpactSelection.decide(impact(List.of("lib"), outside), FILE).reason()).isEqualTo("TOO_MANY_CHANGES");
    }
}
