package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.ChannelVM;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 contract (probe-derived, permanent; fix-round 1 probe "d") — B2
 * on the RECORDED-thread branch of
 * {@code FxDispatcher.isFxThread()}: two channels ramped to odd values from a
 * virtual-thread "audio thread" against a dispatcher that has been started on
 * the FX thread (so the compare is the volatile field, not the toolkit
 * fallback the un-started {@code ChannelVmRtPublishTest} dispatcher uses).
 *
 * <ul>
 *   <li>No {@code Platform.runLater} for VOLUME / PAN: after the ramp an FX
 *       barrier runs and the properties are untouched; the pulse then
 *       converges them on the authority. The dispatcher is started then
 *       disposed BEFORE the VMs exist (the record survives dispose — pinned by
 *       {@code FxDispatcherFxThreadTest}) so no self-pulse can mask a
 *       {@code runLater}.</li>
 *   <li>Contrast: a MUTE edge from the same worker IS the edge-triggered
 *       {@code runLater} — visible after the same barrier — so the barrier
 *       technique discriminates.</li>
 *   <li>A live started dispatcher (timer running) with the same ramp still
 *       converges and never reports the worker as the FX thread.</li>
 * </ul>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class Story322RecordedFxThreadPublishContractTest {

    private static final int STEPS = 10_000;

    @Test
    void rampsFromAWorkerAgainstARecordedFxThreadPostNoRunLaterAndConvergeOnThePulse() throws Exception {
        FxDispatcher dispatcher = new FxDispatcher();
        onFx(() -> {
            dispatcher.start();
            dispatcher.dispose();
        });
        assertThat(dispatcher.isFxThread()).as("test thread is not the recorded FX thread").isFalse();

        MixerChannel kick = new MixerChannel("Kick");
        MixerChannel bass = new MixerChannel("Bass");
        ChannelVM kickVm = new ChannelVM(kick, dispatcher);
        ChannelVM bassVm = new ChannelVM(bass, dispatcher);
        try {
            AtomicBoolean workerLookedLikeFx = new AtomicBoolean(false);
            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            Thread worker = Thread.ofVirtual().name("probe-audio-thread").unstarted(() -> {
                try {
                    for (int i = 0; i <= STEPS; i++) {
                        double t = i / (double) STEPS;
                        kick.setVolume(0.37 * t);
                        kick.setPan(-0.62 * t);
                        bass.setVolume(0.81 * t);
                        bass.setPan(0.81 * t);
                        if (dispatcher.isFxThread()) {
                            workerLookedLikeFx.set(true);
                        }
                    }
                } catch (Throwable t) {
                    workerFailure.set(t);
                }
            });
            worker.start();
            worker.join(TimeUnit.SECONDS.toMillis(20));
            assertThat(worker.isAlive()).as("the ramp finished").isFalse();
            assertThat(workerFailure.get()).as("no failure on the audio thread").isNull();
            assertThat(workerLookedLikeFx).isFalse();

            flushFx();
            assertThat(kickVm.getVolume()).as("no runLater posted for VOLUME (kick)").isEqualTo(1.0);
            assertThat(kickVm.getPan()).as("no runLater posted for PAN (kick)").isEqualTo(0.0);
            assertThat(bassVm.getVolume()).as("no runLater posted for VOLUME (bass)").isEqualTo(1.0);
            assertThat(bassVm.getPan()).as("no runLater posted for PAN (bass)").isEqualTo(0.0);

            onFx(dispatcher::pulse);
            assertThat(kickVm.getVolume()).as("kick converged on the pulse").isEqualTo(0.37);
            assertThat(kickVm.getPan()).isEqualTo(-0.62);
            assertThat(bassVm.getVolume()).as("bass converged on the pulse").isEqualTo(0.81);
            assertThat(bassVm.getPan()).isEqualTo(0.81);

            // Contrast (non-vacuity of the barrier proof): a MUTE edge is the
            // documented edge-triggered runLater and IS visible after a barrier.
            Thread edge = Thread.ofVirtual().name("probe-audio-thread-edge").unstarted(() -> kick.setMuted(true));
            edge.start();
            edge.join(TimeUnit.SECONDS.toMillis(5));
            flushFx();
            assertThat(kickVm.isMuted()).as("the discrete arm marshals through runLater").isTrue();
        } finally {
            kickVm.dispose();
            bassVm.dispose();
        }
    }

    @Test
    void aLiveStartedDispatcherConvergesTwoRampedChannelsAndNeverMistakesTheWorkerForTheFxThread() throws Exception {
        FxDispatcher dispatcher = new FxDispatcher();
        onFx(dispatcher::start);
        MixerChannel kick = new MixerChannel("Kick");
        MixerChannel bass = new MixerChannel("Bass");
        ChannelVM kickVm = new ChannelVM(kick, dispatcher);
        ChannelVM bassVm = new ChannelVM(bass, dispatcher);
        try {
            AtomicBoolean workerLookedLikeFx = new AtomicBoolean(false);
            Thread worker = Thread.ofVirtual().name("probe-audio-thread-live").unstarted(() -> {
                for (int i = 0; i <= STEPS; i++) {
                    double t = i / (double) STEPS;
                    kick.setVolume(0.37 * t);
                    kick.setPan(-0.62 * t);
                    bass.setVolume(0.81 * t);
                    bass.setPan(0.81 * t);
                    if (dispatcher.isFxThread()) {
                        workerLookedLikeFx.set(true);
                    }
                }
            });
            worker.start();
            worker.join(TimeUnit.SECONDS.toMillis(20));
            assertThat(worker.isAlive()).isFalse();
            assertThat(workerLookedLikeFx).isFalse();
            assertThat(onFx(dispatcher::isFxThread)).as("FX thread recognised while a worker publishes").isTrue();

            onFx(dispatcher::pulse);
            assertThat(kickVm.getVolume()).isEqualTo(0.37);
            assertThat(kickVm.getPan()).isEqualTo(-0.62);
            assertThat(bassVm.getVolume()).isEqualTo(0.81);
            assertThat(bassVm.getPan()).isEqualTo(0.81);
        } finally {
            kickVm.dispose();
            bassVm.dispose();
            onFx(dispatcher::dispose);
        }
    }

    private static void flushFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private static void onFx(Runnable work) throws Exception {
        onFx(() -> {
            work.run();
            return null;
        });
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> work) throws Exception {
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
        assertThat(latch.await(10, TimeUnit.SECONDS)).as("FX work completed").isTrue();
        Throwable t = thrown.get();
        if (t instanceof Error e) {
            throw e;
        }
        if (t instanceof Exception e) {
            throw e;
        }
        return result.get();
    }
}
