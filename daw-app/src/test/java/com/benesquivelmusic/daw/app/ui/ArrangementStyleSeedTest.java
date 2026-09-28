package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.css.PseudoClass;
import javafx.scene.layout.HBox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 — "Strip rebuild: mute/arm/solo styles seeded from model at
 * build" (Audio Engine Wiring Design Book §5.6). The arrangement strip's
 * button state is the binder-driven {@code :active} pseudo-class, seeded from
 * the {@code TrackVM} at bind time, so rebuilding a strip over a muted /
 * soloed / armed track renders the active style without any click — and no
 * inline {@code setStyle} hex is involved.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ArrangementStyleSeedTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 256);
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");

    @Test
    void rebuildingAStripOverMutedSoloedArmedTrackRendersActiveWithoutAClick() throws Exception {
        DawProject project = new DawProject("Seed", FORMAT);
        Track hot = project.createAudioTrack("Hot");
        hot.setMuted(true);
        hot.setSolo(true);
        hot.setArmed(true);
        project.getMixerChannelForTrack(hot).setMuted(true);
        project.getMixerChannelForTrack(hot).setSolo(true);
        project.getMixerChannelForTrack(hot).setVolume(0.3);
        project.getMixerChannelForTrack(hot).setPan(0.6);
        Track cold = project.createAudioTrack("Cold");

        ArrangementStripFixture rig = ArrangementStripFixture.onFx(
                () -> new ArrangementStripFixture(project, true));
        try {
            ArrangementStripFixture.onFx(() -> {
                HBox hotStrip = rig.addStrip(hot);
                TrackStripController.StripControls hotControls = ArrangementStripFixture.controlsOf(hotStrip);
                assertThat(hotControls.muteBtn().getPseudoClassStates()).contains(ACTIVE);
                assertThat(hotControls.soloBtn().getPseudoClassStates()).contains(ACTIVE);
                assertThat(hotControls.armBtn().getPseudoClassStates()).contains(ACTIVE);
                assertThat(hotControls.volumeSlider().getValue()).isEqualTo(0.3);
                assertThat(hotControls.panSlider().getValue()).isEqualTo(0.6);
                for (var button : java.util.List.of(hotControls.muteBtn(), hotControls.soloBtn(), hotControls.armBtn())) {
                    assertThat(button.getStyle()).as("no inline style on " + button.getTooltip().getText()).isEmpty();
                }

                HBox coldStrip = rig.addStrip(cold);
                TrackStripController.StripControls coldControls = ArrangementStripFixture.controlsOf(coldStrip);
                assertThat(coldControls.muteBtn().getPseudoClassStates()).doesNotContain(ACTIVE);
                assertThat(coldControls.soloBtn().getPseudoClassStates()).doesNotContain(ACTIVE);
                assertThat(coldControls.armBtn().getPseudoClassStates()).doesNotContain(ACTIVE);

                // Rebuild (remove + add the same node) over the still-muted
                // track: seeded again from the model, not from any click.
                rig.trackListPanel.getChildren().remove(hotStrip);
                assertThat(hotControls.muteBtn().getPseudoClassStates()).contains(ACTIVE);
                rig.trackListPanel.getChildren().add(hotStrip);
                assertThat(hotControls.muteBtn().getPseudoClassStates()).contains(ACTIVE);
                assertThat(hotControls.armBtn().getPseudoClassStates()).contains(ACTIVE);
                assertThat(rig.raised).as("seeding raises no command").isEmpty();
            });
        } finally {
            ArrangementStripFixture.onFx(rig::close);
        }
    }
}
