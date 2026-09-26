package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.LevelMeterDisplay;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.metering.MeterFeed;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.EngineBinder;
import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.dsp.GainStagingProcessor;
import com.benesquivelmusic.daw.core.dsp.ParametricEqProcessor;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageType;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@ExtendWith(JavaFxToolkitExtension.class)
class LiveMasteringViewTest {
    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 24, 512);

    @Test
    void productionNavigationBindsToTheEngineChain() throws Exception {
        try (var fixture = new Fixture()) {
            var preferences = java.util.prefs.Preferences.userRoot().node("liveMastering_" + System.nanoTime());
            try {
                runOnFxThread(() -> {
                    var host = (ViewNavigationController.Host) java.lang.reflect.Proxy.newProxyInstance(
                            getClass().getClassLoader(), new Class<?>[]{ViewNavigationController.Host.class},
                            (_, method, _) -> switch (method.getName()) {
                                case "project" -> fixture.project;
                                case "masteringChain" -> fixture.engine.getMasteringChain();
                                case "engineFormat" -> fixture.engine.getFormat();
                                case "meteringTapBus" -> fixture.engine.meteringTapBus();
                                default -> null;
                            });
                    var root = new javafx.scene.layout.BorderPane(new Label("Arrangement"));
                    var navigation = new ViewNavigationController(root, new Label(),
                            new ToolbarStateStore(preferences), new javafx.scene.control.Button(),
                            DawView.ARRANGEMENT, EditTool.POINTER, true, GridResolution.SIXTEENTH,
                            host, fixture.dispatcher);
                    try {
                        navigation.initializeViewNavigation();
                        assertThat(navigation.getMasteringView().getMasteringChain())
                                .isSameAs(fixture.engine.getMasteringChain());
                        assertThat(MasteringView.class.getConstructors())
                                .noneMatch(constructor -> constructor.getParameterCount() == 0);
                    } finally { navigation.dispose(); }
                    return null;
                });
            } finally { preferences.removeNode(); }
        }
    }

    @Test
    void rapidGesturesCoalesceIndependentlyOfTheOneShotCommandQueue() {
        try (var fixture = new Fixture()) {
            var gain = new GainStagingProcessor(2, 0);
            fixture.engine.getMasteringChain().addStage(MasteringStageType.GAIN_STAGING, "Gain", gain);
            fixture.openMastering();
            runOnFxThread(() -> {
                for (int i = 0; i < 2000; i++) slider(fixture.view, "Gain").setValue(i % 12);
                fixture.view.refresh();
                assertThat(slider(fixture.view, "Gain").getValue()).isEqualTo(1999 % 12);
                return null;
            });
            fixture.renderPeak();
            assertThat(gain.getGainDb()).isEqualTo(1999 % 12);
            int enqueued = 0;
            while (fixture.engine.getMasteringChain().enqueueParameterUpdate(() -> { })) {
                if (++enqueued > 1024) throw new AssertionError("Queue must be bounded");
            }
            runOnFxThread(() -> {
                var slider = slider(fixture.view, "Gain");
                slider.setValue(-12);
                assertThat(slider.getValue()).isEqualTo(-12);
                return null;
            });
            fixture.renderPeak();
            assertThat(gain.getGainDb()).isEqualTo(-12);
        }
    }

    @Test
    void gainKnobAndAbChangeTheEngineOutputWithoutReplacingTheExistingChain() {
        try (var fixture = new Fixture()) {
            var gain = new GainStagingProcessor(2, 0);
            fixture.engine.getMasteringChain().addStage(MasteringStageType.GAIN_STAGING, "Gain", gain);
            fixture.openMastering();
            assertThat(fixture.view.getMasteringChain()).isSameAs(fixture.engine.getMasteringChain());
            assertThat(fixture.view.getMasteringChain().getStages().getFirst().getProcessor()).isSameAs(gain);
            double dry = fixture.renderPeak();
            assertThat(dry).isGreaterThan(0.1);
            runOnFxThread(() -> {
                slider(fixture.view, "Gain").setValue(6);
                return null;
            });
            assertThat(fixture.renderPeak()).isCloseTo(dry * Math.pow(10, 6.0 / 20), within(0.00001));
            assertThat(gain.getGainDb()).isEqualTo(6);
            runOnFxThread(() -> { fixture.view.getAbToggle().fire(); return null; });
            assertThat(fixture.renderPeak()).isCloseTo(dry, within(0.00001));
        }
    }

    @Test
    void masterRackIsLiveSurvivesRefreshAndUsesTheSharedEditorCallback() {
        try (var fixture = new Fixture()) {
            var undo = new UndoManager();
            var opened = new AtomicInteger();
            var mixer = runOnFxThread(() -> {
                var view = new MixerView(fixture.project, undo, fixture.dispatcher);
                view.setOnOpenInsertEditor((channel, slot) -> {
                    assertThat(channel).isSameAs(fixture.project.getMixer().getMasterChannel());
                    opened.incrementAndGet();
                });
                fixture.stage = new Stage();
                fixture.stage.setScene(new Scene(new StackPane(view), 1000, 700));
                fixture.stage.show();
                view.applyCss();
                view.layout();
                return view;
            });
            try {
                double dry = fixture.renderPeak();
                assertThat(dry).isGreaterThan(0.1);
                var rack = runOnFxThread(() -> nodes(mixer).filter(InsertEffectRack.class::isInstance)
                        .map(InsertEffectRack.class::cast)
                        .filter(value -> value.getChannel() == fixture.project.getMixer().getMasterChannel())
                        .findFirst().orElseThrow());
                runOnFxThread(() -> {
                    mixer.refresh();
                    rack.completeExternalInsert(0, new InsertSlot("Master trim", new GainStagingProcessor(2, -6)));
                    return null;
                });
                assertThat(opened).hasValue(1);
                assertThat(fixture.renderPeak()).isCloseTo(dry * Math.pow(10, -6.0 / 20), within(0.00001));
                runOnFxThread(() -> { undo.undo(); return null; });
                assertThat(fixture.renderPeak()).isCloseTo(dry, within(0.00001));
            } finally { runOnFxThread(() -> { mixer.dispose(); return null; }); }
        }
    }

    @Test
    void gainReductionAndStageLevelArriveThroughRegistryAndBecomeIdleAtStop() throws Exception {
        try (var fixture = new Fixture()) {
            var compressor = new CompressorProcessor(2, FORMAT.sampleRate());
            compressor.setThresholdDb(-30);
            compressor.setRatio(8);
            compressor.setAttackMs(0.1);
            fixture.engine.getMasteringChain().addStage(MasteringStageType.COMPRESSION, "Compression", compressor);
            fixture.openMastering();
            for (int i = 0; i < 30; i++) fixture.renderPeak();
            runOnFxThread(() -> {
                fixture.dispatcher.pulse();
                var label = grLabel(fixture.view);
                assertThat(label.getText()).startsWith("GR: -").doesNotContain("---");
                var meter = nodes(fixture.view).filter(LevelMeterDisplay.class::isInstance)
                        .map(LevelMeterDisplay.class::cast).findFirst().orElseThrow();
                assertThat(meter.getPendingPeakDb()).isGreaterThan(-80);
                fixture.project.getTransport().stop();
                return null;
            });
            fixture.renderPeak(); // The device callback may continue while transport is stopped.
            Thread.sleep(250);
            runOnFxThread(() -> {
                fixture.dispatcher.pulse();
                assertThat(grLabel(fixture.view).getText()).isEqualTo("GR: ---");
                var meter = nodes(fixture.view).filter(LevelMeterDisplay.class::isInstance)
                        .map(LevelMeterDisplay.class::cast).findFirst().orElseThrow();
                assertThat(meter.getPendingPeakDb()).isLessThanOrEqualTo(-120);
                fixture.stage.hide();
                fixture.dispatcher.pulse();
                assertThat(fixture.feed.subscriptionCount()).isZero();
                fixture.stage.show();
                fixture.dispatcher.pulse();
                assertThat(fixture.feed.subscriptionCount()).isEqualTo(1);
                return null;
            });
        }
    }

    @Test
    void loudnessReceivesRealAnalysisAndClearsAfterStop() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.openMastering();
            for (int i = 0; i < 140; i++) {
                fixture.renderPeak();
                Thread.sleep(2);
                if (i % 20 == 0) runOnFxThread(() -> { fixture.dispatcher.pulse(); return null; });
            }
            Thread.sleep(80);
            runOnFxThread(() -> {
                fixture.dispatcher.pulse();
                assertThat(fixture.view.getLoudnessDisplay().getAccessibleText()).doesNotContain("---", "Infinity");
                fixture.project.getTransport().stop();
                return null;
            });
            float[][] monitored = new float[2][FORMAT.bufferSize()];
            java.util.Arrays.fill(monitored[0], 0.25f);
            java.util.Arrays.fill(monitored[1], 0.25f);
            for (int i = 0; i < 50; i++) {
                fixture.engine.processBlock(monitored, fixture.output, FORMAT.bufferSize(), fixture.interleaved);
            }
            Thread.sleep(550);
            runOnFxThread(() -> {
                fixture.dispatcher.pulse();
                assertThat(fixture.view.getLoudnessDisplay().getAccessibleText()).contains("---");
                return null;
            });
        }
    }

    @Test
    void presetProcessorsFollowStereoMasteringAcrossMonoAndMultichannelDevices() {
        for (int channels : new int[]{1, 4}) {
            var format = new AudioFormat(96_000, channels, 24, 512);
            var engine = new AudioEngine(format);
            var view = runOnFxThread(() -> new MasteringView(engine.getMasteringChain(), engine::getFormat));
            try {
                runOnFxThread(() -> {
                    view.getPresetSelector().getSelectionModel().select(1);
                    assertThat(engine.getMasteringChain().getStages()).hasSize(7)
                            .allMatch(stage -> stage.getProcessor().getInputChannelCount() == 2);
                    return null;
                });
                engine.start();
                var input = new float[channels][512];
                var output = new float[channels][512];
                java.util.Arrays.fill(input[0], 0.2f);
                engine.processBlock(input, output, 512);
                for (float sample : output[0]) assertThat(Float.isFinite(sample)).isTrue();
            } finally {
                runOnFxThread(() -> { view.dispose(); return null; });
                engine.shutdown();
            }
        }
    }

    @Test
    void presetAndEqControlsUseLiveStagesAndRemainScrollableAtSmallSizes() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.openMastering();
            runOnFxThread(() -> {
                fixture.view.getPresetSelector().getSelectionModel().select(1);
                assertThat(fixture.engine.getMasteringChain().size()).isEqualTo(7);
                var eqStage = fixture.engine.getMasteringChain().getStages().get(1);
                var original = eqStage.getProcessor();
                slider(fixture.view, "Frequency 1").setValue(120);
                assertThat(eqStage.getProcessor()).isNotSameAs(original);
                assertThat(((ParametricEqProcessor) eqStage.getProcessor()).getBands().getFirst().frequency()).isEqualTo(120);
                assertThat(fixture.engine.getMasteringChain().getStages().getLast().isTerminal()).isTrue();
                var scroll = nodes(fixture.view).filter(ScrollPane.class::isInstance)
                        .map(ScrollPane.class::cast).findFirst().orElseThrow();
                assertThat(scroll.getVbarPolicy()).isEqualTo(ScrollPane.ScrollBarPolicy.AS_NEEDED);
                return null;
            });
            capture(fixture, 800, 600);
            capture(fixture, 1280, 900);
        }
    }

    private static void capture(Fixture fixture, int width, int height) throws Exception {
        BufferedImage capture = runOnFxThread(() -> {
            var previousRoot = (StackPane) fixture.view.getParent();
            previousRoot.getChildren().remove(fixture.view);
            var root = new StackPane(fixture.view);
            root.setMinSize(width, height);
            root.setPrefSize(width, height);
            root.setMaxSize(width, height);
            root.getStyleClass().add("root-pane");
            try {
                var scene = new Scene(root, width, height);
                ThemeManager.getDefault().applyTo(scene);
                root.applyCss();
                root.layout();
                scene.snapshot(new WritableImage(width, height));
                root.applyCss();
                root.layout();
                assertThat(root.getWidth()).isEqualTo(width);
                assertThat(root.getHeight()).isEqualTo(height);
                var scroll = nodes(fixture.view).filter(ScrollPane.class::isInstance)
                        .map(ScrollPane.class::cast).findFirst().orElseThrow();
                scroll.setVvalue(1);
                root.layout();
                var viewport = scroll.lookup(".viewport");
                var viewportBounds = viewport.localToScene(viewport.getBoundsInLocal());
                var compressorCard = fixture.view.getStageContainer().getChildren().get(4);
                var bypass = nodes(compressorCard).filter(javafx.scene.control.Button.class::isInstance)
                        .map(javafx.scene.control.Button.class::cast)
                        .filter(button -> button.getText().equals("Bypass")).findFirst().orElseThrow();
                var bypassBounds = bypass.localToScene(bypass.getBoundsInLocal());
                assertThat(bypassBounds.getMinY()).isGreaterThanOrEqualTo(viewportBounds.getMinY());
                assertThat(bypassBounds.getMaxY()).isLessThanOrEqualTo(viewportBounds.getMaxY() + 1);
                scroll.setVvalue(0);
                root.layout();
                var image = scene.snapshot(new WritableImage(width, height));
                var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    result.setRGB(x, y, image.getPixelReader().getArgb(x, y));
                }
                return result;
            } finally {
                root.getChildren().remove(fixture.view);
                previousRoot.getChildren().add(fixture.view);
            }
        });
        Path directory = Path.of("target", "story321");
        Files.createDirectories(directory);
        ImageIO.write(capture, "png", directory.resolve("mastering-" + width + "x" + height + ".png").toFile());
    }

    private static Slider slider(Node root, String name) {
        return nodes(root).filter(Slider.class::isInstance).map(Slider.class::cast)
                .filter(value -> name.equals(value.getAccessibleText())).findFirst().orElseThrow();
    }

    private static Label grLabel(Node root) {
        return nodes(root).filter(Label.class::isInstance).map(Label.class::cast)
                .filter(value -> value.getText().startsWith("GR:")).findFirst().orElseThrow();
    }

    private static Stream<Node> nodes(Node root) {
        return root instanceof Parent parent
                ? Stream.concat(Stream.of(root), parent.getChildrenUnmodifiable().stream().flatMap(LiveMasteringViewTest::nodes))
                : Stream.of(root);
    }

    private static final class Fixture implements AutoCloseable {
        final DawProject project = new DawProject("Live mastering", FORMAT);
        final AudioEngine engine = new AudioEngine(FORMAT);
        final EngineBinder binder = new EngineBinder(engine);
        final FxDispatcher dispatcher = new FxDispatcher();
        final float[][] output = new float[2][FORMAT.bufferSize()];
        final float[] interleaved = new float[2 * FORMAT.bufferSize()];
        MeterFeed feed;
        MasteringView view;
        Stage stage;

        Fixture() {
            var track = project.createAudioTrack("Programme");
            int frames = 10 * (int) FORMAT.sampleRate();
            float[][] data = new float[2][frames];
            for (int frame = 0; frame < frames; frame++) {
                data[0][frame] = data[1][frame] = (float) (0.5 * Math.sin(2 * Math.PI * 750 * frame / FORMAT.sampleRate()));
            }
            var clip = new AudioClip("Sine", 0, 20, null);
            clip.setAudioData(data);
            track.addClip(clip);
            project.getTransport().setTempo(120);
            binder.bind(project);
            engine.start();
            project.getTransport().play();
        }

        void openMastering() {
            runOnFxThread(() -> {
                feed = new MeterFeed(engine.meteringTapBus(), dispatcher);
                view = new MasteringView(engine.getMasteringChain(), engine::getFormat);
                view.bindMeters(feed, engine.meteringTapBus(), dispatcher, () -> {
                    var state = project.getTransport().getState();
                    return state == com.benesquivelmusic.daw.core.transport.TransportState.PLAYING
                            || state == com.benesquivelmusic.daw.core.transport.TransportState.RECORDING;
                });
                stage = new Stage();
                var root = new StackPane(view);
                root.getStyleClass().add("root-pane");
                stage.setScene(new Scene(root, 800, 600));
                ThemeManager.getDefault().applyTo(stage.getScene());
                stage.show();
                view.applyCss();
                view.layout();
                dispatcher.pulse();
                return null;
            });
        }

        double renderPeak() {
            engine.processBlock(null, output, FORMAT.bufferSize(), interleaved);
            double peak = 0;
            for (float value : output[0]) peak = Math.max(peak, Math.abs(value));
            return peak;
        }

        @Override public void close() {
            runOnFxThread(() -> {
                if (view != null) view.dispose();
                if (feed != null) feed.dispose();
                if (stage != null) stage.close();
                dispatcher.dispose();
                return null;
            });
            binder.unbind();
            engine.shutdown();
        }
    }
}
