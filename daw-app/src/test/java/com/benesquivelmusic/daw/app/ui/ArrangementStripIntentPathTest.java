package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.vm.command.RenameTrackCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.SetChannelPanCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.SetChannelVolumeCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleArmCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleMuteCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleSoloCommand;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.css.PseudoClass;
import javafx.scene.layout.HBox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 — the arrangement strip's mute / solo / arm buttons and volume /
 * pan sliders drive the ONE intent path (Audio Engine Wiring Design Book
 * §2.10, §5.6 "Arrangement strip vol/pan/mute/solo"): a gesture raises a
 * {@code TrackCommand} through the wiring's sink, and only the handler
 * writes the model — {@code Track} and {@code MixerChannel} in one
 * dual-write. With a recording sink that does <em>not</em> execute, the
 * model must stay untouched: that is the proof the strip never writes it
 * directly.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ArrangementStripIntentPathTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 256);
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");

    @Test
    void gesturesRaiseCommandsAndNeverWriteTheModelDirectly() throws Exception {
        DawProject project = new DawProject("Intent", FORMAT);
        Track track = project.createAudioTrack("Vox");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        ArrangementStripFixture rig = ArrangementStripFixture.onFx(
                () -> new ArrangementStripFixture(project, false));
        try {
            ArrangementStripFixture.onFx(() -> {
                HBox strip = rig.addStrip(track);
                TrackStripController.StripControls controls = ArrangementStripFixture.controlsOf(strip);

                controls.muteBtn().fire();
                controls.soloBtn().fire();
                controls.armBtn().fire();
                controls.volumeSlider().setValue(0.25);
                controls.panSlider().setValue(-0.5);

                assertThat(rig.raised).hasSize(5);
                assertThat(rig.raised.get(0)).isEqualTo(new ToggleMuteCommand(track, true));
                assertThat(rig.raised.get(1)).isEqualTo(new ToggleSoloCommand(track, true));
                assertThat(rig.raised.get(2)).isEqualTo(new ToggleArmCommand(track, true));
                assertThat(rig.raised.get(3)).isEqualTo(new SetChannelVolumeCommand(channel, 0.25));
                assertThat(rig.raised.get(4)).isEqualTo(new SetChannelPanCommand(channel, -0.5));

                // The recording sink did not execute: nothing on the strip wrote the model.
                assertThat(track.isMuted()).isFalse();
                assertThat(track.isSolo()).isFalse();
                assertThat(track.isArmed()).isFalse();
                assertThat(track.getVolume()).isEqualTo(1.0);
                assertThat(track.getPan()).isEqualTo(0.0);
                assertThat(channel.isMuted()).isFalse();
                assertThat(channel.isSolo()).isFalse();
                assertThat(channel.getVolume()).isEqualTo(1.0);
                assertThat(channel.getPan()).isEqualTo(0.0);
                // ...and the buttons' :active state, a VM subscriber, stayed off.
                assertThat(controls.muteBtn().getPseudoClassStates()).doesNotContain(ACTIVE);
            });
        } finally {
            ArrangementStripFixture.onFx(rig::close);
        }
    }

    @Test
    void executedGesturesMoveBothTrackAndMixerChannelThroughTheWiring() throws Exception {
        DawProject project = new DawProject("Intent", FORMAT);
        Track track = project.createAudioTrack("Vox");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        ArrangementStripFixture rig = ArrangementStripFixture.onFx(
                () -> new ArrangementStripFixture(project, true));
        try {
            ArrangementStripFixture.onFx(() -> {
                HBox strip = rig.addStrip(track);
                TrackStripController.StripControls controls = ArrangementStripFixture.controlsOf(strip);

                controls.muteBtn().fire();
                assertThat(track.isMuted()).isTrue();
                assertThat(channel.isMuted()).isTrue();
                assertThat(controls.muteBtn().getPseudoClassStates()).contains(ACTIVE);
                assertThat(rig.statusBarLabel.getText()).isEqualTo("Muted: Vox");

                controls.soloBtn().fire();
                assertThat(track.isSolo()).isTrue();
                assertThat(channel.isSolo()).isTrue();
                assertThat(controls.soloBtn().getPseudoClassStates()).contains(ACTIVE);
                assertThat(rig.statusBarLabel.getText()).isEqualTo("Solo: Vox");

                controls.armBtn().fire();
                assertThat(track.isArmed()).isTrue();
                assertThat(controls.armBtn().getPseudoClassStates()).contains(ACTIVE);
                assertThat(rig.statusBarLabel.getText()).isEqualTo("Armed: Vox");

                controls.volumeSlider().setValue(0.25);
                assertThat(channel.getVolume()).isEqualTo(0.25);
                assertThat(track.getVolume()).as("dual-write onto the Track").isEqualTo(0.25);

                controls.panSlider().setValue(-0.5);
                assertThat(channel.getPan()).isEqualTo(-0.5);
                assertThat(track.getPan()).as("dual-write onto the Track").isEqualTo(-0.5);

                // A second click toggles back through the same path.
                controls.muteBtn().fire();
                assertThat(track.isMuted()).isFalse();
                assertThat(channel.isMuted()).isFalse();
                assertThat(controls.muteBtn().getPseudoClassStates()).doesNotContain(ACTIVE);
                assertThat(rig.statusBarLabel.getText()).isEqualTo("Unmuted: Vox");
            });
        } finally {
            ArrangementStripFixture.onFx(rig::close);
        }
    }

    @Test
    void mixerSideChangesMoveTheArrangementControlsWithoutRaisingCommands() throws Exception {
        // The reverse direction of §5.6: a change made elsewhere (the mixer
        // strip, automation, undo) republishes through the VM and the
        // arrangement controls follow as subscribers — no echo command.
        DawProject project = new DawProject("Intent", FORMAT);
        Track track = project.createAudioTrack("Vox");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        ArrangementStripFixture rig = ArrangementStripFixture.onFx(
                () -> new ArrangementStripFixture(project, true));
        try {
            ArrangementStripFixture.onFx(() -> {
                HBox strip = rig.addStrip(track);
                TrackStripController.StripControls controls = ArrangementStripFixture.controlsOf(strip);

                new ToggleMuteCommand(track, true).execute(rig.handler);
                new SetChannelVolumeCommand(channel, 0.5).execute(rig.handler);
                new SetChannelPanCommand(channel, 0.75).execute(rig.handler);

                assertThat(controls.muteBtn().getPseudoClassStates()).contains(ACTIVE);
                assertThat(controls.volumeSlider().getValue()).isEqualTo(0.5);
                assertThat(controls.panSlider().getValue()).isEqualTo(0.75);
                assertThat(rig.raised).as("VM-driven refreshes raise no command").isEmpty();
            });
        } finally {
            ArrangementStripFixture.onFx(rig::close);
        }
    }

    /**
     * Story 322 fix round 1 (S5): the lane's name label is a {@code TrackVM.name}
     * subscriber, so a rename made anywhere (the mixer strip's inline editor,
     * an undo) reaches the arrangement; once the strip leaves the panel its
     * binding is disposed and the label no longer follows.
     */
    @Test
    void theLaneNameFollowsARenameThroughTheHandlerUntilTheStripIsUnbound() throws Exception {
        DawProject project = new DawProject("Intent", FORMAT);
        Track track = project.createAudioTrack("Vox");
        ArrangementStripFixture rig = ArrangementStripFixture.onFx(
                () -> new ArrangementStripFixture(project, true));
        try {
            ArrangementStripFixture.onFx(() -> {
                HBox strip = rig.addStrip(track);
                TrackStripController.StripControls controls = ArrangementStripFixture.controlsOf(strip);
                assertThat(controls.nameLabel().getText()).isEqualTo("Vox");

                new RenameTrackCommand(track, "Lead Vox").execute(rig.handler);
                assertThat(controls.nameLabel().getText()).as("lane label follows TrackVM.name").isEqualTo("Lead Vox");
                assertThat(rig.raised).as("a VM-driven refresh raises no command").isEmpty();

                rig.trackListPanel.getChildren().remove(strip);
                assertThat(rig.controller.isBound(strip)).isFalse();
                new RenameTrackCommand(track, "Backing Vox").execute(rig.handler);
                assertThat(track.getName()).isEqualTo("Backing Vox");
                assertThat(controls.nameLabel().getText()).as("an unbound strip no longer follows").isEqualTo("Lead Vox");
            });
        } finally {
            ArrangementStripFixture.onFx(rig::close);
        }
    }

    @Test
    void bindingFollowsTheStripsPresenceInThePanelAcrossRemoveAndUndo() throws Exception {
        DawProject project = new DawProject("Intent", FORMAT);
        Track track = project.createAudioTrack("Vox");
        ArrangementStripFixture rig = ArrangementStripFixture.onFx(
                () -> new ArrangementStripFixture(project, true));
        try {
            ArrangementStripFixture.onFx(() -> {
                HBox strip = rig.addStrip(track);
                assertThat(rig.controller.isBound(strip)).isTrue();

                // Remove: the registry disposes the track's VMs; the strip unbinds.
                project.removeTrack(track);
                rig.trackListPanel.getChildren().remove(strip);
                assertThat(rig.controller.isBound(strip)).isFalse();
                assertThat(rig.registry.trackVm(java.util.UUID.fromString(track.getId()))).isNull();

                // Undo: re-add the track (fresh VMs) and the same strip node —
                // it re-binds against the fresh VMs and drives the model again.
                project.addTrack(track);
                rig.trackListPanel.getChildren().add(strip);
                assertThat(rig.controller.isBound(strip)).isTrue();
                TrackStripController.StripControls controls = ArrangementStripFixture.controlsOf(strip);
                controls.muteBtn().fire();
                assertThat(track.isMuted()).isTrue();
                assertThat(project.getMixerChannelForTrack(track).isMuted()).isTrue();

                // dispose() releases everything and a later re-add stays inert.
                rig.controller.dispose();
                assertThat(rig.controller.isBound(strip)).isFalse();
                rig.trackListPanel.getChildren().remove(strip);
                rig.trackListPanel.getChildren().add(strip);
                assertThat(rig.controller.isBound(strip)).isFalse();
            });
        } finally {
            ArrangementStripFixture.onFx(rig::close);
        }
    }
}
