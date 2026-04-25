package com.benesquivelmusic.daw.core.mixer.spatial;

import com.benesquivelmusic.daw.sdk.spatial.ImmersiveFormat;

import java.util.Objects;
import java.util.UUID;

/**
 * A multi-channel "bed" bus for Dolby Atmos sessions.
 *
 * <p>A bed bus is the channel-based portion of a Dolby Atmos mix: it carries
 * ambience and static elements that are routed to specific speaker positions
 * (Left, Right, Center, LFE, surrounds, height channels). The bus exposes
 * one mixer "strip" per channel of the chosen {@link ImmersiveFormat} (for
 * example, twelve strips for 7.1.4) and feeds the fold-down monitoring chain
 * and the spatial renderer.</p>
 *
 * <p>This record carries the immutable identity ({@link #id()}), the chosen
 * {@link #format()} which drives the channel count and channel order, and a
 * per-channel gain trim in decibels ({@link #channelGainsDb()}). The gain
 * array length must equal {@link ImmersiveFormat#channelCount()}.</p>
 *
 * @param id              the stable identifier of this bed bus
 * @param format          the immersive format (drives channel count and order)
 * @param channelGainsDb  per-channel gain trim in dB
 *                        (length must equal {@code format.channelCount()})
 */
public record BedBus(UUID id, ImmersiveFormat format, double[] channelGainsDb) {

    public BedBus {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(format, "format must not be null");
        Objects.requireNonNull(channelGainsDb, "channelGainsDb must not be null");
        if (channelGainsDb.length != format.channelCount()) {
            throw new IllegalArgumentException(
                    "channelGainsDb length " + channelGainsDb.length
                            + " does not match format channel count " + format.channelCount());
        }
        // Defensive copy so the record is effectively immutable.
        channelGainsDb = channelGainsDb.clone();
    }

    /**
     * Creates a bed bus with all channel gains at unity (0 dB).
     *
     * @param id     the stable identifier
     * @param format the immersive format
     * @return a new {@code BedBus} with zeroed (0 dB) channel gains
     */
    public static BedBus unityGain(UUID id, ImmersiveFormat format) {
        Objects.requireNonNull(format, "format must not be null");
        return new BedBus(id, format, new double[format.channelCount()]);
    }

    /** Returns the channel count for this bed bus. */
    public int channelCount() {
        return format.channelCount();
    }

    /**
     * Returns a defensive copy of the per-channel gain trim array.
     *
     * <p>The accessor is overridden to prevent callers from mutating the
     * record's internal state.</p>
     */
    @Override
    public double[] channelGainsDb() {
        return channelGainsDb.clone();
    }

    /**
     * Returns a copy of this bed bus with the given immersive format.
     *
     * <p>The returned bus has unity gain on every channel; callers that need
     * to preserve existing routings should use
     * {@link BedBusManager#setFormat(ImmersiveFormat)} which keeps routings
     * for channels that exist in both formats.</p>
     *
     * @param newFormat the new format
     * @return a new bed bus
     */
    public BedBus withFormat(ImmersiveFormat newFormat) {
        return BedBus.unityGain(id, newFormat);
    }
}
