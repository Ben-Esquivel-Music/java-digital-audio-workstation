package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.export.MidiFileExporter;
import com.benesquivelmusic.daw.core.midi.KeyboardProcessor;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.midi.MidiRecorder;
import com.benesquivelmusic.daw.core.project.edit.SlipEditService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MIDI grid column is a unit of the saved project: schema 2 of
 * {@code project.daw} stores note starts and lengths and CC breakpoints in
 * columns, as the model holds them, with no conversion on save or open. Every
 * daw-core converter between columns and transport beats — the renderer that
 * plays a clip, the recorder that captures one, the keyboard processor, the
 * slip edit of a MIDI clip and the Standard MIDI File export — must keep
 * using the unit those files were written with; changing it would silently
 * re-time every saved note, so it takes a project migration. (The renderer's
 * constant is package-private, hence this test's package.)
 */
class MidiColumnUnitContractTest {

    private static final double SCHEMA_2_BEATS_PER_COLUMN = 0.25;
    private static final String CONTRACT =
            "%s: schema-2 MIDI column = 0.25 beats; changing it requires a project migration";

    @Test
    void everyColumnToBeatConverterUsesTheSchemaTwoColumnUnit() {
        assertThat(MidiTrackRenderer.BEATS_PER_COLUMN)
                .as(CONTRACT, "MidiTrackRenderer.BEATS_PER_COLUMN")
                .isEqualTo(SCHEMA_2_BEATS_PER_COLUMN);
        assertThat(MidiRecorder.BEATS_PER_COLUMN)
                .as(CONTRACT, "MidiRecorder.BEATS_PER_COLUMN")
                .isEqualTo(SCHEMA_2_BEATS_PER_COLUMN);
        assertThat(KeyboardProcessor.BEATS_PER_COLUMN)
                .as(CONTRACT, "KeyboardProcessor.BEATS_PER_COLUMN")
                .isEqualTo(SCHEMA_2_BEATS_PER_COLUMN);
        assertThat(1.0 / MidiFileExporter.COLUMNS_PER_QUARTER)
                .as(CONTRACT, "MidiFileExporter.COLUMNS_PER_QUARTER")
                .isEqualTo(SCHEMA_2_BEATS_PER_COLUMN);

        // The slip edit holds no constant of its own: what it reports is the
        // unit, so slip a one-note clip by four columns.
        MidiClip clip = new MidiClip();
        clip.addNote(new MidiNoteData(60, 0, 4, 100, 0));
        assertThat(SlipEditService.buildMidiSlip(clip, 4).appliedBeatDelta() / 4)
                .as(CONTRACT, "SlipEditService.buildMidiSlip")
                .isEqualTo(SCHEMA_2_BEATS_PER_COLUMN);
    }
}
