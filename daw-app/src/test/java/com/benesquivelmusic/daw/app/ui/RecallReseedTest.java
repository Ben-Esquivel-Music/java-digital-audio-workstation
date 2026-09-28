package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;
import com.benesquivelmusic.daw.app.ui.vm.command.SetChannelPanCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.SetChannelVolumeCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleMuteCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleSoloCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TrackCommand;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshot;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshotManager;
import com.benesquivelmusic.daw.core.mixer.snapshot.RecallSnapshotAction;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.css.PseudoClass;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 322 — "Snapshot / A-B recall: strips re-seed from model after
 * recall/undo — stale-fader corruption impossible" (Audio Engine Wiring Design
 * Book §5.6). The re-seed is <em>structural</em>: every mixer strip control is
 * a {@code ChannelVM} subscriber, so a recall (and its undo) moves every fader
 * / pan / mute / solo without any imperative re-seed or strip rebuild, and a
 * nudge of one fader afterwards changes only that channel's model — the
 * recalled scene is never silently overwritten.
 *
 * <p>Since the story-271 skin swap (slice 5) a track strip is a
 * {@link MixerChannelStrip}: its fader is in dB ({@code 20·log10(linear)}),
 * its mute / solo are the strip's own two-way flag properties. The return and
 * master strips keep their linear sliders and buttons.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class RecallReseedTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 256);
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");
    private static final PseudoClass MUTED = PseudoClass.getPseudoClass("muted");
    private static final PseudoClass SOLOED = PseudoClass.getPseudoClass("soloed");
    /** dB ↔ linear round-trips through the strip are exact to well below this. */
    private static final double EPS = 1e-9;

    /** The strip fader's dB for a linear volume (the binder's law; the fader floors at −96 dB). */
    private static double db(double linear) {
        return linear <= 0.0 ? MixerChannelStrip.FADER_MIN_DB
                : Math.max(MixerChannelStrip.FADER_MIN_DB, 20.0 * Math.log10(linear));
    }

    /** One channel's scalar scene state and the ways of checking it. */
    private record SceneState(double volume, double pan, boolean muted, boolean solo) {

        void applyTo(MixerChannel channel) {
            channel.setVolume(volume);
            channel.setPan(pan);
            channel.setMuted(muted);
            channel.setSolo(solo);
        }

        /** Seeds a track channel AND its Track mirror — the lock-step the intent path maintains. */
        void applyTo(MixerChannel channel, Track track) {
            applyTo(channel);
            track.setVolume(volume);
            track.setPan(pan);
            track.setMuted(muted);
            track.setSolo(solo);
        }

        void assertHeldBy(MixerChannel channel, String what) {
            assertThat(channel.getVolume()).as(what + " model volume").isEqualTo(volume);
            assertThat(channel.getPan()).as(what + " model pan").isEqualTo(pan);
            assertThat(channel.isMuted()).as(what + " model mute").isEqualTo(muted);
            assertThat(channel.isSolo()).as(what + " model solo").isEqualTo(solo);
        }

        /** The Track mirror follows a recall too (healed through the intent path). */
        void assertMirroredBy(Track track, String what) {
            assertThat(track.getVolume()).as(what + " Track volume").isEqualTo(volume);
            assertThat(track.getPan()).as(what + " Track pan").isEqualTo(pan);
            assertThat(track.isMuted()).as(what + " Track mute").isEqualTo(muted);
            assertThat(track.isSolo()).as(what + " Track solo").isEqualTo(solo);
        }

        /** A return / master strip: linear slider values and the binder's {@code :active} buttons. */
        void assertShownBy(MixerView.MixerStripControls controls, String what) {
            assertThat(controls.volumeFader().getValue()).as(what + " fader").isEqualTo(volume);
            assertThat(controls.panSlider().getValue()).as(what + " pan slider").isEqualTo(pan);
            assertThat(controls.muteBtn().getPseudoClassStates().contains(ACTIVE))
                    .as(what + " mute :active").isEqualTo(muted);
            if (controls.soloBtn() != null) {
                assertThat(controls.soloBtn().getPseudoClassStates().contains(ACTIVE))
                        .as(what + " solo :active").isEqualTo(solo);
            }
        }

        /** A track strip: the {@link MixerChannelStrip}'s dB fader, pan and two-way flags. */
        void assertShownBy(MixerChannelStrip strip, String what) {
            assertThat(strip.getFaderDb()).as(what + " fader (dB)").isCloseTo(db(volume), within(EPS));
            assertThat(strip.getPan()).as(what + " pan").isEqualTo(pan);
            assertThat(strip.isMuted()).as(what + " muted").isEqualTo(muted);
            assertThat(strip.getPseudoClassStates().contains(MUTED)).as(what + " :muted").isEqualTo(muted);
            assertThat(strip.isSoloed()).as(what + " soloed").isEqualTo(solo);
            assertThat(strip.getPseudoClassStates().contains(SOLOED)).as(what + " :soloed").isEqualTo(solo);
        }
    }

    @Test
    void recallReseedsEveryStripAndANudgeMovesOnlyItsOwnChannelAlsoAfterUndo() throws Exception {
        DawProject project = new DawProject("Recall", FORMAT);
        Track kick = project.createAudioTrack("Kick");
        Track snare = project.createAudioTrack("Snare");
        MixerChannel kickCh = project.getMixerChannelForTrack(kick);
        MixerChannel snareCh = project.getMixerChannelForTrack(snare);
        MixerChannel verb = project.getMixer().addReturnBus("Verb");
        MixerChannel master = project.getMixer().getMasterChannel();
        UndoManager undo = new UndoManager();
        MixerSnapshotManager slots = project.getMixerSnapshotManager();

        // Every value in both scenes is non-default and differs between A and B.
        SceneState kickA = new SceneState(0.8, -0.5, false, false);
        SceneState snareA = new SceneState(0.6, 0.25, true, false);
        SceneState verbA = new SceneState(0.7, 0.3, false, false);
        SceneState masterA = new SceneState(0.9, -0.2, false, false);
        SceneState kickB = new SceneState(0.2, 0.9, true, true);
        SceneState snareB = new SceneState(0.1, -0.7, false, true);
        SceneState verbB = new SceneState(0.15, -0.9, true, true);
        SceneState masterB = new SceneState(0.3, 0.6, true, false);

        ArrangementStripFixture.onFx(() -> {
            kickA.applyTo(kickCh, kick);
            snareA.applyTo(snareCh, snare);
            verbA.applyTo(verb);
            masterA.applyTo(master);
            slots.setSlot(MixerSnapshotManager.Slot.A, MixerSnapshot.capture(project.getMixer(), "A"));
            kickB.applyTo(kickCh, kick);
            snareB.applyTo(snareCh, snare);
            verbB.applyTo(verb);
            masterB.applyTo(master);
            slots.setSlot(MixerSnapshotManager.Slot.B, MixerSnapshot.capture(project.getMixer(), "B"));

            MixerView view = new MixerView(project, undo);
            try {
                List<MixerView.TrackStripHandles> tracks = view.getTrackStrips();
                List<MixerView.MixerStripControls> returns = view.getReturnStripControls();
                MixerView.MixerStripControls masterControls = view.getMasterStripControls();
                assertThat(tracks).hasSize(2);
                MixerChannelStrip kickStrip = tracks.get(0).strip();
                MixerChannelStrip snareStrip = tracks.get(1).strip();
                // The project owns a default return bus; Verb is the one added here.
                assertThat(returns).hasSize(project.getMixer().getReturnBuses().size());
                int verbIndex = project.getMixer().getReturnBuses().indexOf(verb);
                assertThat(verbIndex).isNotNegative();
                MixerView.MixerStripControls verbControls = returns.get(verbIndex);
                kickB.assertShownBy(kickStrip, "kick (built over B)");
                snareB.assertShownBy(snareStrip, "snare (built over B)");
                verbB.assertShownBy(verbControls, "verb (built over B)");
                masterB.assertShownBy(masterControls, "master (built over B)");

                // Recall A through the toolbar button — the production gesture.
                view.getSlotAButton().fire();

                // Structural: the very same control instances, no rebuild.
                assertThat(view.getTrackStrips()).as("no strip rebuild on recall")
                        .containsExactlyElementsOf(tracks);
                assertThat(view.getReturnStripControls()).containsExactlyElementsOf(returns);
                kickA.assertHeldBy(kickCh, "kick");
                kickA.assertMirroredBy(kick, "kick after recall A");
                snareA.assertMirroredBy(snare, "snare after recall A");
                kickA.assertShownBy(kickStrip, "kick after recall A");
                snareA.assertShownBy(snareStrip, "snare after recall A");
                verbA.assertShownBy(verbControls, "verb after recall A");
                masterA.assertShownBy(masterControls, "master after recall A");

                // Nudge ONE fader (what the strip's Fader does on a drag tick):
                // only that channel's MixerChannel + Track move.
                kickStrip.setFaderDb(db(0.55));
                assertThat(kickCh.getVolume()).as("nudged channel").isCloseTo(0.55, within(EPS));
                assertThat(kick.getVolume()).as("nudged channel's Track (dual-write)")
                        .isCloseTo(0.55, within(EPS));
                assertThat(kickCh.getPan()).as("nudge touches nothing else on its channel").isEqualTo(kickA.pan());
                snareA.assertHeldBy(snareCh, "snare (untouched by the nudge)");
                verbA.assertHeldBy(verb, "verb (untouched by the nudge)");
                masterA.assertHeldBy(master, "master (untouched by the nudge)");
                assertThat(snare.getVolume()).as("snare Track untouched").isNotCloseTo(0.55, within(EPS));
                snareA.assertShownBy(snareStrip, "snare after the nudge");
                verbA.assertShownBy(verbControls, "verb after the nudge");
                masterA.assertShownBy(masterControls, "master after the nudge");

                // Undo the recall: the pre-recall scene (B) comes back on every
                // control — the nudged fader included — again with no rebuild.
                undo.undo();
                assertThat(view.getTrackStrips()).containsExactlyElementsOf(tracks);
                kickB.assertHeldBy(kickCh, "kick after undo");
                kickB.assertMirroredBy(kick, "kick after undo");
                kickB.assertShownBy(kickStrip, "kick after undo");
                snareB.assertShownBy(snareStrip, "snare after undo");
                verbB.assertShownBy(verbControls, "verb after undo");
                masterB.assertShownBy(masterControls, "master after undo");

                // Redo → A again, then toggleAB → B: both are structural too.
                undo.redo();
                kickA.assertShownBy(kickStrip, "kick after redo");
                view.toggleAB();
                kickB.assertShownBy(kickStrip, "kick after toggleAB");
                verbB.assertShownBy(verbControls, "verb after toggleAB");
                masterB.assertShownBy(masterControls, "master after toggleAB");

                // The snapshots panel's own recall (and its undo) re-seed the
                // same way — Track mirrors included.
                view.getSnapshotsPanel().recallSnapshot(slots.getSlot(MixerSnapshotManager.Slot.A));
                kickA.assertShownBy(kickStrip, "kick after panel recall");
                kickA.assertMirroredBy(kick, "kick after panel recall");
                snareA.assertShownBy(snareStrip, "snare after panel recall");
                verbA.assertShownBy(verbControls, "verb after panel recall");
                undo.undo();
                kickB.assertShownBy(kickStrip, "kick after panel-recall undo");
                kickB.assertMirroredBy(kick, "kick after panel-recall undo");
                snareB.assertMirroredBy(snare, "snare after panel-recall undo");
                masterB.assertShownBy(masterControls, "master after panel-recall undo");
            } finally {
                view.dispose();
            }
        });
    }

    /**
     * Story 322 fix round 1 (S3): the {@code ViewNavigationController} keeps ONE
     * {@code MixerView} and swaps it out of the scene on every view switch, and
     * the view releases its undo-history listener while detached. The panel's
     * recall is a channel-only action, so its heal has to live in the undo
     * entry itself — Edit ▸ Undo from the Arrangement view must leave the
     * {@code Track} mirrors (the arrangement's M / S, the stage tiles) in
     * agreement with the channels the engine reads (§2.10), not only once the
     * user returns to the Mixer.
     */
    @Test
    void panelRecallUndoneWhileTheMixerIsDetachedStillHealsTheTrackMirrors() throws Exception {
        DawProject project = new DawProject("Recall", FORMAT);
        Track kick = project.createAudioTrack("Kick");
        Track snare = project.createAudioTrack("Snare");
        MixerChannel kickCh = project.getMixerChannelForTrack(kick);
        MixerChannel snareCh = project.getMixerChannelForTrack(snare);
        UndoManager undo = new UndoManager();
        MixerSnapshotManager slots = project.getMixerSnapshotManager();
        SceneState kickA = new SceneState(0.8, -0.5, false, false);
        SceneState snareA = new SceneState(0.6, 0.25, true, false);
        SceneState kickB = new SceneState(0.2, 0.9, true, true);
        SceneState snareB = new SceneState(0.1, -0.7, false, true);

        ArrangementStripFixture.onFx(() -> {
            kickA.applyTo(kickCh, kick);
            snareA.applyTo(snareCh, snare);
            slots.setSlot(MixerSnapshotManager.Slot.A, MixerSnapshot.capture(project.getMixer(), "A"));
            kickB.applyTo(kickCh, kick);
            snareB.applyTo(snareCh, snare);

            MixerView view = new MixerView(project, undo);
            StackPane host = new StackPane(view);
            new Scene(host, 900, 600); // mounted, as the navigation controller mounts it
            try {
                MixerChannelStrip kickStrip = view.getTrackStrips().get(0).strip();
                MixerChannelStrip snareStrip = view.getTrackStrips().get(1).strip();

                view.getSnapshotsPanel().recallSnapshot(slots.getSlot(MixerSnapshotManager.Slot.A));
                kickA.assertHeldBy(kickCh, "kick after panel recall");
                kickA.assertMirroredBy(kick, "kick after panel recall");
                snareA.assertMirroredBy(snare, "snare after panel recall");

                // The user switches to the Arrangement view: the cached view
                // leaves the scene and releases its history listener.
                host.getChildren().clear();
                assertThat(view.getScene()).as("detached").isNull();

                // Edit > Undo from the Arrangement view.
                assertThat(undo.undo()).isTrue();
                kickB.assertHeldBy(kickCh, "kick channel after undo while detached");
                snareB.assertHeldBy(snareCh, "snare channel after undo while detached");
                kickB.assertMirroredBy(kick, "kick Track mirror after undo while detached");
                snareB.assertMirroredBy(snare, "snare Track mirror after undo while detached");

                // Back to the Mixer: the same instance re-mounts and its strips
                // agree with the model (they are VM subscribers throughout).
                host.getChildren().add(view);
                kickB.assertShownBy(kickStrip, "kick strip after re-mount");
                snareB.assertShownBy(snareStrip, "snare strip after re-mount");

                // Redo from the Arrangement view heals the same way.
                host.getChildren().clear();
                assertThat(undo.redo()).isTrue();
                kickA.assertHeldBy(kickCh, "kick channel after redo while detached");
                kickA.assertMirroredBy(kick, "kick Track mirror after redo while detached");
                snareA.assertMirroredBy(snare, "snare Track mirror after redo while detached");
                host.getChildren().add(view);
                kickA.assertShownBy(kickStrip, "kick strip after redo + re-mount");
            } finally {
                view.dispose();
            }
        });
    }

    /**
     * Story 322 fix round 1 (S3, the probe's red case): a channel-only history
     * entry that heals nothing itself (a raw {@link RecallSnapshotAction}) and
     * is undone while the Mixer is off-screen leaves the {@code Track} mirrors
     * stale — nobody is listening. When the view returns to the scene it must
     * catch up: re-acquire its listeners and heal, so both surfaces show the
     * engine's state again. Uses the production wiring shape (mixer view +
     * arrangement lanes over one {@code TrackControlWiring}).
     */
    @Test
    void aChannelOnlyRecallUndoneWhileTheMixerIsDetachedIsHealedWhenItReturnsToTheScene() throws Exception {
        DawProject project = new DawProject("Recall", Story322ContractRig.FORMAT);
        Track kick = project.createAudioTrack("Kick");
        Track snare = project.createAudioTrack("Snare");
        MixerChannel kickCh = project.getMixerChannelForTrack(kick);
        MixerChannel snareCh = project.getMixerChannelForTrack(snare);
        SceneState kickA = new SceneState(0.81, -0.62, false, false);
        SceneState snareA = new SceneState(0.55, 0.37, false, false);
        SceneState kickB = new SceneState(0.37, 0.81, true, false);
        SceneState snareB = new SceneState(0.81, -0.62, false, true);

        ArrangementStripFixture.onFx(() -> {
            try (Story322ContractRig rig = new Story322ContractRig(project)) {
                TrackStripController.StripControls kickLane = rig.laneControls(kick);
                TrackStripController.StripControls snareLane = rig.laneControls(snare);
                Consumer<TrackCommand> sink = rig.wiring.commandSink();
                Runnable healThroughTheIntentPath = () -> {
                    for (Track track : List.of(kick, snare)) {
                        MixerChannel channel = project.getMixerChannelForTrack(track);
                        sink.accept(new SetChannelVolumeCommand(channel, channel.getVolume()));
                        sink.accept(new SetChannelPanCommand(channel, channel.getPan()));
                        sink.accept(new ToggleMuteCommand(track, channel.isMuted()));
                        sink.accept(new ToggleSoloCommand(track, channel.isSolo()));
                    }
                };

                kickB.applyTo(kickCh);
                snareB.applyTo(snareCh);
                healThroughTheIntentPath.run();
                MixerSnapshot snapB = MixerSnapshot.capture(project.getMixer(), "B");
                kickA.applyTo(kickCh);
                snareA.applyTo(snareCh);
                healThroughTheIntentPath.run();
                kickA.assertMirroredBy(kick, "kick at start");
                snareA.assertMirroredBy(snare, "snare at start");

                StackPane host = new StackPane(rig.mixerView);
                new Scene(host, 1400, 900);
                host.getChildren().clear(); // the user switched to the Arrangement view

                // A channel-only entry executed while the Mixer is off-screen,
                // healed by its caller (as the panel's Recall does inside its
                // own entry) so both models agree before the undo.
                rig.undoManager.execute(new RecallSnapshotAction(project.getMixer(), snapB));
                healThroughTheIntentPath.run();
                kickB.assertHeldBy(kickCh, "kick after the raw recall");
                kickB.assertMirroredBy(kick, "kick after the raw recall + heal");
                snareB.assertMirroredBy(snare, "snare after the raw recall + heal");

                assertThat(rig.undoManager.undo()).isTrue(); // Edit > Undo from the Arrangement view
                kickA.assertHeldBy(kickCh, "kick channel after undo while detached");
                snareA.assertHeldBy(snareCh, "snare channel after undo while detached");

                host.getChildren().add(rig.mixerView); // the user returns to the Mixer view

                kickA.assertMirroredBy(kick, "kick Track mirror once the Mixer is back");
                snareA.assertMirroredBy(snare, "snare Track mirror once the Mixer is back");
                kickA.assertShownBy(rig.strip(0), "kick strip once the Mixer is back");
                snareA.assertShownBy(rig.strip(1), "snare strip once the Mixer is back");
                assertThat(kickLane.muteBtn().getPseudoClassStates().contains(ACTIVE))
                        .as("arrangement lane shows the engine's mute").isEqualTo(kickCh.isMuted());
                assertThat(snareLane.soloBtn().getPseudoClassStates().contains(ACTIVE))
                        .as("arrangement lane shows the engine's solo").isEqualTo(snareCh.isSolo());
                assertThat(kickLane.volumeSlider().getValue()).isEqualTo(kickA.volume());
                assertThat(snareLane.panSlider().getValue()).isEqualTo(snareA.pan());
            }
        });
    }
}
