package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.performance.DegradationPolicy;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackCpuBudget;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackPerformanceEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 338, ruling E204: every marking is measured against the block time of
 * the stream that rendered the block. A render drive built for a format of
 * another block time detaches the installed CPU budget enforcer before it
 * renders, and the render thread measures no block with an enforcer of
 * another block time installed on a running stream, so an enforcer built for
 * 128 frames never measures a block of 1024; an enforcer built for the
 * stream's format, installed afterwards as the window's reinstall does,
 * measures it; a restart at the same format keeps the installed enforcer.
 * The stream is a {@link MockAudioBackend}'s, driven by the engine's own
 * render drive. Each enforcer's clock counts its reads, one per measurement,
 * so what is measured is told by counts, never by a measured duration. A
 * track whose insert spins for at least a quarter of the block reads, in a
 * block of 1024 frames measured against the block time of 128, at least
 * twice that block time, over every ceiling here, so an enforcer measuring
 * a block of the other format marks it at that block and the assertions that
 * nothing was published or marked can fail; the spin is a lower bound only,
 * and no assertion bounds a measured value from above.
 */
class AudioEngineCpuBudgetEnforcerFormatTest {

    private static final double SAMPLE_RATE = 48_000.0;
    private static final AudioFormat SMALL_BLOCKS = new AudioFormat(SAMPLE_RATE, 2, 16, 128);
    private static final AudioFormat LARGE_BLOCKS = new AudioFormat(SAMPLE_RATE, 2, 16, 1024);
    private static final TrackCpuBudget TRACK_BUDGET =
            new TrackCpuBudget(1.0, new DegradationPolicy.BypassExpensive());
    private static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(20);

    private final AudioEngine engine = new AudioEngine(SMALL_BLOCKS);
    private final MockAudioBackend backend = new MockAudioBackend();
    private final Transport transport = new Transport();
    private final Track track = new Track("Lead Vox", TrackType.AUDIO);

    @AfterEach
    void shutTheEngineDown() {
        engine.stopAudioOutput();
        engine.shutdown();
    }

    /**
     * The costly track joins the graph after the old format's stream stopped,
     * so the old enforcer never measured it: only a measurement of a block of
     * the new format could register it there and mark it.
     */
    @Test
    void anEnforcerOfAnotherBlockTimeMeasuresNoBlockOfTheNewFormatAndTheNewFormatsEnforcerMeasuresAgainstItsBlockTime() {
        Mixer mixer = bindAPlayingTrack(channelFor(track));
        Track costly = new Track("Costly Synth", TrackType.AUDIO);
        AtomicLong oldMeasurements = new AtomicLong();
        TrackCpuBudgetEnforcer old = new TrackCpuBudgetEnforcer(SAMPLE_RATE, SMALL_BLOCKS.bufferSize(), 1.0,
                oldMeasurements::incrementAndGet);
        AtomicLong newMeasurements = new AtomicLong();
        TrackCpuBudgetEnforcer reinstalled = new TrackCpuBudgetEnforcer(SAMPLE_RATE, LARGE_BLOCKS.bufferSize(), 1.0,
                newMeasurements::incrementAndGet);
        try {
            engine.setCpuBudgetEnforcer(old);
            engine.startAudioOutput();
            await(() -> oldMeasurements.get() >= 8L, "the old enforcer measured blocks of its own format");

            engine.stopAudioOutput();
            engine.stop();
            List<Long> countsAtTheStop = List.of(engine.blocksBegun(), engine.meteringTapBus().blockIndex());
            Markings afterTheChange = new Markings();
            old.performanceEvents().subscribe(afterTheChange);
            mixer.addChannel(costlyChannelFor(costly));
            engine.setGraph(transport, mixer, List.of(track, costly));
            engine.setFormat(LARGE_BLOCKS);
            long measuredBeforeTheChange = oldMeasurements.get();
            long blocksBeforeTheChange = engine.meteringTapBus().blockIndex();
            engine.startAudioOutput();
            await(() -> engine.meteringTapBus().blockIndex() - blocksBeforeTheChange >= 12L,
                    "twelve blocks of the new format were rendered");
            TrackCpuBudgetEnforcer installedAfterTheChange = engine.getCpuBudgetEnforcer();
            long measuredBeforeTheReinstall = oldMeasurements.get();

            engine.setCpuBudgetEnforcer(reinstalled);
            await(() -> newMeasurements.get() >= 8L, "the reinstalled enforcer measured blocks of the new format");
            engine.stopAudioOutput();
            old.close();
            afterTheChange.awaitCompletion();

            assertThat(countsAtTheStop.getFirst()).as("premise: no block of the old format is in flight")
                    .isEqualTo(countsAtTheStop.getLast());
            assertThat(measuredBeforeTheReinstall).as("the old enforcer measured no block of the new format")
                    .isEqualTo(measuredBeforeTheChange);
            assertThat(installedAfterTheChange).as("the drive built for the new format detached it").isNull();
            assertThat(oldMeasurements.get()).as("nor after the reinstall").isEqualTo(measuredBeforeTheChange);
            assertThat(afterTheChange.events).as("the old enforcer published nothing for a block of the new format")
                    .isEmpty();
            assertThat(old.isDegraded(costly.getId())).as("and marked nothing after the change").isFalse();
            assertThat(reinstalled.getBlockBudgetNanos())
                    .as("the reinstalled enforcer measures against the new format's block time")
                    .isEqualTo(TrackCpuBudgetEnforcer.blockBudgetNanos(SAMPLE_RATE, LARGE_BLOCKS.bufferSize()))
                    .isNotEqualTo(old.getBlockBudgetNanos());
        } finally {
            old.close();
            reinstalled.close();
        }
    }

