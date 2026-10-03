package com.benesquivelmusic.daw.core.recording;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Ends every take a test left running and fails the test if a
 * {@code capture-flush} thread it started is still alive afterwards.
 *
 * <p>Before each test it snapshots the live {@code capture-flush} threads.
 * After each test — whether the test passed or failed — it ends every
 * pipeline {@link PipelineLifecycleTestSupport#startRecording} started on
 * this thread during the test and is still recording, preparing or
 * finalising ({@link PipelineLifecycleTestSupport#endEveryStartedTake()}),
 * then joins (bounded by {@link PipelineLifecycleTestSupport#LIFECYCLE_GUARD})
 * every {@code capture-flush} thread that was not alive before the test and
 * fails if one survives. A failure of the cleanup itself is reported, never
 * swallowed: it fails the test, or is added as suppressed to the test's own
 * failure by JUnit.</p>
 */
public final class CaptureFlushThreadLeakGuard implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(CaptureFlushThreadLeakGuard.class);
    private static final String BEFORE = "capture-flush threads alive before the test";

    @Override
    public void beforeEach(ExtensionContext context) {
        PipelineLifecycleTestSupport.forgetStartedTakes();
        context.getStore(NAMESPACE).put(BEFORE, liveCaptureFlushThreads());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void afterEach(ExtensionContext context) throws Exception {
        AssertionError failure = null;
        try {
            PipelineLifecycleTestSupport.endEveryStartedTake();
        } catch (AssertionError | RuntimeException e) {
            failure = new AssertionError("ending the takes the test left running failed", e);
        }
        Set<Thread> before = context.getStore(NAMESPACE).get(BEFORE, Set.class);
        List<Thread> leaked = new ArrayList<>();
        for (Thread thread : liveCaptureFlushThreads()) {
            if (before != null && before.contains(thread)) {
                continue;
            }
            thread.join(PipelineLifecycleTestSupport.LIFECYCLE_GUARD.toMillis());
            if (thread.isAlive()) {
                leaked.add(thread);
            }
        }
        if (!leaked.isEmpty()) {
            AssertionError leak = new AssertionError("capture-flush thread(s) still alive "
                    + TimeUnit.MILLISECONDS.toSeconds(PipelineLifecycleTestSupport.LIFECYCLE_GUARD.toMillis())
                    + " s after the test: " + leaked);
            if (failure == null) {
                failure = leak;
            } else {
                failure.addSuppressed(leak);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static Set<Thread> liveCaptureFlushThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> thread.getName().equals(CaptureFlushService.THREAD_NAME))
                .collect(Collectors.toSet());
    }
}
