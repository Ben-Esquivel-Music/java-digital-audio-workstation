package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.DawLauncher;
import com.benesquivelmusic.daw.app.diagnostics.FailureCopy;
import com.benesquivelmusic.daw.app.diagnostics.FailureFunnel;
import com.benesquivelmusic.daw.app.diagnostics.FingerprintRateLimiter;
import com.benesquivelmusic.daw.app.diagnostics.LastResortLog;
import com.benesquivelmusic.daw.app.diagnostics.LogFolderOpener;
import com.benesquivelmusic.daw.app.diagnostics.LogSink;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;

import javafx.application.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * The failure spine of one test (story 336): a failure funnel, its log file in
 * a test's own settings directory and — once {@linkplain #armOnFx() armed} —
 * the notification bar its toasts reach, with everything global the spine
 * touches put back on {@link #close()}.
 *
 * <p><strong>The substitution.</strong> {@code MainController} is never
 * FXML-loaded in tests and {@code DawApplication} never starts in them, so
 * {@link #armOnFx()} builds what they build — an {@link FxDispatcher}, a
 * {@link NotificationBar} whose {@link NotificationHistoryService} is set as
 * {@code MainController.initializeNotificationBar} sets it — and then calls
 * the same production units {@code DawApplication.start} and
 * {@code MainController.attachFailureFunnel} call,
 * {@link FailureFunnel#installOnCurrentFxThread()} and
 * {@link FailureFunnel#armToasts}. Three things differ, each for the test's
 * sake: the dispatcher is not started, so a test drives its pulse
 * ({@link #pulse()}, the very body of the timer {@code start()} would run);
 * the bar does not animate; and a toast stays up for ten minutes instead of
 * five seconds, so that a test reads it. {@code FailureSpineWiringSourceTest}
 * pins the production call sites themselves.</p>
 *
 * <p><strong>Three funnels.</strong> {@link #launch} is the production
 * wiring: {@code DawLauncher.prepareLaunch}, which selects the log manager,
 * installs the log sink, writes the banner and installs the funnel as the
 * default handler, with the production clocks, the production desktop opener
 * and the test's thread as the launch thread. {@link #withClock} installs a
 * funnel over the test's own clocks and folder opener as the default handler.
 * {@link #detached} builds one the default handler does not reach, for a test
 * that hands it its failures through a thread's own handler and must not be
 * reached by anything else.</p>
 *
 * <p><strong>Global state.</strong> The default handler, the FX thread's own
 * handler, the root logger's level, handlers and their formatters, and the
 * {@code java.util.logging.manager} property are global in the shared
 * surefire fork: they are taken on construction and put back on
 * {@link #close()}, which also disarms the funnel, dismisses the bar, disposes
 * the dispatcher and closes every root handler the spine added, so that the
 * test's temporary folder can be deleted on Windows. A thread an earlier test
 * leaked may still fail into a funnel installed here, so tests pick their own
 * failures out by thread name, message or identity.</p>
 */
public final class FailureSpineFixture implements AutoCloseable {

    /** The bound of every wait here; well inside the daw-app suite's 60 s per test. */
    public static final Duration GUARD = Duration.ofSeconds(20);

    /** How long a toast stays up in a test: long enough to read, never long enough to matter. */
    private static final long TOAST_MILLIS = 600_000;

    private static final String LOG_MANAGER_PROPERTY = "java.util.logging.manager";

    private final Path settings;
    private final Thread.UncaughtExceptionHandler previousDefaultHandler;
    private final Thread fxThread;
    private final Optional<Thread.UncaughtExceptionHandler> previousFxHandler;
    private final Logger root = Logger.getLogger("");
    private final Level previousRootLevel;
    private final List<Handler> previousRootHandlers;
    private final Map<Handler, Formatter> previousFormatters = new IdentityHashMap<>();
    private final Optional<String> previousLogManager;
    private final Logger funnelLogger = Logger.getLogger(FailureFunnel.class.getName());
    private final List<LogRecord> funnelRecords = new CopyOnWriteArrayList<>();
    private final Handler funnelRecorder = new Handler() {
        @Override
        public void publish(LogRecord record) {
            funnelRecords.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private FailureFunnel funnel;
    private Optional<LogSink> sink = Optional.empty();
    private FxDispatcher dispatcher;
    private NotificationBar bar;
    private NotificationHistoryService history;

    private FailureSpineFixture(Path settings) throws Exception {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        previousDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        fxThread = onFx(Thread::currentThread);
        Thread.UncaughtExceptionHandler fxHandler = onFx(fxThread::getUncaughtExceptionHandler);
        previousFxHandler = fxHandler == fxThread.getThreadGroup() ? Optional.empty() : Optional.of(fxHandler);
        previousRootLevel = root.getLevel();
        previousRootHandlers = List.of(root.getHandlers());
        previousRootHandlers.forEach(handler -> previousFormatters.put(handler, handler.getFormatter()));
        previousLogManager = Optional.ofNullable(System.getProperty(LOG_MANAGER_PROPERTY));
        funnelLogger.addHandler(funnelRecorder);
    }

    /**
     * The production wiring: {@code DawLauncher.prepareLaunch(settings)} on the
     * calling thread, which is then the launch thread.
     *
     * @param settings the test's settings directory
     * @return the spine, its funnel installed as the default handler
     * @throws Exception if the FX thread does not answer, or a funnel was already installed
     */
    public static FailureSpineFixture launch(Path settings) throws Exception {
        FailureSpineFixture spine = new FailureSpineFixture(settings);
        try {
            if (FailureFunnel.installed().isPresent()) {
                throw new IllegalStateException("a failure funnel is already installed: an earlier test did not"
                        + " put the default handler back");
            }
            spine.funnel = DawLauncher.prepareLaunch(settings);
            return spine;
        } catch (Throwable failure) {
            spine.closeAfter(failure);
            throw failure;
        }
    }

    /**
     * A funnel over the test's clocks and folder opener, installed as the
     * default handler; its log sink writes into {@code settings/logs}.
     *
     * @param settings  the test's settings directory
     * @param nanos     the monotonic clock of the rate limiter's window
     * @param wallClock the wall clock of the copy's times
     * @param opener    what Open Log Folder opens the folder with
     * @return the spine
     * @throws Exception if the FX thread does not answer
     */
    public static FailureSpineFixture withClock(Path settings, LongSupplier nanos, InstantSource wallClock,
                                                LogFolderOpener.FolderOpener opener) throws Exception {
        FailureSpineFixture spine = detached(settings, nanos, wallClock, opener);
        spine.funnel.installAsDefaultHandler();
        return spine;
    }

    /**
     * As {@link #withClock}, but the default handler does not reach the
     * funnel: a test hands it failures through a thread's own handler,
     * {@link FailureFunnel#defaultHandler()}.
     *
     * @param settings  the test's settings directory
     * @param nanos     the monotonic clock of the rate limiter's window
     * @param wallClock the wall clock of the copy's times
     * @param opener    what Open Log Folder opens the folder with
     * @return the spine
     * @throws Exception if the FX thread does not answer
     */
    public static FailureSpineFixture detached(Path settings, LongSupplier nanos, InstantSource wallClock,
                                               LogFolderOpener.FolderOpener opener) throws Exception {
        return detached(settings, nanos, wallClock, opener, FailureFunnel::new);
    }

    /**
     * As {@link #detached(Path, LongSupplier, InstantSource, LogFolderOpener.FolderOpener)},
     * with the funnel {@code funnelOn} builds on the same collaborators: for a
     * test that builds it through a test-only seam of the funnel's own
     * package.
     *
     * @param settings  the test's settings directory
     * @param nanos     the monotonic clock of the rate limiter's window
     * @param wallClock the wall clock of the copy's times
     * @param opener    what Open Log Folder opens the folder with
     * @param funnelOn  builds the funnel on its collaborators
     * @return the spine
     * @throws Exception if the FX thread does not answer
     */
    public static FailureSpineFixture detached(Path settings, LongSupplier nanos, InstantSource wallClock,
                                               LogFolderOpener.FolderOpener opener,
                                               Function<FailureFunnel.Collaborators, FailureFunnel> funnelOn)
            throws Exception {
        Objects.requireNonNull(funnelOn, "funnelOn must not be null");
        FailureSpineFixture spine = new FailureSpineFixture(settings);
        try {
            LogSink sink = LogSink.install(LogSink.folderUnder(settings));
            spine.sink = Optional.of(sink);
            spine.funnel = funnelOn.apply(new FailureFunnel.Collaborators(sink,
                    new FingerprintRateLimiter(nanos, wallClock),
                    FailureCopy.fromBundle(wallClock, ZoneId.systemDefault()),
                    new LogFolderOpener(sink.folder(), opener),
                    thread -> false));
            return spine;
        } catch (Throwable failure) {
            spine.closeAfter(failure);
            throw failure;
        }
    }

    /**
     * On the FX thread, as {@code DawApplication.start} and
     * {@code MainController} do it: installs the funnel's handler on the FX
     * thread, builds the dispatcher and the notification bar with its
     * history, and arms the funnel's toasts on them.
     *
     * @throws Exception if the FX thread does not answer, or arming throws
     */
    public void armOnFx() throws Exception {
        runOnFx(() -> {
            funnel.installOnCurrentFxThread();
            dispatcher = new FxDispatcher();
            history = new NotificationHistoryService();
            bar = new NotificationBar();
            bar.setAnimated(false);
            bar.setAutoDismissMillis(TOAST_MILLIS);
            bar.setHistoryService(history);
            funnel.armToasts(dispatcher, bar);
        });
    }

    /** @return the spine's funnel */
    public FailureFunnel funnel() {
        return funnel;
    }

    /**
     * @return the log sink {@link #withClock} or {@link #detached} installed; none for {@link #launch}, whose
     *         sink {@code DawLauncher.prepareLaunch} keeps
     */
    public Optional<LogSink> sink() {
        return sink;
    }

    /** @return the dispatcher the funnel was armed on, once {@link #armOnFx()} ran */
    public FxDispatcher dispatcher() {
        return dispatcher;
    }

    /** @return the notification bar the toasts reach, once {@link #armOnFx()} ran; read it on the FX thread */
    public NotificationBar bar() {
        return bar;
    }

    /** @return the bar's history, once {@link #armOnFx()} ran */
    public NotificationHistoryService history() {
        return history;
    }

    /** @return the test's settings directory */
    public Path settings() {
        return settings;
    }

    /** @return every record logged on the funnel's own logger since the spine was made, in order */
    public List<LogRecord> funnelRecords() {
        return List.copyOf(funnelRecords);
    }

    /** @return the current log file, {@code <settings>/logs/daw-0.log} */
    public Path logFile() {
        return LogSink.folderUnder(settings).resolve("daw-0.log");
    }

    /**
     * The current log file's text; the file handler flushes every record.
     *
     * @return the file's content
     * @throws IOException if it cannot be read
     */
    public String logText() throws IOException {
        return Files.readString(logFile(), StandardCharsets.UTF_8);
    }

    /** @return the last-resort file, {@code <settings>/logs/daw-last-resort.log} */
    public Path lastResortFile() {
        return LogSink.folderUnder(settings).resolve(LastResortLog.FILE_NAME);
    }

    /**
     * The last-resort file's text, or nothing when it was never written.
     *
     * @return the file's content, or {@code ""}
     * @throws IOException if it exists and cannot be read
     */
    public String lastResortText() throws IOException {
        Path file = lastResortFile();
        return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    }

    /**
     * One pulse of the dispatcher on the FX thread, after one FX turn: what
     * the timer would run on the next frame.
     *
     * @throws Exception if the FX thread does not answer, or the pulse throws
     */
    public void pulse() throws Exception {
        runOnFx(() -> { });
        runOnFx(dispatcher::pulse);
    }

    /**
     * Waits, bounded by {@link #GUARD}, until {@code condition} holds when read
     * on the FX thread.
     *
     * @param what      what is awaited, for the failure message
     * @param condition the condition, read on the FX thread
     * @throws Exception if it never holds, or the FX thread does not answer
     */
    public static void awaitOnFx(String what, Callable<Boolean> condition) throws Exception {
        await(what, () -> onFx(condition));
    }

    /**
     * Waits, bounded by {@link #GUARD}, until {@code condition} holds when read
     * on the calling thread.
     *
     * @param what      what is awaited, for the failure message
     * @param condition the condition
     * @throws Exception if it never holds, or reading it throws
     */
    public static void await(String what, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + GUARD.toNanos();
        while (!condition.call()) {
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError(what + " within " + GUARD);
            }
            Thread.sleep(10);
        }
    }

    /**
     * Runs {@code work} on the FX thread and returns what it returned,
     * rethrowing what it threw; bounded by {@link #GUARD}.
     *
     * @param work the work
     * @param <T>  its result's type
     * @return its result
     * @throws Exception what it threw, or if the FX thread does not answer
     */
    public static <T> T onFx(Callable<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(work.call());
            } catch (Throwable failure) {
                thrown.set(failure);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(GUARD.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new AssertionError("the FX thread did not answer within " + GUARD);
        }
        Throwable failure = thrown.get();
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof Exception exception) {
            throw exception;
        }
        return result.get();
    }

    /**
     * Runs {@code work} on the FX thread, rethrowing what it threw; bounded by
     * {@link #GUARD}.
     *
     * @param work the work
     * @throws Exception what it threw, or if the FX thread does not answer
     */
    public static void runOnFx(Runnable work) throws Exception {
        onFx(() -> {
            work.run();
            return null;
        });
    }

    /**
     * Puts back everything global the spine touched, every step whatever an
     * earlier one threw: disarms the funnel, dismisses the bar, disposes the
     * dispatcher, restores the FX thread's handler and the default handler,
     * removes and closes every root handler the spine added, restores the
     * root logger's level and formatters and the log manager property.
     *
     * @throws Exception the first step's failure, the later ones suppressed
     */
    @Override
    public void close() throws Exception {
        List<Throwable> failures = new ArrayList<>();
        step(failures, () -> {
            if (funnel != null) {
                funnel.disarmToasts();
            }
        });
        step(failures, () -> runOnFx(() -> {
            if (bar != null) {
                bar.dismiss();
            }
            if (dispatcher != null) {
                dispatcher.dispose();
            }
            fxThread.setUncaughtExceptionHandler(previousFxHandler.orElse(null));
        }));
        step(failures, () -> Thread.setDefaultUncaughtExceptionHandler(previousDefaultHandler));
        step(failures, () -> funnelLogger.removeHandler(funnelRecorder));
        step(failures, () -> {
            for (Handler handler : root.getHandlers()) {
                if (!previousRootHandlers.contains(handler)) {
                    root.removeHandler(handler);
                    handler.close();
                }
            }
            previousFormatters.forEach(Handler::setFormatter);
            root.setLevel(previousRootLevel);
        });
        step(failures, () -> {
            if (previousLogManager.isPresent()) {
                System.setProperty(LOG_MANAGER_PROPERTY, previousLogManager.get());
            } else {
                System.clearProperty(LOG_MANAGER_PROPERTY);
            }
        });
        if (failures.isEmpty()) {
            return;
        }
        Throwable first = failures.getFirst();
        failures.subList(1, failures.size()).forEach(first::addSuppressed);
        if (first instanceof Exception exception) {
            throw exception;
        }
        throw (Error) first;
    }

    private void closeAfter(Throwable failure) {
        try {
            close();
        } catch (Throwable closing) {
            failure.addSuppressed(closing);
        }
    }

    private static void step(List<Throwable> failures, Step step) {
        try {
            step.run();
        } catch (Throwable failure) {
            failures.add(failure);
        }
    }

    /** One step of {@link #close()}. */
    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }
}
