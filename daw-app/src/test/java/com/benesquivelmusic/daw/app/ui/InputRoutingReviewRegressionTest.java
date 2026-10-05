package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.InputMeterStrip;
import com.benesquivelmusic.daw.app.ui.display.MiniClipIndicator;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.TrackControlWiring;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleArmCommand;
import com.benesquivelmusic.daw.core.analysis.InputLevelMonitorRegistry;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.sdk.audio.*;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Tooltip;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class InputRoutingReviewRegressionTest {
    private static final AudioFormat FORMAT = new AudioFormat(48000, 2, 16, 256);

    @Test
    void anExplicitArmCannotMoveAnAcceptedWideDefaultRouteToANarrowBackend() throws Exception {
        DawProject project = new DawProject("Union", FORMAT);
        var wide = project.createAudioTrack("Wide default");
        wide.setInputRouting(new InputRouting(6, 2));
        var explicit = project.createAudioTrack("Pinned head");
        explicit.setInputDeviceIndex(0);
        WidthBackend head = new WidthBackend(2), fallback = new WidthBackend(8);
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(new StreamingProvision(head.name(), List.of(
                new BackendStreamRung(head, DeviceId.defaultFor(head.name())),
                new BackendStreamRung(fallback, DeviceId.defaultFor(fallback.name())))));
        FxDispatcher dispatcher = new FxDispatcher();
        AtomicReference<TrackControlWiring> wiring = new AtomicReference<>();
        CountDownLatch firstAccepted = new CountDownLatch(1), refused = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        AtomicInteger acceptedExplicit = new AtomicInteger();
        try {
            ArrangementStripFixture.onFx(() -> {
                wiring.set(TrackControlWiring.standalone(project, dispatcher, null, engine, message -> {
                    error.set(message); refused.countDown();
                }));
                wide.addChangeListener(kind -> { if (wide.isArmed()) firstAccepted.countDown(); });
                explicit.addChangeListener(kind -> { if (explicit.isArmed()) acceptedExplicit.incrementAndGet(); });
                wiring.get().commandSink().accept(new ToggleArmCommand(wide, true));
            });
            assertThat(firstAccepted.await(5, TimeUnit.SECONDS)).isTrue();
            ArrangementStripFixture.onFx(() -> wiring.get().commandSink().accept(new ToggleArmCommand(explicit, true)));
            assertThat(refused.await(5, TimeUnit.SECONDS)).isTrue();
            ArrangementStripFixture.onFx(() -> {
                assertThat(wide.isArmed()).isTrue();
                assertThat(explicit.isArmed()).isFalse();
            });
            assertThat(acceptedExplicit.get()).isZero();
            assertThat(error.get()).contains("Wide default", "Interface", "2 input channels");
        } finally {
            ArrangementStripFixture.onFx(() -> { if (wiring.get() != null) wiring.get().dispose(); dispatcher.dispose(); });
            engine.shutdown();
        }
    }

    @Test
    void assembledMixerAndArrangementKeepTheirControlsRoutingTooltips() throws Exception {
        ArrangementStripFixture.onFx(() -> {
            DawProject project = new DawProject("Tooltips", FORMAT);
            var track = project.createAudioTrack("Vox");
            track.setArmed(true);
            var registry = new InputLevelMonitorRegistry();
            var monitor = registry.getOrCreate(track);
            monitor.setRoutingDescription("Track 'Vox', device 'Interface': Input 7 unavailable; input is silent");
            monitor.setRoutingUnavailable(true);
            var rig = new ArrangementStripFixture(project, false);
            try {
                rig.mixerView.setInputLevelMonitorRegistry(registry);
                rig.mixerView.refresh();
                rig.controller.setInputLevelMonitorRegistry(registry);
                Parent arrangement = rig.addStrip(track);
                List<Node> meters = new ArrayList<>();
                collectMeters(rig.mixerView.getChannelStrips(), meters);
                collectMeters(arrangement, meters);
                assertThat(meters).hasSize(2);
                for (Node meter : meters) {
                    Tooltip installed = installedTooltip(meter);
                    assertThat(installed.getText()).contains("Vox", "Interface", "unavailable", "silent");
                    monitor.setRoutingUnavailable(false);
                    var refresh = meter.getClass().getDeclaredMethod("refreshRoutingState");
                    refresh.setAccessible(true);
                    refresh.invoke(meter);
                    assertThat(installedTooltip(meter)).isSameAs(installed);
                    assertThat(installed.getText()).contains("Click", "reset", "Alt+click resets all");
                    monitor.setRoutingUnavailable(true);
                }
            } finally { rig.close(); }
            return null;
        });
    }

    private static Tooltip installedTooltip(Node meter) {
        var tooltips = meter.getProperties().values().stream().filter(Tooltip.class::isInstance).toList();
        assertThat(tooltips).hasSize(1);
        return (Tooltip) tooltips.getFirst();
    }

    private static void collectMeters(Parent parent, List<Node> found) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof InputMeterStrip || child instanceof MiniClipIndicator) found.add(child);
            else if (child instanceof Parent nested) collectMeters(nested, found);
        }
    }

    private static final class WidthBackend implements AudioBackend {
        private final int width;
        private final MockAudioBackend delegate = new MockAudioBackend();
        WidthBackend(int width) { this.width = width; }
        public String name() { return "Width"; }
        public boolean isAvailable() { return true; }
        public boolean supportsStreaming() { return true; }
        public List<AudioDeviceInfo> listDevices() {
            return List.of(new AudioDeviceInfo(0, "Interface", "Test", width, 2, 48000, List.of(), 0, 0));
        }
        public void open(DeviceId device, com.benesquivelmusic.daw.sdk.audio.AudioFormat format, int frames) { delegate.open(device, format, frames); }
        public boolean isOpen() { return delegate.isOpen(); }
        public Flow.Publisher<AudioBlock> inputBlocks() { return delegate.inputBlocks(); }
        public void sink(AudioBlock block) { delegate.sink(block); }
        public void close() { delegate.close(); }
    }
}
