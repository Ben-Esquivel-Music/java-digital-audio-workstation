package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer;
import com.benesquivelmusic.daw.core.automation.AutomationData;
import com.benesquivelmusic.daw.core.automation.AutomationParameter;
import com.benesquivelmusic.daw.core.automation.PluginParameterTarget;
import com.benesquivelmusic.daw.core.mixer.CueBus;
import com.benesquivelmusic.daw.core.mixer.CueBusManager;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.performance.PerformanceMonitor;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.core.recording.MetronomeSideOutputRouter;
import com.benesquivelmusic.daw.core.recording.MetronomeSideOutputRouter.RoutedClick;
import com.benesquivelmusic.daw.core.recording.Subdivision;
import com.benesquivelmusic.daw.core.track.AutomationMode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.ClipGainEnvelope;
import com.benesquivelmusic.daw.sdk.audio.SampleRateConverter;
import com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier;
import com.benesquivelmusic.daw.sdk.audio.SourceRateMetadata;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Unified per-block audio render pipeline shared by live playback and
 * offline export.
 *
 * <p>This class encapsulates the complete per-block processing chain:</p>
 * <ol>
 *   <li>Read clip audio (or synthesize MIDI) into per-track scratch buffers.</li>
 *   <li>Apply automation lane values to mixer channel parameters (volume,
 *       pan, mute, send level, and plugin-parameter automation) for tracks
 *       with {@link AutomationMode#READ} enabled.</li>
 *   <li>Mix the per-track buffers through the {@link Mixer} — applying
 *       per-channel insert effects, volume, pan, mute and solo, and routing
 *       sends to return buses that are summed into the main mix.</li>
 *   <li>Process the mixed result through the master effects chain.</li>
 *   <li>Render non-master channels to their direct hardware outputs.</li>
 *   <li>Advance the transport by the number of beats corresponding to the
 *       block size at the current tempo.</li>
 * </ol>
 *
 * <p>Two entry points are provided:</p>
 * <ul>
 *   <li>{@link #renderBlock(float[][], float[][], int, Transport, Mixer,
 *       List, MidiTrackRenderer, EffectsChain, AudioEngine.RecordingCallback,
 *       PerformanceMonitor)} — invoked from the audio callback on the live
 *       path. It is {@link RealTimeSafe}: all scratch buffers are
 *       pre-allocated by the constructor, no locks are acquired, and no
 *       heap allocations occur.</li>
 *   <li>{@link #renderOffline(Transport, Mixer, List, MidiTrackRenderer,
 *       EffectsChain, float[][], int, int)} — wraps {@code renderBlock} in a
 *       loop to render {@code totalFrames} of audio into a caller-supplied
 *       output buffer. This is the entry point for offline export code
 *       paths such as stem export, track bouncing, and master rendering.</li>
 * </ul>
 *
 * <p>Sharing a single rendering implementation between live playback and
 * offline export guarantees that exported audio is bit-identical to what
 * the user heard during playback — the "what you hear is what you get"
 * (WYHIWYG) principle. The only difference between live and offline is
 * the output destination and the pace of consumption: live rendering is
 * driven by the audio callback at real time, offline rendering runs as
 * fast as the CPU allows.</p>
 *
 * @see AudioEngine#processBlock(float[][], float[][], int)
 */
public final class RenderPipeline {

    private static final Logger LOG = Logger.getLogger(RenderPipeline.class.getName());

    /** Maximum number of tracks supported by this pipeline instance. */
    private final int maxTracks;

    /** Audio format describing channel count and sample rate. */
    private final AudioFormat format;

    // Pre-allocated mix buffer. [channel][frame]
    private final float[][] mixBuffer;

    // Pre-allocated per-track buffers: [track][channel][frame]
    private final float[][][] trackBuffers;

    // Pre-allocated per-return-bus buffers for send routing: [returnBus][channel][frame]
    private final float[][][] returnBuffers;

    // Pre-allocated scratch buffer for per-frame clip-gain envelope
    // evaluation. Sized to the maximum block size so the audio thread
    // never needs to allocate even when an envelope is present.
    private final float[] gainScratch;

    // Story 136 — buffer holding the trailing samples of the most recent
    // metronome-click main-mix contribution that did not fit inside the
    // current block. The next block's mixMetronomeClicks() copies any
    // remaining samples into mixBuffer at offset 0 so a click that
    // straddles a buffer boundary plays gap-free at the typical
    // 256/512-frame low-latency buffer sizes.
    private final float[][] clickTail;
    private int clickTailFrames;

    /**
     * Story 315 review — set when an in-block loop wrap leaves the CLICK
     * walk's cursor on a lap whose first frame has not been scheduled yet;
     * consumed by the next segment, which may live in the NEXT block because
     * a quantized wrap can land exactly on the block boundary. The positional
     * residue test alone cannot carry that fact across the boundary: for a
     * frame that HAS already been scheduled, beatsIntoLoop * samplesPerBeat
     * evaluates to 0.99999999999988990 rather than 1.0, and the lap's
     * loop-start click then fires a second time one frame later. (That
     * reading, the genuine residue it overlaps with, and the provenance of
     * both are recorded on the widening in {@code mixMetronomeClicks}.)
     *
     * <p><b>Staleness is bounded positionally, NOT by lifecycle clears, and
     * this flag is deliberately never cleared on stop or pause.</b> A stale
     * flag — one set before a seek that landed elsewhere inside the loop —
     * can only widen the window while the residue test also passes, i.e.
     * while the cursor is already within one frame past the loop start, where
     * emitting the loop-start event is the correct thing to do anyway. (A
     * seek landing three frames past the loop START produces no widening at
     * all: the residue test reads 3.0, not < 1.0. A seek past the loop END
     * cannot illustrate staleness at all — it trips the block-entry mapping
     * above {@code mixMetronomeClicks}'s split loop, which clears this flag
     * outright.) A lifecycle clear, by contrast, destroys real information:
     * {@code AudioEngine.processBlock} hands EVERY backend callback to
     * {@link #renderBlock} regardless of transport state, so a clear on the
     * not-playing path runs on every paused callback while the position sits
     * parked inside the sub-frame residue, and the loop-start event owed to
     * the paused lap is lost on resume. Do not re-add it.</p>
     *
     * <p><b>Provenance of the pause/resume evidence: it is a CONTENT-walk
     * measurement, carried over to this walk by symmetry rather than
     * measured here.</b> The number quoted for that argument — 4 loop-start
     * events with the not-playing clear, 5 without it, on the pause/resume
     * fixture in {@code AudioEngineMidiPlaybackTest} (48 kHz, 120 BPM,
     * 64-frame blocks, loop [0.0, 0.328125) beats = 7875 frames, 16 frames of
     * insert latency, 615 playing + 20 paused + 20 resumed blocks) — is a
     * {@link #trackLoopWrapPending} result: that fixture never installs a
     * metronome, so {@code mixMetronomeClicks} never runs during it and THIS
     * flag is never touched by it. No pause/resume fixture exercises the
     * click walk directly. The argument transfers because both walks park
     * their cursor inside the same kind of sub-frame wrap residue across a
     * pause and both consume the flag through the same shape of widening —
     * but it is reasoning by symmetry, not a click-walk measurement.</p>
     */
    private boolean clickLoopWrapPending;

    /**
     * The grid-window END of the previously scheduled click segment, or
     * {@link Double#NaN} if this walk has not scheduled one yet. RT-thread
     * confined, exactly like {@link #clickLoopWrapPending}.
     *
     * <p>The click walk's half of the frame-ownership partition; the full
     * argument is on {@link #trackPreviousWindowEndBeat}, and the two walks
     * are deliberately written to read identically. In brief: a segment's
     * grid window ends half a frame before its own exclusive end, because a
     * grid position in that last half frame rounds to a frame the segment
     * cannot address, so the NEXT segment reaches back to
     * {@code clickPreviousWindowEndBeat} and sounds it at its own frame 0 —
     * the frame nearest-frame rounding always said it belonged on. Carrying
     * the remembered bound itself, rather than recomputing
     * {@code cursor − half a frame}, is what makes the two windows abut with
     * no gap and no overlap.</p>
     *
     * <p>Lap edges are exempt and stay beat-exact at the loop end, so the
     * pre-wrap segment still releases its final grid position on the last
     * frame it owns. NaN is the "nothing scheduled yet" sentinel, and the
     * field is deliberately NOT cleared on stop or pause for the reasons
     * recorded on {@link #clickLoopWrapPending}.</p>
     */
    private double clickPreviousWindowEndBeat = Double.NaN;

    /**
     * The previous scheduled click segment's own END cursor — its raw
     * {@code segEndBeat}, NOT its grid-window end — or {@link Double#NaN} if
     * this walk has not scheduled one yet. RT-thread confined.
     *
     * <p>The continuity reference that decides whether the next segment may
     * carry at all; see {@link #trackPreviousSegmentEndBeat} for the full
     * argument, including why half a frame is the right tolerance and not an
     * arbitrary epsilon. The drift it absorbs arises on THIS walk too, from a
     * different source: the transport wraps its position in one closed-form
     * step per block while this walk accumulates {@code segStartBeat +
     * segFrames / samplesPerBeat} across the block's loop-split segments, so
     * the next block's entry cursor and this walk's accumulated cursor are
     * the same quantity computed two ways. (Without a loop split the two
     * expressions are bit-identical — the click walk carries no PDC offset —
     * which is why an ordinary linear render continues exactly.)</p>
     */
    private double clickPreviousSegmentEndBeat = Double.NaN;

    /**
     * Same fact for the content/MIDI walk. A separate flag because that walk
     * runs on the PDC-shifted cursor and therefore wraps at a different frame
     * than the click walk within the same block. The staleness argument on
     * {@link #clickLoopWrapPending} applies verbatim: bounded by the
     * positional residue test, never cleared on stop or pause.
     *
     * <p>This is the flag the pause/resume measurement quoted there was
     * actually taken on — see
     * {@code AudioEngineMidiPlaybackTest.loopStartNoteSurvivesAPauseOnTheWrapBoundary}.</p>
     */
    private boolean trackLoopWrapPending;

    /**
     * The event-window END of the previously rendered content segment, or
     * {@link Double#NaN} if this walk has not rendered one yet. RT-thread
     * confined, exactly like {@link #trackLoopWrapPending}.
     *
     * <p>Story 315 review (second round) — this is what makes the half-frame
     * CARRY at an ordinary continuation edge safe. A segment's event window
     * is its frame-ownership interval and ends half a frame before its own
     * exclusive end, so the next segment has to reach back and collect what
     * the previous one declined. Reaching back is legitimate only when there
     * IS a previous segment that declined something: the first block of a
     * render, and any seek, are HARD left edges for exactly the reason a lap
     * start is, and widening there would let a seek landing in the middle of
     * a note re-trigger its note-on. Two DIFFERENT fixtures cover that rule
     * and they are not interchangeable — see the case analysis on the
     * DISCONTINUITY bullet in {@link #renderTracks}. In short:
     * {@code AudioEngineMidiPlaybackTest.oneAndAHalfFrameLoopKeepsTheEventWindowWideningEnabled}
     * rules out widening every non-lap edge unconditionally (1023 note-ons,
     * not 1024), while the guard that decides WHICH edges are continuations
     * is pinned by {@code noteOnLandingInTheLastHalfFrameIsCarriedIntoTheNextSegment},
     * {@code noteOffLandingInTheLastHalfFrameIsCarriedIntoTheNextSegment} and
     * {@code eventExactlyOnABlockBoundaryBelongsToTheBlockThatBeginsThere}.</p>
     *
     * <p>Carrying from the REMEMBERED bound rather than from
     * {@code cursor − half a frame} also makes the two ownership intervals
     * tile with no gap and no overlap by construction, even across a block
     * boundary where the accumulated segment cursor and
     * {@code transport.getPositionInBeats() + renderOffsetBeats} can differ
     * by an ulp. The DECISION to carry is a separate question, taken against
     * {@link #trackPreviousSegmentEndBeat}; the carried VALUE always comes
     * from here, so whichever way that decision goes the partition stays
     * exact.</p>
     *
     * <p><b>The NaN initialisation is load-bearing.</b> Every comparison
     * against NaN is false, so the continuity test at the use site takes its
     * hard-edge branch on the first segment of a fresh render without a
     * separate "have we rendered yet" flag. Do not "clean up" that double
     * comparison into a NaN-unsafe form. The field is deliberately NOT reset
     * on stop, on pause, or when looping is off: the continuity test already
     * bounds a stale value, and a defensive clear here would destroy work the
     * walk still owes — the same mistake the not-playing clear on
     * {@link #clickLoopWrapPending} documents.</p>
     */
    private double trackPreviousWindowEndBeat = Double.NaN;

