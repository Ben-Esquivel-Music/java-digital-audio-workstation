package com.benesquivelmusic.daw.app.diagnostics;

import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.regex.Pattern;

/**
 * Every user-facing string of the failure spine (story 336), read from the
 * application's bundle {@code com.benesquivelmusic.daw.app.i18n.Messages}
 * (javafx-application-design "## 14. Accessibility, internationalization, and
 * quality": user-facing strings come from a {@code ResourceBundle}). The
 * wording is the studio engineer's, as ruled for story 336.
 *
 * <p>Every pattern is resolved at {@link Locale#ROOT} <em>once, at
 * construction</em>: under an {@link OutOfMemoryError} a bundle lookup can
 * itself fail, so the funnel never looks a key up while it
 * handles a failure. Each is formatted through {@link MessageFormat} at
 * {@link Locale#ROOT}, as {@code PluginText.text} does; a count is passed as
 * a string without grouping, and a count that chooses a plural form is
 * passed twice (once to choose, once to show).</p>
 */
public final class FailureCopy {

    /** The bundle every string is read from. */
    static final String BUNDLE = "com.benesquivelmusic.daw.app.i18n.Messages";

    static final String UI = "failure.ui";
    static final String UI_NAMED = "failure.ui.named";
    static final String TASK = "failure.task";
    static final String TASK_UNNAMED = "failure.task.unnamed";
    static final String FATAL = "failure.fatal";
    static final String FATAL_MEMORY = "failure.fatal.memory";
    static final String REPEATED = "failure.repeated";
    static final String REPEATED_TAIL = "failure.repeated.tail";
    static final String ACTION_OPEN_LOG_FOLDER = "failure.action.openLogFolder";
    static final String MENU_OPEN_LOG_FOLDER = "menu.help.openLogFolder";
    static final String LOG_FOLDER_OPEN_FAILED = "failure.logFolder.openFailed";
    static final String LOG_SINK_UNAVAILABLE = "failure.logSink.unavailable";
    static final String LOG_SINK_WRITE_FAILED = "failure.logSink.writeFailed";
    static final String STARTUP = "failure.startup";
    static final String PLUGIN_DISPOSE = "failure.plugin.dispose";

    /** The keys of this family, in the bundle's order. */
    static final List<String> KEYS = List.of(UI, UI_NAMED, TASK, TASK_UNNAMED, FATAL, FATAL_MEMORY, REPEATED,
            REPEATED_TAIL, ACTION_OPEN_LOG_FOLDER, MENU_OPEN_LOG_FOLDER, LOG_FOLDER_OPEN_FAILED,
            LOG_SINK_UNAVAILABLE, LOG_SINK_WRITE_FAILED, STARTUP, PLUGIN_DISPOSE);

    /** A JDK thread name that names no purpose, so no task is named after it. */
    private static final Pattern GENERIC_THREAD_NAME =
            Pattern.compile("Thread-\\d+|pool-\\d+-thread-\\d+|ForkJoinPool.*");

    private static final DateTimeFormatter TODAY = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter OTHER_DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private final Map<String, String> patterns;
    private final String fatalMemory;
    private final InstantSource wallClock;
    private final ZoneId zone;

    private FailureCopy(Map<String, String> patterns, InstantSource wallClock, ZoneId zone) {
        this.patterns = Map.copyOf(patterns);
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock must not be null");
        this.zone = Objects.requireNonNull(zone, "zone must not be null");
        this.fatalMemory = format(FATAL_MEMORY);
    }

    /**
     * The copy, every pattern read from the application's bundle now. Never
     * throws: a key the bundle lacks reads as the key itself, as
     * {@code PluginText.text} does.
     *
     * @param wallClock the clock "today" is judged by ({@link #clock})
     * @param zone      the zone times are shown in
     * @return the copy
     */
    public static FailureCopy fromBundle(InstantSource wallClock, ZoneId zone) {
        Map<String, String> patterns = new HashMap<>();
        ResourceBundle bundle;
        try {
            bundle = ResourceBundle.getBundle(BUNDLE, Locale.ROOT);
        } catch (MissingResourceException missing) {
            bundle = null;
        }
        for (String key : KEYS) {
            String pattern = key;
            if (bundle != null) {
                try {
                    pattern = bundle.getString(key);
                } catch (MissingResourceException missing) {
                    pattern = key;
                }
            }
            patterns.put(key, pattern);
        }
        return new FailureCopy(patterns, wallClock, zone);
    }

