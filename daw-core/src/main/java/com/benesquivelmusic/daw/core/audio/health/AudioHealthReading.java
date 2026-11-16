package com.benesquivelmusic.daw.core.audio.health;

import com.benesquivelmusic.daw.sdk.audio.BackendThreadCounters;

import java.util.Objects;
import java.util.Optional;

/**
 * One cut of an {@link AudioHealthRecord}, taken by
 * {@link AudioHealthRecord#read} (story 337, rulings E79 and E82): every
 * category's total, the newest failure of each, the backend report the totals
 * include, the fault of an input halt it counts, the render-drive heartbeat
 * and epoch, the stream opens and the lost samples, all as that one reading
 * saw them.
 *
 * <p>The record took the lane counts first, then handed every sample to the
 * reading's consumer, then read one report of each attached source; if a
 * source was folded while those reports were being read, it dropped them and
 * read the still-attached sources once more, and used only that second read
 * (story 337, ruling E98). So a total here never counts a lane failure whose
 * sample is still to come, and the backend's contribution to
 * {@code audio.callback}, {@code audio.input} and {@code audio.device}, its
 * input halts and its last faults all come from one report per source: the
 * report this reading used for a source still attached, and the report its
 * fold folded for one closed since.</p>
 *
 * <p>A notice names the device whose count grew: the last failure of a
 * category the backends count is the fault of the counter source whose count
 * of it grew, never an older fault of another source (story 337, ruling
 * E94); when several grew, of the one attached last among those that kept a
 * fault (rulings E99 and E100).</p>
 *
 * <p>An immutable value: the record hands it arrays it never touches again,
 * and nothing here exposes them.</p>
 */
public final class AudioHealthReading {

    private final long[] totals;
    private final Throwable[] lastFailures;
    private final BackendThreadCounters backend;
    private final Throwable inputHaltFault;
    private final long renderDriveBeats;
    private final long renderDriveEpoch;
    private final long streamOpens;
    private final long lostSamples;

    /**
     * Creates a reading over arrays the caller never touches again.
     *
     * @param totals           per-code totals, {@link AudioFailureCodes#COUNT} long
     * @param lastFailures     per-code newest failures, {@code null} for none
     * @param backend          the one backend report the totals include
     * @param inputHaltFault   the fault of the input halt this reading counts, {@code null} for none
     * @param renderDriveBeats the render-drive heartbeat
     * @param renderDriveEpoch render-drive lanes claimed so far
     * @param streamOpens      stream opens counted so far
     * @param lostSamples      descriptors dropped so far
     */
    AudioHealthReading(long[] totals, Throwable[] lastFailures, BackendThreadCounters backend,
                       Throwable inputHaltFault, long renderDriveBeats, long renderDriveEpoch,
                       long streamOpens, long lostSamples) {
        this.totals = Objects.requireNonNull(totals, "totals must not be null");
        this.lastFailures = Objects.requireNonNull(lastFailures, "lastFailures must not be null");
        this.backend = Objects.requireNonNull(backend, "backend must not be null");
        this.inputHaltFault = inputHaltFault;
        this.renderDriveBeats = renderDriveBeats;
        this.renderDriveEpoch = renderDriveEpoch;
        this.streamOpens = streamOpens;
        this.lostSamples = lostSamples;
    }

    /**
     * One category's total: retired and live lanes, plus the backend report
     * ({@code CALLBACK} += callback faults, {@code INPUT} += lost input blocks
     * and input halts, {@code DEVICE} += device-notification faults).
     *
     * @param category the category
     * @return the total, monotonic across readings
     */
    public long total(AudioFailureCategory category) {
        return totals[Objects.requireNonNull(category, "category must not be null").code()];
    }

    /**
     * The newest failure of {@code category}: from the live lanes (most
     * recently claimed first), else from retired lanes, else — for the
     * categories backends count — the last fault of that category of the
     * counter source whose count of it grew most recently, as readings saw it:
     * of the source attached last among those whose count of it grew within
     * one reading and kept a fault, and none when none of them kept one, never
     * an older fault of another source (story 337, rulings E94 and E99). A
     * reading in which no source's count of the category grew answers what
     * the previous reading answered.
     *
     * @param category the category
     * @return the newest failure, if any
     */
    public Optional<Throwable> lastFailure(AudioFailureCategory category) {
        return Optional.ofNullable(
                lastFailures[Objects.requireNonNull(category, "category must not be null").code()]);
    }

    /**
     * The fault of the input halt this reading counts: the last input fault
     * of a counter source whose input halts grew since the previous reading
     * — of the newest-attached such source that kept a fault when several
     * did, including one closed since then (story 337, rulings E94 and E99).
     * Never the fault of a lost block or of a source whose halts did not grow.
     *
     * @return the halted input's fault; empty when no source's input halts
     *         grew, or none of those whose halts grew kept a fault
     */
    public Optional<Throwable> inputHaltFault() {
        return Optional.ofNullable(inputHaltFault);
    }

    /**
     * Backend input threads that ended on a fault they could not continue
     * past (story 337, ruling E17), from this reading's backend report.
     *
     * @return the input-halt count, monotonic across readings
     */
    public long inputHalts() {
        return backend.inputHalts();
    }

    /**
     * The backend report this reading's totals include.
     *
     * @return the report; never {@code null}
     */
    public BackendThreadCounters backendCounters() {
        return backend;
    }

    /**
     * The render-drive heartbeat: blocks the engine's render drive has handed
     * to the backend, across every stream (story 337, ruling E5).
     *
     * @return the render-drive beat count, monotonic across readings
     */
    public long renderDriveBeats() {
        return renderDriveBeats;
    }

    /**
     * How many render-drive lanes had been claimed — one per stream open and
     * per resume after a pause.
     *
     * @return the render-drive epoch, monotonic across readings
     */
    public long renderDriveEpoch() {
        return renderDriveEpoch;
    }

    /**
     * How many times a stream's backend had been attached — one per stream
     * open and per retained failed open, never per resume (story 337, ruling
     * E87).
     *
     * @return the stream-open count, monotonic across readings
     */
    public long streamOpens() {
        return streamOpens;
    }

    /**
     * How many descriptors had been dropped because a lane's ring had no room
     * for them, or because a retired lane nobody drained was evicted — samples
     * only; the totals stay exact.
     *
     * @return the lost-sample count, monotonic across readings
     */
    public long lostSamples() {
        return lostSamples;
    }
}