    /**
     * An enforcer of another block time installed on a running stream measures
     * no block of it: its clock is never read and it publishes nothing, though
     * the track costs at least twice that enforcer's block time in each block;
     * one built for the stream's format, installed the same way, is measured.
     */
    @Test
    void anEnforcerOfAnotherBlockTimeInstalledOnARunningStreamMeasuresNoBlock() {
        bindAPlayingTrack(costlyChannelFor(track));
        engine.setFormat(LARGE_BLOCKS);
        AtomicLong mismatchedMeasurements = new AtomicLong();
        TrackCpuBudgetEnforcer mismatched = new TrackCpuBudgetEnforcer(SAMPLE_RATE, SMALL_BLOCKS.bufferSize(), 1.0,
                mismatchedMeasurements::incrementAndGet);
        Markings published = new Markings();
        mismatched.performanceEvents().subscribe(published);
        AtomicLong matchingMeasurements = new AtomicLong();
        TrackCpuBudgetEnforcer matching = new TrackCpuBudgetEnforcer(SAMPLE_RATE, LARGE_BLOCKS.bufferSize(), 1.0,
                matchingMeasurements::incrementAndGet);
        try {
            engine.startAudioOutput();
            engine.setCpuBudgetEnforcer(mismatched);
            long blocksAtTheInstall = engine.meteringTapBus().blockIndex();
            await(() -> engine.meteringTapBus().blockIndex() - blocksAtTheInstall >= 3L,
                    "three blocks ended after the install");
            engine.setCpuBudgetEnforcer(matching);
            await(() -> matchingMeasurements.get() > 0L, "the enforcer built for the stream's format measured a block");
            engine.stopAudioOutput();
            mismatched.close();
            published.awaitCompletion();

            assertThat(mismatchedMeasurements).as("the enforcer of another block time measured no block (E204)")
                    .hasValue(0L);
            assertThat(published.events).as("and published nothing").isEmpty();
        } finally {
            mismatched.close();
            matching.close();
        }
    }

    @Test
    void aRestartAtTheSameFormatKeepsTheInstalledEnforcer() {
        bindAPlayingTrack(channelFor(track));
        AtomicLong measurements = new AtomicLong();
        TrackCpuBudgetEnforcer installed = new TrackCpuBudgetEnforcer(SAMPLE_RATE, SMALL_BLOCKS.bufferSize(), 1.0,
                measurements::incrementAndGet);
        try {
            engine.setCpuBudgetEnforcer(installed);
            engine.startAudioOutput();
            await(() -> measurements.get() >= 4L, "the enforcer measured blocks");

            engine.stopAudioOutput();
            engine.stop();
            engine.setFormat(SMALL_BLOCKS);
            long measuredBeforeTheRestart = measurements.get();
            engine.startAudioOutput();
            await(() -> measurements.get() - measuredBeforeTheRestart >= 4L,
                    "the enforcer measured blocks of the restarted stream");

            assertThat(engine.getCpuBudgetEnforcer()).isSameAs(installed);
        } finally {
            installed.close();
        }
    }

    private Mixer bindAPlayingTrack(MixerChannel channel) {
        engine.setStreamingProvision(new StreamingProvision(backend.name(),
                List.of(new BackendStreamRung(backend, DeviceId.defaultFor(backend.name())))));
        Mixer mixer = new Mixer();
        mixer.addChannel(channel);
        transport.play();
        engine.setGraph(transport, mixer, List.of(track));
        return mixer;
    }

    private static MixerChannel channelFor(Track track) {
        MixerChannel channel = new MixerChannel(track.getName());
        channel.setCpuBudget(TRACK_BUDGET);
        return channel;
    }

    /**
     * A channel whose insert spins for a quarter of the block. It is the only
     * channel with an insert, so the scheduler's parallel pass, which needs
     * two, leaves the spin inside the track's measured segment.
     */
    private static MixerChannel costlyChannelFor(Track track) {
        MixerChannel channel = channelFor(track);
        channel.addInsert(new InsertSlot("A quarter of the block", new SpinsForAQuarterOfTheBlock()));
        return channel;
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + WAIT_NANOS;
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() - deadline < 0L).as("%s, within 20 s", what).isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
    }

    /** Every event an enforcer delivered, and its completion. */
    private static final class Markings implements Flow.Subscriber<TrackPerformanceEvent> {
        final List<TrackPerformanceEvent> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch completed = new CountDownLatch(1);

        void awaitCompletion() {
            try {
                assertThat(completed.await(20, TimeUnit.SECONDS)).as("the enforcer's events completed").isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(TrackPerformanceEvent event) {
            events.add(event);
        }

        @Override
        public void onError(Throwable throwable) {
            completed.countDown();
        }

        @Override
        public void onComplete() {
            completed.countDown();
        }
    }

    /** Passes the signal through and spins for at least a quarter of the block. */
    private static final class SpinsForAQuarterOfTheBlock implements AudioProcessor {
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            long cost = (long) Math.ceil(0.25 * numFrames / SAMPLE_RATE * 1_000_000_000.0);
            long start = System.nanoTime();
            for (int channel = 0; channel < Math.min(inputBuffer.length, outputBuffer.length); channel++) {
                System.arraycopy(inputBuffer[channel], 0, outputBuffer[channel], 0, numFrames);
            }
            while (System.nanoTime() - start < cost) {
                Thread.onSpinWait();
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
