package com.benesquivelmusic.daw.app.diagnostics;

import com.benesquivelmusic.daw.app.ui.NotificationLevel;
import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every user-facing string of the failure spine (story 336) is the studio
 * engineer's ruled wording, character for character — {@code failure.fatal}
 * included, which names the next step before the reason — read from the
 * bundle (javafx-application-design "## 14. Accessibility, internationalization,
 * and quality"); and the rules that choose and fill it: the selection table,
 * the de-slugged task name, the reason clause, the severity and the times.
 * The expected values are literals,
 * with the typographic quotes “ ” (U+201C, U+201D), the ellipsis … (U+2026)
 * and ▸ (U+25B8) as printed, so the assertions also prove each key's
 * {@code MessageFormat} (a single apostrophe would swallow the text after it).
 *
 * <p>Story 337 (rulings E45 and E64) adds thirty-six keys — the real-time error
 * channel's first-occurrence, sustained and halt copy, the Restart Audio
 * Engine action, and the pulse and bus isolated-consumer notices — and the
 * WARNING severity, shown as a WARNING toast and logged at WARNING (ruling
 * E43).</p>
 */
class FailureCopyTest {

    private static final Instant NOW = Instant.parse("2026-11-12T14:40:00Z");
    private static final Instant EARLIER_TODAY = Instant.parse("2026-11-12T14:32:05Z");
    private static final Instant YESTERDAY = Instant.parse("2026-11-11T23:59:59Z");
    private static final String GATE_FOUR =
            "Internal error in the user interface: gate four. Check that your last change took effect.";
    private static final String DEVICE = "USB Audio Interface";
    private static final String REASON = "the driver returned a short buffer";

    private final FailureCopy copy = FailureCopy.fromBundle(InstantSource.fixed(NOW), ZoneOffset.UTC);

    @Test
    void theBundlesFailureAndHelpFamiliesHoldExactlyTheRuledKeys() {
        ResourceBundle bundle = ResourceBundle.getBundle(FailureCopy.BUNDLE, Locale.ROOT);
        Set<String> family = new TreeSet<>();
        for (String key : Collections.list(bundle.getKeys())) {
            if (key.startsWith("failure.") || key.startsWith("menu.help.")) {
                family.add(key);
            }
        }

        assertThat(family).hasSize(52).containsExactlyInAnyOrderElementsOf(FailureCopy.KEYS);
        assertThat(FailureCopy.KEYS).allSatisfy(key -> assertThat(bundle.getString(key)).doesNotContain("'"));
    }

    @Test
    void theUserInterfaceCopyNamesTheReasonOrADeclaredAction() {
        assertThat(copy.base(FailureFact.Severity.ERROR, FailureFunnel.UI_HANDLER, Optional.empty(),
                FailureFunnel.FX_THREAD_NAME, new IllegalStateException("gate four"))).isEqualTo(GATE_FOUR);
        assertThat(copy.base(FailureFact.Severity.ERROR, FailureFunnel.UI_HANDLER, Optional.of("Bounce in Place"),
                FailureFunnel.FX_THREAD_NAME, new IllegalStateException("no tracks selected")))
                .isEqualTo("“Bounce in Place” stopped because of an internal error: no tracks selected."
                        + " Check whether it took effect.");
    }

    @Test
    void theBackgroundTaskCopyNamesTheTaskTheThreadStates() {
        assertThat(copy.base(FailureFact.Severity.ERROR, FailureFunnel.THREAD_UNCAUGHT, Optional.empty(),
                "daw-take-storage", new IllegalStateException("disk gone")))
                .isEqualTo("Internal error in the background task “take storage”: disk gone. Its work may be"
                        + " incomplete.");
        assertThat(copy.base(FailureFact.Severity.ERROR, FailureFunnel.THREAD_UNCAUGHT, Optional.empty(),
                "", new IllegalStateException("disk gone")))
                .isEqualTo("Internal error in a background task: disk gone. Its work may be incomplete.");
        assertThat(copy.base(FailureFact.Severity.ERROR, "audio.callback", Optional.of("Output stream"),
                "daw-audio-worker-3", new IllegalStateException("underrun")))
                .as("another category with a declared subject")
                .isEqualTo("Internal error in the background task “Output stream”: underrun. Its work may be"
                        + " incomplete.");
    }

    @Test
    void thePluginDisposalCopyNamesThePlugin() {
        assertThat(copy.base(FailureFact.Severity.ERROR, DeclaredSubject.PLUGIN_DISPOSE, Optional.of("Vintage Delay"),
                "cancelled-plugin-dispose", new IllegalStateException("licence check failed")))
                .isEqualTo("The plugin “Vintage Delay” failed while it was being closed: licence check failed."
                        + " It is no longer in use, but it may hold memory or processor time until you restart.");
    }

    @Test
    void theFatalCopyPutsTheNextStepBeforeTheReason() {
        assertThat(copy.base(FailureFact.Severity.FATAL, FailureFunnel.THREAD_UNCAUGHT, Optional.empty(),
                "daw-take-storage", new StackOverflowError()))
                .isEqualTo("Serious internal error: the application may not work correctly from now on. When you"
                        + " can, save a copy with File ▸ Save As… and restart. Reason: StackOverflowError.");
        assertThat(copy.base(FailureFact.Severity.FATAL, FailureFunnel.UI_HANDLER, Optional.empty(),
                FailureFunnel.FX_THREAD_NAME, new OutOfMemoryError("Java heap space")))
                .isEqualTo("The application ran out of memory. When you can, save a copy with File ▸ Save As… and"
                        + " restart.");
    }

    @Test
    void theRepeatAndTailCopyCarryTheCountsAndTheTimes() {
        assertThat(copy.repeated(GATE_FOUR, 11, EARLIER_TODAY))
                .isEqualTo(GATE_FOUR + " It has happened 11 times since 14:32:05.");
        assertThat(copy.tail(GATE_FOUR, 1, EARLIER_TODAY))
                .isEqualTo(GATE_FOUR + " It happened once more after that notice, the last time at 14:32:05.");
        assertThat(copy.tail(GATE_FOUR, 1234, YESTERDAY))
                .isEqualTo(GATE_FOUR + " It happened 1234 more times after that notice, the last time at"
                        + " 2026-11-11 23:59:59.");
    }

    @Test
    void theStartupSinkAndFolderCopyAndTheActionLabels() {
        Path folder = Path.of("logs").toAbsolutePath();
        assertThat(copy.startupSummary(2)).isEqualTo("2 internal errors occurred while the application was"
                + " starting, so some features may not work.");
        assertThat(copy.sinkUnavailable(folder, "Access is denied")).isEqualTo("The log file could not be opened"
                + " in " + folder + ": Access is denied. This session is not logged to a file.");
        assertThat(copy.sinkWriteFailed("There is not enough space on the disk")).isEqualTo("The log file could"
                + " not be written: There is not enough space on the disk. Lines from this session may be missing"
                + " from it.");
        assertThat(copy.logFolderOpenFailed("HeadlessException", folder))
                .isEqualTo("The log folder could not be opened: HeadlessException. Find it at " + folder);
        assertThat(copy.openLogFolderLabel()).isEqualTo("Open Log Folder");
        assertThat(new MessageFormat(ResourceBundle.getBundle(FailureCopy.BUNDLE, Locale.ROOT)
                .getString(FailureCopy.MENU_OPEN_LOG_FOLDER), Locale.ROOT).format(new Object[0]))
                .isEqualTo("Open Log Folder");
    }

    @Test
    void aThreadNameStatesItsTaskUnlessItIsGeneric() {
        assertThat(FailureCopy.taskName("daw-take-storage")).hasValue("take storage");
        assertThat(FailureCopy.taskName("daw-audio-worker-3")).hasValue("audio worker");
        assertThat(FailureCopy.taskName("insert-plugin-load")).hasValue("insert plugin load");
        assertThat(FailureCopy.taskName("daw-save_writer-")).hasValue("save writer");
        assertThat(FailureCopy.taskName("Thread-7")).isEmpty();
        assertThat(FailureCopy.taskName("pool-2-thread-1")).isEmpty();
        assertThat(FailureCopy.taskName("ForkJoinPool-1-worker-3")).isEmpty();
        assertThat(FailureCopy.taskName("ForkJoinPool.commonPool-worker-1")).isEmpty();
        assertThat(FailureCopy.taskName("")).isEmpty();
        assertThat(FailureCopy.taskName("daw-")).isEmpty();
    }

    @Test
    void theReasonClauseIsTheUnwrappedMessagesFirstLineWithoutItsFullStopCapped() {
        assertThat(GuardedThrowables.reason(GuardedThrowables.unwrapCarriers(
                new CompletionException(new IllegalStateException("disk gone"))))).isEqualTo("disk gone");
        assertThat(GuardedThrowables.reason(new IllegalStateException("line one\nline two"))).isEqualTo("line one");
        assertThat(GuardedThrowables.reason(new IllegalStateException("Disk full."))).isEqualTo("Disk full");
        assertThat(GuardedThrowables.reason(new IllegalStateException("Disk full.."))).isEqualTo("Disk full.");
        assertThat(GuardedThrowables.reason(new IllegalStateException())).isEqualTo("IllegalStateException");
        String reason = GuardedThrowables.reason(new IllegalStateException("x".repeat(200)));
        assertThat(reason).hasSize(120).endsWith("x…");
        assertThat(GuardedThrowables.reason(new UnreadableMessage())).as("a message that throws: the class name")
                .isEqualTo("UnreadableMessage");
    }

    @Test
    void onlyCarriersAreUnwrappedAndTheLaunchersWrapperOnlyOnTheLaunchThread() {
        IllegalStateException notACarrier = new IllegalStateException("x", new IOException("y"));
        assertThat(GuardedThrowables.reason(GuardedThrowables.unwrapCarriers(notACarrier))).isEqualTo("x");

        IllegalStateException startFailed = new IllegalStateException("start failed");
        RuntimeException wrapper = launcherWrapper("Exception in Application start method", startFailed);
        assertThat(GuardedThrowables.unwrapLaunchCarriers(wrapper)).isSameAs(startFailed);
        assertThat(GuardedThrowables.unwrapCarriers(wrapper)).as("not a carrier elsewhere").isSameAs(wrapper);

        RuntimeException plain = new RuntimeException("Exception in Application start method", startFailed);
        assertThat(GuardedThrowables.unwrapLaunchCarriers(plain)).as("not thrown by the launcher").isSameAs(plain);
    }

    @Test
    void severityFollowsTheRulingOnTheUnwrappedThrowable() {
        assertThat(severity(FailureFunnel.THREAD_UNCAUGHT, new OutOfMemoryError())).isEqualTo(FailureFact.Severity.FATAL);
        assertThat(severity(FailureFunnel.UI_HANDLER, new StackOverflowError())).isEqualTo(FailureFact.Severity.FATAL);
        assertThat(severity(FailureFunnel.THREAD_UNCAUGHT, new CompletionException(new InternalError("vm"))))
                .as("a carried VirtualMachineError").isEqualTo(FailureFact.Severity.FATAL);
        assertThat(severity(FailureFunnel.THREAD_UNCAUGHT,
                new ExceptionInInitializerError(new IllegalStateException("host init"))))
                .as("a host class is unusable").isEqualTo(FailureFact.Severity.FATAL);
        IllegalStateException pluginInit = new IllegalStateException("plugin init");
        pluginInit.setStackTrace(new StackTraceElement[] {
                new StackTraceElement(null, null, null, "com.example.Plugin", "dispose", "Plugin.java", 10)});
        assertThat(severity(FailureFunnel.THREAD_UNCAUGHT, new ExceptionInInitializerError(pluginInit)))
                .as("a plugin is broken, the application is not").isEqualTo(FailureFact.Severity.ERROR);
        assertThat(severity(FailureFunnel.THREAD_UNCAUGHT, new UnsatisfiedLinkError("no asioshim")))
                .isEqualTo(FailureFact.Severity.FATAL);
        assertThat(severity(FailureFunnel.THREAD_UNCAUGHT, new NoClassDefFoundError("com/example/Gone")))
                .isEqualTo(FailureFact.Severity.ERROR);
        assertThat(severity(FailureFunnel.UI_HANDLER, new AssertionError("x"))).isEqualTo(FailureFact.Severity.ERROR);
        assertThat(severity(FailureFunnel.UI_HANDLER, new IllegalStateException("x")))
                .isEqualTo(FailureFact.Severity.ERROR);
    }

    @Test
    void aLaunchFailureIsFatalUnlessTheApplicationRanAndItsStopThrew() {
        assertThat(severity(FailureFunnel.APP_LAUNCH, new IllegalArgumentException("sessionMem")))
                .isEqualTo(FailureFact.Severity.FATAL);
        assertThat(severity(FailureFunnel.APP_LAUNCH,
                launcherWrapper("Exception in Application start method", new IllegalStateException("x"))))
                .isEqualTo(FailureFact.Severity.FATAL);
        assertThat(severity(FailureFunnel.APP_LAUNCH,
                launcherWrapper(FailureFunnel.APPLICATION_STOP_WRAPPER, new IllegalStateException("x"))))
                .isEqualTo(FailureFact.Severity.ERROR);
        assertThat(severity(FailureFunnel.APP_LAUNCH,
                launcherWrapper(FailureFunnel.PRELOADER_STOP_WRAPPER, new IllegalStateException("x"))))
                .isEqualTo(FailureFact.Severity.ERROR);
    }

    @Test
    void theFirstOccurrenceCopyOfEachAudioCategoryNamesTheDeviceOrTheComponentAndTheReason() {
        assertThat(first(AudioFailureCategory.CALLBACK, Optional.empty())).isEqualTo("The audio callback for “"
                + DEVICE + "” failed, so a block of output was played as silence: " + REASON + ". Audio continues.");
        assertThat(first(AudioFailureCategory.RENDER, Optional.empty())).isEqualTo("Audio processing failed, so a"
                + " block was played as silence: " + REASON + ". Playback continues.");
        assertThat(first(AudioFailureCategory.RENDER, Optional.of("Tape Echo"))).isEqualTo("Audio processing failed"
                + " in “Tape Echo”, so a block was played as silence: " + REASON + ". Playback continues; if it"
                + " happens again, bypass “Tape Echo”.");
        assertThat(first(AudioFailureCategory.SINK, Optional.empty())).isEqualTo("The audio device “" + DEVICE
                + "” did not accept a block of output, so it was skipped: " + REASON + ". Audio continues.");
        assertThat(first(AudioFailureCategory.PACING, Optional.empty())).isEqualTo("The audio device “" + DEVICE
                + "” stopped reporting when it needs more audio, so playback is timed by the computer clock instead: "
                + REASON + ". Audio continues but may drift or drop out.");
        assertThat(first(AudioFailureCategory.INPUT, Optional.empty())).isEqualTo("Input from “" + DEVICE + "” was"
                + " lost for a block: " + REASON + ". If you are recording, the take has a gap there.");
        assertThat(first(AudioFailureCategory.WORKER, Optional.empty())).isEqualTo("The inserts of a track failed"
                + " during multi-core processing and were run again for that block: " + REASON + ". If it keeps"
                + " happening, check the inserts on your tracks.");
        assertThat(first(AudioFailureCategory.DSP, Optional.of("Velvet Noise Reverb"))).isEqualTo("Part of the sound"
                + " of “Velvet Noise Reverb” failed and was left out of a block: " + REASON + ". Audio continues.");
        assertThat(first(AudioFailureCategory.MIDI, Optional.empty())).isEqualTo("MIDI playback failed, so notes may"
                + " be missing or held: " + REASON + ". If it keeps happening, check the instruments on your MIDI"
                + " tracks.");
        assertThat(first(AudioFailureCategory.PLUGIN, Optional.of("Tape Echo"))).isEqualTo("The plugin “Tape Echo”"
                + " failed while processing, so its input was passed through unprocessed: " + REASON + ".");
        assertThat(first(AudioFailureCategory.AUTOMATION, Optional.of("Tape Echo: Feedback"))).isEqualTo("Automation"
                + " of “Tape Echo: Feedback” stopped because its plugin rejected a value: " + REASON + ". The"
                + " parameter keeps its last value; restarting the audio engine retries its automation.");
        assertThat(copy.audioFirst(AudioFailureCategory.CONFIG, Optional.empty(), DEVICE, "", 18))
                .as("the bus count, then the cap").isEqualTo("This mix has 18 return buses but only 16 can receive"
                        + " sends, so sends to the others are silent. Remove return buses you do not need.");
        assertThat(copy.audioFirst(AudioFailureCategory.CONFIG, Optional.empty(), DEVICE, "", 0))
                .as("no sample carried the count: the cap alone (ruling E64)").isEqualTo("This mix has more return"
                        + " buses than the 16 that can receive sends, so sends to the others are silent. Remove return"
                        + " buses you do not need.");
        assertThat(first(AudioFailureCategory.DEVICE, Optional.empty())).isEqualTo("A notification from the audio"
                + " device “" + DEVICE + "” could not be handled: " + REASON + ". If you changed its settings,"
                + " restart the audio engine to apply them.");
    }

    @Test
    void aComponentSentenceWithNoComponentUsesTheUnnamedSentenceOfItsCategory() {
        assertThat(first(AudioFailureCategory.DSP, Optional.empty())).as("a part left out, not silence (ruling E64)")
                .isEqualTo("Part of the sound of an effect failed and was left out of a block: " + REASON
                        + ". Audio continues.");
        assertThat(first(AudioFailureCategory.PLUGIN, Optional.empty())).as("passed through, not silence")
                .isEqualTo("A plugin failed while processing, so a block passed through it unprocessed or without"
                        + " its parameter changes: " + REASON + ". Audio continues.");
        assertThat(first(AudioFailureCategory.AUTOMATION, Optional.empty())).as("a value rejected, no audio lost")
                .isEqualTo("Automation of a plugin parameter stopped because its plugin rejected a value: " + REASON
                        + ". The parameter keeps its last value; restarting the audio engine retries its"
                        + " automation.");
    }

    @Test
    void theSustainedFaultCopyCarriesTheGroupedCountTheDeviceAndWhenItBegan() {
        assertThat(sustained(AudioFailureCategory.CALLBACK, Optional.empty(), 1234)).isEqualTo("Audio engine fault —"
                + " 1,234 callback errors since 14:32:05: " + REASON + ". Audio may stay silent or broken until you"
                + " restart the audio engine.");
        for (AudioFailureCategory device : new AudioFailureCategory[] {AudioFailureCategory.SINK,
                AudioFailureCategory.PACING, AudioFailureCategory.INPUT, AudioFailureCategory.DEVICE}) {
            assertThat(sustained(device, Optional.empty(), 66)).as("%s", device).isEqualTo("Audio device fault — 66"
                    + " errors from “" + DEVICE + "” since 14:32:05: " + REASON + ". Audio may stay silent or broken"
                    + " until you restart the audio engine.");
        }
        assertThat(sustained(AudioFailureCategory.RENDER, Optional.empty(), 180)).isEqualTo("Audio processing keeps"
                + " failing — 180 errors since 14:32:05: " + REASON + ". The failed blocks are played as silence;"
                + " check the inserts on your tracks.");
        assertThat(sustained(AudioFailureCategory.RENDER, Optional.of("Tape Echo"), 180)).isEqualTo("“Tape Echo”"
                + " keeps failing — 180 errors since 14:32:05: " + REASON + ". Its failed blocks are played as"
                + " silence; bypass or remove “Tape Echo”.");
        assertThat(sustained(AudioFailureCategory.PLUGIN, Optional.of("Tape Echo"), 2000)).isEqualTo("“Tape Echo”"
                + " keeps failing — 2,000 errors since 14:32:05: " + REASON + ". Its input is passed through"
                + " unprocessed; bypass or remove “Tape Echo”.");
    }

    @Test
    void eachRenderPipelineCategorySustainsWithASentenceOfItsOwn() {
        assertThat(sustained(AudioFailureCategory.WORKER, Optional.of("ignored"), 180))
                .as("re-run on one core, not silence (ruling E64); any name is ignored")
                .isEqualTo("Multi-core processing keeps failing — 180 errors since 14:32:05: " + REASON + ". The"
                        + " affected tracks are processed again on one core, which costs processor time; check the"
                        + " inserts on your tracks.");
        assertThat(sustained(AudioFailureCategory.DSP, Optional.of("Velvet Noise Reverb"), 180))
                .isEqualTo("Part of the sound of “Velvet Noise Reverb” keeps failing — 180 errors since 14:32:05: "
                        + REASON + ". That part is left out while it fails; bypass or remove “Velvet Noise Reverb”.");
        assertThat(sustained(AudioFailureCategory.DSP, Optional.empty(), 1234))
                .isEqualTo("Part of the sound of an effect keeps failing — 1,234 errors since 14:32:05: " + REASON
                        + ". That part is left out while it fails; check the effects in your mix.");
        assertThat(sustained(AudioFailureCategory.MIDI, Optional.of("ignored"), 66)).as("any name is ignored")
                .isEqualTo("MIDI playback keeps failing — 66 errors since 14:32:05: " + REASON + ". Notes may be"
                        + " missing or held; check the instruments on your MIDI tracks.");
        assertThat(sustained(AudioFailureCategory.PLUGIN, Optional.empty(), 2000))
                .isEqualTo("A plugin keeps failing — 2,000 errors since 14:32:05: " + REASON + ". Its blocks pass"
                        + " through unprocessed or without their parameter changes; check the plugins on your"
                        + " tracks.");
    }

    @Test
    void theHaltCopyJoinsTheBaseTheContextAndTheNextStepWithSingleSpaces() {
        assertThat(copy.audioHalted(Optional.empty(), REASON, HaltContext.PLAYBACK)).isEqualTo("The audio engine"
                + " stopped because audio processing failed for a full second without recovering: " + REASON + "."
                + " Playback was stopped. Restart the audio engine to continue.");
        assertThat(copy.audioHalted(Optional.of("Tape Echo"), REASON, HaltContext.RECORDING)).isEqualTo("The audio"
                + " engine stopped because “Tape Echo” failed for a full second without recovering: " + REASON + "."
                + " Recording was stopped; the take keeps what was recorded before the audio failed. Bypass or"
                + " remove “Tape Echo”, then restart the audio engine.");
        assertThat(copy.audioHalted(Optional.empty(), REASON, HaltContext.IDLE)).as("idle: no context sentence")
                .isEqualTo("The audio engine stopped because audio processing failed for a full second without"
                        + " recovering: " + REASON + ". Restart the audio engine to continue.");
        assertThat(copy.audioHaltedFatal(new StackOverflowError(), HaltContext.RECORDING)).isEqualTo("Serious internal"
                + " error: the application may not work correctly from now on. When you can, save a copy with File ▸"
                + " Save As… and restart. Reason: StackOverflowError. Recording was stopped; the take keeps what was"
                + " recorded before the audio failed.");
        assertThat(copy.audioHaltedFatal(new OutOfMemoryError("Java heap space"), HaltContext.PLAYBACK))
                .isEqualTo("The application ran out of memory. When you can, save a copy with File ▸ Save As… and"
                        + " restart. Playback was stopped.");
        assertThat(copy.audioHaltedFatal(new StackOverflowError(), HaltContext.IDLE))
                .as("idle: the FATAL copy carries no context sentence either (story 337, ruling E101)")
                .isEqualTo("Serious internal error: the application may not work correctly from now on. When you can,"
                        + " save a copy with File ▸ Save As… and restart. Reason: StackOverflowError.");
    }

    @Test
    void theIsolatedConsumerCopyNamesTheEventTypeAndTheReason() {
        assertThat(copy.isolatedConsumer(FailureFunnel.UI_PULSE, Optional.empty(), new IllegalStateException(REASON)))
                .isEqualTo("A display in the window keeps failing to update: " + REASON + ". The rest of the window"
                        + " still updates; if something looks frozen, check the log.");
        assertThat(copy.isolatedConsumer(FailureFunnel.BUS_SUBSCRIBER, Optional.of("Started"),
                new IllegalStateException(REASON))).isEqualTo("Part of the application keeps failing to handle"
                + " “Started” updates: " + REASON + ". Everything else still receives them; check the log for"
                + " details.");
    }

    @Test
    void theRestartActionAndTheWarningSeverity() {
        assertThat(copy.restartAudioEngineLabel()).isEqualTo("Restart Audio Engine");
        assertThat(FailureFact.Severity.WARNING.notificationLevel()).isEqualTo(NotificationLevel.WARNING);
        assertThat(FailureFact.Severity.WARNING.logLevel()).isEqualTo(Level.WARNING);
        assertThat(FailureFact.Severity.ERROR.notificationLevel()).isEqualTo(NotificationLevel.ERROR);
        assertThat(FailureFact.Severity.FATAL.notificationLevel()).isEqualTo(NotificationLevel.ERROR);
        assertThat(FailureFact.Severity.ERROR.logLevel()).isEqualTo(Level.SEVERE);
        assertThat(FailureFact.Severity.FATAL.logLevel()).isEqualTo(Level.SEVERE);
        assertThat(FailureFact.Severity.values()).as("story 336's ordinals unchanged")
                .containsExactly(FailureFact.Severity.FATAL, FailureFact.Severity.ERROR, FailureFact.Severity.WARNING);
    }

    @Test
    void aTimeIsHoursMinutesAndSecondsTodayAndCarriesTheDateOtherwise() {
        assertThat(copy.clock(EARLIER_TODAY)).isEqualTo("14:32:05");
        assertThat(copy.clock(YESTERDAY)).isEqualTo("2026-11-11 23:59:59");
    }

    /**
     * Ruling E128: "1 error" for one, "{N} errors" for 0 and for more, and
     * "from the audio device" with no device or backend name at all — for
     * every category that takes the device's sentence.
     */
    @Test
    void theDeviceFaultCountsOneErrorAndSaysTheAudioDeviceWithNoNameAtAll() {
        String tail = " since 14:32:05: " + REASON + ". Audio may stay silent or broken until you restart the audio"
                + " engine.";
        for (AudioFailureCategory device : new AudioFailureCategory[] {AudioFailureCategory.SINK,
                AudioFailureCategory.PACING, AudioFailureCategory.INPUT, AudioFailureCategory.DEVICE}) {
            assertThat(sustained(device, Optional.empty(), 1)).as("%s", device)
                    .isEqualTo("Audio device fault — 1 error from “" + DEVICE + "”" + tail);
            assertThat(sustained(device, Optional.empty(), 2)).as("%s", device)
                    .isEqualTo("Audio device fault — 2 errors from “" + DEVICE + "”" + tail);
            assertThat(sustained(device, Optional.empty(), 0)).as("%s", device)
                    .isEqualTo("Audio device fault — 0 errors from “" + DEVICE + "”" + tail);
            assertThat(copy.audioSustained(device, Optional.empty(), "", 1, EARLIER_TODAY, REASON)).as("%s", device)
                    .isEqualTo("Audio device fault — 1 error from the audio device" + tail);
            assertThat(copy.audioSustained(device, Optional.empty(), "", 3, EARLIER_TODAY, REASON)).as("%s", device)
                    .isEqualTo("Audio device fault — 3 errors from the audio device" + tail);
        }
    }

    private String first(AudioFailureCategory category, Optional<String> name) {
        return copy.audioFirst(category, name, DEVICE, REASON, 0);
    }

    private String sustained(AudioFailureCategory category, Optional<String> name, long count) {
        return copy.audioSustained(category, name, DEVICE, count, EARLIER_TODAY, REASON);
    }

    private static FailureFact.Severity severity(String category, Throwable failure) {
        Throwable unwrapped = FailureFunnel.APP_LAUNCH.equals(category)
                ? GuardedThrowables.unwrapLaunchCarriers(failure)
                : GuardedThrowables.unwrapCarriers(failure);
        return FailureFunnel.severityOf(category, failure, Fingerprint.of(category, Optional.empty(), unwrapped));
    }

    private static RuntimeException launcherWrapper(String message, Throwable cause) {
        RuntimeException wrapper = new RuntimeException(message, cause);
        wrapper.setStackTrace(new StackTraceElement[] {
                new StackTraceElement(null, "javafx.graphics", null, "com.sun.javafx.application.LauncherImpl",
                        "launchApplication1", "LauncherImpl.java", 900)});
        return wrapper;
    }

    /** An exception whose message cannot be read, as a closed plugin class loader's. */
    private static final class UnreadableMessage extends RuntimeException {
        @Override
        public String getMessage() {
            throw new NoClassDefFoundError("com/example/Gone");
        }
    }
}
