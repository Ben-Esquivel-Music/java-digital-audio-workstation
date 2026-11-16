package com.benesquivelmusic.daw.app.diagnostics;

import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;
import com.benesquivelmusic.daw.core.audio.health.AudioFailureSample;
import com.benesquivelmusic.daw.core.audio.health.AudioHealthReading;
import com.benesquivelmusic.daw.core.audio.health.AudioHealthRecord;
import com.benesquivelmusic.daw.core.automation.PluginParameterTarget;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The audio health drain (story 337, ruling E42; Failure Surfacing book §3.2
 * channel B, §4.3): once per FX pulse it reads what the real-time threads
 * recorded into the engine's {@link AudioHealthRecord} — counters, stack
 * samples, heartbeats — and turns it into failure notices through the
 * {@link FailureFunnel}, so a render thread only ever records (book §2.3
 * "The RT thread records; it never surfaces").
 *
 * <p><strong>One frame</strong>, in this order:</p>
 * <ol>
 *   <li><em>One reading.</em> {@link AudioHealthRecord#read}: every count, then
 *       every sampled descriptor, then one counter report of each attached
 *       device, read again if a device is closed or reopened while they are
 *       read (story 337, rulings E79, E82 and E98). A sample is kept until a
 *       report of its category uses it, so a sample that arrives a frame
 *       before its count still names its subject; per category the drain
 *       keeps the distinct anchors sampled since that category's last notice
 *       (story 337, ruling E40).</li>
 *   <li><em>Deltas.</em> Each category's total in the reading is compared with
 *       its baseline; baselines start at zero, so nothing counted before the
 *       drain existed is lost (story 337, ruling E42) — the window builds one
 *       drain for its engine, for the window's life, so the record's history is
 *       reported once (story 337, ruling E73) — and a delta is never
 *       negative.</li>
 *   <li><em>Halt.</em> A terminal {@code audio.render.halted} record is reacted
 *       to at once (story 337, ruling E11), and acted on only while the open
 *       stream's render drive is the drive that halted itself (story 337,
 *       ruling E69): the transport is stopped through the Stop gesture's path,
 *       the stream is closed once the take allows it — retried on every later
 *       frame until it is, through a query that never restarts the take's own
 *       input cleanup (story 337, ruling E66) — and one notice is escalated and
 *       latched: the halt copy with Restart Audio Engine, or, for a
 *       {@link VirtualMachineError}, the FATAL copy with Open Log Folder (story
 *       337, rulings E13 and E45). A halt whose stream was already reaped,
 *       replaced, paused, closed or being reopened stops and closes nothing,
 *       and its notice has no context sentence (story 337, rulings E70 and
 *       E72).</li>
 *   <li><em>Input halt.</em> A backend input thread that ended on a fault
 *       escalates at once with the device-fault copy and Restart.</li>
 *   <li><em>Fold.</em> A first worker failure in the same frame as a first render
 *       failure is one notice, the render one, whose record names the worker's
 *       anchor too (story 337, ruling E27).</li>
 *   <li><em>Streaming categories</em> ({@code audio.callback}, {@code .render},
 *       {@code .sink}, {@code .pacing}, {@code .input}, {@code .worker},
 *       {@code .dsp}, {@code .midi}, {@code .plugin}, {@code .device}) are
 *       fingerprinted by guard site and counted by their exact delta (story 337,
 *       rulings E39 and E40): a first occurrence is a WARNING at once, repeats
 *       inside the 30 s window are counted, and a fingerprint seen in three
 *       consecutive one-second buckets of the drain's monotonic clock escalates
 *       once to the ERROR fact (story 337, ruling E44).</li>
 *   <li><em>One-shot categories</em> ({@code audio.automation},
 *       {@code audio.config}) are reported once per sampled subject, each
 *       report using as many of its subject's samples as it counted (story 337,
 *       ruling E79), and are never sustained (story 337, ruling E40).</li>
 *   <li><em>The engine fault.</em> An escalation latches the
 *       {@linkplain #engineFaultProperty() engine-fault fact} (story 337, ruling
 *       E41); while it is latched its count and last time follow the
 *       fingerprint, and it clears on a successful restart
 *       ({@link #clearEngineFault()}) or after one fault-free second of
 *       render-drive heartbeats on a render drive started after the latch (a
 *       reopened or resumed stream; for a halt, any drive started after the
 *       halted one, story 337, ruling E71; for an input halt, only on a stream
 *       opened after it, story 337, ruling E87), with no delta in a
 *       restart-eligible category, {@code audio.render} or any latched category
 *       (story 337, rulings E59 and E63).</li>
 * </ol>
 *
 * <p>Restart Audio Engine is offered only for what a stream restart can cure —
 * the device-side categories and the halt — and Open Log Folder for the
 * render-pipeline categories (story 337, ruling E13). A notice names the
 * device, the plugin, the effect or the setting when a sample identifies one
 * ({@link FailureComponentName}, story 337, ruling E89).</p>
 *
 * <p><strong>Threading.</strong> FX thread only: {@link #pulse()} is a pulse
 * participant of the window's dispatcher, and {@link #clearEngineFault()} is
 * posted to the FX thread by the restart. The drain is the record's one
 * reader of {@link AudioHealthRecord#read}. A failure of a collaborator it
 * calls — the halt response, the device name — never costs the frame its
 * reports: it is held and rethrown at the end of the frame, where the
 * dispatcher's per-participant guard reports it (story 337, ruling E33).</p>
 */
public final class AudioHealthDrain {

    /** Consecutive one-second buckets that make a fingerprint sustained (story 337, ruling E44). */
    static final int SUSTAINED_BUCKETS = 3;

    /** One bucket of the drain's monotonic clock. */
    static final long BUCKET_NANOS = 1_000_000_000L;

    /**
     * How long the render drive must beat without a fault, on a render drive started after the latch, to clear
     * the fact (story 337, ruling E41).
     */
    static final long FAULT_FREE_NANOS = 1_000_000_000L;

    /** The most distinct anchors a notice's record lists. */
    static final int MAX_ANCHORS = 16;

    /** The subject key of a one-shot occurrence whose every sample the ring dropped. */
    static final String UNKNOWN_SUBJECT = "unknown";

    /** The streaming categories, in code order. */
    private static final List<AudioFailureCategory> STREAMING = List.of(AudioFailureCategory.CALLBACK,
            AudioFailureCategory.RENDER, AudioFailureCategory.SINK, AudioFailureCategory.PACING,
            AudioFailureCategory.INPUT, AudioFailureCategory.WORKER, AudioFailureCategory.DSP,
            AudioFailureCategory.MIDI, AudioFailureCategory.PLUGIN, AudioFailureCategory.DEVICE);

    /** The categories reported once per sampled subject. */
    private static final List<AudioFailureCategory> ONE_SHOT =
            List.of(AudioFailureCategory.AUTOMATION, AudioFailureCategory.CONFIG);

    /** With the latched categories (story 337, ruling E63), a delta in any of these keeps a latched fact from clearing. */
    private static final Set<AudioFailureCategory> KEEPS_THE_FAULT = Set.of(AudioFailureCategory.CALLBACK,
            AudioFailureCategory.RENDER, AudioFailureCategory.RENDER_HALTED, AudioFailureCategory.SINK,
            AudioFailureCategory.PACING, AudioFailureCategory.INPUT, AudioFailureCategory.DEVICE);

    /**
     * What the window does when the render drive halts (story 337, ruling E11):
     * the Stop gesture's paths, on the FX thread, acting only on the stream
     * whose render drive halted (story 337, ruling E69).
     */
    public interface HaltResponse {

        /**
         * Stops the transport through the normal Stop when it is rolling or a
         * take is in flight, while the open stream's render drive is the one
         * that halted itself; the take ends through its normal finalization
         * and keeps every block captured before the halt. The Stop closes no
         * stream: the halt's own close follows on the same frame.
         *
         * @return what the halt's Stop did, which picks the notice's context sentence (ruling E101); or
         *         {@link HaltContext#STREAM_REPLACED} when the halted stream was no longer the open one, in
         *         which case nothing was stopped and neither close method is called for this halt (story 337,
         *         rulings E69, E70 and E72)
         */
        HaltContext stopTransportForHalt();

        /**
         * The halt frame's own attempt: closes the engine's stream, even with
         * graph instruments, once the record coordinator no longer defers the
         * input-stream stop — only while its render drive is the one that
         * halted, and without waiting for the engine's lifecycle lock; never
         * reopens it. Only this attempt may act on the take's input-stream
         * stop (story 337, rulings E66 and E69).
         *
         * @return {@code true} when nothing more is owed; {@code false} while the take still defers the
         *         stop or another lifecycle step holds the engine's lifecycle lock, and the drain retries on its
         *         next frame through {@link #retryCloseStreamForHalt()}
         */
        boolean closeStreamForHalt();

        /**
         * A later frame's retry of the halted stream's close: asks the record
         * coordinator, without side effects, whether the take still defers the
         * input-stream stop, and closes the stream once it does not — still
         * only while its render drive is the one that halted, and without
         * waiting for the lifecycle lock — so a retry never restarts a failed
         * input cleanup or repeats its toast (story 337, rulings E66 and E69).
         *
         * @return {@code true} when nothing more is owed; {@code false} while the take still defers the
         *         stop or the lifecycle lock is held, and the drain asks again on its next frame
         */
        boolean retryCloseStreamForHalt();
    }

    /**
     * The drain's collaborators.
     *
     * @param record             the engine's audio health record
     * @param funnel             the failure funnel the notices go through
     * @param clockNanos         the monotonic clock of the sustained buckets and the fault-free second
     *                           ({@code System::nanoTime} in production — the funnel's limiter reads the same
     *                           clock)
     * @param wallClock          the wall clock of the engine fault's first and last times
     * @param deviceName         the open device's display name, or the backend's; read only when a notice
     *                           names the device
     * @param restartAudioEngine Restart Audio Engine: what the action runs, on the FX thread; it must not block it
     * @param haltResponse       what the window does when the render drive halts
     */
    public record Collaborators(AudioHealthRecord record, FailureFunnel funnel, LongSupplier clockNanos,
                                InstantSource wallClock, Supplier<String> deviceName, Runnable restartAudioEngine,
                                HaltResponse haltResponse) {

        public Collaborators {
            Objects.requireNonNull(record, "record must not be null");
            Objects.requireNonNull(funnel, "funnel must not be null");
            Objects.requireNonNull(clockNanos, "clockNanos must not be null");
            Objects.requireNonNull(wallClock, "wallClock must not be null");
            Objects.requireNonNull(deviceName, "deviceName must not be null");
            Objects.requireNonNull(restartAudioEngine, "restartAudioEngine must not be null");
            Objects.requireNonNull(haltResponse, "haltResponse must not be null");
        }
    }

    private final AudioHealthRecord record;
    private final FailureFunnel funnel;
    private final FailureCopy copy;
    private final LongSupplier clockNanos;
    private final InstantSource wallClock;
    private final Supplier<String> deviceName;
    private final HaltResponse haltResponse;
    private final NoticeAction restartAction;
    private final NoticeAction openLogFolderAction;
    private final ReadOnlyObjectWrapper<Optional<EngineFault>> engineFault =
            new ReadOnlyObjectWrapper<>(this, "engineFault", Optional.empty());

    /** Each category's total as last read; starts at zero (story 337, ruling E42). */
    private final long[] baselines = new long[AudioFailureCategory.values().length];
    private final Map<AudioFailureCategory, Track> tracks = new EnumMap<>(AudioFailureCategory.class);
    /** The samples taken from the record and not yet used in a report (story 337, ruling E79). */
    private final UnreportedSamples unreported = new UnreportedSamples();
    private long inputHaltBaseline;
    private long beatsBaseline;
    /**
     * Whether the halted stream's close is still owed: retried on every frame until it is done (story 337, rulings
     * E11 and E66).
     */
    private boolean closeOwed;
    /** Every fingerprint escalated since the fact last cleared; clearing releases each one's latch. */
    private final Set<Fingerprint> latched = new LinkedHashSet<>();
    /**
     * The categories of every fingerprint escalated since the fact last cleared: a delta in any of them keeps the
     * fact latched (story 337, ruling E63).
     */
    private final Set<AudioFailureCategory> latchedCategories = EnumSet.noneOf(AudioFailureCategory.class);
    /** The latched fact's category, its stream epoch, and whether it is a halt — while a fact is latched. */
    private Optional<Latch> latch = Optional.empty();
    /** When the current fault-free stretch on a render drive started after the latch began. */
    private OptionalLong faultFreeSince = OptionalLong.empty();
    /**
     * The stream opens counted when the newest input halt escalated, while its fact is latched: the fact clears only
     * on a stream opened after that halt, or a successful restart (story 337, ruling E87).
     */
    private OptionalLong inputHaltStreamOpens = OptionalLong.empty();

    /**
     * A drain over {@code collaborators}; nothing is read until the first
     * {@link #pulse()}.
     *
     * @param collaborators the collaborators
     */
    public AudioHealthDrain(Collaborators collaborators) {
        Objects.requireNonNull(collaborators, "collaborators must not be null");
        this.record = collaborators.record();
        this.funnel = collaborators.funnel();
        this.copy = funnel.copy();
        this.clockNanos = collaborators.clockNanos();
        this.wallClock = collaborators.wallClock();
        this.deviceName = collaborators.deviceName();
        this.haltResponse = collaborators.haltResponse();
        this.restartAction = new NoticeAction(copy.restartAudioEngineLabel(), collaborators.restartAudioEngine());
        this.openLogFolderAction = funnel.openLogFolderNotice();
        for (AudioFailureCategory category : AudioFailureCategory.values()) {
            if (!ONE_SHOT.contains(category)) {
                tracks.put(category, new Track(category));
            }
        }
    }

    /**
     * One frame of the drain — the body of its pulse participant. FX thread.
     * Every report reaches the funnel even when a collaborator throws; the
     * first such failure is rethrown at the end of the frame, later ones
     * suppressed onto it.
     */
    public void pulse() {
        Frame frame = new Frame(clockNanos.getAsLong(), wallClock.instant());
        frame.reading = record.read(frame::take);
        long[] delta = new long[baselines.length];
        for (AudioFailureCategory category : AudioFailureCategory.values()) {
            int code = category.code();
            long total = frame.reading.total(category);
            delta[code] = Math.max(0L, total - baselines[code]);
            baselines[code] = Math.max(baselines[code], total);
        }
        long inputHalts = frame.reading.inputHalts();
        long inputHaltDelta = Math.max(0L, inputHalts - inputHaltBaseline);
        inputHaltBaseline = Math.max(inputHaltBaseline, inputHalts);
        long beats = frame.reading.renderDriveBeats();
        boolean beatsAdvanced = beats > beatsBaseline;
        beatsBaseline = Math.max(beatsBaseline, beats);

        boolean halted = delta(delta, AudioFailureCategory.RENDER_HALTED) > 0;
        if (halted) {
            reactToHalt(frame, delta);
        } else if (closeOwed) {
            retryTheHaltedStreamClose(frame);
        }
        boolean inputHalted = inputHaltDelta > 0;
        if (inputHalted) {
            escalateInputHalt(frame, Math.max(delta(delta, AudioFailureCategory.INPUT), inputHaltDelta));
        }
        boolean fold = !halted
                && delta(delta, AudioFailureCategory.WORKER) > 0 && delta(delta, AudioFailureCategory.RENDER) > 0
                && !funnel.hasSeen(track(AudioFailureCategory.WORKER).fingerprint)
                && !funnel.hasSeen(track(AudioFailureCategory.RENDER).fingerprint);
        for (AudioFailureCategory category : STREAMING) {
            long weight = delta(delta, category);
            if (weight == 0 || category == AudioFailureCategory.INPUT && inputHalted) {
                continue;
            }
            reportStreaming(frame, category, weight, halted, fold);
        }
        for (AudioFailureCategory category : ONE_SHOT) {
            long weight = delta(delta, category);
            if (weight > 0) {
                reportOneShot(frame, category, weight);
            }
        }
        followTheFault(frame, delta, beatsAdvanced);
        frame.rethrowDeferred();
    }

    /**
     * The latched engine-fault fact (story 337, ruling E41): empty while none
     * is latched. Story 338's engine-state cell renders it. FX thread.
     *
     * @return the read-only property
     */
    public ReadOnlyObjectProperty<Optional<EngineFault>> engineFaultProperty() {
        return engineFault.getReadOnlyProperty();
    }

    /**
     * The latched engine-fault fact, if any. FX thread.
     *
     * @return the fact
     */
    public Optional<EngineFault> engineFault() {
        return engineFault.get();
    }

    /**
     * Clears the engine-fault fact after a successful Restart Audio Engine —
     * one that returned normally and, if a stream was open when it began, left
     * one open (story 337, rulings E12, E41 and E62): every latched fingerprint follows the
     * window rule again, the sustained buckets start over, and a halted
     * stream's close is no longer owed — the restart closed and reopened it.
     * FX thread.
     */
    public void clearEngineFault() {
        closeOwed = false;
        clearTheFault();
    }

    // ── the halt ────────────────────────────────────────────────────────────

    private void reactToHalt(Frame frame, long[] delta) {
        Track track = track(AudioFailureCategory.RENDER_HALTED);
        Optional<Throwable> cause = unreported.newestFailure(AudioFailureCategory.RENDER_HALTED)
                .or(() -> frame.reading.lastFailure(AudioFailureCategory.RENDER_HALTED));
        HaltContext context = HaltContext.IDLE;
        try {
            HaltContext answered = haltResponse.stopTransportForHalt();
            context = answered == null ? HaltContext.IDLE : answered;
        } catch (Throwable failure) {
            frame.defer(failure);
        }
        // A halt whose stream was already replaced stopped nothing and owes no close (story 337, rulings E70, E72).
        boolean replaced = context == HaltContext.STREAM_REPLACED;
        if (!replaced) {
            closeOwed = true;
            closeTheHaltedStream(frame);
        }
        // The notice is posted and the fact latched whatever the response did (story 337, ruling E11, step 3).
        long weight = delta(delta, AudioFailureCategory.RENDER_HALTED);
        track.occurred(weight, frame);
        Optional<Throwable> unwrapped = cause.map(GuardedThrowables::unwrapCarriers);
        boolean fatal = unwrapped.filter(VirtualMachineError.class::isInstance).isPresent();
        String base = fatal
                ? copy.audioHaltedFatal(cause.orElseThrow(), context)
                : copy.audioHalted(FailureComponentName.pluginAnchorName(track.category, unwrapped),
                        reason(cause, track.site), context);
        List<String> anchors = new ArrayList<>(track.anchors);
        // The render failures that led to the halt are named in its record too.
        for (String anchor : track(AudioFailureCategory.RENDER).anchors) {
            if (!anchors.contains(anchor) && anchors.size() < MAX_ANCHORS) {
                anchors.add(anchor);
            }
        }
        // A replaced halt's drive never beats again; the drive running now, or the next one, started after it, so
        // its fact clears on that drive (story 337, ruling E71).
        long epoch = frame.reading.renderDriveEpoch() - (replaced ? 1L : 0L);
        escalate(track, new RealTimeReport(FailureFact.Origin.RT_GUARD,
                fatal ? FailureFact.Severity.FATAL : FailureFact.Severity.ERROR, track.fingerprint, weight, cause,
                anchors, track.site, base, fatal ? openLogFolderAction : restartAction), frame, true, epoch);
        unreported.clear(AudioFailureCategory.RENDER_HALTED);
    }

    private void closeTheHaltedStream(Frame frame) {
        try {
            closeOwed = !haltResponse.closeStreamForHalt();
        } catch (Throwable failure) {
            frame.defer(failure);
        }
    }

    /** A later frame's retry: only the halt frame's own attempt may act on the take (story 337, ruling E66). */
    private void retryTheHaltedStreamClose(Frame frame) {
        try {
            closeOwed = !haltResponse.retryCloseStreamForHalt();
        } catch (Throwable failure) {
            frame.defer(failure);
        }
    }

    private void escalateInputHalt(Frame frame, long weight) {
        Track track = track(AudioFailureCategory.INPUT);
        track.occurred(weight, frame);
        // The halted input's own fault, never a lost block's or an input's whose halts did not grow (story 337,
        // ruling E94).
        Optional<Throwable> newest = unreported.newestFailure(AudioFailureCategory.INPUT)
                .or(() -> frame.reading.inputHaltFault());
        String base = copy.audioSustained(AudioFailureCategory.INPUT, Optional.empty(), frame.device(),
                track.total, track.firstSeen.orElse(frame.wall), reason(newest, track.site));
        // A resume restarts the drive, not the input: only a stream opened after the newest input halt clears its
        // fact (story 337, ruling E87).
        inputHaltStreamOpens = OptionalLong.of(frame.reading.streamOpens());
        escalate(track, report(track, FailureFact.Severity.ERROR, weight, newest, List.copyOf(track.anchors), base,
                restartAction), frame, false, frame.reading.renderDriveEpoch());
        unreported.clear(AudioFailureCategory.INPUT);
    }

    // ── the streaming categories ────────────────────────────────────────────

    private void reportStreaming(Frame frame, AudioFailureCategory category, long weight, boolean halted,
                                 boolean fold) {
        Track track = track(category);
        boolean sustained = track.occurred(weight, frame);
        Optional<Throwable> newest = unreported.newestFailure(category)
                .or(() -> frame.reading.lastFailure(category));
        Optional<String> name = FailureComponentName.of(category, unreported.newestSubject(category), newest);
        // Every branch below reports this category, so its kept samples are used (story 337, ruling E79).
        unreported.clear(category);
        String reason = reason(newest, track.site);
        NoticeAction action = actionOf(category);
        if (halted && category == AudioFailureCategory.RENDER || fold && category == AudioFailureCategory.WORKER) {
            // Counted, but no notice of its own: the halt's notice, or the render notice it is folded into,
            // names its anchors (story 337, ruling E27); the funnel writes its record without a toast (ruling E65).
            boolean surfaces = surfaces(track, frame.now);
            funnel.foldRealTime(report(track, FailureFact.Severity.WARNING, weight, newest, List.of(),
                    copy.audioFirst(category, name, deviceFor(category, frame), reason, 0L), action));
            if (surfaces) {
                track.noticeShown(frame.now);
            } else {
                track.anchors.clear();
            }
            return;
        }
        if (sustained && !funnel.isEscalated(track.fingerprint)) {
            String base = copy.audioSustained(category, name, deviceFor(category, frame), track.total,
                    track.firstSeen.orElse(frame.wall), reason);
            escalate(track, report(track, FailureFact.Severity.ERROR, weight, newest, List.copyOf(track.anchors), base,
                    action), frame, false, frame.reading.renderDriveEpoch());
            return;
        }
        List<String> anchors = new ArrayList<>(track.anchors);
        if (fold && category == AudioFailureCategory.RENDER) {
            for (String anchor : track(AudioFailureCategory.WORKER).anchors) {
                if (!anchors.contains(anchor) && anchors.size() < MAX_ANCHORS) {
                    anchors.add(anchor);
                }
            }
        }
        boolean surfaces = surfaces(track, frame.now);
        funnel.reportRealTime(report(track, FailureFact.Severity.WARNING, weight, newest, anchors,
                copy.audioFirst(category, name, deviceFor(category, frame), reason, 0L), action));
        if (surfaces) {
            track.noticeShown(frame.now);
        }
    }

    /**
     * Whether the funnel's limiter will surface the next report of
     * {@code track}'s fingerprint — its first occurrence, or one a window after
     * its last notice while no escalation is latched — judged on the same
     * monotonic clock. It decides only when the anchors listed since the last
     * notice start over; the limiter alone decides what is shown.
     */
    private boolean surfaces(Track track, long now) {
        if (!funnel.hasSeen(track.fingerprint)) {
            return true;
        }
        return !funnel.isEscalated(track.fingerprint)
                && now - track.noticeNanos >= FingerprintRateLimiter.WINDOW_NANOS;
    }

    // ── the one-shot categories ─────────────────────────────────────────────

    /**
     * One report per distinct kept subject, oldest first, until the delta is
     * spent: each counts its own samples, the last one also the unsampled
     * remainder of the delta; with no sample at all the whole delta goes to
     * {@link #UNKNOWN_SUBJECT} (story 337, ruling E40). A report uses as many
     * of its subject's samples as it counted, oldest first; every other sample
     * stays for the next delta, so a sample that arrived before its count is
     * named when the count arrives (story 337, ruling E79).
     */
    private void reportOneShot(Frame frame, AudioFailureCategory category, long weight) {
        Map<String, OneShot> subjects = new LinkedHashMap<>();
        for (AudioFailureSample sample : unreported.of(category)) {
            subjects.computeIfAbsent(subjectKey(category, sample.subject()), OneShot::new).samples.add(sample);
        }
        if (subjects.isEmpty()) {
            subjects.put(UNKNOWN_SUBJECT, new OneShot(UNKNOWN_SUBJECT));
        }
        long remaining = weight;
        List<OneShot> ordered = List.copyOf(subjects.values());
        for (int i = 0; i < ordered.size() && remaining > 0; i++) {
            OneShot subject = ordered.get(i);
            long sampled = subject.samples.size();
            long counted = i == ordered.size() - 1 ? remaining : Math.min(Math.max(1L, sampled), remaining);
            remaining -= counted;
            reportOneSubject(frame, category, subject, counted);
        }
    }

    private void reportOneSubject(Frame frame, AudioFailureCategory category, OneShot subject, long weight) {
        String site = siteOf(category);
        Fingerprint fingerprint = Fingerprint.ofSite(category.slug(), subject.key);
        List<AudioFailureSample> used = subject.used(weight);
        Optional<AudioFailureSample> newestSample = used.isEmpty() ? Optional.empty() : Optional.of(used.getLast());
        Optional<Throwable> newest = newestSample.flatMap(AudioFailureSample::failure)
                .or(() -> newestSample.isEmpty() ? frame.reading.lastFailure(category) : Optional.empty());
        String base;
        if (category == AudioFailureCategory.CONFIG) {
            // Only this subject's own sample states a bus count; with none, the count-free sentence (story 337,
            // ruling E64).
            base = copy.audioFirst(category, Optional.empty(), "", "",
                    newestSample.map(AudioFailureSample::detail).orElse(0));
        } else {
            Optional<String> name = FailureComponentName.of(category,
                    newestSample.flatMap(AudioFailureSample::subject), newest);
            base = copy.audioFirst(category, name, "", reason(newest, site), 0L);
        }
        funnel.reportRealTime(new RealTimeReport(FailureFact.Origin.RT_GUARD, FailureFact.Severity.WARNING,
                fingerprint, weight, newest, anchorsOf(category, used), site, base, openLogFolderAction));
        unreported.remove(category, used);
    }

    private static String subjectKey(AudioFailureCategory category, Optional<Object> subject) {
        if (subject.isEmpty()) {
            return UNKNOWN_SUBJECT;
        }
        Object present = subject.get();
        if (present instanceof PluginParameterTarget target) {
            return target.pluginInstanceId() + "#" + target.parameterId();
        }
        if (present instanceof String key) {
            return key;
        }
        return category == AudioFailureCategory.CONFIG ? UNKNOWN_SUBJECT : present.getClass().getName();
    }

    /** The distinct anchors of the samples a one-shot report used, at most {@link #MAX_ANCHORS}. */
    private static List<String> anchorsOf(AudioFailureCategory category, List<AudioFailureSample> used) {
        Set<String> anchors = new LinkedHashSet<>();
        for (AudioFailureSample sample : used) {
            sample.failure().ifPresent(failure -> {
                if (anchors.size() < MAX_ANCHORS) {
                    anchors.add(anchorOf(category, failure));
                }
            });
        }
        return List.copyOf(anchors);
    }

    // ── the engine fault ────────────────────────────────────────────────────

    /**
     * Escalates {@code report} — always shown, latched in the funnel — and
     * latches the engine fault: a halt replaces any fact, a sustained fault
     * replaces any fact but a halt (story 337, ruling E41). The fact clears on
     * a render drive whose epoch is above {@code epoch}: the frame's reading's,
     * read at the frame's start (story 337, ruling E79), or, for a halt whose
     * stream was already replaced, one below the reading's: the halted drive
     * never beats again, and the drive running now, or the next one, was
     * started after it (story 337, ruling E71).
     */
    private void escalate(Track track, RealTimeReport report, Frame frame, boolean halt, long epoch) {
        funnel.escalateRealTime(report);
        track.noticeShown(frame.now);
        latched.add(track.fingerprint);
        latchedCategories.add(track.category);
        if (halt || latch.isEmpty() || !latch.get().halt()) {
            latch = Optional.of(new Latch(track.category, epoch, halt, report.action()));
            faultFreeSince = OptionalLong.empty();
        }
        publishTheFault();
    }

    /** Keeps the latched fact's count and last time with its fingerprint's, then clears it when it may. */
    private void followTheFault(Frame frame, long[] delta, boolean beatsAdvanced) {
        if (latch.isEmpty()) {
            return;
        }
        publishTheFault();
        boolean faulted = false;
        for (AudioFailureCategory category : KEEPS_THE_FAULT) {
            faulted |= delta(delta, category) > 0;
        }
        // A latched fingerprint that keeps failing keeps its fact latched, whatever its category (story 337, ruling
        // E63).
        for (AudioFailureCategory category : latchedCategories) {
            faulted |= delta(delta, category) > 0;
        }
        boolean newerDrive = frame.reading.renderDriveEpoch() > latch.get().epoch();
        // An input halt's fact also waits for a stream opened after it (story 337, ruling E87).
        boolean reopened = inputHaltStreamOpens.isEmpty()
                || frame.reading.streamOpens() > inputHaltStreamOpens.getAsLong();
        boolean newerStream = newerDrive && reopened;
        if (faulted || !newerStream) {
            faultFreeSince = OptionalLong.empty();
            return;
        }
        if (!beatsAdvanced) {
            return;
        }
        if (faultFreeSince.isEmpty()) {
            faultFreeSince = OptionalLong.of(frame.now);
        } else if (frame.now - faultFreeSince.getAsLong() >= FAULT_FREE_NANOS) {
            clearTheFault();
        }
    }

    private void publishTheFault() {
        Latch current = latch.orElseThrow();
        Track track = track(current.category());
        Optional<EngineFault> fact = Optional.of(new EngineFault(current.category(), Math.max(1L, track.total),
                track.firstSeen.orElse(track.lastSeen), track.lastSeen, current.action()));
        if (!fact.equals(engineFault.get())) {
            engineFault.set(fact);
        }
    }

    private void clearTheFault() {
        for (Fingerprint fingerprint : latched) {
            funnel.clearEscalation(fingerprint);
        }
        latched.clear();
        latchedCategories.clear();
        tracks.values().forEach(Track::restartTheBuckets);
        latch = Optional.empty();
        faultFreeSince = OptionalLong.empty();
        inputHaltStreamOpens = OptionalLong.empty();
        engineFault.set(Optional.empty());
    }

    // ── reasons, sites ──────────────────────────────────────────────────────

    private RealTimeReport report(Track track, FailureFact.Severity severity, long weight,
                                  Optional<Throwable> newest, List<String> anchors, String base, NoticeAction action) {
        return new RealTimeReport(originOf(track.category), severity, track.fingerprint, weight, newest, anchors,
                track.site, base, action);
    }

    /** Restart Audio Engine for what a stream restart can cure, Open Log Folder otherwise (story 337, ruling E13). */
    private NoticeAction actionOf(AudioFailureCategory category) {
        return switch (category) {
            case CALLBACK, SINK, PACING, INPUT, DEVICE, RENDER_HALTED -> restartAction;
            case RENDER, WORKER, DSP, MIDI, PLUGIN, AUTOMATION, CONFIG -> openLogFolderAction;
        };
    }

    private static FailureFact.Origin originOf(AudioFailureCategory category) {
        return switch (category) {
            case CALLBACK, DEVICE -> FailureFact.Origin.NATIVE_UPCALL;
            case WORKER -> FailureFact.Origin.WORKER_POOL;
            default -> FailureFact.Origin.RT_GUARD;
        };
    }

    /**
     * The guard site a category's failures are recorded at: the streaming
     * categories' fingerprint is {@code category|site} (story 337, ruling E40),
     * and every report names its site in place of a thread.
     *
     * @param category the category
     * @return its site
     */
    static String siteOf(AudioFailureCategory category) {
        return switch (category) {
            case CALLBACK -> "device-callback";
            case RENDER, RENDER_HALTED -> "render-drive";
            case SINK -> "render-drive-sink";
            case PACING -> "render-drive-pacing";
            case INPUT -> "input-capture";
            case WORKER -> "worker-pool";
            case DSP -> "dsp-processor";
            case MIDI -> "midi-renderer";
            case PLUGIN -> "clap-host";
            case AUTOMATION -> "automation-binder";
            case CONFIG -> "render-pipeline";
            case DEVICE -> "device-notification";
        };
    }

    /** The device the copy names — read only for the categories whose sentence names one. */
    private static String deviceFor(AudioFailureCategory category, Frame frame) {
        return switch (category) {
            case CALLBACK, SINK, PACING, INPUT, DEVICE -> frame.device();
            default -> "";
        };
    }

    /** The newest throwable's reason ({@link GuardedThrowables#reason}); the site when no throwable was kept. */
    private static String reason(Optional<Throwable> newest, String site) {
        return newest.map(failure -> GuardedThrowables.reason(GuardedThrowables.unwrapCarriers(failure)))
                .orElse(site);
    }

    private Track track(AudioFailureCategory category) {
        return tracks.get(category);
    }

    private static long delta(long[] delta, AudioFailureCategory category) {
        return delta[category.code()];
    }

    /**
     * The latched fact's category, the render-drive epoch a newer drive must be above to clear it, whether it is a
     * halt, and its action.
     */
    private record Latch(AudioFailureCategory category, long epoch, boolean halt, NoticeAction action) {
        Latch {
            Objects.requireNonNull(category, "category must not be null");
            Objects.requireNonNull(action, "action must not be null");
        }
    }

    /** One streaming category's — or the halt's — state across frames. FX thread. */
    private static final class Track {
        final AudioFailureCategory category;
        final String site;
        final Fingerprint fingerprint;
        /** Distinct anchors sampled since the fingerprint's last notice, at most {@link #MAX_ANCHORS}. */
        final Set<String> anchors = new LinkedHashSet<>();
        Optional<Instant> firstSeen = Optional.empty();
        Instant lastSeen = Instant.EPOCH;
        /** Every occurrence the drain reported for the fingerprint. */
        long total;
        long noticeNanos;
        boolean bucketed;
        long lastBucket;
        int consecutiveBuckets;

        Track(AudioFailureCategory category) {
            this.category = category;
            this.site = siteOf(category);
            this.fingerprint = Fingerprint.ofSite(category.slug(), site);
        }

        /**
         * Counts {@code weight} occurrences in this frame's one-second bucket.
         *
         * @return whether the fingerprint has now occurred in {@link #SUSTAINED_BUCKETS} consecutive buckets
         */
        boolean occurred(long weight, Frame frame) {
            total += weight;
            if (firstSeen.isEmpty()) {
                firstSeen = Optional.of(frame.wall);
            }
            lastSeen = frame.wall;
            long bucket = Math.floorDiv(frame.now, BUCKET_NANOS);
            if (!bucketed || bucket > lastBucket + 1) {
                consecutiveBuckets = 1;
            } else if (bucket == lastBucket + 1) {
                consecutiveBuckets++;
            }
            bucketed = true;
            lastBucket = Math.max(lastBucket, bucket);
            return consecutiveBuckets >= SUSTAINED_BUCKETS;
        }

        void addAnchor(String anchor) {
            if (anchors.size() < MAX_ANCHORS) {
                anchors.add(anchor);
            }
        }

        void noticeShown(long now) {
            noticeNanos = now;
            anchors.clear();
        }

        void restartTheBuckets() {
            bucketed = false;
            consecutiveBuckets = 0;
        }
    }

    /** One kept subject of a one-shot category, with its kept samples, oldest first. */
    private static final class OneShot {
        final String key;
        final List<AudioFailureSample> samples = new ArrayList<>();

        OneShot(String key) {
            this.key = key;
        }

        /** The samples a report that counted {@code counted} occurrences of this subject uses: the oldest ones. */
        List<AudioFailureSample> used(long counted) {
            return List.copyOf(samples.subList(0, (int) Math.min(counted, samples.size())));
        }
    }

    private static String anchorOf(AudioFailureCategory category, Throwable failure) {
        return Fingerprint.of(category.slug(), Optional.empty(), GuardedThrowables.unwrapCarriers(failure)).anchor();
    }

    /** What one frame read: its clock readings, the record's reading, the device name, and any deferred failure. */
    private final class Frame {
        final long now;
        final Instant wall;
        /** The record's one reading of this frame (story 337, rulings E79 and E82); set before any report. */
        AudioHealthReading reading;
        Optional<String> device = Optional.empty();
        Optional<Throwable> deferred = Optional.empty();

        Frame(long now, Instant wall) {
            this.now = now;
            this.wall = Objects.requireNonNull(wall, "wall must not be null");
        }

        /** Keeps a sample the reading hands over until a report of its category uses it, and notes its anchor. */
        void take(AudioFailureSample sample) {
            unreported.add(sample);
            Track track = tracks.get(sample.category());
            if (track != null) {
                sample.failure().ifPresent(failure -> track.addAnchor(anchorOf(sample.category(), failure)));
            }
        }

        /** The device's display name, read once per frame; a supplier that throws names none. */
        String device() {
            if (device.isEmpty()) {
                String name = "";
                try {
                    String supplied = deviceName.get();
                    name = supplied == null ? "" : supplied;
                } catch (Throwable failure) {
                    defer(failure);
                }
                device = Optional.of(name);
            }
            return device.get();
        }

        void defer(Throwable failure) {
            if (deferred.isEmpty()) {
                deferred = Optional.of(failure);
            } else if (deferred.get() != failure) {
                deferred.get().addSuppressed(failure);
            }
        }

        void rethrowDeferred() {
            if (deferred.isPresent()) {
                GuardedThrowables.<RuntimeException>rethrow(deferred.get());
            }
        }
    }
}
