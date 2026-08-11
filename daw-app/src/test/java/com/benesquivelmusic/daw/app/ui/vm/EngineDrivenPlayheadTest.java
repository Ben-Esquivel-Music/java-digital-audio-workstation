package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.EngineBinder;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;

import javafx.application.Platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 314 — the engine-driven playhead reaches the UI: a project bound
 * through the production {@link EngineBinder} advances its transport from
 * {@code processBlock}, and {@link TransportVM} observes the movement through
 * its continuous playhead channel after a single manual dispatcher drain
 * (the deterministic-pulse idiom of {@code TransportVMTest}).
 */
class EngineDrivenPlayheadTest {

    private static final long TIMEOUT_SECONDS = 5;

    // 8-frame buffer at 44.1 kHz / 120 BPM for block-granular position math.
    private static final double SAMPLE_RATE = 44_100.0;
    private static final int CHANNELS = 2;
    private static final int BUFFER_SIZE = 8;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, CHANNELS, 16, BUFFER_SIZE);
    private static final double TEMPO = 120.0;

    @BeforeAll
    static void initToolkit() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    @Test
    void engineDrivenBlocksAdvanceTheTransportAndTransportVmObservesTheMovement()
            throws InterruptedException {
        DawProject project = buildClipProject();
        Transport transport = project.getTransport();

        AudioEngine engine = new AudioEngine(FORMAT);
        new EngineBinder(engine).bind(project);

        FxDispatcher dispatcher = new FxDispatcher();
        TransportVM vm = new TransportVM(transport, dispatcher);
        try {
            transport.play();
            engine.start();

            float[][] input = new float[CHANNELS][BUFFER_SIZE];
            float[][] output = new float[CHANNELS][BUFFER_SIZE];
            for (int block = 0; block < 3; block++) {
                engine.processBlock(input, output, BUFFER_SIZE);
            }
            assertThat(transport.getPositionInBeats())
                    .as("engine-driven advancePosition moved the transport").isGreaterThan(0.0);

            runOnFx(dispatcher::pulse); // single manual drain on the FX thread
            assertThat(vm.getPlayhead())
                    .as("TransportVM observes the engine-driven movement").isGreaterThan(0.0);
            assertThat(vm.getPlayhead())
                    .as("the coalesced drain lands on the latest transport position")
                    .isEqualTo(transport.getPositionInBeats());
        } finally {
            vm.dispose();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** One audio track carrying a short constant-value clip at beat 0. */
    private static DawProject buildClipProject() {
        DawProject project = new DawProject("Playhead", FORMAT);
        project.getTransport().setTempo(TEMPO);
        Track track = project.createAudioTrack("Lead");
        AudioClip clip = new AudioClip("Clip", 0.0, 1.0, null);
        float[][] data = new float[CHANNELS][(int) (SAMPLE_RATE * 60.0 / TEMPO)];
        for (int ch = 0; ch < CHANNELS; ch++) {
            java.util.Arrays.fill(data[ch], 0.25f);
        }
        clip.setAudioData(data);
        track.addClip(clip);
        return project;
    }

    /** Runs {@code work} on the FX thread and blocks until it completes (capture + rethrow). */
    private static void runOnFx(Runnable work) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        if (thrown.get() instanceof RuntimeException re) {
            throw re;
        }
        if (thrown.get() instanceof Error e) {
            throw e;
        }
    }
}