    /**
     * The previous rendered content segment's own END cursor — its raw
     * {@code segmentEndBeat}, NOT its window end — or {@link Double#NaN} if
     * this walk has not rendered one yet. RT-thread confined.
     *
     * <p>Story 315 review (third round) — this field carries the carry
     * DECISION, and it exists because the obvious alternative is not robust.
     * Testing {@link #trackPreviousWindowEndBeat} positionally, i.e.
     * "does it lie in {@code [origin − half a frame, origin]}?", passes in
     * the ordinary continuation case only by EQUALITY: the remembered bound
     * sits exactly on the lower end of that interval. Within a block that
     * equality is exact by construction ({@code currentBeat} is assigned the
     * previous {@code segmentEndBeat}), but across a block boundary it is
     * not. The block-entry cursor is
     * {@code (transport.getPositionInBeats()) + renderOffsetBeats}, i.e.
     * {@code (a + b) + c}, while the accumulated segment cursor reaches the
     * same place as {@code (a + c) + b}. Floating-point addition is not
     * associative, so with a non-zero PDC {@code renderOffsetBeats} the two
     * can differ by an ulp IN EITHER DIRECTION. Drift upward by one ulp and
     * an equality-only guard fails, the boundary silently degrades to a hard
     * edge, and every event in its last half frame is dropped — for a
     * note-OFF that is a stuck note, which is precisely the bug class this
     * whole change exists to remove. (With {@code renderOffsetBeats == 0} the
     * two expressions are bit-identical, which is why no fixture catches
     * it.)</p>
     *
     * <p>So the test is CONTINUITY, at the resolution the carry itself
     * operates at: {@code |origin − trackPreviousSegmentEndBeat| ×
     * samplesPerBeat < 0.5}. Half a frame is not an arbitrary epsilon, and
     * this codebase is right to distrust thresholds — see the wrap-flag essay
     * on the widening in {@link #renderTracks}, where a threshold genuinely
     * cannot work because the two populations of readings OVERLAP. Here they
     * do not, by eight orders of magnitude:</p>
     * <ul>
     *   <li>ULP DRIFT, which must be treated as continuation. An ulp at beat
     *       1024 is 2⁻⁴² = 2.2737367544323206e-13 beats; at 44.1 kHz /
     *       120 BPM ({@code samplesPerBeat} 22050) that is 5.0e-9 of a frame
     *       — a hundred-millionth of the tolerance.</li>
     *   <li>A GENUINE DISCONTINUITY, which must be treated as a hard edge. A
     *       seek, a transport restart, or the block-entry loop mapping moves
     *       the cursor by frames at least and usually by thousands of them —
     *       orders of magnitude ABOVE the tolerance.</li>
     *   <li>THE GAP BETWEEN THEM is not a grey area but a no-op zone: a
     *       "seek" of less than half a frame lands inside the very frame the
     *       walk was already on, and there carrying and not carrying select
     *       the same frame for every event, so either answer is correct.</li>
     * </ul>
     *
     * <p>NaN is again the "nothing rendered yet" sentinel:
     * {@code Math.abs(NaN − x) < 0.5} is false, so a fresh walk takes the
     * hard-edge branch with no separate flag. And as with every other piece
     * of state on these two walks, the field is deliberately NOT cleared on
     * stop or pause — the continuity test itself is the staleness bound, and
     * a lifecycle clear would destroy work the walk still owes.</p>
     */
    private double trackPreviousSegmentEndBeat = Double.NaN;

    // One-shot warning flag for exceeding return bus cap
    private boolean returnBusCapWarningLogged;

    /**
     * Optional cache of sample-rate-converted clip buffers. When set,
     * {@link #renderSegment} consults the cache for any clip whose
     * {@link AudioClip#getSourceRateMetadata()} reports a native rate
     * different from the session rate, falling back to the raw
     * {@link AudioClip#getAudioData()} when the cache is absent or the
     * rates already match. Story 126.
     */
    private volatile SampleRateConversionCache srcCache;

    /**
     * SRC quality tier used by the cache when it must materialize a new
     * conversion. Defaults to {@link QualityTier#MEDIUM}, matching the
     * persisted default in {@code SettingsModel}.
     */
    private volatile QualityTier srcQualityTier = QualityTier.MEDIUM;

    /**
     * Story 215 — maximum number of physical output channels reported by
     * the active audio backend. When positive, the cue-bus write loop
     * skips any bus whose {@code hardwareOutputIndex * 2 + 1} exceeds
     * this limit, preventing writes to non-existent driver channels.
     * Zero (the default) disables the guard — all writes are attempted,
     * matching the pre-story-215 behavior.
     */
    private volatile int outputChannelCount;

