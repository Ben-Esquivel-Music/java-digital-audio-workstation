package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.plugin.EditorFrame;
import com.benesquivelmusic.daw.app.ui.plugin.PluginSlotLoader;
import com.benesquivelmusic.daw.app.ui.plugin.PluginTestJars;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.UndeclaredThrowFixture;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.*;
import com.benesquivelmusic.daw.core.plugin.*;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.*;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@ExtendWith(JavaFxToolkitExtension.class)
final class PluginSignalPathActivationTest {
    static PluginViewController controller() {
        return controller(new ArrayList<>());
    }

    static PluginViewController controller(List<String> notifications) {
        return new PluginViewController(new PluginViewController.Deps(() -> 48_000, () -> 512,
                () -> null, () -> { }, (message, icon) -> { },
                (level, message) -> notifications.add(message), (segments, node) -> { },
                () -> null, () -> { }));
    }

    static void configure(PluginViewController controller, MixerChannel channel) {
        controller.setRouting(new PluginViewController.Routing(() -> channel, () -> null,
                PluginSignalPathActivationTest::builtInSlot, ignored -> { }), null);
    }

    static InsertSlot builtInSlot(Class<? extends BuiltInDawPlugin> type) {
        try {
            return new BuiltInPluginGraph(new ProcessorRegistry()).createSlot(type.getConstructor().newInstance(),
                    new PluginContext() {
                        @Override public double getSampleRate() { return 48_000; }
                        @Override public int getBufferSize() { return 512; }
                        @Override public void log(String message) { }
                    }, new Metronome(48_000, 2));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void menuActivationInsertsOnceThenFocusesTheSameEditorAndSlot() {
        runOnFxThread(() -> {
            var channel = new MixerChannel("Voice");
            var controller = controller();
            configure(controller, channel);
            try {
                controller.onActivateBuiltInPlugin(CompressorPlugin.class);
                var slot = channel.getInsertSlots().getFirst();
                var session = controller.activeEditorSessionForTest();
                assertThat(session).isNotNull();
                assertThat(session.store()).isSameAs(slot.getParameterStore());
                controller.onActivateBuiltInPlugin(CompressorPlugin.class);
                assertThat(channel.getInsertSlots()).containsExactly(slot);
                assertThat(controller.activeEditorSessionForTest()).isSameAs(session);
                channel.removeInsert(0);
                controller.reconcileGraph();
                assertThat(controller.activeEditorSessionForTest()).isNull();
            } finally { controller.dispose(); }
            return null;
        });
    }

    @Test
    void builtInActivationFindsAnExistingSlotAfterSwitchingAndClosingEditorsInAFullRack() {
        runOnFxThread(() -> {
            var channel = new MixerChannel("Voice");
            var controller = controller();
            configure(controller, channel);
            try {
                controller.onActivateBuiltInPlugin(CompressorPlugin.class);
                var compressor = channel.getInsertSlots().getFirst();
                controller.onActivateBuiltInPlugin(ReverbPlugin.class);
                while (channel.getInsertCount() < MixerChannel.MAX_INSERT_SLOTS) {
                    channel.addInsert(InsertEffectFactory.createSlot(InsertEffectType.DELAY, 2, 48_000));
                }
                controller.onActivateBuiltInPlugin(CompressorPlugin.class);
                assertThat(controller.activeEditorSessionForTest().store()).isSameAs(compressor.getParameterStore());
                controller.activeEditorSessionForTest().frame().getOnCloseRequested().run();
                assertThat(controller.activeEditorSessionForTest()).isNull();
                controller.onActivateBuiltInPlugin(CompressorPlugin.class);
                assertThat(controller.activeEditorSessionForTest().store()).isSameAs(compressor.getParameterStore());
                assertThat(channel.getInsertCount()).isEqualTo(MixerChannel.MAX_INSERT_SLOTS);
            } finally {
                controller.dispose();
                channel.disposeInsertsWhenQuiescent();
            }
            return null;
        });
    }

    @Test
    void externalActivationFindsAnExistingSlotAfterSwitchingAndClosingEditors() {
        runOnFxThread(() -> {
            var channel = new MixerChannel("External");
            var controller = controller();
            configure(controller, channel);
            var plugin = new ExternalFixture();
            var loads = new AtomicInteger();
            controller.setExternalSlotLoader(requested -> () -> {
                loads.incrementAndGet();
                return InsertEffectFactory.createSlotFromPlugin(requested).orElseThrow();
            });
            try {
                controller.onActivateExternalPlugin(plugin);
                var slot = channel.getInsertSlots().getFirst();
                controller.onActivateBuiltInPlugin(CompressorPlugin.class);
                controller.onActivateExternalPlugin(new ExternalFixture());
                assertThat(controller.activeEditorSessionForTest().store()).isSameAs(slot.getParameterStore());
                controller.activeEditorSessionForTest().frame().getOnCloseRequested().run();
                controller.onActivateExternalPlugin(new ExternalFixture());
                assertThat(controller.activeEditorSessionForTest().store()).isSameAs(slot.getParameterStore());
                assertThat(channel.getInsertCount()).isEqualTo(2);
                assertThat(loads).hasValue(1);
            } finally {
                controller.dispose();
                channel.disposeInsertsWhenQuiescent();
            }
            return null;
        });
    }

    @Test
    void builtInActivationMatchesRawEffectTypesWithoutMistakingExternalPluginsForThem() {
        runOnFxThread(() -> {
            var channel = new MixerChannel("Voice");
            var controller = controller();
            configure(controller, channel);
            var external = new InsertSlot("Compressor",
                    InsertEffectFactory.createProcessor(InsertEffectType.COMPRESSOR, 2, 48_000),
                    InsertEffectType.COMPRESSOR, new ExternalFixture());
            var raw = new InsertSlot("Renamed compressor",
                    InsertEffectFactory.createProcessor(InsertEffectType.COMPRESSOR, 2, 48_000),
                    InsertEffectType.COMPRESSOR);
            channel.addInsert(external);
            channel.addInsert(raw);
            try {
                controller.onActivateBuiltInPlugin(CompressorPlugin.class);
                assertThat(channel.getInsertSlots()).containsExactly(external, raw);
                assertThat(controller.activeEditorSessionForTest().store()).isSameAs(raw.getParameterStore());
            } finally {
                controller.dispose();
                channel.disposeInsertsWhenQuiescent();
            }
            return null;
        });
    }

    @Test
    void aMatchingSlotInsertedDuringLoadingIsFocusedAndThePreparedDuplicateIsDisposed() {
        runOnFxThread(() -> {
            var channel = new MixerChannel("External");
            var controller = controller();
            configure(controller, channel);
            var queued = new ArrayList<Runnable>();
            controller.setActivationWorker(queued::add);
            var preparedPlugin = new ExternalFixture();
            var existing = InsertEffectFactory.createSlotFromPlugin(new ExternalFixture()).orElseThrow();
            try {
                controller.onActivateExternalPlugin(preparedPlugin);
                channel.addInsert(existing);
                queued.removeFirst().run();
                assertThat(channel.getInsertSlots()).containsExactly(existing);
                assertThat(controller.activeEditorSessionForTest().store()).isSameAs(existing.getParameterStore());
                queued.removeFirst().run();
                assertThat(preparedPlugin.disposed).isTrue();
                assertThat(queued).isEmpty();
            } finally {
                controller.dispose();
                channel.disposeInsertsWhenQuiescent();
            }
            return null;
        });
    }

    @Test
    void theEngineMetronomeHasOneSlotAcrossChannelsAndPendingActivations() {
        runOnFxThread(() -> {
            var project = new com.benesquivelmusic.daw.core.project.DawProject("Metronome",
                    new com.benesquivelmusic.daw.core.audio.AudioFormat(48_000, 2, 24, 512));
            var first = project.getMixerChannelForTrack(project.createAudioTrack("First"));
            var second = project.getMixerChannelForTrack(project.createAudioTrack("Second"));
            var selected = new java.util.concurrent.atomic.AtomicReference<>(first);
            var queued = new ArrayList<Runnable>();
            var controller = new PluginViewController(new PluginViewController.Deps(() -> 48_000, () -> 512,
                    () -> project, () -> { }, (message, icon) -> { }, (level, message) -> { },
                    (segments, node) -> { }, () -> null, () -> { }));
            controller.setRouting(new PluginViewController.Routing(selected::get, () -> null,
                    PluginSignalPathActivationTest::builtInSlot, ignored -> { }), null);
            controller.setActivationWorker(queued::add);
            try {
                controller.onActivateBuiltInPlugin(MetronomePlugin.class);
                selected.set(second);
                controller.onActivateBuiltInPlugin(MetronomePlugin.class);
                assertThat(queued).hasSize(1);
                queued.removeFirst().run();
                var session = controller.activeEditorSessionForTest();
                controller.onActivateBuiltInPlugin(MetronomePlugin.class);
                assertThat(queued).isEmpty();
                assertThat(first.getInsertCount()).isOne();
                assertThat(second.getInsertSlots()).isEmpty();
                assertThat(controller.activeEditorSessionForTest()).isSameAs(session);
            } finally {
                controller.dispose();
                project.disposeInsertsWhenQuiescent();
            }
            return null;
        });
    }

    @Test
    void noSelectionPresentsPickerBeforeConstructingOrInsertingAnyPlugin() {
        runOnFxThread(() -> {
            var pickerCalls = new AtomicInteger();
            var factoryCalls = new AtomicInteger();
            var controller = controller();
            controller.setRouting(new PluginViewController.Routing(() -> null,
                    () -> { pickerCalls.incrementAndGet(); return null; },
                    type -> { factoryCalls.incrementAndGet(); return builtInSlot(type); }, ignored -> { }), null);
            try {
                controller.onActivateBuiltInPlugin(CompressorPlugin.class);
                assertThat(pickerCalls).hasValue(1);
                assertThat(factoryCalls).hasValue(0);
                assertThat(controller.activeEditorSessionForTest()).isNull();
            } finally { controller.dispose(); }
            return null;
        });
    }

    @Test
    void selectingChannelFromPickerInsertsOnThatChannel() {
        runOnFxThread(() -> {
            var channel = new MixerChannel("Chosen track");
            var controller = controller();
            controller.setRouting(new PluginViewController.Routing(() -> null, () -> channel,
                    PluginSignalPathActivationTest::builtInSlot, ignored -> { }), null);
            try {
                controller.onActivateBuiltInPlugin(ParametricEqPlugin.class);
                assertThat(channel.getInsertCount()).isOne();
                assertThat(controller.activeEditorSessionForTest()).isNotNull();
            } finally { controller.dispose(); }
            return null;
        });
    }

    @Test
    void failingExternalActivationLeavesNoLiveSlotAndDisposesUnpublishedInstance() {
        runOnFxThread(() -> {
            var channel = new MixerChannel("Track");
            var notifications = new ArrayList<String>();
            var controller = controller(notifications);
            configure(controller, channel);
            var plugin = new ExternalFixture();
            plugin.failActivation = true;
            try {
                controller.onActivateExternalPlugin(plugin);
                assertThat(channel.getInsertSlots()).isEmpty();
                assertThat(plugin.disposed).isTrue();
                assertThat(controller.activeEditorSessionForTest()).isNull();
                assertThat(notifications).singleElement().asString().contains("activation refused");
            } finally { controller.dispose(); }
            return null;
        });
    }

    @Test
    void externalInitializationErrorDisposesTheInstanceAndAllowsRetryingTheSamePlugin() {
        runOnFxThread(() -> {
            var channel = new MixerChannel("Track");
            var notifications = new ArrayList<String>();
            var controller = controller(notifications);
            configure(controller, channel);
            var failed = new ExternalFixture();
            failed.failInitialization = true;
            try {
                controller.onActivateExternalPlugin(failed);
                assertThat(failed.disposed).isTrue();
                assertThat(channel.getInsertSlots()).isEmpty();
                assertThat(notifications).singleElement().asString().contains("initialization linkage failed");
                var healthy = new ExternalFixture();
                controller.onActivateExternalPlugin(healthy);
                assertThat(channel.getInsertSlots()).singleElement()
                        .satisfies(slot -> assertThat(slot.getPlugin()).isSameAs(healthy));
                assertThat(controller.activeEditorSessionForTest()).isNotNull();
            } finally { controller.dispose(); }
            return null;
        });
    }

    /**
     * Story 334 (PR #990 review, rulings R-C3.2, R-C3.4, R-C3.6 and R2-2): a plugin the menu
     * activates, loaded from its JAR as {@code MainController.prepareExternalPluginLoad} wires the
     * load, whose {@code activate()} throws a checked exception it does not declare is disposed
     * once, adds no insert and is notified and logged once; the pending activation is forgotten, so
     * activating it on the strip again starts a second load, whose message-less unchecked failure
     * is notified once, named by its class, and logged once.
     */
    @Test
    void aMenuLoadWhoseActivationThrowsAnythingIsForgottenAndNotifiedOnceEachTime(@TempDir Path directory)
            throws Exception {
        Path jar = PluginTestJars.buildJar(directory, "undeclared-throw.jar",
                List.of(PluginTestJars.manifest(UndeclaredThrowFixture.class)), List.of(UndeclaredThrowFixture.class));
        ExternalPluginEntry entry = new ExternalPluginEntry(jar, UndeclaredThrowFixture.class.getName());
        AudioFormat format = new AudioFormat(48_000, 2, 24, 512);
        MixerChannel channel = new MixerChannel("Track");
        List<String> notifications = new CopyOnWriteArrayList<>();
        Queue<Runnable> queued = new ConcurrentLinkedQueue<>();
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = capturingWarnings(warnings);
        Logger controllerLog = Logger.getLogger(PluginViewController.class.getName());
        controllerLog.addHandler(capture);
        UndeclaredThrowFixture template = new UndeclaredThrowFixture();
        UndeclaredThrowFixture.disarm();
        UndeclaredThrowFixture.onActivate = new IOException("the licence file could not be read");
        PluginViewController controller = runOnFxThread(() -> {
            PluginViewController made = controller(notifications);
            configure(made, channel);
            made.setActivationWorker(queued::add);
            made.setExternalSlotLoader(requested -> () -> PluginSlotLoader.load(entry, format));
            made.onActivateExternalPlugin(template);
            return made;
        });
        try {
            assertThat(queued).as("one load queued").hasSize(1);
            runOffFxThenOneFxTurn(queued.remove());

            assertThat(notifications).as("one notification, naming the plugin and its failure")
                    .containsExactly("Failed to activate " + UndeclaredThrowFixture.NAME
                            + ": the licence file could not be read");
            long loadThread = Thread.currentThread().threadId();
            assertThat(warnings).as("logged once").singleElement().satisfies(record -> {
                assertThat(record.getMessage()).isEqualTo("Failed to activate " + UndeclaredThrowFixture.NAME);
                assertThat(record.getLongThreadID()).as("logged on the thread that ran the load, not the FX thread")
                        .isEqualTo(loadThread);
            });
            assertThat(UndeclaredThrowFixture.disposals).as("the instance it made is disposed once").hasValue(1);
            assertThat(channel.getInsertSlots()).as("no insert").isEmpty();

            UndeclaredThrowFixture.onActivate = new IllegalStateException();
            runOnFxThread(() -> {
                controller.onActivateExternalPlugin(template);
                return null;
            });
            assertThat(queued).as("the pending activation was forgotten: a second load starts").hasSize(1);
            runOffFxThenOneFxTurn(queued.remove());

            assertThat(notifications).hasSize(2).last()
                    .isEqualTo("Failed to activate " + UndeclaredThrowFixture.NAME + ": IllegalStateException");
            assertThat(warnings).as("each load's failure logged once").hasSize(2);
            assertThat(UndeclaredThrowFixture.disposals).hasValue(2);
            assertThat(channel.getInsertSlots()).isEmpty();
        } finally {
            runOnFxThread(() -> {
                controller.dispose();
                return null;
            });
            controllerLog.removeHandler(capture);
            UndeclaredThrowFixture.disarm();
        }
    }

    /**
     * Story 334 (PR #990 review, ruling R2-2): a menu load that fails after its view was disposed —
     * a load in flight while the main window closes — shows nothing, there being no view, but is
     * still logged once at WARNING with what was thrown, on the load's worker; the instance it made
     * is disposed once and nothing is inserted.
     */
    @Test
    void aMenuLoadThatFailsAfterTheViewIsDisposedIsStillLogged(@TempDir Path directory) throws Exception {
        Path jar = PluginTestJars.buildJar(directory, "undeclared-throw.jar",
                List.of(PluginTestJars.manifest(UndeclaredThrowFixture.class)), List.of(UndeclaredThrowFixture.class));
        ExternalPluginEntry entry = new ExternalPluginEntry(jar, UndeclaredThrowFixture.class.getName());
        AudioFormat format = new AudioFormat(48_000, 2, 24, 512);
        MixerChannel channel = new MixerChannel("Track");
        List<String> notifications = new CopyOnWriteArrayList<>();
        Queue<Runnable> queued = new ConcurrentLinkedQueue<>();
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = capturingWarnings(warnings);
        Logger controllerLog = Logger.getLogger(PluginViewController.class.getName());
        controllerLog.addHandler(capture);
        UndeclaredThrowFixture template = new UndeclaredThrowFixture();
        UndeclaredThrowFixture.disarm();
        IOException activationFailure = new IOException("the licence file could not be read");
        UndeclaredThrowFixture.onActivate = activationFailure;
        PluginViewController controller = runOnFxThread(() -> {
            PluginViewController made = controller(notifications);
            configure(made, channel);
            made.setActivationWorker(queued::add);
            made.setExternalSlotLoader(requested -> () -> PluginSlotLoader.load(entry, format));
            made.onActivateExternalPlugin(template);
            return made;
        });
        try {
            assertThat(queued).as("one load queued").hasSize(1);
            runOnFxThread(() -> {
                controller.dispose();
                return null;
            });
            runOffFxThenOneFxTurn(queued.remove());

            long loadThread = Thread.currentThread().threadId();
            assertThat(warnings).as("logged once, whatever became of the view").singleElement().satisfies(record -> {
                assertThat(record.getMessage()).isEqualTo("Failed to activate " + UndeclaredThrowFixture.NAME);
                assertThat(record.getThrown()).isInstanceOf(IllegalStateException.class)
                        .hasCauseReference(activationFailure);
                assertThat(record.getLongThreadID()).as("logged on the load's worker: the thread that ran the load")
                        .isEqualTo(loadThread);
            });
            assertThat(notifications).as("no view, so nothing is shown").isEmpty();
            assertThat(UndeclaredThrowFixture.disposals).as("the instance it made is disposed once").hasValue(1);
            assertThat(channel.getInsertSlots()).as("no insert").isEmpty();
        } finally {
            runOnFxThread(() -> {
                controller.dispose();
                return null;
            });
            controllerLog.removeHandler(capture);
            UndeclaredThrowFixture.disarm();
        }
    }

    /**
     * PR #990 review, ruling R5-2: a menu load whose failure a log handler cannot format — reading
     * its message throws, as a plugin's exception that needs its closed JAR does — is still
     * notified once, naming the exception by its class, and forgotten, so activating it again
     * starts a second load; what the handler threw then ends the load's worker, and the instance
     * the load made is disposed once.
     */
    @Test
    void aMenuLoadWhoseFailureCannotBeFormattedForTheLogIsStillForgottenAndNotified(@TempDir Path directory)
            throws Exception {
        Path jar = PluginTestJars.buildJar(directory, "undeclared-throw.jar",
                List.of(PluginTestJars.manifest(UndeclaredThrowFixture.class)), List.of(UndeclaredThrowFixture.class));
        ExternalPluginEntry entry = new ExternalPluginEntry(jar, UndeclaredThrowFixture.class.getName());
        AudioFormat format = new AudioFormat(48_000, 2, 24, 512);
        MixerChannel channel = new MixerChannel("Track");
        List<String> notifications = new CopyOnWriteArrayList<>();
        Queue<Runnable> queued = new ConcurrentLinkedQueue<>();
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = formattingWarnings(warnings);
        Logger controllerLog = Logger.getLogger(PluginViewController.class.getName());
        controllerLog.addHandler(capture);
        UndeclaredThrowFixture template = new UndeclaredThrowFixture();
        UndeclaredThrowFixture.disarm();
        UndeclaredThrowFixture.UnformattableFailure activationFailure = new UndeclaredThrowFixture.UnformattableFailure();
        UndeclaredThrowFixture.onActivate = activationFailure;
        PluginViewController controller = runOnFxThread(() -> {
            PluginViewController made = controller(notifications);
            configure(made, channel);
            made.setActivationWorker(queued::add);
            made.setExternalSlotLoader(requested -> () -> PluginSlotLoader.load(entry, format));
            made.onActivateExternalPlugin(template);
            return made;
        });
        try {
            assertThat(queued).as("one load queued").hasSize(1);
            Runnable load = queued.remove();
            Throwable escaped = catchThrowable(load::run);
            runOnFxThread(() -> Boolean.TRUE);

            assertThat(escaped).as("what the handler threw ends the load's worker")
                    .isInstanceOf(AssertionError.class)
                    .hasMessage(UndeclaredThrowFixture.UnformattableFailure.UNREADABLE);
            assertThat(warnings).as("the record is handed to the handlers once").singleElement()
                    .extracting(LogRecord::getThrown).isSameAs(activationFailure);
            assertThat(notifications).as("one notification, naming the exception by its class")
                    .containsExactly("Failed to activate " + UndeclaredThrowFixture.NAME + ": UnformattableFailure");
            assertThat(UndeclaredThrowFixture.disposals).as("the instance it made is disposed once").hasValue(1);
            assertThat(channel.getInsertSlots()).as("no insert").isEmpty();

            runOnFxThread(() -> {
                controller.onActivateExternalPlugin(template);
                return null;
            });
            assertThat(queued).as("the pending activation was forgotten: a second load starts").hasSize(1);
        } finally {
            runOnFxThread(() -> {
                controller.dispose();
                return null;
            });
            controllerLog.removeHandler(capture);
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
     * Runs a queued activation load on this thread, off FX as the {@code plugin-activate} worker
     * does, then waits one FIFO FX turn for what it posted there.
     */
    private static void runOffFxThenOneFxTurn(Runnable load) {
        assertThat(catchThrowable(load::run)).as("the load's worker ends normally").isNull();
        runOnFxThread(() -> Boolean.TRUE);
    }

    @Test
    void midiEffectActivationReportsUnsupportedInsertionWithoutChangingTheGraphOrEditor() {
        runOnFxThread(() -> {
            var channel = new MixerChannel("MIDI track");
            var notifications = new ArrayList<String>();
            var dirtyCalls = new AtomicInteger();
            var graphChanges = new AtomicInteger();
            var controller = new PluginViewController(new PluginViewController.Deps(() -> 48_000, () -> 512,
                    () -> null, dirtyCalls::incrementAndGet, (message, icon) -> { },
                    (level, message) -> notifications.add(message), (segments, node) -> { },
                    () -> null, () -> { }));
            controller.setRouting(new PluginViewController.Routing(() -> channel, () -> null,
                    PluginSignalPathActivationTest::builtInSlot, ignored -> graphChanges.incrementAndGet()), null);
            try {
                controller.onActivateBuiltInPlugin(ArpeggiatorPlugin.class);
                assertThat(channel.getInsertSlots()).isEmpty();
                assertThat(controller.activeEditorSessionForTest()).isNull();
                controller.onActivateBuiltInPlugin(ArpeggiatorPlugin.class);
                assertThat(notifications).hasSize(2).allSatisfy(message ->
                        assertThat(message).contains("MIDI effects are not supported in audio insert slots"));
                assertThat(dirtyCalls).hasValue(0);
                assertThat(graphChanges).hasValue(0);

                controller.onActivateBuiltInPlugin(CompressorPlugin.class);
                var slot = channel.getInsertSlots().getFirst();
                var session = controller.activeEditorSessionForTest();
                dirtyCalls.set(0);
                graphChanges.set(0);
                controller.onActivateBuiltInPlugin(ArpeggiatorPlugin.class);
                assertThat(channel.getInsertSlots()).containsExactly(slot);
                assertThat(controller.activeEditorSessionForTest()).isSameAs(session);
                assertThat(dirtyCalls).hasValue(0);
                assertThat(graphChanges).hasValue(0);
            } finally { controller.dispose(); }
            return null;
        });
    }

    @Test
    void externalRackSlotOpensOwnedThemedContractEditorBoundToItsLiveStore() {
        runOnFxThread(() -> {
            var channel = new MixerChannel("External track");
            var plugin = new ExternalFixture();
            var slot = InsertEffectFactory.createSlotFromPlugin(plugin).orElseThrow();
            channel.addInsert(slot);
            var rack = new InsertEffectRack(channel, 2, 48_000, 512, null, null);
            var owner = new Stage();
            owner.setScene(new Scene(new VBox(rack)));
            owner.show();
            try {
                rack.openParameterEditor(slot);
                var window = Window.getWindows().stream().filter(candidate -> candidate instanceof Stage stage
                        && stage.getOwner() == owner).findFirst().orElseThrow();
                assertThat(window.getScene().getRoot()).isInstanceOf(EditorFrame.class);
                assertThat(window.getScene().getStylesheets()).isNotEmpty();
                rack.openParameterEditor(slot);
                assertThat(Window.getWindows().stream().filter(candidate -> candidate instanceof Stage stage
                        && stage.getOwner() == owner).count()).isOne();
            } finally { rack.dispose(); owner.close(); }
            return null;
        });
    }

    @Test
    void editorProductionEntryPointsAreSlotBoundAndRawLegacyChromeIsGone() throws Exception {
        Path source = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui");
        assertThat(source).isDirectory();
        String controllerSource = productionSource(source.resolve("PluginViewController.java"));
        String rackSource = productionSource(source.resolve("InsertEffectRack.java"));
        assertThat(controllerSource).doesNotContain("builtInPluginCache", "initializedExternalPlugins",
                "routesToMasteringView", "openEditor(DawPlugin");
        assertThat(controllerSource).contains("PluginEditorSession.open(channel, slot,");
        assertThat(rackSource).doesNotContain("new PluginParameterEditorPanel", "new Scene(editor,");
        assertThat(rackSource).contains("channel, slot, new", "stage.initOwner(",
                "ThemeManager.getDefault().applyTo(stage.getScene())");
    }

    private static String productionSource(Path source) throws java.io.IOException {
        return SourceScanSupport.stripStringLiterals(
                SourceScanSupport.stripComments(Files.readString(source)));
    }

    static final class ExternalFixture implements DawPlugin, AudioProcessor {
        boolean failActivation;
        boolean failInitialization;
        boolean disposed;
        @Override public PluginDescriptor getDescriptor() {
            return new PluginDescriptor("test.external.live", "External gain", "1", "Test", PluginType.EFFECT);
        }
        @Override public List<PluginParameter> getParameters() {
            return List.of(new PluginParameter(0, "Gain", 0, 1, 1));
        }
        @Override public Optional<AudioProcessor> asAudioProcessor() { return Optional.of(this); }
        @Override public void initialize(PluginContext context) {
            if (failInitialization) throw new LinkageError("initialization linkage failed");
        }
        @Override public void activate() { if (failActivation) throw new IllegalStateException("activation refused"); }
        @Override public void deactivate() { }
        @Override public void dispose() { disposed = true; }
        @Override public void process(float[][] input, float[][] output, int frames) {
            for (int ch = 0; ch < output.length; ch++) System.arraycopy(input[ch], 0, output[ch], 0, frames);
        }
        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
    }
}
