package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.core.dsp.TruePeakDetector;
import com.benesquivelmusic.daw.sdk.analysis.InputLevelMeter;

/**
 * Real-time input-signal monitor for an armed track.
 *
 * <p>Taps the raw input signal <em>ahead of any processing</em> (pre-insert,
 * pre-gain) and computes per-render-block sample peak, RMS, and an inter-
 * sample-peak clip decision. The clip flag is "latching" — once triggered,
 * it remains set until {@link #reset()} is called. The UI wires the mixer's
 * clip LED click to {@link #reset()} so engineers can acknowledge and clear
 * clips during tracking without re-arming the transport.</p>
 *
 * <h2>Inter-sample peak detection</h2>
 *
 * <p>Clip decisions use {@link TruePeakDetector}, an ITU-R BS.1770-4
 * compliant 4× oversampling polyphase FIR that reveals inter-sample peaks
 * lurking between digital sample points. A signal whose sample peaks stay
 * just below {@code 0 dBFS} can still have inter-sample peaks above
 * {@code 0 dBFS} that will clip the A/D or D/A — this is exactly what we
 * want to flag during input gain staging.</p>
 *
 * <p>A clip is registered when the oversampled peak equals or exceeds the
 * configured threshold (default {@code 1.0} linear, i.e., {@code 0 dBFS}).
 * The {@link InputLevelMeter#lastClipFrameIndex()} records the frame index
 * (a running counter maintained by the monitor, incremented by {@code
 * numFrames} each {@code process()} call) at which the clip was detected.</p>
 *
 * <h2>Thread-safety</h2>
 *
 * <p>A single monitor instance is written by exactly one thread — the audio
 * render thread that calls {@link #process(float[], int, int)}. The UI
 * thread reads snapshots via {@link #snapshot()}. The latest snapshot is
 * held in a {@code volatile} field so the UI always sees a complete,
 * consistent {@link InputLevelMeter} record (records are immutable, which
 * makes this safe without additional synchronization). The {@link #reset()}
 * operation may be called from the UI thread; it writes {@code volatile}
 * fields that the render thread picks up on its next pass.</p>
 *
 * <p>Matches the style and thread-safety conventions of sibling classes
 * {@link LevelMeter} and {@link CorrelationMeter}.</p>
 */
public final class InputLevelMonitor {

    /** Default clip threshold in linear amplitude: {@code 1.0} = {@code 0 dBFS}. */
    public static final double DEFAULT_CLIP_THRESHOLD_LINEAR = 1.0;

    private static final double DB_FLOOR = InputLevelMeter.DB_FLOOR;

    private final double clipThresholdLinear;
    private final TruePeakDetector truePeakDetector;

    // Written from the audio thread only.
    private long framesProcessed;

    // Mutable state mirrored into volatile snapshot each process() call.
    private volatile InputLevelMeter latest = InputLevelMeter.SILENCE;

    // Sticky clip state. Written by audio thread on detection; written by
    // UI thread on reset(). volatile to ensure cross-thread visibility.
    private volatile boolean clippedSinceReset;
    private volatile long lastClipFrameIndex = -1L;

    /**
     * Creates a monitor using the default {@code 0 dBFS} clip threshold.
     */
    public InputLevelMonitor() {
        this(DEFAULT_CLIP_THRESHOLD_LINEAR);
    }

    /**
     * Creates a monitor with a custom linear clip threshold.
     *
     * <p>Pass {@code 1.0} for a conventional {@code 0 dBFS} clip decision,
     * or a smaller value (e.g., {@code 0.9885} for {@code -0.1 dBFS}) for
     * a more conservative "approaching full-scale" policy.</p>
     *
     * @param clipThresholdLinear linear-amplitude threshold; must be {@code > 0}
     */
    public InputLevelMonitor(double clipThresholdLinear) {
        if (clipThresholdLinear <= 0.0) {
            throw new IllegalArgumentException(
                    "clipThresholdLinear must be > 0: " + clipThresholdLinear);
        }
        this.clipThresholdLinear = clipThresholdLinear;
        this.truePeakDetector = new TruePeakDetector();
    }

