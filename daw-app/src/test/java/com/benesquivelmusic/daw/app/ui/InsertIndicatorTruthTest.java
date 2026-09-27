package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 — "Strip insert indicator: rendered from the channel's real
 * InsertSlot list (name/bypass); replaces the hardcoded five-icon fiction"
 * (Audio Engine Wiring Design Book §5.6). The arrangement strip's insert row
 * is a {@code ChannelVM.inserts} subscriber for EVERY track type: add /
 * bypass / remove an insert and the row follows; an empty rack shows the
 * "(no inserts)" placeholder; no Gain/Gate/Comp/HPF/Limiter or MIDI
 * instrument-hint icon survives.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class InsertIndicatorTruthTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 256);
    private static final List<String> FICTION_TOOLTIPS =
            List.of("Gain", "Gate", "Compressor", "High-Pass Filter", "Limiter", "Velocity / Normalize");

    @Test
    void indicatorRowFollowsTheRealRackOnAddBypassAndRemove() throws Exception {
        DawProject project = new DawProject("Inserts", FORMAT);
        Track track = project.createAudioTrack("Vox");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        ArrangementStripFixture rig = ArrangementStripFixture.onFx(
                () -> new ArrangementStripFixture(project, true));
        try {
            ArrangementStripFixture.onFx(() -> {
                HBox strip = rig.addStrip(track);
                HBox row = ArrangementStripFixture.controlsOf(strip).insertChain();

                assertPlaceholderOnly(row);

                channel.addInsert(new InsertSlot("Compressor", new Passthrough()));
                channel.addInsert(new InsertSlot("Reverb", new Passthrough()));
                assertThat(indicatorTooltips(row)).containsExactly("Compressor", "Reverb");
                assertThat(indicatorTexts(row)).containsExactly("COM", "REV");
                assertThat(bypassedFlags(row)).containsExactly(false, false);

                channel.setInsertBypassed(0, true);
                assertThat(indicatorTooltips(row)).containsExactly("Compressor (bypassed)", "Reverb");
                assertThat(bypassedFlags(row)).containsExactly(true, false);

                channel.removeInsert(0);
                assertThat(indicatorTooltips(row)).containsExactly("Reverb");

                channel.removeInsert(0);
                assertPlaceholderOnly(row);
            });
        } finally {
            ArrangementStripFixture.onFx(rig::close);
        }
    }

    @Test
    void noPlaceholderFictionRemainsForAnyTrackType() throws Exception {
        DawProject project = new DawProject("Inserts", FORMAT);
        Track audio = project.createAudioTrack("Guitar");
        Track midi = project.createMidiTrack("Drums");
        ArrangementStripFixture rig = ArrangementStripFixture.onFx(
                () -> new ArrangementStripFixture(project, true));
        try {
            ArrangementStripFixture.onFx(() -> {
                for (Track track : List.of(audio, midi)) {
                    HBox strip = rig.addStrip(track);
                    HBox row = ArrangementStripFixture.controlsOf(strip).insertChain();
                    assertPlaceholderOnly(row);
                    // No fiction anywhere on the strip, not just in the row.
                    for (Node node : strip.lookupAll("*")) {
                        Tooltip tip = node instanceof javafx.scene.control.Control c ? c.getTooltip() : null;
                        if (tip != null) {
                            assertThat(FICTION_TOOLTIPS).doesNotContain(tip.getText());
                            assertThat(tip.getText()).doesNotStartWith("Instrument:");
                        }
                    }
                }
                // A MIDI track's real rack renders too (all track types).
                project.getMixerChannelForTrack(midi).addInsert(new InsertSlot("Chorus", new Passthrough()));
                HBox midiRow = ArrangementStripFixture.controlsOf(
                        (HBox) rig.trackListPanel.getChildren().get(2)).insertChain();
                assertThat(indicatorTooltips(midiRow)).containsExactly("Chorus");
            });
        } finally {
            ArrangementStripFixture.onFx(rig::close);
        }
    }

    private static void assertPlaceholderOnly(HBox row) {
        assertThat(row.getChildren()).hasSize(1);
        Label placeholder = (Label) row.getChildren().get(0);
        assertThat(placeholder.getText()).isEqualTo(TrackStripController.NO_INSERTS_TEXT);
        assertThat(placeholder.getStyleClass()).contains("track-insert-placeholder");
    }

    private static List<String> indicatorTooltips(HBox row) {
        return row.getChildren().stream()
                .map(Label.class::cast)
                .peek(label -> assertThat(label.getStyleClass()).contains("track-insert-indicator"))
                .map(label -> label.getTooltip().getText())
                .toList();
    }

    private static List<String> indicatorTexts(HBox row) {
        return row.getChildren().stream().map(Label.class::cast).map(Label::getText).toList();
    }

    private static List<Boolean> bypassedFlags(HBox row) {
        return row.getChildren().stream()
                .map(node -> node.getStyleClass().contains(TrackStripController.BYPASSED_STYLE_CLASS))
                .toList();
    }

    /** A processor with no capability tags. */
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
