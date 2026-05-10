package com.benesquivelmusic.daw.app.longtests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.opentest4j.AssertionFailedError;

/**
 * JUnit 5 extension powering {@link LongRenderTest}.
 *
 * <p>Responsibilities:</p>
 * <ol>
 *   <li><b>Per-test working directory.</b> A fresh
 *       {@code daw-longtest-*} temp directory is created in
 *       {@code @BeforeEach} and deep-deleted in {@code @AfterEach}.
 *       Tests can request it via constructor / method parameter
 *       injection of {@link Path} (parameter name or annotated
 *       parameter resolved through {@link LongTestHarness}).</li>
 *   <li><b>File-handle leak detection.</b> The number of open file
 *       descriptors for the JVM (via {@code /proc/self/fd}, when
 *       available) is captured before and after the test. A net
 *       increase larger than a small slack threshold fails the test
 *       — long tests must close every file they open.</li>
 *   <li><b>Wall-clock budget enforcement.</b> Each test annotated with
 *       {@link LongRenderTest} declares an expected
 *       {@code budgetSeconds}. Exceeding {@code 2 ×} that budget fails
 *       the test with a performance-regression message.</li>
 * </ol>
 *
 * <p>The harness deliberately uses only {@code java.base} APIs and the
 * {@code /proc} filesystem so it works in the constrained CI sandbox
 * without additional dependencies.</p>
 */
public final class LongTestHarness
        implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    /** Slack on the open-FD count to absorb GC-driven jitter. */
    private static final int FD_LEAK_SLACK = 8;

    /** Storage namespace for per-test state attached to the JUnit context. */
    private static final ExtensionContext.Namespace NS =
            ExtensionContext.Namespace.create(LongTestHarness.class);

    private static final String KEY_WORK_DIR  = "workDir";
    private static final String KEY_START_NS  = "startNs";
    private static final String KEY_FD_BEFORE = "fdBefore";

    /** Returns the working directory for the currently-running test. */
    public static Path workDir(ExtensionContext context) {
        Path p = context.getStore(NS).get(KEY_WORK_DIR, Path.class);
        if (p == null) {
            throw new IllegalStateException(
                    "LongTestHarness work dir not initialised — is the test annotated @LongRenderTest?");
        }
        return p;
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        Path workDir = Files.createTempDirectory("daw-longtest-");
        ExtensionContext.Store store = context.getStore(NS);
        store.put(KEY_WORK_DIR,  workDir);
        store.put(KEY_START_NS,  System.nanoTime());
        store.put(KEY_FD_BEFORE, openFdCount());
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        ExtensionContext.Store store = context.getStore(NS);
        Path workDir   = store.remove(KEY_WORK_DIR,  Path.class);
        Long  startNs  = store.remove(KEY_START_NS,  Long.class);
        Integer before = store.remove(KEY_FD_BEFORE, Integer.class);

        // 1. Cleanup the temp working directory and verify it's gone.
        if (workDir != null) {
            deleteRecursively(workDir);
            if (Files.exists(workDir)) {
                throw new AssertionFailedError(
                        "LongTestHarness failed to clean up temp work dir: " + workDir);
            }
        }

        // 2. File-handle leak detection (best-effort; only on systems
        //    that expose /proc/self/fd). A small slack absorbs jitter
        //    from GC-managed resources.
        int after = openFdCount();
        if (before != null && before >= 0 && after >= 0
                && after - before > FD_LEAK_SLACK) {
            throw new AssertionFailedError(
                    "Long test leaked file handles: before=" + before
                            + " after=" + after
                            + " (slack=" + FD_LEAK_SLACK + ")");
        }

        // 3. Wall-clock budget enforcement (2× the documented budget).
        LongRenderTest spec = context.getRequiredTestClass()
                .getAnnotation(LongRenderTest.class);
        if (spec != null && startNs != null) {
            double elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000.0;
            double maxSec = 2.0 * spec.budgetSeconds();
            if (elapsedSec > maxSec) {
                throw new AssertionFailedError(String.format(
                        "Performance regression in %s: budget=%.2fs, "
                                + "max-allowed=%.2fs (2× budget), actual=%.2fs",
                        context.getDisplayName(),
                        spec.budgetSeconds(), maxSec, elapsedSec));
            }
        }
    }

    // ─── ParameterResolver: inject the per-test work dir ──────────────

    @Override
    public boolean supportsParameter(ParameterContext pc, ExtensionContext ec) {
        return pc.getParameter().getType() == Path.class;
    }

    @Override
    public Object resolveParameter(ParameterContext pc, ExtensionContext ec) {
        return workDir(ec);
    }

    // ─── helpers ──────────────────────────────────────────────────────

    /**
     * Returns the number of open file descriptors for the current JVM,
     * or {@code -1} if not measurable on this platform (non-Linux).
     */
    private static int openFdCount() {
        Path procFd = Path.of("/proc/self/fd");
        if (!Files.isDirectory(procFd)) {
            return -1;
        }
        try (Stream<Path> s = Files.list(procFd)) {
            return (int) s.count();
        } catch (IOException e) {
            return -1;
        }
    }

    /** Recursively delete a directory, suppressing per-entry errors. */
    static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort; the post-delete existence check below
                    // is the source of truth for the harness assertion.
                }
            });
        }
    }
}
