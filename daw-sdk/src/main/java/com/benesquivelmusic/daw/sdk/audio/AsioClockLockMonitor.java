package com.benesquivelmusic.daw.sdk.audio;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Derives an ASIO driver's external-clock lock state and publishes its
 * changes as {@link ClockLockEvent}s. The public ASIO API has no lock query,
 * so the evidence is a sample-position poll once per interval, plus the
 * driver's {@code kAsioResyncRequest} messages, which {@link AsioBackend}
 * counts.
 *
 * <p><strong>What is judged.</strong> Only a stream on a source that is not
 * classified internal, of a driver that reports more than one source, is
 * polled; an unknown source name counts as external. Anything else reads
 * locked and makes no position call at all. The poll interval is the unit
 * (ruling E106): each interval is one good answer, one bad answer, or none.
 * An interval in which the device's callback periods did not advance is
 * none, resyncs or not: the device stalled, which is the engine watchdog's
 * case, not the clock's (E59, E120). Otherwise:</p>
 * <ul>
 *   <li>bad: the interval holds at least one resync message, however many
 *       and whatever its poll answered, even a poll that could only set a new
 *       baseline (E106, E121); or the driver answers "no clock"
 *       ({@code ASE_NoClock}) or "not advancing" ({@code ASE_SPNotAdvancing}),
 *       or OK with the same sample position as the previous answered
 *       poll;</li>
 *   <li>good: no resync, and OK with a sample position that moved;</li>
 *   <li>none: no resync, and a poll that timed out, was refused, failed or
 *       returned any other error, or an OK that could only set a new
 *       baseline (E59, E121). Also, whatever its poll answered, an interval
 *       whose resyncs belong to the person's source switch: from the moment
 *       it is sent until the poll after the one that judges the driver's
 *       answer, accepted or not (E122, E137, E138; see {@link #switchSent()}
 *       and {@link #switchAnswered(boolean)}).</li>
 * </ul>
 * <p>An interval that is none resets both runs, so unlock needs two bad
 * intervals with nothing between them, and messages that all arrived inside
 * one interval never unlock. A resync's count, log entry and take mark are
 * not this monitor's: it only reads the backend's resync counter. While the
 * driver's control panel is open nothing is sent to the driver: the interval
 * is unanswered. Once it closes, the source is read again, since the panel
 * may have switched it.</p>
 *
 * <p><strong>What is published.</strong> Every stream, and every source
 * change inside one, starts not yet measured: it reads locked, but nothing
 * this stream answered has said so. The first good answer then publishes
 * {@code locked = true} once, and two consecutive bad answers publish an
 * unlock; one bad answer publishes nothing. After an unlock, three
 * consecutive good answers relock. An internal or unjudged source publishes
 * nothing.</p>
 *
 * <p>The same state is kept as a level, {@link #status()}: one volatile
 * field holding an immutable {@link ClockLockStatus}, written under this
 * monitor's lock at every change (a start, a source read that succeeded, a
 * first measurement, an unlock, a relock), always before the change's event
 * is offered, and emptied by {@link #stop()}. A start and a source change give
 * it a new serial. A source read that fails changes nothing in it.</p>
 *
 * <p>A clock-source read that names no current source (it timed out behind
 * the driver's control panel, say) is no answer too: nothing is judged until
 * a read succeeds, the lock state is kept, and the read is retried every
 * {@link #SOURCE_RETRY_INTERVALS} poll intervals while the stream runs.</p>
 *
 * <p><strong>Lifetimes.</strong> The lock state and both runs belong to one
 * stream: every {@link #start(DeviceId)} begins with them fresh, also on the
 * same device, and so does a change of the current source inside a stream
 * (both silently, with no event). A source read that fails keeps them. The
 * sample-position baseline belongs to one run of the poller.</p>
 *
 * <p><strong>Threads.</strong> {@link #start(DeviceId)} starts one daemon
 * thread per stream. It is stopped by a flag and a generation bump, and woken
 * with {@link LockSupport#unpark(Thread)}; it is never interrupted. An
 * interrupted caller of {@link AsioControlThread#call} leaves a running
 * downcall ABANDONED, which makes every later bounded ASIO call fail fast and
 * would break the backend's own close. A poll result is applied and published
 * under this monitor's own lock together with the generation check, so
 * nothing is published after {@link #stop()}; the downcalls themselves run
 * outside that lock. {@link #pause()} is a single volatile write, safe on the
 * driver's notification thread. Nothing here takes the backend's driver
 * lifecycle lock.</p>
 */
final class AsioClockLockMonitor {

    private static final Logger LOG = Logger.getLogger(AsioClockLockMonitor.class.getName());

    /** How often the sample position is polled. */
    static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

    /** Consecutive bad answers that declare an unlock. */
    static final int BAD_ANSWERS_TO_UNLOCK = 2;

    /** Consecutive good answers that declare a relock. */
    static final int GOOD_ANSWERS_TO_RELOCK = 3;

    /**
     * Poll intervals between attempts to read a clock source that could not be
     * read: slow, because the usual cause is a driver control panel that holds
     * the control thread until it closes.
     */
    static final int SOURCE_RETRY_INTERVALS = 10;

    /**
     * Polls, from the one that judges the interval the driver's answer to a
     * source switch falls in, whose resyncs belong to the switch (rulings
     * E122, E137).
     */
    static final int SWITCH_INTERVALS = 2;

    /** The SDK's {@code ASE_SPNotAdvancing}: the sample position is not advancing. */
    static final int ASE_SP_NOT_ADVANCING = -996;

    /** The SDK's {@code ASE_NoClock}: the driver has no clock. */
    static final int ASE_NO_CLOCK = -995;

    private static final BiPredicate<Flow.Subscriber<? super ClockLockEvent>, ClockLockEvent>
            DROP_EVENT = (subscriber, event) -> false;

    /** What the monitor reads from its backend; every call is off the driver's threads. */
    interface Probe {

        /**
         * The device's clock sources, one of them flagged current; empty when
         * they cannot be read.
         *
         * @param device the open device
         * @return the sources; never {@code null}
         */
        List<ClockSource> clockSources(DeviceId device);

        /** Whether the sample-position query exists in this native build. */
        boolean samplePositionAvailable();

        /**
         * One sample-position answer.
         *
         * @return the driver's answer; never {@code null}
         * @throws RuntimeException when the driver gave no answer
         */
        AsioCapabilityShim.SamplePosition samplePosition();

        /** The device-callback heartbeat, monotonic for the backend's life. */
        long deviceCallbackPeriods();

        /** Resync requests the driver has sent, monotonic for the backend's life. */
        long driverResyncs();

        /** The {@link System#nanoTime()} at which the newest resync request arrived. */
        long lastDriverResyncNanos();

        /** Whether the driver's modal control panel owns the control thread; cheap, any thread. */
        default boolean controlPanelOpen() {
            return false;
        }
    }

    /** Never repeats within the process, so statuses of two backends never share a serial. */
    private static final AtomicLong SERIALS = new AtomicLong();

    private enum AnswerKind { NONE, BAD, OK }

    private record Answer(AnswerKind kind, long position) {
        static final Answer NO_ANSWER = new Answer(AnswerKind.NONE, 0L);
        static final Answer BAD_ANSWER = new Answer(AnswerKind.BAD, 0L);
    }

    /**
     * What one poll interval brought.
     *
     * @param answer          the poll's answer
     * @param resynced        whether a resync request arrived that no interval has judged yet
     * @param periodsAdvanced whether the device periods advanced since the previous interval
     * @param switchInterval  whether its resyncs belong to the person's source switch
     * @param resyncNanos     when the newest resync request arrived
     * @param now             when the interval ended
     */
    private record Interval(Answer answer, boolean resynced, boolean periodsAdvanced, boolean switchInterval,
                            long resyncNanos, long now) {
    }

    private record SourceReading(boolean known, int sourceId, boolean pollable) {
        static final SourceReading UNKNOWN = new SourceReading(false, -1, false);
    }

    private final Probe probe;
    private final LongSupplier nanoClock;
    private final SubmissionPublisher<ClockLockEvent> publisher;
    private final long pollIntervalNanos;
    private final ThreadFactory threadFactory;
    private final Object lock = new Object();

    private volatile long generation;
    private volatile boolean running;
    private volatile boolean paused;
    /** The level behind the events; {@code null} while stopped. Written under {@link #lock}. */
    private volatile ClockLockStatus status;

    private Thread poller;
    private DeviceId device;
    private long sourceEpoch;
    private boolean sourceKnown;
    private boolean sourceUnreadable;
    /** Whether a tick of this stream found the driver's control panel open; the source is read once it closes. */
    private boolean panelSeenOpen;
    private int intervalsToSourceRetry;
    private boolean pollable;
    private int sourceId = -1;
    private boolean havePosition;
    private long lastPosition;
    private boolean havePeriods;
    private long lastPeriods;
    private long seenResyncs;
    /** Polls still to come whose resyncs belong to the person's answered source switch (rulings E122, E137). */
    private int switchIntervals;
    /** Whether the person's source switch was sent and the driver has not answered it yet (ruling E138). */
    private boolean switchPending;

    private boolean locked = true;
    /** Whether a good answer or an unlock of this stream and source has decided the lock. */
    private boolean measured;
    private int badRun;
    private int goodRun;
    private long firstBadNanos;
    private long serial;
    private long lockEvidenceNanos;

    /**
     * @param probe         what the monitor reads; must not be null
     * @param nanoClock     the clock that dates the evidence, in
     *                      {@link System#nanoTime()} units; must not be null
     * @param publisher     where lock changes are offered; never completed
     *                      here; must not be null
     * @param pollInterval  the delay between polls; must be positive
     * @param threadFactory makes the poller thread of each
     *                      {@link #start(DeviceId)}; must not be null
     */
    AsioClockLockMonitor(Probe probe, LongSupplier nanoClock,
                         SubmissionPublisher<ClockLockEvent> publisher,
                         Duration pollInterval, ThreadFactory threadFactory) {
        this.probe = Objects.requireNonNull(probe, "probe must not be null");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
        Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive: " + pollInterval);
        }
        this.pollIntervalNanos = pollInterval.toNanos();
        this.threadFactory = Objects.requireNonNull(threadFactory, "threadFactory must not be null");
    }

    /** The production poller thread: a named platform daemon thread. */
    static Thread daemonThread(Runnable body) {
        return Thread.ofPlatform().name("asio-clock-monitor").daemon(true).unstarted(body);
    }

    /**
     * Starts polling for a freshly opened stream of {@code device}, with a
     * fresh, not yet measured lock state: nothing is carried from the
     * previous stream. The current clock source is read on the poller's
     * first tick, not here, so starting costs the caller no downcall.
     *
     * @param device the opened device, as the backend names it in its events;
     *               must not be null
     */
    void start(DeviceId device) {
        Objects.requireNonNull(device, "device must not be null");
        Thread started;
        synchronized (lock) {
            resetLockState();
            sourceId = -1;
            pollable = false;
            sourceUnreadable = false;
            panelSeenOpen = false;
            this.device = device;
            generation++;
            long mine = generation;
            running = true;
            paused = false;
            sourceKnown = false;
            sourceEpoch++;
            havePosition = false;
            havePeriods = false;
            seenResyncs = probe.driverResyncs();
            switchIntervals = 0;
            switchPending = false;
            writeStatus();
            started = threadFactory.newThread(() -> pollUntilStopped(mine));
            poller = started;
        }
        started.start();
    }

    /**
     * Stops polling: a flag, a generation bump, and an unpark of the poller.
     * Never interrupts it, never waits for it. A poll still inside a downcall
     * finishes there and publishes nothing.
     */
    void stop() {
        Thread stopped;
        synchronized (lock) {
            running = false;
            generation++;
            switchPending = false;
            status = null;
            stopped = poller;
            poller = null;
        }
        if (stopped != null) {
            LockSupport.unpark(stopped);
        }
    }

    /**
     * Suspends judging until the next {@link #start(DeviceId)}: the driver
     * asked to be reset, so its answers mean nothing until the stream is
     * reopened. A single volatile write, safe on the driver's notification
     * thread.
     */
    void pause() {
        paused = true;
    }

    /**
     * The person's clock-source switch is being sent to the driver: until the
     * driver answers ({@link #switchAnswered(boolean)}), every resync an
     * interval holds belongs to the switch and is not judged (ruling E138).
     * Only the person's selection calls this (E107, E124).
     */
    void switchSent() {
        synchronized (lock) {
            switchPending = true;
        }
    }

    /**
     * The driver answered the person's switch, whether it accepted it,
     * refused it, threw or timed out. Resyncs judged by the poll whose
     * interval holds this answer and by the next poll belong to the switch
     * ({@link #SWITCH_INTERVALS}, rulings E137, E138). An accepted switch
     * makes the next tick read the source again, since a driver may switch
     * sources without a reset request; a refused one resets nothing: the
     * source, the lock state, both runs and the status stay. An answer with
     * no switch pending, because none was sent or a {@link #start(DeviceId)}
     * or {@link #stop()} came between, grants nothing.
     *
     * @param accepted whether the driver accepted the switch
     */
    void switchAnswered(boolean accepted) {
        synchronized (lock) {
            if (!switchPending) {
                return;
            }
            switchPending = false;
            if (accepted) {
                sourceKnown = false;
                sourceEpoch++;
            }
            switchIntervals = SWITCH_INTERVALS;
        }
    }

    /** Whether a switch was sent that the driver has not answered yet. */
    boolean isSwitchPending() {
        synchronized (lock) {
            return switchPending;
        }
    }

    /** Whether a {@link #start(DeviceId)} is in effect that no {@link #stop()} has ended. */
    boolean isRunning() {
        return running;
    }

    /**
     * The current lock state as a level: one volatile read, no lock, no
     * driver call; any thread, every frame.
     *
     * @return the status of the running stream; empty after {@link #stop()}
     *         and before the first {@link #start(DeviceId)}
     */
    Optional<ClockLockStatus> status() {
        return Optional.ofNullable(status);
    }

    /** The lock state the monitor last declared; {@code true} while not yet measured. */
    boolean isLocked() {
        synchronized (lock) {
            return locked;
        }
    }

    /** Whether an answer of the current stream and source has decided the lock yet. */
    boolean isMeasured() {
        synchronized (lock) {
            return measured;
        }
    }

    /**
     * Runs one poll on the calling thread for the current start, exactly as the
     * poller thread does. Test seam: a monitor whose thread factory returns an
     * idle thread is driven tick by tick through this.
     */
    void pollOnce() {
        tick(generation);
    }

    private boolean isCurrent(long mine) {
        return running && generation == mine;
    }

    private void pollUntilStopped(long mine) {
        while (isCurrent(mine)) {
            try {
                tick(mine);
            } catch (RuntimeException failure) {
                LOG.log(Level.WARNING, "ASIO clock-lock poll failed; the next poll retries", failure);
            }
            long wakeAt = System.nanoTime() + pollIntervalNanos;
            long remaining = pollIntervalNanos;
            while (isCurrent(mine) && remaining > 0L) {
                LockSupport.parkNanos(this, remaining);
                remaining = wakeAt - System.nanoTime();
            }
        }
    }

    private void tick(long mine) {
        DeviceId runDevice;
        long epoch;
        boolean needSource;
        boolean pollNow;
        boolean panelOpen = controlPanelOpen();
        synchronized (lock) {
            if (!isCurrent(mine) || paused) {
                return;
            }
            if (panelOpen) {
                panelSeenOpen = true;
            } else if (panelSeenOpen) {
                // The person may have chosen another source in the driver's own panel.
                panelSeenOpen = false;
                sourceKnown = false;
                sourceEpoch++;
            }
            runDevice = device;
            epoch = sourceEpoch;
            needSource = !sourceKnown || sourceRetryDue();
            pollNow = sourceKnown && pollable && !sourceUnreadable;
        }
        if (panelOpen) {
            // Each call would wait behind the modal panel and leak its shim when withdrawn.
            needSource = false;
            pollNow = false;
        }
        SourceReading reading = null;
        if (needSource) {
            reading = readSource(runDevice);
            pollNow = reading.pollable();
        }
        Answer answer = pollNow ? poll() : Answer.NO_ANSWER;
        long periods = probe.deviceCallbackPeriods();
        long resyncs = probe.driverResyncs();
        long resyncNanos = probe.lastDriverResyncNanos();
        long now = nanoClock.getAsLong();
        synchronized (lock) {
            if (!isCurrent(mine) || paused) {
                return;
            }
            boolean switchInterval = switchPending || switchIntervals > 0;
            if (!switchPending && switchIntervals > 0) {
                switchIntervals--;
            }
            if (epoch != sourceEpoch) {
                // An accepted switch answered while this tick ran: its reading is stale and its resyncs the switch's.
                seenResyncs = resyncs;
                havePeriods = true;
                lastPeriods = periods;
                return;
            }
            boolean periodsAdvanced = havePeriods && periods != lastPeriods;
            if (reading != null) {
                applySource(reading);
            }
            if (pollable && !sourceUnreadable) {
                judge(new Interval(answer, resyncs > seenResyncs, periodsAdvanced, switchInterval, resyncNanos, now));
            }
            seenResyncs = resyncs;
            havePeriods = true;
            lastPeriods = periods;
        }
    }

    private SourceReading readSource(DeviceId runDevice) {
        List<ClockSource> sources;
        try {
            sources = probe.clockSources(runDevice);
        } catch (RuntimeException unreadable) {
            sources = List.of();
        }
        ClockSource current = null;
        for (ClockSource source : sources) {
            if (source.current()) {
                current = source;
                break;
            }
        }
        if (current == null) {
            return SourceReading.UNKNOWN;
        }
        boolean judged = sources.size() > 1
                && !(current.kind() instanceof ClockKind.Internal)
                && samplePositionAvailable();
        return new SourceReading(true, current.id(), judged);
    }

    /** Counts down one poll interval of an unreadable source; true when it is time to read again. */
    private boolean sourceRetryDue() {
        if (!sourceUnreadable) {
            return false;
        }
        intervalsToSourceRetry--;
        return intervalsToSourceRetry <= 0;
    }

    private boolean samplePositionAvailable() {
        try {
            return probe.samplePositionAvailable();
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    private boolean controlPanelOpen() {
        try {
            return probe.controlPanelOpen();
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    private Answer poll() {
        AsioCapabilityShim.SamplePosition answer;
        try {
            answer = probe.samplePosition();
        } catch (RuntimeException noAnswer) {
            return Answer.NO_ANSWER;
        }
        return switch (answer.error()) {
            case AsioCapabilityShim.SamplePosition.ASE_OK ->
                    new Answer(AnswerKind.OK, answer.samplePosition());
            case ASE_SP_NOT_ADVANCING, ASE_NO_CLOCK -> Answer.BAD_ANSWER;
            default -> Answer.NO_ANSWER;
        };
    }

    /**
     * An unreadable source is no answer: the runs reset, the lock state and
     * the last known source stay, nothing is judged, and the read is retried
     * every {@link #SOURCE_RETRY_INTERVALS} intervals.
     */
    private void applySource(SourceReading reading) {
        sourceKnown = true;
        if (!reading.known()) {
            sourceUnreadable = true;
            intervalsToSourceRetry = SOURCE_RETRY_INTERVALS;
            badRun = 0;
            goodRun = 0;
            havePosition = false;
            return;
        }
        sourceUnreadable = false;
        if (reading.sourceId() != sourceId || reading.pollable() != pollable) {
            resetLockState();
        }
        sourceId = reading.sourceId();
        pollable = reading.pollable();
        havePosition = false;
        writeStatus();
    }

    /**
     * Judges one poll interval as one bad answer, one good answer, or none.
     * The poll's position becomes the next interval's baseline whatever the
     * verdict; an unanswered poll leaves no baseline.
     * <ul>
     *   <li>Bad: it held a resync request while the device periods advanced
     *       and outside a switch's intervals, whatever its poll answered, a
     *       poll that only set a new baseline included (rulings E106, E121);
     *       or, with no resync, its poll answered bad, or OK with the position
     *       unchanged, while the device periods advanced (E59).</li>
     *   <li>Good: no resync, the periods advanced, and the position moved
     *       since the previous answered poll.</li>
     *   <li>None, which breaks both runs: the periods did not advance (E59,
     *       E120: the watchdog's case, resyncs or not), its resyncs belong to
     *       a source switch (E122), its poll went unanswered (E59), or it
     *       could only set a new baseline (E121).</li>
     * </ul>
     */
    private void judge(Interval interval) {
        Answer answer = interval.answer();
        boolean baselineOnly = answer.kind() == AnswerKind.OK && !havePosition;
        boolean moved = answer.kind() == AnswerKind.OK && answer.position() != lastPosition;
        if (answer.kind() == AnswerKind.OK) {
            havePosition = true;
            lastPosition = answer.position();
        } else if (answer.kind() == AnswerKind.NONE) {
            havePosition = false;
        }
        if (!interval.periodsAdvanced()) {
            breakRuns();
        } else if (interval.resynced()) {
            if (interval.switchInterval()) {
                breakRuns();
            } else {
                badAnswer(interval.resyncNanos());
            }
        } else if (answer.kind() == AnswerKind.NONE || baselineOnly) {
            breakRuns();
        } else if (moved) {
            goodAnswer(interval.now());
        } else {
            badAnswer(interval.now());
        }
    }

    private void breakRuns() {
        badRun = 0;
        goodRun = 0;
    }

    private void badAnswer(long evidenceNanos) {
        goodRun = 0;
        if (badRun == 0) {
            firstBadNanos = evidenceNanos;
        }
        badRun = Math.min(badRun + 1, BAD_ANSWERS_TO_UNLOCK);
        if (locked && badRun >= BAD_ANSWERS_TO_UNLOCK) {
            locked = false;
            measured = true;
            publish(false, firstBadNanos);
        }
    }

    private void goodAnswer(long now) {
        badRun = 0;
        goodRun = Math.min(goodRun + 1, GOOD_ANSWERS_TO_RELOCK);
        if (!measured) {
            measured = true;
            publish(true, now);
            return;
        }
        if (!locked && goodRun >= GOOD_ANSWERS_TO_RELOCK) {
            locked = true;
            publish(true, now);
        }
    }

    /** A fresh measurement: a start or a source change. Caller holds {@link #lock}. */
    private void resetLockState() {
        locked = true;
        measured = false;
        badRun = 0;
        goodRun = 0;
        firstBadNanos = 0L;
        lockEvidenceNanos = 0L;
        serial = SERIALS.incrementAndGet();
    }

    /** The status first, then the event, so a consumer the event wakes reads the new state. */
    private void publish(boolean nowLocked, long evidenceNanos) {
        lockEvidenceNanos = evidenceNanos;
        writeStatus();
        publisher.offer(new ClockLockEvent(device, sourceId, nowLocked, evidenceNanos), DROP_EVENT);
    }

    /** Caller holds {@link #lock}. */
    private void writeStatus() {
        ClockLockStatus.Lock state;
        if (sourceId < 0) {
            state = ClockLockStatus.Lock.CHECKING;
        } else if (!pollable) {
            state = ClockLockStatus.Lock.NOT_MEASURABLE;
        } else if (!measured) {
            state = ClockLockStatus.Lock.CHECKING;
        } else {
            state = locked ? ClockLockStatus.Lock.LOCKED : ClockLockStatus.Lock.UNLOCKED;
        }
        boolean decided = state == ClockLockStatus.Lock.LOCKED || state == ClockLockStatus.Lock.UNLOCKED;
        status = new ClockLockStatus(device, sourceId, serial, state, decided ? lockEvidenceNanos : 0L);
    }
}
