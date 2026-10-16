#!/usr/bin/env bash
# Optional integration test: Docker and a warmed cache, never real credentials.
set -euo pipefail
controller="$PWD/.github/automation/isolated-maven.sh"
test -f "$controller"
test -d "${RUNNER_TEMP:?}/agent-maven-cache"
fixture="$(mktemp -d "$RUNNER_TEMP/negative.XXXXXX")"
cp -R backend "$fixture/backend"
mkdir -p "$fixture/backend/src/test/java/automationfixture"
cat > "$fixture/backend/src/test/java/automationfixture/ContainerBoundaryTest.java" <<'JAVA'
package automationfixture;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ContainerBoundaryTest {
    @Test void preservesIsolation() throws Exception {
        for (String name : new String[]{"GH_TOKEN", "APP_TOKEN", "OPENAI_API_KEY",
                "GITHUB_OUTPUT", "GITHUB_ENV", "ACTIONS_RUNTIME_TOKEN"}) {
            assertNull(System.getenv(name), name + " must not reach candidate tests");
        }
        assertNotEquals("root", System.getProperty("user.name"));
        assertFalse(Files.isWritable(Path.of("/source")));
        assertFalse(Files.isWritable(Path.of("/m2")));
        assertFalse(Files.exists(Path.of("/var/run/docker.sock")));
        String status = Files.readString(Path.of("/proc/self/status"));
        assertTrue(status.contains("NoNewPrivs:\t1"));
        assertTrue(status.contains("CapEff:\t0000000000000000"));
        var interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            assertTrue(interfaces.nextElement().isLoopback(), "Only loopback networking is allowed");
        }
    }
}
JAVA
cat > "$fixture/backend/src/test/java/automationfixture/DeliberateFailureTest.java" <<'JAVA'
package automationfixture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.fail;
class DeliberateFailureTest {
    @Test void rejectsCandidate() { fail("Intentional negative verifier fixture"); }
}
JAVA
log="$fixture/negative.log"
if (cd "$fixture" && bash "$controller" test) > "$log" 2>&1; then
  echo 'SECURITY FAILURE: failing candidate was accepted' >&2
  exit 1
fi
rg -q 'DeliberateFailureTest.rejectsCandidate.*Intentional negative verifier fixture' "$log"
rg -q 'Tests run: [0-9]+, Failures: 1, Errors: 0, Skipped: 0' "$log"
rg -q 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0.*ContainerBoundaryTest' "$log"
echo "Expected candidate failure confirmed; isolation probe passed. Evidence: $log"
