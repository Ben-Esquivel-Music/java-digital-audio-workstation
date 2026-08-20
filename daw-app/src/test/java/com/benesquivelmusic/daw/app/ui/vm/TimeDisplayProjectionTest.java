package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.transport.Transport;

import javafx.application.Platform;
import javafx.scene.control.Label;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 315 — the time display is the beats→time projection of
 * {@link TransportVM#playheadProperty()} through {@link TransportVM#tempoProperty()}
 * ({@link TransportControlBinder#bindTimeDisplay}) — one clock, projected
 * (Audio Engine Wiring Design Book §2.3). It shows <em>transport position</em>,
 * never wall-clock elapsed time: a seek to bar 17 reads as bar-17 time whether
 * or not any wall time has passed, and a tempo change re-projects the same
 * beat position ({@code TimeTickerAnimator}, the wall-clock stopwatch this
 * replaces, is deleted — see {@code TransportTimeSourceRetiredTest}).
 */
class TimeDisplayProjectionTest {

    private static final long TIMEOUT_SECONDS = 5;

    @BeforeAll
    static void initToolkit() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    @Test
    void timeDisplayShowsTheBeatsToTimeProjectionOfTheSeekTarget() throws Exception {
        Transport transport = new Transport();
        transport.setTempo(120.0);
        FxDispatcher dispatcher = new FxDispatcher();
        TransportVM vm = new TransportVM(transport, dispatcher);
        Label timeDisplay = computeOnFx(Label::new);
        TransportControlBinder binder = new TransportControlBinder(vm, command -> { });
        try {
            computeOnFx(() -> { binder.bindTimeDisplay(timeDisplay); return null; });
            assertThat(computeOnFx(timeDisplay::getText))
                    .as("at position 0 the projection is all zeros")
                    .isEqualTo("00:00:00.0");

            // Seek to bar 17 (4/4 → 16 bars × 4 beats = beat 64). At 120 BPM
            // that is 32 s of musical time — NOT the ~0 s of wall time that
            // has elapsed since this test started (the story's motivating lie).
            transport.setPositionInBeats(64.0);
            runOnFx(dispatcher::pulse); // the continuous playhead channel drains per frame
            assertThat(computeOnFx(timeDisplay::getText))
                    .as("seek to bar 17 → the display shows the bar-17 beats→time projection")
                    .isEqualTo("00:00:32.0");

            // A tempo change re-projects the SAME beat position.
            transport.setTempo(240.0);
            flushFx();
            assertThat(computeOnFx(timeDisplay::getText))
                    .as("tempo change re-projects the same beat position (64 beats @ 240 BPM = 16 s)")
                    .isEqualTo("00:00:16.0");

            // The playing path is the same clock: the engine's advance lands
            // in the display after the per-frame drain.
            transport.setTempo(120.0);
            transport.play();
            transport.advancePosition(1.0); // beat 65 → 32.5 s
            runOnFx(dispatcher::pulse);
            flushFx();
            assertThat(computeOnFx(timeDisplay::getText))
                    .as("engine-driven advance projects through the same binding")
                    .isEqualTo("00:00:32.5");

            // A bound label is the single writer's surface — the binder must
            // release it on dispose so the next epoch can bind it again.
            computeOnFx(() -> { binder.dispose(); return null; });
            assertThat(computeOnFx(() -> timeDisplay.textProperty().isBound()))
                    .as("dispose() unbinds the time display").isFalse();
        } finally {
            computeOnFx(() -> { binder.dispose(); return null; });
            vm.dispose();
        }
    }

    @Test
    void anAccumulatedPlayheadDoesNotRenderATenthLow() throws Exception {
        // Story 315 review — TransportVM.playhead is produced by repeated RT
        // accumulation of deltaBeats, so a position that is mathematically an
        // exact tenth of a second almost never is in binary. Truncating the
        // floating-point product then renders a whole tenth low.
        //
        // Reproduction: 150 blocks of 256 frames at 48 kHz and 120 BPM. Exactly
        // 1.6 beats = 0.8 s; accumulated it is 1.5999999999999983 beats =
        // 0.7999999999999992 s, which truncates to "00:00:00.7".
        // (The review's own example — 0.6 beats at 60 BPM — does NOT reproduce:
        // the double rounding lands it on exactly 6.0 tenths.)
        Transport transport = new Transport();
        transport.setTempo(120.0);
        FxDispatcher dispatcher = new FxDispatcher();
        TransportVM vm = new TransportVM(transport, dispatcher);
        Label timeDisplay = computeOnFx(Label::new);
        TransportControlBinder binder = new TransportControlBinder(vm, command -> { });
        try {
            computeOnFx(() -> { binder.bindTimeDisplay(timeDisplay); return null; });

            double deltaBeats = (256.0 / 48_000.0) * (120.0 / 60.0);
            transport.play();
            for (int block = 0; block < 150; block++) {
                transport.advancePosition(deltaBeats);
            }
            assertThat(transport.getPositionInBeats())
                    .as("the accumulated playhead really is a few ulps below 1.6 beats")
                    .isNotEqualTo(1.6)
                    .isCloseTo(1.6, org.assertj.core.data.Offset.offset(1e-12));

            runOnFx(dispatcher::pulse);
            flushFx();
            assertThat(computeOnFx(timeDisplay::getText))
                    .as("1.6 beats @ 120 BPM is 0.8 s — accumulation must not cost a tenth")
                    .isEqualTo("00:00:00.8");

            // The epsilon must never promote a genuinely lower reading: a
            // position a comfortable distance below the boundary still reads
            // as the lower tenth.
            transport.setPositionInBeats(0.74999 * 2.0); // 0.74999 s
            runOnFx(dispatcher::pulse);
            flushFx();
            assertThat(computeOnFx(timeDisplay::getText))
                    .as("truncation semantics are otherwise unchanged")
                    .isEqualTo("00:00:00.7");
        } finally {
            computeOnFx(() -> { binder.dispose(); return null; });
            vm.dispose();
        }
    }

    // ── FX helpers (mirror TransportVMTest) ──────────────────────────────────

    private static void flushFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    private static void runOnFx(Runnable work) throws Exception {
        computeOnFx(() -> { work.run(); return null; });
    }

    private static <T> T computeOnFx(Callable<T> work) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(work.call());
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        switch (thrown.get()) {
            case null -> { }
            case RuntimeException re -> throw re;
            case Error e -> throw e;
            case Throwable t -> throw new AssertionError("FX work threw", t);
        }
        return result.get();
    }
}