    /**
     * Processes one block of mono input samples, updating the peak/RMS/clip
     * state and publishing a fresh {@link InputLevelMeter} snapshot.
     *
     * <p>Call this exactly once per render block, with the raw input signal
     * <em>before</em> any insert effects, track gain, or fader stage.</p>
     *
     * @param samples audio samples
     * @param offset  start offset in {@code samples}
     * @param length  number of samples to process; must be {@code > 0}
     */
    public void process(float[] samples, int offset, int length) {
        if (samples == null) {
            throw new IllegalArgumentException("samples must not be null");
        }
        if (length <= 0) {
            throw new IllegalArgumentException("length must be > 0: " + length);
        }
        if (offset < 0 || offset + length > samples.length) {
            throw new IllegalArgumentException(
                    "offset/length out of range: offset=" + offset
                            + ", length=" + length
                            + ", samples.length=" + samples.length);
        }

        double peakLinear = 0.0;
        double truePeakLinear = 0.0;
        double sumSquares = 0.0;
        int clipOffsetInBlock = -1;

        for (int i = 0; i < length; i++) {
            float s = samples[offset + i];
            double abs = Math.abs(s);
            if (abs > peakLinear) {
                peakLinear = abs;
            }
            sumSquares += (double) s * s;

            // 4× oversampled inter-sample peak for this sample step.
            double tp = truePeakDetector.processSample(s);
            if (tp > truePeakLinear) {
                truePeakLinear = tp;
            }
            // Latch the first sample in the block at or above threshold.
            if (clipOffsetInBlock < 0 && tp >= clipThresholdLinear) {
                clipOffsetInBlock = i;
            }
        }

        double rmsLinear = Math.sqrt(sumSquares / length);

        // Peak reported to the UI is the greater of the sample peak and the
        // oversampled inter-sample peak so the meter never visually under-
        // reports relative to the clip LED.
        double reportedPeak = Math.max(peakLinear, truePeakLinear);

        long blockStartFrame = framesProcessed;
        framesProcessed += length;

        if (clipOffsetInBlock >= 0) {
            clippedSinceReset = true;
            lastClipFrameIndex = blockStartFrame + clipOffsetInBlock;
        }

        latest = new InputLevelMeter(
                linearToDb(reportedPeak),
                linearToDb(rmsLinear),
                clippedSinceReset,
                lastClipFrameIndex);
    }

    /**
     * Convenience overload that processes the entire sample array.
     *
     * @param samples audio samples (must not be empty)
     */
    public void process(float[] samples) {
        process(samples, 0, samples.length);
    }

