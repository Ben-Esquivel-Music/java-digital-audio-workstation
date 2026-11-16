package com.benesquivelmusic.daw.app.diagnostics;

import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;
import com.benesquivelmusic.daw.core.mixer.Mixer;

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
 * passed twice (once to choose, once to show). The one exception is story
 * 337's sustained-fault copy, whose ruled patterns format their count as
 * {@code {n,number,integer}} and so receive it as a {@link Long}, grouped at
 * {@link Locale#ROOT} (story 337, ruling E45).</p>
 *
 * <p>Story 337 adds the copy of the real-time error channel — one sentence
 * per audio category for a first occurrence ({@link #audioFirst}), the
 * sustained fault ({@link #audioSustained}), the halt ({@link #audioHalted},
 * {@link #audioHaltedFatal}) and the Restart Audio Engine action
 * ({@link #restartAudioEngineLabel()}) — and of a pulse item or bus
 * subscriber that keeps failing ({@link #isolatedConsumer}), all ruled
 * wording (story 337, rulings E45 and E64). Each names the device, the
 * plugin or the setting when a sample identifies one, never a bare exception
 * class, and takes its reason from {@link GuardedThrowables#reason}.</p>
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
    static final String ACTION_RESTART_AUDIO_ENGINE = "failure.action.restartAudioEngine";
    static final String AUDIO_CALLBACK = "failure.audio.callback";
    static final String AUDIO_RENDER = "failure.audio.render";
    static final String AUDIO_RENDER_NAMED = "failure.audio.render.named";
    static final String AUDIO_SINK = "failure.audio.sink";
    static final String AUDIO_PACING = "failure.audio.pacing";
    static final String AUDIO_INPUT = "failure.audio.input";
    static final String AUDIO_WORKER = "failure.audio.worker";
    static final String AUDIO_DSP = "failure.audio.dsp";
    static final String AUDIO_DSP_UNNAMED = "failure.audio.dsp.unnamed";
    static final String AUDIO_MIDI = "failure.audio.midi";
    static final String AUDIO_PLUGIN = "failure.audio.plugin";
    static final String AUDIO_PLUGIN_UNNAMED = "failure.audio.plugin.unnamed";
    static final String AUDIO_AUTOMATION = "failure.audio.automation";
    static final String AUDIO_AUTOMATION_UNNAMED = "failure.audio.automation.unnamed";
    static final String AUDIO_CONFIG = "failure.audio.config";
    static final String AUDIO_CONFIG_UNKNOWN = "failure.audio.config.unknown";
    static final String AUDIO_DEVICE = "failure.audio.device";
    static final String AUDIO_FAULT = "failure.audio.fault";
    static final String AUDIO_FAULT_DEVICE = "failure.audio.fault.device";
    static final String AUDIO_FAULT_PROCESSING = "failure.audio.fault.processing";
    static final String AUDIO_FAULT_PROCESSING_NAMED = "failure.audio.fault.processing.named";
    static final String AUDIO_FAULT_PLUGIN = "failure.audio.fault.plugin";
    static final String AUDIO_FAULT_WORKER = "failure.audio.fault.worker";
    static final String AUDIO_FAULT_DSP = "failure.audio.fault.dsp";
    static final String AUDIO_FAULT_DSP_UNNAMED = "failure.audio.fault.dsp.unnamed";
    static final String AUDIO_FAULT_MIDI = "failure.audio.fault.midi";
    static final String AUDIO_FAULT_PLUGIN_UNNAMED = "failure.audio.fault.plugin.unnamed";
    static final String AUDIO_HALTED = "failure.audio.halted";
    static final String AUDIO_HALTED_NAMED = "failure.audio.halted.named";
    static final String AUDIO_HALTED_PLAYBACK = "failure.audio.halted.playback";
    static final String AUDIO_HALTED_RECORDING = "failure.audio.halted.recording";
    static final String AUDIO_HALTED_NEXT = "failure.audio.halted.next";
    static final String AUDIO_HALTED_NEXT_NAMED = "failure.audio.halted.next.named";
    static final String UI_PULSE = "failure.ui.pulse";
    static final String BUS_SUBSCRIBER = "failure.bus.subscriber";

    /** The keys of this family, in the bundle's order: story 336's fifteen, then story 337's thirty-six. */
    static final List<String> KEYS = List.of(UI, UI_NAMED, TASK, TASK_UNNAMED, FATAL, FATAL_MEMORY, REPEATED,
            REPEATED_TAIL, ACTION_OPEN_LOG_FOLDER, MENU_OPEN_LOG_FOLDER, LOG_FOLDER_OPEN_FAILED,
            LOG_SINK_UNAVAILABLE, LOG_SINK_WRITE_FAILED, STARTUP, PLUGIN_DISPOSE,
            ACTION_RESTART_AUDIO_ENGINE, AUDIO_CALLBACK, AUDIO_RENDER, AUDIO_RENDER_NAMED, AUDIO_SINK, AUDIO_PACING,
            AUDIO_INPUT, AUDIO_WORKER, AUDIO_DSP, AUDIO_DSP_UNNAMED, AUDIO_MIDI, AUDIO_PLUGIN, AUDIO_PLUGIN_UNNAMED,
            AUDIO_AUTOMATION, AUDIO_AUTOMATION_UNNAMED, AUDIO_CONFIG, AUDIO_CONFIG_UNKNOWN, AUDIO_DEVICE,
            AUDIO_FAULT, AUDIO_FAULT_DEVICE, AUDIO_FAULT_PROCESSING, AUDIO_FAULT_PROCESSING_NAMED, AUDIO_FAULT_PLUGIN,
            AUDIO_FAULT_WORKER, AUDIO_FAULT_DSP, AUDIO_FAULT_DSP_UNNAMED, AUDIO_FAULT_MIDI, AUDIO_FAULT_PLUGIN_UNNAMED,
            AUDIO_HALTED, AUDIO_HALTED_NAMED, AUDIO_HALTED_PLAYBACK, AUDIO_HALTED_RECORDING, AUDIO_HALTED_NEXT,
            AUDIO_HALTED_NEXT_NAMED, UI_PULSE, BUS_SUBSCRIBER);

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
     *       Story 337's categories do not reach this table: the audio categories have their own copy
     *       ({@link #audioFirst}, {@link #audioSustained}, {@link #audioHalted}), and so do {@code ui.pulse}
     *       and {@code bus.subscriber} ({@link #isolatedConsumer}); the later stages (stories 338–340)
     *       give their categories copy of their own in the same way, rather than reusing
     *       "background task";</li>
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
     * The action a notice carries for the audio categories a stream restart
     * can cure (story 337, rulings E12 and E13).
     *
     * @return {@code failure.action.restartAudioEngine}
     */
    public String restartAudioEngineLabel() {
        return format(ACTION_RESTART_AUDIO_ENGINE);
    }

    /**
     * The WARNING of a real-time category's first occurrence (story 337,
     * rulings E44, E45 and E64): what failed, then the reason, then the
     * consequence, each true for its own category. A category whose ruled
     * sentence names a component — DSP, PLUGIN, AUTOMATION, and the named
     * variant of RENDER — takes it from {@code name}; with no name, each uses
     * the unnamed sentence of its own category ({@code failure.audio.render},
     * {@code failure.audio.dsp.unnamed}, {@code failure.audio.plugin.unnamed},
     * {@code failure.audio.automation.unnamed}), never another category's.
     * CONFIG uses {@code failure.audio.config} with the return-bus count and
     * the cap when {@code detail} carries a count, else
     * {@code failure.audio.config.unknown} with the cap alone. RENDER_HALTED,
     * which never has a first occurrence of its own, reads as the idle halt.
     *
     * @param category the category
     * @param name     the component the failure's sample names — a plugin or processor display name, or a
     *                 parameter's display name for AUTOMATION — if any
     * @param device   the open device's display name, or the backend's
     * @param reason   the newest sampled throwable's reason ({@link GuardedThrowables#reason})
     * @param detail   the category's detail: for CONFIG the return-bus count, or {@code 0} when no sample
     *                 carried one; otherwise unused
     * @return the sentence
     */
    public String audioFirst(AudioFailureCategory category, Optional<String> name, String device, String reason,
                             long detail) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        return switch (category) {
            case CALLBACK -> format(AUDIO_CALLBACK, device, reason);
            case RENDER -> namedOrUnnamed(AUDIO_RENDER_NAMED, AUDIO_RENDER, name, reason);
            case RENDER_HALTED -> audioHalted(name, reason, HaltContext.IDLE);
            case SINK -> format(AUDIO_SINK, device, reason);
            case PACING -> format(AUDIO_PACING, device, reason);
            case INPUT -> format(AUDIO_INPUT, device, reason);
            case WORKER -> format(AUDIO_WORKER, reason);
            case DSP -> namedOrUnnamed(AUDIO_DSP, AUDIO_DSP_UNNAMED, name, reason);
            case MIDI -> format(AUDIO_MIDI, reason);
            case PLUGIN -> namedOrUnnamed(AUDIO_PLUGIN, AUDIO_PLUGIN_UNNAMED, name, reason);
            case AUTOMATION -> namedOrUnnamed(AUDIO_AUTOMATION, AUDIO_AUTOMATION_UNNAMED, name, reason);
            case CONFIG -> detail > 0
                    ? format(AUDIO_CONFIG, Long.toString(detail), Integer.toString(Mixer.MAX_RETURN_BUSES))
                    : format(AUDIO_CONFIG_UNKNOWN, Integer.toString(Mixer.MAX_RETURN_BUSES));
            case DEVICE -> format(AUDIO_DEVICE, device, reason);
        };
    }

    /**
     * The ERROR fact of a sustained real-time fault (story 337, rulings E41,
     * E44, E45 and E64), a sentence true for its own category:
     * {@code failure.audio.fault} for CALLBACK;
     * {@code failure.audio.fault.device} for SINK, PACING, INPUT and DEVICE;
     * {@code failure.audio.fault.plugin} for a named PLUGIN and
     * {@code failure.audio.fault.plugin.unnamed} for an unnamed one;
     * {@code failure.audio.fault.worker} for WORKER, whatever the name;
     * {@code failure.audio.fault.dsp} for a named DSP and
     * {@code failure.audio.fault.dsp.unnamed} for an unnamed one;
     * {@code failure.audio.fault.midi} for MIDI, whatever the name; and for
     * RENDER {@code failure.audio.fault.processing.named} when named, else
     * {@code failure.audio.fault.processing}. RENDER_HALTED, AUTOMATION and
     * CONFIG, which are never sustained, take RENDER's sentence.
     *
     * @param category the category
     * @param name     the component the failure's sample names, if any
     * @param device   the open device's display name, or the backend's
     * @param count    the occurrences counted for its fingerprint so far
     * @param since    when its fingerprint was first seen
     * @param reason   the newest sampled throwable's reason
     * @return the sentence
     */
    public String audioSustained(AudioFailureCategory category, Optional<String> name, String device, long count,
                                 Instant since, String reason) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(since, "since must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Long total = count;
        String shownSince = clock(since);
        return switch (category) {
            case CALLBACK -> format(AUDIO_FAULT, total, shownSince, reason);
            case SINK, PACING, INPUT, DEVICE -> format(AUDIO_FAULT_DEVICE, total, device, shownSince, reason);
            case PLUGIN -> name.map(named -> format(AUDIO_FAULT_PLUGIN, named, total, shownSince, reason))
                    .orElseGet(() -> format(AUDIO_FAULT_PLUGIN_UNNAMED, total, shownSince, reason));
            case WORKER -> format(AUDIO_FAULT_WORKER, total, shownSince, reason);
            case DSP -> name.map(named -> format(AUDIO_FAULT_DSP, named, total, shownSince, reason))
                    .orElseGet(() -> format(AUDIO_FAULT_DSP_UNNAMED, total, shownSince, reason));
            case MIDI -> format(AUDIO_FAULT_MIDI, total, shownSince, reason);
            case RENDER, RENDER_HALTED, AUTOMATION, CONFIG ->
                    name.map(named -> format(AUDIO_FAULT_PROCESSING_NAMED, named, total, shownSince, reason))
                            .orElseGet(() -> format(AUDIO_FAULT_PROCESSING, total, shownSince, reason));
        };
    }

    /**
     * The ERROR notice of a render-drive halt (story 337, rulings E11, E45
     * and E101): the base sentence, then the context sentence when the halt's
     * Stop stopped playback or ended a started take, then the next step, joined
     * with single spaces. A name — the plugin the halting failure is anchored in —
     * picks the named base and next step.
     *
     * @param name    the plugin the cause is anchored in, if any
     * @param reason  the halting throwable's reason
     * @param context what the halt's Stop did, or that the halted stream
     *                was already replaced ({@link HaltContext#STREAM_REPLACED}),
     *                which gets no context sentence
     * @return the notice
     */
    public String audioHalted(Optional<String> name, String reason, HaltContext context) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(context, "context must not be null");
        String base = name.map(named -> format(AUDIO_HALTED_NAMED, named, reason))
                .orElseGet(() -> format(AUDIO_HALTED, reason));
        String next = name.map(named -> format(AUDIO_HALTED_NEXT_NAMED, named))
                .orElseGet(() -> format(AUDIO_HALTED_NEXT));
        return joined(base, haltContext(context), Optional.of(next));
    }

    /**
     * The FATAL notice of a halt a {@link VirtualMachineError} caused (story
     * 337, rulings E13 and E45): story 336's {@code failure.fatal}, or
     * {@code failure.fatal.memory} for an {@link OutOfMemoryError}, then the
     * same context sentence as {@link #audioHalted}; no restart step.
     *
     * @param fatal   the error that halted the drive
     * @param context what the halt's Stop did, or that the halted stream
     *                was already replaced ({@link HaltContext#STREAM_REPLACED}),
     *                which gets no context sentence
     * @return the notice
     */
    public String audioHaltedFatal(Throwable fatal, HaltContext context) {
        Objects.requireNonNull(fatal, "fatal must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Throwable unwrapped = GuardedThrowables.unwrapCarriers(fatal);
        String base = unwrapped instanceof OutOfMemoryError
                ? fatalMemory
                : format(FATAL, GuardedThrowables.reason(unwrapped));
        return joined(base, haltContext(context), Optional.empty());
    }

    /**
     * The WARNING of a pulse item or bus subscriber that keeps failing (story
     * 337, rulings E35 and E45): {@code failure.bus.subscriber} for
     * {@code bus.subscriber}, naming the event type; {@code failure.ui.pulse}
     * otherwise.
     *
     * @param category  {@code ui.pulse} or {@code bus.subscriber}
     * @param eventType the simple name of the event type the subscriber failed on, for the bus
     * @param unwrapped the throwable the carriers held
     * @return the sentence
     */
    public String isolatedConsumer(String category, Optional<String> eventType, Throwable unwrapped) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        String reason = GuardedThrowables.reason(unwrapped);
        if (FailureFunnel.BUS_SUBSCRIBER.equals(category)) {
            return format(BUS_SUBSCRIBER, eventType.orElse(""), reason);
        }
        return format(UI_PULSE, reason);
    }

    /** The named sentence with the name, else its category's own unnamed sentence (story 337, ruling E64). */
    private String namedOrUnnamed(String namedKey, String unnamedKey, Optional<String> name, String reason) {
        return name.map(named -> format(namedKey, named, reason)).orElseGet(() -> format(unnamedKey, reason));
    }

    private Optional<String> haltContext(HaltContext context) {
        if (context == HaltContext.PLAYBACK) {
            return Optional.of(format(AUDIO_HALTED_PLAYBACK));
        }
        if (context == HaltContext.RECORDING) {
            return Optional.of(format(AUDIO_HALTED_RECORDING));
        }
        return Optional.empty();
    }

    /** The sentences that apply, joined with single spaces (story 337, ruling E45). */
    private static String joined(String base, Optional<String> context, Optional<String> next) {
        StringBuilder text = new StringBuilder(base);
        context.ifPresent(sentence -> text.append(' ').append(sentence));
        next.ifPresent(sentence -> text.append(' ').append(sentence));
        return text.toString();
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
