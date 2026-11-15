package com.enterprise.agentharness.ai;

import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiCallSourceTest {
    @Test
    void theSourceIsScopedAndFollowsWorkToAnotherThread() throws Exception {
        assertThat(AiCallSource.current()).isNull();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var seen = AiCallSource.with("task", "t-1", () -> {
                var inner = AiCallSource.with("qa-run", "r-1", AiCallSource::current);
                assertThat(inner).isEqualTo(new AiCallSource.Source("qa-run", "r-1"));
                assertThat(AiCallSource.current()).isEqualTo(new AiCallSource.Source("task", "t-1"));
                try { return executor.submit(AiCallSource.propagate(AiCallSource::current)).get(); }
                catch (Exception ex) { throw new IllegalStateException(ex); }
            });
            assertThat(seen).isEqualTo(new AiCallSource.Source("task", "t-1"));
            assertThat(AiCallSource.current()).isNull();
            // The worker thread does not keep the source after the task.
            assertThat(executor.submit(AiCallSource::current).get()).isNull();
        } finally { executor.shutdownNow(); }
    }

    @Test
    void storedTextIsBounded() {
        String big = "x".repeat(AiService.MAX_STORED_CHARS + 5);
        assertThat(AiService.bounded(big)).startsWith("x".repeat(100)).endsWith("[5 more characters not stored]");
        assertThat(AiService.bounded("short")).isEqualTo("short");
    }
}