    /**
     * Processes a multi-channel slice of an interleaved {@code [ch][frame]}
     * input buffer by reducing it to mono via per-frame {@code max(|s_ch|)}.
     *
     * <p>This matches the conventional pro-DAW behavior of showing a single
     * input-meter column per track even for stereo inputs: the clip LED
     * trips when <em>either</em> leg of the stereo pair clips, and the
     * displayed peak is the louder of the two.</p>
     *
     * <p>Zero-allocation on the audio thread: samples are read directly
     * from the provided {@code channels} array and summarized sample-by-
     * sample through a small stack-allocated loop.</p>
     *
     * @param channels      input buffer {@code [channel][frame]}
     * @param firstChannel  zero-based channel offset to start reading from
     * @param channelCount  number of contiguous channels to summarize
     * @param numFrames     number of frames in this block
     */
    public void processInputChannels(float[][] channels,
                                     int firstChannel,
                                     int channelCount,
                                     int numFrames) {
        if (channels == null) {
            throw new IllegalArgumentException("channels must not be null");
        }
        if (numFrames <= 0) {
            throw new IllegalArgumentException("numFrames must be > 0: " + numFrames);
        }
        if (firstChannel < 0 || channelCount <= 0
                || firstChannel + channelCount > channels.length) {
            throw new IllegalArgumentException(
                    "channel range [" + firstChannel + "," + (firstChannel + channelCount)
                            + ") out of bounds for channels.length=" + channels.length);
        }

        double peakLinear = 0.0;
        double truePeakLinear = 0.0;
        double sumSquares = 0.0;
        int clipOffsetInBlock = -1;

        for (int i = 0; i < numFrames; i++) {
            // Per-frame max-abs across the selected channel range.
            float summary = 0.0f;
            for (int ch = 0; ch < channelCount; ch++) {
                float s = channels[firstChannel + ch][i];
                float abs = Math.abs(s);
                if (abs > Math.abs(summary)) {
                    // Preserve sign so the oversampling FIR sees a signed
                    // representation close to the continuous-time envelope.
                    summary = s;
                }
            }

            double abs = Math.abs(summary);
            if (abs > peakLinear) {
                peakLinear = abs;
            }
            sumSquares += (double) summary * summary;

            double tp = truePeakDetector.processSample(summary);
            if (tp > truePeakLinear) {
                truePeakLinear = tp;
            }
            if (clipOffsetInBlock < 0 && tp >= clipThresholdLinear) {
                clipOffsetInBlock = i;
            }
        }

        double rmsLinear = Math.sqrt(sumSquares / numFrames);
        double reportedPeak = Math.max(peakLinear, truePeakLinear);

        long blockStartFrame = framesProcessed;
        framesProcessed += numFrames;

        if (clipOffsetInBlock >= 0) {
            clippedSinceReset = true;
            lastClipFrameIndex = blockStartFrame + clipOffsetInBlock;
        }

        latest = new InputLevelMeter(
                linearToDb(reportedPeak),
                linearToDb(rmsLinear),
                clippedSinceReset,
                lastClipFrameIndex);
    }

    /**
     * Returns the most recent {@link InputLevelMeter} snapshot.
     *
     * <p>Safe to call from any thread. If {@link #process} has never been
     * invoked, returns {@link InputLevelMeter#SILENCE}.</p>
     */
    public InputLevelMeter snapshot() {
        return latest;
    }

    /**
     * Returns whether a clip has been observed since the last {@link #reset()}.
     * Safe to call from any thread.
     */
    public boolean isClippedSinceReset() {
        return clippedSinceReset;
    }

    /**
     * Returns the configured clip threshold in linear amplitude.
     * {@code 1.0} corresponds to {@code 0 dBFS}.
     */
    public double getClipThresholdLinear() {
        return clipThresholdLinear;
    }

    /**
     * Clears the sticky clip flag. The current peak/RMS snapshot values are
     * preserved — only the clip latch and last-clip index are cleared. The
     * underlying oversampled-peak detector's running peak memory is also
     * cleared so that subsequent {@link #process} calls do not resurface
     * previously-observed inter-sample peaks.
     *
     * <p>Safe to call from any thread.</p>
     */
    public void reset() {
        clippedSinceReset = false;
        lastClipFrameIndex = -1L;
        // The oversampling detector's reset() clears its own truePeakLinear
        // memory and history. The ring-buffer clear is a benign race with
        // the audio thread's read: worst case, the next block sees a few
        // zeroed history samples, which only affects interpolation for the
        // first ~12 samples — indistinguishable from a normal silent start.
        truePeakDetector.reset();

        InputLevelMeter current = latest;
        latest = new InputLevelMeter(
                current.peakDbfs(),
                current.rmsDbfs(),
                false,
                -1L);
    }

    private static double linearToDb(double linear) {
        if (linear <= 0.0) {
            return DB_FLOOR;
        }
        return Math.max(20.0 * Math.log10(linear), DB_FLOOR);
    }
}
