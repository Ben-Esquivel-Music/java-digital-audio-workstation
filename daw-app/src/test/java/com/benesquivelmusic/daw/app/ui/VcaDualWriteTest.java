package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.vm.command.ToggleChannelMuteCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleMuteCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleSoloCommand;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.VcaGroup;
import com.benesquivelmusic.daw.core.mixer.VcaGroupManager;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 — "VCA mute/solo: same dual-write path as strip buttons — Track +
 * arrangement stay consistent" (Audio Engine Wiring Design Book §5.6). A
 * {@link VcaStrip} raises intents through the shared wiring: a member with a
 * track gets {@code ToggleMuteCommand}/{@code ToggleSoloCommand} (dual-write
 * Track + MixerChannel), a track-less member {@code ToggleChannelMuteCommand}
 * — and both the arrangement strip and the mixer strip follow as VM
 * subscribers. The member badge shows the composite VCA gain readout.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class VcaDualWriteTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 256);
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");
    /** The mixer strip's own {@code :muted} pseudo-class (story 271 MixerChannelStrip). */
    private static final PseudoClass MUTED = PseudoClass.getPseudoClass("muted");

    @Test
    void vcaMuteAndSoloUpdateTrackChannelAndBothSurfaces() throws Exception {
        DawProject project = new DawProject("VCA", FORMAT);
        Track kick = project.createAudioTrack("Kick");
        Track snare = project.createAudioTrack("Snare");
        UUID kickId = UUID.fromString(kick.getId());
        UUID snareId = UUID.fromString(snare.getId());
        MixerChannel kickCh = project.getMixerChannelForTrack(kick);
        MixerChannel snareCh = project.getMixerChannelForTrack(snare);
        project.getVcaGroupManager().createVcaGroup("Drums", List.of(kickId, snareId));

        ArrangementStripFixture rig = ArrangementStripFixture.onFx(
                () -> new ArrangementStripFixture(project, true));
        try {
            ArrangementStripFixture.onFx(() -> {
                rig.mixerView.setTrackControlWiring(() -> rig.wiring);
                HBox kickLane = rig.addStrip(kick);
                HBox snareLane = rig.addStrip(snare);

                vcaStrip(rig.mixerView).getMuteButton().fire();
                assertThat(kick.isMuted()).as("Track mute (kick)").isTrue();
                assertThat(snare.isMuted()).as("Track mute (snare)").isTrue();
                assertThat(kickCh.isMuted()).as("MixerChannel mute (kick)").isTrue();
                assertThat(snareCh.isMuted()).as("MixerChannel mute (snare)").isTrue();
                assertThat(rig.registry.trackVm(kickId).isMuted()).as("TrackVM mute").isTrue();
                assertThat(rig.registry.trackVm(snareId).isMuted()).isTrue();
                assertThat(ArrangementStripFixture.controlsOf(kickLane).muteBtn().getPseudoClassStates())
                        .as("arrangement strip follows").contains(ACTIVE);
                assertThat(ArrangementStripFixture.controlsOf(snareLane).muteBtn().getPseudoClassStates())
                        .contains(ACTIVE);
                assertThat(rig.mixerView.getTrackStrips().get(0).strip().isMuted())
                        .as("mixer strip follows").isTrue();
                assertThat(rig.mixerView.getTrackStrips().get(0).strip().getPseudoClassStates())
                        .as("mixer strip :muted").contains(MUTED);
                assertThat(rig.raised).as("track-targeted intents, one per member")
                        .containsExactly(new ToggleMuteCommand(kick, true), new ToggleMuteCommand(snare, true));

                rig.raised.clear();
                vcaStrip(rig.mixerView).getSoloButton().fire();
                assertThat(kick.isSolo()).isTrue();
                assertThat(snare.isSolo()).isTrue();
                assertThat(kickCh.isSolo()).isTrue();
                assertThat(snareCh.isSolo()).isTrue();
                assertThat(rig.registry.trackVm(kickId).isSoloed()).isTrue();
                assertThat(ArrangementStripFixture.controlsOf(kickLane).soloBtn().getPseudoClassStates())
                        .contains(ACTIVE);
                assertThat(rig.mixerView.getTrackStrips().get(1).strip().isSoloed())
                        .as("mixer strip solo follows").isTrue();
                assertThat(rig.raised)
                        .containsExactly(new ToggleSoloCommand(kick, true), new ToggleSoloCommand(snare, true));

                // The toggle is coherent: the next click clears every member.
                vcaStrip(rig.mixerView).getMuteButton().fire();
                assertThat(kick.isMuted()).isFalse();
                assertThat(snareCh.isMuted()).isFalse();
                assertThat(ArrangementStripFixture.controlsOf(snareLane).muteBtn().getPseudoClassStates())
                        .doesNotContain(ACTIVE);
                assertThat(rig.mixerView.getTrackStrips().get(1).strip().isMuted())
                        .as("mixer strip unmuted with the members").isFalse();
            });
        } finally {
            ArrangementStripFixture.onFx(rig::close);
        }
    }

    @Test
    void aMemberWithoutATrackIsMutedThroughTheChannelCommand() throws Exception {
        DawProject project = new DawProject("VCA", FORMAT);
        MixerChannel aux = new MixerChannel("Aux");
        project.getMixer().addChannel(aux);
        project.getVcaGroupManager().createVcaGroup("Stems", List.of(aux.getId()));

        ArrangementStripFixture rig = ArrangementStripFixture.onFx(
                () -> new ArrangementStripFixture(project, true));
        try {
            ArrangementStripFixture.onFx(() -> {
                rig.mixerView.setTrackControlWiring(() -> rig.wiring);
                vcaStrip(rig.mixerView).getMuteButton().fire();
                assertThat(aux.isMuted()).isTrue();
                assertThat(rig.raised).containsExactly(new ToggleChannelMuteCommand(aux, true));
            });
        } finally {
            ArrangementStripFixture.onFx(rig::close);
        }
    }

    @Test
    void memberBadgeShowsTheCompositeGainReadout() throws Exception {
        DawProject project = new DawProject("VCA", FORMAT);
        Track kick = project.createAudioTrack("Kick");
        UUID kickId = UUID.fromString(kick.getId());
        VcaGroupManager vcas = project.getVcaGroupManager();
        VcaGroup drums = vcas.createVcaGroup("Drums", List.of(kickId));
        vcas.setMasterGainDb(drums.id(), -6.0);

        ArrangementStripFixture.onFx(() -> {
            MixerView view = new MixerView(project);
            try {
                assertThat(badgeTexts(view)).containsExactly("VCA: Drums (-6.0 dB)");

                // Composite: a second group riding the same channel sums in dB.
                VcaGroup bus = vcas.createVcaGroup("Bus", List.of(kickId));
                vcas.setMasterGainDb(bus.id(), 2.5);
                view.refresh();
                assertThat(badgeTexts(view)).containsExactly("VCA: Drums (-3.5 dB)", "VCA: Bus (-3.5 dB)");

                // A group at the floor reads as silence.
                vcas.setMasterGainDb(bus.id(), VcaGroup.MIN_GAIN_DB);
                view.refresh();
                assertThat(badgeTexts(view)).containsExactly("VCA: Drums (-∞ dB)", "VCA: Bus (-∞ dB)");

                // A committed VCA fader move (release) rebuilds the readout.
                vcas.setMasterGainDb(bus.id(), 0.0);
                VcaStrip busStrip = (VcaStrip) view.getVcaStrips().getChildren().get(1);
                busStrip.getGainFader().setValue(1.5);
                busStrip.getGainFader().getOnMouseReleased().handle(null);
                assertThat(badgeTexts(view)).containsExactly("VCA: Drums (-4.5 dB)", "VCA: Bus (-4.5 dB)");
            } finally {
                view.dispose();
            }
        });
    }

    private static VcaStrip vcaStrip(MixerView view) {
        return (VcaStrip) view.getVcaStrips().getChildren().getFirst();
    }

    private static List<String> badgeTexts(MixerView view) {
        List<String> texts = new ArrayList<>();
        for (Node strip : view.getChannelStrips().getChildren()) {
            if (strip instanceof Parent p) {
                collectBadges(p, texts);
            }
        }
        return texts;
    }

    private static void collectBadges(Parent parent, List<String> into) {
        for (Node n : parent.getChildrenUnmodifiable()) {
            if (n instanceof Label label && label.getStyleClass().contains("mixer-vca-badge")) {
                into.add(label.getText());
            } else if (n instanceof Parent p) {
                collectBadges(p, into);
            }
        }
    }
}
