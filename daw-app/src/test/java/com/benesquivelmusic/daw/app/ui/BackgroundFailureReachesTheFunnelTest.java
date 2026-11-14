package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.plugin.PluginBrowser;
import com.benesquivelmusic.daw.app.ui.plugin.PluginSlotLoader;
import com.benesquivelmusic.daw.app.ui.plugin.PluginTestJars;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.JarOnlyExceptions;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.UndeclaredThrowFixture;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Pattern;

import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.GUARD;
import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.awaitOnFx;
import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Story 336, Goals — Tests bullet 2, and the Note from Story 334: a failure
 * that reaches the top of a background or virtual thread goes through the
 * default handler into the same funnel an FX failure does — a SEVERE record
 * with its whole stack in the log file and an ERROR toast — and so do the
 * plugin threads story 334 left to it. A plugin the insert rack discards
 * whose {@code dispose()} throws reaches the funnel once, with that very
 * exception, the plugin named (story 336, studio-engineer ruling E10); and what a log handler throws on
 * the rack's load worker ends that worker into the funnel while the rack
 * recovers.
 *
 * <p>Every case runs on the production default wiring:
 * {@link FailureSpineFixture#launch} is {@code DawLauncher.prepareLaunch}, and
 * {@link FailureSpineFixture#armOnFx()} builds the {@code FxDispatcher},
 * {@code NotificationBar} and {@code NotificationHistoryService} as
 * {@code MainController.initializeNotificationBar} and
 * {@code DawApplication.start} do and calls the same production units
 * ({@code installOnCurrentFxThread}, {@code armToasts}). The rack cases drive
 * the real {@code InsertEffectRack} code and so the production
 * {@code DeclaredSubject.guard}. The plugin's exception is one only its closed
 * JAR can format ({@link JarOnlyExceptions}): a plugin's class loader is closed
 * once it is disposed (dawg-annotations-reflection, "### Other reflection /
 * {@code MethodHandles} uses (NOT annotation-driven)"), so the assertions
 * compare it by identity and class name only.</p>
 *
 * <p>The toast of a background failure is shown on the FX application
 * thread, never on the thread that failed: the funnel posts it through the
 * {@code FxDispatcher} (story 336 Goals; javafx-application-design "## 11.
 * Threading"), and a history listener records the thread the bar's
 * {@code show} recorded the toast on. {@code FailureSpineWiringSourceTest}
 * pins that the post is the dispatcher's.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class BackgroundFailureReachesTheFunnelTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 24, 512);
    private static final String PLUGIN_DISPOSE_KEY =
            "plugin.dispose|" + UndeclaredThrowFixture.NAME + "|" + JarOnlyExceptions.EXCEPTION_CLASS_NAME;
    private static final String PLUGIN_DISPOSE_TOAST = "The plugin “" + UndeclaredThrowFixture.NAME + "” failed"
            + " while it was being closed: LicenceException. It is no longer in use, but it may hold memory or"
            + " processor time until you restart.";
    private static final String ALERT_TITLE = "Insert Effect Error";

    @Test
    void aVirtualThreadsUncaughtFailureIsLoggedAndShown(@TempDir Path settings) throws Exception {
        try (FailureSpineFixture spine = FailureSpineFixture.launch(settings)) {
            spine.armOnFx();

            Thread worker = Thread.ofVirtual().name("daw-take-storage").start(() -> {
                throw new IllegalStateException("disk gone");
            });
            assertThat(worker.join(GUARD)).isTrue();

            String expected = "Internal error in the background task “take storage”: disk gone. Its work may be"
                    + " incomplete.";
            awaitOnFx("the background failure's toast", () -> expected.equals(spine.bar().getMessage()));
            assertThat(spine.history().getEntries()).filteredOn(entry -> entry.message().equals(expected))
                    .singleElement().satisfies(entry -> {
                        assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
                        assertThat(entry.actionLabel()).contains("Open Log Folder");
                        assertThat(entry.action()).isPresent();
                    });

            String log = spine.logText();
            assertThat(Pattern.compile("(?m)^\\d{4}-\\S+ SEVERE  \\[daw-take-storage\\]"
                    + " com\\.benesquivelmusic\\.daw\\.app\\.diagnostics\\.FailureFunnel: Failure thread\\.uncaught"
                    + " \\(ERROR\\) on thread \"daw-take-storage\", occurrence 1, fingerprint thread\\.uncaught"
                    + "\\|java\\.lang\\.IllegalStateException\\|com\\.benesquivelmusic\\.daw\\.app\\.ui"
                    + "\\.BackgroundFailureReachesTheFunnelTest\\.lambda\\$\\S+\\R"
                    + "\tjava\\.lang\\.IllegalStateException: disk gone\\R"
                    + "\t\tat \\S*BackgroundFailureReachesTheFunnelTest\\.lambda\\$").matcher(log).find())
                    .as("the SEVERE record, its exception line and its stack, in %s:%n%s", spine.logFile(), log)
                    .isTrue();
        }
    }

    @Test
    void aBackgroundFailuresToastIsShownOnTheFxThreadNotOnTheThreadThatFailed(@TempDir Path settings)
            throws Exception {
        try (FailureSpineFixture spine = FailureSpineFixture.launch(settings)) {
            spine.armOnFx();
            Thread fxThread = onFx(Thread::currentThread);
            String expected = "Internal error in the background task “bounce writer”: the bounce folder went away."
                    + " Its work may be incomplete.";
            BlockingQueue<Thread> shownOn = new LinkedBlockingQueue<>();
            Consumer<NotificationEntry> recordTheShowingThread = entry -> {
                if (entry != null && expected.equals(entry.message())) {
                    shownOn.add(Thread.currentThread());
                }
            };
            spine.history().addListener(recordTheShowingThread);
            try {
                Thread worker = Thread.ofVirtual().name("daw-bounce-writer").start(() -> {
                    throw new IllegalStateException("the bounce folder went away");
                });
                assertThat(worker.join(GUARD)).isTrue();

                Thread shown = shownOn.poll(GUARD.toMillis(), TimeUnit.MILLISECONDS);
                assertThat(shown).as("the toast was shown and recorded").isNotNull();
                assertThat(shown).as("by NotificationBar.show on the FX application thread, never on the failing %s",
                        worker).isSameAs(fxThread).isNotSameAs(worker);
                assertThat(shownOn).as("once").isEmpty();
            } finally {
                spine.history().removeListener(recordTheShowingThread);
            }
        }
    }

    @Test
    void aRackDiscardWhoseDisposeThrowsReachesTheFunnelOnce(@TempDir Path directory) throws Exception {
        assertADiscardWhoseDisposeThrowsReachesTheFunnelOnce(directory, "cancelled-plugin-dispose", slot -> {
            InsertEffectRack rack = new InsertEffectRack(new MixerChannel("Voice"), 2, 48_000, 512, null, null);
            // The rack's view went away while the load ran: the late slot is cancelled and disposed.
            rack.dispose();
            rack.completeExternalInsert(0, slot);
        });
    }

    @Test
    void aRefusedRackLoadWhoseDisposeThrowsReachesTheFunnelOnce(@TempDir Path directory) throws Exception {
        assertADiscardWhoseDisposeThrowsReachesTheFunnelOnce(directory, "refused-plugin-dispose", slot -> {
            // The load lands while the window's edit gate says no: the slot is refused and disposed.
            InsertEffectRack rack = new InsertEffectRack(new MixerChannel("Voice"), 2, 48_000, 512, null, null,
                    () -> false);
            try {
                rack.completeExternalInsert(0, slot);
            } finally {
                rack.dispose();
            }
        });
    }

    @Test
    void aRackLoadWhoseLogHandlerThrowsEndsTheWorkerIntoTheFunnel(@TempDir Path directory) throws Exception {
        UndeclaredThrowFixture.disarm();
        ExternalPluginEntry entry = undeclaredThrowPlugin(directory);
        RuntimeException jarOnly = jarOnlyException(directory);
        List<Throwable> formattingFailures = new CopyOnWriteArrayList<>();
        Handler formatting = formattingThenRethrowing(formattingFailures);
        Logger rackLog = Logger.getLogger(InsertEffectRack.class.getName());
        List<String> alerts = new CopyOnWriteArrayList<>();
        ListChangeListener<Window> hideAlerts = hidingAlerts(alerts);
        MixerChannel channel = new MixerChannel("Voice");
        try (FailureSpineFixture spine = FailureSpineFixture.launch(directory.resolve("settings"))) {
            spine.armOnFx();
            rackLog.addHandler(formatting);
            UndeclaredThrowFixture.onActivate = jarOnly;
            InsertEffectRack rack = onFx(() -> {
                Window.getWindows().addListener(hideAlerts);
                return new InsertEffectRack(channel, 2, 48_000, 512, null, null);
            });
            try {
                runOnFx(() -> rack.applyPickerSelection(0, new PluginBrowser.ExternalSelection(entry)));
                awaitOnFx("the load worker's end in the funnel, and the rack usable again", () ->
                        !severeRecordsOn(spine, "insert-plugin-load").isEmpty()
                                && alerts.size() == 1 && !rack.isDisable());

                assertThat(formattingFailures).as("the handler's formatter could not read the plugin's exception")
                        .singleElement().isInstanceOf(NoClassDefFoundError.class);
                Throwable handlerThrew = formattingFailures.getFirst();
                assertThat(severeRecordsOn(spine, "insert-plugin-load")).as("one record, of what the handler threw")
                        .singleElement().satisfies(record -> {
                            assertThat(record.getMessage())
                                    .startsWith("Failure thread.uncaught (ERROR) on thread \"insert-plugin-load\"");
                            assertThat(record.getThrown() == handlerThrew).as("that very error").isTrue();
                        });
                awaitOnFx("the worker's toast", () -> spine.bar().getMessage()
                        .startsWith("Internal error in the background task “insert plugin load”: "));
                assertThat(onFx(rack::isDisable)).as("the rack is enabled again").isFalse();
                assertThat(alerts).as("and shows its own error, naming the exception by its class")
                        .containsExactly("Could not load plugin: LicenceException");
                assertThat(channel.getInsertSlots()).as("no insert").isEmpty();
                assertThat(UndeclaredThrowFixture.disposals).as("the instance the load made is disposed once")
                        .hasValue(1);
            } finally {
                runOnFx(() -> {
                    Window.getWindows().removeListener(hideAlerts);
                    rack.dispose();
                });
            }
        } finally {
            rackLog.removeHandler(formatting);
            UndeclaredThrowFixture.disarm();
        }
    }

    /**
     * Loads the plugin from its JAR, arms its {@code dispose()} with an
     * exception only its closed JAR can format, discards it through
     * {@code discard} on the FX thread, and asserts the funnel got that
     * exception once, on {@code disposeThread}, with the plugin named.
     */
    private static void assertADiscardWhoseDisposeThrowsReachesTheFunnelOnce(Path directory, String disposeThread,
                                                                              SlotDiscard discard)
            throws Exception {
        UndeclaredThrowFixture.disarm();
        ExternalPluginEntry entry = undeclaredThrowPlugin(directory);
        RuntimeException jarOnly = jarOnlyException(directory);
        InsertSlot slot = PluginSlotLoader.load(entry, FORMAT);
        UndeclaredThrowFixture.onDispose = jarOnly;
        try (FailureSpineFixture spine = FailureSpineFixture.launch(directory.resolve("settings"))) {
            spine.armOnFx();

            runOnFx(() -> discard.discard(slot));

            awaitOnFx("the dispose failure's toast", () -> PLUGIN_DISPOSE_TOAST.equals(spine.bar().getMessage()));
            assertThat(UndeclaredThrowFixture.disposals).as("disposed once").hasValue(1);
            assertThat(spine.funnel().occurrenceTotals()).as("reached the funnel once")
                    .containsEntry(PLUGIN_DISPOSE_KEY, 1L);
            List<LogRecord> reported = spine.funnelRecords().stream()
                    .filter(record -> record.getThrown() == jarOnly).toList();
            assertThat(reported).as("as that very exception, once").hasSize(1);
            assertThat(reported.getFirst().getLevel()).isEqualTo(Level.SEVERE);
            assertThat(reported.getFirst().getMessage())
                    .startsWith("Failure plugin.dispose (ERROR) on thread \"" + disposeThread + "\", occurrence 1,"
                            + " fingerprint " + PLUGIN_DISPOSE_KEY)
                    .endsWith(", subject \"" + UndeclaredThrowFixture.NAME + "\"");
            assertThat(spine.history().getEntries()).filteredOn(e -> e.message().equals(PLUGIN_DISPOSE_TOAST))
                    .as("one toast").hasSize(1);
            assertThat(spine.logText()).as("the record names the exception it could not format")
                    .contains("\t" + JarOnlyExceptions.EXCEPTION_CLASS_NAME
                            + ": <the message could not be read: java.lang.NoClassDefFoundError>");
        } finally {
            UndeclaredThrowFixture.disarm();
        }
    }

    /** How a test discards a loaded slot through the rack, on the FX thread. */
    @FunctionalInterface
    private interface SlotDiscard {
        void discard(InsertSlot slot);
    }

    private static ExternalPluginEntry undeclaredThrowPlugin(Path directory) throws Exception {
        Path jar = PluginTestJars.buildJar(directory, "undeclared-throw.jar",
                List.of(PluginTestJars.manifest(UndeclaredThrowFixture.class)), List.of(UndeclaredThrowFixture.class));
        return new ExternalPluginEntry(jar, UndeclaredThrowFixture.class.getName());
    }

    private static RuntimeException jarOnlyException(Path directory) throws Exception {
        RuntimeException jarOnly = JarOnlyExceptions.loadThenClose(directory.resolve("exceptions"));
        assertThat(catchThrowable(jarOnly::getMessage))
                .as("non-vacuity: only its closed JAR can give the exception's message")
                .isInstanceOf(NoClassDefFoundError.class);
        return jarOnly;
    }

    /** The funnel's SEVERE records about a failure on {@code threadName}. */
    private static List<LogRecord> severeRecordsOn(FailureSpineFixture spine, String threadName) {
        return spine.funnelRecords().stream()
                .filter(record -> record.getLevel() == Level.SEVERE)
                .filter(record -> record.getMessage().contains("on thread \"" + threadName + "\""))
                .toList();
    }

    /**
     * A handler that formats each WARNING record as the JDK's console handler
     * does, which runs what the record's exception runs to give its message,
     * and records and rethrows what that throws.
     */
    private static Handler formattingThenRethrowing(List<Throwable> thrown) {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() != Level.WARNING) {
                    return;
                }
                try {
                    new SimpleFormatter().format(record);
                } catch (RuntimeException | Error formatting) {
                    thrown.add(formatting);
                    throw formatting;
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }

    /**
     * Records the content of each {@value #ALERT_TITLE} alert, then hides it — on
     * a later FX turn, never inside the window list's add notification.
     */
    private static ListChangeListener<Window> hidingAlerts(List<String> alerts) {
        return change -> {
            while (change.next()) {
                for (Window window : change.getAddedSubList()) {
                    if (window instanceof Stage stage && ALERT_TITLE.equals(stage.getTitle())) {
                        Platform.runLater(() -> {
                            try {
                                if (stage.getScene().getRoot().lookup(".dialog-pane") instanceof DialogPane pane) {
                                    alerts.add(pane.getContentText());
                                } else {
                                    alerts.add("<no dialog pane>");
                                }
                            } finally {
                                stage.hide();
                            }
                        });
                    }
                }
            }
        };
    }
}
