package com.benesquivelmusic.daw.core.mixer.spatial;

import com.benesquivelmusic.daw.sdk.spatial.ImmersiveFormat;

import java.util.Objects;
import java.util.UUID;

/**
 * Per-track routing into specific bed-bus channels.
 *
 * <p>A {@code BedChannelRouting} answers the question: "for this source
 * track, how loud should it appear in each channel of the bed bus?" The
 * canonical example from the issue is a guitar routed at 0 dB to L, −3 dB
 * to C, and −6 dB to Ls — a routing that the rest of the bed bus channels
 * leave at {@link Double#NEGATIVE_INFINITY} (silent).</p>
 *
 * <p>The {@link #channelGainsDb()} array must have one element per channel
 * of {@link #format()}, in the channel order defined by
 * {@link ImmersiveFormat#speakers()}. A value of
 * {@link Double#NEGATIVE_INFINITY} means "do not route to this channel"
 * (i.e. linear gain of 0). Finite values represent dB gain trim
 * (0 dB = unity).</p>
 *
 * @param trackId        the source track identifier
 * @param format         the immersive bed format this routing targets
 * @param channelGainsDb per-channel gain in dB; length must equal
 *                       {@code format.channelCount()};
 *                       {@link Double#NEGATIVE_INFINITY} means muted on
 *                       that channel
 */
public record BedChannelRouting(UUID trackId, ImmersiveFormat format, double[] channelGainsDb) {

    /** Sentinel value indicating "no signal" on a bed channel. */
    public static final double SILENT_DB = Double.NEGATIVE_INFINITY;

    public BedChannelRouting {
        Objects.requireNonNull(trackId, "trackId must not be null");
        Objects.requireNonNull(format, "format must not be null");
        Objects.requireNonNull(channelGainsDb, "channelGainsDb must not be null");
        if (channelGainsDb.length != format.channelCount()) {
            throw new IllegalArgumentException(
                    "channelGainsDb length " + channelGainsDb.length
                            + " does not match format channel count " + format.channelCount());
        }
        channelGainsDb = channelGainsDb.clone();
    }

    /**
     * Creates a fully muted routing for the given track and format
     * (every channel set to {@link #SILENT_DB}).
     *
     * @param trackId the source track identifier
     * @param format  the bed format
     * @return an all-silent routing
     */
    public static BedChannelRouting silent(UUID trackId, ImmersiveFormat format) {
        Objects.requireNonNull(format, "format must not be null");
        double[] gains = new double[format.channelCount()];
        java.util.Arrays.fill(gains, SILENT_DB);
        return new BedChannelRouting(trackId, format, gains);
    }

    @Override
    public double[] channelGainsDb() {
        return channelGainsDb.clone();
    }

    /**
     * Returns the gain in dB for the given channel index.
     *
     * @param channelIndex the zero-based channel index
     * @return the gain in dB (possibly {@link #SILENT_DB})
     */
    public double gainDb(int channelIndex) {
        return channelGainsDb[channelIndex];
    }

    /**
     * Returns the linear gain coefficient for the given channel index.
     *
     * <p>{@link #SILENT_DB} is mapped to {@code 0.0}; finite dB values are
     * mapped via {@code 10^(dB/20)}.</p>
     *
     * @param channelIndex the zero-based channel index
     * @return the linear gain in [0.0, +∞)
     */
    public double linearGain(int channelIndex) {
        double db = channelGainsDb[channelIndex];
        if (db == SILENT_DB || Double.isInfinite(db)) {
            return 0.0;
        }
        return Math.pow(10.0, db / 20.0);
    }

    /**
     * Returns a copy of this routing with the gain on the given channel
     * replaced by {@code gainDb}.
     *
     * @param channelIndex the channel to update
     * @param gainDb       the new gain in dB ({@link #SILENT_DB} for muted)
     * @return a new routing
     */
    public BedChannelRouting withChannelGain(int channelIndex, double gainDb) {
        double[] copy = channelGainsDb.clone();
        copy[channelIndex] = gainDb;
        return new BedChannelRouting(trackId, format, copy);
    }
}
