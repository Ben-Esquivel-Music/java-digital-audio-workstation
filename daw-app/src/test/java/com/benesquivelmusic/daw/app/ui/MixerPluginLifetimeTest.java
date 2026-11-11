package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.plugin.PluginBrowser;
import com.benesquivelmusic.daw.app.ui.plugin.PluginTestJars;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.UndeclaredThrowFixture;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
import com.benesquivelmusic.daw.core.plugin.PluginLoadException;
import com.benesquivelmusic.daw.core.project.DawProject;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class MixerPluginLifetimeTest {
    @Test
    void replacingMixerRejectsLateRackLoadAndDisposesItWithoutOpeningEditor() throws Exception {
        var oldProject = new DawProject("Old", AudioFormat.CD_QUALITY);
        var track = oldProject.createAudioTrack("Voice");
        var channel = oldProject.getMixerChannelForTrack(track);
        var lateSlot = InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, 2, 44_100);
        var disposed = new CountDownLatch(1);
        lateSlot.setDisposal(disposed::countDown);
        var opened = new AtomicInteger();
        var preferences = Preferences.userRoot().node("mixerLifetimeTest_" + System.nanoTime());
        try {
            runOnFxThread(() -> {
                var oldView = new MixerView(oldProject, null, null);
                oldView.setOnOpenInsertEditor((_, _) -> opened.incrementAndGet());
                var scene = new Scene(new StackPane(oldView), 1000, 600);
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                var rack = nodes(oldView).filter(InsertEffectRack.class::isInstance)
                        .map(InsertEffectRack.class::cast).filter(value -> value.getChannel() == channel)
                        .findFirst().orElseThrow();
                var host = (ViewNavigationController.Host) Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[]{ViewNavigationController.Host.class},
                        (_, method, _) -> {
                            if (method.getName().equals("projectActionsAllowed")) {
                                return true; // reading the edit gate is not a host action
                            }
                            throw new AssertionError("Replacement must not invoke unrelated host actions");
                        });
                var navigation = new ViewNavigationController(new BorderPane(), new Label(),
                        new ToolbarStateStore(preferences), new Button(), DawView.ARRANGEMENT,
                        EditTool.POINTER, true, GridResolution.SIXTEENTH, host, null);
                navigation.setMixerView(oldView);
                navigation.setMixerView(new MixerView(new DawProject("New", AudioFormat.CD_QUALITY), null, null));
                rack.completeExternalInsert(0, lateSlot);
                assertThat(channel.getInsertSlots()).isEmpty();
                assertThat(opened).hasValue(0);
                navigation.dispose();
                return null;
            });
            assertThat(disposed.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            preferences.removeNode();
        }
    }

    /**
     * Story 334 (PR #990 review, rulings R-C3.2, R-C3.4 and R-C3.5): a plugin the browser picked
     * whose {@code activate()} throws a checked exception it does not declare, with no message, is
     * disposed once, adds no insert, and leaves the rack enabled, with one error alert naming the
     * exception and one WARNING record; the engineer can pick again, and a message-less unchecked
     * failure is named the same way.
     */
    @Test
    void aPluginWhoseActivationThrowsAnythingLeavesTheRackUsableWithOneErrorEachTime(@TempDir Path directory)
            throws Exception {
        Path jar = PluginTestJars.buildJar(directory, "undeclared-throw.jar",
                List.of(PluginTestJars.manifest(UndeclaredThrowFixture.class)), List.of(UndeclaredThrowFixture.class));
        ExternalPluginEntry entry = new ExternalPluginEntry(jar, UndeclaredThrowFixture.class.getName());
        MixerChannel channel = new MixerChannel("Voice");
        List<String> alerts = new CopyOnWriteArrayList<>();
        ListChangeListener<Window> recordAlerts = recordingAlerts(alerts);
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = capturingWarnings(warnings);
        Logger rackLog = Logger.getLogger(InsertEffectRack.class.getName());
        rackLog.addHandler(capture);
        UndeclaredThrowFixture.disarm();
        UndeclaredThrowFixture.onActivate = new IOException();
        InsertEffectRack rack = runOnFxThread(() -> {
            Window.getWindows().addListener(recordAlerts);
            return new InsertEffectRack(channel, 2, 48_000, 512, null, null);
        });
        try {
            runOnFxThread(() -> {
                rack.applyPickerSelection(0, new PluginBrowser.ExternalSelection(entry));
                return null;
            });
            awaitAlerts(rack, alerts, 1);

            assertThat(alerts).as("one error, naming the exception's class: it has no message")
                    .containsExactly("Could not load plugin: IOException");
            assertThat(runOnFxThread(rack::isDisable)).as("the rack is enabled again").isFalse();
            assertThat(channel.getInsertSlots()).as("no insert").isEmpty();
            assertThat(UndeclaredThrowFixture.disposals).as("the instance it made is disposed once").hasValue(1);
            assertThat(warnings).as("the rack logs the failure once").singleElement().satisfies(record -> {
                assertThat(record.getMessage()).isEqualTo("Could not load plugin " + UndeclaredThrowFixture.class.getName());
                assertThat(record.getThrown()).isInstanceOf(IllegalStateException.class)
                        .hasCauseInstanceOf(IOException.class);
            });

            UndeclaredThrowFixture.onActivate = new IllegalStateException();
            runOnFxThread(() -> {
                rack.applyPickerSelection(0, new PluginBrowser.ExternalSelection(entry));
                return null;
            });
            awaitAlerts(rack, alerts, 2);

            assertThat(alerts).as("the pick ran again, and its error names the class too")
                    .containsExactly("Could not load plugin: IOException", "Could not load plugin: IllegalStateException");
            assertThat(runOnFxThread(rack::isDisable)).isFalse();
            assertThat(channel.getInsertSlots()).isEmpty();
            assertThat(UndeclaredThrowFixture.disposals).hasValue(2);
            assertThat(warnings).hasSize(2);
        } finally {
            runOnFxThread(() -> {
                Window.getWindows().removeListener(recordAlerts);
                rack.dispose();
                return null;
            });
            rackLog.removeHandler(capture);
            UndeclaredThrowFixture.disarm();
        }
    }

    /**
     * Story 334 (PR #990 review, ruling R2-1): a plugin the browser picked whose constructor throws
     * is named by what it threw — the loader's error gives the constructor's own message, never the
     * reflective wrapper's {@code null} — adds no insert, makes nothing to dispose, and leaves the
     * rack enabled, with one error alert and one WARNING record. Real wiring: the rack's pick, the
     * real {@code PluginSlotLoader} and the real loader.
     */
    @Test
    void aPluginWhoseConstructorThrowsIsNamedByWhatItThrew(@TempDir Path directory) throws Exception {
        UndeclaredThrowFixture.disarm();
        // Built before it is armed: the manifest reads the descriptor of an instance it constructs.
        Path jar = PluginTestJars.buildJar(directory, "constructor-throw.jar",
                List.of(PluginTestJars.manifest(UndeclaredThrowFixture.class)), List.of(UndeclaredThrowFixture.class));
        ExternalPluginEntry entry = new ExternalPluginEntry(jar, UndeclaredThrowFixture.class.getName());
        MixerChannel channel = new MixerChannel("Voice");
        List<String> alerts = new CopyOnWriteArrayList<>();
        ListChangeListener<Window> recordAlerts = recordingAlerts(alerts);
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = capturingWarnings(warnings);
        Logger rackLog = Logger.getLogger(InsertEffectRack.class.getName());
        rackLog.addHandler(capture);
        UndeclaredThrowFixture.onConstruct = new IllegalStateException("the licence server could not be reached");
        InsertEffectRack rack = runOnFxThread(() -> {
            Window.getWindows().addListener(recordAlerts);
            return new InsertEffectRack(channel, 2, 48_000, 512, null, null);
        });
        try {
            runOnFxThread(() -> {
                rack.applyPickerSelection(0, new PluginBrowser.ExternalSelection(entry));
                return null;
            });
            awaitAlerts(rack, alerts, 1);

            assertThat(alerts).as("one error").hasSize(1);
            // The prefix's exact form belongs to future story 430; what the constructor threw ends it.
            assertThat(alerts.getFirst()).as("it names what the constructor threw")
                    .startsWith("Could not load plugin: ")
                    .endsWith("Failed to load plugin from " + entry.jarPath()
                            + ": the licence server could not be reached");
            assertThat(runOnFxThread(rack::isDisable)).as("the rack is enabled again").isFalse();
            assertThat(channel.getInsertSlots()).as("no insert").isEmpty();
            assertThat(UndeclaredThrowFixture.disposals).as("nothing was made, so nothing is disposed").hasValue(0);
            assertThat(warnings).as("the rack logs the failure once").singleElement()
                    .extracting(LogRecord::getMessage)
                    .isEqualTo("Could not load plugin " + UndeclaredThrowFixture.class.getName());
        } finally {
            runOnFxThread(() -> {
                Window.getWindows().removeListener(recordAlerts);
                rack.dispose();
                return null;
            });
            rackLog.removeHandler(capture);
            UndeclaredThrowFixture.disarm();
        }
    }

    /**
     * Story 334 (PR #990 review, rulings R-C3.4 and R3-1): a plugin the browser picked whose
     * {@code initialize()} throws a {@link PluginLoadException} with no message, which the load
     * takes for the loader's own, is named by its class, never {@code null}; the instance it made
     * is disposed once, no insert is added, and the rack is enabled again with one error alert and
     * one WARNING record. Real wiring: the rack's pick, the real {@code PluginSlotLoader} and the
     * real loader.
     */
    @Test
    void aLoadExceptionWithNoMessageFromThePluginsInitializeIsNamedByItsClass(@TempDir Path directory)
            throws Exception {
        UndeclaredThrowFixture.disarm();
        Path jar = PluginTestJars.buildJar(directory, "initialize-throw.jar",
                List.of(PluginTestJars.manifest(UndeclaredThrowFixture.class)), List.of(UndeclaredThrowFixture.class));
        ExternalPluginEntry entry = new ExternalPluginEntry(jar, UndeclaredThrowFixture.class.getName());
        MixerChannel channel = new MixerChannel("Voice");
        List<String> alerts = new CopyOnWriteArrayList<>();
        ListChangeListener<Window> recordAlerts = recordingAlerts(alerts);
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = capturingWarnings(warnings);
        Logger rackLog = Logger.getLogger(InsertEffectRack.class.getName());
        rackLog.addHandler(capture);
        PluginLoadException initializeFailure = new PluginLoadException(null);
        UndeclaredThrowFixture.onInitialize = initializeFailure;
        InsertEffectRack rack = runOnFxThread(() -> {
            Window.getWindows().addListener(recordAlerts);
            return new InsertEffectRack(channel, 2, 48_000, 512, null, null);
        });
        try {
            runOnFxThread(() -> {
                rack.applyPickerSelection(0, new PluginBrowser.ExternalSelection(entry));
                return null;
            });
            awaitAlerts(rack, alerts, 1);

            assertThat(alerts).as("one error").hasSize(1);
            // The prefix's exact form belongs to future story 430; the class of what initialize() threw ends it.
            assertThat(alerts.getFirst()).as("it names what initialize() threw by its class, never null")
                    .startsWith("Could not load plugin: ")
                    .endsWith(": PluginLoadException")
                    .doesNotContain("null");
            assertThat(runOnFxThread(rack::isDisable)).as("the rack is enabled again").isFalse();
            assertThat(channel.getInsertSlots()).as("no insert").isEmpty();
            assertThat(UndeclaredThrowFixture.disposals).as("the instance it made is disposed once").hasValue(1);
            assertThat(warnings).as("the rack logs the failure once").singleElement().satisfies(record -> {
                assertThat(record.getMessage()).isEqualTo("Could not load plugin " + UndeclaredThrowFixture.class.getName());
                assertThat(record.getThrown()).isInstanceOf(IllegalStateException.class)
                        .hasCauseReference(initializeFailure);
            });
        } finally {
            runOnFxThread(() -> {
                Window.getWindows().removeListener(recordAlerts);
                rack.dispose();
                return null;
            });
            rackLog.removeHandler(capture);
            UndeclaredThrowFixture.disarm();
        }
    }

    /**
     * Story 334 (PR #990 review, rulings R-C3.4, R2-2, R4-2 and R5-8): when a step after an insert fails —
     * here the slot is inserted and its editor then fails to open — the rack's error names an
     * exception with no message by its class, as its load error does (today's
     * {@code Could not insert plugin:} copy, which future story 430 makes name the step), and the
     * failure is logged once at WARNING with what was thrown, its record saying the plugin was
     * inserted; the slot is released only when its channel retires it.
     */
    @Test
    void anInsertErrorNamesAnExceptionWithNoMessageByItsClass() throws Exception {
        MixerChannel channel = new MixerChannel("Voice");
        InsertSlot slot = InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, 2, 48_000);
        AtomicReference<String> disposedOn = new AtomicReference<>();
        slot.setDisposal(() -> disposedOn.compareAndSet(null, Thread.currentThread().getName()));
        IllegalStateException editorFailure = new IllegalStateException();
        List<String> alerts = new CopyOnWriteArrayList<>();
        ListChangeListener<Window> recordAlerts = recordingAlerts(alerts);
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = capturingWarnings(warnings);
        Logger rackLog = Logger.getLogger(InsertEffectRack.class.getName());
        rackLog.addHandler(capture);
        InsertEffectRack rack = runOnFxThread(() -> {
            Window.getWindows().addListener(recordAlerts);
            InsertEffectRack made = new InsertEffectRack(channel, 2, 48_000, 512, null, null);
            made.setOnOpenEditor((_, _) -> {
                throw editorFailure;
            });
            return made;
        });
        try {
            // The alert's nested event loop runs the recorder's post before completeExternalInsert returns.
            runOnFxThread(() -> {
                rack.completeExternalInsert(0, slot);
                return null;
            });

            assertThat(alerts).containsExactly("Could not insert plugin: IllegalStateException");
            assertThat(warnings).as("the rack logs the failure once").singleElement().satisfies(record -> {
                assertThat(record.getMessage())
                        .isEqualTo("Inserted plugin " + slot.getName() + ", but a step after the insert failed");
                assertThat(record.getThrown()).as("with what was thrown").isSameAs(editorFailure);
            });
            channel.disposeInsertsWhenQuiescent().get(5, TimeUnit.SECONDS);
            assertThat(disposedOn.get())
                    .as("an inserted slot is released when its channel retires it, never while the channel renders it")
                    .isNotNull().isNotEqualTo("failed-plugin-dispose");
        } finally {
            runOnFxThread(() -> {
                Window.getWindows().removeListener(recordAlerts);
                rack.dispose();
                return null;
            });
            rackLog.removeHandler(capture);
            channel.disposeInsertsWhenQuiescent().get(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Story 334 (PR #990 review, rulings R4-2 and R5-8): an insert the channel refuses — it already holds
     * {@link MixerChannel#MAX_INSERT_SLOTS} inserts — is logged once at WARNING as not inserted,
     * with what was thrown, and shows one error; the slot is not added, and it is released.
     */
    @Test
    void anInsertTheChannelRefusesIsLoggedAsNotInserted() throws Exception {
        MixerChannel channel = new MixerChannel("Voice");
        for (int i = 0; i < MixerChannel.MAX_INSERT_SLOTS; i++) {
            channel.addInsert(InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, 2, 48_000));
        }
        InsertSlot slot = InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, 2, 48_000);
        CountDownLatch released = new CountDownLatch(1);
        slot.setDisposal(released::countDown);
        List<String> alerts = new CopyOnWriteArrayList<>();
        ListChangeListener<Window> recordAlerts = recordingAlerts(alerts);
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = capturingWarnings(warnings);
        Logger rackLog = Logger.getLogger(InsertEffectRack.class.getName());
        rackLog.addHandler(capture);
        InsertEffectRack rack = runOnFxThread(() -> {
            Window.getWindows().addListener(recordAlerts);
            return new InsertEffectRack(channel, 2, 48_000, 512, null, null);
        });
        try {
            // The alert's nested event loop runs the recorder's post before completeExternalInsert returns.
            runOnFxThread(() -> {
                rack.completeExternalInsert(0, slot);
                return null;
            });

            assertThat(alerts).containsExactly(
                    "Could not insert plugin: cannot exceed " + MixerChannel.MAX_INSERT_SLOTS + " insert slots");
            assertThat(warnings).as("the rack logs the failure once").singleElement().satisfies(record -> {
                assertThat(record.getMessage()).isEqualTo("Could not insert plugin " + slot.getName());
                assertThat(record.getThrown()).as("with what was thrown")
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("cannot exceed " + MixerChannel.MAX_INSERT_SLOTS + " insert slots");
            });
            assertThat(channel.getInsertSlots()).as("the slot is not added")
                    .hasSize(MixerChannel.MAX_INSERT_SLOTS)
                    .doesNotContain(slot);
            assertThat(released.await(5, TimeUnit.SECONDS)).as("the slot the channel refused is released")
                    .isTrue();
        } finally {
            runOnFxThread(() -> {
                Window.getWindows().removeListener(recordAlerts);
                rack.dispose();
                return null;
            });
            rackLog.removeHandler(capture);
            channel.disposeInsertsWhenQuiescent().get(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Story 334 (PR #990 review, ruling R2-2): a load that fails after its rack was disposed shows
     * nothing, there being no view, but is still logged once at WARNING with what was thrown, on
     * the load's worker rather than the FX thread; the instance it made is disposed once and
     * nothing is inserted.
     */
    @Test
    void aLoadThatFailsAfterTheRackIsDisposedIsStillLogged(@TempDir Path directory) throws Exception {
        Path jar = PluginTestJars.buildJar(directory, "undeclared-throw.jar",
                List.of(PluginTestJars.manifest(UndeclaredThrowFixture.class)), List.of(UndeclaredThrowFixture.class));
        ExternalPluginEntry entry = new ExternalPluginEntry(jar, UndeclaredThrowFixture.class.getName());
        MixerChannel channel = new MixerChannel("Voice");
        List<String> alerts = new CopyOnWriteArrayList<>();
        ListChangeListener<Window> recordAlerts = recordingAlerts(alerts);
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = capturingWarnings(warnings);
        Logger rackLog = Logger.getLogger(InsertEffectRack.class.getName());
        rackLog.addHandler(capture);
        UndeclaredThrowFixture.disarm();
        IOException activationFailure = new IOException();
        UndeclaredThrowFixture.onActivate = activationFailure;
        InsertEffectRack rack = runOnFxThread(() -> {
            Window.getWindows().addListener(recordAlerts);
            return new InsertEffectRack(channel, 2, 48_000, 512, null, null);
        });
        try {
            // One FX turn: what the load's worker posts runs on a later one, after the rack is disposed.
            long fxThread = runOnFxThread(() -> {
                rack.applyPickerSelection(0, new PluginBrowser.ExternalSelection(entry));
                rack.dispose();
                return Thread.currentThread().threadId();
            });
            awaitWarningAndEnabled(rack, warnings);

            assertThat(warnings).as("logged once, whatever became of the rack").singleElement().satisfies(record -> {
                assertThat(record.getMessage()).isEqualTo("Could not load plugin " + UndeclaredThrowFixture.class.getName());
                assertThat(record.getThrown()).isInstanceOf(IllegalStateException.class)
                        .hasCauseReference(activationFailure);
                assertThat(record.getLongThreadID()).as("logged on the load's worker, not the FX thread")
                        .isNotEqualTo(fxThread);
            });
            assertThat(alerts).as("no view, so nothing is shown").isEmpty();
            assertThat(UndeclaredThrowFixture.disposals).as("the instance it made is disposed once").hasValue(1);
            assertThat(channel.getInsertSlots()).as("no insert").isEmpty();
        } finally {
            runOnFxThread(() -> {
                Window.getWindows().removeListener(recordAlerts);
                rack.dispose();
                return null;
            });
            rackLog.removeHandler(capture);
            UndeclaredThrowFixture.disarm();
        }
    }

    /**
     * PR #990 review, ruling R5-1: a plugin the browser picked whose failure a log handler cannot
     * format — reading its message throws, as a plugin's exception that needs its closed JAR does —
     * still leaves the rack enabled, with one error alert naming the exception by its class; the
     * record is handed to the handlers once, the instance the load made is disposed once, and
     * nothing is inserted.
     */
    @Test
    void aLoadWhoseFailureCannotBeFormattedForTheLogStillLeavesTheRackUsable(@TempDir Path directory)
            throws Exception {
        Path jar = PluginTestJars.buildJar(directory, "undeclared-throw.jar",
                List.of(PluginTestJars.manifest(UndeclaredThrowFixture.class)), List.of(UndeclaredThrowFixture.class));
        ExternalPluginEntry entry = new ExternalPluginEntry(jar, UndeclaredThrowFixture.class.getName());
        MixerChannel channel = new MixerChannel("Voice");
        List<String> alerts = new CopyOnWriteArrayList<>();
        ListChangeListener<Window> recordAlerts = recordingAlerts(alerts);
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = formattingWarnings(warnings);
        Logger rackLog = Logger.getLogger(InsertEffectRack.class.getName());
        rackLog.addHandler(capture);
        UndeclaredThrowFixture.disarm();
        UndeclaredThrowFixture.UnformattableFailure activationFailure = new UndeclaredThrowFixture.UnformattableFailure();
        UndeclaredThrowFixture.onActivate = activationFailure;
        InsertEffectRack rack = runOnFxThread(() -> {
            Window.getWindows().addListener(recordAlerts);
            return new InsertEffectRack(channel, 2, 48_000, 512, null, null);
        });
        try {
            runOnFxThread(() -> {
                rack.applyPickerSelection(0, new PluginBrowser.ExternalSelection(entry));
                return null;
            });
            awaitAlerts(rack, alerts, 1);

            assertThat(alerts).as("one error, naming the exception by its class: its message cannot be read")
                    .containsExactly("Could not load plugin: UnformattableFailure");
            assertThat(runOnFxThread(rack::isDisable)).as("the rack is enabled again").isFalse();
            assertThat(warnings).as("the record is handed to the handlers once").singleElement()
                    .extracting(LogRecord::getThrown).isSameAs(activationFailure);
            assertThat(UndeclaredThrowFixture.disposals).as("the instance it made is disposed once").hasValue(1);
            assertThat(channel.getInsertSlots()).as("no insert").isEmpty();
        } finally {
            runOnFxThread(() -> {
                Window.getWindows().removeListener(recordAlerts);
                rack.dispose();
                return null;
            });
            rackLog.removeHandler(capture);
            UndeclaredThrowFixture.disarm();
        }
    }

    /** A handler that adds each WARNING record it is given to {@code warnings}. */
    private static Handler capturingWarnings(List<LogRecord> warnings) {
        return new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) warnings.add(record);
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
    }

    /**
     * A handler that adds each WARNING record it is given to {@code warnings}, then formats it as the
     * JDK's console handler does, which runs what the record's exception runs to give its message.
     */
    private static Handler formattingWarnings(List<LogRecord> warnings) {
        return new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) {
                    warnings.add(record);
                    new SimpleFormatter().format(record);
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
    }

    /**
     * Records the content of each {@code Insert Effect Error} alert, then hides it — on a later FX
     * turn, never inside the window list's add notification.
     */
    private static ListChangeListener<Window> recordingAlerts(List<String> alerts) {
        return change -> {
            while (change.next()) {
                for (Window window : change.getAddedSubList()) {
                    if (window instanceof Stage stage && "Insert Effect Error".equals(stage.getTitle())) {
                        Platform.runLater(() -> {
                            try {
                                Node pane = stage.getScene().getRoot().lookup(".dialog-pane");
                                alerts.add(pane instanceof DialogPane dialogPane ? dialogPane.getContentText()
                                        : "<no dialog pane>");
                            } finally {
                                stage.hide();
                            }
                        });
                    }
                }
            }
        };
    }

    /** Waits, bounded, until the rack shows {@code count} errors and is enabled again. */
    private static void awaitAlerts(InsertEffectRack rack, List<String> alerts, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && (alerts.size() < count || runOnFxThread(rack::isDisable))) {
            Thread.sleep(20);
        }
        // One more FX turn: what the failure posted after its alert has run before the asserts.
        runOnFxThread(() -> Boolean.TRUE);
    }

    /**
     * Waits, bounded and shorter than an FX call's guard, until a WARNING is recorded and the rack
     * is enabled again, then one more FX turn.
     */
    private static void awaitWarningAndEnabled(InsertEffectRack rack, List<LogRecord> warnings)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && (warnings.isEmpty() || runOnFxThread(rack::isDisable))) {
            Thread.sleep(20);
        }
        runOnFxThread(() -> Boolean.TRUE);
    }

    private static Stream<Node> nodes(Node node) {
        return node instanceof Parent parent
                ? Stream.concat(Stream.of(node), parent.getChildrenUnmodifiable().stream().flatMap(MixerPluginLifetimeTest::nodes))
                : Stream.of(node);
    }
}
