package com.benesquivelmusic.daw.sdk.audio;

/**
 * Standard audio sample rates supported by the DAW.
 *
 * <p>Higher sample rates provide wider frequency response and more
 * accurate representation of transients, at the cost of higher CPU
 * and storage requirements.</p>
 */
public enum SampleRate {

    /** CD quality. */
    HZ_44100(44_100),

    /** Professional video/broadcast standard. */
    HZ_48000(48_000),

    /** High-resolution: 2x CD rate. */
    HZ_88200(88_200),

    /** High-resolution: 2x broadcast rate. */
    HZ_96000(96_000),

    /** Ultra-high-resolution: 4x CD rate. */
    HZ_176400(176_400),

    /** Ultra-high-resolution: 4x broadcast rate. */
    HZ_192000(192_000);

    private final int hz;

    SampleRate(int hz) {
        this.hz = hz;
    }

    /**
     * Returns the sample rate in Hertz.
     *
     * @return the sample rate in Hz
     */
    public int getHz() {
        return hz;
    }

    /**
     * Returns the {@code SampleRate} whose value matches the given frequency.
     *
     * @param hz the sample rate in Hz
     * @return the matching sample rate
     * @throws IllegalArgumentException if no matching sample rate exists
     */
    public static SampleRate fromHz(int hz) {
        for (SampleRate rate : values()) {
            if (rate.hz == hz) {
                return rate;
            }
        }
        throw new IllegalArgumentException("Unsupported sample rate: " + hz);
    }
}
