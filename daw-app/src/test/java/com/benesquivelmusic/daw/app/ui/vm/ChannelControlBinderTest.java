package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.command.CoreTrackIntentHandler;
import com.benesquivelmusic.daw.app.ui.vm.command.SetChannelPanCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.SetChannelVolumeCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleChannelMuteCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleChannelSoloCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TrackCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TrackIntentHandler;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.control.Button;
import javafx.scene.control.Slider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 — {@link ChannelControlBinder} for a standalone channel (a return
 * bus / the master): mute/solo {@code :active} is seeded from the model at bind
 * time and follows the {@link ChannelVM} discrete facts; a click raises the
 * channel-targeted command; fader/pan sliders share the {@link SliderBinding}
 * discipline; {@code dispose()} severs everything.
 */
class ChannelControlBinderTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");

    @BeforeAll
    static void initToolkit() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    private static void onFx(Runnable action) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        if (thrown.get() instanceof RuntimeException re) {
            throw re;
        }
        if (thrown.get() instanceof Error e) {
            throw e;
        }
    }

    private static final class Rig {
        final DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        final MixerChannel returnBus = project.getMixer().getReturnBuses().get(0);
        final FxDispatcher dispatcher = new FxDispatcher();
        final ChannelVM vm = new ChannelVM(returnBus, dispatcher);
        final TrackIntentHandler handler = new CoreTrackIntentHandler(project);
        final List<TrackCommand> raised = new ArrayList<>();
        final Consumer<TrackCommand> sink = command -> {
            raised.add(command);
            command.execute(handler);
        };
        final ChannelControlBinder binder = new ChannelControlBinder(returnBus, vm, sink);

        void close() {
            binder.dispose();
            vm.dispose();
        }
    }

    @Test
    void muteButtonIsSeededFromTheModelAndAClickRaisesTheChannelCommand() throws InterruptedException {
        onFx(() -> {
            Rig rig = new Rig();
            try {
                rig.returnBus.setMuted(true); // muted BEFORE bind: the style must seed, no click needed
                Button mute = new Button("M");
                rig.binder.bindMute(mute);
                assertThat(mute.getPseudoClassStates()).as("seeded :active").contains(ACTIVE);

                mute.fire();
                assertThat(rig.raised).hasSize(1).first().isInstanceOf(ToggleChannelMuteCommand.class);
                assertThat(((ToggleChannelMuteCommand) rig.raised.getFirst()).muted()).isFalse();
                assertThat(rig.returnBus.isMuted()).isFalse();
                assertThat(mute.getPseudoClassStates()).as("follows the VM fact").doesNotContain(ACTIVE);

                rig.returnBus.setMuted(true); // model-driven
                assertThat(mute.getPseudoClassStates()).contains(ACTIVE);
                assertThat(rig.raised).as("no command for a model-driven change").hasSize(1);
            } finally {
                rig.close();
            }
        });
    }

    @Test
    void soloButtonRaisesTheChannelSoloCommand() throws InterruptedException {
        onFx(() -> {
            Rig rig = new Rig();
            try {
                Button solo = new Button("S");
                rig.binder.bindSolo(solo);
                assertThat(solo.getPseudoClassStates()).doesNotContain(ACTIVE);

                solo.fire();
                assertThat(rig.raised).hasSize(1).first().isInstanceOf(ToggleChannelSoloCommand.class);
                assertThat(rig.returnBus.isSolo()).isTrue();
                assertThat(solo.getPseudoClassStates()).contains(ACTIVE);
            } finally {
                rig.close();
            }
        });
    }

    @Test
    void faderAndPanSlidersCommitPerTickAndFollowTheVm() throws InterruptedException {
        onFx(() -> {
            Rig rig = new Rig();
            try {
                Slider fader = new Slider(0.0, 1.0, 1.0);
                Slider pan = new Slider(-1.0, 1.0, 0.0);
                rig.binder.bindFader(fader);
                rig.binder.bindPan(pan);

                fader.setValue(0.3);
                assertThat(rig.returnBus.getVolume()).isEqualTo(0.3);
                assertThat(rig.raised).last().isInstanceOf(SetChannelVolumeCommand.class);
                pan.setValue(0.75);
                assertThat(rig.returnBus.getPan()).isEqualTo(0.75);
                assertThat(rig.raised).last().isInstanceOf(SetChannelPanCommand.class);
                assertThat(rig.raised).hasSize(2);

                rig.returnBus.setVolume(0.65);
                assertThat(fader.getValue()).isEqualTo(0.65);
                rig.returnBus.setPan(-0.5);
                assertThat(pan.getValue()).isEqualTo(-0.5);
                assertThat(rig.raised).as("VM-driven refreshes raise nothing").hasSize(2);

                fader.setValueChanging(true);
                fader.setValue(0.1);
                rig.returnBus.setVolume(0.9);
                assertThat(fader.getValue()).as("echo suppressed while dragging").isEqualTo(0.1);
                fader.setValueChanging(false);
                assertThat(fader.getValue()).as("re-applied on release").isEqualTo(0.9);
            } finally {
                rig.close();
            }
        });
    }

    @Test
    void disposeSeversEverything() throws InterruptedException {
        onFx(() -> {
            Rig rig = new Rig();
            try {
                Button mute = new Button("M");
                Slider fader = new Slider(0.0, 1.0, 1.0);
                rig.binder.bindMute(mute);
                rig.binder.bindFader(fader);
                rig.binder.dispose();

                mute.fire();
                fader.setValue(0.2);
                rig.returnBus.setMuted(true);
                rig.returnBus.setVolume(0.5);

                assertThat(rig.raised).isEmpty();
                assertThat(mute.getPseudoClassStates()).doesNotContain(ACTIVE);
                assertThat(fader.getValue()).isEqualTo(0.2);
            } finally {
                rig.close();
            }
        });
    }
}
