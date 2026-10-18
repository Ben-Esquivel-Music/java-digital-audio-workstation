package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.midi.CountInDownbeat;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.util.Objects;
import java.util.Optional;

/**
 * A count-in on the engine's frame clock (story 328, Recording Reliability
 * book §5.6): the playhead holds at the record position while the render
 * thread plays {@code totalBeats} clicks through the metronome voice, and at
 * the frame where the count-in ends the render thread itself performs the
 * record start it was given ({@link #setRecordStart(Runnable)}).
 *
 * <h2>Frame math</h2>
 * <p>{@code countInFrames = round(totalBeats × sampleRate × 60 / tempoBpm)}.
 * The leading pad is {@code (blockSize − countInFrames mod blockSize) mod
 * blockSize}, plus whole blocks until it is at least
 * {@code masterLatencyFrames}, so {@link #totalFrames()}
 * {@code = pad + countInFrames} is a whole number of render blocks and the
 * count-in ends exactly on a block boundary: the block after it is the
 * first one rendered — and captured — from the record position. Clicks are
 * placed backwards from that end frame ({@code end − k × framesPerBeat},
 * rounded), so the click grid and bar 1 coincide sample-exactly; the first
 * click lands at frame {@code pad}. The downbeat of each count-in bar is
 * accented.</p>
 *
 * <p>The render thread emits each click {@code masterLatencyFrames} early
 * — the serial master-insert and mastering latency the running click is
 * advanced by, so the count-in's grid meets bar 1's PDC-compensated
 * downbeat at the output. The pad is sized at build time so that advance
 * never reaches before frame 0: click 1 at {@code pad − masterLatencyFrames
 * ≥ 0} is emitted on its beat like every later click, never clamped to a
 * block's first frame. The pad is part of {@link #totalFrames()} and so of
 * {@link #countInDurationMicros()}: the MIDI window covers the whole
 * count-in whatever the latency.</p>
 *
 * <h2>Phases and threads</h2>
 * <p>Built on the caller thread, then handed to
 * {@link AudioEngine#scheduleCountIn(CountInSchedule)}, which requires the
 * engine's stream to be in this schedule's rate and block size. From then
 * on the render thread owns the frame cursor and the click index, and is
 * the only writer of {@code FIRED}; the caller is the only writer of
 * {@code CANCELLED}. Both words are volatile and neither side does an atomic
 * read-modify-write: the render thread reads {@code CANCELLED} immediately
 * before it stores {@code FIRED} and runs the record start, and
 * {@link #cancel()} stores {@code CANCELLED} and then re-reads {@code FIRED}
 * to report who won. The verdict can be stale by the render thread's single
 * instruction window between that read and that store: a cancel that reports
 * a win while the render thread had already passed its read fires all the
 * same. Callers therefore run the same cleanup for both verdicts (the
 * recording pipeline's stop closes the producer gate, removes the callback
 * and stops the transport whichever side won) and use the verdict only for
 * what they announce.</p>
 *
 * <p>The render thread stamps {@link #fireNanos()} from the monotonic clock
 * immediately before it stores {@code FIRED}: the fire is the real downbeat
 * of bar 1 on the render clock, which a {@code MidiRecorder} given this
 * schedule as its {@link CountInDownbeat} measures bar 1 from, instead of
 * the window its caller predicted before the schedule was installed.</p>
 *
 * <p>A schedule fires once and is never reused: a new count-in is a new
 * schedule.</p>
 */
public final class CountInSchedule implements CountInDownbeat {

    /** Where a schedule is in its life; see the class note on who writes which phase. */
    public enum Phase {
        /** Installed or installable; the render thread may still be counting. */
        PENDING,
        /** The render thread reached the end frame and ran the record start. */
        FIRED,
        /** {@link #cancel()} won: the record start will never run. */
        CANCELLED
    }

