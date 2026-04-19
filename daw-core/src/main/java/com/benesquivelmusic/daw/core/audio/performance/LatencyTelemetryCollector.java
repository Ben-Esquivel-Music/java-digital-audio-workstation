package com.benesquivelmusic.daw.core.audio.performance;

import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.Send;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.audio.LatencyTelemetry;
import com.benesquivelmusic.daw.sdk.audio.LatencyTelemetry.NodeKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/**
 * Snapshots the live mixer graph's reported latencies and publishes
 * {@link LatencyTelemetry} records for tracks, insert plugins, return
 * buses, sends, and the master bus.
 *
 * <p>This is the non-audio-thread side of plugin delay compensation
 * (PDC) introspection. It provides:</p>
 * <ul>
 *   <li>{@link #snapshot(Mixer)} — produces an immutable list of
 *       {@link LatencyTelemetry} records for the current graph, trusting
 *       each processor's self-reported
 *       {@link AudioProcessor#getLatencySamples()} value.</li>
 *   <li>{@link #publish(Mixer)} — snapshots and pushes the result onto a
 *       {@link Flow.Publisher} that UI components (the latency telemetry
 *       panel, the transport bar PDC counter) subscribe to.</li>
 *   <li>{@link #nodesChangedSinceLastSnapshot()} — returns the set of
 *       node ids whose latency changed since the previous publish, so
 *       the UI can flash them.</li>
 *   <li>A "Constrain Delay Compensation" mode
 *       ({@link #setConstrainDelayCompensationEnabled(boolean)} plus
 *       {@link #setConstrainThresholdSamples(int)}) that bypasses
 *       lookahead-heavy plugins above the threshold while tracking and
 *       restores them on disable.</li>
 * </ul>
 *
 * <p>The publisher delivers on a separate executor so the UI never
 * blocks the audio thread.</p>
 */
public final class LatencyTelemetryCollector implements AutoCloseable {

    /** Default sample threshold for Constrain Delay Compensation mode (256 samples). */
    public static final int DEFAULT_CONSTRAIN_THRESHOLD_SAMPLES = 256;

    private final SubmissionPublisher<List<LatencyTelemetry>> publisher;

    /** Most recent samples-per-nodeId snapshot, used for change detection. */
    private Map<String, Integer> previousSamples = Map.of();

    /** Node ids whose latency changed between the last two snapshots. */
    private List<String> changedNodes = List.of();

    /** Slots that constrain-mode has bypassed, along with their pre-constrain bypass flag. */
    private final Map<InsertSlot, Boolean> constrainedSlots = new HashMap<>();

    private boolean constrainEnabled;
    private int constrainThresholdSamples = DEFAULT_CONSTRAIN_THRESHOLD_SAMPLES;

    /** Creates a collector with a default {@link SubmissionPublisher}. */
    public LatencyTelemetryCollector() {
        this.publisher = new SubmissionPublisher<>();
    }

    /**
     * Returns the publisher that emits latency telemetry snapshots. UI
     * code subscribes here to refresh tree views, bar graphs, and the
     * transport bar PDC counter.
     */
    public Flow.Publisher<List<LatencyTelemetry>> telemetryEvents() {
        return publisher;
    }

    /**
     * Captures the current latency state of the mixer graph.
     *
     * <p>The returned list is ordered: per-channel {@code TRACK} node
     * first, then each of its {@code PLUGIN} inserts (only those that
     * are not bypassed), then its {@code SEND} entries; after all
     * channels, every return {@code BUS} and its plugins; finally the
     * {@code MASTER} bus and its plugins. Bypassed inserts contribute
     * zero to the parent track/bus but are still emitted so the UI can
     * render them greyed-out.</p>
     *
     * <p>The aggregate {@code TRACK}/{@code BUS}/{@code MASTER} sample
     * count equals the sum of its enabled (non-bypassed) inserts —
     * mirroring {@link com.benesquivelmusic.daw.core.audio.EffectsChain#getTotalLatencySamples()}.</p>
     *
     * @param mixer the mixer to introspect
     * @return an immutable list of telemetry records
     */
    public List<LatencyTelemetry> snapshot(Mixer mixer) {
        Objects.requireNonNull(mixer, "mixer must not be null");
        List<LatencyTelemetry> out = new ArrayList<>();
        for (MixerChannel ch : mixer.getChannels()) {
            appendChannel(out, ch, NodeKind.TRACK, /*emitSends*/ true);
        }
        for (MixerChannel bus : mixer.getReturnBuses()) {
            appendChannel(out, bus, NodeKind.BUS, /*emitSends*/ false);
        }
        appendChannel(out, mixer.getMasterChannel(), NodeKind.MASTER, /*emitSends*/ false);
        return Collections.unmodifiableList(out);
    }

