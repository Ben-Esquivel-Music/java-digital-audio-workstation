package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.app.ui.status.SessionStatusDiskScanner;
import com.benesquivelmusic.daw.core.audio.AudioFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 295 — the §1.8 disk-space scan ({@link SessionStatusDiskScanner}) reads
 * the file store off the FX thread on a background <strong>virtual</strong> thread
 * ({@code javafx-application-design} §11 "the FX thread is sacred — marshal
 * deliberately"; story 205 virtual threads) and publishes its result back through
 * the story-289 {@link FxDispatcher} keyed seam, never by writing the model
 * property directly off-thread.
 *
 * <p>The scanner reads its {@code projectDirectory} and {@code audioFormat}
 * suppliers <em>on the scan thread</em>, so the test captures {@code
 * Thread.currentThread()} inside the directory supplier and asserts the captured
 * thread is a virtual thread that is not the FX Application Thread. Per the
 * project's headless-test discipline, FX-thread identity is asserted by comparing
 * captured {@link Thread} objects on the test thread — never by calling
 * {@code Platform.isFxApplicationThread()} from inside a background/swallowed
 * runnable.</p>
 *
 * <p>The publish is then verified to have landed: the model's disk facts only
 * become visible after a {@link FxDispatcher#pulse()} drains the keyed work on the
 * FX thread (the scanner enqueues via {@code progress.setDisk(...)}, which routes
 * through the dispatcher).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class DiskScanOffFxThreadTest {

    @TempDir
    Path tempDir;

    @Test
    void scanRunsOnAVirtualNonFxThreadAndPublishesThroughTheDispatcher() throws Exception {
        FxDispatcher dispatcher = new FxDispatcher();
        ProjectOperationProgress progress = new ProjectOperationProgress(dispatcher);

        // Capture the FX Application Thread separately so we can prove the scan
        // ran elsewhere (comparing Thread objects on the test thread, not asserting
        // FX-thread identity from inside a swallowed runnable).
        AtomicReference<Thread> fxThread = new AtomicReference<>();
        runOnFx(() -> fxThread.set(Thread.currentThread()));
        assertThat(fxThread.get())
                .as("the FX Application Thread must have been captured")
                .isNotNull();

        // The scanner reads projectDirectory.get() ON the scan thread — capture it.
        AtomicReference<Thread> scanThread = new AtomicReference<>();
        SessionStatusDiskScanner scanner = new SessionStatusDiskScanner(
                progress,
                () -> {
                    scanThread.set(Thread.currentThread());
                    return tempDir;
                },
                () -> AudioFormat.CD_QUALITY);

        Thread t = scanner.scanNow();
        t.join(5000);

        assertThat(scanThread.get())
                .as("the scan must have run (the directory supplier was invoked)")
                .isNotNull();
        assertThat(scanThread.get().isVirtual())
                .as("the disk scan runs on a background virtual thread (story 205)")
                .isTrue();
        assertThat(scanThread.get())
                .as("the disk scan must NOT run on the FX Application Thread "
                        + "(javafx-application-design §11)")
                .isNotSameAs(fxThread.get());

        // The publish routes through the dispatcher's keyed work, which only
        // applies on a pulse() drained on the FX thread.
        runOnFx(dispatcher::pulse);

        assertThat(progress.getFreeDiskBytes())
                .as("tempDir is on a real file store, so usable bytes are non-negative")
                .isGreaterThanOrEqualTo(0L);
        assertThat(progress.getEstimatedRecordMinutes())
                .as("a non-negative free-space figure yields a positive capture estimate")
                .isGreaterThan(0.0);
    }

    @Test
    void estimatedRecordMinutesMatchesUncompressedByteRateAndHonoursUnknown() {
        // CD quality: 44100 Hz * 2 channels * 2 bytes/sample (16-bit) per second.
        long freeBytes = 10L * 1024 * 1024 * 1024; // 10 GiB
        double expected = freeBytes / (44_100.0 * 2 * 2 * 60.0);
        assertThat(SessionStatusDiskScanner.estimatedRecordMinutes(
                        freeBytes, AudioFormat.CD_QUALITY))
                .as("estimate = freeBytes / (sampleRate * channels * bytesPerSample * 60)")
                .isCloseTo(expected, within(1e-6));

        assertThat(SessionStatusDiskScanner.estimatedRecordMinutes(
                        -1L, AudioFormat.CD_QUALITY))
                .as("a negative (unknown) free-byte count yields UNKNOWN_MINUTES")
                .isEqualTo(ProjectOperationProgress.UNKNOWN_MINUTES);
    }

    // ── FX-thread helper (captures + rethrows swallowed assertion failures) ────

    private static void runOnFx(Runnable action) throws Exception {
        java.util.concurrent.atomic.AtomicReference<Throwable> error =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        javafx.application.Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new RuntimeException("FX action timed out");
        }
        if (error.get() != null) {
            throw new RuntimeException(error.get());
        }
    }
}
