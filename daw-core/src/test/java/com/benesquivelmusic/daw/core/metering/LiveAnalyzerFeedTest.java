package com.benesquivelmusic.daw.core.metering;

import com.benesquivelmusic.daw.core.analysis.AnalyzerProcessor;
import com.benesquivelmusic.daw.core.analysis.AnalyzerSnapshot;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LiveAnalyzerFeedTest {
    private static final int RATE = 48_000;

    @Test
    void spectrumHopAccumulatesRenderBlocksOnAnalysisThreadAndFindsSineBin() throws Exception {
        var snapshots = throughLane(AnalyzerProcessor.Kind.SPECTRUM, sine(4096, 375));
        var data = ((AnalyzerSnapshot.Spectrum) snapshots.getFirst()).data();
        assertThat(data.fftSize()).isEqualTo(4096);
        int peak = 0;
        for (int i = 1; i < data.binCount(); i++) {
            if (data.magnitudesDb()[i] > data.magnitudesDb()[peak]) peak = i;
        }
        assertThat(peak).isEqualTo(32);
    }

    @Test
    void broadbandFillsSpectrumAndAntiphaseCannotEraseIt() throws Exception {
        float[][] noise = new float[2][4096];
        var random = new Random(319);
        for (int i = 0; i < noise[0].length; i++) {
            noise[0][i] = random.nextFloat(-0.5f, 0.5f);
            noise[1][i] = -noise[0][i];
        }
        var data = ((AnalyzerSnapshot.Spectrum) throughLane(AnalyzerProcessor.Kind.SPECTRUM, noise).getFirst()).data();
        for (float value : data.magnitudesDb()) assertThat(value).isGreaterThan(-100);
    }

    @Test
    void correlationReportsIdenticalInvertedAndDecorrelatedSignals() throws Exception {
        float[][] audio = sine(3200, 440);
        assertThat(correlation(audio)).isCloseTo(1, within(1e-6));
        for (int i = 0; i < audio[0].length; i++) audio[1][i] = -audio[0][i];
        assertThat(correlation(audio)).isCloseTo(-1, within(1e-6));
        var random = new Random(42);
        for (int i = 0; i < audio[0].length; i++) audio[1][i] = random.nextFloat(-1, 1);
        assertThat(correlation(audio)).isCloseTo(0, within(0.08));
    }

    @Test
    void laneTunesA440AndClearsOnSilence() throws Exception {
        var data = ((AnalyzerSnapshot.Pitch) throughLane(AnalyzerProcessor.Kind.PITCH, sine(4096, 440)).getFirst()).data();
        assertThat(data.noteName()).isEqualTo("A");
        assertThat(data.octave()).isEqualTo(4);
        assertThat(data.frequencyHz()).isCloseTo(440, within(1.0));
        var silence = throughLane(AnalyzerProcessor.Kind.PITCH, new float[2][4096]);
        assertThat(((AnalyzerSnapshot.Pitch) silence.getFirst()).data()).isNull();
    }

    @Test
    void rightOnlyTuningUsesLiveReferenceAndClearsTheSameConsumer() {
        var snapshots = new ArrayList<AnalyzerSnapshot>();
        var reference = new java.util.concurrent.atomic.AtomicReference<>(440.0);
        try (var processor = new AnalyzerProcessor(AnalyzerProcessor.Kind.PITCH, snapshots::add,
                reference::get, 4096, com.benesquivelmusic.daw.sdk.analysis.WindowType.HANN)) {
            float[][] rightOnly = sine(8192, 432);
            java.util.Arrays.fill(rightOnly[0], 0);
            processor.onBlock(rightOnly, 2, 8192, RATE);
            assertThat(((AnalyzerSnapshot.Pitch) snapshots.getLast()).data().centsOffset()).isLessThan(-20);
            reference.set(432.0);
            processor.onBlock(rightOnly, 2, 8192, RATE);
            assertThat(((AnalyzerSnapshot.Pitch) snapshots.getLast()).data().centsOffset()).isCloseTo(0, within(1.0));
            processor.onBlock(new float[2][8192], 2, 8192, RATE);
            assertThat(((AnalyzerSnapshot.Pitch) snapshots.getLast()).data()).isNull();
        }
        try (var waveform = new AnalyzerProcessor(AnalyzerProcessor.Kind.WAVEFORM, snapshots::add)) {
            float[][] rightOnly = sine(1024, 432);
            java.util.Arrays.fill(rightOnly[0], 0);
            waveform.onBlock(rightOnly, 2, 1024, RATE);
            float peak = 0;
            for (float value : ((AnalyzerSnapshot.Waveform) snapshots.getLast()).data().maxValues()) peak = Math.max(peak, Math.abs(value));
            assertThat(peak).isGreaterThan(0.4f);
        }
    }

    @Test
    void snapshotsAreIsolatedFromSourceAndConsumerMutation() {
        var data = new com.benesquivelmusic.daw.sdk.visualization.SpectrumData(new float[]{-10, -20}, 4, RATE);
        var snapshot = new AnalyzerSnapshot.Spectrum(data);
        data.magnitudesDb()[0] = 0;
        snapshot.data().magnitudesDb()[1] = 0;
        assertThat(snapshot.data().magnitudesDb()).containsExactly(-10, -20);
        var wave = new com.benesquivelmusic.daw.sdk.visualization.WaveformData(
                new float[]{-0.5f}, new float[]{0.5f}, new float[]{0.25f}, 1);
        var retained = new AnalyzerSnapshot.Waveform(wave);
        wave.maxValues()[0] = 1;
        retained.data().minValues()[0] = -1;
        assertThat(retained.data().maxValues()).containsExactly(0.5f);
        assertThat(retained.data().minValues()).containsExactly(-0.5f);
    }

    @Test
    void actualRingOverrunReportsDiscontinuityBeforeSurvivingBlock() {
        var bus = new MeteringTapBus();
        bus.rebind(new Mixer(), new AudioFormat(RATE, 2, 24, 512), 1);
        // Drain a lane synchronously to make overload and recovery ordering deterministic.
        var ring = new SampleBlockRing(2, 512);
        var subscription = new AnalysisSubscription(bus, MeterTapPoint.MASTER_CHAIN, 1, ring);
        var events = new ArrayList<String>();
        var lane = new AnalysisLane(subscription, new AnalysisConsumer() {
            @Override public void onBlock(float[][] samples, int channels, int frames, double rate) { events.add("block"); }
            @Override public void onOverrun(long dropped) { events.add("drop:" + dropped); }
        }, RATE);
        try {
            for (int i = 0; i < 8; i++) {
                ring.write(new float[2][512], 2, 512);
            }
            lane.drain();
            assertThat(events.getFirst()).startsWith("drop:");
            assertThat(events).contains("block");
            assertThat(ring.droppedBlocks()).isEqualTo(6);
        } finally { bus.close(); }
    }

    @Test
    void loudnessPublishesTenSnapshotsPerSecondOnAnalysisThread() throws Exception {
        var snapshots = throughLane(AnalyzerProcessor.Kind.LOUDNESS, sine(RATE, 1000));
        assertThat(snapshots).hasSize(10);
        assertThat(((AnalyzerSnapshot.Loudness) snapshots.getLast()).data().momentaryLufs()).isFinite();
    }

    @Test
    void monoLoudnessIsThreeLuBelowDualMonoAndSilenceKeepsProgramHistory() {
        var mono = new ArrayList<AnalyzerSnapshot>();
        var stereo = new ArrayList<AnalyzerSnapshot>();
        try (var one = new AnalyzerProcessor(AnalyzerProcessor.Kind.LOUDNESS, mono::add);
             var two = new AnalyzerProcessor(AnalyzerProcessor.Kind.LOUDNESS, stereo::add)) {
            float[][] audio = sine(RATE, 1000);
            one.onBlock(audio, 1, RATE, RATE);
            two.onBlock(audio, 2, RATE, RATE);
            double monoLu = ((AnalyzerSnapshot.Loudness) mono.getLast()).data().integratedLufs();
            double stereoLu = ((AnalyzerSnapshot.Loudness) stereo.getLast()).data().integratedLufs();
            assertThat(stereoLu - monoLu).isCloseTo(3.0103, within(0.03));
            one.onBlock(new float[1][4800], 1, 4800, RATE);
            assertThat(((AnalyzerSnapshot.Loudness) mono.getLast()).data()).isNull();
            float[][] quieter = sine(4800, 1000);
            for (int i = 0; i < 4800; i++) quieter[0][i] *= 0.1f;
            one.onBlock(quieter, 1, 4800, RATE);
            double resumed = ((AnalyzerSnapshot.Loudness) mono.getLast()).data().integratedLufs();
            assertThat(resumed).as("previous program loudness survives the quiet gap").isGreaterThan(monoLu - 3);
        }
    }

    @Test
    void missingSamplesAndFormatChangesRestartWindows() {
        var snapshots = new ArrayList<AnalyzerSnapshot>();
        try (var processor = new AnalyzerProcessor(AnalyzerProcessor.Kind.SPECTRUM, snapshots::add)) {
            processor.onBlock(sine(4095, 375), 2, 4095, RATE);
            assertThat(snapshots).isEmpty();
            processor.onOverrun(7);
            processor.onBlock(sine(1, 375), 2, 1, RATE);
            assertThat(snapshots).isEmpty();
            assertThat(processor.droppedBlocks()).isEqualTo(7);
            processor.onBlock(sine(4095, 375), 2, 4095, RATE);
            assertThat(snapshots).hasSize(1);
            processor.onBlock(sine(2048, 375), 2, 2048, 96_000);
            assertThat(snapshots).hasSize(1);
            processor.onBlock(sine(2048, 375), 2, 2048, 96_000);
            assertThat(((AnalyzerSnapshot.Spectrum) snapshots.getLast()).data().sampleRate()).isEqualTo(96_000);
        }
    }

    @Test
    void everyAnalyzerPublishesExplicitNoSignalForSilentBlocks() throws Exception {
        for (var kind : AnalyzerProcessor.Kind.values()) {
            var snapshots = throughLane(kind, new float[2][RATE / 5]);
            assertThat(snapshots).isNotEmpty();
            for (var snapshot : snapshots) {
                Object value = switch (snapshot) {
                    case AnalyzerSnapshot.Spectrum s -> s.data();
                    case AnalyzerSnapshot.Waveform s -> s.data();
                    case AnalyzerSnapshot.Correlation s -> s.data();
                    case AnalyzerSnapshot.Loudness s -> s.data();
                    case AnalyzerSnapshot.Pitch s -> s.data();
                };
                assertThat(value).isNull();
            }
        }
    }

    private double correlation(float[][] samples) throws Exception {
        return ((AnalyzerSnapshot.Correlation) throughLane(AnalyzerProcessor.Kind.CORRELATION, samples).getFirst()).data().correlation();
    }

    private List<AnalyzerSnapshot> throughLane(AnalyzerProcessor.Kind kind, float[][] samples) throws Exception {
        var bus = new MeteringTapBus();
        var results = new CopyOnWriteArrayList<AnalyzerSnapshot>();
        var threads = new CopyOnWriteArrayList<String>();
        var processed = new AtomicLong();
        bus.rebind(new Mixer(), new AudioFormat(RATE, 2, 24, 512), 1);
        try (var processor = new AnalyzerProcessor(kind, snapshot -> {
            results.add(snapshot);
            threads.add(Thread.currentThread().getName());
        })) {
            var subscription = bus.attachAnalysis(MeterTapPoint.MASTER_CHAIN, 256, (audio, channels, frames, rate) -> {
                processor.onBlock(audio, channels, frames, rate);
                processed.addAndGet(frames);
            });
            for (int offset = 0; offset < samples[0].length; offset += 512) {
                int frames = Math.min(512, samples[0].length - offset);
                var taps = bus.snapshot();
                var slot = taps.masterChain();
                slot.beginBlock(taps.epoch(), taps.blockIndex(), 2);
                float[][] block = new float[2][];
                for (int channel = 0; channel < 2; channel++) {
                    block[channel] = java.util.Arrays.copyOfRange(samples[channel], offset, offset + frames);
                    slot.accumulate(channel, block[channel], frames);
                }
                slot.publish(frames);
                for (var ring : slot.rings()) ring.write(block, 2, frames);
                bus.blockCompleted(taps);
            }
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (processed.get() < samples[0].length && System.nanoTime() < deadline) Thread.sleep(2);
            assertThat(processed.get()).isEqualTo(samples[0].length);
            assertThat(subscription.droppedBlocks()).isZero();
            assertThat(threads).containsOnly("daw-metering-analysis");
            return results;
        } finally {
            bus.close();
        }
    }

    static float[][] sine(int frames, double frequency) {
        float[][] samples = new float[2][frames];
        for (int i = 0; i < frames; i++) samples[0][i] = samples[1][i] = (float) (0.5 * Math.sin(2 * Math.PI * frequency * i / RATE));
        return samples;
    }
}
