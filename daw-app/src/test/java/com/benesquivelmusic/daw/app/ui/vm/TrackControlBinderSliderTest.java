package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.command.CoreTrackIntentHandler;
import com.benesquivelmusic.daw.app.ui.vm.command.TrackCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TrackIntentHandler;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.application.Platform;
import javafx.scene.control.Slider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 — {@link TrackControlBinder#bindFader(Slider)} /
 * {@link TrackControlBinder#bindPan(Slider)}: the model is written on every
 * drag tick (a DAW fader is audible while dragging), the VM→slider echo is
 * suppressed while {@link Slider#isValueChanging()} and re-applied exactly once
 * on release, a VM change outside a drag lands immediately, a rejected value
 * snaps back, and {@code dispose()} severs everything. Everything runs on the
 * FX thread so the VM republishes inline and the assertions are synchronous.
 */
class TrackControlBinderSliderTest {

    private static final long TIMEOUT_SECONDS = 5;

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

    /** One track, its VMs, the real handler behind a counting sink. */
    private static final class Rig {
        final DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        final Track track = project.createAudioTrack("Drums");
        final MixerChannel channel = project.getMixerChannelForTrack(track);
        final FxDispatcher dispatcher = new FxDispatcher();
        final TrackVM trackVm = new TrackVM(track, dispatcher);
        final ChannelVM channelVm = new ChannelVM(channel, dispatcher);
        final TrackIntentHandler handler = new CoreTrackIntentHandler(project);
        final AtomicInteger commands = new AtomicInteger();
        final Consumer<TrackCommand> sink = command -> {
            commands.incrementAndGet();
            command.execute(handler);
        };
        final TrackControlBinder binder =
                new TrackControlBinder(track, trackVm, channel, channelVm, sink);

        void close() {
            binder.dispose();
            trackVm.dispose();
            channelVm.dispose();
        }
    }

    @Test
    void everyDragTickCommitsToTheModel() throws InterruptedException {
        onFx(() -> {
            Rig rig = new Rig();
            try {
                Slider fader = new Slider(0.0, 1.0, 1.0);
                rig.binder.bindFader(fader);
                assertThat(fader.getValue()).as("seeded from the VM").isEqualTo(1.0);

                fader.setValueChanging(true);
                fader.setValue(0.8);
                assertThat(rig.channel.getVolume()).as("audible on the first tick").isEqualTo(0.8);
                fader.setValue(0.6);
                assertThat(rig.channel.getVolume()).isEqualTo(0.6);
                assertThat(rig.track.getVolume()).as("dual-written").isEqualTo(0.6);
                assertThat(rig.commands.get()).as("one command per tick").isEqualTo(2);
                fader.setValueChanging(false);
                assertThat(rig.commands.get()).as("release re-applies the VM value: an echo, not a command")
                        .isEqualTo(2);
                assertThat(fader.getValue()).isEqualTo(0.6);
            } finally {
                rig.close();
            }
        });
    }

    @Test
    void vmEchoIsSuppressedWhileDraggingAndReappliedOnRelease() throws InterruptedException {
        onFx(() -> {
            Rig rig = new Rig();
            try {
                Slider fader = new Slider(0.0, 1.0, 1.0);
                rig.binder.bindFader(fader);

                fader.setValueChanging(true);
                fader.setValue(0.6);
                // An external write (automation / undo / a linked partner) lands mid-drag.
                rig.channel.setVolume(0.2);
                assertThat(rig.channelVm.getVolume()).as("VM republished inline").isEqualTo(0.2);
                assertThat(fader.getValue()).as("the thumb is NOT yanked from under the pointer").isEqualTo(0.6);

                int before = rig.commands.get();
                fader.setValueChanging(false);
                assertThat(fader.getValue()).as("release re-applies the model's value once").isEqualTo(0.2);
                assertThat(rig.commands.get()).as("the re-apply is an echo").isEqualTo(before);
                assertThat(rig.channel.getVolume()).as("the stale thumb never overwrote the model").isEqualTo(0.2);
            } finally {
                rig.close();
            }
        });
    }

    @Test
    void aVmChangeOutsideADragLandsImmediatelyWithoutACommand() throws InterruptedException {
        onFx(() -> {
            Rig rig = new Rig();
            try {
                Slider fader = new Slider(0.0, 1.0, 1.0);
                rig.binder.bindFader(fader);

                rig.channel.setVolume(0.45);
                assertThat(fader.getValue()).isEqualTo(0.45);
                assertThat(rig.commands.get()).as("a VM-driven refresh raises nothing").isZero();
            } finally {
                rig.close();
            }
        });
    }

    @Test
    void panSliderCommitsPerTickAndSnapsBackWhenTheHandlerRejects() throws InterruptedException {
        onFx(() -> {
            Rig rig = new Rig();
            try {
                Slider pan = new Slider(-2.0, 2.0, 0.0); // wider than the model's [−1,1] on purpose
                rig.binder.bindPan(pan);

                pan.setValue(-0.7);
                assertThat(rig.channel.getPan()).isEqualTo(-0.7);
                assertThat(rig.track.getPan()).isEqualTo(-0.7);

                pan.setValue(1.5); // the handler throws IllegalArgumentException
                assertThat(rig.channel.getPan()).as("model unchanged").isEqualTo(-0.7);
                assertThat(pan.getValue()).as("snapped back to the VM's accepted value").isEqualTo(-0.7);
            } finally {
                rig.close();
            }
        });
    }

    @Test
    void disposeSeversBothDirections() throws InterruptedException {
        onFx(() -> {
            Rig rig = new Rig();
            try {
                Slider fader = new Slider(0.0, 1.0, 1.0);
                rig.binder.bindFader(fader);
                rig.binder.dispose();

                rig.channel.setVolume(0.9);
                assertThat(fader.getValue()).as("VM → slider severed").isEqualTo(1.0);
                fader.setValue(0.3);
                assertThat(rig.channel.getVolume()).as("slider → command severed").isEqualTo(0.9);
                assertThat(rig.commands.get()).isZero();
            } finally {
                rig.close();
            }
        });
    }
}
