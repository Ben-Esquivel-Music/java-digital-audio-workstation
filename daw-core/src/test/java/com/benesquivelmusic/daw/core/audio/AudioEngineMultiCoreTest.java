package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.audio.health.AudioHealthRecord;
import com.benesquivelmusic.daw.core.mastering.MasteringChain;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Verifies that {@link AudioEngine} wires the multi-core graph scheduler
 * (story 125) into the live render path.
 */
@Tag("slow")
class AudioEngineMultiCoreTest {

    @Test
    void defaultsCreatePoolAndSchedulerOnStartAndAttachToMixer() {
        AudioEngineSettings settings = new AudioEngineSettings(
                4, AudioGraphScheduler.DEFAULT_MIN_PARALLEL_BLOCK_SIZE);
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY, settings);
        Mixer mixer = new Mixer();
        engine.setGraph(null, mixer, null);

        // Before start: no scheduler/pool wired yet.
        assertThat(engine.getWorkerPool()).isNull();
        assertThat(engine.getGraphScheduler()).isNull();
        assertThat(engine.getActiveThreadCount()).isZero();
        assertThat(engine.getWorkerPoolSize()).isEqualTo(4);

        engine.start();
        try {
            assertThat(engine.getWorkerPool()).isNotNull();
            assertThat(engine.getWorkerPool().size()).isEqualTo(4);
            assertThat(engine.getGraphScheduler()).isNotNull();
            // Mixer.mixDown will dispatch through this scheduler.
            assertThat(mixer.getGraphScheduler()).isSameAs(engine.getGraphScheduler());
            // No block has been processed yet.
            assertThat(engine.getActiveThreadCount()).isZero();
        } finally {
            engine.stop();
        }

