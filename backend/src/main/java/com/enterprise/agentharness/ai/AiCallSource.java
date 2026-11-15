package com.enterprise.agentharness.ai;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * What an AI call is for (a QA run, a task's plan, a test-generation draft, an evaluation run), so the AI call log can
 * link each call to the page it belongs to. Callers set it around their work; {@link AiService} reads it on the same
 * thread. Work handed to another thread carries it with {@link #propagate}.
 */
public final class AiCallSource {
    public record Source(String type, String id) {}

    private static final ThreadLocal<Source> CURRENT = new ThreadLocal<>();

    private AiCallSource() {}

    public static Source current() { return CURRENT.get(); }

    public static <T> T with(String type, String id, Supplier<T> body) {
        Source previous = CURRENT.get();
        CURRENT.set(new Source(type, id));
        try { return body.get(); }
        finally { restore(previous); }
    }

    /** {@link #with} for work that throws checked exceptions. */
    public static <T> T call(String type, String id, Callable<T> body) throws Exception {
        Source previous = CURRENT.get();
        CURRENT.set(new Source(type, id));
        try { return body.call(); }
        finally { restore(previous); }
    }

    /** The task runs with the source of the thread that created it. */
    public static <T> Callable<T> propagate(Callable<T> task) {
        Source source = CURRENT.get();
        return () -> {
            Source previous = CURRENT.get();
            CURRENT.set(source);
            try { return task.call(); }
            finally { restore(previous); }
        };
    }

    private static void restore(Source previous) {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
