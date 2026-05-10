package com.benesquivelmusic.daw.app.longtests;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/**
 * Tests-of-the-harness for {@link LongTestHarness} (story 209
 * acceptance criterion: <em>"Tests-of-the-harness verify that
 * long-test setup/teardown cleans up temp directories and does not
 * leak file handles"</em>).
 *
 * <p>Rather than fabricate a {@code FakeExtensionContext} (which
 * shifts with every JUnit Jupiter release), this self-test drives the
 * real JUnit Platform launcher against tiny synthetic test classes
 * that are themselves wired to {@link LongTestHarness}. We then
 * inspect the {@link TestExecutionSummary} to assert harness
 * behaviour — exactly how a CI pipeline would observe it.</p>
 */
final class LongTestHarnessSelfTest {

    /** Captures the per-test work dir so the outer test can assert on it. */
    private static final AtomicReference<Path> CAPTURED_WORK_DIR = new AtomicReference<>();

    /** Holds intentionally-leaked streams across the leak-detection probe. */
    private static final List<InputStream> LEAKED_STREAMS = new ArrayList<>();

    @Test
    void cleansUpTempDirectoryAfterTestCompletes() {
        CAPTURED_WORK_DIR.set(null);
        TestExecutionSummary summary = runProbe(CleanProbe.class);
        assertThat(summary.getFailures())
                .as("clean probe should pass")
                .isEmpty();

        Path workDir = CAPTURED_WORK_DIR.get();
        assertThat(workDir)
                .as("probe should have captured a work dir")
                .isNotNull();
        assertThat(Files.exists(workDir))
                .as("temp work dir must be cleaned up after the test")
                .isFalse();
    }

    @Test
    void detectsFileHandleLeak() {
        // The harness only watches FDs on platforms exposing /proc/self/fd
        // (Linux). On macOS / Windows the check no-ops; skip to keep the
        // suite cross-platform-clean.
        if (!Files.isDirectory(Path.of("/proc/self/fd"))) {
            return;
        }

        LEAKED_STREAMS.clear();
        try {
            TestExecutionSummary summary = runProbe(LeakingProbe.class);
            assertThat(summary.getFailures())
                    .as("leak probe should fail with the harness's leak message")
                    .hasSize(1);
            assertThat(summary.getFailures().get(0).getException())
                    .hasMessageContaining("leaked file handles");
        } finally {
            for (InputStream s : LEAKED_STREAMS) {
                try { s.close(); } catch (IOException ignored) { }
            }
        }
    }

    // ─── synthetic probe test classes (driven via the platform) ──────

    /**
     * Probe that opens nothing extra and lets the harness clean up.
     * Captures the work dir so the outer self-test can assert it was
     * deleted.
     */
    @ExtendWith(LongTestHarness.class)
    public static class CleanProbe {
        @Test
        void capturesWorkDir(Path workDir) throws Exception {
            CAPTURED_WORK_DIR.set(workDir);
            // touch the dir so deletion has something to recurse over
            Files.writeString(workDir.resolve("scratch.txt"), "hi");
        }
    }

    /**
     * Probe that opens many files and intentionally never closes them
     * — the harness's afterEach must surface this as a leak failure.
     */
    @ExtendWith(LongTestHarness.class)
    public static class LeakingProbe {
        @Test
        void leaksFileHandles(Path workDir) throws Exception {
            for (int i = 0; i < 32; i++) {
                Path p = workDir.resolve("leak-" + i + ".txt");
                Files.writeString(p, "x");
                LEAKED_STREAMS.add(Files.newInputStream(p));
            }
        }
    }

    // ─── helper: run a probe class via the JUnit Platform launcher ───

    private static TestExecutionSummary runProbe(Class<?> probeClass) {
        LauncherDiscoveryRequest req = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClass(probeClass))
                .build();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        try (var session = LauncherFactory.openSession()) {
            Launcher launcher = session.getLauncher();
            launcher.registerTestExecutionListeners(listener);
            launcher.execute(req);
        }
        return listener.getSummary();
    }
}
