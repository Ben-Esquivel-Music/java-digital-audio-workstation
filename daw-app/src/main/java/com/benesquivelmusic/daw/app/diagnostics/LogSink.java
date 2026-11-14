package com.benesquivelmusic.daw.app.diagnostics;

import com.benesquivelmusic.daw.app.logging.ExitHoldingLogManager;

import java.io.File;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.ErrorManager;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The application's rotating log file (story 336, Goals bullet 6; Failure
 * Surfacing book §4.2), configured in code at launch — the jlink
 * image and the installer ship only the JDK's console configuration, and a
 * bundled properties file is something an image build can drop.
 *
 * <p>The files are {@code <settings>/logs/daw-0.log} (current, newest) to
 * {@code daw-4.log} (oldest), {@link #FILE_LIMIT_BYTES} each, so at most
 * 25&nbsp;MiB; generation 0 is appended to across sessions. Wherever the
 * handler starts writing — generation 0 at launch, after the earlier
 * sessions' lines, and each new generation 0 a rotation opens in the middle
 * of a session — this session's lines there begin with one line naming the
 * session: the application's version, when the session started and its
 * process (the file formatter's {@linkplain LogLineFormatter#getHead head};
 * studio-engineer ruling, story 336). At launch the
 * {@linkplain #logSessionStart() banner} follows it.
 * So any single {@code daw-N.log} says which session its records
 * belong to, and where each session's lines begin. Every record is flushed
 * as it is written ({@code FileHandler}'s own per-record flush), so a hard
 * crash keeps every line before it. The root logger and the file handler are
 * both at {@link Level#INFO}; no third-party logger's level is touched; the JDK's
 * console handler stays, but every root handler formats with the hardened
 * {@link LogLineFormatter} — whether or not the file could be opened —
 * because {@code Logger.log} publishes to the handlers in turn with no catch,
 * and a console handler whose formatter threw an {@link Error} would stop the
 * record before it reached the file, and would throw it at the failure funnel
 * writing it.</p>
 *
 * <p>The version is the build's own on every launcher: the {@code daw.app}
 * module's version ({@code 0.1.0-SNAPSHOT} today). The installer's version,
 * which jpackage requires to be numeric and the POM fixes at {@code 1.0.0},
 * never stands in for it; when the installed application runs, it follows it
 * labelled as the installer's, {@code 0.1.0-SNAPSHOT (installer 1.0.0)}
 * (story 336, studio-engineer ruling R-V4).</p>
 *
 * <p>The {@code FileHandler} pattern is the folder's absolute path with every
 * {@code %} doubled, so a folder whose name holds {@code %g} or {@code %t} is
 * written literally. {@code FileHandler.generate} re-attaches a pattern's
 * root without scanning it for tokens, so the escaping cannot reach the root
 * of the path: a UNC share name holding a {@code %} is not supported.</p>
 *
 * <p>The sink's own failures are not silent: a failed setup returns a sink
 * whose {@link #unavailableReason()} the funnel shows once it can, and the
 * first failed write is reported once to {@link #onFirstWriteFailure}.</p>
 */
public final class LogSink {

    /** The {@code FileHandler} pattern of the files, in the log folder. */
    public static final String FILE_PATTERN = "daw-%g.log";

    /** The size at which a file is rotated: 5 MiB. */
    public static final long FILE_LIMIT_BYTES = 5_242_880L;

    /** How many files are kept. */
    public static final int FILE_COUNT = 5;

    /** The log folder's name in the settings directory. */
    static final String FOLDER_NAME = "logs";

    /** The application's name in the session banner ({@code DawApplication.APP_TITLE}). */
    private static final String APPLICATION_NAME = "Digital Audio Workstation";

    /** The system property the jpackage launcher sets to the installer's version. */
    static final String INSTALLER_VERSION_PROPERTY = "jpackage.app-version";

    /** The version when the module carries none. */
    static final String VERSION_UNKNOWN = "version unknown";

    /** How the line that begins this session's lines in every file the handler writes begins. */
    static final String SESSION_LINE_PREFIX = "Lines from here on belong to the session of ";

    private final Path folder;
    private final Optional<FileHandler> fileHandler;
    private final Optional<String> unavailableReason;
    private final Object notices = new Object();
    private Consumer<String> writeFailureListener;
    private String writeFailure;
    private boolean writeFailureReported;

    private LogSink(Path folder, Optional<FileHandler> fileHandler, Optional<String> unavailableReason) {
        this.folder = folder;
        this.fileHandler = fileHandler;
        this.unavailableReason = unavailableReason;
    }

    /**
     * The log folder under a settings directory.
     *
     * @param settingsDirectory the user settings directory
     * @return {@code <settingsDirectory>/logs}
     */
    public static Path folderUnder(Path settingsDirectory) {
        return settingsDirectory.resolve(FOLDER_NAME);
    }

    /**
     * Installs the sink: creates the folder, opens the rotating file on the
     * root logger and gives every root handler the hardened format. Never
     * throws; on any failure it still gives every root handler the hardened
     * format, prints one line to standard error (nothing else exists yet) and
     * returns a sink whose {@link #unavailableReason()} says why.
     *
     * @param folder the log folder
     * @return the sink
     */
    public static LogSink install(Path folder) {
        return install(folder, FILE_LIMIT_BYTES, FILE_COUNT);
    }

    /** {@link #install(Path)} with another size and count: the seam of the rotation-count test only. */
    static LogSink install(Path folder, long limitBytes, int count) {
        Path absolute = folder.toAbsolutePath();
        FileHandler handler = null;
        try {
            Files.createDirectories(absolute);
            String pattern = absolute.toString().replace("%", "%%") + File.separator + FILE_PATTERN;
            handler = new FileHandler(pattern, limitBytes, count, true);
            handler.setLevel(Level.INFO);
            handler.setFormatter(new LogLineFormatter(LogSink.class.getName(), sessionLine()));
            handler.setEncoding("UTF-8");
            LogSink sink = new LogSink(absolute, Optional.of(handler), Optional.empty());
            handler.setErrorManager(sink.new SinkErrorManager());
            Logger root = Logger.getLogger("");
            root.setLevel(Level.INFO);
            root.addHandler(handler);
            hardenTheOtherRootHandlers(root, Optional.of(handler));
            return sink;
        } catch (Throwable unavailable) {
            String reason = GuardedThrowables.reason(unavailable);
            closeQuietly(handler);
            try {
                hardenTheOtherRootHandlers(Logger.getLogger(""), Optional.empty());
            } catch (Throwable unreachable) {
                // The root handlers keep their formatters; the funnel still toasts a record it cannot write.
            }
            try {
                System.err.println("The log file could not be opened in " + absolute + ": " + reason
                        + ". This session is not logged to a file.");
            } catch (Throwable unprintable) {
                // Nothing else exists yet.
            }
            return new LogSink(absolute, Optional.empty(), Optional.of(reason));
        }
    }

    /** Gives every root handler but the file handler the hardened format. */
    private static void hardenTheOtherRootHandlers(Logger root, Optional<Handler> fileHandler) {
        for (Handler each : root.getHandlers()) {
            if (fileHandler.isEmpty() || each != fileHandler.get()) {
                each.setFormatter(new LogLineFormatter());
            }
        }
    }

    /**
     * The line that begins this session's lines in every file the handler
     * writes (studio-engineer ruling, story 336), at launch ahead of the banner: the
     * application and its version, when this session started — now, as the
     * sink is installed at launch — and its process. Never throws.
     */
    static String sessionLine() {
        StringBuilder line = new StringBuilder(160).append(SESSION_LINE_PREFIX).append(APPLICATION_NAME)
                .append(' ').append(version());
        try {
            line.append(" that started at ").append(LogLineFormatter.timestamp(Instant.now()));
        } catch (Throwable unreadable) {
            // The version alone still names the build.
        }
        try {
            line.append(" as process ").append(ProcessHandle.current().pid());
        } catch (Throwable unreadable) {
            // As above.
        }
        return line.toString();
    }

    /**
     * The folder the file handler writes into: the one directory Help ▸ Open
     * Log Folder and every toast action open.
     *
     * @return the absolute log folder
     */
    public Path folder() {
        return folder;
    }

    /**
     * Why the log file could not be opened at launch, if it could not.
     *
     * @return the reason, or empty when the sink is writing
     */
    public Optional<String> unavailableReason() {
        return unavailableReason;
    }

    /**
     * The file handler on the root logger, when the sink is writing.
     *
     * @return the handler, or empty when the sink is unavailable
     */
    public Optional<Handler> handler() {
        return fileHandler.map(Handler.class::cast);
    }

    /**
     * Sets who is told, once, that a write to the log file failed. A failure
     * that already happened is told at once. The listener may run under the
     * file handler's lock — a failed flush, rotation or close is reported
     * under it; since JDK-8349206 a failed write in {@code publish} is
     * reported after the lock is released — so it must never log (it would
     * re-enter the handler) and must take no lock that is held while logging.
     *
     * @param listener receives the reason of the first failed write
     */
    public void onFirstWriteFailure(Consumer<String> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        String failed;
        synchronized (notices) {
            writeFailureListener = listener;
            if (writeFailure == null || writeFailureReported) {
                return;
            }
            writeFailureReported = true;
            failed = writeFailure;
        }
        tell(listener, failed);
    }

    /**
     * Logs the session banner at INFO, two lines that at launch
     * follow the file's session line: which application, Java,
     * operating system, process and heap; and whether the exit hold is active
     * on this launch path — the in-file record of a
     * {@code java.util.logging.manager} fallback that {@code LogManager}
     * itself reports only on standard error. Never throws.
     */
    public void logSessionStart() {
        try {
            Logger log = Logger.getLogger(LogSink.class.getName());
            Runtime runtime = Runtime.getRuntime();
            log.info("Session started: " + APPLICATION_NAME + " " + version()
                    + ", Java " + System.getProperty("java.runtime.version") + " (" + System.getProperty("java.vendor")
                    + "), " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                    + " (" + System.getProperty("os.arch") + "), process " + ProcessHandle.current().pid()
                    + ", maximum heap " + runtime.maxMemory() / (1024L * 1024L) + " MiB");
            log.info(ExitHoldingLogManager.isActive()
                    ? "Exit hold: active"
                    : "Exit hold: inactive (java.util.logging.manager does not name the application's log manager"
                            + " on this launch path); lines logged while the application exits may be lost");
        } catch (Throwable unloggable) {
            // The banner is best effort; the file handler reports its own write failures.
        }
    }

    /** The build's version as {@link #applicationVersion} words it, on this launcher. Never throws. */
    private static String version() {
        try {
            Optional<String> installer = Optional.ofNullable(System.getProperty(INSTALLER_VERSION_PROPERTY));
            return applicationVersion(moduleVersion(), installer);
        } catch (Throwable unreadable) {
            return VERSION_UNKNOWN;
        }
    }

    /** The {@code daw.app} module's own version, when it carries one. */
    private static Optional<String> moduleVersion() {
        ModuleDescriptor descriptor = LogSink.class.getModule().getDescriptor();
        return descriptor == null ? Optional.empty() : descriptor.rawVersion();
    }

    /**
     * The version the banner and the session line name (story 336, studio-engineer
     * ruling R-V4): the build's own — the module's version, or
     * {@value #VERSION_UNKNOWN} — and after it, when the installed application
     * runs, the installer's, labelled as such; the installer's never stands in
     * for the build's.
     *
     * @param moduleVersion    the {@code daw.app} module's version, if any
     * @param installerVersion the installer's version ({@value #INSTALLER_VERSION_PROPERTY}), if any
     * @return for example {@code 0.1.0-SNAPSHOT}, or {@code 0.1.0-SNAPSHOT (installer 1.0.0)}
     */
    static String applicationVersion(Optional<String> moduleVersion, Optional<String> installerVersion) {
        String build = moduleVersion.filter(version -> !version.isBlank()).orElse(VERSION_UNKNOWN);
        return installerVersion.filter(version -> !version.isBlank())
                .map(installer -> build + " (installer " + installer + ")")
                .orElse(build);
    }

    private void recordWriteFailure(String reason) {
        Consumer<String> listener;
        synchronized (notices) {
            if (writeFailure != null) {
                return;
            }
            writeFailure = reason;
            if (writeFailureListener == null) {
                return;
            }
            writeFailureReported = true;
            listener = writeFailureListener;
        }
        tell(listener, reason);
    }

    private static void tell(Consumer<String> listener, String reason) {
        try {
            listener.accept(reason);
        } catch (Throwable ignored) {
            // A listener's failure must not reach the file handler, which would report it to this sink again.
        }
    }

    private static void closeQuietly(Handler handler) {
        if (handler == null) {
            return;
        }
        try {
            Logger.getLogger("").removeHandler(handler);
            handler.close();
        } catch (Throwable ignored) {
            // Already unusable.
        }
    }

    /**
     * Records the file handler's first failure, prints it once to standard
     * error as the JDK's own error manager does, and tells the sink's
     * listener once. Never logs: it runs inside the file handler's publish.
     */
    private final class SinkErrorManager extends ErrorManager {

        private boolean reported;

        @Override
        public synchronized void error(String message, Exception failure, int code) {
            if (reported) {
                return;
            }
            reported = true;
            String reason = failure != null
                    ? GuardedThrowables.reason(failure)
                    : message != null ? message : "error code " + code;
            try {
                System.err.println("java.util.logging.ErrorManager: " + code
                        + (message == null ? "" : ": " + message) + " (" + reason + ")");
            } catch (Throwable unprintable) {
                // Standard error may be gone.
            }
            recordWriteFailure(reason);
        }
    }
}
