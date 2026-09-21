package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.project.DawProject;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
                        (_, _, _) -> { throw new AssertionError("Replacement must not invoke unrelated host actions"); });
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

    private static Stream<Node> nodes(Node node) {
        return node instanceof Parent parent
                ? Stream.concat(Stream.of(node), parent.getChildrenUnmodifiable().stream().flatMap(MixerPluginLifetimeTest::nodes))
                : Stream.of(node);
    }
}
