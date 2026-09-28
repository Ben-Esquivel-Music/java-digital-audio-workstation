package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.controls.Fader;
import com.benesquivelmusic.daw.app.ui.controls.InsertSlotModel;
import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.metering.MeterFeed;
import com.benesquivelmusic.daw.app.ui.vm.command.CoreTrackIntentHandler;
import com.benesquivelmusic.daw.app.ui.vm.command.RenameTrackCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.SetChannelVolumeCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleArmCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleMuteCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleSoloCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TrackCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TrackIntentHandler;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.metering.LevelTapSlot;
import com.benesquivelmusic.daw.core.metering.MeteringTapBus;
import com.benesquivelmusic.daw.core.metering.TapSnapshot;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 322 — {@link TrackControlBinder#bindStrip(MixerChannelStrip)}: every
 * strip fact is a subscriber of a VM property and every strip gesture is an
 * intent. Fader dB ↔ linear round-trips at −6 dB / 0.5 linear without a phantom
 * command; M/S/R flag flips on the strip (what the skin's buttons do) raise the
 * matching commands and a VM-driven mirror raises nothing; inserts and name
 * follow; the meter relays into the strip's integrated-meter seam. Runs on the
 * FX thread so the VMs republish inline.
 */
class TrackControlBinderStripTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 64);
    private static final int BLOCK = 64;
    private static final double HALF_SCALE_DB = -6.0206;

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
        final DawProject project = new DawProject("Test", FORMAT);
        final Track track = project.createAudioTrack("Drums");
        final MixerChannel channel = project.getMixerChannelForTrack(track);
        final FxDispatcher dispatcher = new FxDispatcher();
        final TrackVM trackVm = new TrackVM(track, dispatcher);
        final ChannelVM channelVm;
        final TrackIntentHandler handler = new CoreTrackIntentHandler(project);
        final List<TrackCommand> raised = new ArrayList<>();
        final Consumer<TrackCommand> sink = command -> {
            raised.add(command);
            command.execute(handler);
        };
        final TrackControlBinder binder;
        final MixerChannelStrip strip = new MixerChannelStrip();

        Rig(MeterFeed feed) {
            channelVm = new ChannelVM(channel, dispatcher, feed);
            binder = new TrackControlBinder(track, trackVm, channel, channelVm, sink);
        }

        Rig() {
            this(null);
        }

        void close() {
            binder.dispose();
            trackVm.dispose();
            channelVm.dispose();
        }
    }

    @Test
    void faderDbFollowsTheVmVolumeInDbAndFloorsAtTheStripMinimum() throws InterruptedException {
        onFx(() -> {
            Rig rig = new Rig();
            try {
                rig.binder.bindStrip(rig.strip);
                assertThat(rig.strip.getFaderDb()).as("unity seeds 0 dB").isEqualTo(0.0);

                rig.channel.setVolume(0.5);
                assertThat(rig.strip.getFaderDb()).isCloseTo(HALF_SCALE_DB, within(1e-4));
                assertThat(rig.raised).as("a VM-driven write raises no command").isEmpty();

                rig.channel.setVolume(0.0);
                assertThat(rig.strip.getFaderDb()).isEqualTo(MixerChannelStrip.FADER_MIN_DB);
                assertThat(rig.raised).isEmpty();
            } finally {
                rig.close();
            }
        });
    }

    @Test
    void aFaderGestureInDbCommitsTheLinearVolumeExactlyOnce() throws InterruptedException {
        onFx(() -> {
            Rig rig = new Rig();
            try {
                rig.binder.bindStrip(rig.strip);

                rig.strip.setFaderDb(-6.0);

                assertThat(rig.channel.getVolume()).isCloseTo(Math.pow(10.0, -6.0 / 20.0), within(1e-12));
                assertThat(rig.track.getVolume()).as("dual-written").isEqualTo(rig.channel.getVolume());
                assertThat(rig.raised).as("one command, no dB↔linear ping-pong").hasSize(1);
                // The VM republish wrote linearToDb(volume) back — a 1-ulp-class
                // correction at most, and it must not have raised a second command.
                assertThat(rig.strip.getFaderDb()).isCloseTo(-6.0, within(1e-9));

                rig.strip.setFaderDb(+6.0); // above unity: clamped to 1.0 in the model
                assertThat(rig.channel.getVolume()).isEqualTo(1.0);
                assertThat(rig.strip.getFaderDb()).as("the strip settles on the model's 0 dB").isEqualTo(0.0);
                assertThat(rig.raised).hasSize(2);
            } finally {
                rig.close();
            }
        });
    }

    @Test
    void aClampedNoOpFaderGestureFromUnitySnapsTheStripBackToTheModel() throws InterruptedException {
        // PR #977 review: the strip's travel reaches FADER_MAX_DB = +12 dB while
        // the model is [0,1]. From unity a +6 dB gesture dispatches a clamped
        // 1.0 that CoreTrackIntentHandler.setVolume sees as a TRUE no-op — no
        // MUTATE, no republish — so nothing pulls the strip back unless the
        // binder re-asserts the VM value itself. (The gesture in the test
        // above starts at −6 dB, where the model DOES change and republishes.)
        onFx(() -> {
            Rig rig = new Rig();
            try {
                rig.binder.bindStrip(rig.strip);
                assertThat(rig.strip.getFaderDb()).as("unity seeds 0 dB").isEqualTo(0.0);

                rig.strip.setFaderDb(+6.0);

                assertThat(rig.channel.getVolume()).as("the model never left unity").isEqualTo(1.0);
                assertThat(rig.track.getVolume()).isEqualTo(1.0);
                assertThat(rig.strip.getFaderDb())
                        .as("the strip snaps back to the model's 0 dB, not +6 dB over a gain of 1.0")
                        .isEqualTo(0.0);
                assertThat(rig.raised)
                        .as("exactly the clamped 1.0 was raised; the snap-back is an echo and raises nothing")
                        .containsExactly(new SetChannelVolumeCommand(rig.channel, 1.0));

                rig.strip.setFaderDb(-6.0); // a legitimate gesture afterwards still commits
                assertThat(rig.channel.getVolume()).isCloseTo(Math.pow(10.0, -6.0 / 20.0), within(1e-12));
                assertThat(rig.strip.getFaderDb()).isCloseTo(-6.0, within(1e-9));
                assertThat(rig.raised).hasSize(2);
            } finally {
                rig.close();
            }
        });
    }

    @Test
    void aClampedNoOpWidgetGestureSnapsBackThroughTheRealSkinWhenTheBinderIsBoundFirst()
            throws InterruptedException {
        // The production show path: bindStrip runs before the strip enters a
        // Scene, so the binder's faderDb listener precedes the skin's two.
        skinnedClampedNoOpGestureSnapsBack(true);
    }

    @Test
    void aClampedNoOpWidgetGestureSnapsBackThroughTheRealSkinWhenTheSkinIsBoundFirst()
            throws InterruptedException {
        // The other registration order — a strip bound after its skin exists,
        // so the skin's two faderDb listeners precede the binder's. No
        // production path does this today (MixerView.buildChannelStrip binds a
        // freshly built strip before it joins the scene graph); it is pinned so
        // a future re-bind of a live strip cannot regress into a ping-pong.
        skinnedClampedNoOpGestureSnapsBack(false);
    }

    /**
     * PR #977 verification round 2 — finding 3 with the REAL skin attached. The
     * skinless test above pins one {@code faderDb} listener; production has
     * three ({@code MixerChannelStripSkin.faderToWidget}, guarded by
     * {@code fader.getValue() != now}, its {@code readoutRefreshListener}, and
     * the binder's {@code faderToCommand}) and the snap-back re-enters
     * {@code faderDb} from inside its own notification. JavaFX 26's
     * {@code ExpressionHelper} hands the listeners after the nesting one
     * {@code (outerOld, nestedNew)} — old == new — so the skin's guard swallows
     * it; a dispatcher that handed them a stale value would, in the binder-first
     * order, ping-pong widget ↔ control to a {@code StackOverflowError}, which
     * {@link #onFx} rethrows (in the skin-first order the binder's listener is
     * the last one on {@code faderDb}, so nothing after it can see a stale
     * value; that order pins the command count and the readout).
     * The gesture is the WIDGET's value, as a drag is; the widget is found by
     * {@code lookupAll(".fader")} — no reflection into the skin.
     */
    private static void skinnedClampedNoOpGestureSnapsBack(boolean bindBeforeSkin) throws InterruptedException {
        onFx(() -> {
            Rig rig = new Rig();
            try {
                Pane root = new Pane(rig.strip);
                new Scene(root, 100, 600); // CSS needs a Scene; applyCss() creates the skin
                assertThat(rig.strip.getSkin()).as("no skin before the CSS pass").isNull();
                if (bindBeforeSkin) {
                    rig.binder.bindStrip(rig.strip);
                }
                root.applyCss();
                root.layout();
                assertThat(rig.strip.getSkin()).as("applyCss() created the skin").isNotNull();
                if (!bindBeforeSkin) {
                    rig.binder.bindStrip(rig.strip);
                }
                Set<Node> faders = rig.strip.lookupAll(".fader");
                assertThat(faders).as("the strip's one level fader (no sends)").hasSize(1);
                Fader fader = (Fader) faders.iterator().next();
                Label readout = (Label) rig.strip.lookup(".mixer-channel-strip-readout");
                assertThat(readout).isNotNull();
                assertThat(fader.getValue()).as("unity seeds the widget at 0 dB").isEqualTo(0.0);

                for (double aboveUnityDb : new double[] {6.0, 11.9}) {
                    int before = rig.raised.size();
                    fader.setValue(aboveUnityDb);
                    assertThat(rig.strip.getFaderDb())
                            .as("strip after a widget drag to +%s dB", aboveUnityDb).isEqualTo(0.0);
                    assertThat(fader.getValue())
                            .as("widget after a widget drag to +%s dB", aboveUnityDb).isEqualTo(0.0);
                    assertThat(rig.channelVm.getVolume()).as("the model never left unity").isEqualTo(1.0);
                    assertThat(rig.raised.subList(before, rig.raised.size()))
                            .as("exactly one command per gesture — no widget ↔ control ping-pong")
                            .containsExactly(new SetChannelVolumeCommand(rig.channel, 1.0));
                    assertThat(readout.getText()).as("the readout follows the snapped-back value")
                            .isEqualTo("0.0 dB");
                }

                fader.setValue(-6.0); // a legitimate widget drag afterwards still commits
                assertThat(rig.channelVm.getVolume()).isCloseTo(Math.pow(10.0, -6.0 / 20.0), within(1e-9));
                assertThat(fader.getValue()).isCloseTo(-6.0, within(1e-9));
                assertThat(rig.strip.getFaderDb()).isCloseTo(-6.0, within(1e-9));
                assertThat(readout.getText()).as("the readout follows a committed value").isEqualTo("-6.0 dB");
                assertThat(rig.raised).hasSize(3);
            } finally {
                rig.close();
            }
        });
    }

    @Test
    void stripFlagFlipsRaiseCommandsAndVmMirrorsRaiseNothing() throws InterruptedException {
        onFx(() -> {
            Rig rig = new Rig();
            try {
                rig.binder.bindStrip(rig.strip);

                rig.strip.setMuted(true); // what the skin's M button does
                assertThat(rig.raised).hasSize(1).first().isInstanceOf(ToggleMuteCommand.class);
                assertThat(((ToggleMuteCommand) rig.raised.getFirst()).muted()).isTrue();
                assertThat(rig.track.isMuted()).isTrue();
                assertThat(rig.channel.isMuted()).as("§1.3 lock-step").isTrue();
                assertThat(rig.strip.isMuted()).isTrue();

                rig.strip.setSoloed(true);
                assertThat(rig.raised).hasSize(2).last().isInstanceOf(ToggleSoloCommand.class);
                assertThat(rig.channel.isSolo()).isTrue();

                rig.strip.setArmed(true);
                assertThat(rig.raised).hasSize(3).last().isInstanceOf(ToggleArmCommand.class);
                assertThat(rig.track.isArmed()).isTrue();

                // The other surface (the arrangement lane) unmutes through the same handler.
                rig.handler.toggleMute(rig.track, false);
                assertThat(rig.strip.isMuted()).as("strip follows the ONE TrackVM flag").isFalse();
                assertThat(rig.raised).as("a VM-driven mirror raises no command").hasSize(3);
            } finally {
                rig.close();
            }
        });
    }

    @Test
    void nameChannelIdPanAndInsertsAreMirrored() throws InterruptedException {
        onFx(() -> {
            Rig rig = new Rig();
            try {
                rig.binder.bindStrip(rig.strip);
                assertThat(rig.strip.getChannelId()).isEqualTo(rig.channel.getId());
                assertThat(rig.strip.getChannelName()).isEqualTo("Drums");
                rig.track.setName("Kick");
                assertThat(rig.strip.getChannelName()).isEqualTo("Kick");

                rig.strip.setPan(-0.25);
                assertThat(rig.channel.getPan()).isEqualTo(-0.25);
                rig.channel.setPan(0.5);
                assertThat(rig.strip.getPan()).isEqualTo(0.5);

                assertThat(rig.strip.insertsProperty()).isEmpty();
                rig.channel.addInsert(new InsertSlot("Comp", new Passthrough()));
                assertThat(rig.strip.insertsProperty())
                        .containsExactly(new InsertSlotModel("Comp", true, false));
                rig.channel.setInsertBypassed(0, true);
                assertThat(rig.strip.insertsProperty())
                        .containsExactly(new InsertSlotModel("Comp", false, true));
            } finally {
                rig.close();
            }
        });
    }

    @Test
    void aStripNameEditRaisesRenameAndABlankOneSnapsBack() throws InterruptedException {
        onFx(() -> {
            Rig rig = new Rig();
            try {
                rig.binder.bindStrip(rig.strip);

                rig.strip.setChannelName("Kick "); // what the skin's inline editor commits
                assertThat(rig.raised).hasSize(1).first()
                        .isEqualTo(new RenameTrackCommand(rig.track, "Kick "));
                assertThat(rig.track.getName()).as("the intent path renamed the model").isEqualTo("Kick");
                assertThat(rig.strip.getChannelName())
                        .as("the strip shows the model's (stripped) name, not its own text")
                        .isEqualTo("Kick");

                rig.strip.setChannelName("   ");
                assertThat(rig.raised).hasSize(2);
                assertThat(rig.track.getName()).as("a blank name is refused").isEqualTo("Kick");
                assertThat(rig.strip.getChannelName()).as("a rejected name snaps back").isEqualTo("Kick");

                rig.track.setName("Snare"); // the other surface renamed
                assertThat(rig.strip.getChannelName()).isEqualTo("Snare");
                assertThat(rig.raised).as("a VM-driven mirror raises no command").hasSize(2);
            } finally {
                rig.close();
            }
        });
    }

    @Test
    void withoutAFeedTheMeterSeamStaysAtTheFloorAndNothingIsBound() throws InterruptedException {
        onFx(() -> {
            Rig rig = new Rig();
            try {
                rig.binder.bindStrip(rig.strip);
                assertThat(rig.channelVm.isMeterBound()).isFalse();
                assertThat(rig.strip.getMeterPeakDb()).isEqualTo(ChannelVM.METER_FLOOR_DB);
            } finally {
                rig.close();
            }
        });
    }

    @Test
    void withAFeedTheRenderedPeakReachesTheStripMeterSeam() throws InterruptedException {
        onFx(() -> {
            MeteringTapBus bus = new MeteringTapBus();
            MeterFeed feed = null;
            Stage stage = new Stage();
            try {
                DawProject project = new DawProject("Meters", FORMAT);
                Track drums = project.createAudioTrack("Drums");
                MixerChannel channel = project.getMixerChannelForTrack(drums);
                bus.rebind(project.getMixer(), FORMAT, 1L);
                FxDispatcher dispatcher = new FxDispatcher();
                feed = new MeterFeed(bus, dispatcher);
                ChannelVM channelVm = new ChannelVM(channel, dispatcher, feed);
                TrackVM trackVm = new TrackVM(drums, dispatcher);
                MixerChannelStrip strip = new MixerChannelStrip();
                TrackControlBinder binder = new TrackControlBinder(drums, trackVm, channel, channelVm,
                        c -> c.execute(new CoreTrackIntentHandler(project)));
                stage.setScene(new Scene(new Pane(strip), 100, 100));

                binder.bindStrip(strip);
                assertThat(channelVm.isMeterBound()).as("the strip owns a meter binding").isTrue();
                stage.show();
                assertThat(feed.subscriptionCount()).as("visible → demand").isEqualTo(1);

                renderInto(bus, channel, 0.5f);
                dispatcher.pulse();
                assertThat(channelVm.getMeterLevel()).isCloseTo(HALF_SCALE_DB, within(0.01));
                assertThat(strip.getMeterPeakDb())
                        .as("the VM level is relayed into the strip's integrated-meter seam")
                        .isCloseTo(HALF_SCALE_DB, within(0.01));

                binder.dispose();
                assertThat(channelVm.isMeterBound()).as("dispose releases the meter binding").isFalse();
                assertThat(feed.subscriptionCount()).isZero();
                channelVm.dispose();
                trackVm.dispose();
            } finally {
                stage.close();
                if (feed != null) {
                    feed.dispose();
                }
                bus.close();
            }
        });
    }

    private static void renderInto(MeteringTapBus bus, MixerChannel channel, float level) {
        TapSnapshot taps = bus.snapshot();
        LevelTapSlot slot = null;
        for (int i = 0; slot == null && i < 8; i++) {
            slot = taps.channelSlot(i, channel);
        }
        assertThat(slot).isNotNull();
        slot.beginBlock(taps.epoch(), taps.blockIndex(), 2);
        for (int frame = 0; frame < BLOCK; frame++) {
            slot.accumulate(0, level);
            slot.accumulate(1, level);
        }
        slot.publish(BLOCK);
        bus.blockCompleted(taps);
    }

    private static final class Passthrough implements AudioProcessor {
        @Override
        public void process(float[][] in, float[][] out, int numFrames) {
            for (int c = 0; c < Math.min(in.length, out.length); c++) {
                System.arraycopy(in[c], 0, out[c], 0, numFrames);
            }
        }

        @Override
        public void reset() { }

        @Override
        public int getInputChannelCount() { return 2; }

        @Override
        public int getOutputChannelCount() { return 2; }
    }
}
