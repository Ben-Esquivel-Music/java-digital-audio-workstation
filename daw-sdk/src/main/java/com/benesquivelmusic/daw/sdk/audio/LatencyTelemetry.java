package com.benesquivelmusic.daw.sdk.audio;

import java.util.Objects;

/**
 * Reports the latency contributed by a single node in the audio graph
 * (a plugin insert, a track's effects chain, a bus, a send, or the
 * master bus).
 *
 * <p>The DAW's {@code LatencyTelemetryCollector} emits a snapshot of
 * these records each render cycle so user-interface layers can surface
 * plugin delay compensation (PDC) numbers the way Pro Tools' Time
 * Adjuster, Cubase's Constrain Delay Compensation, or Reaper's
 * performance meter do.</p>
 *
 * @param nodeId     stable identifier for the node (track name, slot
 *                   index, bus name, etc.); must not be {@code null}
 * @param kind       the kind of node that reported the latency
 * @param samples    latency in sample frames; must be {@code >= 0}
 * @param reportedBy identifier of the entity that produced the value
 *                   (plugin name, channel id, "internal", …);
 *                   must not be {@code null}
 */
public record LatencyTelemetry(String nodeId, NodeKind kind, int samples, String reportedBy) {

    public LatencyTelemetry {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(reportedBy, "reportedBy must not be null");
        if (samples < 0) {
            throw new IllegalArgumentException("samples must be >= 0: " + samples);
        }
    }

    /**
     * Returns the latency in milliseconds given a sample rate.
     *
     * @param sampleRateHz frames per second; must be positive
     * @return the latency in milliseconds
     */
    public double millis(double sampleRateHz) {
        if (sampleRateHz <= 0.0 || Double.isNaN(sampleRateHz) || Double.isInfinite(sampleRateHz)) {
            throw new IllegalArgumentException("sampleRateHz must be positive: " + sampleRateHz);
        }
        return (samples / sampleRateHz) * 1_000.0;
    }

    /**
     * The kind of node that reported the latency. Although enums in
     * Java are already closed by construction, this type is the
     * "sealed enum" described in the latency-telemetry story: a fixed,
     * exhaustive set of values that drives pattern-matching {@code switch}
     * expressions in the collector and UI.
     */
    public enum NodeKind {
        /** A single plugin/insert effect within a track or bus chain. */
        PLUGIN,
        /** An entire track's effects chain (sum of its inserts). */
        TRACK,
        /** A return/auxiliary bus chain. */
        BUS,
        /** A send routing from a track to a bus. */
        SEND,
        /** The master bus. */
        MASTER
    }
}
