package com.enterprise.agentharness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The platform knows no business module: modules depend on the platform, never the other way round. 方案 4 (qa)
 * may use the code agent module (auto-fix composes it), but the code agent knows nothing of 方案 4.
 */
class ModuleBoundaryTest {
    private static final Path ROOT = Path.of("src/main/java/com/enterprise/agentharness");
    private static final Set<String> MODULES = Set.of("qa", "code");

    private static List<String> offenders(Pattern forbidden, java.util.function.Predicate<String> scope) throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String top = ROOT.relativize(f).getName(0).toString();
                if (!scope.test(top)) continue;
                if (forbidden.matcher(Files.readString(f)).find()) out.add(ROOT.relativize(f).toString());
            }
        }
        return out;
    }

    @Test
    void thePlatformImportsNoModule() throws IOException {
        assertThat(offenders(Pattern.compile("com\\.enterprise\\.agentharness\\.(qa|code)\\b"), top -> !MODULES.contains(top))).isEmpty();
    }

    @Test
    void theCodeAgentModuleDoesNotDependOnQa() throws IOException {
        assertThat(offenders(Pattern.compile("com\\.enterprise\\.agentharness\\.qa\\b"), "code"::equals)).isEmpty();
    }
}
