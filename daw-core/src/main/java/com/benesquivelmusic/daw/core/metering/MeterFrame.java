package com.benesquivelmusic.daw.core.metering;

import com.benesquivelmusic.daw.sdk.visualization.LevelData;

import java.util.Arrays;
import java.util.Objects;

/**
 * A consumer-side, mutable, <em>reusable</em> level frame: per-channel peak
 * and RMS (linear), the clip flag, and the {@code epoch} / {@code blockIndex}
 * stamp of the render block it describes (book &sect;3.4).
 *
 * <p>A consumer owns one frame per subscription and passes it to
 * {@link LevelSubscription#readInto(MeterFrame)} on every FX pulse; nothing is
 * allocated per read. The frame is only ever written on the reader's thread.
 * When a read fails (torn or unresolved) the frame is left exactly as it was,
 * so the caller may keep showing the previous value.</p>
 *
 * <p>{@link #toLevelData()} is the FX-side bridge to the SDK
 * {@link LevelData} record (dB conversion happens here, never on the render
 * thread).</p>
 */
public final class MeterFrame {

    /** Maximum lanes carried per frame; wider buffers are truncated to this. */
    public static final int MAX_CHANNELS = 8;

    private final float[] peakLinear = new float[MAX_CHANNELS];
    private final float[] rmsLinear = new float[MAX_CHANNELS];
    /** Seqlock read staging; committed into the live arrays only on a stable read. */
    private final float[] stagePeak = new float[MAX_CHANNELS];
    private final float[] stageRms = new float[MAX_CHANNELS];
    private int channelCount;
    private boolean clipped;
    private long epoch;
    private long blockIndex;

    /** Creates an empty (silent, zero-channel, epoch 0, block 0) frame. */
    public MeterFrame() {
    }

    /** Linear peak of lane {@code channel}; zero for lanes beyond {@link #channelCount()}. */
    public float peak(int channel) {
        Objects.checkIndex(channel, MAX_CHANNELS);
        return peakLinear[channel];
    }

    /** Linear RMS of lane {@code channel}; zero for lanes beyond {@link #channelCount()}. */
    public float rms(int channel) {
        Objects.checkIndex(channel, MAX_CHANNELS);
        return rmsLinear[channel];
    }

    /** The largest lane peak, or zero when the frame has no lanes. */
    public float maxPeak() {
        float max = 0f;
        for (int ch = 0; ch < channelCount; ch++) {
            if (peakLinear[ch] > max) {
                max = peakLinear[ch];
            }
        }
        return max;
    }

    /** The largest lane RMS, or zero when the frame has no lanes. */
    public float maxRms() {
        float max = 0f;
        for (int ch = 0; ch < channelCount; ch++) {
            if (rmsLinear[ch] > max) {
                max = rmsLinear[ch];
            }
        }
        return max;
    }

    /** Number of meaningful lanes ({@code 0..MAX_CHANNELS}). */
    public int channelCount() {
        return channelCount;
    }

    /** {@code true} when any sample of the block reached or exceeded full scale. */
    public boolean clipped() {
        return clipped;
    }

    /** The binding epoch the producing block rendered under. */
    public long epoch() {
        return epoch;
    }

    /** The bus block counter of the producing block. */
    public long blockIndex() {
        return blockIndex;
    }

    /** {@code true} when every lane is at zero and nothing clipped. */
    public boolean isSilent() {
        return !clipped && maxPeak() == 0f && maxRms() == 0f;
    }

    /** Resets to the empty state: no lanes, silent, epoch 0, block 0. */
    public void clear() {
        Arrays.fill(peakLinear, 0f);
        Arrays.fill(rmsLinear, 0f);
        channelCount = 0;
        clipped = false;
        epoch = 0L;
        blockIndex = 0L;
    }

    /**
     * Makes this frame a silent frame with the given stamp and lane count
     * (the "honest idle" frame a drain delivers when blocks stop arriving).
     *
     * @throws IllegalArgumentException if {@code channelCount} is negative or
     *                                  exceeds {@link #MAX_CHANNELS}
     */
    public void markSilent(long epoch, long blockIndex, int channelCount) {
        if (channelCount < 0 || channelCount > MAX_CHANNELS) {
            throw new IllegalArgumentException(
                    "channelCount must be in [0, " + MAX_CHANNELS + "]: " + channelCount);
        }
        Arrays.fill(peakLinear, 0f);
        Arrays.fill(rmsLinear, 0f);
        this.channelCount = channelCount;
        this.clipped = false;
        this.epoch = epoch;
        this.blockIndex = blockIndex;
    }

    /**
     * Builds the SDK {@link LevelData} for the loudest lane. dB is
     * {@code 20 * log10(linear)}, negative infinity for zero. FX side only:
     * this allocates a record and calls {@link Math#log10(double)}.
     */
    public LevelData toLevelData() {
        double peak = maxPeak();
        double rms = maxRms();
        return new LevelData(peak, rms, toDb(peak), toDb(rms), clipped);
    }

    /** {@code 20 * log10(linear)}, or negative infinity for a non-positive input. */
    public static double toDb(double linear) {
        return linear <= 0.0 ? Double.NEGATIVE_INFINITY : 20.0 * Math.log10(linear);
    }

    // Seqlock reader seam (package-private, LevelTapSlot only).

    float[] stagePeak() {
        return stagePeak;
    }

    float[] stageRms() {
        return stageRms;
    }

    /** Commits a stable staged read into the live fields. */
    void commitStaged(int channelCount, boolean clipped, long epoch, long blockIndex) {
        System.arraycopy(stagePeak, 0, peakLinear, 0, MAX_CHANNELS);
        System.arraycopy(stageRms, 0, rmsLinear, 0, MAX_CHANNELS);
        this.channelCount = channelCount;
        this.clipped = clipped;
        this.epoch = epoch;
        this.blockIndex = blockIndex;
    }

    @Override
    public String toString() {
        return "MeterFrame[epoch=" + epoch + ", block=" + blockIndex
                + ", channels=" + channelCount + ", peak=" + maxPeak()
                + ", rms=" + maxRms() + ", clipped=" + clipped + "]";
    }
}
