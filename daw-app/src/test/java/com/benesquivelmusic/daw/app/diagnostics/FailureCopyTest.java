package com.benesquivelmusic.daw.app.diagnostics;

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
 */
class FailureCopyTest {

    private static final Instant NOW = Instant.parse("2026-11-12T14:40:00Z");
    private static final Instant EARLIER_TODAY = Instant.parse("2026-11-12T14:32:05Z");
    private static final Instant YESTERDAY = Instant.parse("2026-11-11T23:59:59Z");
    private static final String GATE_FOUR =
            "Internal error in the user interface: gate four. Check that your last change took effect.";

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

        assertThat(family).hasSize(15).containsExactlyInAnyOrderElementsOf(FailureCopy.KEYS);
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
    void aTimeIsHoursMinutesAndSecondsTodayAndCarriesTheDateOtherwise() {
        assertThat(copy.clock(EARLIER_TODAY)).isEqualTo("14:32:05");
        assertThat(copy.clock(YESTERDAY)).isEqualTo("2026-11-11 23:59:59");
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