    /**
     * The base sentence of a failure. The first matching row wins:
     * <ol>
     *   <li>FATAL and an {@link OutOfMemoryError}: {@code failure.fatal.memory}, no reason;</li>
     *   <li>FATAL: {@code failure.fatal};</li>
     *   <li>{@code plugin.dispose} with its plugin declared: {@code failure.plugin.dispose};</li>
     *   <li>{@code ui.handler} with a declared subject: {@code failure.ui.named} (story 339 declares it);</li>
     *   <li>{@code ui.handler}: {@code failure.ui};</li>
     *   <li>any other category with a declared subject: {@code failure.task}, the subject as the task.
     *       The later stages (stories 337–340) give their own categories copy of their own when they
     *       add them, rather than reusing "background task";</li>
     *   <li>a thread whose name states its purpose ({@link #taskName}): {@code failure.task};</li>
     *   <li>otherwise {@code failure.task.unnamed}.</li>
     * </ol>
     *
     * @param severity   the failure's severity
     * @param category   its category slug
     * @param subject    the subject its site declared, if any
     * @param threadName the failing thread's raw name
     * @param unwrapped  the throwable the carriers held
     * @return the base sentence
     */
    public String base(FailureFact.Severity severity, String category, Optional<String> subject,
                       String threadName, Throwable unwrapped) {
        if (severity == FailureFact.Severity.FATAL) {
            if (unwrapped instanceof OutOfMemoryError) {
                return fatalMemory;
            }
            return format(FATAL, GuardedThrowables.reason(unwrapped));
        }
        String reason = GuardedThrowables.reason(unwrapped);
        if (DeclaredSubject.PLUGIN_DISPOSE.equals(category) && subject.isPresent()) {
            return format(PLUGIN_DISPOSE, subject.get(), reason);
        }
        if (FailureFunnel.UI_HANDLER.equals(category)) {
            return subject.map(named -> format(UI_NAMED, named, reason)).orElseGet(() -> format(UI, reason));
        }
        if (subject.isPresent()) {
            return format(TASK, subject.get(), reason);
        }
        return taskName(threadName).map(task -> format(TASK, task, reason))
                .orElseGet(() -> format(TASK_UNNAMED, reason));
    }

    /**
     * A re-show after the window: the base sentence with the total
     * since the fingerprint was first seen and when that was.
     *
     * @param base      the base sentence
     * @param total     the occurrences so far, at least 2
     * @param firstSeen when the fingerprint was first seen
     * @return {@code failure.repeated}
     */
    public String repeated(String base, long total, Instant firstSeen) {
        return format(REPEATED, base, Long.toString(total), clock(firstSeen));
    }

    /**
     * The history-only tail entry: how many more times the
     * failure happened after its last notice, and when it last did.
     *
     * @param base       the base sentence of the last notice
     * @param suppressed the occurrences after that notice, at least 1
     * @param last       when the last of them happened
     * @return {@code failure.repeated.tail}
     */
    public String tail(String base, long suppressed, Instant last) {
        return format(REPEATED_TAIL, base, suppressed, Long.toString(suppressed), clock(last));
    }

    /**
     * The one toast for several failures before the toasts were armed.
     *
     * @param count how many, at least 2
     * @return {@code failure.startup}
     */
    public String startupSummary(int count) {
        return format(STARTUP, Integer.toString(count));
    }

    /**
     * The WARNING when the log file could not be opened at launch.
     *
     * @param folder the log folder
     * @param reason why
     * @return {@code failure.logSink.unavailable}
     */
    public String sinkUnavailable(Path folder, String reason) {
        return format(LOG_SINK_UNAVAILABLE, folder.toAbsolutePath().toString(), reason);
    }

    /**
     * The WARNING when the log file could not be written.
     *
     * @param reason why
     * @return {@code failure.logSink.writeFailed}
     */
    public String sinkWriteFailed(String reason) {
        return format(LOG_SINK_WRITE_FAILED, reason);
    }

    /**
     * The ERROR when the log folder could not be opened.
     *
     * @param reason why
     * @param folder the log folder, which ends the sentence
     * @return {@code failure.logFolder.openFailed}
     */
    public String logFolderOpenFailed(String reason, Path folder) {
        return format(LOG_FOLDER_OPEN_FAILED, reason, folder.toAbsolutePath().toString());
    }

    /**
     * The action every funnel toast carries.
     *
     * @return {@code failure.action.openLogFolder}
     */
    public String openLogFolderLabel() {
        return format(ACTION_OPEN_LOG_FOLDER);
    }

    /**
     * A background thread's name as the task it states:
     * a leading {@code daw-} and a trailing {@code -<digits>} or {@code -}
     * stripped, {@code -} and {@code _} turned into spaces, trimmed. A
     * generic JDK name ({@code Thread-<n>}, {@code pool-<n>-thread-<n>},
     * {@code ForkJoinPool…}) or the empty name a virtual thread has by
     * default names no task.
     *
     * @param threadName the raw thread name
     * @return the task's name, or empty when the name states none
     */
    static Optional<String> taskName(String threadName) {
        if (threadName == null || threadName.isEmpty() || GENERIC_THREAD_NAME.matcher(threadName).matches()) {
            return Optional.empty();
        }
        String name = threadName;
        if (name.startsWith("daw-")) {
            name = name.substring("daw-".length());
        }
        name = name.replaceFirst("-\\d+$", "");
        if (name.endsWith("-")) {
            name = name.substring(0, name.length() - 1);
        }
        name = name.replace('-', ' ').replace('_', ' ').strip();
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    /**
     * A time in copy: {@code HH:mm:ss} when it is today in the
     * shown zone, else {@code yyyy-MM-dd HH:mm:ss}; at {@link Locale#ROOT}.
     *
     * @param at the instant
     * @return the shown time
     */
    String clock(Instant at) {
        ZonedDateTime shown = at.atZone(zone);
        LocalDate today = wallClock.instant().atZone(zone).toLocalDate();
        return shown.toLocalDate().equals(today) ? TODAY.format(shown) : OTHER_DAY.format(shown);
    }

    private String format(String key, Object... arguments) {
        String pattern = patterns.getOrDefault(key, key);
        try {
            return new MessageFormat(pattern, Locale.ROOT).format(arguments);
        } catch (IllegalArgumentException malformed) {
            return pattern;
        }
    }
}
