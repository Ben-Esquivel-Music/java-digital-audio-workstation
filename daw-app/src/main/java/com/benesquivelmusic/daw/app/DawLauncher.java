package com.benesquivelmusic.daw.app;

import com.benesquivelmusic.daw.app.diagnostics.FailureFunnel;
import com.benesquivelmusic.daw.app.diagnostics.GuardedThrowables;
import com.benesquivelmusic.daw.app.diagnostics.LastResortLog;
import com.benesquivelmusic.daw.app.diagnostics.LogSink;
import com.benesquivelmusic.daw.app.logging.ExitHoldingLogManager;

import javafx.application.Application;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Entry point for the DAW application.
 *
 * <p>This separate launcher class exists because JavaFX requires the
 * {@link Application} subclass to <em>not</em> be the main class when
 * running from a classpath-based (non-module) configuration.</p>
 *
 * <p>On startup the launcher also materializes the bundled
 * {@code zgc.conf} JVM options file into the user's settings directory
 * (see {@link #installZgcConfig(Path, String)}). The shipped launcher
 * script references that file via {@code java @<settings>/zgc.conf ...}
 * to run the DAW under the real-time ZGC tuning profile documented in
 * {@code docs/java26-setup.md}.</p>
 *
 * <p>Before that, it installs the application's log file and failure
 * funnel ({@link #prepareLaunch(Path)}, story 336), so a failure is logged
 * from {@code main}'s first statement on.</p>
 */
public final class DawLauncher {

    /** Classpath resource containing the ZGC JVM options template. */
    static final String ZGC_CONF_RESOURCE = "/zgc.conf";

    /** File name written into the user's settings directory. */
    static final String ZGC_CONF_FILENAME = "zgc.conf";

    /** Default heap size used when {@code sessionMem} is not provided. */
    static final String DEFAULT_SESSION_MEM = "4G";

    /** The system property {@code java.util.logging.LogManager} reads its class from. */
    static final String LOG_MANAGER_PROPERTY = "java.util.logging.manager";

    /**
     * Conservative validation for {@code sessionMem}: a whole number
     * followed by an optional {@code k/K/m/M/g/G} unit suffix. Its syntax is
     * narrower than the size syntax {@code -Xms} / {@code -Xmx} read, which
     * also takes a {@code t/T} unit; yet a value it matches can still be one
     * the JVM refuses at start: {@code -Xmx} reads {@code 0} and a number too
     * large for it as invalid sizes, and {@code 4096}, which is bytes, as too
     * small a heap (story 336, studio-engineer ruling R992-10). It prevents
     * arbitrary whitespace / newlines / extra JVM args from being injected
     * into the options file via substitution.
     */
    static final Pattern SESSION_MEM_PATTERN = Pattern.compile("\\d+[kKmMgG]?");

    /**
     * The system property that sets the heap size {@code zgc.conf} is written
     * with, ahead of {@link #SESSION_MEM_VARIABLE} (story 336, studio-engineer
     * ruling R992-8).
     */
    static final String SESSION_MEM_PROPERTY = "daw.sessionMem";

    /**
     * The environment variable that sets the heap size {@code zgc.conf} is
     * written with when {@link #SESSION_MEM_PROPERTY} does not (story 336,
     * studio-engineer ruling R992-8).
     */
    static final String SESSION_MEM_VARIABLE = "DAW_SESSION_MEM";

    private DawLauncher() {
        // utility class
    }

    public static void main(String[] args) {
        prepareLaunch(userSettingsDirectory());
        Application.launch(DawApplication.class, args);
    }

    /**
     * Every step {@code main} takes before the toolkit starts, in the Failure
     * Surfacing book §6.4's order (story 336, Goals bullet 8): name the
     * application's log manager before anything touches
     * {@code java.util.logging}; install the log sink and write the session
     * banner; install the failure funnel as the default uncaught-exception
     * handler; then write {@code zgc.conf} for a later launch that passes it.
     * Neither a session-memory setting that is not a heap size nor a
     * {@code zgc.conf} that cannot be written stops the launch: each is a
     * WARNING in the log, which the packaged application has, rather than a
     * line on a standard error it does not show. A rejected setting is named
     * with its value, and {@code zgc.conf} is then not written, so a size an
     * earlier launch wrote is not replaced by the default (story 336,
     * studio-engineer ruling R992-8). Public only inside {@code daw.app}: the
     * module exports this package to no one.
     *
     * @param settingsDirectory the user settings directory ({@link #userSettingsDirectory()} in production)
     * @return the installed failure funnel
     */
    public static FailureFunnel prepareLaunch(Path settingsDirectory) {
        selectExitHoldingLogManager();
        FailureFunnel funnel = installDiagnostics(settingsDirectory, Thread.currentThread());
        switch (resolveSessionMem()) {
            case SessionMem.Rejected rejected ->
                    // The user's setting, not a fault of the system: one WARNING naming it, without a stack, and no
                    // zgc.conf written (story 336, studio-engineer ruling R992-8).
                    Logger.getLogger(DawLauncher.class.getName()).log(Level.WARNING, rejected.warning());
            case SessionMem.Accepted(String size, SessionMem.Source _) -> {
                try {
                    installZgcConfig(settingsDirectory, size);
                } catch (IOException e) {
                    // A fault of the system, with its stack; this session is unaffected, since the running JVM read
                    // its options when it started (story 336, studio-engineer ruling R992-8).
                    Logger.getLogger(DawLauncher.class.getName()).log(Level.WARNING,
                            "Could not write zgc.conf; a zgc.conf from an earlier launch is left as it was", e);
                }
            }
        }
        return funnel;
    }

    /**
     * Names the application's log manager when the launch path did not
     * (story 336): {@code java.util.logging.LogManager} reads
     * {@value #LOG_MANAGER_PROPERTY} once, when it is first initialized, and
     * nothing initializes it before {@code main}'s first statement on the
     * application's launch paths. An explicit choice on the command line is
     * respected. The class literal loads the class and does not initialize
     * it; this class itself declares no logger and touches no
     * {@code java.util.logging} state in a static initializer.
     */
    static void selectExitHoldingLogManager() {
        if (System.getProperty(LOG_MANAGER_PROPERTY) == null) {
            System.setProperty(LOG_MANAGER_PROPERTY, ExitHoldingLogManager.class.getName());
        }
    }

    /**
     * Installs the log sink under {@code settingsDirectory}, writes the session
     * banner and installs a failure funnel as the default uncaught-exception
     * handler — in that order, so the sink exists before any handler can fire.
     * An oversized last-resort file from an earlier session is moved aside
     * first, while this is still single-threaded. Idempotent:
     * an installed funnel is returned as it is.
     *
     * @param settingsDirectory the user settings directory
     * @param launchThread      the thread whose uncaught failures are {@code app.launch}
     * @return the installed funnel
     */
    static FailureFunnel installDiagnostics(Path settingsDirectory, Thread launchThread) {
        Optional<FailureFunnel> installed = FailureFunnel.installed();
        if (installed.isPresent()) {
            return installed.get();
        }
        Path logFolder = LogSink.folderUnder(settingsDirectory);
        LastResortLog.moveOversizedAtLaunch(logFolder);
        LogSink sink = LogSink.install(logFolder);
        sink.logSessionStart();
        FailureFunnel funnel = FailureFunnel.forLaunch(sink, launchThread);
        funnel.installAsDefaultHandler();
        return funnel;
    }

    /**
     * The funnel {@code DawApplication.start} arms: the one {@code main}
     * installed, or — when the application starts without {@code main} — one
     * installed now, with no launch thread, an oversized last-resort file
     * moved aside first as {@link #installDiagnostics} moves it. Then the log
     * manager was already chosen, and the banner says
     * whether the exit hold is active.
     *
     * @return the installed failure funnel
     */
    static FailureFunnel failureFunnel() {
        Optional<FailureFunnel> installed = FailureFunnel.installed();
        if (installed.isPresent()) {
            return installed.get();
        }
        Path logFolder = LogSink.folderUnder(userSettingsDirectory());
        LastResortLog.moveOversizedAtLaunch(logFolder);
        LogSink sink = LogSink.install(logFolder);
        sink.logSessionStart();
        FailureFunnel funnel = FailureFunnel.withoutLaunchThread(sink);
        funnel.installAsDefaultHandler();
        return funnel;
    }

    /**
     * Writes the bundled {@code zgc.conf} template into the given settings
     * directory, substituting {@code ${sessionMem}} with the supplied value.
     * The directory is created if it does not already exist.
     *
     * @param settingsDir directory where {@code zgc.conf} will be written;
     *                    must not be {@code null}
     * @param sessionMem  JVM memory size to substitute for
     *                    {@code ${sessionMem}} (for example {@code "4G"});
     *                    must not be {@code null} or blank
     * @return the absolute path to the written config file
     * @throws IOException if the file cannot be written
     */
    public static Path installZgcConfig(Path settingsDir, String sessionMem) throws IOException {
        Objects.requireNonNull(settingsDir, "settingsDir must not be null");
        Objects.requireNonNull(sessionMem, "sessionMem must not be null");
        String trimmed = sessionMem.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("sessionMem must not be blank");
        }
        if (!SESSION_MEM_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "sessionMem must match " + SESSION_MEM_PATTERN.pattern()
                            + " (e.g. \"4G\"): " + sessionMem);
        }

        Files.createDirectories(settingsDir);
        Path target = settingsDir.resolve(ZGC_CONF_FILENAME).toAbsolutePath();

        String template = readResource(ZGC_CONF_RESOURCE);
        String rendered = template.replace("${sessionMem}", trimmed);

        Path tmp = Files.createTempFile(settingsDir, "zgc", ".conf.tmp");
        Files.writeString(tmp, rendered, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Some filesystems (notably certain Windows setups) don't
            // support atomic rename. Fall back to a plain replace; the
            // temp-file write still avoids truncation on writer crash.
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /**
     * Resolves the user's settings directory for the DAW.
     *
     * <p>Honors {@code $XDG_CONFIG_HOME} on Linux, defaults to
     * {@code %APPDATA%} on Windows, and
     * {@code ~/Library/Application Support} on macOS. Falls back to
     * {@code ~/.config} on unknown platforms.</p>
     */
    static Path userSettingsDirectory() {
        String appName = "java-daw";
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", ".");

        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path base = (appData != null && !appData.isBlank())
                    ? Path.of(appData)
                    : Path.of(home, "AppData", "Roaming");
            return base.resolve(appName);
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return Path.of(home, "Library", "Application Support", appName);
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = (xdg != null && !xdg.isBlank()) ? Path.of(xdg) : Path.of(home, ".config");
        return base.resolve(appName);
    }

    /**
     * The heap size {@code zgc.conf} is written with, which pins {@code -Xms}
     * and {@code -Xmx}, read from this JVM's system property
     * {@value #SESSION_MEM_PROPERTY} and environment variable
     * {@value #SESSION_MEM_VARIABLE} ({@link #resolveSessionMem(Optional, Optional)}).
     *
     * @return the size, or the rejection of the value the setting holds
     */
    static SessionMem resolveSessionMem() {
        return resolveSessionMem(Optional.ofNullable(System.getProperty(SESSION_MEM_PROPERTY)),
                Optional.ofNullable(System.getenv(SESSION_MEM_VARIABLE)));
    }

    /**
     * The heap size {@code zgc.conf} is written with: the system property
     * {@value #SESSION_MEM_PROPERTY}, else the environment variable
     * {@value #SESSION_MEM_VARIABLE}, else {@link #DEFAULT_SESSION_MEM}; a
     * blank value counts as unset. The chosen value is trimmed and must match
     * {@link #SESSION_MEM_PATTERN} whole. Otherwise it is rejected, the
     * rejection naming its source and keeping its raw value, and neither the
     * next source nor the default takes its place: the user chose that source
     * (story 336, studio-engineer ruling R992-8).
     *
     * @param property the system property's raw value, if it is set
     * @param variable the environment variable's raw value, if it is set
     * @return the trimmed size and its source, or the rejection
     */
    static SessionMem resolveSessionMem(Optional<String> property, Optional<String> variable) {
        Objects.requireNonNull(property, "property must not be null");
        Objects.requireNonNull(variable, "variable must not be null");
        Optional<String> fromProperty = property.filter(value -> !value.isBlank());
        if (fromProperty.isPresent()) {
            return checked(fromProperty.get(), SessionMem.Source.SYSTEM_PROPERTY);
        }
        Optional<String> fromVariable = variable.filter(value -> !value.isBlank());
        if (fromVariable.isPresent()) {
            return checked(fromVariable.get(), SessionMem.Source.ENVIRONMENT_VARIABLE);
        }
        return new SessionMem.Accepted(DEFAULT_SESSION_MEM, SessionMem.Source.DEFAULT);
    }

    private static SessionMem checked(String value, SessionMem.Source source) {
        String trimmed = value.trim();
        if (SESSION_MEM_PATTERN.matcher(trimmed).matches()) {
            return new SessionMem.Accepted(trimmed, source);
        }
        return new SessionMem.Rejected(source, value);
    }

    /**
     * What {@link DawLauncher#resolveSessionMem} gives: a heap size, or a
     * value the setting holds that is not one. The rejection is a value, not
     * an exception (story 336, studio-engineer ruling R992-8).
     */
    sealed interface SessionMem {

        /** Where a value came from. */
        enum Source {
            /** The system property {@value DawLauncher#SESSION_MEM_PROPERTY}. */
            SYSTEM_PROPERTY("system property " + SESSION_MEM_PROPERTY),
            /** The environment variable {@value DawLauncher#SESSION_MEM_VARIABLE}. */
            ENVIRONMENT_VARIABLE("environment variable " + SESSION_MEM_VARIABLE),
            /** Neither: {@link DawLauncher#DEFAULT_SESSION_MEM}. */
            DEFAULT("default");

            private final String setting;

            Source(String setting) {
                this.setting = setting;
            }

            /** The words that name it in a WARNING, such as {@code system property daw.sessionMem}. */
            String setting() {
                return setting;
            }
        }

        /**
         * A heap size {@link DawLauncher#SESSION_MEM_PATTERN} matches.
         *
         * @param size   the size, trimmed
         * @param source where it came from
         */
        record Accepted(String size, Source source) implements SessionMem {

            public Accepted {
                Objects.requireNonNull(size, "size must not be null");
                Objects.requireNonNull(source, "source must not be null");
            }
        }

        /**
         * A value the user set that is not a heap size: the user's to fix, so
         * a WARNING without a stack, and no {@code zgc.conf} written.
         *
         * @param source the setting that holds it; never {@link Source#DEFAULT}
         * @param value  the value as it was set, untrimmed
         */
        record Rejected(Source source, String value) implements SessionMem {

            public Rejected {
                Objects.requireNonNull(source, "source must not be null");
                Objects.requireNonNull(value, "value must not be null");
                if (source == Source.DEFAULT) {
                    throw new IllegalArgumentException("the default session memory is never rejected");
                }
            }

            /**
             * The WARNING, on one line: the value is quoted with its line
             * breaks written as their escapes ({@link GuardedThrowables#oneLine}),
             * and every other character as it is.
             *
             * @return the message
             */
            String warning() {
                return "The " + source.setting() + " holds \"" + GuardedThrowables.oneLine(value)
                        + "\", which is not a heap size this setting accepts: use a whole number, optionally"
                        + " followed by k, m or g in either case, such as 16G. zgc.conf was not written; a zgc.conf"
                        + " from an earlier launch is left as it was.";
            }
        }
    }

    private static String readResource(String resource) throws IOException {
        try (InputStream in = DawLauncher.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Missing classpath resource: " + resource);
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                return sb.toString();
            }
        }
    }
}
