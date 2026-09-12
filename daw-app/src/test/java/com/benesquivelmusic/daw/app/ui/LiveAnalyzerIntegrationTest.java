package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.metering.AnalyzerFeeds;
import com.benesquivelmusic.daw.app.ui.plugin.PluginEditorSession;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.metering.LevelTapSlot;
import com.benesquivelmusic.daw.core.metering.MeterTapPoint;
import com.benesquivelmusic.daw.core.metering.MeteringTapBus;
import com.benesquivelmusic.daw.core.metering.TapSnapshot;
import com.benesquivelmusic.daw.core.plugin.LiveAnalyzerPlugin;
import com.benesquivelmusic.daw.core.plugin.SoundWaveTelemetryPlugin;
import com.benesquivelmusic.daw.core.plugin.SpectrumAnalyzerPlugin;
import com.benesquivelmusic.daw.core.plugin.TunerPlugin;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.InputMonitoringMode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.sdk.analysis.WindowType;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@ExtendWith(JavaFxToolkitExtension.class)
class LiveAnalyzerIntegrationTest {
    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 24, 512);

    @ParameterizedTest
    @ValueSource(strings = {"spectrum", "tuner", "telemetry"})
    void utilityAndInsertedEditorsReceiveTheirOwnTapAndDisposeWithTheRack(String name) throws Exception {
        Fixture fixture = onFx(() -> new Fixture(name));
        try {
            onFx(() -> {
                fixture.session = PluginEditorSession.open(fixture.plugin, null,
                        new PluginEditorSession.Deps(() -> 48_000, null, null, null));
                fixture.session.bindAnalyzer(fixture.feeds);
                fixture.root.getChildren().add(fixture.session.frame());
                fixture.dispatcher.pulse();
                return null;
            });
            fixture.render(4096);
            awaitFx(fixture, () -> fixture.hasData() && fixture.tunerLabelShows(false));
            onFx(() -> {
                fixture.assertSource(false);
                if (!(fixture.plugin instanceof TunerPlugin)) {
                    assertThat(java.util.Arrays.equals(fixture.idleEditorPixels, fixture.editorPixels()))
                            .as("the plugin canvas renders real feed data instead of its idle state").isFalse();
                }
                fixture.session.dispose();
                fixture.root.getChildren().remove(fixture.session.frame());
                assertThat(fixture.bus.analysisSubscriptionCount()).isZero();
                assertThat(fixture.rack.insertPlugin(0, fixture.plugin)).isTrue();
                fixture.dispatcher.pulse();
                fixture.dispatcher.pulse();
                assertThat(fixture.bus.snapshot().masterChain()).isNull();
                assertThat(fixture.bus.snapshot().channelSlot(0, fixture.channel())).isNotNull();
                fixture.rack.openParameterEditor(fixture.channel().getInsertSlots().getFirst());
                return null;
            });
            fixture.render(4096);
            awaitFx(fixture, () -> fixture.hasData() && fixture.tunerLabelShows(true));
            onFx(() -> {
                fixture.assertSource(true);
                Stage editor = (Stage) Window.getWindows().stream()
                        .filter(window -> window instanceof Stage stage && stage.getOwner() == fixture.stage)
                        .findFirst().orElseThrow();
                assertThat(editor.getScene().getStylesheets()).containsExactlyElementsOf(fixture.stage.getScene().getStylesheets());
                fixture.channel().removeInsert(0);
                fixture.dispatcher.pulse();
                assertThat(editor.isShowing()).isFalse();
                assertThat(fixture.bus.analysisSubscriptionCount()).isZero();
                assertThat(fixture.hasData()).isFalse();
                fixture.rack.insertPlugin(0, fixture.plugin);
                fixture.rack.openParameterEditor(fixture.channel().getInsertSlots().getFirst());
                fixture.rack.dispose();
                assertThat(Window.getWindows().stream().noneMatch(window -> window instanceof Stage stage
                        && stage.getOwner() == fixture.stage && stage.isShowing())).isTrue();
                return null;
            });
        } finally { onFx(() -> { fixture.close(); return null; }); }
    }

    @Test
    void utilitySpectrumReconfiguresAndRebindsFormatWhileOpenAndDeactivationStaysIdle() throws Exception {
        Fixture fixture = onFx(() -> new Fixture("spectrum"));
        try {
            onFx(() -> {
                fixture.session = PluginEditorSession.open(fixture.plugin, null,
                        new PluginEditorSession.Deps(() -> 48_000, null, null, null));
                fixture.session.bindAnalyzer(fixture.feeds);
                fixture.root.getChildren().add(fixture.session.frame());
                fixture.dispatcher.pulse();
                return null;
            });
            fixture.render(4096);
            awaitFx(fixture, fixture::hasData);
            onFx(() -> {
                ((SpectrumAnalyzerPlugin) fixture.plugin).reconfigure(2048, WindowType.HAMMING);
                fixture.dispatcher.pulse();
                fixture.bus.refreshSlots(new AudioFormat(96_000, 2, 24, 512));
                fixture.dispatcher.pulse();
                return null;
            });
            fixture.render(4096);
            awaitFx(fixture, fixture::hasData);
            onFx(() -> {
                var spectrum = (SpectrumAnalyzerPlugin) fixture.plugin;
                assertThat(spectrum.getLatestSpectrum().fftSize()).isEqualTo(2048);
                assertThat(spectrum.getLatestSpectrum().sampleRate()).isEqualTo(96_000);
                spectrum.deactivate();
                fixture.dispatcher.pulse();
                assertThat(spectrum.getLatestSpectrum()).isNull();
                assertThat(fixture.bus.analysisSubscriptionCount()).isZero();
                return null;
            });
        } finally { onFx(() -> { fixture.close(); return null; }); }
    }

    @Test
    void tunerFollowsMonitoredArmedTrackAndNeverFallsBackToMaster() {
        var project = new DawProject("Tuner", FORMAT);
        var first = new Track("First", TrackType.AUDIO);
        var second = new Track("Second", TrackType.AUDIO);
        project.addTrack(first);
        project.addTrack(second);
        var bus = new MeteringTapBus();
        try (var feeds = new AnalyzerFeeds(bus, new FxDispatcher(), () -> project)) {
            assertThat(feeds.tunerPoint()).isNull();
            first.setArmed(true);
            first.setInputMonitoringMode(InputMonitoringMode.OFF);
            second.setArmed(true);
            second.setInputMonitoringMode(InputMonitoringMode.ALWAYS);
            assertThat(feeds.tunerPoint()).isEqualTo(new MeterTapPoint.ChannelPost(project.getMixerChannelForTrack(second).getId()));
            second.setArmed(false);
            assertThat(feeds.tunerPoint()).isEqualTo(new MeterTapPoint.ChannelPost(project.getMixerChannelForTrack(first).getId()));
            first.setArmed(false);
            assertThat(feeds.tunerPoint()).isNull();
        } finally { bus.close(); }
    }

    @Test
    void utilityAttachmentFollowsAncestorAndFloatingWindowVisibility() throws Exception {
        Fixture fixture = onFx(() -> new Fixture("spectrum"));
        try {
            onFx(() -> {
                fixture.session = PluginEditorSession.open(fixture.plugin, null,
                        new PluginEditorSession.Deps(() -> 48_000, null, null, null));
                fixture.session.bindAnalyzer(fixture.feeds);
                fixture.root.getChildren().add(fixture.session.frame());
                fixture.dispatcher.pulse();
                assertThat(fixture.bus.analysisSubscriptionCount()).isEqualTo(1);
                fixture.root.setVisible(false);
                fixture.dispatcher.pulse();
                assertThat(fixture.bus.analysisSubscriptionCount()).isZero();
                fixture.root.setVisible(true);
                fixture.dispatcher.pulse();
                assertThat(fixture.bus.analysisSubscriptionCount()).isEqualTo(1);
                fixture.stage.hide();
                fixture.dispatcher.pulse();
                assertThat(fixture.bus.analysisSubscriptionCount()).isZero();
                fixture.stage.show();
                fixture.dispatcher.pulse();
                assertThat(fixture.bus.analysisSubscriptionCount()).isEqualTo(1);
                assertThat(fixture.hasData()).isFalse();
                return null;
            });
        } finally { onFx(() -> { fixture.close(); return null; }); }
    }

    private static final class Fixture implements AutoCloseable {
        final MeteringTapBus bus = new MeteringTapBus();
        final FxDispatcher dispatcher = new FxDispatcher();
        final FxDispatcher previous = FxDispatcher.getDefault();
        final DawProject project = new DawProject("Analyzers", FORMAT);
        final Track track = new Track("Host", TrackType.AUDIO);
        final VBox root = new VBox();
        final Stage stage = new Stage();
        final LiveAnalyzerPlugin plugin;
        final AnalyzerFeeds feeds;
        final InsertEffectRack rack;
        PluginEditorSession session;
        final int[] idleEditorPixels;

        Fixture(String type) {
            FxDispatcher.installDefault(dispatcher);
            project.addTrack(track);
            bus.rebind(project.getMixer(), FORMAT, 1);
            feeds = new AnalyzerFeeds(bus, dispatcher, () -> project);
            plugin = switch (type) {
                case "spectrum" -> new SpectrumAnalyzerPlugin();
                case "tuner" -> new TunerPlugin();
                default -> new SoundWaveTelemetryPlugin();
            };
            plugin.initialize(new PluginContext() {
                public double getSampleRate() { return 48_000; }
                public int getBufferSize() { return 512; }
                public void log(String message) { }
            });
            plugin.activate();
            idleEditorPixels = plugin instanceof TunerPlugin ? null : editorPixels();
            rack = new InsertEffectRack(channel(), 2, 48_000, 512, null, dispatcher);
            root.getChildren().add(rack);
            stage.setScene(new Scene(root, 640, 600));
            stage.show();
        }

        com.benesquivelmusic.daw.core.mixer.MixerChannel channel() { return project.getMixerChannelForTrack(track); }

        void render(int frames) {
            for (int offset = 0; offset < frames; offset += 512) {
                TapSnapshot taps = bus.snapshot();
                publish(taps.channelSlot(0, channel()), taps, offset, true);
                publish(taps.masterChain(), taps, offset, false);
                bus.blockCompleted(taps);
            }
        }

        void publish(LevelTapSlot slot, TapSnapshot taps, int offset, boolean host) {
            if (slot == null) return;
            float[] audio = new float[512];
            double frequency = plugin instanceof TunerPlugin ? (host ? 440 : 880) : (host ? 375 : 750);
            for (int i = 0; i < audio.length; i++) audio[i] = plugin instanceof SoundWaveTelemetryPlugin
                    ? (host ? 0.25f : 0.75f) : (float) (0.5 * Math.sin(2 * Math.PI * frequency * (offset + i) / 48_000));
            slot.beginBlock(taps.epoch(), taps.blockIndex(), 2);
            slot.accumulate(0, audio, 512);
            slot.accumulate(1, audio, 512);
            slot.publish(512);
            for (var ring : slot.rings()) ring.write(new float[][]{audio, audio}, 2, 512);
        }

        boolean hasData() {
            if (plugin instanceof SpectrumAnalyzerPlugin spectrum) return spectrum.getLatestSpectrum() != null;
            if (plugin instanceof TunerPlugin tuner) return tuner.getLastResult() != null;
            return ((SoundWaveTelemetryPlugin) plugin).getWaveform() != null;
        }

        boolean tunerLabelShows(boolean host) {
            if (!(plugin instanceof TunerPlugin)) return true;
            var editorRoot = host ? Window.getWindows().stream()
                    .filter(window -> window instanceof Stage child && child.getOwner() == stage)
                    .findFirst().orElseThrow().getScene().getRoot() : session.frame();
            return editorRoot.lookupAll(".label").stream().anyMatch(node -> node instanceof javafx.scene.control.Label label
                    && label.getText().equals(host ? "A4" : "A5"));
        }

        int[] editorPixels() {
            var canvas = new javafx.scene.canvas.Canvas(480, 300);
            var editor = (com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory.Canvas) plugin.editorFactory();
            editor.attach(new com.benesquivelmusic.daw.sdk.editor.CanvasSurface() {
                public double width() { return 480; }
                public double height() { return 300; }
                public javafx.scene.canvas.GraphicsContext graphicsContext() { return canvas.getGraphicsContext2D(); }
                public void requestRender() { }
            });
            editor.render(new com.benesquivelmusic.daw.sdk.editor.RenderTick(0, 0,
                    com.benesquivelmusic.daw.sdk.editor.Theme.neutral()));
            var image = canvas.snapshot(null, null);
            int[] pixels = new int[480 * 300];
            image.getPixelReader().getPixels(0, 0, 480, 300,
                    javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, 480);
            editor.detach();
            return pixels;
        }

        void assertSource(boolean host) {
            if (plugin instanceof TunerPlugin tuner) assertThat(tuner.getLastResult().octave()).isEqualTo(host ? 4 : 5);
            else if (plugin instanceof SoundWaveTelemetryPlugin telemetry) {
                assertThat(telemetry.getWaveform().maxValues()[0]).isCloseTo(host ? 0.25f : 0.75f, within(1e-6f));
            } else {
                float[] bins = ((SpectrumAnalyzerPlugin) plugin).getLatestSpectrum().magnitudesDb();
                int peak = 0;
                for (int i = 1; i < bins.length; i++) if (bins[i] > bins[peak]) peak = i;
                assertThat(peak).isEqualTo(host ? 32 : 64);
            }
        }

        @Override public void close() {
            if (session != null) session.dispose();
            rack.dispose();
            feeds.close();
            bus.close();
            stage.close();
            plugin.dispose();
            dispatcher.dispose();
            FxDispatcher.installDefault(previous);
        }
    }

    private static void awaitFx(Fixture fixture, BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (onFx(() -> { fixture.dispatcher.pulse(); return ready.getAsBoolean(); })) return;
            Thread.sleep(5);
        }
        throw new AssertionError("No analyzer snapshot reached the editor");
    }

    static <T> T onFx(Callable<T> action) throws Exception {
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        var done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { result.set(action.call()); }
            catch (Throwable error) { failure.set(error); }
            finally { done.countDown(); }
        });
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }
}
