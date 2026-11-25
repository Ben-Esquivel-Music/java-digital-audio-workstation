package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.core.dsp.TruePeakDetector;
import com.benesquivelmusic.daw.sdk.analysis.InputLevelMeter;

import java.util.concurrent.atomic.AtomicLong;

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
 * <h2>Per-stream state</h2>
 *
 * <p>The level, the clip latch and the frame of its clip, the routing flag,
 * the physical-input flag, the frame counter and the true-peak detector
 * belong to the stream of one render drive. A monitor holds one volatile
 * reference to that stream's state, tagged with the drive it was built for;
 * each method reads the reference once and works on that state. The source
 * availability and the routing description stay with the monitor, and the
 * mute is the registry's. Each render drive the engine builds gives
 * the monitor a new state ({@link InputLevelMonitorRegistry#liftAll(long)}),
 * and so does the close of the input devices beside a stream when it stops
 * waiting for a block one of them is still delivering
 * ({@link InputLevelMonitorRegistry#refuseTheDevicesBesideTheStream()}): a
 * state of the same drive, with a true-peak detector of its own, that takes
 * over the level record, the clip latch, the routing and physical-input
 * flags and the frame count as they are at that moment and refuses those
 * devices' writes. Each is installed through the registry on the engine's
 * stream lifecycle thread, never on a render or delivery thread; a monitor
 * the registry creates is constructed with the state the registry requires
 * at that moment ({@link InputLevelMonitorRegistry#getOrCreate(String)}).
 * A writer that passes a render drive is refused when that drive is older
 * than the state's, or when the state refuses the input devices beside its
 * stream: nothing is written and nothing latched. The render pump passes
 * {@code Long.MAX_VALUE}, as the overloads that take no drive do, and is
 * never refused; the delivery thread of an input device beside the stream
 * passes the drive built for that device's stream.</p>
 *
 * <h2>Thread-safety</h2>
 *
 * <p>One lock, a private object of each monitor, makes a clear of the clip
 * latch ({@link #reset()}, the FX thread), a loss's floor
 * ({@link #silenceThrough(long)}, through
 * {@link InputLevelMonitorRegistry#silenceAll(long)}, on the FX thread and
 * the engine's stream lifecycle) and the install of a new state (the
 * engine's stream lifecycle, through the registry) exclude each other; each
 * holds it for a few statements that take no other lock. A clear or a floor
 * is therefore made entirely before an install copies the latch and the
 * level, which then carries no latch from before the clear and carries the
 * floor, or entirely after the install, on the installed state; a latch a
 * writer sets on the replaced state after the install is in a state nothing
 * shows. Nothing else takes it: the measuring methods, the flags' writes,
 * {@link #snapshot()} and the other readers take no lock, so the render pump
 * and an input device's delivery thread never wait on it, and a clear or a
 * floor waits only for the others that take it, an install, a clear or a
 * floor, each held for a few statements. A reader — the UI reads
 * {@link #snapshot()} on the FX thread — reads only volatile fields: the
 * state's level, latch, routing flag and physical-input flag, the bound
 * source's availability and the registry's mute, never the frame counter or
 * the true-peak detector. It gets an immutable {@link InputLevelMeter},
 * always whole: the state's last
 * one; {@link InputLevelMeter#SILENCE} while the input is not a physical one
 * or its routing or source is unavailable; or, while the mute holds, the
 * floor with the state's clip latch and the clip's frame index, read one
 * after the other. A state's frame counter and true-peak history are plain,
 * written by the threads that measure its stream's blocks and by
 * {@link #reset()}; where two of them overlap on one state, a clip's frame
 * index, or one block's level or clip decision, can be off; what a reader
 * gets is still whole. A monitor is written from several threads:</p>
 * <ul>
 *   <li>the stream's render pump, which taps its input block through
 *       {@link #processInputChannels} and sets the physical-input and
 *       routing flags;</li>
 *   <li>the delivery thread of an input device opened beside the stream for a
 *       multi-device take, which taps that device's blocks the same way,
 *       passing the drive built for that device's stream;</li>
 *   <li>the engine's control thread, which binds the routing description, the
 *       source availability and the flags when the graph or a take changes;</li>
 *   <li>the FX thread, which clears the clip latch ({@link #reset()}) and,
 *       through {@link InputLevelMonitorRegistry#silenceAll(long)}, drops the
 *       level to the floor at a device loss;</li>
 *   <li>the engine's stream lifecycle, which at each render drive it builds
 *       gives each monitor a new state and lifts a loss's mute
 *       ({@link InputLevelMonitorRegistry#liftAll(long)}), which gives each
 *       monitor a state that refuses the input devices beside the stream
 *       when their close stops waiting for a block one of them is still
 *       delivering
 *       ({@link InputLevelMonitorRegistry#refuseTheDevicesBesideTheStream()}),
 *       and which mutes the meters again when a reconnect closes at once a
 *       stream it opened on another device
 *       ({@link InputLevelMonitorRegistry#silenceAll(long)}).</li>
 * </ul>
 * <p>The UI reads {@link #snapshot()} on the FX thread.</p>
 *
 * <h2>A device loss</h2>
 *
 * <p>A monitor created by an {@link InputLevelMonitorRegistry} shares the
 * registry's lost-stream mute. From the loss until a newer render drive is
 * built, {@link #snapshot()} reads the floor with the live clip latch, so a
 * block the lost stream still renders — the render pump's in-flight block,
 * or an input device beside it that keeps delivering until it is closed —
 * shows no level, while a clip in it still latches (story 338, ruling E175).
 * A stream a reconnect opens on another device and closes again at once does
 * not end the loss: from its close the snapshot reads the floor again until
 * the next stream starts (ruling E185). The mute is read only in
 * {@link #snapshot()}: the processing methods never read it, so the render
 * path does no work for it and no check there can race the mute's
 * writers.</p>
 *
 * <p>Every render drive the engine builds starts each monitor with a new
 * state: the floor with the clip latch the old state holds at the build, the
 * old state's frame count, routing flag and physical-input flag, and a
 * true-peak detector of its own, so nothing of the previous stream enters
 * the new stream's first block, whichever order a loss's delivery and the
 * build take (ruling E185).
 * No block of a closed stream shows on the input meters once a newer render
 * drive was built, however late it arrives (ruling E201): a block an input
 * device beside the closed stream delivers after that build is refused, and
 * one being measured at the build completes into the closed stream's level
 * and true-peak history, which nothing shows, so neither shows a level,
 * lights the clip LED or leaves samples in the newer stream's true-peak
 * history. From the moment the close of the input devices beside a stream
 * stops waiting for a block one of them is still delivering, nothing more of
 * that delivery shows on the input meters, whether or not a newer render
 * drive is built after that moment (ruling E205): the meters take nothing
 * more from the input devices beside that stream, so what the delivery still
 * writes is refused, or completes into state nothing shows when it was being
 * measured at that moment, and it shows no level, lights no clip LED and sets
 * or clears no routing flag, on the stopped stream's meters or, through the
 * clip latch and routing flag a newer stream takes over at its build, on the
 * newer stream's. A loss delivered after a newer render drive was built changes
 * nothing on the meters: no floor and no mute, so the newer stream's levels
 * and clip latches stay as they are (ruling E202).</p>
 *
 * <p>Matches the style and thread-safety conventions of sibling classes
 * {@link LevelMeter} and {@link CorrelationMeter}.</p>
 */
public final class InputLevelMonitor {

    /** Default clip threshold in linear amplitude: {@code 1.0} = {@code 0 dBFS}. */
    public static final double DEFAULT_CLIP_THRESHOLD_LINEAR = 1.0;

    private static final double DB_FLOOR = InputLevelMeter.DB_FLOOR;

    /**
     * The drive of a writer no state refuses, neither one a build makes stale nor one that refuses the input
     * devices beside its stream: the stream's render pump, or a caller that names none.
     */
    private static final long ANY_DRIVE = Long.MAX_VALUE;

    private static final Runnable NOTHING = () -> { };

    private final double clipThresholdLinear;
    private final LostStreamMute lostStreamMute;

    /**
     * Taken by {@link #reset()}, {@link #silenceThrough(long)} and the
     * install of a state, and by nothing else; see the class javadoc.
     */
    private final Object installLock = new Object();

    /** The newest built drive's state; replaced only under {@link #installLock}. */
    private volatile DriveState state;

    private volatile InputSourceAvailability sourceAvailability;
    private volatile String routingDescription = "Input routing unavailable; input is silent";

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
        this(clipThresholdLinear, new LostStreamMute(), 0L, false);
    }

    /**
     * Creates a monitor with the default threshold that shares a registry's
     * lost-stream mute, so a monitor created during a device loss is muted
     * from its first snapshot.
     *
     * @param lostStreamMute the registry's mute
     * @param drive          the newest render drive the registry has seen built
     * @param refusing       whether the meters refuse the input devices beside that drive's stream
     */
    InputLevelMonitor(LostStreamMute lostStreamMute, long drive, boolean refusing) {
        this(DEFAULT_CLIP_THRESHOLD_LINEAR, lostStreamMute, drive, refusing);
    }

    private InputLevelMonitor(double clipThresholdLinear, LostStreamMute lostStreamMute, long drive,
                              boolean refusing) {
        if (clipThresholdLinear <= 0.0) {
            throw new IllegalArgumentException(
                    "clipThresholdLinear must be > 0: " + clipThresholdLinear);
        }
        this.clipThresholdLinear = clipThresholdLinear;
        this.lostStreamMute = lostStreamMute;
        this.state = new DriveState(drive, refusing, 0L, false, -1L, false, true, InputLevelMeter.SILENCE);
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

        DriveState measured = state;
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
            double tp = measured.truePeakDetector.processSample(s);
            if (tp > truePeakLinear) {
                truePeakLinear = tp;
            }
            // Latch the first sample in the block at or above threshold.
            if (clipOffsetInBlock < 0 && tp >= clipThresholdLinear) {
                clipOffsetInBlock = i;
            }
        }

        // Peak reported to the UI is the greater of the sample peak and the
        // oversampled inter-sample peak so the meter never visually under-
        // reports relative to the clip LED.
        publishBlock(measured, Math.max(peakLinear, truePeakLinear), Math.sqrt(sumSquares / length),
                clipOffsetInBlock, length);
    }

    /**
     * Convenience overload that processes the entire sample array.
     *
     * @param samples audio samples (must not be empty)
     */
    public void process(float[] samples) {
        process(samples, 0, samples.length);
    }

    public String routingDescription() { return routingDescription; }
    public void setRoutingDescription(String description) { routingDescription = description; }

    /** The input routing cannot be satisfied; meter surfaces share the capture warning. */
    public boolean isRoutingUnavailable() {
        DriveState shown = state;
        return shown.routingUnavailable || isSourceUnavailable(shown);
    }

    /** Binds this monitor to its current capture source; {@code null} clears the binding. */
    public void setSourceAvailability(InputSourceAvailability availability) { sourceAvailability = availability; }
    /** Enables source availability warnings for a physical route, preserving its generation across NONE edits. */
    public void setPhysicalInput(boolean physicalInput) { state.physicalInput = physicalInput; }

    /**
     * As {@link #setPhysicalInput(boolean)}, for a writer of the stream of
     * render drive {@code drive}: refused, writing nothing, when the current
     * state belongs to a newer drive (ruling E201) or refuses the input
     * devices beside its stream (ruling E205), and otherwise stored into the
     * state read at entry, so a write a build or such a refusal overtakes
     * lands in the state it replaced, which nothing shows, and a routing edit
     * made on the installed state stays. {@code Long.MAX_VALUE}, the render
     * pump's, is never refused.
     *
     * @param physicalInput whether the track's input routing names a physical input
     * @param drive         the render drive built for the writer's stream
     */
    public void setPhysicalInput(boolean physicalInput, long drive) {
        DriveState written = state;
        if (!refuses(written, drive)) {
            written.physicalInput = physicalInput;
        }
    }

    /**
     * Package-private for tests: as {@link #setPhysicalInput(boolean, long)},
     * running {@code afterTheStateIsChecked} between the read of the state,
     * with its check of {@code drive}, and the store.
     */
    void setPhysicalInput(boolean physicalInput, long drive, Runnable afterTheStateIsChecked) {
        DriveState written = state;
        if (!refuses(written, drive)) {
            afterTheStateIsChecked.run();
            written.physicalInput = physicalInput;
        }
    }

    private boolean isSourceUnavailable(DriveState shown) {
        InputSourceAvailability source = sourceAvailability;
        return shown.physicalInput && source != null && !source.isAvailable();
    }

    /**
     * Marks the routing of the current state's stream unavailable, which
     * also drops its level to {@link InputLevelMeter#SILENCE}, or available.
     *
     * @param unavailable whether the routing cannot be satisfied
     */
    public void setRoutingUnavailable(boolean unavailable) {
        setRoutingUnavailable(unavailable, ANY_DRIVE);
    }

    /**
     * As {@link #setRoutingUnavailable(boolean)}, for a writer of the stream
     * of render drive {@code drive}: refused, writing nothing, when the
     * current state belongs to a newer drive (ruling E201) or refuses the
     * input devices beside its stream (ruling E205). {@code Long.MAX_VALUE},
     * the render pump's, is never refused.
     *
     * @param unavailable whether the routing cannot be satisfied
     * @param drive       the render drive built for the writer's stream
     */
    public void setRoutingUnavailable(boolean unavailable, long drive) {
        DriveState written = state;
        if (refuses(written, drive)) {
            return;
        }
        written.routingUnavailable = unavailable;
        if (unavailable) {
            written.latest = InputLevelMeter.SILENCE;
        }
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
        processInputChannels(channels, firstChannel, channelCount, numFrames, ANY_DRIVE);
    }

    /**
     * As {@link #processInputChannels(float[][], int, int, int)}, for a block
     * of the stream of render drive {@code drive}: refused, measuring and
     * latching nothing, when the current state belongs to a newer drive
     * (ruling E201) or refuses the input devices beside its stream (ruling
     * E205), and otherwise measured into the state read at entry, so a block
     * a build or such a refusal overtakes completes into the state it
     * replaced, which nothing shows. {@code Long.MAX_VALUE}, the render
     * pump's, is never refused.
     *
     * @param channels      input buffer {@code [channel][frame]}
     * @param firstChannel  zero-based channel offset to start reading from
     * @param channelCount  number of contiguous channels to summarize
     * @param numFrames     number of frames in this block
     * @param drive         the render drive built for the block's stream
     */
    public void processInputChannels(float[][] channels, int firstChannel, int channelCount, int numFrames,
                                     long drive) {
        DriveState measured = state;
        checkTheSlice(channels, firstChannel, channelCount, numFrames);
        if (!refuses(measured, drive)) {
            measureInto(measured, channels, firstChannel, channelCount, numFrames);
        }
    }

    /**
     * Package-private for tests: as
     * {@link #processInputChannels(float[][], int, int, int, long)}, running
     * {@code afterTheStateIsChecked} between the read of the state, with its
     * check of {@code drive}, and the measurement.
     */
    void processInputChannels(float[][] channels, int firstChannel, int channelCount, int numFrames, long drive,
                              Runnable afterTheStateIsChecked) {
        DriveState measured = state;
        checkTheSlice(channels, firstChannel, channelCount, numFrames);
        if (!refuses(measured, drive)) {
            afterTheStateIsChecked.run();
            measureInto(measured, channels, firstChannel, channelCount, numFrames);
        }
    }

    /**
     * Whether {@code written} refuses a write that passes render drive
     * {@code drive}. A write that names a drive is refused when that drive
     * is older than the state's (ruling E201) or the state refuses the input
     * devices beside its stream (ruling E205); one that names none
     * ({@link #ANY_DRIVE}: the render pump, the control thread) is never
     * refused.
     */
    private static boolean refuses(DriveState written, long drive) {
        return drive < written.drive || drive != ANY_DRIVE && written.refusesTheDevicesBesideTheStream;
    }

    private static void checkTheSlice(float[][] channels, int firstChannel, int channelCount, int numFrames) {
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
    }

    private void measureInto(DriveState measured, float[][] channels, int firstChannel, int channelCount,
                             int numFrames) {
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

            double tp = measured.truePeakDetector.processSample(summary);
            if (tp > truePeakLinear) {
                truePeakLinear = tp;
            }
            if (clipOffsetInBlock < 0 && tp >= clipThresholdLinear) {
                clipOffsetInBlock = i;
            }
        }

        publishBlock(measured, Math.max(peakLinear, truePeakLinear), Math.sqrt(sumSquares / numFrames),
                clipOffsetInBlock, numFrames);
    }

    private static void publishBlock(DriveState measured, double reportedPeakLinear, double rmsLinear,
                                     int clipOffsetInBlock, int frames) {
        long blockStartFrame = measured.framesProcessed;
        measured.framesProcessed += frames;

        if (clipOffsetInBlock >= 0) {
            measured.clippedSinceReset = true;
            measured.lastClipFrameIndex = blockStartFrame + clipOffsetInBlock;
        }

        measured.latest = new InputLevelMeter(
                linearToDb(reportedPeakLinear),
                linearToDb(rmsLinear),
                measured.clippedSinceReset,
                measured.lastClipFrameIndex);
    }

    /**
     * Returns the most recent {@link InputLevelMeter} snapshot.
     *
     * <p>Safe to call from any thread. If {@link #process} has never been
     * invoked, returns {@link InputLevelMeter#SILENCE}. While the registry's
     * lost-stream mute holds (see the class javadoc), returns the floor with
     * the live clip latch.</p>
     */
    public InputLevelMeter snapshot() {
        DriveState shown = state;
        if (!shown.physicalInput || shown.routingUnavailable || isSourceUnavailable(shown)) {
            return InputLevelMeter.SILENCE;
        }
        return lostStreamMute.muted() ? floorKeepingTheClipLatch(shown) : shown.latest;
    }

    /**
     * Returns whether a clip has been observed since the last {@link #reset()}.
     * Safe to call from any thread.
     */
    public boolean isClippedSinceReset() {
        return state.clippedSinceReset;
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
     * <p>Made under the monitor's lock, which the install of a new state,
     * by a render drive's build or by a refusal of the input devices beside
     * the stream, also takes: the clear is made on the newest installed
     * state, and a state installed after it takes the latch as it is then,
     * clear unless a block has latched a clip since.</p>
     *
     * <p>Safe to call from any thread.</p>
     */
    public void reset() {
        synchronized (installLock) {
            clearTheLatch(state);
        }
    }

    private static void clearTheLatch(DriveState cleared) {
        cleared.clippedSinceReset = false;
        cleared.lastClipFrameIndex = -1L;
        // The oversampling detector's reset() clears its own truePeakLinear
        // memory and history. The ring-buffer clear is a benign race with
        // the audio thread's read: worst case, the next block sees a few
        // zeroed history samples, which only affects interpolation for the
        // first ~12 samples — indistinguishable from a normal silent start.
        cleared.truePeakDetector.reset();

        InputLevelMeter current = cleared.latest;
        cleared.latest = new InputLevelMeter(
                current.peakDbfs(),
                current.rmsDbfs(),
                false,
                -1L);
    }

    /**
     * Drops the level of the current state's stream to the floor and keeps
     * the clip latch: the input's device is gone, so the bar must not hold
     * its last level (story 338, ruling E64). On its own this floors the
     * level once, and the next processed block replaces it; a device loss
     * goes through {@link InputLevelMonitorRegistry#silenceAll(long)}, whose
     * mute holds the floor until a newer render drive is built (ruling
     * E175).
     *
     * <p>Safe to call from any thread.</p>
     */
    public void silence() {
        DriveState floored = state;
        floored.latest = floorKeepingTheClipLatch(floored);
    }

    /**
     * A device loss through render drive {@code lostDrive}: floors the
     * current state's level, keeping its clip latch, unless that state
     * belongs to a newer drive, which the loss leaves alone (ruling E202). A
     * negative {@code lostDrive}, a loss whose drive is unknown, floors it
     * whichever drive it belongs to. Made under the monitor's lock, which the
     * install of a new state also takes: the floor is made on the newest
     * installed state, and a state installed after it takes the floor over.
     */
    void silenceThrough(long lostDrive) {
        synchronized (installLock) {
            DriveState floored = state;
            if (lostDrive < 0 || floored.drive <= lostDrive) {
                floored.latest = floorKeepingTheClipLatch(floored);
            }
        }
    }

    /**
     * One monitor's step at the build of render drive {@code drive} (rulings
     * E175, E185 and E201), or, when {@code refusing}, at the close of the
     * input devices beside the stream of {@code drive} that stopped waiting
     * for a block one of them is still delivering (ruling E205). Under the
     * monitor's lock, unless its state already belongs to a newer drive, or
     * to {@code drive} and refuses as asked, installs a new one for
     * {@code drive} with a true-peak detector of its own, the old state's
     * frame count, routing and physical-input flags and clip latch as they
     * are at the install, and refusing those devices' writes when asked: at
     * the floor when the old state belongs to an older drive, otherwise
     * showing the old state's level record. A block or a write that read the
     * old state before the install completes into it, and nothing shows it.
     */
    void startWithDrive(long drive, boolean refusing) {
        install(drive, refusing, NOTHING);
    }

    /**
     * Package-private for tests: as {@link #startWithDrive(long, boolean)},
     * running {@code afterTheLatchIsCopied} between the copy of the old
     * state's clip latch and level and the install, with the monitor's lock
     * held.
     */
    void startWithDrive(long drive, boolean refusing, Runnable afterTheLatchIsCopied) {
        install(drive, refusing, afterTheLatchIsCopied);
    }

    private void install(long drive, boolean refusing, Runnable afterTheLatchIsCopied) {
        synchronized (installLock) {
            DriveState current = state;
            if (!precedes(current, drive, refusing)) {
                return;
            }
            boolean clipped = current.clippedSinceReset;
            long clipFrame = current.lastClipFrameIndex;
            InputLevelMeter shown = current.drive < drive
                    ? new InputLevelMeter(DB_FLOOR, DB_FLOOR, clipped, clipFrame)
                    : current.latest;
            DriveState fresh = new DriveState(drive, refusing, current.framesProcessed, clipped, clipFrame,
                    current.routingUnavailable, current.physicalInput, shown);
            afterTheLatchIsCopied.run();
            state = fresh;
        }
    }

    /** Whether {@code current} comes before the state for {@code drive} that refuses as {@code refusing} asks. */
    private static boolean precedes(DriveState current, long drive, boolean refusing) {
        return current.drive < drive
                || current.drive == drive && refusing && !current.refusesTheDevicesBesideTheStream;
    }

    private static InputLevelMeter floorKeepingTheClipLatch(DriveState floored) {
        return new InputLevelMeter(DB_FLOOR, DB_FLOOR, floored.clippedSinceReset, floored.lastClipFrameIndex);
    }

    /**
     * What one render drive's stream wrote into a monitor. The frame counter
     * and the true-peak detector are plain: the threads that measure this
     * stream's blocks write them (the stream's render pump and the delivery
     * threads of the input devices beside it), and the thread that clears the
     * clip latch ({@link #reset()}, the FX thread at the clip LED) clears the
     * detector.
     */
    private static final class DriveState {

        /** The render drive this state was built for; {@code 0} for a monitor created before any build. */
        final long drive;
        /** Whether this state refuses the writes of the input devices beside its stream (ruling E205). */
        final boolean refusesTheDevicesBesideTheStream;
        final TruePeakDetector truePeakDetector = new TruePeakDetector();
        long framesProcessed;
        volatile InputLevelMeter latest;
        volatile boolean clippedSinceReset;
        volatile long lastClipFrameIndex;
        volatile boolean routingUnavailable;
        volatile boolean physicalInput;

        DriveState(long drive, boolean refusesTheDevicesBesideTheStream, long framesProcessed,
                   boolean clippedSinceReset, long lastClipFrameIndex, boolean routingUnavailable,
                   boolean physicalInput, InputLevelMeter latest) {
            this.drive = drive;
            this.refusesTheDevicesBesideTheStream = refusesTheDevicesBesideTheStream;
            this.framesProcessed = framesProcessed;
            this.clippedSinceReset = clippedSinceReset;
            this.lastClipFrameIndex = lastClipFrameIndex;
            this.routingUnavailable = routingUnavailable;
            this.physicalInput = physicalInput;
            this.latest = latest;
        }
    }

    /**
     * A device loss's mute, shared by a registry and every monitor it
     * creates. It holds from a mute through render drive {@code L} until a
     * render drive newer than {@code L} is built, whichever order the two are
     * told in: the loss is delivered on the FX thread, and a reconnect can
     * build the next drive before that delivery runs. Two threads mute — the
     * FX thread at the loss, the engine's stream lifecycle when a reconnect
     * closes at once a stream it opened on another device — so each epoch
     * rises only by an atomic maximum: a store that loses a race to a newer
     * one never lowers it. Neither writer is a render thread.
     */
    static final class LostStreamMute {

        /** The newest drive a mute was taken through, {@code -1} before any. */
        private final AtomicLong mutedThroughDrive = new AtomicLong(-1L);
        /** The newest drive built since the registry was created; raised by the engine's stream lifecycle. */
        private final AtomicLong liftedFromDrive = new AtomicLong();

        boolean muted() {
            return mutedThroughDrive.get() >= liftedFromDrive.get();
        }

        void muteThrough(long drive) {
            mutedThroughDrive.accumulateAndGet(drive, Math::max);
        }

        void liftFrom(long newDrive) {
            liftedFromDrive.accumulateAndGet(newDrive, Math::max);
        }
    }

    private static double linearToDb(double linear) {
        if (linear <= 0.0) {
            return DB_FLOOR;
        }
        return Math.max(20.0 * Math.log10(linear), DB_FLOOR);
    }
}
