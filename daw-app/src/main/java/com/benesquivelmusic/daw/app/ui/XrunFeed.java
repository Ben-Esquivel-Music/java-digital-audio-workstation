package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;
import com.benesquivelmusic.daw.core.audio.health.AudioHealthRecord;
import com.benesquivelmusic.daw.core.audio.performance.XrunDetector;
import com.benesquivelmusic.daw.sdk.audio.BackendDropoutCounters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The xrun feed (story 338, rulings E17 and E19, amendment A15): ticked by the
 * engine-health monitor, it turns what the engine counted into
 * {@link XrunJournal} entries and keeps the SDK's xrun stream fed.
 *
 * <p>Each tick takes one sample of the engine, in the heartbeat watchdog's
 * order — epoch, alive, then the readings, then alive and epoch again — and
 * reads one {@link AudioHealthRecord#totals()} in it. The observed gaps of
 * ruling E17 and E38 are the growth, between two samples, of the record's
 * callback, render and sink fault totals and of the open stream's backend's
 * drop counters (output starved, blocks never played, driver overloads, driver
 * resyncs). They are counted only when both samples saw the render drive alive
 * in the same epoch and the drive had already advanced in that epoch (the
 * start-up allowance, ruling E2); what grew before that first advance is
 * neither counted nor carried. They are written when the stream's watched
 * heartbeat — its device periods where the backend reports them, its render
 * beats otherwise — advanced between the samples. What grew in an interval in
 * which it stood still is carried, per detail, and written with the growth of
 * the first later interval in which it advanced again: a hiccup shorter than
 * the stall window, or a refused block in an interval in which no block was
 * accepted, is counted once the stream moves again. If it never does — the
 * stall verdict closes the stream, and what accrued is the stall's (ruling
 * E19) — or the drive pauses, stops or reopens first, the carried growth is
 * discarded with the sample that is not valid or the epoch that changed. The
 * fault totals are measured from a high-water mark set at the first tick; the
 * drop counters, which are a backend instance's and outlive a reopen, from the
 * sample before.</p>
 *
 * <p>Late blocks — the render drive's late and dropped ticks, read from the
 * engine's {@link XrunDetector} — are written as Late entries and never raise
 * the count (ruling E17). Every gap is also reported to the detector, which
 * offers it on the SDK stream. When a tick finds that the controller rebuilt
 * the detector — a Settings apply, Restart Audio Engine or a reopen the driver
 * asked for — the old one's last late blocks are written and the journal's
 * count restarts (rulings E22 and E148); the first detector a feed sees
 * restarts nothing.</p>
 *
 * <p>Monitor thread only (or a test's tick); not thread-safe.</p>
 */
final class XrunFeed {

    private static final Logger LOG = Logger.getLogger(XrunFeed.class.getName());

    /** The fault categories whose growth is a gap, in {@link #GAP_DETAILS}' order from its second entry. */
    private static final AudioFailureCategory[] FAULTS = {
            AudioFailureCategory.CALLBACK, AudioFailureCategory.RENDER, AudioFailureCategory.SINK};
    /** Every counted detail, in the order an interval's growth is written. */
    private static final XrunJournal.Detail[] GAP_DETAILS = {
            XrunJournal.Detail.OUTPUT_STARVED, XrunJournal.Detail.CALLBACK_FAILED,
            XrunJournal.Detail.PROCESSING_FAILED, XrunJournal.Detail.OUTPUT_REFUSED,
            XrunJournal.Detail.BLOCK_NEVER_PLAYED, XrunJournal.Detail.DRIVER_OVERLOAD, XrunJournal.Detail.DRIVER_RESYNC};

    /** One look at the engine. */
    private record Sample(boolean valid, long epoch, long beats, OptionalLong devicePeriods, long[] totals,
                          BackendDropoutCounters dropouts, OptionalLong resyncArrival, long underrunAnswers) {
    }

    private final AudioEngine engine;
    private final Supplier<XrunDetector> detectors;
    private final XrunJournal journal;

    /** The fault totals' high-water mark; {@code null} before the first tick. */
    private long[] faultMarks;
    /** The sample the next one is measured from; {@code null} when the next valid sample starts afresh. */
    private Sample previous;
    private long epoch = -1L;
    /**
     * Whether the watched heartbeat has advanced since {@link #startUp}: the start-up allowance is over. Where
     * the backend reports device periods, blocks refused before the device's first period are never counted.
     */
    private boolean started;
    /** The sample the start-up is measured from: the first of the epoch, or the first after the watched kind changed. */
    private Sample startUp;
    private XrunDetector detector;
    /** The detector's late and dropped ticks already written. */
    private long lateTicksWritten;
    /** The gaps this feed reported to {@link #detector}, which its dropped count includes. */
    private long gapsReported;
    /** What grew in intervals in which the heartbeat stood still, per {@link #GAP_DETAILS} entry, not yet written. */
    private final long[] carried = new long[GAP_DETAILS.length];

    /**
     * A feed of {@code journal}.
     *
     * @param engine    the engine whose counts are read
     * @param detectors the engine's current xrun detector, read every tick
     * @param journal   the journal written
     */
    XrunFeed(AudioEngine engine, Supplier<XrunDetector> detectors, XrunJournal journal) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.detectors = Objects.requireNonNull(detectors, "detectors must not be null");
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
    }

    /** One tick: the late blocks, then the gaps since the previous sample. */
    void tick() {
        XrunDetector current = followTheDetector();
        Sample sample = sample();
        long[] faultGrowth = faultGrowth(sample.totals());
        if (current != null) {
            writeLateBlocks(current);
        }
        long gaps = countGaps(sample, faultGrowth);
        if (gaps > 0L && current != null) {
            current.reportDropped(gaps);
            gapsReported += gaps;
        }
    }

    private XrunDetector followTheDetector() {
        XrunDetector current = detectors.get();
        if (current == detector) {
            return current;
        }
        if (detector == null) {
            lateTicksWritten = current == null ? 0L : current.getLateCount() + current.getDroppedCount();
        } else {
            writeLateBlocks(detector);
            journal.restartCount();
            lateTicksWritten = 0L;
        }
        detector = current;
        gapsReported = 0L;
        return current;
    }

    private Sample sample() {
        // Read before the drop counters: an answer sampled before a forget is the forgotten driver's (ruling E116).
        long underrunAnswers = journal.underrunAnswers();
        AudioHealthRecord health = engine.audioHealth();
        long epochBefore = health.renderDriveEpoch();
        boolean aliveBefore = engine.isRenderDriveAlive();
        long[] totals = health.totals();
        long beats = health.renderDriveBeats();
        OptionalLong periods = health.deviceCallbackPeriods();
        BackendDropoutCounters dropouts = engine.dropoutCounters();
        OptionalLong resyncArrival = engine.newestDriverResyncNanos();
        boolean aliveAfter = engine.isRenderDriveAlive();
        long epochAfter = health.renderDriveEpoch();
        boolean valid = aliveBefore && aliveAfter && epochBefore == epochAfter;
        return new Sample(valid, epochAfter, beats, periods, totals, dropouts, resyncArrival, underrunAnswers);
    }

    /** Each fault category's growth since the previous tick; nothing at the first. */
    private long[] faultGrowth(long[] totals) {
        long[] growth = new long[FAULTS.length];
        if (faultMarks == null) {
            faultMarks = new long[FAULTS.length];
            for (int i = 0; i < FAULTS.length; i++) {
                faultMarks[i] = totals[FAULTS[i].code()];
            }
            return growth;
        }
        for (int i = 0; i < FAULTS.length; i++) {
            long total = totals[FAULTS[i].code()];
            growth[i] = Math.max(0L, total - faultMarks[i]);
            faultMarks[i] = Math.max(faultMarks[i], total);
        }
        return growth;
    }

    /** Writes and returns the gaps between the previous sample and {@code sample}, where the gates allow. */
    private long countGaps(Sample sample, long[] faultGrowth) {
        if (!sample.valid()) {
            previous = null;
            Arrays.fill(carried, 0L);
            return 0L;
        }
        journal.reportsOutputStarvation(sample.dropouts().reportsOutputStarvation(), sample.underrunAnswers());
        if (sample.epoch() != epoch) {
            epoch = sample.epoch();
            startAfresh(sample);
            return 0L;
        }
        Sample before = previous;
        previous = sample;
        if (before == null) {
            return 0L;
        }
        if (sample.devicePeriods().isPresent() != startUp.devicePeriods().isPresent()) {
            startAfresh(sample);
            return 0L;
        }
        if (!started) {
            started = heartbeatAdvanced(startUp, sample);
            return 0L;
        }
        long[] growth = intervalGrowth(before.dropouts(), sample.dropouts(), faultGrowth);
        if (!heartbeatAdvanced(before, sample)) {
            for (int i = 0; i < growth.length; i++) {
                carried[i] += growth[i];
            }
            return 0L;
        }
        List<String> logged = new ArrayList<>();
        long gaps = 0L;
        for (int i = 0; i < growth.length; i++) {
            gaps += write(GAP_DETAILS[i], carried[i] + growth[i], logged, sample.resyncArrival());
            carried[i] = 0L;
        }
        if (gaps > 0L) {
            LOG.info("audio.xrun: " + gaps + (gaps == 1L ? " dropout: " : " dropouts: ") + String.join(", ", logged));
        }
        return gaps;
    }

    /** The start-up allowance begins again at {@code sample}; nothing grown before it is counted or carried. */
    private void startAfresh(Sample sample) {
        started = false;
        startUp = sample;
        previous = sample;
        Arrays.fill(carried, 0L);
    }

    /** Each {@link #GAP_DETAILS} entry's growth between two samples of one epoch. */
    private static long[] intervalGrowth(BackendDropoutCounters was, BackendDropoutCounters now,
                                         long[] faultGrowth) {
        return new long[] {
                growth(was.outputStarvedPeriods(), now.outputStarvedPeriods()),
                faultGrowth[0], faultGrowth[1], faultGrowth[2],
                growth(was.outputDiscardedBlocks(), now.outputDiscardedBlocks()),
                growth(was.driverOverloads(), now.driverOverloads()),
                growth(was.driverResyncs(), now.driverResyncs())};
    }

    /**
     * Whether the stream's watched heartbeat advanced between the samples: its device periods where the backend
     * reports them, its render beats otherwise. What grew while it stood still waits for the next interval in which
     * it advances; a stall verdict closes the stream first, so that growth is the stall's and never counted
     * (ruling E19).
     */
    private static boolean heartbeatAdvanced(Sample before, Sample after) {
        OptionalLong was = before.devicePeriods();
        OptionalLong now = after.devicePeriods();
        if (was.isEmpty() && now.isEmpty()) {
            return after.beats() > before.beats();
        }
        return was.isPresent() && now.isPresent() && now.getAsLong() > was.getAsLong();
    }

    /** A backend count's growth; a count that fell (a fresh backend instance) starts afresh. */
    private static long growth(long was, long now) {
        return now >= was ? now - was : 0L;
    }

    private long write(XrunJournal.Detail detail, long count, List<String> logged, OptionalLong resyncArrival) {
        if (count <= 0L) {
            return 0L;
        }
        if (detail == XrunJournal.Detail.DRIVER_RESYNC && resyncArrival.isPresent()) {
            journal.append(detail, List.of(), count, resyncArrival.getAsLong());
        } else {
            journal.append(detail, List.of(), count);
        }
        logged.add(count + " " + detail.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        return count;
    }

    /** Writes the late and dropped ticks of {@code source} not yet written, as one Late entry. */
    private void writeLateBlocks(XrunDetector source) {
        long ticks = source.getLateCount() + source.getDroppedCount() - (source == detector ? gapsReported : 0L);
        long fresh = ticks - lateTicksWritten;
        if (fresh <= 0L) {
            return;
        }
        lateTicksWritten = ticks;
        double periodMillis = source.getDeadlineNanos() / 1_000_000.0;
        double tookMillis = (source.getDeadlineNanos() + source.getLastOvershootNanos()) / 1_000_000.0;
        journal.append(XrunJournal.Detail.LATE_BLOCK, List.of(tookMillis, periodMillis), fresh);
        LOG.log(Level.FINE, () -> "audio.xrun: " + fresh + " late block(s), the newest " + tookMillis + " ms of a "
                + periodMillis + " ms period");
    }
}
