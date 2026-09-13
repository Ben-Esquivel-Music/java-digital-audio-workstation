package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.PluginParameterEditorPanel;
import com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.plugin.TransientShaperPlugin;
import com.benesquivelmusic.daw.core.plugin.parameter.ParameterPreset;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.editor.EditorHints;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.app.ui.controls.Knob;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(JavaFxToolkitExtension.class)
class PluginEditorSignalPathTest {
    private static final PluginEditorSession.Deps DEPS = new PluginEditorSession.Deps(() -> 48_000, null, null, null);

    @TempDir Path directory;

    @Test
    void editorRequiresMembershipAndReopeningRetainsQueuedChanges() {
        var channel = new MixerChannel("Voice");
        var slot = InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, 2, 48_000);
        runOnFxThread(() -> {
            assertThatThrownBy(() -> PluginEditorSession.open(channel, slot, DEPS))
                    .isInstanceOf(IllegalArgumentException.class);
            channel.addInsert(slot);
            var session = PluginEditorSession.open(channel, slot, DEPS);
            session.store().writeFromUiById(0, -36);
            session.dispose();
            var reopened = PluginEditorSession.open(channel, slot, DEPS);
            assertThat(reopened.store()).isSameAs(slot.getParameterStore());
            assertThat(reopened.store().valueById(0)).isEqualTo(-36);
            reopened.frame().getOnReloadRequested().run();
            assertThat(reopened.store().valueById(0)).isEqualTo(-36);
            reopened.dispose();
            return null;
        });
        render(channel);
        assertThat(((CompressorProcessor) slot.getProcessor()).getThresholdDb()).isEqualTo(-36);
        assertThat(channel.getInsertSlots()).containsExactly(slot);
    }

    @Test
    void contractAbRecallsBooleanAndEchoesBespokePanelWithoutFxProcessorWrites() {
        var plugin = new TransientShaperPlugin();
        plugin.initialize(context());
        var channel = new MixerChannel("Drums");
        var slot = InsertEffectFactory.createSlotFromPlugin(plugin).orElseThrow();
        channel.addInsert(slot);
        runOnFxThread(() -> {
            var session = PluginEditorSession.open(channel, slot, DEPS);
            try {
                CheckBox monitor = nodes(session.frame().getBody())
                        .filter(CheckBox.class::isInstance).map(CheckBox.class::cast).findFirst().orElseThrow();
                monitor.setSelected(true);
                assertThat(plugin.getProcessor().isInputMonitor()).isFalse();
                render(channel);
                assertThat(plugin.getProcessor().isInputMonitor()).isTrue();
                session.frame().getOnAbToggleRequested().run();
                assertThat(monitor.isSelected()).isFalse();
                render(channel);
                assertThat(plugin.getProcessor().isInputMonitor()).isFalse();
                session.frame().getOnAbToggleRequested().run();
                assertThat(monitor.isSelected()).isTrue();
                render(channel);
                assertThat(plugin.getProcessor().isInputMonitor()).isTrue();
                session.dispose();
                monitor.setSelected(false);
                assertThat(slot.getParameterStore().valueById(3)).isOne();
            } finally {
                session.dispose();
                plugin.dispose();
            }
            return null;
        });
    }

    @Test
    void fallbackAbAndPresetRecallPublishBooleanAndEchoText() {
        runOnFxThread(() -> {
            var panel = new PluginParameterEditorPanel(List.of(new PluginParameter(9, "Enabled Toggle", 0, 1, 0)));
            var applied = new double[1];
            panel.setOnParameterChanged((_, value) -> applied[0] = value);
            ToggleButton toggle = nodes(panel).filter(ToggleButton.class::isInstance)
                    .map(ToggleButton.class::cast).filter(button -> button != panel.getAbToggleButton()).findFirst().orElseThrow();
            toggle.fire();
            assertThat(applied[0]).isOne();
            panel.getAbToggleButton().fire();
            assertThat(applied[0]).isZero();
            assertThat(toggle.getText()).isEqualTo("OFF");
            panel.setPresets(List.of(ParameterPreset.factory("On", Map.of(9, 1.0))));
            panel.getPresetComboBox().getSelectionModel().selectFirst();
            assertThat(applied[0]).isOne();
            assertThat(toggle.getText()).isEqualTo("ON");
            return null;
        });
    }

    @Test
    void externalSlotWithNoParametersShowsExplicitPlaceholder() {
        var channel = new MixerChannel("External");
        var slot = new InsertSlot("External processor", new PassThrough());
        channel.addInsert(slot);
        runOnFxThread(() -> {
            var session = PluginEditorSession.open(channel, slot, DEPS);
            try {
                assertThat(session.frame().getBody().getStyleClass()).contains("editor-empty-placeholder");
                assertThat(nodes(session.frame().getBody()).filter(Label.class::isInstance)
                        .map(Label.class::cast).map(Label::getText)).anyMatch(text -> text.contains("exposes no parameters"));
            } finally { session.dispose(); }
            return null;
        });
    }

    @Test
    void frameBypassChangesTheLiveSignalAndReflectsRackChanges() throws Exception {
        var channel = new MixerChannel("Bypass");
        var slot = InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, 2, 48_000);
        channel.addInsert(slot);
        var session = runOnFxThread(() -> {
            var editor = PluginEditorSession.open(channel, slot, DEPS);
            new Scene(new StackPane(editor.frame()), 640, 400);
            editor.store().writeFromUiById(0, 0);
            editor.store().writeFromUiById(1, 1);
            editor.store().writeFromUiById(5, 6);
            return editor;
        });
        try {
            float[][] input = { {0.1f}, {0.1f} };
            float[][] output = new float[2][1];
            channel.getEffectsChain().process(input, output, 1);
            assertThat(output[0][0]).isGreaterThan(0.15f);
            runOnFxThread(() -> { session.frame().setBypassed(true); return null; });
            channel.getEffectsChain().process(input, output, 1);
            assertThat(slot.isBypassed()).isTrue();
            assertThat(output[0][0]).isEqualTo(0.1f);
            channel.setInsertBypassed(0, false);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (runOnFxThread(() -> session.frame().isBypassed()) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(runOnFxThread(() -> session.frame().isBypassed())).isFalse();
        } finally {
            runOnFxThread(() -> { session.dispose(); return null; });
        }
    }

    @Test
    void declarativeFactoryParametersDriveTheSameSlotWhenPluginParameterListIsEmpty() {
        var processor = new CompressorProcessor(2, 48_000);
        processor.setThresholdDb(0);
        processor.setRatio(1);
        DawPlugin plugin = declaredGainPlugin(processor, List.of(), List.of(new PluginParameter(42, "Gain", 0, 12, 0)));
        var channel = new MixerChannel("External gain");
        var slot = InsertEffectFactory.createSlotFromPlugin(plugin).orElseThrow();
        channel.addInsert(slot);
        runOnFxThread(() -> {
            var session = PluginEditorSession.open(channel, slot, DEPS);
            try {
                assertThat(session.store().parameterCount()).isOne();
                var knob = nodes(session.frame().getBody()).filter(Knob.class::isInstance)
                        .map(Knob.class::cast).findFirst().orElseThrow();
                knob.setValue(6);
                assertThat(processor.getMakeupGainDb()).isZero();
            } finally { session.dispose(); }
            var originalStore = slot.getParameterStore();
            var reopened = PluginEditorSession.open(channel, slot, DEPS);
            try {
                assertThat(reopened.store()).isSameAs(originalStore);
                assertThat(reopened.store().valueById(42)).isEqualTo(6);
            } finally { reopened.dispose(); }
            return null;
        });
        float[][] input = {{0.1f}, {0.1f}};
        float[][] output = new float[2][1];
        channel.getEffectsChain().process(input, output, 1);
        assertThat(output[0][0]).isGreaterThan(0.19f);
    }

    @Test
    void factoryMetadataReconciliationPreservesPendingValuesByIdAndReopenKeepsItsStore() {
        var processor = new CompressorProcessor(2, 48_000);
        var gain = new PluginParameter(42, "Gain", 0, 12, 0);
        var ratio = new PluginParameter(99, "Ratio", 1, 20, 1);
        DawPlugin plugin = declaredGainPlugin(processor, List.of(gain), List.of(ratio, gain));
        var slot = InsertEffectFactory.createSlotFromPlugin(plugin).orElseThrow();
        var channel = new MixerChannel("Factory metadata");
        channel.addInsert(slot);
        runOnFxThread(() -> {
            slot.getParameterStore().writeFromUiById(42, 3);
            var session = PluginEditorSession.open(channel, slot, DEPS);
            try {
                assertThat(session.store().parameterCount()).isEqualTo(2);
                assertThat(session.store().parameterIdAt(0)).isEqualTo(99);
                assertThat(session.store().valueById(42)).isEqualTo(3);
                assertThat(session.store().valueById(99)).isOne();
                var ratioKnob = nodes(session.frame().getBody()).filter(Knob.class::isInstance)
                        .map(Knob.class::cast).findFirst().orElseThrow();
                ratioKnob.setValue(2);
            } finally { session.dispose(); }
            var store = slot.getParameterStore();
            var reopened = PluginEditorSession.open(channel, slot, DEPS);
            try {
                assertThat(reopened.store()).isSameAs(store);
                assertThat(reopened.store().valueById(42)).isEqualTo(3);
                assertThat(reopened.store().valueById(99)).isEqualTo(2);
            } finally { reopened.dispose(); }
            return null;
        });
        render(channel);
        assertThat(processor.getMakeupGainDb()).isEqualTo(3);
        assertThat(processor.getRatio()).isEqualTo(2);
    }

    @Test
    void factoryPresetAffectsLiveProcessorAndSavedPresetReturnsOnReopen() throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", directory.toString());
        try {
            var channel = new MixerChannel("Vocals");
            var slot = InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, 2, 48_000);
            channel.addInsert(slot);
            var session = runOnFxThread(() -> PluginEditorSession.open(channel, slot, DEPS));
            session.presetOperation().get(10, TimeUnit.SECONDS);
            runOnFxThread(() -> {
                assertThat(session.frame().getPresetItems()).contains("Gentle Vocal Compression");
                session.frame().getOnPresetSelected().accept("Gentle Vocal Compression");
                return null;
            });
            render(channel);
            assertThat(((CompressorProcessor) slot.getProcessor()).getThresholdDb()).isEqualTo(-24);
            runOnFxThread(() -> {
                session.store().writeFromUiById(0, -33);
                session.savePreset("My Vocal");
                return null;
            });
            session.presetOperation().get(10, TimeUnit.SECONDS);
            try (Stream<Path> files = Files.walk(directory)) {
                assertThat(files.filter(path -> path.toString().endsWith(".json")).count()).isOne();
            }
            var reopened = runOnFxThread(() -> {
                session.dispose();
                return PluginEditorSession.open(channel, slot, DEPS);
            });
            reopened.presetOperation().get(10, TimeUnit.SECONDS);
            runOnFxThread(() -> {
                try {
                    assertThat(reopened.frame().getPresetItems()).contains("My Vocal");
                    reopened.frame().getOnPresetSelected().accept("My Vocal");
                    render(channel);
                    assertThat(((CompressorProcessor) slot.getProcessor()).getThresholdDb()).isEqualTo(-33);
                } finally { reopened.dispose(); }
                return null;
            });
        } finally { System.setProperty("user.home", previousHome); }
    }

    @Test
    void collidingPresetNameReportsFailureAndPreservesTheSavedPresetOnReopen() throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", directory.toString());
        try {
            var channel = new MixerChannel("Vocals");
            var slot = InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, 2, 48_000);
            channel.addInsert(slot);
            var notifications = new java.util.ArrayList<String>();
            var deps = new PluginEditorSession.Deps(() -> 48_000, null, null,
                    (_, message) -> notifications.add(message));
            var session = runOnFxThread(() -> PluginEditorSession.open(channel, slot, deps));
            try {
                session.presetOperation().get(10, TimeUnit.SECONDS);
                runOnFxThread(() -> {
                    session.store().writeFromUiById(0, -33);
                    session.savePreset("Lead/Vocal");
                    return null;
                });
                session.presetOperation().get(10, TimeUnit.SECONDS);
                runOnFxThread(() -> {
                    session.store().writeFromUiById(0, -12);
                    session.savePreset("Lead Vocal");
                    return null;
                });
                assertThatThrownBy(() -> session.presetOperation().get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(FileAlreadyExistsException.class);
                runOnFxThread(() -> {
                    assertThat(notifications).singleElement().asString().contains("Lead/Vocal");
                    assertThat(session.frame().getPresetItems()).contains("Lead/Vocal").doesNotContain("Lead Vocal");
                    assertThat(session.frame().getSelectedPreset()).isEqualTo("Lead/Vocal");
                    return null;
                });
            } finally { runOnFxThread(() -> { session.dispose(); return null; }); }

            var reopened = runOnFxThread(() -> PluginEditorSession.open(channel, slot, DEPS));
            try {
                reopened.presetOperation().get(10, TimeUnit.SECONDS);
                runOnFxThread(() -> {
                    assertThat(reopened.frame().getPresetItems()).contains("Lead/Vocal").doesNotContain("Lead Vocal");
                    reopened.frame().getOnPresetSelected().accept("Lead/Vocal");
                    return null;
                });
                render(channel);
                assertThat(((CompressorProcessor) slot.getProcessor()).getThresholdDb()).isEqualTo(-33);
            } finally { runOnFxThread(() -> { reopened.dispose(); return null; }); }
        } finally { System.setProperty("user.home", previousHome); }
    }

    @Test
    void themedOwnedSlotEditorProducesReviewSnapshot() throws Exception {
        var channel = new MixerChannel("Drum Bus");
        var slot = InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, 2, 48_000);
        channel.addInsert(slot);
        WritableImage image = runOnFxThread(() -> {
            var session = PluginEditorSession.open(channel, slot, DEPS);
            var owner = new Stage();
            var window = new Stage();
            try {
                owner.setScene(new Scene(new StackPane(), 100, 100));
                window.initOwner(owner);
                var scene = new Scene(new StackPane(session.frame()), 640, 420);
                window.setScene(scene);
                ThemeManager.getDefault().applyTo(scene);
                scene.getRoot().resize(640, 420);
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                scene.snapshot(new WritableImage(640, 420));
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                assertThat(window.getOwner()).isSameAs(owner);
                assertThat(scene.getStylesheets()).isNotEmpty();
                var skin = (EditorFrameSkin) session.frame().getSkin();
                assertThat(skin.header().localToScene(skin.header().getBoundsInLocal()).getMinY()).isGreaterThanOrEqualTo(0);
                assertThat(skin.footer().localToScene(skin.footer().getBoundsInLocal()).getMaxY()).isLessThanOrEqualTo(420);
                return scene.snapshot(new WritableImage(640, 420));
            } finally {
                session.dispose();
                window.close();
                owner.close();
            }
        });
        Path output = Path.of("target", "story320", "live-compressor-editor.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(FxSnapshotTest.toBufferedImage(image), "png", output.toFile());
    }

    private static void render(MixerChannel channel) {
        channel.getEffectsChain().process(new float[2][64], new float[2][64], 64);
    }

    private static DawPlugin declaredGainPlugin(CompressorProcessor processor,
            List<PluginParameter> legacy, List<PluginParameter> declared) {
        return new DawPlugin() {
            @Override public PluginDescriptor getDescriptor() {
                return new PluginDescriptor("test.declarative-only", "Declared gain", "1", "Test", PluginType.EFFECT);
            }
            @Override public void initialize(PluginContext context) { }
            @Override public void activate() { }
            @Override public void deactivate() { }
            @Override public void dispose() { }
            @Override public java.util.Optional<AudioProcessor> asAudioProcessor() { return java.util.Optional.of(processor); }
            @Override public List<PluginParameter> getParameters() { return legacy; }
            @Override public PluginEditorFactory editorFactory() {
                assertThat(javafx.application.Platform.isFxApplicationThread()).isTrue();
                return new PluginEditorFactory.Declarative(declared, EditorHints.standard());
            }
            @Override public void setAutomatableParameter(int id, double value) {
                if (id == 42) processor.setMakeupGainDb(value);
                else if (id == 99) processor.setRatio(value);
            }
        };
    }

    private static Stream<Node> nodes(Node node) {
        return node instanceof Parent parent
                ? Stream.concat(Stream.of(node), parent.getChildrenUnmodifiable().stream().flatMap(PluginEditorSignalPathTest::nodes))
                : Stream.of(node);
    }

    private static PluginContext context() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 48_000; }
            @Override public int getBufferSize() { return 64; }
            @Override public void log(String message) { }
        };
    }

    private static final class PassThrough implements AudioProcessor {
        @Override public void process(float[][] input, float[][] output, int frames) {
            for (int channel = 0; channel < input.length; channel++) System.arraycopy(input[channel], 0, output[channel], 0, frames);
        }
        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
    }
}
