package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.plugin.EditorFrame;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

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
        Path source = Path.of("src/main/java/com/benesquivelmusic/daw/app/ui");
        String controllerSource = Files.readString(source.resolve("PluginViewController.java"));
        String rackSource = Files.readString(source.resolve("InsertEffectRack.java"));
        assertThat(controllerSource).doesNotContain("builtInPluginCache", "initializedExternalPlugins",
                "routesToMasteringView", "openEditor(DawPlugin");
        assertThat(controllerSource).contains("PluginEditorSession.open(channel, slot,");
        assertThat(rackSource).doesNotContain("new PluginParameterEditorPanel", "new Scene(editor,");
        assertThat(rackSource).contains("channel, slot, new", "stage.initOwner(",
                "ThemeManager.getDefault().applyTo(stage.getScene())");
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