        // After stop: pool closed and detached from the mixer.
        assertThat(engine.getWorkerPool()).isNull();
        assertThat(engine.getGraphScheduler()).isNull();
        assertThat(mixer.getGraphScheduler()).isNull();
    }

    @Test
    void poolSizeOneSkipsSchedulerCreation() {
        AudioEngine engine = new AudioEngine(
                AudioFormat.CD_QUALITY, new AudioEngineSettings(1, 64));
        Mixer mixer = new Mixer();
        engine.setGraph(null, mixer, null);

        engine.start();
        try {
            // pool size 1 => single-threaded; no pool/scheduler created.
            assertThat(engine.getWorkerPool()).isNull();
            assertThat(engine.getGraphScheduler()).isNull();
            assertThat(mixer.getGraphScheduler()).isNull();
            assertThat(engine.getActiveThreadCount()).isZero();
        } finally {
            engine.stop();
        }
    }

    @Test
    void mixerHotSwapViaSetGraphReinstallsSchedulerOnRunningEngine() {
        AudioEngine engine = new AudioEngine(
                AudioFormat.CD_QUALITY, new AudioEngineSettings(2, 64));
        engine.start();
        try {
            // Hot-swap mixer while running should propagate the scheduler.
            Mixer m1 = new Mixer();
            engine.setGraph(engine.getTransport(), m1, engine.getTracks());
            assertThat(m1.getGraphScheduler()).isSameAs(engine.getGraphScheduler());

            Mixer m2 = new Mixer();
            engine.setGraph(engine.getTransport(), m2, engine.getTracks());
            assertThat(m2.getGraphScheduler()).isSameAs(engine.getGraphScheduler());
            // The previous mixer must have its scheduler detached so stale
            // references do not attempt parallel dispatch after pool close.
            assertThat(m1.getGraphScheduler()).isNull();
        } finally {
            engine.stop();
        }
    }

    @Test
    void engineSettingsCannotChangeWhileRunning() {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.start();
        try {
            assertThatThrownBy(() ->
                    engine.setEngineSettings(new AudioEngineSettings(2, 64)))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            engine.stop();
        }
    }

    @Test
    void defaultConstructorUsesDefaultEngineSettings() {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        assertThat(engine.getEngineSettings()).isEqualTo(AudioEngineSettings.defaults());
    }

    /**
     * Story 337, ruling E75, on the production wiring: an offline render of a
     * mixer the running engine's scheduler is installed on runs every insert
     * on the rendering thread — never on the engine's worker pool — so its
     * failures never enter the engine's health record. Each probe waits on a
     * two-party barrier, which only a parallel dispatch can trip, and the
     * second probe throws: on this thread the throw reaches the caller of the
     * render, while on a pool worker it would be counted as the engine's
     * {@code audio.worker}.
     */
    @Test
    void anOfflineRenderRunsEveryInsertOnItsOwnThreadAndRecordsNothingInTheEnginesRecord() {
        int block = 256;
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY, new AudioEngineSettings(2, 64));
        Mixer mixer = new Mixer();
        CyclicBarrier bothProbes = new CyclicBarrier(2);
        List<Thread> ranOn = new CopyOnWriteArrayList<>();
        ThreadProbe first = new ThreadProbe(bothProbes, ranOn, false);
        ThreadProbe second = new ThreadProbe(bothProbes, ranOn, true);
        for (ThreadProbe probe : List.of(first, second)) {
            MixerChannel channel = new MixerChannel("Probe " + mixer.getChannels().size());
            channel.addInsert(new InsertSlot("Probe", probe));
            mixer.addChannel(channel);
        }
        mixer.prepareForPlayback(2, block);
        engine.setGraph(null, mixer, null);
        engine.start();
        try {
            assertThat(mixer.getGraphScheduler()).as("the engine's scheduler is installed on the mixer")
                    .isSameAs(engine.getGraphScheduler());
            RenderPipeline offline = new RenderPipeline(new AudioFormat(44_100.0, 2, 16, block), 2, block,
                    new AudioHealthRecord());
            Transport transport = new Transport();
            transport.play();

            Throwable thrown = catchThrowable(() -> offline.renderOffline(transport, mixer, List.of(), null,
                    new MasteringChain(), new float[2][block], block, block));

            assertThat(ranOn).as("every insert of the offline block ran on the rendering thread")
                    .hasSize(2)
                    .containsOnly(Thread.currentThread());
            assertThat(engine.audioHealth().totals())
                    .as("nothing of the offline render entered the engine's record")
                    .containsOnly(0L);
            assertThat(thrown).as("the failing insert's throw reached the render's caller")
                    .isSameAs(second.failure);
        } finally {
            engine.stop();
        }
    }

    /**
     * An insert that notes the thread it runs on, waits for the other probe
     * on a two-party barrier (tripped only when both run at once), then
     * passes through — or throws, if {@code throwing}.
     */
    private static final class ThreadProbe implements AudioProcessor {

        /** Long enough for a parked pool worker to wake and meet the coordinator. */
        private static final long BARRIER_WAIT_MILLIS = 2_000L;

        final IllegalStateException failure = new IllegalStateException("probe insert failed");
        private final CyclicBarrier barrier;
        private final List<Thread> ranOn;
        private final boolean throwing;

        ThreadProbe(CyclicBarrier barrier, List<Thread> ranOn, boolean throwing) {
            this.barrier = barrier;
            this.ranOn = ranOn;
            this.throwing = throwing;
        }

        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            ranOn.add(Thread.currentThread());
            try {
                barrier.await(BARRIER_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (BrokenBarrierException | TimeoutException alone) {
                // Run one after the other: no partner arrives.
            }
            if (throwing) {
                throw failure;
            }
            for (int ch = 0; ch < Math.min(inputBuffer.length, outputBuffer.length); ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
        }

        @Override
        public void reset() {
        }

        @Override
        public int getInputChannelCount() {
            return 2;
        }

        @Override
        public int getOutputChannelCount() {
            return 2;
        }
    }
}