    /**
     * Snapshots {@code mixer} and publishes the result to subscribers.
     * Also updates the "changed nodes" set for flash animations.
     *
     * @param mixer the mixer to introspect
     * @return the published snapshot
     */
    public List<LatencyTelemetry> publish(Mixer mixer) {
        List<LatencyTelemetry> snap = snapshot(mixer);
        updateChangeTracking(snap);
        publisher.offer(snap, null);
        return snap;
    }

    /**
     * Returns the node ids whose latency changed between the two most
     * recent {@link #publish(Mixer)} calls. Consumed by the UI to
     * trigger a short flash animation on those rows.
     */
    public List<String> nodesChangedSinceLastSnapshot() {
        return changedNodes;
    }

    /**
     * Returns the total session plugin delay compensation — the same
     * value surfaced by the transport bar next to the xruns counter.
     * Equivalent to {@link Mixer#getSystemLatencySamples()}, but
     * computed from the last snapshot for UI consistency.
     *
     * @param snapshot the snapshot produced by {@link #snapshot(Mixer)}
     *                 or {@link #publish(Mixer)}
     * @return the maximum track/bus/master sample count in the snapshot
     */
    public static int totalSessionPdcSamples(List<LatencyTelemetry> snapshot) {
        int max = 0;
        for (LatencyTelemetry t : snapshot) {
            if (t.kind() == NodeKind.TRACK || t.kind() == NodeKind.BUS || t.kind() == NodeKind.MASTER) {
                max = Math.max(max, t.samples());
            }
        }
        return max;
    }

    // ---------------- Constrain Delay Compensation ----------------

    /** Returns whether Constrain Delay Compensation mode is active. */
    public boolean isConstrainDelayCompensationEnabled() {
        return constrainEnabled;
    }

    /**
     * Sets the per-plugin sample threshold used by
     * {@link #applyConstrainDelayCompensation(Mixer)}. Plugins whose
     * reported latency exceeds this value are bypassed while constrain
     * mode is active. Default: {@value #DEFAULT_CONSTRAIN_THRESHOLD_SAMPLES}.
     */
    public void setConstrainThresholdSamples(int samples) {
        if (samples < 0) {
            throw new IllegalArgumentException("samples must be >= 0: " + samples);
        }
        this.constrainThresholdSamples = samples;
    }

    /** Returns the configured constrain threshold in samples. */
    public int getConstrainThresholdSamples() {
        return constrainThresholdSamples;
    }

    /**
     * Enables or disables Constrain Delay Compensation globally. When
     * enabled, the caller should invoke {@link #applyConstrainDelayCompensation(Mixer)}
     * (or pass the mixer directly — see overload) so lookahead-heavy
     * plugins are bypassed. When disabled, previously-constrained
     * plugins are automatically restored.
     */
    public void setConstrainDelayCompensationEnabled(boolean enabled) {
        this.constrainEnabled = enabled;
        if (!enabled) {
            restoreConstrainedSlots();
        }
    }

    /**
     * Enables/disables Constrain Delay Compensation and immediately
     * applies the change to {@code mixer}.
     *
     * @param enabled whether to constrain
     * @param mixer   the mixer whose plugins should be constrained or
     *                restored
     */
    public void setConstrainDelayCompensationEnabled(boolean enabled, Mixer mixer) {
        Objects.requireNonNull(mixer, "mixer must not be null");
        this.constrainEnabled = enabled;
        if (enabled) {
            applyConstrainDelayCompensation(mixer);
        } else {
            restoreConstrainedSlots();
        }
    }

