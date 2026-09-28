package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.css.PseudoClass;
import javafx.scene.control.Button;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 — "Strip rebuild: mute/arm/solo styles seeded from model at
 * build" for the <em>mixer</em> strips (Audio Engine Wiring Design Book
 * §5.6). A track strip is a {@link MixerChannelStrip} (the story-271 skin
 * swap): its mute / solo / arm state is the strip's own {@code :muted} /
 * {@code :soloed} / {@code :armed} pseudo-class, mirrored from the VM at bind
 * time — so building, and rebuilding, the strips over muted / soloed / armed
 * channels shows the active state without any click and with no inline
 * {@code setStyle} hex. Return-bus and master strips render theirs as the
 * binder-driven {@code :active} pseudo-class on their buttons. The solo-safe
 * ring is the {@link MixerView#SOLO_SAFE} pseudo-class — on the strip control
 * for track strips, on the solo button for return strips — likewise a
 * function of the model.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class StyleSeedTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 256);
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");
    private static final PseudoClass MUTED = PseudoClass.getPseudoClass("muted");
    private static final PseudoClass SOLOED = PseudoClass.getPseudoClass("soloed");
    private static final PseudoClass ARMED = PseudoClass.getPseudoClass("armed");

    @Test
    void rebuildingStripsOverMutedSoloedArmedChannelsRendersActiveWithoutAClick() throws Exception {
        DawProject project = new DawProject("Seed", FORMAT);
        Track hot = project.createAudioTrack("Hot");
        Track cold = project.createAudioTrack("Cold");
        MixerChannel hotCh = project.getMixerChannelForTrack(hot);
        MixerChannel coldCh = project.getMixerChannelForTrack(cold);
        MixerChannel verb = project.getMixer().addReturnBus("Verb");
        MixerChannel master = project.getMixer().getMasterChannel();

        ArrangementStripFixture.onFx(() -> {
            hot.setMuted(true);
            hot.setSolo(true);
            hot.setArmed(true);
            hotCh.setMuted(true);
            hotCh.setSolo(true);
            hotCh.setSoloSafe(true);
            coldCh.setSoloSafe(false);
            verb.setMuted(true);
            verb.setSolo(true);
            master.setMuted(true);

            // The project owns a default return bus; Verb is the one added here.
            int verbIndex = project.getMixer().getReturnBuses().indexOf(verb);
            assertThat(verbIndex).isNotNegative();

            MixerView view = new MixerView(project);
            try {
                assertSeeded(view, hotCh, coldCh, verb, verbIndex, "built");
                // Rebuild over the unchanged model: seeded again, no click.
                view.refresh();
                assertSeeded(view, hotCh, coldCh, verb, verbIndex, "rebuilt");

                // Solo-safe is model-driven too: flip it, re-sync (the undo /
                // context-menu path), and the ring follows.
                hotCh.setSoloSafe(false);
                view.refresh();
                assertThat(view.getTrackStrips().get(0).strip().getPseudoClassStates())
                        .doesNotContain(MixerView.SOLO_SAFE);
            } finally {
                view.dispose();
            }
        });
    }

    private static void assertSeeded(MixerView view, MixerChannel hotCh, MixerChannel coldCh,
                                     MixerChannel verb, int verbIndex, String when) {
        List<MixerView.TrackStripHandles> tracks = view.getTrackStrips();
        assertThat(tracks).hasSize(2);
        MixerChannelStrip hotStrip = tracks.get(0).strip();
        MixerChannelStrip coldStrip = tracks.get(1).strip();

        assertThat(hotStrip.isMuted()).as("hot mute " + when).isTrue();
        assertThat(hotStrip.isSoloed()).as("hot solo " + when).isTrue();
        assertThat(hotStrip.isArmed()).as("hot arm " + when).isTrue();
        assertThat(hotStrip.getPseudoClassStates()).as("hot pseudo-classes " + when)
                .contains(MUTED, SOLOED, ARMED, MixerView.SOLO_SAFE);
        assertThat(hotCh.isSoloSafe()).isTrue();

        assertThat(coldStrip.isMuted()).as("cold mute " + when).isFalse();
        assertThat(coldStrip.isSoloed()).as("cold solo " + when).isFalse();
        assertThat(coldStrip.isArmed()).as("cold arm " + when).isFalse();
        assertThat(coldStrip.getPseudoClassStates()).as("cold pseudo-classes " + when)
                .doesNotContain(MUTED, SOLOED, ARMED, MixerView.SOLO_SAFE);
        assertThat(coldCh.isSoloSafe()).isFalse();

        List<MixerView.MixerStripControls> returns = view.getReturnStripControls();
        assertThat(returns).hasSizeGreaterThan(verbIndex);
        MixerView.MixerStripControls verbControls = returns.get(verbIndex);
        assertThat(verbControls.muteBtn().getPseudoClassStates()).as("return mute " + when).contains(ACTIVE);
        assertThat(verbControls.soloBtn().getPseudoClassStates()).as("return solo " + when).contains(ACTIVE);
        assertThat(verbControls.soloBtn().getPseudoClassStates().contains(MixerView.SOLO_SAFE))
                .as("return ring mirrors the model " + when).isEqualTo(verb.isSoloSafe());

        MixerView.MixerStripControls masterControls = view.getMasterStripControls();
        assertThat(masterControls.muteBtn().getPseudoClassStates()).as("master mute " + when).contains(ACTIVE);

        // No inline hex anywhere: the style is CSS, driven by pseudo-classes.
        for (MixerChannelStrip strip : List.of(hotStrip, coldStrip)) {
            assertThat(strip.getStyle()).as("no inline style " + when + " on " + strip.getChannelName()).isEmpty();
        }
        for (Button button : List.of(verbControls.muteBtn(), verbControls.soloBtn(), masterControls.muteBtn())) {
            assertThat(button.getStyle()).as("no inline style " + when + " on " + button.getText()).isEmpty();
        }
    }
}