    /**
     * Creates a render pipeline with pre-allocated scratch buffers.
     *
     * @param format    the audio format describing channel count and sample rate
     * @param maxTracks the maximum number of tracks rendered per block
     * @param blockSize the maximum block size in frames
     */
    public RenderPipeline(AudioFormat format, int maxTracks, int blockSize) {
        this.format = Objects.requireNonNull(format, "format must not be null");
        if (maxTracks <= 0) {
            throw new IllegalArgumentException("maxTracks must be positive: " + maxTracks);
        }
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be positive: " + blockSize);
        }
        this.maxTracks = maxTracks;
        int channels = format.channels();
        this.mixBuffer = new float[channels][blockSize];
        this.trackBuffers = new float[maxTracks][channels][blockSize];
        this.returnBuffers = new float[Mixer.MAX_RETURN_BUSES][channels][blockSize];
        this.gainScratch = new float[blockSize];
        // Story 136 — pre-allocated tail for clicks that overflow the
        // current block (e.g. a 20 ms click is 882 samples at 44.1 kHz,
        // which exceeds typical low-latency buffer sizes of 256/512).
        // Sized at one full second of audio, generous enough to hold any
        // realistic click or count-in tail without ever allocating on
        // the audio thread.
        int tailFrames = Math.max(blockSize, (int) format.sampleRate());
        this.clickTail = new float[channels][tailFrames];
    }

    /**
     * Returns the audio format this pipeline was configured with.
     *
     * @return the audio format
     */
    public AudioFormat getFormat() {
        return format;
    }

    /**
     * Installs (or removes) the process-wide
     * {@link SampleRateConversionCache} used to memoize JIT sample-rate
     * conversions of clips whose {@link SourceRateMetadata#nativeRateHz()}
     * differs from the session rate. Story 126.
     *
     * <p>Pass {@code null} to disable just-in-time SRC entirely — the
     * pipeline will then read each clip's raw {@link AudioClip#getAudioData()}
     * verbatim, restoring the legacy "we assume the importer already
     * resampled" behavior.</p>
     *
     * @param cache the cache, or {@code null} to disable JIT SRC
     */
    public void setSampleRateConversionCache(SampleRateConversionCache cache) {
        this.srcCache = cache;
    }

    /**
     * Returns the currently installed sample-rate conversion cache, or
     * {@code null}.
     */
    public SampleRateConversionCache getSampleRateConversionCache() {
        return srcCache;
    }

    /**
     * Sets the SRC quality tier used by the cache when materializing a
     * new conversion. Story 126 — surfaced through the
     * {@code AudioSettingsDialog} "SRC Quality" combo.
     *
     * @param tier quality tier (must not be {@code null})
     */
    public void setSrcQualityTier(QualityTier tier) {
        this.srcQualityTier = Objects.requireNonNull(tier, "tier must not be null");
    }

    /** Returns the SRC quality tier currently in effect. */
    public QualityTier getSrcQualityTier() {
        return srcQualityTier;
    }

    /**
     * Story 215 — sets the number of physical output channels reported
     * by the active audio backend. Used by the cue-bus write loop to
     * skip writes to channels that do not exist on the current device.
     *
     * @param count the total number of output channels ({@code &ge; 0});
     *              zero disables the guard (all writes attempted)
     */
    public void setOutputChannelCount(int count) {
        if (count < 0) {
            throw new IllegalArgumentException(
                    "outputChannelCount must not be negative: " + count);
        }
        this.outputChannelCount = count;
    }

    /** Returns the configured output channel count, or 0 if unset. */
    public int getOutputChannelCount() {
        return outputChannelCount;
    }

    /**
     * Returns the pre-allocated per-track scratch buffers. Package-private
     * so that {@link AudioEngine} can invoke
     * {@link Mixer#renderDirectOutputs(float[][][], float[][], int)} on the
     * per-track buffers after {@link #renderBlock} has populated them.
     *
     * @return the track buffers as {@code [track][channel][frame]}
     */
    float[][][] getTrackBuffers() {
        return trackBuffers;
    }

    /**
     * Renders a single block of audio into {@code outputBuffer}.
     *
     * <p>When the transport, mixer, and track list are non-null and the
     * transport is in {@link TransportState#PLAYING} or
     * {@link TransportState#RECORDING} state, the engine renders clips
     * through the mixer and master effects chain. Otherwise, the
     * {@code inputBuffer} is routed through the master effects chain
     * (pass-through).</p>
     *
     * <p>This method performs zero heap allocations and acquires no locks
     * on the live path — it is safe to invoke from the audio callback
     * thread.</p>
     *
     * @param inputBuffer       the input audio data {@code [channel][frame]}
     *                          (may be {@code null} when rendering offline)
     * @param outputBuffer      the output audio data {@code [channel][frame]}
     * @param numFrames         the number of sample frames to process
     * @param transport         the transport, or {@code null} for pass-through
     * @param mixer             the mixer, or {@code null} for pass-through
     * @param tracks            the tracks, or {@code null} for pass-through
     * @param midiRenderer      the MIDI track renderer, or {@code null}
     * @param masterChain       the master effects chain applied after mixdown
     * @param recordingCallback optional recording callback invoked with the
     *                          captured {@code inputBuffer} (may be {@code null})
     * @param performanceMonitor optional performance monitor (may be {@code null})
     */
    @RealTimeSafe
    public void renderBlock(float[][] inputBuffer,
                            float[][] outputBuffer,
                            int numFrames,
                            Transport transport,
                            Mixer mixer,
                            List<Track> tracks,
                            MidiTrackRenderer midiRenderer,
                            EffectsChain masterChain,
                            AudioEngine.RecordingCallback recordingCallback,
                            PerformanceMonitor performanceMonitor) {
        renderBlock(inputBuffer, outputBuffer, numFrames, transport, mixer,
                tracks, midiRenderer, masterChain, recordingCallback,
                performanceMonitor, null);
    }

    /**
     * Renders a single block of audio into {@code outputBuffer}, optionally
     * feeding per-track CPU measurements to a {@link TrackCpuBudgetEnforcer}.
     *
     * <p>This overload adds per-track CPU timing around the mixer's insert
     * processing. When {@code cpuBudgetEnforcer} is non-null, each track's
     * mixer processing time is measured and reported to the enforcer, which
     * evaluates per-track and master budgets and publishes degradation or
     * restoration events.</p>
     *
     * <p><strong>RT-safety note:</strong> When {@code cpuBudgetEnforcer} is
     * non-null, this method acquires a {@link java.util.concurrent.locks.ReentrantLock}
     * inside the enforcer for each {@code recordTrackCpu} call and for the
     * {@code evaluateMasterBudget} cascade. The enforcer pre-allocates its
     * internal snapshot/sort buffers to minimize GC pressure, but the lock
     * acquisitions mean this path is not fully lock-free. When the enforcer
     * is {@code null}, the method remains allocation-free and lock-free.</p>
     *
     * @param inputBuffer        the input audio data {@code [channel][frame]}
     *                           (may be {@code null} when rendering offline)
     * @param outputBuffer       the output audio data {@code [channel][frame]}
     * @param numFrames          the number of sample frames to process
     * @param transport          the transport, or {@code null} for pass-through
     * @param mixer              the mixer, or {@code null} for pass-through
     * @param tracks             the tracks, or {@code null} for pass-through
     * @param midiRenderer       the MIDI track renderer, or {@code null}
     * @param masterChain        the master effects chain applied after mixdown
     * @param recordingCallback  optional recording callback (may be {@code null})
     * @param performanceMonitor optional performance monitor (may be {@code null})
     * @param cpuBudgetEnforcer  optional per-track CPU budget enforcer
     *                           (may be {@code null}); when non-null, per-track
     *                           timing and lock acquisition occur on this path
     */
    @RealTimeSafe
    public void renderBlock(float[][] inputBuffer,
                            float[][] outputBuffer,
                            int numFrames,
                            Transport transport,
                            Mixer mixer,
                            List<Track> tracks,
                            MidiTrackRenderer midiRenderer,
                            EffectsChain masterChain,
                            AudioEngine.RecordingCallback recordingCallback,
                            PerformanceMonitor performanceMonitor,
                            TrackCpuBudgetEnforcer cpuBudgetEnforcer) {
        renderBlock(inputBuffer, outputBuffer, numFrames, transport, mixer, tracks,
                midiRenderer, masterChain, recordingCallback, performanceMonitor,
                cpuBudgetEnforcer, null, null, null, null);
    }

    /**
     * Story 136 — overload that additionally invokes
     * {@link MetronomeSideOutputRouter#route(Metronome, float[][],
     * AudioBackend, CueBusManager) router.route(...)} on every scheduled
     * beat (and subdivision) that lands inside this block, summing the
     * returned {@link RoutedClick#mainMixBuffer() main-mix buffer} into
     * the engine's mix buffer at the sample-accurate offset, and writing
     * the returned {@link RoutedClick#sideOutputBuffer() side-output buffer}
     * and each {@link RoutedClick#cueBusBuffers() cue-bus contribution} to
     * the matching hardware channels on {@code backend} via
     * {@link AudioBackend#writeToChannel(int, float[])} with leading-zero
     * alignment so every destination is sample-accurate within the block.
     *
     * <p>When {@code metronome}, {@code router}, or {@code transport} is
     * {@code null}, or when the transport is not playing/recording, no
     * click is generated and this overload is bit-identical to the
     * 11-arg one above.</p>
     *
     * <h4>Allocation note</h4>
     * <p>This overload is <em>not</em> allocation-free when clicks are
     * generated: {@link Metronome#generateClick(boolean)} and
     * {@link MetronomeSideOutputRouter#route} each allocate short-lived
     * buffers, and the aligned hardware-write buffers are freshly
     * allocated per click per destination. The allocations are bounded
     * (typically 0–2 per block at musical tempos) and short-lived.</p>
     *
     * @param metronome      the metronome producing the click samples; may be
     *                       {@code null} to skip click generation
     * @param router         the side-output router governing routing; may be
     *                       {@code null} to skip click generation
     * @param cueBusManager  the cue bus manager used to look up each
     *                       contributing cue bus's hardware output pair; may
     *                       be {@code null} to drop cue-bus contributions
     * @param backend        the audio backend used for the side output and
     *                       cue-bus hardware writes; may be {@code null} —
     *                       cue-bus and side-output writes are then dropped
     */
    public void renderBlock(float[][] inputBuffer,
                            float[][] outputBuffer,
                            int numFrames,
                            Transport transport,
                            Mixer mixer,
                            List<Track> tracks,
                            MidiTrackRenderer midiRenderer,
                            EffectsChain masterChain,
                            AudioEngine.RecordingCallback recordingCallback,
                            PerformanceMonitor performanceMonitor,
                            TrackCpuBudgetEnforcer cpuBudgetEnforcer,
                            Metronome metronome,
                            MetronomeSideOutputRouter router,
                            CueBusManager cueBusManager,
                            AudioBackend backend) {
        Objects.requireNonNull(outputBuffer, "outputBuffer must not be null");
        Objects.requireNonNull(masterChain, "masterChain must not be null");

        long startNanos = (performanceMonitor != null) ? System.nanoTime() : 0L;

        // Clear the mix buffer
        for (float[] channel : mixBuffer) {
            Arrays.fill(channel, 0, numFrames, 0.0f);
        }

        boolean playbackActive = transport != null
                && mixer != null
                && tracks != null
                && (transport.getState() == TransportState.PLAYING
                    || transport.getState() == TransportState.RECORDING);

        if (playbackActive) {
            int trackCount = Math.min(tracks.size(), maxTracks);

            // Compute the render offset so that PDC delays align output with
            // the displayed transport position. We render audio slightly
            // ahead of the transport cursor; the compensation delays then
            // push the audio back, so beat-1 arrives at the output exactly
            // on time.
            int systemLatency = mixer.getSystemLatencySamples();
            double samplesPerBeatForOffset =
                    format.sampleRate() * 60.0 / transport.getTempo();
            double renderOffsetBeats = systemLatency / samplesPerBeatForOffset;

            // Render clip audio (or synthesized MIDI) for each track
            renderTracks(tracks, trackCount, transport, renderOffsetBeats,
                    midiRenderer, numFrames);

            // Apply automation lane values to mixer channel parameters
            List<MixerChannel> mixerChannels = mixer.getChannels();
            applyAutomation(tracks, trackCount, mixerChannels, transport, mixer);

            // Warn once if the mixer has more return buses than pre-allocated
            if (!returnBusCapWarningLogged
                    && mixer.getReturnBusCount() > Mixer.MAX_RETURN_BUSES) {
                returnBusCapWarningLogged = true;
                final Mixer m = mixer;
                LOG.log(Level.WARNING,
                        () -> "Mixer has " + m.getReturnBusCount()
                                + " return buses but only " + Mixer.MAX_RETURN_BUSES
                                + " are supported; extra buses will not receive send audio");
            }

            // Per-track CPU timing for budget enforcement. The enforcer
            // measures around each track's mixer processing (insert chain
            // application, delay compensation, and summing). When not
            // present, the mixer processes all channels in one call with
            // no instrumentation overhead.
            if (cpuBudgetEnforcer != null) {
                mixer.mixDownInstrumented(trackBuffers, mixBuffer, returnBuffers,
                        numFrames, tracks, cpuBudgetEnforcer);
            } else {
                // Mix through the mixer into the mix buffer, routing sends to
                // return buses which are summed into the main output.
                mixer.mixDown(trackBuffers, mixBuffer, returnBuffers, numFrames);
            }
        } else if (inputBuffer != null) {
            // Fallback: copy input into the mix buffer (pass-through)
            int channels = Math.min(inputBuffer.length, mixBuffer.length);
            for (int ch = 0; ch < channels; ch++) {
                System.arraycopy(inputBuffer[ch], 0, mixBuffer[ch], 0, numFrames);
            }
        }

        // Story 136 — schedule per-beat (and per-subdivision) metronome
        // clicks that fall inside this block, route them through the
        // side-output router, and sum the returned main-mix contribution
        // into the mix buffer at the sample-accurate offset. The router
        // also writes the side output and we write each cue-bus
        // contribution to the bus's hardware output stereo pair so the
        // drummer's cue mix is audibly fed the click. This must run
        // before the master chain (so the click flows through master
        // inserts) and BEFORE the transport position is advanced
        // further down (so beat scheduling sees the start-of-block
        // position).
        if (playbackActive && metronome != null && router != null) {
            mixMetronomeClicks(transport, metronome, router,
                    cueBusManager, backend, numFrames);
        } else {
            // Clear any pending click-tail so stray clicks do not leak
            // into the first block when playback resumes or when the
            // metronome/router is disconnected.
            clearClickTail();
        }

        // Notify recording callback with the captured input
        if (recordingCallback != null && inputBuffer != null) {
            recordingCallback.onAudioCaptured(inputBuffer, numFrames);
        }

        // Process through the master effects chain
        masterChain.process(mixBuffer, outputBuffer, numFrames);

        // Write non-master channels to their direct hardware outputs.
        // This runs AFTER the master chain so that its overwrite of
        // outputBuffer (channels 0..N) does not clobber direct-output data
        // on higher channels.
        if (playbackActive) {
            mixer.renderDirectOutputs(trackBuffers, outputBuffer, numFrames);
        }

        // Advance the transport position
        if (playbackActive) {
            double samplesPerBeat = format.sampleRate() * 60.0 / transport.getTempo();
            double deltaBeats = numFrames / samplesPerBeat;
            transport.advancePosition(deltaBeats);
        }

        // Record processing time
        if (performanceMonitor != null) {
            long elapsedNanos = System.nanoTime() - startNanos;
            performanceMonitor.recordProcessingTime(elapsedNanos);
        }

        // Evaluate master budget after all per-track recordings for this block
        if (cpuBudgetEnforcer != null && playbackActive) {
            cpuBudgetEnforcer.evaluateMasterBudget();
        }
    }

    /**
     * Story 136 — schedules every metronome click whose beat (or
     * subdivision) position falls inside this block, generates the
     * click sample via {@link Metronome#generateClick(boolean)}, and
     * delegates routing to
     * {@link MetronomeSideOutputRouter#route(Metronome, float[][],
     * AudioBackend, CueBusManager)}. The returned
     * {@link RoutedClick#mainMixBuffer() main-mix buffer} is summed
     * into {@link #mixBuffer} at the sample-accurate offset; the
     * returned {@link RoutedClick#sideOutputBuffer() side-output buffer}
     * and each {@link RoutedClick#cueBusBuffers() cue-bus contribution}
     * are written by the pipeline to the appropriate hardware channels
     * via {@link AudioBackend#writeToChannel(int, float[])} with
     * leading-zero alignment so every destination is sample-accurate
     * within the block.
     *
     * <p>All three destinations share the same source buffer, so
     * timing across them is inherently sample-accurate.</p>
     *
     * <h4>Allocation note</h4>
     * <p>Each invocation of {@link Metronome#generateClick(boolean)}
     * and {@link MetronomeSideOutputRouter#route} allocates the click
     * buffer and per-bus mono buffer respectively. These allocations
     * are short-lived and bounded (one per scheduled subdivision per
     * block — typically 0–2 per block at musical tempos). This method
     * is therefore <em>not</em> allocation-free, unlike the rest of
     * the render pipeline's live path.</p>
     */
    private void mixMetronomeClicks(Transport transport,
                                    Metronome metronome,
                                    MetronomeSideOutputRouter router,
                                    CueBusManager cueBusManager,
                                    AudioBackend backend,
                                    int numFrames) {
        // If the metronome is disabled, clear any pending click-tail
        // so disabling immediately silences every destination — no
        // stray tail samples leak into subsequent blocks.
        if (!metronome.isEnabled()) {
            clearClickTail();
            return;
        }

        // Drain any pending click-tail left over from the previous
        // block so a click that overflowed plays gap-free.
        if (clickTailFrames > 0) {
            int copy = Math.min(clickTailFrames, numFrames);
            int channels = Math.min(clickTail.length, mixBuffer.length);
            int remaining = clickTailFrames - copy;
            for (int ch = 0; ch < channels; ch++) {
                float[] src = clickTail[ch];
                float[] dst = mixBuffer[ch];
                for (int s = 0; s < copy; s++) {
                    dst[s] += src[s];
                }
                if (remaining > 0) {
                    System.arraycopy(src, copy, src, 0, remaining);
                }
                java.util.Arrays.fill(src, remaining, remaining + copy, 0.0f);
            }
            clickTailFrames = remaining;
        }

        double samplesPerBeat = format.sampleRate() * 60.0 / transport.getTempo();
        if (samplesPerBeat <= 0.0) {
            return;
        }
        // Half a frame, in beats — the shift that turns a segment's cursor
        // range into its FRAME-OWNERSHIP interval at an ordinary continuation
        // edge. A per-block constant, hoisted here beside the other per-block
        // quantities exactly as renderTracks hoists its own; see the
        // scheduling-window computation in the segment loop below.
        double halfFrameBeats = 0.5 / samplesPerBeat;
        Subdivision subdivision = metronome.getSubdivision();
        int clicksPerBeat = subdivision.getClicksPerBeat();
        if (clicksPerBeat <= 0) {
            return;
        }
        int beatsPerBar = transport.getTimeSignatureNumerator();
        if (beatsPerBar <= 0) {
            beatsPerBar = 4;
        }

        // Story 315 — split this block at the loop boundary the same way
        // renderTracks does, so click scheduling is loop-aware: no click is
        // scheduled for a beat at or beyond the loop end, and the wrapped-in
        // click at the loop start fires within this block at its exact
        // post-wrap sample offset. The loop trio is read once through the
        // immutable LoopWindow record so a concurrent FX-thread loop edit
        // cannot tear it mid-block. (A click's decaying body may still ring
        // across the wrap boundary within the block/tail — that is the click's
        // natural ring-out, not timeline content bleeding past the loop end.)
        //
        // Known divergence: clicks are scheduled against the raw transport
        // cursor (getPositionInBeats()), while renderTracks renders
        // PDC-shifted content from getPositionInBeats() + renderOffsetBeats.
        // The two intra-block split frames therefore differ by the render
        // offset whenever plugin latency is non-zero — making the click grid
        // follow the PDC-compensated cursor would change audible click
        // timing and is deliberately not done here. Both cursors are,
        // however, loop-mapped before use (below and in renderTracks), so
        // neither path ever schedules a click grid position or a MIDI note-on
        // from at/beyond the loop end. There are exactly TWO deliberate
        // exceptions, both of them events AT the loop end rather than beyond
        // it:
        //  • audio-clip RANGES — renderSegment's own endBeat local is
        //    deliberately NOT capped at the loop end, so a clip's raw range
        //    runs up to one frame past it and the frame straddling the
        //    boundary is fully written (see its javadoc);
        //  • note-OFFS sitting exactly at loopEnd, which are scheduled on
        //    every lap. MidiTrackRenderer admits a note end with
        //    "noteEndBeat > windowStartBeat && noteEndBeat <= windowEndBeat"
        //    — inclusive on the right — and the value it receives as
        //    windowEndBeat is eventWindowEndBeat, which renderTracks pins to
        //    loopEnd on the segment that reaches it (the segmentEndsLap
        //    branch) and passes into renderSegment. (It is NOT
        //    renderSegment's own endBeat local, which is the uncapped
        //    audio-clip range of the first bullet.) So the final pre-wrap
        //    segment releases a note that ends exactly as the lap ends. That
        //    is intended: the note must be released, and the post-wrap window
        //    cannot admit it a second time (its endBeat is a few frames into
        //    the new lap, four beats short of the note end). Pinned by
        //    AudioEngineMidiPlaybackTest#noteEndingExactlyOnTheLoopEndIsReleasedOncePerLapWhileLooping.
        // Note-ONS at loopEnd are NOT an exception — they are excluded, and
        // that exclusion is pinned by noteExactlyAtTheLoopEndNeverFiresWhileLooping.
        Transport.LoopWindow loop = transport.getLoopWindow();
        boolean loopActive = loop.enabled() && loop.endInBeats() > loop.startInBeats();
        double loopLength = loop.endInBeats() - loop.startInBeats(); // > 0 whenever loopActive
        // Story 315 review — DEFENSIVE AND UNPINNED. No test covers this
        // clear, and none can: it is redundant given the code below it, so
        // deleting it changes no observable and no mutation of it can be
        // caught. Kept only as a local guarantee.
        //
        // Why it is redundant. Nothing returns between here and the segment
        // loop (every early return above is before the LoopWindow read), the
        // loop runs at least once for any numFrames >= 1, and its body ends
        // with an UNCONDITIONAL "clickLoopWrapPending = false" after
        // scheduleSegmentClicks — so the flag is false at method exit with or
        // without this line. Meanwhile the only read of the flag inside the
        // block is the widening, whose condition is conjoined with
        // loopActive, and the only write that sets it is the in-block wrap,
        // also guarded by loopActive; with looping off neither can act on a
        // stale value. The single residual difference is a numFrames == 0
        // callback, where the segment loop never runs — not a case any
        // backend produces.
        if (!loopActive) {
            clickLoopWrapPending = false;
        }
        double segStartBeat = transport.getPositionInBeats();
        // Story 315 review — same loop-mapping as renderTracks, applied to
        // the raw cursor: setPositionInBeats permits a target at/past the
        // loop end while looping, and advancePosition wraps only at the NEXT
        // block boundary, so an unmapped start would schedule a full block
        // of out-of-loop clicks. Mirrors advancePosition's closed-form wrap.
        if (loopActive && segStartBeat >= loop.endInBeats()) {
            segStartBeat = loop.startInBeats() + ((segStartBeat - loop.endInBeats()) % loopLength);
            // This mapping lands the cursor on a genuine fractional position
            // that no lap owes a widened window for, so clear the flag. It
            // fires for two reasons, and neither is a quantization residue:
            // a seek past the loop end, or the FX thread shrinking loopEnd
            // below the current position (no seek involved). Either way the
            // cursor's offset into the lap is arbitrary, not the sub-frame
            // overshoot of a whole-frame split.
            //
            // The asymmetry with renderTracks is deliberate and must stay:
            // this walk reads the RAW cursor, which the transport itself
            // already wraps, so the only ways for it to arrive at/past the
            // loop end are the two above. renderTracks walks the PDC-shifted
            // cursor, and its mapping ALSO fires as the continuation of a
            // quantized wrap — the shifted cursor crosses the loop end while
            // the raw one is still inside — so it deliberately does NOT clear
            // there, and "restoring symmetry" by adding the clear back drops
            // the loop-start event on every block-boundary-aligned wrap.
            //
            // Pinned by MetronomeLoopSchedulingEngineTest#
            // seekPastTheLoopEndAfterABlockBoundaryWrapAbandonsTheOwedLoopStartClick,
            // which is the only fixture that can see this line: the others
            // either never seek, or seek in block 0 where the flag is still
            // false, and deleting the clear leaves their rendered output
            // byte-identical. Delete it and a click reappears at the seek
            // landing — the abandoned lap's loop-start click, recovered one
            // frame into a lap the seek walked away from.
            clickLoopWrapPending = false;
        }
        int framesProcessed = 0;

        while (framesProcessed < numFrames) {
            int segFrames = numFrames - framesProcessed;
            if (loopActive && segStartBeat < loop.endInBeats()) {
                double beatsUntilLoopEnd = loop.endInBeats() - segStartBeat;
                // Story 315 review — shave an epsilon off the ceil and floor
                // the result at one frame. Same arithmetic as renderTracks;
                // the reasoning is recorded in full there, and the figures
                // below were measured on THIS walk.
                //
                // THE EPSILON. A product that ought to be a whole number of
                // frames comes out a hair ABOVE it, so ceil returns k + 1 and
                // the segment overshoots the loop end by a frame. Simulated
                // on the fixture this floor is pinned on
                // (MetronomeLoopSchedulingEngineTest#
                // loopStartClickFiresOnlyOncePerLapWhenTheWrapResidueIsAlreadyConsumed
                // — 48 kHz, 120 BPM, samplesPerBeat 24000, loop
                // [0.0, 0.328125), 64-frame blocks, 2708 blocks), 1385 of the
                // products this walk computes land within 1e-6 above a
                // positive integer; the largest such excess is (exact double,
                // printed in full)
                //   5333.0000000000072759576141834259033203125
                // i.e. k + 7.2759576141834259e-12.
                // The often-quoted 5.56e-13 is NOT a product error — it is
                // the excess of a resulting RESIDUE, and it was measured on
                // the identical arithmetic at 44.1 kHz / 72 BPM with loop
                // [0.0, 0.25) and 1024-frame blocks, where the wrap after the
                // over-ceiled product leaves the cursor
                //   1.0000000000005559996907322783954441547393798828125
                // frames into the lap — just OUTSIDE the "< 1.0" widening
                // below, so that lap's loop-start click is dropped. Product
                // excess and residue excess are different quantities; do not
                // read either figure as the other.
                //
                // THE FLOOR is load-bearing, not cosmetic, and this fixture
                // exercises it directly: with the epsilon applied, the raw
                // ceil evaluates to 0 exactly 17 times across those 2708
                // blocks. The old "> 0" guard skipped the clamp on each of
                // them, so the segment ran to the end of its block from a
                // cursor a fraction of a frame short of the loop end and
                // overshot the loop end by the whole remainder of the block.
                //
                // The FIGURE "286 of 300 laps wrong in a sweep" was measured
                // offline by simulation and is NOT reproducible from any test
                // in this repo — that disclaimer applies to the figure only.
                // The MECHANISM is pinned right here: putting the old "> 0"
                // guard back in place of this floor fails
                // loopStartClickFiresOnlyOncePerLapWhenTheWrapResidueIsAlreadyConsumed,
                // whose onset list drops from 23 lap-start clicks to 6 — 17
                // laps lose their click, the same count as the 17 zero
                // ceils measured above (both figures observed on JDK 26; the
                // one-to-one correspondence between them is inferred from the
                // mechanism, not checked lap by lap). Dropping the epsilon
                // instead (keeping the
                // floor) fails that same test plus
                // AudioEngineMidiPlaybackTest#
                // noteAtTheLoopStartFiresOnEveryLapWhenTheWrapResidueRoundsUp.
                int framesUntilLoopEnd =
                        Math.max(1, (int) Math.ceil(beatsUntilLoopEnd * samplesPerBeat - 1e-9));
                segFrames = Math.min(segFrames, framesUntilLoopEnd);
            }
            double segEndBeat = segStartBeat + segFrames / samplesPerBeat;
            // Subdivision indices [firstIdx, lastIdxExclusive) whose
            // beat-positions land inside this segment. Inclusive on the left
            // so a segment that begins exactly on a beat fires its click at
            // its first frame (sample-accurate downbeat / loop restart);
            // capped at the loop end (when this segment runs up to it) so a
            // grid position at or past the loop end never schedules from the
            // pre-wrap segment — that beat belongs to the wrapped timeline and
            // is emitted by the follow-up segment starting at the loop start.
            // (With the initial cursor loop-mapped above, a loop-active
            // segment always starts inside the loop; the linear branch covers
            // loop-inactive blocks only.)
            //
            // Story 315 review (third round) — the right edge is NOT this
            // segment's cursor end, for the same reason renderTracks' event
            // window is not: admission is a test in BEATS while
            // scheduleSegmentClicks maps in FRAMES, with
            // round((beatPos − segStartBeat) × samplesPerBeat), and the two
            // domains disagree over the last half frame of EVERY segment.
            // A grid position in (N − 0.5, N] frames was admitted here and
            // rounded to N, outside the segment's addressable [0, N − 1], so
            // the clamp below had to drag it back to N − 1 — one sample
            // early, systematically. Copilot found this in the MIDI walk; the
            // click walk has it in the identical shape, and the two walks are
            // required to read alike.
            //
            // So the window handed to scheduleSegmentClicks is this segment's
            // FRAME-OWNERSHIP interval: the set of beats whose nearest frame
            // lies in [0, N − 1]. At an ordinary continuation edge that is
            // the cursor end shifted back half a frame; the straggler is then
            // owned by the NEXT segment, whose gridStartBeat reaches back to
            // collect it (below), and sounds on that segment's frame 0 — the
            // frame the rounding always said it belonged on. The click is
            // CARRIED across the boundary, not re-quantized.
            //
            // Verified branch by branch against the value this expression
            // produced before the change; only ONE of them moves, and it is
            // exactly the defect:
            //  • segment ENDS A LAP, ordinary looping case (segStartBeat
            //    inside the loop, span clamped to the loop end): capped at
            //    loop.endInBeats(), as Math.min did. UNCHANGED — a lap end is
            //    a HARD edge, because the pre-wrap segment's last frame is
            //    the only frame available to a grid position sitting at the
            //    loop end and there is no next segment in the lap to carry it
            //    into;
            //  • segment ENDS A LAP from the binade corner (segStartBeat
            //    already at or past loopEnd, so the span above was NOT
            //    clamped): keeps its uncapped segEndBeat verbatim, as the
            //    false branch of the old conditional did. UNCHANGED, and
            //    deliberately so — that corner is documented on
            //    renderTracks' loopSplitActive and is a non-goal here;
            //  • ordinary CONTINUATION, looping or not: segEndBeat − half a
            //    frame. This is the only branch whose value changes, and the
            //    change is the fix.
            // segStartBeat = segEndBeat at the bottom of this loop already
            // gives exact tiling within a block, so no gap opens between the
            // shifted right edge and the next segment's carried-in left edge.
            boolean segmentEndsLap = loopActive && segEndBeat >= loop.endInBeats();
            double schedulingEndBeat;
            if (segmentEndsLap) {
                schedulingEndBeat = (segStartBeat < loop.endInBeats())
                        ? loop.endInBeats()
                        : segEndBeat;
            } else {
                schedulingEndBeat = segEndBeat - halfFrameBeats;
            }
            // Story 315 review — the pre-wrap segment is clamped to WHOLE
            // frames, so a loop end that is not sample-aligned is overshot
            // by δ ∈ (0, 1) frame and the wrap leaves the cursor δ frames
            // PAST the loop start. Walking the grid from that fractional
            // cursor makes ceil() step over the grid position exactly AT
            // loopStart, so the wrapped-in downbeat is silently dropped on
            // every lap whose length is not a whole number of frames — the
            // ordinary case (a 4-beat loop at 44.1 kHz / 128 BPM drops it on
            // every other lap). Keep the fractional cursor for the
            // sample-offset math — it stays sub-frame accurate and composes
            // with advancePosition — but widen the grid window down to the
            // loop start whenever this segment begins inside that sub-frame
            // residue, so the loop-start click is emitted exactly once, at
            // the quantized wrap frame.
            //
            // The widening is gated on BOTH a wrap flag that survives the
            // block boundary AND the positional residue test, and each does a
            // job the other cannot.
            //
            // The FLAG makes the widening fire exactly once per lap. A
            // threshold cannot, because the two populations of readings
            // OVERLAP — the largest genuine residue sits ABOVE the smallest
            // already-consumed reading, so any cut-off placed between them
            // is on the wrong side of one of the two:
            //  • largest GENUINE residue — the wrap overshot and this lap's
            //    first frame is still unscheduled, so the widening MUST
            //    fire: 0.99999999999994320, at 32768 Hz / 128 BPM
            //    (samplesPerBeat = 15360), loop [0.0, 0.25) beats,
            //    128-frame blocks;
            //  • smallest CONSUMED reading — this lap's first frame was
            //    ALREADY scheduled, so the widening MUST NOT fire:
            //    0.99999999999988990, at 48000 Hz / 120 BPM
            //    (samplesPerBeat = 24000), loop [0.0, 0.328125) beats,
            //    64-frame blocks.
            // 0.99999999999994320 > 0.99999999999988990, so a cut-off that
            // admits every genuine residue also admits that consumed reading
            // and the lap's loop-start click fires a second time one frame
            // later; a cut-off that rejects the consumed reading also
            // rejects a genuine residue and that lap loses its click.
            // Provenance: both figures were measured offline by simulating
            // this walk across a sweep of sample rates, tempos, block sizes
            // and loop lengths. They are NOT reproducible from any test in
            // this repo. The flag is set at the
            // in-block wrap and cleared by the first segment that schedules
            // afterwards — which may be in the NEXT block, because a
            // quantized wrap can land exactly on the block boundary and leave
            // the residue where the wrap itself is invisible to this walk.
            //
            // The RESIDUE TEST is retained as a bound on a STALE flag: after
            // a seek to a position inside the loop no mapping fires, so a
            // flag set before the seek can survive it. The residue test
            // confines any such stale widening to a cursor already within one
            // frame of the loop start, where emitting the loop-start click is
            // harmless.
            //
            // Loops shorter than one frame are excluded: they cannot
            // express one downbeat per lap at frame resolution — every
            // segment would be a fresh wrap, and a grid-aligned sub-sample
            // loop start would then click on every single frame.
            //
            // The 1.0 is pinned from BOTH sides, so it cannot drift:
            // subFrameLoopClicksOnceNotOnEveryFrame uses a 0.3-frame loop
            // that must be EXCLUDED (ruling out any threshold <= 0.3), and
            // oneAndAHalfFrameLoopKeepsTheGridWideningEnabled uses a
            // 1.5-frame loop that must be INCLUDED (ruling out any threshold
            // > 1.5). Both live in MetronomeLoopSchedulingEngineTest.
            //
            // Be precise about what that guard does and does not buy.
            // Measured on this walk at 32768 Hz / 120 BPM over 1536 frames,
            // with the loop start on a quarter-note grid position:
            //  • loop length 0.3 frame — not an exact binary fraction, so
            //    the modulo residue drifts and never returns to the loop
            //    start: the rendered output is EXACTLY one click at frame 0
            //    with the guard, and one click started on every one of the
            //    1536 frames without it. Here the guard is load-bearing, and
            //    MetronomeLoopSchedulingEngineTest pins it.
            //  • loop length 0.25 frame — a length that DIVIDES a frame
            //    exactly: the modulo wrap puts the cursor back exactly ON
            //    loopStart every frame, so ceil() admits the loop-start grid
            //    index through the ordinary (un-widened) walk with no
            //    widening involved. One click per frame — 1536 in 1536
            //    frames — WITH the guard AND without it. The guard does not
            //    rescue that case and does not claim to: it is a
            //    pre-existing pathology of sub-frame loops, not something
            //    this widening introduced. (0.5 frame behaves identically.
            //    Other exact binary fractions return to the loop start
            //    periodically rather than every frame — 0.75 frame every
            //    third — a count taken on the content walk, whose events are
            //    directly countable; see renderTracks.)
            // This segment BEGINS a lap iff the previous one ended at the loop
            // end — the wrap that set the flag. A lap start is a HARD left
            // edge: nothing below the loop start may leak in, so nothing is
            // carried into it and its window is not shifted back.
            boolean segmentBeginsLap = clickLoopWrapPending && loopActive;
            double gridStartBeat = segStartBeat;
            if (segmentBeginsLap && loopLength * samplesPerBeat >= 1.0) {
                double beatsIntoLoop = segStartBeat - loop.startInBeats();
                if (beatsIntoLoop > 0.0 && beatsIntoLoop * samplesPerBeat < 1.0) {
                    gridStartBeat = loop.startInBeats();
                }
            }
            // The carry-in half of the partition: at an ordinary continuation
            // edge this segment collects the grid position the previous one
            // declined, by beginning at the very bound the previous one
            // ended on. Using that remembered double — rather than
            // recomputing segStartBeat − halfFrameBeats — is what makes the
            // two windows abut with no gap and no overlap.
            //
            // The DECISION is a continuity test against the previous
            // segment's own end cursor, at the resolution the carry operates
            // at: half a frame. It is not an arbitrary epsilon and it is not
            // the equality-in-disguise a positional bound on
            // clickPreviousWindowEndBeat would be — see
            // trackPreviousSegmentEndBeat for the full argument. On this walk
            // the drift it absorbs comes from the transport wrapping in one
            // closed-form step per block while this loop accumulates
            // segStartBeat + segFrames / samplesPerBeat across the block's
            // segments; ulp drift is ~1e-13 beats (about 5e-9 of a frame)
            // while any genuine discontinuity — a seek, a restart, the
            // block-entry loop mapping — moves the cursor by whole frames.
            //
            // NaN is the "nothing scheduled yet" sentinel: every comparison
            // against NaN is false, Math.abs(NaN − x) < 0.5 included, so the
            // first segment of a fresh render is a hard edge with no separate
            // flag. Do not rewrite this into a NaN-unsafe form.
            boolean continuesPreviousSegment =
                    Math.abs(segStartBeat - clickPreviousSegmentEndBeat) * samplesPerBeat < 0.5;
            if (!segmentBeginsLap && continuesPreviousSegment) {
                gridStartBeat = clickPreviousWindowEndBeat;
            }
            long firstIdx = (long) Math.ceil(gridStartBeat * clicksPerBeat - 1e-9);
            long lastIdxExclusive = (long) Math.ceil(schedulingEndBeat * clicksPerBeat - 1e-9);

            scheduleSegmentClicks(metronome, router, cueBusManager, backend,
                    numFrames, samplesPerBeat, clicksPerBeat, beatsPerBar,
                    segStartBeat, framesProcessed, segFrames, firstIdx, lastIdxExclusive);
            // This segment owns its lap's first frame now, whether or not the
            // widening actually fired (a frame-aligned wrap leaves no residue
            // and needs none), so no later segment may widen for the same lap.
            clickLoopWrapPending = false;
            // Hand this segment's right edge to the next one as its carry-in
            // bound, so the two ownership intervals abut exactly, and its raw
            // end cursor as the continuity reference that decides whether the
            // next segment may reach back for it at all. Both are recorded
            // BEFORE the modulo wrap below, so they describe this segment's
            // own timeline position; a segment that follows a wrap sets
            // segmentBeginsLap and takes the hard edge regardless.
            clickPreviousWindowEndBeat = schedulingEndBeat;
            clickPreviousSegmentEndBeat = segEndBeat;

            framesProcessed += segFrames;
            segStartBeat = segEndBeat;
            // Story 315 review — wrap by modulo, not plain subtraction: the
            // segment is clamped to whole frames, so a loop shorter than one
            // frame (a valid sub-sample loop) is overshot by more than its
            // own length and a single subtraction would leave the cursor at
            // or past the loop end — the split guard above then never fires
            // again and the rest of the block schedules linearly from outside
            // the loop. Modulo keeps the cursor in [loopStart, loopEnd) for
            // any overshoot and composes with advancePosition's closed form.
            if (loopActive && segStartBeat >= loop.endInBeats()) {
                segStartBeat = loop.startInBeats() + ((segStartBeat - loop.endInBeats()) % loopLength);
                clickLoopWrapPending = true;
            }
        }
    }

    /**
     * Story 315 — schedules the metronome clicks of one loop-split segment:
     * every subdivision index in {@code [firstIdx, lastIdxExclusive)} is
     * generated, routed, and summed/written at the sample-accurate offset
     * {@code segFrameOffset + round((beatPos − segStartBeat) × samplesPerBeat)},
     * clamped into this segment's own frame span
     * {@code [segFrameOffset, segFrameOffset + segFrames)} so that every index
     * the beat window admits sounds exactly once, inside the frames its own
     * segment owns. The click body is clamped to the block (not the segment)
     * and any overflow is parked in the click tail, exactly as before the
     * loop-aware split — see {@link #mixMetronomeClicks} for the routing
     * contract.
     *
     * <p>{@code segStartBeat} is the sample-offset ORIGIN, not the admission
     * window: the caller passes the index range separately, derived from a
     * window that may begin below this origin (a lap-start widening, or the
     * half-frame carry-in from the previous segment) and that ends half a
     * frame below the segment's cursor end at an ordinary continuation edge.
     * The clamp is therefore not the general-case corrector it once was —
     * {@code mixMetronomeClicks} now hands down the segment's
     * frame-ownership interval, so a position in the last half frame is
     * carried into the next segment instead of being pulled back a sample.
     * The LOWER clamp remains load-bearing for the widened lap-start
     * position; the upper one is reachable only at a lap end and on
     * floating-point residue. The clamp comment below carries the detail.</p>
     */
    private void scheduleSegmentClicks(Metronome metronome,
                                       MetronomeSideOutputRouter router,
                                       CueBusManager cueBusManager,
                                       AudioBackend backend,
                                       int numFrames,
                                       double samplesPerBeat,
                                       int clicksPerBeat,
                                       int beatsPerBar,
                                       double segStartBeat,
                                       int segFrameOffset,
                                       int segFrames,
                                       long firstIdx,
                                       long lastIdxExclusive) {
        for (long idx = firstIdx; idx < lastIdxExclusive; idx++) {
            double beatPos = idx / (double) clicksPerBeat;
            int sampleOffset = segFrameOffset
                    + (int) Math.round((beatPos - segStartBeat) * samplesPerBeat);
            // Story 315 review — clamp into THIS segment's own frame span.
            // Nearest-frame rounding is kept (it matches renderSegment's clip
            // placement, so clicks stay aligned with rendered content), but
            // two boundary cases would otherwise mis-place or lose a click:
            //  • the loop-start grid position recovered by the widened grid
            //    window lies up to one frame BEFORE the fractional cursor, so
            //    (beatPos − segStartBeat) × samplesPerBeat is negative and
            //    Math.round takes it to −1 whenever that residue is above
            //    half a frame (at exactly half, Java's round-half-up takes
            //    −0.5 to 0). The consequence then depended on where the
            //    widened segment starts: at block frame 0 the offset came out
            //    −1 and the defensive bound below dropped the click outright;
            //    with segFrameOffset > 0 it came out segFrameOffset − 1, so
            //    the click was placed one frame EARLY, in the previous
            //    segment's last frame. Either way it is wrong — the position
            //    belongs to this segment's first frame, the quantized wrap
            //    frame, never to the pre-wrap segment that owns the frames
            //    before it;
            //  • a grid position within half a frame of the segment end
            //    rounds UP to segFrames. That USED to be the general case
            //    and it was a bug in its own right: the position was
            //    admitted by a window ending on the segment's cursor end,
            //    then dragged back to the last frame the segment owns — one
            //    sample early — or, on the block's last segment, dropped
            //    outright by the numFrames bound. It no longer arises there:
            //    mixMetronomeClicks now hands down the segment's
            //    FRAME-OWNERSHIP interval, which ends half a frame early, so
            //    such a position is admitted by the NEXT segment instead and
            //    sounds at its frame 0.
            // What remains reachable on the upper clamp is therefore narrow:
            // a grid position sitting AT a lap end, where the window is
            // deliberately held beat-exact because the pre-wrap segment's
            // last frame is the only frame that position can sound on; and
            // floating-point residue, where a product lands a hair above the
            // frame count. The LOWER clamp is untouched by any of this and
            // remains fully load-bearing for the widened lap-start position
            // of the first bullet.
            // Every index admitted by the beat window therefore sounds
            // exactly once, inside the frames its segment owns.
            if (sampleOffset < segFrameOffset) {
                sampleOffset = segFrameOffset;
            } else if (sampleOffset >= segFrameOffset + segFrames) {
                sampleOffset = segFrameOffset + segFrames - 1;
            }
            // Defensive bound: the clamp above already keeps the offset
            // inside [0, numFrames), and the buffer writes below index by it.
            if (sampleOffset < 0 || sampleOffset >= numFrames) {
                continue;
            }
            boolean isMainBeat = (idx % clicksPerBeat == 0);
            boolean accent = isMainBeat
                    && ((idx / clicksPerBeat) % beatsPerBar == 0);

            float[][] click = metronome.generateClick(accent);
            RoutedClick routed = router.route(metronome, click, backend, cueBusManager);

            // Sum the main-mix click into the engine's mix buffer at
            // the sample-accurate offset, parking any tail that does
            // not fit in this block into clickTail so the next block
            // can drain it (story 136: clicks > buffer size still play
            // continuously at typical 256/512-frame low-latency sizes).
            if (routed.hasMainMix()) {
                float[][] main = routed.mainMixBuffer();
                if (main.length > 0) {
                    int clickLen = main[0].length;
                    int writeable = Math.min(clickLen, numFrames - sampleOffset);
                    int channels = Math.min(main.length, mixBuffer.length);
                    for (int ch = 0; ch < channels; ch++) {
                        float[] src = main[ch];
                        float[] dst = mixBuffer[ch];
                        for (int s = 0; s < writeable; s++) {
                            dst[sampleOffset + s] += src[s];
                        }
                    }
                    int overflow = clickLen - writeable;
                    if (overflow > 0) {
                        int tailCh = Math.min(channels, clickTail.length);
                        int tailCap = clickTail[0].length;
                        // Sum overflow into existing tail (a previous
                        // click may not have fully drained yet at fast
                        // subdivisions — be additive, not destructive).
                        for (int ch = 0; ch < tailCh; ch++) {
                            float[] src = main[ch];
                            float[] dst = clickTail[ch];
                            int n = Math.min(overflow, tailCap);
                            for (int s = 0; s < n; s++) {
                                dst[s] += src[writeable + s];
                            }
                        }
                        clickTailFrames = Math.max(clickTailFrames,
                                Math.min(overflow, tailCap));
                    }
                }
            }

            // Write the side-output to its hardware channel with
            // sample-accurate alignment: prepend sampleOffset zeros so
            // the click lands at the same intra-block position as the
            // main-mix contribution.
            if (backend != null && routed.hasSideOutput()) {
                float[] sideRaw = routed.sideOutputBuffer();
                float[] aligned = new float[sampleOffset + sideRaw.length];
                System.arraycopy(sideRaw, 0, aligned, sampleOffset, sideRaw.length);
                backend.writeToChannel(routed.sideChannelIndex(), aligned);
            }

            // Write each cue-bus contribution to its hardware output
            // stereo pair (CueBus.hardwareOutputIndex is a stereo-pair
            // index; physical channels are 2N / 2N+1). Prepend leading
            // zeros for sample-accurate alignment, matching the main-mix
            // offset. The contribution is mono — both channels of the
            // pair receive the same attenuated click so the drummer
            // hears it centered.
            if (backend != null && cueBusManager != null
                    && !routed.cueBusBuffers().isEmpty()) {
                int outChCount = this.outputChannelCount;
                for (Map.Entry<UUID, float[]> e : routed.cueBusBuffers().entrySet()) {
                    CueBus bus = cueBusManager.getById(e.getKey());
                    if (bus == null) {
                        continue;
                    }
                    int leftCh = bus.hardwareOutputIndex() * 2;
                    // Story 215 — skip writes to channels beyond the
                    // device's reported output count to avoid hitting a
                    // non-existent driver channel. The guard is disabled
                    // (outChCount == 0) when no channel info is available.
                    if (outChCount > 0 && leftCh + 1 >= outChCount) {
                        continue;
                    }
                    float[] mono = e.getValue();
                    float[] aligned = new float[sampleOffset + mono.length];
                    System.arraycopy(mono, 0, aligned, sampleOffset, mono.length);
                    backend.writeToChannel(leftCh, aligned);
                    backend.writeToChannel(leftCh + 1, aligned);
                }
            }
        }
    }

    /**
     * Zeros the click-tail buffer and resets the pending frame count.
     * Called when playback stops, the metronome is disabled, or the
     * metronome/router is disconnected — prevents stray clicks from
     * leaking into a subsequent block.
     */
    private void clearClickTail() {
        if (clickTailFrames > 0) {
            for (float[] ch : clickTail) {
                java.util.Arrays.fill(ch, 0, clickTailFrames, 0.0f);
            }
            clickTailFrames = 0;
        }
    }

    /**
     * Renders {@code totalFrames} of audio offline by invoking
     * {@link #renderBlock} in a loop. The caller-supplied
     * {@code outputBuffer} must have dimensions
     * {@code [format.channels()][totalFrames]}.
     *
     * <p>The transport is assumed to already be positioned at the render
     * start and in {@link TransportState#PLAYING} state. The pipeline will
     * advance the transport as it renders, just as it does on the live
     * path. This guarantees that live and offline rendering produce
     * bit-identical output for the same project state.</p>
     *
     * <p>Unlike {@link #renderBlock}, this method is <b>not</b> real-time
     * safe — it is intended for offline export contexts where allocations
     * and blocking I/O are acceptable.</p>
     *
     * @param transport     the transport positioned at the render start
     * @param mixer         the mixer (non-null)
     * @param tracks        the tracks to render (non-null)
     * @param midiRenderer  the MIDI track renderer, or {@code null}
     * @param masterChain   the master effects chain (non-null)
     * @param outputBuffer  the destination buffer
     *                      {@code [channels][totalFrames]}
     * @param totalFrames   the number of frames to render
     * @param blockSize     the per-block render size (must be &le; the
     *                      {@code blockSize} this pipeline was constructed
     *                      with)
     * @throws NullPointerException     if any required argument is null
     * @throws IllegalArgumentException if {@code totalFrames} or
     *                                  {@code blockSize} is non-positive, or
     *                                  if the output buffer dimensions do
     *                                  not match the pipeline format
     */
    public void renderOffline(Transport transport,
                              Mixer mixer,
                              List<Track> tracks,
                              MidiTrackRenderer midiRenderer,
                              EffectsChain masterChain,
                              float[][] outputBuffer,
                              int totalFrames,
                              int blockSize) {
        Objects.requireNonNull(transport, "transport must not be null");
        Objects.requireNonNull(mixer, "mixer must not be null");
        Objects.requireNonNull(tracks, "tracks must not be null");
        Objects.requireNonNull(masterChain, "masterChain must not be null");
        Objects.requireNonNull(outputBuffer, "outputBuffer must not be null");
        if (totalFrames <= 0) {
            throw new IllegalArgumentException(
                    "totalFrames must be positive: " + totalFrames);
        }
        if (blockSize <= 0 || blockSize > mixBuffer[0].length) {
            throw new IllegalArgumentException(
                    "blockSize must be in (0, " + mixBuffer[0].length + "]: " + blockSize);
        }
        int channels = format.channels();
        if (outputBuffer.length < channels) {
            throw new IllegalArgumentException(
                    "outputBuffer must have at least " + channels + " channels");
        }
        for (int ch = 0; ch < channels; ch++) {
            if (outputBuffer[ch].length < totalFrames) {
                throw new IllegalArgumentException(
                        "outputBuffer channel " + ch + " shorter than totalFrames");
            }
        }

        // Scratch block-sized output that the pipeline writes into; we copy
        // each block into the correct offset of the caller's buffer.
        float[][] blockOut = new float[channels][blockSize];

        int framesRendered = 0;
        while (framesRendered < totalFrames) {
            int framesThisBlock = Math.min(blockSize, totalFrames - framesRendered);

            // Clear blockOut so master chain writes land on a zero scratch
            for (int ch = 0; ch < channels; ch++) {
                Arrays.fill(blockOut[ch], 0, framesThisBlock, 0.0f);
            }

            renderBlock(null, blockOut, framesThisBlock,
                    transport, mixer, tracks, midiRenderer, masterChain,
                    null, null);

            for (int ch = 0; ch < channels; ch++) {
                System.arraycopy(blockOut[ch], 0,
                        outputBuffer[ch], framesRendered, framesThisBlock);
            }
            framesRendered += framesThisBlock;
        }
    }

    // ------------------------------------------------------------------
    // Internal rendering helpers (moved verbatim from AudioEngine so that
    // live and offline paths share identical clip-to-buffer logic).
    // ------------------------------------------------------------------

    @RealTimeSafe
    private void renderTracks(List<Track> tracks, int trackCount, Transport transport,
                              double renderOffsetBeats, MidiTrackRenderer midiRenderer,
                              int numFrames) {
        int audioChannels = format.channels();
        for (int t = 0; t < trackCount; t++) {
            for (int ch = 0; ch < audioChannels; ch++) {
                Arrays.fill(trackBuffers[t][ch], 0, numFrames, 0.0f);
            }
        }

        double tempo = transport.getTempo();
        double sampleRate = format.sampleRate();
        double samplesPerBeat = sampleRate * 60.0 / tempo;
        // Half a frame, in beats — the shift that turns a segment's cursor
        // range into its FRAME-OWNERSHIP interval at an ordinary continuation
        // edge. Hoisted here because it is a per-block constant; see the event
        // window computation in the segment loop below for what it buys.
        double halfFrameBeats = 0.5 / samplesPerBeat;
        // Offset ahead by the PDC system latency so that after compensation
        // delays, the output aligns with the transport cursor.
        double currentBeat = transport.getPositionInBeats() + renderOffsetBeats;
        // Story 315 — read the loop trio through the immutable LoopWindow
        // record (a single volatile load) so a concurrent FX-thread loop edit
        // cannot tear the enabled/start/end triple mid-block.
        Transport.LoopWindow loopWindow = transport.getLoopWindow();
        boolean loopEnabled = loopWindow.enabled();
        double loopStart = loopWindow.startInBeats();
        double loopEnd = loopWindow.endInBeats();
        double loopLength = loopEnd - loopStart;
        // Story 315 review — the same predicate mixMetronomeClicks calls
        // loopActive, spelled the same way so the two walks' loop conditions
        // read identically: "looping is on AND the region is non-empty".
        // (loopEnd > loopStart and loopLength > 0.0 are the same test.) The
        // two walks are deliberately equivalent here; a reader comparing them
        // should find no asymmetry to interpret.
        boolean loopActive = loopEnabled && loopLength > 0.0;
        // Story 315 review — DEFENSIVE AND UNPINNED, for exactly the reasons
        // recorded on the matching clear in mixMetronomeClicks: nothing
        // returns between here and the segment loop, that loop runs at least
        // once for any numFrames >= 1, and its body ends with an
        // UNCONDITIONAL "trackLoopWrapPending = false" after renderSegment,
        // so the flag is false at method exit either way. The widening that
        // reads it and the in-block wrap that sets it are both conjoined with
        // loopActive, so a stale value cannot act while looping is off. No
        // test covers this clear and none can; only a numFrames == 0 callback
        // would tell the two versions apart.
        if (!loopActive) {
            trackLoopWrapPending = false;
        }

        // Story 315 review — loop-map the starting cursor before rendering:
        // the split guard below only fires while the cursor is still inside
        // the loop, so a cursor starting at/past the end would render the
        // whole block linearly from beyond the loop. Two ways it gets there:
        // (a) renderOffsetBeats pushes the PDC-shifted cursor at/past the
        // loop end while the raw cursor is still inside — the no-bleed loop
        // guarantee case; (b) the raw cursor itself is at/past the end
        // (setPositionInBeats permits such a target while looping;
        // advancePosition wraps only at the NEXT block boundary) — this block
        // then renders from the position the transport is about to occupy.
        // The mapping mirrors advancePosition's closed-form wrap, and modulo
        // mapping composes: the wrapped cursor lines up exactly with where
        // the previous block's in-block wrap left off and with the
        // transport's own wrap one boundary later.
        if (loopActive && currentBeat >= loopEnd) {
            currentBeat = loopStart + ((currentBeat - loopEnd) % loopLength);
            // The content walk must NOT clear the wrap flag here. Unlike the
            // click walk, this mapping also fires for reason (a) above — the
            // PDC-shifted cursor crosses the loop end while the raw cursor is
            // still inside — which is the exact continuation of a whole-frame
            // quantization residue, not a seek. Clearing here wiped a flag
            // the previous block legitimately set and dropped the loop-start
            // event on exactly the block-boundary-aligned wraps the flag
            // exists for (measured: 938 missed laps vs 792 across 792 configs
            // at 16 frames of insert latency, 1961 vs 792 at 512 frames; the
            // 792 residual is each config's lap 0, a genuine PDC start-up
            // artifact — measured offline by simulation, NOT reproducible
            // from a test in this repo). A stale flag from a real seek is
            // already bounded by the residue test below.
        }

        int framesProcessed = 0;

        while (framesProcessed < numFrames) {
            int framesToProcess = numFrames - framesProcessed;

            // True while this walk is splitting blocks at the loop boundary
            // at all — the condition under which the segment span must be
            // clamped to the loop end and the event window below capped at
            // it, because only then can the raw whole-frame span overshoot
            // it. It does NOT say the segment reaches the loop end; a segment
            // that runs out of block first is just as much a loop-split
            // segment.
            //
            // Recomputed per iteration, and for every non-degenerate loop it
            // is invariant across the whole block: currentBeat < loopEnd
            // holds on entry (the block-entry mapping above establishes it)
            // and is restored by every in-block modulo wrap at the bottom, so
            // in practice the second conjunct is true throughout and the
            // value reduces to loopActive — itself read once, before the
            // loop. Segments of one block therefore do not disagree about it.
            //
            // "Always true" is not, however, provable. Both mappings restore
            // the cursor as loopStart + ((x - loopEnd) % loopLength): the
            // modulo result r is strictly < loopLength, but the SUM can still
            // round up to exactly loopEnd. The trigger is a loop that
            // STRADDLES A BINADE BOUNDARY — a finer double grid at loopStart
            // than at loopEnd, so an exact r is representable while
            // loopStart + r is not — and it needs no extreme
            // loopStart/loopLength ratio. Measured on JDK 26 for an ordinary
            // ~6-beat loop:
            //
            //   loopStart = Math.nextDown(1024.0) = 1023.9999999999999
            //   loopEnd   = 1030.0
            //   cursor    = 1036.0
            //   loopLength                          = 6.000000000000114
            //   r = (cursor - loopEnd) % loopLength = 6.0   (r < loopLength)
            //   loopStart + r                       = 1030.0  == loopEnd
            //   ulp(loopStart) = 1.1368683772161603E-13
            //   ulp(loopEnd)   = 2.2737367544323206E-13
            //
            // In that corner the second conjunct MAKES THINGS WORSE — it is
            // not a safety net, and it is written down here so that nobody
            // re-derives it as one. Simulated on those values at 44.1 kHz /
            // 120 BPM with 512-frame blocks:
            //  • conjunct PRESENT (today) — loopSplitActive is false, so the
            //    span is NOT clamped to the loop end and the event window is
            //    NOT capped at it. All 512 frames render from the loop end
            //    outwards, window [1030.0, 1030.0232199546485): a whole block
            //    scheduled from OUTSIDE the loop, which is precisely the
            //    failure the block-entry mapping above exists to prevent.
            //  • conjunct REMOVED — the split yields a 1-frame segment whose
            //    event window is capped to the empty [1030.0, 1030.0), and
            //    the bottom-of-loop modulo restores the cursor to
            //    1024.000045351474, back inside the loop.
            //
            // It is nevertheless left as-is here. The conjunct is
            // PRE-EXISTING — HEAD spelled the same guard inline as
            // "loopEnabled && loopLength > 0.0 && currentBeat < loopEnd", and
            // this work only hoisted it into a named local (then reused that
            // local for the event-window cap below). No test reaches the
            // corner either: replacing this line with
            // "loopSplitActive = loopActive" leaves the whole daw-core suite
            // green (Tests run: 6321, Failures: 0, Errors: 0, Skipped: 10),
            // so nothing pins either behaviour. Dropping the conjunct is
            // therefore a judgement call about a case no fixture constructs;
            // it is a candidate for a FOLLOW-UP, deliberately not part of
            // this change. Do not "simplify" it away as an obvious no-op
            // either — it is not one.
            boolean loopSplitActive = loopActive && currentBeat < loopEnd;
            if (loopSplitActive) {
                double beatsUntilLoopEnd = loopEnd - currentBeat;
                // Story 315 review — shave an epsilon off the ceil and floor
                // the result at one frame.
                //
                // THE EPSILON. A product that ought to be a whole number of
                // frames comes out a hair ABOVE it, so ceil returns k + 1 and
                // the segment overshoots the loop end by a frame. Simulated
                // on the fixture this half is pinned on
                // (AudioEngineMidiPlaybackTest#
                // noteAtTheLoopStartFiresOnEveryLapWhenTheWrapResidueRoundsUp
                // — 44.1 kHz, 72 BPM, samplesPerBeat 36750, loop
                // [0.0, 0.25), 1024-frame blocks), the PRODUCTS this walk
                // computes that land within 1e-6 above a positive integer run
                // from k + 1.1368683772161603e-13 to
                // k + 1.8189894035458565e-12, and the one that does the
                // damage is (exact double, printed in full)
                //   967.0000000000001136868377216160297393798828125
                // i.e. k + 1.1368683772161603e-13, at block 17.
                // The often-quoted 5.56e-13 is NOT a product error — it is
                // the excess of the resulting RESIDUE: after that ceil the
                // wrap leaves the cursor
                //   1.0000000000005559996907322783954441547393798828125
                // frames into the lap, just OUTSIDE the "< 1.0" widening
                // below, so that lap's loop-start event is dropped. The two
                // excesses are not the same quantity and not even the same
                // size: 5.5599969e-13 for the residue against
                // 1.1368683772161603e-13 for the product that caused it. The
                // residue's excess is accumulated across the whole block's
                // "currentBeat += framesToProcess / samplesPerBeat" chain; it
                // is not the product's own excess carried through.
                //
                // THE FLOOR is load-bearing, not cosmetic. With the epsilon
                // alone the count can reach 0 — in that same fixture the last
                // segment of blocks 35 and 53 has product 1.020017403874e-12,
                // and ceil(that - 1e-9) is -0.0, i.e. 0 — and the old "> 0"
                // guard then skipped the clamp entirely, so the segment ran
                // to the end of the block from a cursor a fraction of a frame
                // short of the loop end, overshooting the loop end by the
                // whole remainder of the block. With the floor the count is
                // always >= 1, the guard is gone, and every segment consumes
                // >= 1 frame.
                //
                // The FIGURE "286 of 300 laps wrong in a sweep" was measured
                // offline by simulation and is NOT reproducible from any test
                // in this repo — that disclaimer applies to the figure only.
                // The MECHANISM is pinned right here: putting the old "> 0"
                // guard back in place of this floor fails four tests in
                // AudioEngineMidiPlaybackTest, every one of them by LOSING
                // loop-start note-ons (run on JDK 26):
                //   noteAtTheLoopStartFiresOnlyOncePerLapWhenTheWrapResidueIsAlreadyConsumed
                //       expected 23 note-ons, got 6
                //   noteAtTheLoopStartFiresOnEveryLapWhenTheWrapResidueRoundsUp
                //       expected 7, got 5
                //   loopStartNoteSurvivesTheBlockEntryMappingUnderPluginDelayCompensation
                //       expected 5, got 2
                //   loopStartNoteSurvivesAPauseOnTheWrapBoundary
                //       expected 5, got 2
                // Dropping the epsilon instead (keeping the floor) fails
                // exactly two: the WrapResidueRoundsUp test above and
                // MetronomeLoopSchedulingEngineTest#
                // loopStartClickFiresOnlyOncePerLapWhenTheWrapResidueIsAlreadyConsumed.
                int framesUntilLoopEnd =
                        Math.max(1, (int) Math.ceil(beatsUntilLoopEnd * samplesPerBeat - 1e-9));
                framesToProcess = Math.min(framesToProcess, framesUntilLoopEnd);
            }

            // Story 315 review — MIDI notes are POINT events, so the beat
            // window handed to the MIDI renderer must partition the loop
            // timeline EXACTLY: every note position inside the loop must fall
            // in exactly one segment window per lap, and no position outside
            // the loop may fall in any. The raw cursor cannot do that, because
            // the split above is clamped to WHOLE frames and therefore
            // overshoots the loop end by δ ∈ [0, 1) frame:
            //
            //   • the pre-wrap window ran to currentBeat + framesToProcess /
            //     samplesPerBeat, i.e. δ PAST the loop end, so a note
            //     sitting exactly at loopEnd — outside the half-open loop —
            //     fired on every lap (out-of-loop bleed);
            //   • the post-wrap window began at loopStart + δ, so a note
            //     sitting exactly at loopStart was skipped on every wrapped
            //     lap. MidiTrackRenderer tests note positions with an exact
            //     >=, with no epsilon, so even the ~1e-11-frame residue left
            //     on an otherwise frame-aligned lap dropped that note.
            //
            // Widen the window down to the loop start whenever this segment
            // begins inside that sub-frame residue, and cap it at the loop
            // end whenever this segment runs up to it. The raw fractional
            // cursor stays the frame-mapping origin for audio clips
            // (renderSegment keeps using it); for MIDI the origin travels
            // with the widening, as its own frameOriginBeat argument.
            //
            // Story 315 review (second round) — that is only half the
            // partition, and the other half is not about loops at all.
            // Admission is a test in BEATS while MidiTrackRenderer maps in
            // FRAMES, with round((beat - origin) × samplesPerBeat), and the
            // two domains disagree over the last half frame of EVERY segment,
            // looping or not: a position in (N - 0.5, N] frames was admitted
            // by this segment and rounded to N, outside its renderable
            // [0, N - 1], so the renderer had to drag it back to N - 1 — one
            // sample early, systematically. Copilot flagged the note-off case
            // (44.1 kHz at 68 BPM: ideal offset 9727.94, which correctly
            // rounds to the NEXT block's frame 0); the note-on branch had the
            // identical defect.
            //
            // So the window handed down is not this segment's cursor range at
            // all: it is the segment's FRAME-OWNERSHIP interval, the set of
            // beats whose nearest frame lies in [0, N - 1]. At an ordinary
            // continuation edge that is the cursor range shifted back half a
            // frame at BOTH ends. The straggler is then owned by the NEXT
            // segment, whose own window reaches half a frame back to collect
            // it, and lands on its frame 0 — the frame the rounding always
            // said it belonged on. The event is CARRIED across the boundary,
            // not re-quantized.
            //
            // Loop edges are exempt, and must be: they are HARD edges. The
            // right edge stays beat-exact at loopEnd, because a note-off
            // sitting there has no next segment in the lap to be carried into
            // — releasing it on the last frame before the wrap is the
            // semantics, implemented by MidiTrackRenderer's inclusive <= plus
            // its upper clamp. A lap start stays beat-exact at loopStart,
            // because nothing in the half frame BELOW the loop start — i.e.
            // outside the half-open loop — may leak in.
            //
            // The tiling is exact by construction, not by tolerance: segment
            // k + 1 does not recompute its left edge from its own cursor, it
            // is HANDED segment k's right edge through
            // trackPreviousWindowEndBeat. Segment k's right edge and segment
            // k + 1's left edge are therefore the same double — no gap for an
            // event to fall through, no overlap to double-fire it — and that
            // holds even across a block boundary, where the accumulated
            // segment cursor and transport.getPositionInBeats() +
            // renderOffsetBeats can differ by an ulp.
            //
            // Carrying is also what distinguishes a continuation from a
            // DISCONTINUITY. The render's first segment and any seek have no
            // previous segment that declined anything, so they are hard left
            // edges exactly as a lap start is; see the guard at the use site.
            //
            // The widening is gated on BOTH a wrap flag that survives the
            // block boundary AND the positional residue test, and each does a
            // job the other cannot.
            //
            // The FLAG makes the widening fire exactly once per lap. A
            // threshold cannot, because the two populations of readings
            // OVERLAP — the largest genuine residue sits ABOVE the smallest
            // already-consumed reading, so any cut-off placed between them
            // is on the wrong side of one of the two:
            //  • largest GENUINE residue — the wrap overshot and this lap's
            //    first frame is still unrendered, so the widening MUST
            //    fire: 0.99999999999994320, at 32768 Hz / 128 BPM
            //    (samplesPerBeat = 15360), loop [0.0, 0.25) beats,
            //    128-frame blocks;
            //  • smallest CONSUMED reading — this lap's first frame was
            //    ALREADY rendered, so the widening MUST NOT fire:
            //    0.99999999999988990, at 48000 Hz / 120 BPM
            //    (samplesPerBeat = 24000), loop [0.0, 0.328125) beats,
            //    64-frame blocks.
            // 0.99999999999994320 > 0.99999999999988990, so a cut-off that
            // admits every genuine residue also admits that consumed reading
            // and the lap's loop-start note fires a second time one frame
            // later; a cut-off that rejects the consumed reading also
            // rejects a genuine residue and that lap loses its note.
            // Provenance: both figures were measured offline by simulating
            // the walk across a sweep of sample rates, tempos, block sizes
            // and loop lengths. They are NOT reproducible from any test in
            // this repo. The flag is set at the in-block wrap and
            // cleared by the first segment that renders afterwards — which
            // may be in the NEXT block, because a quantized wrap can land
            // exactly on the block boundary and leave the residue where the
            // wrap itself is invisible to this walk.
            //
            // The RESIDUE TEST is retained as a bound on a STALE flag: after
            // a seek to a position inside the loop no mapping fires, so a
            // flag set before the seek can survive it. The residue test
            // confines any such stale widening to a cursor already within one
            // frame of the loop start, where emitting the loop-start event is
            // harmless.
            //
            // Loops shorter than one frame are excluded from the widening:
            // every segment would be a fresh wrap, so a note at the loop
            // start would re-fire on every single frame.
            //
            // The 1.0 is pinned from BOTH sides, so it cannot drift:
            // subFrameLoopFiresTheLoopStartNoteOnceNotOnEveryFrame uses a
            // 0.3-frame loop that must be EXCLUDED (ruling out any threshold
            // <= 0.3), and oneAndAHalfFrameLoopKeepsTheEventWindowWideningEnabled
            // uses a 1.5-frame loop that must be INCLUDED (ruling out any
            // threshold > 1.5). Both live in AudioEngineMidiPlaybackTest.
            //
            // Be precise about what that guard does and does not buy.
            // Measured on this walk at 32768 Hz / 120 BPM over 1536 frames,
            // with a note sitting exactly on a grid-aligned loop start:
            //  • loop length 0.3 frame — not an exact binary fraction, so
            //    the modulo residue drifts and never returns to the loop
            //    start: 1 note-on WITH the guard, one per frame (1536)
            //    without it. Here the guard is load-bearing, and
            //    AudioEngineMidiPlaybackTest pins it.
            //  • loop length 0.25 frame — a length that DIVIDES a frame
            //    exactly: the modulo wrap puts the cursor back exactly ON
            //    loopStart every frame, so the note is admitted by the
            //    ordinary (un-widened) window test with no widening
            //    involved. 1536 note-ons in 1536 frames WITH the guard AND
            //    without it. The guard does not rescue that case and does
            //    not claim to: it is a pre-existing pathology of sub-frame
            //    loops, not something this widening introduced. (0.5 frame
            //    behaves identically; 0.75 frame returns to the loop start
            //    every third frame — 512 note-ons — because it too is an
            //    exact binary fraction.)
            // This segment BEGINS a lap iff the previous one ended at the loop
            // end — the wrap that set the flag. A lap start is a hard edge:
            // nothing is carried into it, so its window is not shifted back.
            boolean segmentBeginsLap = trackLoopWrapPending && loopActive;

            double frameOriginBeat = currentBeat;
            if (segmentBeginsLap && loopLength * samplesPerBeat >= 1.0) {
                double beatsIntoLoop = currentBeat - loopStart;
                if (beatsIntoLoop > 0.0 && beatsIntoLoop * samplesPerBeat < 1.0) {
                    frameOriginBeat = loopStart;
                }
            }

            double segmentEndBeat = currentBeat + framesToProcess / samplesPerBeat;
            // A wrap FOLLOWS this segment iff the advanced cursor reaches the
            // loop end — the same test the wrap at the bottom of this loop
            // body performs, evaluated one step early so the window can be
            // held beat-exact there.
            boolean segmentEndsLap = loopActive && segmentEndBeat >= loopEnd;

            // The LEFT edge carries from the remembered previous window end,
            // never from a positional guess. Three cases:
            //  • a LAP START — a hard edge by definition; nothing below the
            //    loop start may leak in, so the window begins at the origin;
            //  • a DISCONTINUITY — the render's first segment, or a seek —
            //    also a hard edge, and for the same reason: no previous
            //    segment declined anything here, and reaching back would let
            //    a seek landing inside a note re-trigger its note-on. Which
            //    fixture pins this depends on WHAT you break, and the two
            //    cases are worth keeping straight (all three counts below
            //    measured on JDK 26, AudioEngineMidiPlaybackTest, 29 tests):
            //      – drop the discontinuity rule ALTOGETHER, i.e. widen every
            //        non-lap edge positionally with "frameOriginBeat -
            //        halfFrameBeats": the ONLY failure is
            //        oneAndAHalfFrameLoopKeepsTheEventWindowWideningEnabled,
            //        whose transport is parked a quarter frame past a note on
            //        the loop start — 1024 note-ons where 1023 is correct.
            //        That fixture is what caught the over-widening, and it is
            //        the reason this bullet exists;
            //      – break the CONTINUITY GUARD below instead, and that
            //        fixture cannot see it. Forcing continuesPreviousSegment
            //        false gives 3 failures —
            //        noteOnLandingInTheLastHalfFrameIsCarriedIntoTheNextSegment,
            //        noteOffLandingInTheLastHalfFrameIsCarriedIntoTheNextSegment
            //        and eventExactlyOnABlockBoundaryBelongsToTheBlockThatBeginsThere
            //        — and forcing it true gives 11; the 1.5-frame fixture
            //        stays GREEN in BOTH directions. The reason is the NaN
            //        sentinel: with the guard forced true the first segment's
            //        window start becomes trackPreviousWindowEndBeat, still
            //        NaN, and every "noteStartBeat >= NaN" is false — which
            //        admits nothing, landing on the same 1023 the hard edge
            //        produces. Coincidence, not coverage. A future reader who
            //        mutates the guard and finds that fixture green has not
            //        found dead code; look at the three named above;
            //  • an exact CONTINUATION — begin where the previous window
            //    ended, which is half a frame back past this origin.
            //
            // Using the remembered bound ITSELF as the edge is what makes the
            // two ownership intervals tile with no gap and no overlap, even
            // across a block boundary where the accumulated segment cursor
            // and transport.getPositionInBeats() + renderOffsetBeats can
            // differ by an ulp.
            //
            // What DECIDES between the two is a separate question, and it is
            // deliberately not a positional bound on the carried value. A
            // "does trackPreviousWindowEndBeat lie in [origin - half a frame,
            // origin]?" test passes in the ordinary continuation case only by
            // EQUALITY — the remembered bound sits exactly on that lower end
            // — and across a block boundary that equality is at the mercy of
            // the same non-associative ulp above. One ulp of upward drift and
            // the boundary would silently degrade to a hard edge, dropping
            // every event in its last half frame; for a note-OFF that is a
            // STUCK NOTE, the very bug class this change exists to remove.
            //
            // So the test is CONTINUITY against the remembered previous
            // segment END, at the resolution the carry itself operates at:
            // half a frame. That is not an arbitrary epsilon. Unlike the
            // wrap-flag essay above — where a threshold genuinely cannot work
            // because the two populations of readings OVERLAP — the two
            // populations here are eight orders of magnitude apart: ulp drift
            // is ~1e-13 beats, about 5e-9 of a frame, while any genuine
            // discontinuity (a seek, a restart, the block-entry loop mapping)
            // moves the cursor by whole frames and usually by thousands of
            // them. The gap between them is not a grey area either: a "seek"
            // shorter than half a frame lands inside the frame the walk was
            // already on, where carrying and not carrying pick the same frame
            // for every event. The full argument is on
            // trackPreviousSegmentEndBeat.
            //
            // NaN is the "nothing rendered yet" sentinel and every comparison
            // against it is false — Math.abs(NaN - x) < 0.5 included — so a
            // fresh walk takes the hard-edge branch without a separate flag.
            // Do not rewrite this test into a NaN-unsafe form.
            boolean continuesPreviousSegment =
                    Math.abs(frameOriginBeat - trackPreviousSegmentEndBeat) * samplesPerBeat < 0.5;
            double eventWindowStartBeat = (segmentBeginsLap || !continuesPreviousSegment)
                    ? frameOriginBeat
                    : trackPreviousWindowEndBeat;
            double eventWindowEndBeat;
            if (segmentEndsLap) {
                // loopSplitActive is the ordinary looping case: the span above
                // was clamped to the loop end, so cap the window there too and
                // let MidiTrackRenderer's right-INCLUSIVE note-off bound
                // release a note that ends exactly as the lap ends.
                //
                // Its absence here is the binade corner documented on
                // loopSplitActive above — currentBeat has rounded up to
                // exactly loopEnd, the span was NOT clamped, and this window
                // is left uncapped and unshifted exactly as it is today. That
                // known limitation is a deliberate non-goal of this change; do
                // not "tidy" the branch away.
                eventWindowEndBeat = loopSplitActive ? loopEnd : segmentEndBeat;
            } else {
                eventWindowEndBeat = segmentEndBeat - halfFrameBeats;
            }

            renderSegment(tracks, trackCount, currentBeat,
                    eventWindowStartBeat, eventWindowEndBeat, frameOriginBeat,
                    samplesPerBeat, midiRenderer, framesProcessed, framesToProcess);
            // This segment owns its lap's first frame now, whether or not the
            // widening actually fired (a frame-aligned wrap leaves no residue
            // and needs none), so no later segment may widen for the same lap.
            trackLoopWrapPending = false;
            // Hand this segment's right edge to the next one as its carry-in
            // bound, so the two ownership intervals abut exactly, and its raw
            // end cursor as the continuity reference that decides whether the
            // next segment may reach back for it at all.
            trackPreviousWindowEndBeat = eventWindowEndBeat;
            trackPreviousSegmentEndBeat = segmentEndBeat;

            framesProcessed += framesToProcess;
            // Assign the segment's own end rather than re-adding the same
            // quotient. The VALUE is identical either way, but reusing the
            // very double the window was derived from makes the next
            // iteration's continuity test exact BY INSPECTION for a
            // within-block continuation: its frameOriginBeat is literally
            // this segmentEndBeat, so the difference is a bit-exact zero
            // rather than two identical expressions argued to agree. Across a
            // BLOCK boundary the origin is re-derived from the transport and
            // the difference is an ulp, which is what the half-frame
            // tolerance is there to absorb.
            currentBeat = segmentEndBeat;

            // Story 315 review — modulo, not plain subtraction: the segment
            // is clamped to whole frames, so a sub-sample loop (shorter than
            // one frame) is overshot by more than its own length and a single
            // subtraction would leave the cursor at/past the loop end — the
            // split guard above then never fires again and the rest of the
            // block renders linearly from beyond the loop. Same closed form
            // as the start-of-block mapping and advancePosition.
            if (loopActive && currentBeat >= loopEnd) {
                currentBeat = loopStart + ((currentBeat - loopEnd) % loopLength);
                trackLoopWrapPending = true;
                if (midiRenderer != null) {
                    midiRenderer.allNotesOff();
                }
            }
        }
    }

    /**
     * Renders one loop-split segment of a block: MIDI tracks through the
     * {@link MidiTrackRenderer}, audio tracks by copying overlapping clip
     * data into the track buffers.
     *
     * <p>THREE beat quantities are in play, and the split is deliberate.
     * {@code startBeat} is the segment's raw fractional render cursor and,
     * with the {@code endBeat} derived from it, is the range used for audio
     * clips. {@code eventWindowStartBeat}/{@code eventWindowEndBeat} are the
     * caller's EVENT window — the segment's frame-ownership interval — and
     * are used only for MIDI. {@code frameOriginBeat} is the beat that maps to
     * MIDI frame 0 of this segment, and it is NOT the window start: an
     * ordinary continuation window begins half a frame below it, and a lap
     * start moves the origin onto {@code loopStart} while the window follows
     * it exactly. {@link #renderTracks} computes all three.</p>
     *
     * <p>That window is ASYMMETRIC — half-open {@code [start, end)} for
     * note-ons, right-INCLUSIVE {@code (start, end]} for note-offs, which is
     * how a note ending exactly at the loop end is released;
     * {@link MidiTrackRenderer} carries the full rule.</p>
     *
     * <p>Audio clips are RANGES, not point events: the frame that straddles
     * the loop boundary has to be filled with something, and clipping their
     * beat range to the loop end would leave part of that frame unwritten —
     * an audible click. A clip starting exactly at the loop start also cannot
     * be dropped, because {@code overlapStart = max(startBeat, clipStart)}
     * maps it to the segment's first frame regardless of the sub-frame
     * residue left by the whole-frame loop split. Only point events (MIDI
     * note-on/note-off) can fall through the crack that residue opens, so
     * only they get the loop-exact window.</p>
     */
    @RealTimeSafe
    private void renderSegment(List<Track> tracks, int trackCount,
                               double startBeat,
                               double eventWindowStartBeat, double eventWindowEndBeat,
                               double frameOriginBeat,
                               double samplesPerBeat,
                               MidiTrackRenderer midiRenderer,
                               int frameOffset, int framesToProcess) {
        double endBeat = startBeat + framesToProcess / samplesPerBeat;

        for (int t = 0; t < trackCount; t++) {
            Track track = tracks.get(t);

            if (track.getType() == TrackType.MIDI
                    && track.getSoundFontAssignment() != null
                    && midiRenderer != null) {
                midiRenderer.renderMidiTrack(track, trackBuffers[t],
                        eventWindowStartBeat, eventWindowEndBeat, frameOriginBeat,
                        samplesPerBeat, frameOffset, framesToProcess);
                continue;
            }

            List<AudioClip> clips = track.getClips();

            for (int c = 0; c < clips.size(); c++) {
                AudioClip clip = clips.get(c);
                float[][] audioData = resolveAudioData(clip);
                if (audioData == null || audioData.length == 0) {
                    continue;
                }

                double clipStart = clip.getStartBeat();
                double clipEnd = clip.getEndBeat();

                if (endBeat <= clipStart || startBeat >= clipEnd) {
                    continue;
                }

                double overlapStart = Math.max(startBeat, clipStart);
                double overlapEnd = Math.min(endBeat, clipEnd);

                int outStart = frameOffset + (int) Math.round((overlapStart - startBeat) * samplesPerBeat);
                int outEnd = frameOffset + (int) Math.round((overlapEnd - startBeat) * samplesPerBeat);
                outEnd = Math.min(outEnd, frameOffset + framesToProcess);

                double beatInClip = overlapStart - clipStart + clip.getSourceOffsetBeats();
                int srcStart = (int) Math.round(beatInClip * samplesPerBeat);
                int audioLength = audioData[0].length;

                if (srcStart < 0) {
                    outStart += -srcStart;
                    srcStart = 0;
                }

                int copyLength = Math.min(outEnd - outStart, audioLength - srcStart);
                if (copyLength <= 0) {
                    continue;
                }

                int audioChannels = Math.min(audioData.length, trackBuffers[t].length);
                // Resolve the per-sample gain: if the clip has a gain envelope,
                // evaluate it per source-frame; otherwise use the scalar clip-gain.
                // Use the non-allocating accessor so the audio thread does not
                // allocate an Optional on every call.
                ClipGainEnvelope envelope = clip.getGainEnvelope();
                double scalarGain = (envelope == null) ? Math.pow(10.0, clip.getGainDb() / 20.0) : 1.0;
                if (envelope == null && scalarGain == 1.0) {
                    for (int ch = 0; ch < audioChannels; ch++) {
                        for (int f = 0; f < copyLength; f++) {
                            trackBuffers[t][ch][outStart + f] += audioData[ch][srcStart + f];
                        }
                    }
                } else if (envelope == null) {
                    float g = (float) scalarGain;
                    for (int ch = 0; ch < audioChannels; ch++) {
                        for (int f = 0; f < copyLength; f++) {
                            trackBuffers[t][ch][outStart + f] += audioData[ch][srcStart + f] * g;
                        }
                    }
                } else {
                    // Fill the preallocated scratch buffer in a single
                    // segment-walk pass (no per-sample binary search), then
                    // apply it across channels. Zero heap allocations on
                    // the audio thread.
                    envelope.fillLinearGains((long) srcStart, gainScratch, copyLength);
                    for (int ch = 0; ch < audioChannels; ch++) {
                        for (int f = 0; f < copyLength; f++) {
                            trackBuffers[t][ch][outStart + f]
                                    += audioData[ch][srcStart + f] * gainScratch[f];
                        }
                    }
                }
            }
        }
    }

    @RealTimeSafe
    private void applyAutomation(List<Track> tracks, int trackCount,
                                 List<MixerChannel> channels, Transport transport,
                                 Mixer mixer) {
        int channelCount = channels.size();
        double currentBeat = transport.getPositionInBeats();

        for (int t = 0; t < trackCount && t < channelCount; t++) {
            Track track = tracks.get(t);
            if (!track.getAutomationMode().readsAutomation()) {
                continue;
            }

            AutomationData automation = track.getAutomationData();
            MixerChannel channel = channels.get(t);

            if (automation.hasActiveAutomation(AutomationParameter.VOLUME)) {
                channel.setVolume(Math.clamp(
                        automation.getValueAtTime(AutomationParameter.VOLUME, currentBeat),
                        0.0, 1.0));
            }

            if (automation.hasActiveAutomation(AutomationParameter.PAN)) {
                channel.setPan(Math.clamp(
                        automation.getValueAtTime(AutomationParameter.PAN, currentBeat),
                        -1.0, 1.0));
            }

            if (automation.hasActiveAutomation(AutomationParameter.MUTE)) {
                channel.setMuted(
                        automation.getValueAtTime(AutomationParameter.MUTE, currentBeat) > 0.5);
            }

            if (automation.hasActiveAutomation(AutomationParameter.SEND_LEVEL)) {
                channel.setSendLevel(Math.clamp(
                        automation.getValueAtTime(AutomationParameter.SEND_LEVEL, currentBeat),
                        0.0, 1.0));
            }

            applyPluginParameterAutomation(automation, channel, currentBeat);

            // Apply reflective @ProcessorParam automation for built-in DSP inserts.
            // Bindings are pre-computed in Mixer.prepareForPlayback (and re-computed
            // when inserts change), so this call is allocation-free on the audio thread.
            mixer.getReflectiveParameterBinder().apply(channel, automation, currentBeat);
        }
    }

    @RealTimeSafe
    private void applyPluginParameterAutomation(AutomationData automation,
                                                MixerChannel channel,
                                                double currentBeat) {
        Map<PluginParameterTarget, ?> pluginLanes = automation.getPluginLanes();
        if (pluginLanes.isEmpty()) {
            return;
        }
        List<InsertSlot> inserts = channel.getInsertSlots();
        for (PluginParameterTarget target : pluginLanes.keySet()) {
            if (!automation.hasActiveAutomation(target)) {
                continue;
            }
            DawPlugin plugin = findPluginByInstanceId(inserts, target.pluginInstanceId());
            if (plugin == null) {
                continue;
            }
            double value = Math.clamp(
                    automation.getValueAtTime(target, currentBeat),
                    target.getMinValue(), target.getMaxValue());
            plugin.setAutomatableParameter(target.parameterId(), value);
        }
    }

    @RealTimeSafe
    private static DawPlugin findPluginByInstanceId(List<InsertSlot> inserts,
                                                    String instanceId) {
        for (int i = 0, n = inserts.size(); i < n; i++) {
            InsertSlot slot = inserts.get(i);
            DawPlugin plugin = slot.getPlugin();
            if (plugin != null
                    && instanceId.equals(plugin.getDescriptor().id())) {
                return plugin;
            }
        }
        return null;
    }

    /**
     * Returns the {@code [channel][sample]} buffer to read from for
     * {@code clip}, applying just-in-time sample-rate conversion via the
     * installed {@link SampleRateConversionCache} when the clip's
     * {@link SourceRateMetadata#nativeRateHz()} differs from the
     * session rate. Falls back to {@link AudioClip#getAudioData()} when
     * no cache is installed or no conversion is required.
     *
     * <p><strong>RT-safety caveat:</strong> On a cache <em>hit</em> (the
     * common steady-state path) this method is a single
     * {@link java.util.concurrent.ConcurrentHashMap} read — no allocation
     * and no lock. On a cache <em>miss</em> (the very first block after a
     * rate-mismatched clip enters the render graph, or after the cache is
     * invalidated) the conversion is computed inline, which allocates and
     * is CPU-heavy. Callers that require strict RT guarantees on the first
     * block should pre-warm the cache before entering the audio callback
     * (e.g. via {@link SampleRateConversionCache#get} from a setup thread).
     * Once populated, subsequent blocks are allocation-free.</p>
     */
    private float[][] resolveAudioData(AudioClip clip) {
        float[][] raw = clip.getAudioData();
        SampleRateConversionCache cache = this.srcCache;
        if (cache == null || raw == null || raw.length == 0) {
            return raw;
        }
        SourceRateMetadata meta = clip.getSourceRateMetadata();
        int sessionRateHz = (int) Math.round(format.sampleRate());
        if (meta == null || !meta.requiresConversion(sessionRateHz)) {
            return raw;
        }
        return cache.get(clip.getId(), meta, sessionRateHz, srcQualityTier, () -> raw);
    }
}