    /**
     * Walks the mixer and bypasses every insert slot whose processor
     * reports latency strictly greater than the configured threshold,
     * remembering each slot's original bypass flag so it can be
     * restored by {@link #setConstrainDelayCompensationEnabled(boolean)}
     * when the mode is turned off.
     *
     * <p>A no-op when constrain mode is disabled.</p>
     *
     * @param mixer the mixer whose channels should be scanned
     */
    public void applyConstrainDelayCompensation(Mixer mixer) {
        Objects.requireNonNull(mixer, "mixer must not be null");
        if (!constrainEnabled) {
            return;
        }
        for (MixerChannel ch : mixer.getChannels()) {
            constrainChannel(ch);
        }
        for (MixerChannel bus : mixer.getReturnBuses()) {
            constrainChannel(bus);
        }
        constrainChannel(mixer.getMasterChannel());
    }

    private void constrainChannel(MixerChannel channel) {
        List<InsertSlot> slots = channel.getInsertSlots();
        for (InsertSlot slot : slots) {
            int latency = safeLatency(slot.getProcessor());
            if (latency > constrainThresholdSamples && !constrainedSlots.containsKey(slot)) {
                constrainedSlots.put(slot, slot.isBypassed());
                slot.setBypassed(true);
            }
        }
    }

    private void restoreConstrainedSlots() {
        for (Map.Entry<InsertSlot, Boolean> e : constrainedSlots.entrySet()) {
            e.getKey().setBypassed(e.getValue());
        }
        constrainedSlots.clear();
    }

    // ---------------- Internals ----------------

    private void appendChannel(List<LatencyTelemetry> out,
                               MixerChannel channel,
                               NodeKind kind,
                               boolean emitSends) {
        String channelId = kind.name().toLowerCase() + ":" + channel.getName();
        int aggregate = 0;
        List<InsertSlot> slots = channel.getInsertSlots();
        for (int i = 0; i < slots.size(); i++) {
            InsertSlot slot = slots.get(i);
            int pluginLatency = slot.isBypassed() ? 0 : safeLatency(slot.getProcessor());
            aggregate += pluginLatency;
            String pluginId = channelId + "/insert[" + i + "]:" + slot.getName();
            out.add(new LatencyTelemetry(pluginId, NodeKind.PLUGIN, pluginLatency, slot.getName()));
        }
        out.add(new LatencyTelemetry(channelId, kind, aggregate, channel.getName()));
        if (emitSends) {
            List<Send> sends = channel.getSends();
            for (int i = 0; i < sends.size(); i++) {
                Send send = sends.get(i);
                MixerChannel target = send.getTarget();
                int sendLatency = target == null ? 0 : target.getEffectsChain().getTotalLatencySamples();
                String targetName = target == null ? "unassigned" : target.getName();
                String sendId = channelId + "/send[" + i + "]->" + targetName;
                out.add(new LatencyTelemetry(sendId, NodeKind.SEND, sendLatency, channel.getName()));
            }
        }
    }

    private static int safeLatency(AudioProcessor processor) {
        if (processor == null) {
            return 0;
        }
        int l = processor.getLatencySamples();
        return Math.max(0, l);
    }

    private void updateChangeTracking(List<LatencyTelemetry> snapshot) {
        Map<String, Integer> current = new HashMap<>(snapshot.size() * 2);
        List<String> changed = new ArrayList<>();
        for (LatencyTelemetry t : snapshot) {
            current.put(t.nodeId(), t.samples());
            Integer prev = previousSamples.get(t.nodeId());
            if (prev == null || prev != t.samples()) {
                changed.add(t.nodeId());
            }
        }
        // Don't flash the very first snapshot — only subsequent changes.
        if (previousSamples.isEmpty()) {
            changed = List.of();
        }
        this.previousSamples = Map.copyOf(current);
        this.changedNodes = List.copyOf(changed);
    }

    @Override
    public void close() {
        publisher.close();
    }
}