    private final int totalBeats;
    private final int beatsPerBar;
    private final double tempoBpm;
    private final double sampleRate;
    private final int blockSize;
    private final int masterLatencyFrames;
    private final long countInFrames;
    private final long pad;
    private final long totalFrames;
    private final double framesPerBeat;
    private final long[] clickFrames;
    private final boolean[] clickAccents;

    private volatile Runnable recordStart;
    /** Render thread only; stored after {@link #fireNanos}, so a {@code true} read publishes the stamp. */
    private volatile boolean fired;
    /** Caller thread only. */
    private volatile boolean cancelled;
    /** The monotonic clock at the fire, written by the render thread before {@link #fired}. */
    private volatile long fireNanos;

    // Render-thread-owned walk state.
    private long cursor;
    private int nextClick;
    /**
     * Whether the render thread has emitted bar 1's downbeat from inside the
     * count-in (see {@link #hasPendingDownbeat()}).
     */
    private boolean downbeatEmitted;

    /**
     * Builds a count-in of {@code totalBeats} beats.
     *
     * @param totalBeats          the beats to count, positive
     * @param beatsPerBar         the time-signature numerator at the record position, positive
     * @param tempoBpm            the tempo at the record position, finite and positive
     * @param sampleRate          the stream's sample rate, finite and positive
     * @param blockSize           the stream's block size in frames, positive
     * @param masterLatencyFrames the serial master-insert and mastering latency
     *                            the clicks are emitted early by, in frames at
     *                            {@code sampleRate}; non-negative
     */
    public CountInSchedule(int totalBeats, int beatsPerBar, double tempoBpm, double sampleRate, int blockSize,
                           int masterLatencyFrames) {
        if (totalBeats <= 0) {
            throw new IllegalArgumentException("totalBeats must be positive: " + totalBeats);
        }
        if (beatsPerBar <= 0) {
            throw new IllegalArgumentException("beatsPerBar must be positive: " + beatsPerBar);
        }
        if (!(tempoBpm > 0) || !Double.isFinite(tempoBpm)) {
            throw new IllegalArgumentException("tempoBpm must be finite and positive: " + tempoBpm);
        }
        if (!(sampleRate > 0) || !Double.isFinite(sampleRate)) {
            throw new IllegalArgumentException("sampleRate must be finite and positive: " + sampleRate);
        }
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be positive: " + blockSize);
        }
        if (masterLatencyFrames < 0) {
            throw new IllegalArgumentException("masterLatencyFrames must not be negative: " + masterLatencyFrames);
        }
        this.totalBeats = totalBeats;
        this.beatsPerBar = beatsPerBar;
        this.tempoBpm = tempoBpm;
        this.sampleRate = sampleRate;
        this.blockSize = blockSize;
        this.masterLatencyFrames = masterLatencyFrames;
        this.framesPerBeat = sampleRate * 60.0 / tempoBpm;
        this.countInFrames = Math.round(totalBeats * framesPerBeat);
        long alignment = (blockSize - countInFrames % blockSize) % blockSize;
        long latencyBlocks = masterLatencyFrames > alignment
                ? (masterLatencyFrames - alignment + blockSize - 1) / blockSize
                : 0L;
        this.pad = alignment + latencyBlocks * blockSize;
        this.totalFrames = pad + countInFrames;
        this.clickFrames = new long[totalBeats];
        this.clickAccents = new boolean[totalBeats];
        for (int beat = 0; beat < totalBeats; beat++) {
            clickFrames[beat] = totalFrames - Math.round((totalBeats - beat) * framesPerBeat);
            clickAccents[beat] = beat % beatsPerBar == 0;
        }
    }

    /**
     * The schedule of a count-in mode, or empty for {@link CountInMode#OFF}.
     *
     * @param mode                the count-in mode
     * @param beatsPerBar         the time-signature numerator at the record position, positive
     * @param tempoBpm            the tempo at the record position, finite and positive
     * @param sampleRate          the stream's sample rate, finite and positive
     * @param blockSize           the stream's block size in frames, positive
     * @param masterLatencyFrames the serial master-insert and mastering latency in frames, non-negative
     * @return the schedule, or empty when the mode counts nothing in
     */
    public static Optional<CountInSchedule> forMode(CountInMode mode, int beatsPerBar, double tempoBpm,
                                                    double sampleRate, int blockSize, int masterLatencyFrames) {
        Objects.requireNonNull(mode, "mode must not be null");
        int totalBeats = mode.getTotalBeats(beatsPerBar);
        if (totalBeats == 0) {
            return Optional.empty();
        }
        return Optional.of(new CountInSchedule(totalBeats, beatsPerBar, tempoBpm, sampleRate, blockSize,
                masterLatencyFrames));
    }

    /** The beats counted in. */
    public int totalBeats() {
        return totalBeats;
    }

    /** The time-signature numerator the accents follow. */
    public int beatsPerBar() {
        return beatsPerBar;
    }

    /** The tempo the beat grid is laid at. */
    public double tempoBpm() {
        return tempoBpm;
    }

    /** The sample rate the frames are in. */
    public double sampleRate() {
        return sampleRate;
    }

    /** The block size the end frame is aligned to. */
    public int blockSize() {
        return blockSize;
    }

    /** The serial master-insert and mastering latency the pad was sized for, in frames. */
    public int masterLatencyFrames() {
        return masterLatencyFrames;
    }

    /** {@code round(totalBeats × sampleRate × 60 / tempoBpm)}. */
    public long countInFrames() {
        return countInFrames;
    }

    /**
     * The leading silence that makes the end frame block-aligned and holds
     * at least {@link #masterLatencyFrames()}: below one block when the
     * latency fits the alignment, whole blocks more otherwise.
     */
    public long pad() {
        return pad;
    }

    /** {@code pad + countInFrames}: a whole number of blocks, the frame the record start runs at. */
    public long totalFrames() {
        return totalFrames;
    }

    /** Frames per beat at the schedule's tempo and rate, unrounded. */
    public double framesPerBeat() {
        return framesPerBeat;
    }

    /**
     * The count-in's length as the MIDI recorder's window:
     * {@code round(totalFrames × 1e6 / sampleRate)} microseconds.
     */
    public long countInDurationMicros() {
        return Math.round(totalFrames * 1_000_000.0 / sampleRate);
    }

    /** How many clicks the count-in plays: one per beat. Inspection for the package's tests. */
    int clickCount() {
        return clickFrames.length;
    }

    /**
     * The frame of click {@code index}, counted from the schedule's first
     * frame. Inspection for the package's tests.
     *
     * @param index the click, {@code 0 <= index < clickCount()}
     * @return the frame the click starts on
     */
    long clickFrame(int index) {
        return clickFrames[index];
    }

    /**
     * Whether click {@code index} is a bar's downbeat and accented.
     * Inspection for the package's tests.
     *
     * @param index the click, {@code 0 <= index < clickCount()}
     * @return {@code true} for an accented click
     */
    boolean isAccent(int index) {
        return clickAccents[index];
    }

    /**
     * Sets what the render thread runs at the end frame — once, before the
     * schedule is installed. The recording pipeline sets the transport's
     * record transition and its capture start gate here; a schedule with no
     * record start fires silently (only its phase changes).
     *
     * @param recordStart the record start, run on the render thread; it must
     *                    be real-time safe
     * @throws IllegalStateException if a record start was already set
     */
    public void setRecordStart(Runnable recordStart) {
        Objects.requireNonNull(recordStart, "recordStart must not be null");
        if (this.recordStart != null) {
            throw new IllegalStateException("the count-in's record start is already set");
        }
        this.recordStart = recordStart;
    }

    /** Whether {@link #setRecordStart(Runnable)} has been called. */
    public boolean hasRecordStart() {
        return recordStart != null;
    }

    /** The phase now; a {@code FIRED} read wins over a concurrent cancel (see the class note). */
    public Phase phase() {
        if (fired) {
            return Phase.FIRED;
        }
        return cancelled ? Phase.CANCELLED : Phase.PENDING;
    }

    /** Whether the render thread may still count: neither fired nor cancelled. Two volatile reads. */
    @RealTimeSafe
    public boolean isPending() {
        return !fired && !cancelled;
    }

    /** Whether the render thread ran the record start. */
    @Override
    public boolean isFired() {
        return fired;
    }

    /**
     * The monotonic clock ({@code System.nanoTime()}) the render thread read
     * immediately before it fired: bar 1's downbeat on the render clock.
     * Meaningful only once {@link #isFired()} reads {@code true}.
     */
    @Override
    public long fireNanos() {
        return fireNanos;
    }

    /** Whether {@link #cancel()} was called, whether or not it won. */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Cancels the count-in from the caller thread: the render thread renders
     * no further click and, if it had not already passed its check, never
     * runs the record start.
     *
     * @return {@code true} if the cancel won — the record start has not run
     *         and will not; {@code false} if the render thread fired first
     *         and the take is recording. The verdict is read after the
     *         cancel is stored; see the class note for the one-instruction
     *         window in which it can be stale
     */
    public boolean cancel() {
        cancelled = true;
        return !fired;
    }

    // ---- render thread ----

    /** The first frame of the block the render thread is about to render. Render thread. */
    @RealTimeSafe
    long cursor() {
        return cursor;
    }

    /** Whether a click is still owed. Render thread. */
    @RealTimeSafe
    boolean hasPendingClick() {
        return nextClick < clickFrames.length;
    }

    /** The frame of the next owed click; call only when {@link #hasPendingClick()}. Render thread. */
    @RealTimeSafe
    long nextClickFrame() {
        return clickFrames[nextClick];
    }

    /** Whether the next owed click is accented; call only when {@link #hasPendingClick()}. Render thread. */
    @RealTimeSafe
    boolean nextClickAccent() {
        return clickAccents[nextClick];
    }

    /** Marks the next owed click as rendered. Render thread. */
    @RealTimeSafe
    void consumeClick() {
        nextClick++;
    }

    /**
     * Whether bar 1's downbeat click — the accented click at
     * {@link #totalFrames()}, the beat the count-in leads into — is still
     * owed from inside the count-in. The running click's own walk starts at
     * the record position advanced by the master chain's latency, so with
     * any latency the downbeat at the record position itself is nobody's
     * (story 328 review): the render thread emits it from the count-in's
     * last blocks, {@code masterLatency} frames early like every count-in
     * click, when the running metronome is enabled. With no latency the
     * emission frame is the end frame itself, past every count-in block, and
     * the running walk sounds the downbeat at the first recorded frame as
     * before — never both. Render thread.
     */
    @RealTimeSafe
    boolean hasPendingDownbeat() {
        return !downbeatEmitted;
    }

    /** The frame of bar 1's downbeat: the end frame. Render thread. */
    @RealTimeSafe
    long downbeatFrame() {
        return totalFrames;
    }

    /** Marks bar 1's downbeat as rendered from inside the count-in. Render thread. */
    @RealTimeSafe
    void consumeDownbeat() {
        downbeatEmitted = true;
    }

    /**
     * Moves the cursor past a rendered block. Render thread.
     *
     * @param numFrames the frames just rendered
     * @return whether the cursor reached the end frame: the record start is due
     */
    @RealTimeSafe
    boolean advance(int numFrames) {
        cursor += numFrames;
        return cursor >= totalFrames;
    }

    /**
     * Runs the record start unless the caller cancelled first. Render thread,
     * at the block boundary {@link #advance(int)} reported. The cancel word is
     * read immediately before {@code FIRED} is stored, so the window in which
     * a cancel can be reported as won while this fires is that one store.
     * The monotonic clock is stamped between the two ({@link #fireNanos()}):
     * one intrinsic read, no allocation.
     *
     * @return whether the record start ran
     */
    @RealTimeSafe
    boolean fire() {
        if (cancelled) {
            return false;
        }
        fireNanos = System.nanoTime();
        fired = true;
        Runnable start = recordStart;
        if (start != null) {
            start.run();
        }
        return true;
    }
}
