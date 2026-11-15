package com.benesquivelmusic.daw.app.diagnostics;

import com.benesquivelmusic.daw.app.logging.ExitHoldingLogManager;
import com.benesquivelmusic.daw.app.ui.NotificationBar;
import com.benesquivelmusic.daw.app.ui.NotificationHistoryService;
import com.benesquivelmusic.daw.app.ui.NotificationLevel;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.marshal.PulseFailureSink;
import com.benesquivelmusic.daw.app.ui.marshal.PulseItemKind;
import com.benesquivelmusic.daw.core.event.SubscriberDescription;
import com.benesquivelmusic.daw.core.event.SubscriberFailureSink;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * The one funnel every uncaught failure reaches (story 336; Failure Surfacing
 * book §4.1): it builds the failure fact,
 * writes the full stack to the log, and — once the window can show it —
 * marshals one ERROR toast through {@link FxDispatcher}, carrying the
 * "Open Log Folder" action.
 *
 * <p><strong>Its entry points.</strong> The process-wide
 * {@linkplain #defaultHandler() default handler} ({@code DawLauncher.main}
 * installs it before the toolkit starts), the FX thread's own
 * {@linkplain #fxThreadHandler() handler} ({@code DawApplication.start}
 * installs it alongside the dispatcher), and {@link #reportDeclared}, through
 * which a site that knows what failed names it ({@link DeclaredSubject}).
 * The categories are {@link #UI_HANDLER} (the FX thread), {@link #THREAD_UNCAUGHT}
 * (any other thread), {@link #APP_LAUNCH} (the launch thread, log-only) and a
 * declared subject's own.</p>
 *
 * <p><strong>Story 337's entry points.</strong> The application's
 * {@code FxDispatcher} reports each pulse item that threw through
 * {@link #pulseFailureSink()} ({@link #UI_PULSE}), and the application's event
 * bus each consumer that threw through {@link #busFailureSink()}
 * ({@link #BUS_SUBSCRIBER}); both are isolated consumers, which one policy
 * decides for (story 337, rulings E34 and E35): the first occurrence of a
 * fingerprint is one WARNING record with its stack and the offender, and no
 * toast; the {@value FingerprintRateLimiter#ISOLATED_CONSUMER_THRESHOLD}rd
 * shows one WARNING toast with Open Log Folder; after that the 30 s window
 * re-shows it with the running total; a FATAL one surfaces at once. The
 * audio health drain reports what the real-time threads recorded through
 * {@link #reportRealTime} (a first occurrence, rate-limited by the window
 * with weighted counts), {@link #escalateRealTime} (a sustained fault or a
 * halt: always shown, latched) and {@link #foldRealTime} (a worker failure
 * folded into a render notice): the report names the guard site instead of
 * a thread, and carries its own action (story 337, rulings E39–E44).</p>
 *
 * <p><strong>Step by step.</strong> The throwable's carriers are unwrapped and
 * its {@linkplain Fingerprint fingerprint} taken; the
 * {@linkplain FingerprintRateLimiter rate limiter} decides on the failing
 * thread; an occurrence inside its window is counted and nothing else. A
 * surfaced one gets its severity and base sentence, one SEVERE record with
 * the original throwable's whole chain, and — unless it is {@code app.launch}
 * — a toast: posted at once when the toasts are {@linkplain #armToasts armed},
 * kept until they are (the first 16, a count of the rest, and the first FATAL
 * one whichever it was), and only logged after they are
 * {@linkplain #disarmToasts disarmed}. A record that cannot be written does
 * not cost the failure its toast: what the writing threw goes to the
 * {@link LastResortLog} and the toast is still posted.</p>
 *
 * <p><strong>Accounting.</strong> What the log and the history account for,
 * where each counted occurrence goes and what neither holds are stated once,
 * in the "Accounting" paragraph of {@link FingerprintRateLimiter}'s class
 * Javadoc. The funnel writes the tail lines it names; those owed when the log
 * closes it writes as the default handler's answer to
 * {@link ExitHoldingLogManager.OwedLines}.</p>
 *
 * <p><strong>It never throws, and never re-enters itself (story 336, Goals
 * bullet 5).</strong>
 * A failure handled while this thread is already inside the funnel — bound by
 * a {@link ScopedValue} (JEP 506, final in Java 25) — goes to the
 * {@link LastResortLog} instead; so does anything the funnel itself throws.
 * Every runnable it posts to the FX thread runs its whole body inside that
 * same binding and its own {@code catch (Throwable)}: JavaFX
 * hands a listener's exception straight to the FX thread's handler without
 * throwing it, and without the binding a throwing history listener would loop
 * forever through the toast that notified it. Every record the funnel writes
 * itself is written inside the binding too. The last resort guards its own
 * reads the same way: a failure that re-enters the funnel while the last
 * resort reads a throwable's message or frames reaches the last resort again
 * as a shorter record that reads neither throwable, so the re-entry ends
 * there instead of recursing until the stack is exhausted
 * ({@link LastResortLog}; story 336, studio-engineer ruling R992-13).</p>
 *
 * <p><strong>Threading (javafx-application-design "## 11. Threading").</strong>
 * Not everything is off the FX thread, and the studio engineer's review of
 * this design accepted what is on it: a failure on the FX thread is handled
 * there, so its SEVERE record is formatted, written and flushed to the log
 * file on the FX thread, and so is each tail line, which the pulse's keyed
 * flush writes — at a rate the limiter bounds: per fingerprint, at most one
 * record and one tail line per 30 s window. And because the log sink is a handler on the
 * root logger, every other INFO-and-above record anything logs on the FX
 * thread is now a file write too. What the funnel avoids is waiting on other
 * threads: the rate decision runs on the failing thread before anything is
 * posted, every toast is an un-keyed {@link FxDispatcher#onFx(Runnable)}
 * (FIFO, never coalesced) that waits on nothing, the due-tail check is a field
 * read in a pulse participant that posts keyed work, and Open Log Folder runs
 * on a virtual thread, never on the FX thread.</p>
 *
 * <p><strong>Rule:</strong> nothing is logged, formatted or
 * recorded into the history while {@code armLock} or the rate limiter's
 * monitor is held. The log sink's write-failure notice may run under the file
 * handler's lock — a failed flush, rotation or close is reported under it; a
 * failed write in {@code publish} is reported after the lock is released
 * (JDK-8349206) — and takes {@code armLock}; that order — file handler, then
 * {@code armLock} — is never reversed.</p>
 */
public final class FailureFunnel {

    /** Anything reaching the FX application thread's handler. */
    public static final String UI_HANDLER = "ui.handler";

    /** Anything reaching the default handler from any thread but the launch thread and the FX thread. */
    public static final String THREAD_UNCAUGHT = "thread.uncaught";

    /** Anything reaching the default handler on the launch thread; log-only. */
    public static final String APP_LAUNCH = "app.launch";

    /** A unit of work of the FX pulse that threw: a participant, keyed work or a channel drain (story 337). */
    public static final String UI_PULSE = "ui.pulse";

    /** An event bus consumer that threw while an event was delivered to it (story 337). */
    public static final String BUS_SUBSCRIBER = "bus.subscriber";

    /** The JavaFX application thread's name ({@code QuantumToolkit}). */
    static final String FX_THREAD_NAME = "JavaFX Application Thread";

    /** How many failures before the toasts are armed are kept for the arm-time toast. */
    static final int PRE_ARM_CAPACITY = 16;

    /** The launcher's wrapper when the application did run and its {@code stop()} threw. */
    static final String APPLICATION_STOP_WRAPPER = "Exception in Application stop method";

    /** The launcher's wrapper when the preloader's {@code stop()} threw. */
    static final String PRELOADER_STOP_WRAPPER = "Exception in Preloader stop method";

    /** Bound while the current thread is inside the funnel: what keeps it from re-entering itself. */
    private static final ScopedValue<Handling> IN_FUNNEL = ScopedValue.newInstance();

    private static final DateTimeFormatter LOG_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);

    /** What the funnel does while it records the due tails; also each tail's activity when it cannot be named. */
    private static final String RECORDING_DUE_TAILS = "recording the tails of rate-limited failures";

    /** The record header's detail of a real-time occurrence folded into another notice (story 337, ruling E65). */
    private static final String FOLDED_DETAIL = "folded into the notice of the same frame; no toast of its own (story 337,"
            + " ruling E65)";

    private final LogSink sink;
    private final FingerprintRateLimiter limiter;
    private final FailureCopy copy;
    private final LogFolderOpener logFolder;
    private final Predicate<Thread> isLaunchThread;
    private final LastResortLog lastResort;
    private final Logger log;
    /** How a last-resort record names a tail: {@link #tailNamed} but in a test that makes the naming throw. */
    private final Function<FingerprintRateLimiter.Tail, String> tailNames;
    private final String openLogFolderLabel;
    private final FunnelHandler defaultHandler;
    private final FunnelHandler fxHandler;
    private final Runnable openLogFolderAction = this::openLogFolder;
    /** Open Log Folder as a notice's action: every story-336 toast's, and a caller's choice (story 337, ruling E43). */
    private final NoticeAction openLogFolderNotice;
    /** The production dispatcher's pulse failure sink (story 337, ruling E32); the same instance on every call. */
    private final PulseFailureSink pulseSink = this::pulseItemFailed;
    /** The application bus's consumer failure sink (story 337, ruling E37); the same instance on every call. */
    private final SubscriberFailureSink busSink = this::subscriberFailed;
    private final Runnable flushDueTailsWork = this::flushDueTails;
    private final Runnable tailParticipant = this::postTailsIfDue;
    /** The one key of this funnel's tail flush: one key, one fact. */
    private final Object tailFlushKey = new Object();

    private final Object armLock = new Object();
    /** The armed target, or {@code null}. Written under {@link #armLock}; read anywhere. */
    private volatile Target armed;
    /** Guarded by {@link #armLock}: whether the toasts were disarmed (after teardown, log only). */
    private boolean disarmed;
    /** Guarded by {@link #armLock}: the toasts owed from before the arming. */
    private final List<PendingToast> preArm = new ArrayList<>();
    /** Guarded by {@link #armLock}: how many more than {@link #PRE_ARM_CAPACITY} there were. */
    private int preArmOverflow;
    /**
     * Guarded by {@link #armLock}: the first FATAL toast from before the arming, whether it was kept in
     * {@link #preArm} or only counted in {@link #preArmOverflow}.
     */
    private Optional<PendingToast> preArmFatal = Optional.empty();
    /** Guarded by {@link #armLock}: the sink's first write failure, owed a notice once armed. */
    private Optional<String> pendingWriteFailure = Optional.empty();

    /**
     * The funnel's collaborators.
     *
     * @param sink           the log sink; its folder is the one every Open Log Folder opens
     * @param limiter        the rate limiter; its clock also times the tail flush
     * @param copy           the user-facing strings
     * @param logFolder      what opens the log folder
     * @param isLaunchThread whether a thread is the launch thread ({@link #APP_LAUNCH})
     */
    public record Collaborators(LogSink sink, FingerprintRateLimiter limiter, FailureCopy copy,
                                LogFolderOpener logFolder, Predicate<Thread> isLaunchThread) {
        public Collaborators {
            Objects.requireNonNull(sink, "sink must not be null");
            Objects.requireNonNull(limiter, "limiter must not be null");
            Objects.requireNonNull(copy, "copy must not be null");
            Objects.requireNonNull(logFolder, "logFolder must not be null");
            Objects.requireNonNull(isLaunchThread, "isLaunchThread must not be null");
        }
    }

    /**
     * A funnel on the given collaborators (tests inject clocks and an opener).
     *
     * @param collaborators the collaborators
     */
    public FailureFunnel(Collaborators collaborators) {
        this(collaborators, FailureFunnel::tailNamed);
    }

    /**
     * A funnel that names a tail for its last-resort records with
     * {@code tailNames}. A test-only seam, never used by the application:
     * building a tail's name can throw only when the JVM runs out of memory,
     * so a test makes it throw through this.
     *
     * @param collaborators the collaborators
     * @param tailNames     how a last-resort record names a tail: its fingerprint and count
     */
    FailureFunnel(Collaborators collaborators, Function<FingerprintRateLimiter.Tail, String> tailNames) {
        Objects.requireNonNull(collaborators, "collaborators must not be null");
        this.tailNames = Objects.requireNonNull(tailNames, "tailNames must not be null");
        this.sink = collaborators.sink();
        this.limiter = collaborators.limiter();
        this.copy = collaborators.copy();
        this.logFolder = collaborators.logFolder();
        this.isLaunchThread = collaborators.isLaunchThread();
        this.lastResort = new LastResortLog(lastResortFile(sink.folder()));
        this.log = Logger.getLogger(FailureFunnel.class.getName());
        this.openLogFolderLabel = copy.openLogFolderLabel();
        this.openLogFolderNotice = new NoticeAction(openLogFolderLabel, openLogFolderAction);
        this.defaultHandler = new FunnelHandler(this, FailureFact.Origin.DEFAULT_HANDLER);
        this.fxHandler = new FunnelHandler(this, FailureFact.Origin.FX_HANDLER);
        sink.onFirstWriteFailure(this::onSinkWriteFailure);
    }

    /**
     * The production funnel of a launch: the system clocks and zone, the
     * desktop's file manager, and {@code launchThread} as the launch thread.
     *
     * @param sink         the installed log sink
     * @param launchThread the thread {@code DawLauncher.main} runs on
     * @return the funnel, not yet installed
     */
    public static FailureFunnel forLaunch(LogSink sink, Thread launchThread) {
        Objects.requireNonNull(launchThread, "launchThread must not be null");
        return production(sink, thread -> thread == launchThread);
    }

    /**
     * The production funnel when {@code DawApplication} starts without
     * {@code DawLauncher.main}: no thread is the launch thread.
     *
     * @param sink the installed log sink
     * @return the funnel, not yet installed
     */
    public static FailureFunnel withoutLaunchThread(LogSink sink) {
        return production(sink, thread -> false);
    }

    private static FailureFunnel production(LogSink sink, Predicate<Thread> isLaunchThread) {
        Objects.requireNonNull(sink, "sink must not be null");
        InstantSource wallClock = InstantSource.system();
        return new FailureFunnel(new Collaborators(sink,
                new FingerprintRateLimiter(System::nanoTime, wallClock),
                FailureCopy.fromBundle(wallClock, ZoneId.systemDefault()),
                new LogFolderOpener(sink.folder(), LogFolderOpener.DESKTOP),
                isLaunchThread));
    }

    /**
     * The funnel behind {@link Thread#getDefaultUncaughtExceptionHandler()}, if
     * any. The JDK's default handler is the registry: restoring it uninstalls
     * the funnel.
     *
     * @return the installed funnel
     */
    public static Optional<FailureFunnel> installed() {
        return Thread.getDefaultUncaughtExceptionHandler() instanceof FunnelHandler handler
                ? Optional.of(handler.funnel())
                : Optional.empty();
    }

    /**
     * The process-wide handler; the same instance on every call.
     *
     * @return the default handler
     */
    public Thread.UncaughtExceptionHandler defaultHandler() {
        return defaultHandler;
    }

    /**
     * The FX thread's own handler; the same instance on every call.
     *
     * @return the FX thread's handler
     */
    public Thread.UncaughtExceptionHandler fxThreadHandler() {
        return fxHandler;
    }

    /** Installs {@link #defaultHandler()} as the process-wide default handler. */
    public void installAsDefaultHandler() {
        Thread.setDefaultUncaughtExceptionHandler(defaultHandler);
    }

    /**
     * Installs {@link #fxThreadHandler()} on the current thread, which must be
     * the FX application thread: the FX thread's failures are then labelled
     * {@code ui.handler} without asking the toolkit which thread this is.
     */
    public void installOnCurrentFxThread() {
        Thread.currentThread().setUncaughtExceptionHandler(fxHandler);
    }

    /**
     * Reports a failure whose site declared what failed ({@link DeclaredSubject}),
     * on the current thread. Never throws.
     *
     * @param subject what failed, as the site knows it
     * @param failure the throwable, reported exactly as it was thrown
     */
    public void reportDeclared(DeclaredSubject subject, Throwable failure) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(failure, "failure must not be null");
        report(FailureFact.Origin.DECLARING_SITE, subject.category(), Thread.currentThread(), failure,
                Optional.of(subject.subject()));
    }

    /**
     * The sink the composition root builds the application's
     * {@link FxDispatcher} with (story 337, ruling E32): each pulse item that
     * threw is an isolated consumer of {@link #UI_PULSE}, origin
     * {@code PULSE_DRAIN}; the item's kind and description go into its log
     * record only (story 337, ruling E36). Never throws. The same instance on
     * every call.
     *
     * @return the pulse failure sink
     */
    public PulseFailureSink pulseFailureSink() {
        return pulseSink;
    }

    /**
     * The sink the composition root builds the application's event bus
     * with (story 337, ruling E37): each consumer that threw is an isolated
     * consumer of {@link #BUS_SUBSCRIBER}, origin {@code BUS_DELIVERY}, whose
     * notice names the event type; the subscriber's description goes into
     * its log record only (story 337, ruling E36). Never throws. The same
     * instance on every call.
     *
     * @return the bus failure sink
     */
    public SubscriberFailureSink busFailureSink() {
        return busSink;
    }

    /**
     * Reports what the real-time threads recorded for one fingerprint in one
     * audio drain frame, under the window policy (story 337, ruling E44): its
     * first occurrence surfaces at once — one record and one toast with the
     * report's action; repeats inside the 30 s window are counted, weighted;
     * a repeat after it re-surfaces with the weighted running total; while the
     * fingerprint's escalation is latched it is only counted. On the FX
     * thread, from the audio health drain. Never throws.
     *
     * @param report the drain's report
     */
    public void reportRealTime(RealTimeReport report) {
        Objects.requireNonNull(report, "report must not be null");
        handleRealTimeGuarded(report, "reporting a real-time failure", () -> realTimeWithinWindow(report));
    }

    /**
     * Escalates a sustained real-time fault or a halt (story 337, rulings E41
     * and E44): the report's occurrences are counted, its fingerprint's
     * escalation is latched — no WARNING re-show until
     * {@link #clearEscalation} — and it always surfaces, whatever the window:
     * one record at its severity's level and one toast, with its history
     * entry, carrying the report's action. On the FX thread, from the audio
     * health drain. Never throws.
     *
     * @param report the drain's report; its severity is ERROR or FATAL
     */
    public void escalateRealTime(RealTimeReport report) {
        Objects.requireNonNull(report, "report must not be null");
        handleRealTimeGuarded(report, "escalating a real-time failure", () -> realTimeEscalated(report));
    }

    /**
     * Counts a real-time report folded into another notice of the same drain
     * frame (story 337, rulings E27 and E65): a first worker failure in the
     * same frame as a first render failure is one notice, the render one,
     * whose log record names the worker's anchor; a render failure in the
     * frame of a halt is folded into the halt's notice. The fold's occurrences
     * are counted, weighted; when they would surface, the fold writes its own
     * record at its severity — its site, its sampled anchors, its count and
     * the occurrences since its previous notice, and that it was folded — and
     * the notice is noted for the fingerprint, but no toast is shown. Every
     * counted occurrence so stays in exactly one record or tail line. Never
     * throws.
     *
     * @param report the drain's report of the folded fingerprint
     */
    public void foldRealTime(RealTimeReport report) {
        Objects.requireNonNull(report, "report must not be null");
        handleRealTimeGuarded(report, "counting a folded real-time failure", () -> realTimeFolded(report));
    }

    /**
     * Whether {@code fingerprint} has occurred and is still kept by the rate
     * limiter (story 337, ruling E27).
     *
     * @param fingerprint the fingerprint
     * @return whether it has been seen
     */
    public boolean hasSeen(Fingerprint fingerprint) {
        return limiter.hasSeen(fingerprint);
    }

    /**
     * Whether {@code fingerprint}'s escalation is latched (story 337, ruling
     * E44).
     *
     * @param fingerprint the fingerprint
     * @return whether it is escalated
     */
    public boolean isEscalated(Fingerprint fingerprint) {
        return limiter.isEscalated(fingerprint);
    }

    /**
     * Clears {@code fingerprint}'s escalation when its engine fault clears
     * (story 337, ruling E41): its occurrences follow the window rule again.
     *
     * @param fingerprint the fingerprint
     */
    public void clearEscalation(Fingerprint fingerprint) {
        limiter.clearEscalation(fingerprint);
    }

    /**
     * The funnel's Open Log Folder as a notice's action (story 337, ruling
     * E43): the action of every story-336 toast, and of the audio categories
     * a stream restart cannot cure (story 337, ruling E13). The same instance
     * on every call.
     *
     * @return the action
     */
    public NoticeAction openLogFolderNotice() {
        return openLogFolderNotice;
    }

    /**
     * The copy this funnel formats its notices with; the audio health drain
     * formats its reports' sentences with it too.
     *
     * @return the copy
     */
    FailureCopy copy() {
        return copy;
    }

    /**
     * Arms the toasts, on the FX thread, once the dispatcher and the
     * notification bar with its history are live (Failure Surfacing book
     * §6.4): registers the
     * tail check as a pulse participant, and posts — once — what is owed from
     * before: the sink's WARNINGs first, then one buffered failure's own
     * toast, or one summary toast for several, or — instead of the summary —
     * the toast of the first FATAL one among all of them, even one that came
     * after the first {@value #PRE_ARM_CAPACITY} and was only counted. Arming
     * again replaces the earlier target.
     *
     * @param dispatcher the application's dispatcher
     * @param bar        the notification bar; its history service receives the tails
     */
    public void armToasts(FxDispatcher dispatcher, NotificationBar bar) {
        Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        Objects.requireNonNull(bar, "bar must not be null");
        Runnable removal = dispatcher.addPulseParticipant(tailParticipant);
        Target target = new Target(dispatcher, bar, Optional.ofNullable(bar.getHistoryService()), removal);
        Target previous;
        Owed owed;
        synchronized (armLock) {
            previous = armed;
            armed = target;
            disarmed = false;
            owed = new Owed(List.copyOf(preArm), preArmOverflow, preArmFatal, pendingWriteFailure);
            clearPreArm();
            pendingWriteFailure = Optional.empty();
        }
        if (previous != null) {
            retire(previous);
        }
        postOwedNotices(target, owed);
    }

    /**
     * Disarms the toasts: from now on failures are logged only,
     * and the pulse participant is removed (javafx-application-design "## 15.
     * Anti-patterns to call out": every registration has its removal). The
     * default handler stays installed, which the exit hold relies on. Every
     * tail still owed is written to the log at once, due or not — no history
     * entry is owed any more — so that the log accounts for every occurrence
     * counted so far, whether or not the toasts were ever armed; occurrences
     * counted after this are written when the log closes, only with the exit
     * hold active ({@link FingerprintRateLimiter}'s "Accounting"). Idempotent.
     * It runs as the first statement of {@code DawApplication.stop()}, before
     * story 333's pinned {@code try}, so it never throws.
     */
    public void disarmToasts() {
        try {
            Target previous;
            synchronized (armLock) {
                previous = armed;
                armed = null;
                disarmed = true;
                clearPreArm();
            }
            if (previous != null) {
                retire(previous);
            }
        } catch (Throwable failure) {
            lastResort.write(Thread.currentThread(), "disarming the failure funnel's toasts", failure);
        }
        logEveryOwedTail("writing the tails still owed when the toasts were disarmed");
    }

    /**
     * Records every tail now due: for each, one line without a stack at its
     * last notice's level (SEVERE, or WARNING after a WARNING notice) and,
     * when armed and its fingerprint ever surfaced, one history-only entry at
     * that level with its last notice's action (Open Log Folder unless the
     * notice carried another; story 337, ruling E43). The tail of an isolated
     * consumer that never reached its threshold is log-only (ruling E35). The
     * pulse participant posts it as keyed work when a tail may be due; on the
     * FX thread. Never throws.
     */
    public void flushDueTails() {
        Handling handling = new Handling(Optional.empty(), RECORDING_DUE_TAILS);
        try {
            ScopedValue.where(IN_FUNNEL, handling).run(this::recordDueTails);
        } catch (Throwable failure) {
            lastResort.write(Thread.currentThread(), handling.activity(), failure);
        }
    }

    /**
     * Writes every tail still owed, due or not, to the log only: one line
     * each at its last notice's level, inside the funnel's binding; a line
     * that cannot be written
     * goes to the last resort naming its tail's fingerprint and count — or,
     * when even the tail's name cannot be built, naming {@code activity} —
     * and the tails after it are still written. Never throws.
     *
     * <p>The lock order that matters, not a list of every lock it may take:
     * the limiter's monitor, to take the tails, is released before anything
     * is written (the class Javadoc's rule); then, for each line, each root
     * handler's own lock is taken as {@code Logger.log} publishes the line to
     * it; and only when a write to the log file fails, the sink's own
     * error-reporting monitors and then {@code armLock}, which the sink's
     * write-failure notice takes ({@code onSinkWriteFailure}) — after the file
     * handler's lock when a flush or rotation failed under it, never before
     * it. Under the sink's error-manager monitor the failure is also printed
     * to {@code System.err}, which takes that stream's lock; and when the
     * toasts are still armed — at the log's close when
     * {@code DawApplication.stop()} never ran — the notice posts its toast
     * through {@link FxDispatcher#onFx(Runnable)}, whose
     * {@code Platform.runLater} takes the toolkit's own locks. It waits for no
     * other thread to finish its work, only for locks, and writes at most one
     * line per live fingerprint
     * ({@code FingerprintRateLimiter.MAX_FINGERPRINTS}, 256). At the shutdown
     * reset, where the exit hold calls it after the hold, it therefore extends
     * the reset past the hold by at most one line write per live
     * fingerprint.</p>
     *
     * @param activity what the funnel is doing, for the last-resort record
     */
    private void logEveryOwedTail(String activity) {
        try {
            ScopedValue.where(IN_FUNNEL, new Handling(Optional.empty(), activity)).run(() -> {
                for (FingerprintRateLimiter.Tail tail : limiter.takeEveryTail()) {
                    try {
                        logTail(tail);
                    } catch (Throwable failure) {
                        try {
                            lastResort.write(Thread.currentThread(), "writing " + tailNames.apply(tail), failure);
                        } catch (Throwable unnameable) {
                            lastResort.write(Thread.currentThread(), activity, failure);
                        }
                    }
                }
            });
        } catch (Throwable failure) {
            lastResort.write(Thread.currentThread(), activity, failure);
        }
    }

    /**
     * Opens the log folder, off the FX thread: the Help item and
     * the action of every toast that offers Open Log Folder. A failure shows
     * a plain ERROR toast — not through the funnel and without the action, so
     * it cannot loop — and one WARNING line. Never throws.
     */
    public void openLogFolder() {
        try {
            logFolder.open(this::onLogFolderOpenFailed);
        } catch (Throwable failure) {
            onLogFolderOpenFailed(failure);
        }
    }

    /**
     * The folder the log file is written into.
     *
     * @return the sink's folder
     */
    public Path logFolder() {
        return sink.folder();
    }

    /**
     * Every live fingerprint's total occurrences.
     *
     * @return fingerprint key → total
     */
    public Map<String, Long> occurrenceTotals() {
        return limiter.totals();
    }

    /**
     * The severity of a failure: a
     * {@link VirtualMachineError} — tested on the unwrapped throwable — is
     * FATAL; an {@code app.launch} failure is FATAL unless the launcher's
     * wrapper says the application ran and its {@code stop()} threw; an
     * {@link ExceptionInInitializerError} or {@link UnsatisfiedLinkError}
     * anywhere on the carrier path is FATAL, unless its anchor is plugin code;
     * everything else is ERROR.
     *
     * @param category    the category slug
     * @param failure     the throwable as caught
     * @param fingerprint its fingerprint
     * @return the severity
     */
    static FailureFact.Severity severityOf(String category, Throwable failure, Fingerprint fingerprint) {
        boolean launch = APP_LAUNCH.equals(category);
        List<Throwable> carriers = launch
                ? GuardedThrowables.launchCarrierPath(failure)
                : GuardedThrowables.carrierPath(failure);
        Throwable unwrapped = launch
                ? GuardedThrowables.unwrapLaunchCarriers(failure)
                : GuardedThrowables.unwrapCarriers(failure);
        if (unwrapped instanceof VirtualMachineError) {
            return FailureFact.Severity.FATAL;
        }
        if (launch) {
            return ranAndStopped(carriers) ? FailureFact.Severity.ERROR : FailureFact.Severity.FATAL;
        }
        if (isUnusableCode(unwrapped) || carriers.stream().anyMatch(FailureFunnel::isUnusableCode)) {
            return fingerprint.anchoredInPlugin() ? FailureFact.Severity.ERROR : FailureFact.Severity.FATAL;
        }
        return FailureFact.Severity.ERROR;
    }

    private static boolean ranAndStopped(List<Throwable> carriers) {
        for (Throwable carrier : carriers) {
            if (GuardedThrowables.isLauncherWrapper(carrier)) {
                String message = GuardedThrowables.message(carrier).orElse("");
                if (APPLICATION_STOP_WRAPPER.equals(message) || PRELOADER_STOP_WRAPPER.equals(message)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isUnusableCode(Throwable failure) {
        return failure instanceof ExceptionInInitializerError || failure instanceof UnsatisfiedLinkError;
    }

    // ── the funnel ───────────────────────────────────────────────────────────

    private void uncaught(FailureFact.Origin origin, Thread thread, Throwable failure) {
        String category;
        try {
            category = categoryOf(origin, thread);
        } catch (Throwable unexpected) {
            category = THREAD_UNCAUGHT;
        }
        report(origin, category, thread, failure, Optional.empty());
    }

    private String categoryOf(FailureFact.Origin origin, Thread thread) {
        if (origin == FailureFact.Origin.FX_HANDLER) {
            return UI_HANDLER;
        }
        if (isLaunchThread.test(thread)) {
            return APP_LAUNCH;
        }
        return FX_THREAD_NAME.equals(thread.getName()) ? UI_HANDLER : THREAD_UNCAUGHT;
    }

    /** Never throws: re-entry and the funnel's own failures go to the last-resort record. */
    private void report(FailureFact.Origin origin, String category, Thread thread, Throwable failure,
                        Optional<String> subject) {
        try {
            if (IN_FUNNEL.isBound()) {
                reentered(thread, failure);
                return;
            }
            try {
                ScopedValue.where(IN_FUNNEL, new Handling(Optional.of(failure), "handling a failure"))
                        .run(() -> handle(origin, category, thread, failure, subject));
            } catch (Throwable funnelFailure) {
                lastResort.write(thread, failure, funnelFailure);
            }
        } catch (Throwable givingUp) {
            // The end of the line: nothing may leave an uncaught-exception handler.
        }
    }

    private void reentered(Thread thread, Throwable failure) {
        Handling handling = IN_FUNNEL.get();
        if (handling.failure().isPresent()) {
            lastResort.write(thread, handling.failure().get(), failure);
        } else {
            lastResort.write(thread, handling.activity(), failure);
        }
    }

    private void handle(FailureFact.Origin origin, String category, Thread thread, Throwable failure,
                        Optional<String> subject) {
        boolean launch = APP_LAUNCH.equals(category);
        Throwable unwrapped = launch
                ? GuardedThrowables.unwrapLaunchCarriers(failure)
                : GuardedThrowables.unwrapCarriers(failure);
        Fingerprint fingerprint = Fingerprint.of(category, subject, unwrapped);
        FingerprintRateLimiter.Verdict verdict = limiter.occur(fingerprint, GuardedThrowables.reason(unwrapped));
        if (!(verdict instanceof FingerprintRateLimiter.Verdict.Surface surface)) {
            return;
        }
        surface.evicted().ifPresent(tail -> logTailOf(thread, failure, tail));
        FailureFact.Severity severity = severityOf(category, failure, fingerprint);
        String threadName = thread.getName();
        String base = copy.base(severity, category, subject, threadName, unwrapped);
        limiter.noticeShown(fingerprint, base, severity, openLogFolderNotice);
        FailureFact fact = new FailureFact(origin, severity, category, fingerprint, base, surface.total(),
                surface.firstSeen(), surface.at(), threadName, Optional.of(failure));
        try {
            logFact(fact, onThread(thread), surface.notLoggedSinceNotice(),
                    subject.map(declared -> List.of("subject \"" + declared + "\"")).orElse(List.of()));
        } catch (Throwable unloggable) {
            // A record that cannot be written — a log handler's formatter that throws an Error on a plugin's
            // exception, or memory running out while formatting — must not cost the failure its toast.
            lastResort.write(thread, failure, unloggable);
        }
        if (launch) {
            return;
        }
        String text = surface.total() == 1 ? base : copy.repeated(base, surface.total(), surface.firstSeen());
        toast(new PendingToast(fact, text, thread, openLogFolderNotice));
    }

    /**
     * One record of the fact, at its severity's level — SEVERE for FATAL and
     * ERROR, WARNING for WARNING (story 337, ruling E43) — with the original
     * throwable's whole chain when there is one.
     *
     * @param fact      the fact
     * @param where     where it failed: {@code on thread "name"}, or a real-time report's {@code at "site"}
     * @param notLogged the occurrences since the previous notice that no record logged
     * @param details   what the header names besides: a declared subject, an isolated consumer's offender,
     *                  a real-time report's sampled anchors
     */
    private void logFact(FailureFact fact, String where, long notLogged, List<String> details) {
        StringBuilder header = new StringBuilder(160)
                .append("Failure ").append(fact.category())
                .append(" (").append(fact.severity().name()).append(") ").append(where)
                .append(", occurrence ").append(fact.count())
                .append(", fingerprint ").append(fact.fingerprint().key());
        for (String detail : details) {
            header.append(", ").append(detail);
        }
        if (notLogged > 0) {
            header.append(", ").append(notLogged)
                    .append(" occurrences since the previous notice were not logged individually");
        }
        // One line, so the record's header line carries the fingerprint: a line break anywhere in it, a thread's
        // name and the key included, is written as its escape (story 336, studio-engineer ruling R992-3).
        LogRecord record = new LogRecord(fact.severity().logLevel(), GuardedThrowables.oneLine(header.toString()));
        record.setLoggerName(log.getName());
        fact.failure().ifPresent(record::setThrown);
        log.log(record);
    }

    private static String onThread(Thread thread) {
        return "on thread \"" + threadLabel(thread) + "\"";
    }

    // ── story 337: isolated consumers (the pulse and the bus) ───────────────

    /** The pulse sink's body; never throws (story 337, ruling E33). */
    private void pulseItemFailed(PulseItemKind kind, String offender, Throwable failure) {
        Thread thread = Thread.currentThread();
        try {
            reportIsolatedConsumer(UI_PULSE, FailureFact.Origin.PULSE_DRAIN, Optional.empty(),
                    kind + " " + offender, thread, failure);
        } catch (Throwable unreportable) {
            lastResortQuietly(thread, "reporting a failed pulse item", failure);
        }
    }

    /** The bus sink's body; never throws. */
    private void subscriberFailed(SubscriberDescription subscriber, Throwable failure) {
        Thread thread = Thread.currentThread();
        try {
            reportIsolatedConsumer(BUS_SUBSCRIBER, FailureFact.Origin.BUS_DELIVERY,
                    Optional.of(subscriber.eventTypeName()), subscriber.toString(), thread, failure);
        } catch (Throwable unreportable) {
            lastResortQuietly(thread, "reporting a failed event bus consumer", failure);
        }
    }

    private void lastResortQuietly(Thread thread, String activity, Throwable failure) {
        try {
            lastResort.write(thread, activity, failure);
        } catch (Throwable givingUp) {
            // The end of the line: a sink must not throw into the dispatcher or the bus.
        }
    }

    /** Never throws: re-entry and the funnel's own failures go to the last-resort record, as in {@link #report}. */
    private void reportIsolatedConsumer(String category, FailureFact.Origin origin, Optional<String> copyArgument,
                                        String offender, Thread thread, Throwable failure) {
        try {
            if (IN_FUNNEL.isBound()) {
                reentered(thread, failure);
                return;
            }
            try {
                ScopedValue.where(IN_FUNNEL, new Handling(Optional.of(failure), "handling a failure"))
                        .run(() -> handleIsolatedConsumer(category, origin, copyArgument, offender, thread, failure));
            } catch (Throwable funnelFailure) {
                lastResort.write(thread, failure, funnelFailure);
            }
        } catch (Throwable givingUp) {
            // The end of the line: a sink must not throw into the dispatcher or the bus.
        }
    }

    /**
     * The isolated-consumer policy (story 337, rulings E34–E36): the
     * fingerprint is the standard {@code category|exceptionClass|anchor} with
     * no declared subject; the offender is named in the record only. ERROR is
     * graded down to WARNING; a FATAL one takes the standard path and
     * surfaces at once.
     */
    private void handleIsolatedConsumer(String category, FailureFact.Origin origin, Optional<String> copyArgument,
                                        String offender, Thread thread, Throwable failure) {
        Throwable unwrapped = GuardedThrowables.unwrapCarriers(failure);
        Fingerprint fingerprint = Fingerprint.of(category, Optional.empty(), unwrapped);
        String reason = GuardedThrowables.reason(unwrapped);
        boolean fatal = severityOf(category, failure, fingerprint) == FailureFact.Severity.FATAL;
        FailureFact.Severity severity = fatal ? FailureFact.Severity.FATAL : FailureFact.Severity.WARNING;
        FingerprintRateLimiter.Verdict verdict = fatal
                ? limiter.occur(fingerprint, reason)
                : limiter.occurIsolatedConsumer(fingerprint, reason);
        String threadName = thread.getName();
        List<String> details = List.of("offender " + offender);
        if (verdict instanceof FingerprintRateLimiter.Verdict.LogOnly logOnly) {
            logOnly.evicted().ifPresent(tail -> logTailOf(thread, failure, tail));
            FailureFact fact = new FailureFact(origin, severity, category, fingerprint,
                    copy.isolatedConsumer(category, copyArgument, unwrapped), 1, logOnly.at(), logOnly.at(),
                    threadName, Optional.of(failure));
            writeFact(fact, thread, failure, 0, details);
            return;
        }
        if (!(verdict instanceof FingerprintRateLimiter.Verdict.Surface surface)) {
            return;
        }
        surface.evicted().ifPresent(tail -> logTailOf(thread, failure, tail));
        String base = fatal
                ? copy.base(severity, category, Optional.empty(), threadName, unwrapped)
                : copy.isolatedConsumer(category, copyArgument, unwrapped);
        limiter.noticeShown(fingerprint, base, severity, openLogFolderNotice);
        FailureFact fact = new FailureFact(origin, severity, category, fingerprint, base, surface.total(),
                surface.firstSeen(), surface.at(), threadName, Optional.of(failure));
        writeFact(fact, thread, failure, surface.notLoggedSinceNotice(), details);
        // The threshold notice shows the base sentence; a re-show after the window carries the running total.
        boolean firstNotice = fatal
                ? surface.total() == 1
                : surface.total() <= FingerprintRateLimiter.ISOLATED_CONSUMER_THRESHOLD;
        String text = firstNotice ? base : copy.repeated(base, surface.total(), surface.firstSeen());
        toast(new PendingToast(fact, text, thread, openLogFolderNotice));
    }

    /** The fact's record; one that cannot be written costs the failure nothing more (it goes to the last resort). */
    private void writeFact(FailureFact fact, Thread thread, Throwable failure, long notLogged, List<String> details) {
        try {
            logFact(fact, onThread(thread), notLogged, details);
        } catch (Throwable unloggable) {
            lastResort.write(thread, failure, unloggable);
        }
    }

    // ── story 337: the real-time reports of the audio health drain ──────────

    /** Never throws: re-entry and the funnel's own failures go to the last-resort record. */
    private void handleRealTimeGuarded(RealTimeReport report, String activity, Runnable body) {
        Thread thread = Thread.currentThread();
        try {
            if (IN_FUNNEL.isBound()) {
                lastResort.write(thread, activity + " (" + report.fingerprint().key() + ") inside the funnel",
                        report.newest().orElseGet(() -> new IllegalStateException(report.baseText())));
                return;
            }
            try {
                ScopedValue.where(IN_FUNNEL, new Handling(report.newest(), activity)).run(body);
            } catch (Throwable funnelFailure) {
                lastResort.write(thread, activity + " (" + report.fingerprint().key() + ")", funnelFailure);
            }
        } catch (Throwable givingUp) {
            // The end of the line: the drain's pulse must not be thrown into.
        }
    }

    private void realTimeWithinWindow(RealTimeReport report) {
        FingerprintRateLimiter.Verdict verdict =
                limiter.occur(report.fingerprint(), guardedMessageOf(report), report.weight());
        if (verdict instanceof FingerprintRateLimiter.Verdict.Surface surface) {
            surfaceRealTime(report, surface, false);
        }
    }

    private void realTimeEscalated(RealTimeReport report) {
        surfaceRealTime(report, limiter.occurEscalating(report.fingerprint(), guardedMessageOf(report),
                report.weight()), true);
    }

    private void realTimeFolded(RealTimeReport report) {
        FingerprintRateLimiter.Verdict verdict =
                limiter.occur(report.fingerprint(), guardedMessageOf(report), report.weight());
        if (verdict instanceof FingerprintRateLimiter.Verdict.Surface surface) {
            Thread thread = Thread.currentThread();
            Fingerprint fingerprint = report.fingerprint();
            surface.evicted().ifPresent(tail -> logEvictedTail(tail, "counting a folded real-time failure"));
            // Its own record, without a toast: the surface has handed over what was counted since the last notice,
            // so only this record accounts for it (story 337, ruling E65).
            FailureFact fact = realTimeFact(report, surface);
            List<String> details = new ArrayList<>(sampledAnchorDetails(report));
            details.add(FOLDED_DETAIL);
            try {
                logFact(fact, "at \"" + report.site() + "\"", surface.notLoggedSinceNotice(), details);
            } catch (Throwable unloggable) {
                lastResort.write(thread, "writing the record of " + fingerprint.key(), unloggable);
            }
            limiter.noticeShown(fingerprint, report.baseText(), report.severity(), report.action());
        }
    }

    /**
     * One record at the report's severity, naming its site and every anchor
     * sampled since the last notice (story 337, ruling E40), and one toast
     * with its action. A weighted surface is the fingerprint's first when its
     * total is the report's weight: then the base sentence shows, else the
     * running total; an escalation shows its base sentence, which carries its
     * own count.
     */
    private void surfaceRealTime(RealTimeReport report, FingerprintRateLimiter.Verdict.Surface surface,
                                 boolean escalation) {
        Thread thread = Thread.currentThread();
        Fingerprint fingerprint = report.fingerprint();
        surface.evicted().ifPresent(tail -> logEvictedTail(tail, "writing a tail evicted by a real-time failure"));
        String base = report.baseText();
        limiter.noticeShown(fingerprint, base, report.severity(), report.action());
        FailureFact fact = realTimeFact(report, surface);
        List<String> details = sampledAnchorDetails(report);
        try {
            logFact(fact, "at \"" + report.site() + "\"", surface.notLoggedSinceNotice(), details);
        } catch (Throwable unloggable) {
            lastResort.write(thread, "writing the record of " + fingerprint.key(), unloggable);
        }
        String text = escalation || surface.total() == report.weight()
                ? base
                : copy.repeated(base, surface.total(), surface.firstSeen());
        toast(new PendingToast(fact, text, thread, report.action()));
    }

    /** A surfaced real-time report's fact: its base sentence, the surface's running total and times, its site. */
    private static FailureFact realTimeFact(RealTimeReport report, FingerprintRateLimiter.Verdict.Surface surface) {
        Fingerprint fingerprint = report.fingerprint();
        return new FailureFact(report.origin(), report.severity(), fingerprint.category(), fingerprint,
                report.baseText(), surface.total(), surface.firstSeen(), surface.at(), report.site(), report.newest());
    }

    /** The record header's detail naming the anchors sampled since the last notice, if any (story 337, ruling E40). */
    private static List<String> sampledAnchorDetails(RealTimeReport report) {
        return report.sampledAnchors().isEmpty()
                ? List.of()
                : List.of("sampled anchors " + String.join("; ", report.sampledAnchors()));
    }

    /** The message a real-time report's occurrences are counted under: its newest throwable's reason, or its site. */
    private static String guardedMessageOf(RealTimeReport report) {
        return report.newest()
                .map(newest -> GuardedThrowables.reason(GuardedThrowables.unwrapCarriers(newest)))
                .orElse(report.site());
    }

    /** An evicted fingerprint's tail line, when no throwable is being handled: a failure to write it goes to the last resort. */
    private void logEvictedTail(FingerprintRateLimiter.Tail evicted, String activity) {
        Thread thread = Thread.currentThread();
        try {
            logTail(evicted);
        } catch (Throwable unloggable) {
            try {
                lastResort.write(thread, "writing " + tailNames.apply(evicted) + ", whose fingerprint was evicted",
                        unloggable);
            } catch (Throwable unnameable) {
                lastResort.write(thread, activity, unloggable);
            }
        }
    }

    /**
     * An evicted fingerprint's tail line. What writing it throws costs the
     * failure being handled nothing; it goes to the last resort naming the
     * tail's fingerprint and count, so the count is recorded even when the
     * line reached no log file. When even the tail's name cannot be built,
     * the last resort records the failure being handled and what writing the
     * line threw, which needs nothing more; either way the failure being
     * handled still gets its record and its toast.
     */
    private void logTailOf(Thread thread, Throwable handled, FingerprintRateLimiter.Tail evicted) {
        try {
            logTail(evicted);
        } catch (Throwable unloggable) {
            try {
                lastResort.write(thread, "writing " + tailNames.apply(evicted) + ", whose fingerprint was evicted",
                        unloggable);
            } catch (Throwable unnameable) {
                lastResort.write(thread, handled, unloggable);
            }
        }
    }

    /** A tail as a last-resort record names it: its fingerprint and how many occurrences it accounts for. */
    static String tailNamed(FingerprintRateLimiter.Tail tail) {
        return "the tail of " + tail.fingerprint().key() + " (" + tail.suppressed()
                + " occurrences counted since its last notice)";
    }

    /**
     * One line without a stack that accounts for a tail, at the level of its
     * last notice's severity — SEVERE, or WARNING for a WARNING notice (story
     * 337, ruling E43); a line break in its fingerprint is written as its
     * escape (story 336, studio-engineer ruling R992-3).
     */
    private void logTail(FingerprintRateLimiter.Tail tail) {
        LogRecord record = new LogRecord(tail.severity().logLevel(), GuardedThrowables.oneLine("Failure "
                + tail.fingerprint().category() + " repeated " + tail.suppressed() + " more times between "
                + logTime(tail.firstSuppressed()) + " and " + logTime(tail.last())
                + ", not logged individually (fingerprint " + tail.fingerprint().key() + "); last message: "
                + tail.lastMessage()));
        record.setLoggerName(log.getName());
        log.log(record);
    }

    private static String logTime(Instant at) {
        return LOG_TIME.format(at.atZone(ZoneId.systemDefault()));
    }

    private static String threadLabel(Thread thread) {
        String name = thread.getName();
        return name.isEmpty() ? "#" + thread.threadId() : name;
    }

    /** Posts the toast when armed, keeps it when not yet armed, drops it after teardown. */
    private void toast(PendingToast toast) {
        Target target;
        synchronized (armLock) {
            target = armed;
            if (target == null) {
                if (!disarmed) {
                    keepUntilArmed(toast);
                }
                return;
            }
        }
        postFact(target, toast);
    }

    /**
     * Keeps a toast for the arming: the first {@link #PRE_ARM_CAPACITY} in
     * order, a count of the rest, and the first FATAL one whichever it was, so
     * that a FATAL fact after a full buffer still replaces the summary.
     * Caller holds {@link #armLock}.
     */
    private void keepUntilArmed(PendingToast toast) {
        if (preArmFatal.isEmpty() && toast.fact().severity() == FailureFact.Severity.FATAL) {
            preArmFatal = Optional.of(toast);
        }
        if (preArm.size() < PRE_ARM_CAPACITY) {
            preArm.add(toast);
        } else {
            preArmOverflow++;
        }
    }

    /** Forgets what was kept for the arming. Caller holds {@link #armLock}. */
    private void clearPreArm() {
        preArm.clear();
        preArmOverflow = 0;
        preArmFatal = Optional.empty();
    }

    private void postFact(Target target, PendingToast toast) {
        Handling handling = new Handling(toast.fact().failure(), "showing a failure's toast");
        postOnFx(target, handling, toast.failingThread(), () -> target.bar().show(
                toast.fact().severity().notificationLevel(), toast.text(), toast.action().label(),
                toast.action().action()));
    }

    private void postWithAction(Target target, NotificationLevel level, String text, String activity) {
        postOnFx(target, new Handling(Optional.empty(), activity), Thread.currentThread(),
                () -> target.bar().show(level, text, openLogFolderLabel, openLogFolderAction));
    }

    /** A toast without the action, never through the funnel: it cannot loop. */
    private void postPlain(Target target, NotificationLevel level, String text, String activity) {
        postOnFx(target, new Handling(Optional.empty(), activity), Thread.currentThread(),
                () -> target.bar().show(level, text));
    }

    /**
     * Posts {@code body} un-keyed (FIFO; never coalesced, which would drop
     * distinct facts) to the target's dispatcher. On the FX thread it runs only
     * while that target is still armed, bound in {@link #IN_FUNNEL} and inside
     * its own {@code catch (Throwable)}, so that nothing it runs re-enters the
     * funnel.
     */
    private void postOnFx(Target target, Handling handling, Thread reportOn, Runnable body) {
        target.dispatcher().onFx(() -> {
            try {
                if (armed != target) {
                    return;
                }
                ScopedValue.where(IN_FUNNEL, handling).run(body);
            } catch (Throwable toastFailure) {
                if (handling.failure().isPresent()) {
                    lastResort.write(reportOn, handling.failure().get(), toastFailure);
                } else {
                    lastResort.write(Thread.currentThread(), handling.activity(), toastFailure);
                }
            }
        });
    }

    /**
     * What arming owes, posted once in this order: the sink's WARNINGs, then
     * the first FATAL fact among every
     * pre-arm fact, else the one fact, else the summary counting them all.
     */
    private void postOwedNotices(Target target, Owed owed) {
        try {
            if (sink.unavailableReason().isPresent()) {
                postPlain(target, NotificationLevel.WARNING,
                        copy.sinkUnavailable(sink.folder(), sink.unavailableReason().get()),
                        "showing that the log file could not be opened");
            }
            if (owed.writeFailure().isPresent()) {
                postWithAction(target, NotificationLevel.WARNING, copy.sinkWriteFailed(owed.writeFailure().get()),
                        "showing that the log file could not be written");
            }
            if (owed.firstFatal().isPresent()) {
                postFact(target, owed.firstFatal().get());
            } else if (owed.count() == 1) {
                postFact(target, owed.buffered().getFirst());
            } else if (owed.count() > 1) {
                postWithAction(target, NotificationLevel.ERROR, copy.startupSummary(owed.count()),
                        "showing the failures from before the window appeared");
            }
        } catch (Throwable failure) {
            lastResort.write(Thread.currentThread(), "showing what failed before the toasts were armed", failure);
        }
    }

    private void retire(Target target) {
        target.participantRemoval().run();
        target.dispatcher().cancelKey(tailFlushKey);
    }

    /** The pulse participant: a field read; when a tail may be due, the flush is posted as keyed work. */
    private void postTailsIfDue() {
        try {
            Target target = armed;
            if (target != null && limiter.nanoTime() >= limiter.nextTailDueNanos()) {
                target.dispatcher().onFx(tailFlushKey, flushDueTailsWork);
            }
        } catch (Throwable failure) {
            lastResort.write(Thread.currentThread(), "checking for the tails of rate-limited failures", failure);
        }
    }

    /**
     * Each due tail is recorded inside a binding of its own that names its
     * fingerprint and count, so a listener exception JavaFX hands to the FX
     * thread's handler while the tail's history entry is recorded goes to the
     * last resort naming both the tail's failure and what the listener threw,
     * and a tail line that cannot be written leaves its count in the last
     * resort. A tail whose name cannot be built is still recorded, under the
     * flush's own activity ({@link #RECORDING_DUE_TAILS}), and costs the tails
     * taken after it nothing.
     */
    private void recordDueTails() {
        Target target = armed;
        for (FingerprintRateLimiter.Tail tail : limiter.takeDueTails()) {
            recordDueTail(target, tail);
        }
    }

    private void recordDueTail(Target target, FingerprintRateLimiter.Tail tail) {
        String activity;
        try {
            activity = "recording " + tailNames.apply(tail);
        } catch (Throwable unnameable) {
            activity = RECORDING_DUE_TAILS;
        }
        try {
            ScopedValue.where(IN_FUNNEL, new Handling(Optional.empty(), activity)).run(() -> recordTail(target, tail));
        } catch (Throwable failure) {
            lastResort.write(Thread.currentThread(), activity, failure);
        }
    }

    /**
     * A due tail: its line, and — when its fingerprint ever surfaced and the
     * toasts are armed — one history entry at its last notice's level with
     * its last notice's action (story 337, ruling E43). The tail of an
     * isolated consumer that never reached its threshold is log-only (story
     * 337, ruling E35): no notice in the history for it to follow.
     */
    private void recordTail(Target target, FingerprintRateLimiter.Tail tail) {
        logTail(tail);
        if (tail.surfaced() && target != null && target.history().isPresent()) {
            NoticeAction action = tail.action().orElse(openLogFolderNotice);
            target.history().get().record(tail.severity().notificationLevel(),
                    copy.tail(tail.baseMessage(), tail.suppressed(), tail.last()),
                    Optional.of(action.action()), Optional.of(action.label()));
        }
    }

    /**
     * The sink's first write failure. It may run under the file
     * handler's lock — a failed flush, rotation or close is reported under it,
     * a failed write in {@code publish} after the lock is released
     * (JDK-8349206) — so it takes {@code armLock} only after that lock and
     * never logs.
     */
    private void onSinkWriteFailure(String reason) {
        Target target;
        synchronized (armLock) {
            target = armed;
            if (target == null) {
                if (!disarmed) {
                    pendingWriteFailure = Optional.of(reason);
                }
                return;
            }
        }
        try {
            postWithAction(target, NotificationLevel.WARNING, copy.sinkWriteFailed(reason),
                    "showing that the log file could not be written");
        } catch (Throwable failure) {
            lastResort.write(Thread.currentThread(), "showing that the log file could not be written", failure);
        }
    }

    /**
     * On the {@code daw-open-log-folder} thread: one WARNING line and a plain
     * ERROR toast, the line written inside the funnel's binding like every
     * record the funnel writes itself.
     */
    private void onLogFolderOpenFailed(Throwable failure) {
        Handling handling = new Handling(Optional.ofNullable(failure),
                "reporting that the log folder could not be opened");
        try {
            ScopedValue.where(IN_FUNNEL, handling).run(() -> reportLogFolderOpenFailed(failure));
        } catch (Throwable unexpected) {
            lastResort.write(Thread.currentThread(), handling.activity(), unexpected);
        }
    }

    private void reportLogFolderOpenFailed(Throwable failure) {
        Path folder = logFolder.folder();
        LogRecord record = new LogRecord(Level.WARNING, "The log folder " + folder + " could not be opened");
        record.setLoggerName(log.getName());
        record.setThrown(failure);
        log.log(record);
        Target target = armed;
        if (target != null) {
            postPlain(target, NotificationLevel.ERROR,
                    copy.logFolderOpenFailed(GuardedThrowables.reason(failure), folder),
                    "showing that the log folder could not be opened");
        }
    }

    private static Optional<Path> lastResortFile(Path folder) {
        try {
            return Files.isDirectory(folder) ? Optional.of(folder.resolve(LastResortLog.FILE_NAME)) : Optional.empty();
        } catch (Throwable unreadable) {
            return Optional.empty();
        }
    }

    /**
     * A handler of this funnel; its origin labels what it catches. As the
     * process's default handler it is also what the exit hold asks, at the
     * shutdown reset, for the lines still owed to the log (story 336, Goals
     * bullet 9).
     */
    private record FunnelHandler(FailureFunnel funnel, FailureFact.Origin origin)
            implements Thread.UncaughtExceptionHandler, ExitHoldingLogManager.OwedLines {
        private FunnelHandler {
            Objects.requireNonNull(funnel, "funnel must not be null");
            Objects.requireNonNull(origin, "origin must not be null");
        }

        @Override
        public void uncaughtException(Thread thread, Throwable failure) {
            funnel.uncaught(origin, thread, failure);
        }

        @Override
        public void writeOwedLines() {
            funnel.logEveryOwedTail("writing the tails still owed when the log closed");
        }
    }

    /** Where toasts go while armed. */
    private record Target(FxDispatcher dispatcher, NotificationBar bar,
                          Optional<NotificationHistoryService> history, Runnable participantRemoval) {
        private Target {
            Objects.requireNonNull(dispatcher, "dispatcher must not be null");
            Objects.requireNonNull(bar, "bar must not be null");
            Objects.requireNonNull(history, "history must not be null");
            Objects.requireNonNull(participantRemoval, "participantRemoval must not be null");
        }
    }

    /**
     * A surfaced failure's toast, with the thread it failed on — the drain's,
     * for a real-time report — and the action it carries (story 337, ruling
     * E43).
     */
    private record PendingToast(FailureFact fact, String text, Thread failingThread, NoticeAction action) {
        private PendingToast {
            Objects.requireNonNull(fact, "fact must not be null");
            Objects.requireNonNull(text, "text must not be null");
            Objects.requireNonNull(failingThread, "failingThread must not be null");
            Objects.requireNonNull(action, "action must not be null");
        }
    }

    /**
     * What arming owes, taken under {@code armLock}.
     *
     * @param buffered     the first {@link #PRE_ARM_CAPACITY} pre-arm toasts, in order
     * @param overflow     how many pre-arm toasts came after those
     * @param firstFatal   the first FATAL toast among all of them, buffered or not
     * @param writeFailure the sink's first write failure, when one is owed a notice
     */
    private record Owed(List<PendingToast> buffered, int overflow, Optional<PendingToast> firstFatal,
                        Optional<String> writeFailure) {
        private Owed {
            Objects.requireNonNull(buffered, "buffered must not be null");
            Objects.requireNonNull(firstFatal, "firstFatal must not be null");
            Objects.requireNonNull(writeFailure, "writeFailure must not be null");
        }

        /** How many pre-arm toasts there were, the overflow included. */
        int count() {
            return buffered.size() + overflow;
        }
    }

    /** What the current thread is doing inside the funnel: the failure it handles, if any, and the activity. */
    private record Handling(Optional<Throwable> failure, String activity) {
        private Handling {
            Objects.requireNonNull(failure, "failure must not be null");
            Objects.requireNonNull(activity, "activity must not be null");
        }
    }
}
