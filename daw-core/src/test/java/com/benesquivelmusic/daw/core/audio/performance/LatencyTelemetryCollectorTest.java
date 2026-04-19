package com.benesquivelmusic.daw.core.audio.performance;

import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.Send;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.audio.LatencyTelemetry;
import com.benesquivelmusic.daw.sdk.audio.LatencyTelemetry.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LatencyTelemetryCollectorTest {

    /** A minimal audio processor that reports a fixed latency and does nothing. */
    private static AudioProcessor processorWithLatency(int samples) {
        return new AudioProcessor() {
            @Override
            public void process(float[][] input, float[][] output, int numFrames) {
                // no-op
            }

            @Override
            public int getOutputChannelCount() {
                return 2;
            }

            @Override
            public int getInputChannelCount() {
                return 2;
            }

            @Override
            public void reset() {
                // no-op
            }

            @Override
            public int getLatencySamples() {
                return samples;
            }
        };
    }

    @Test
    void snapshotSumsEnabledPluginLatenciesOnTracksAndBuses() {
        Mixer mixer = new Mixer();
        MixerChannel drums = new MixerChannel("Drums");
        drums.addInsert(new InsertSlot("Comp", processorWithLatency(64)));
        drums.addInsert(new InsertSlot("EQ", processorWithLatency(128)));
        // Bypassed plugins must NOT contribute to the parent track sum.
        InsertSlot bypassed = new InsertSlot("Limiter", processorWithLatency(2048));
        drums.addInsert(bypassed);
        drums.setInsertBypassed(2, true);
        mixer.addChannel(drums);

        MixerChannel master = mixer.getMasterChannel();
        master.addInsert(new InsertSlot("Mastering EQ", processorWithLatency(32)));

        try (LatencyTelemetryCollector collector = new LatencyTelemetryCollector()) {
            List<LatencyTelemetry> snapshot = collector.snapshot(mixer);

            LatencyTelemetry track = findOne(snapshot, NodeKind.TRACK, "track:Drums");
            assertThat(track.samples())
                    .as("Track total latency equals sum of enabled inserts")
                    .isEqualTo(64 + 128);

            LatencyTelemetry bypassedPlugin = findOne(snapshot, NodeKind.PLUGIN,
                    "track:Drums/insert[2]:Limiter");
            assertThat(bypassedPlugin.samples())
                    .as("Bypassed plugin reports zero contribution")
                    .isZero();

            LatencyTelemetry masterNode = findOne(snapshot, NodeKind.MASTER, "master:Master");
            assertThat(masterNode.samples()).isEqualTo(32);

            // Collector's total-session PDC equals the maximum aggregate node.
            assertThat(LatencyTelemetryCollector.totalSessionPdcSamples(snapshot))
                    .isEqualTo(Math.max(64 + 128, 32));
        }
    }

    @Test
    void snapshotEmitsSendsWithTargetBusLatency() {
        Mixer mixer = new Mixer();
        MixerChannel reverb = mixer.getReturnBuses().get(0); // "Reverb Return"
        reverb.addInsert(new InsertSlot("Convolver", processorWithLatency(512)));

        MixerChannel vocal = new MixerChannel("Vocal");
        vocal.addSend(new Send(reverb));
        mixer.addChannel(vocal);

        try (LatencyTelemetryCollector collector = new LatencyTelemetryCollector()) {
            List<LatencyTelemetry> snapshot = collector.snapshot(mixer);

            LatencyTelemetry send = snapshot.stream()
                    .filter(t -> t.kind() == NodeKind.SEND)
                    .findFirst().orElseThrow();
            assertThat(send.samples())
                    .as("Send latency mirrors its target bus")
                    .isEqualTo(512);
        }
    }

    @Test
    void publishDeliversSnapshotToSubscribers() throws InterruptedException {
        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Gtr");
        ch.addInsert(new InsertSlot("Amp", processorWithLatency(100)));
        mixer.addChannel(ch);

        try (LatencyTelemetryCollector collector = new LatencyTelemetryCollector()) {
            List<List<LatencyTelemetry>> received = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            collector.telemetryEvents().subscribe(new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                @Override public void onNext(List<LatencyTelemetry> item) { received.add(item); latch.countDown(); }
                @Override public void onError(Throwable t) { }
                @Override public void onComplete() { }
            });

            collector.publish(mixer);

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received).hasSize(1);
            assertThat(received.get(0)).anyMatch(t -> t.kind() == NodeKind.TRACK && t.samples() == 100);
        }
    }

    @Test
    void changedNodesReflectLatencyDeltasBetweenSnapshots() {
        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Synth");
        InsertSlot eq = new InsertSlot("EQ", processorWithLatency(64));
        ch.addInsert(eq);
        mixer.addChannel(ch);

        try (LatencyTelemetryCollector collector = new LatencyTelemetryCollector()) {
            collector.publish(mixer);
            assertThat(collector.nodesChangedSinceLastSnapshot())
                    .as("First snapshot never flashes")
                    .isEmpty();

            // Bypassing the only insert changes both plugin and track latency.
            ch.setInsertBypassed(0, true);
            collector.publish(mixer);

            assertThat(collector.nodesChangedSinceLastSnapshot())
                    .anyMatch(id -> id.startsWith("track:Synth"))
                    .anyMatch(id -> id.contains("insert[0]:EQ"));

            // No further change → changed set is empty.
            collector.publish(mixer);
            assertThat(collector.nodesChangedSinceLastSnapshot()).isEmpty();
        }
    }

    @Test
    void constrainModeBypassesPluginsAboveThresholdAndRestoresOnDisable() {
        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Master-like");
        InsertSlot lightEq = new InsertSlot("EQ", processorWithLatency(64));
        InsertSlot heavyLim = new InsertSlot("Limiter", processorWithLatency(2048));
        InsertSlot alreadyOff = new InsertSlot("ExtraLim", processorWithLatency(4096));
        alreadyOff.setBypassed(true);
        ch.addInsert(lightEq);
        ch.addInsert(heavyLim);
        ch.addInsert(alreadyOff);
        mixer.addChannel(ch);

        try (LatencyTelemetryCollector collector = new LatencyTelemetryCollector()) {
            // Default threshold = 256 samples.
            assertThat(collector.getConstrainThresholdSamples())
                    .isEqualTo(LatencyTelemetryCollector.DEFAULT_CONSTRAIN_THRESHOLD_SAMPLES);

            collector.setConstrainDelayCompensationEnabled(true, mixer);

            assertThat(lightEq.isBypassed())
                    .as("Sub-threshold plugin keeps its original bypass state")
                    .isFalse();
            assertThat(heavyLim.isBypassed())
                    .as("Over-threshold plugin is bypassed by constrain mode")
                    .isTrue();
            assertThat(alreadyOff.isBypassed())
                    .as("Already-bypassed plugin stays bypassed")
                    .isTrue();

            // Track total should drop to just the light EQ while constrained.
            List<LatencyTelemetry> constrained = collector.snapshot(mixer);
            assertThat(findOne(constrained, NodeKind.TRACK, "track:Master-like").samples())
                    .isEqualTo(64);

            // Disable → everything restored to pre-constrain flags.
            collector.setConstrainDelayCompensationEnabled(false, mixer);
            assertThat(lightEq.isBypassed()).isFalse();
            assertThat(heavyLim.isBypassed())
                    .as("Heavy plugin restored to original (non-bypassed) state")
                    .isFalse();
            assertThat(alreadyOff.isBypassed())
                    .as("Pre-existing user bypass preserved after restore")
                    .isTrue();
        }
    }

    @Test
    void constrainModeRespectsCustomThreshold() {
        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Ch");
        InsertSlot a = new InsertSlot("A", processorWithLatency(100));
        InsertSlot b = new InsertSlot("B", processorWithLatency(500));
        ch.addInsert(a);
        ch.addInsert(b);
        mixer.addChannel(ch);

        try (LatencyTelemetryCollector collector = new LatencyTelemetryCollector()) {
            collector.setConstrainThresholdSamples(50);
            collector.setConstrainDelayCompensationEnabled(true, mixer);

            assertThat(a.isBypassed()).isTrue();
            assertThat(b.isBypassed()).isTrue();

            collector.setConstrainDelayCompensationEnabled(false, mixer);
            assertThat(a.isBypassed()).isFalse();
            assertThat(b.isBypassed()).isFalse();
        }
    }

    @Test
    void shouldRejectNegativeConstrainThreshold() {
        try (LatencyTelemetryCollector collector = new LatencyTelemetryCollector()) {
            assertThatThrownBy(() -> collector.setConstrainThresholdSamples(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void snapshotRequiresNonNullMixer() {
        try (LatencyTelemetryCollector collector = new LatencyTelemetryCollector()) {
            assertThatThrownBy(() -> collector.snapshot(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    private static LatencyTelemetry findOne(List<LatencyTelemetry> list, NodeKind kind, String id) {
        return list.stream()
                .filter(t -> t.kind() == kind && t.nodeId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No telemetry for " + kind + " " + id + " in " + list));
    }
}
