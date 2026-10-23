package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MIDI grid column is a unit of the saved project: schema 2 of
 * {@code project.daw} stores note starts and lengths and CC breakpoints in
 * columns, as the model holds them, with no conversion on save or open. The
 * piano roll, and every daw-app view that converts its columns to beats
 * through it, must keep the unit those files were written with; changing it
 * would silently re-time every saved note, so it takes a project migration.
 * daw-core pins its own converters in {@code MidiColumnUnitContractTest}.
 * (The constants are package-private, hence this test's package.)
 */
class MidiEditorColumnUnitContractTest {

    private static final String CONTRACT =
            "%s: schema-2 MIDI column = 0.25 beats; changing it requires a project migration";

    @Test
    void thePianoRollColumnIsTheSchemaTwoColumnUnit() {
        assertThat(MidiEditorView.BEATS_PER_COLUMN)
                .as(CONTRACT, "MidiEditorView.BEATS_PER_COLUMN")
                .isEqualTo(0.25);
        assertThat(EditorView.BEATS_PER_COLUMN)
                .as(CONTRACT, "EditorView.BEATS_PER_COLUMN")
                .isEqualTo(0.25);
        assertThat(ArrangementCanvas.BEATS_PER_COLUMN)
                .as(CONTRACT, "ArrangementCanvas.BEATS_PER_COLUMN")
                .isEqualTo(0.25);
        assertThat(SelectionModel.BEATS_PER_COLUMN)
                .as(CONTRACT, "SelectionModel.BEATS_PER_COLUMN")
                .isEqualTo(0.25);
    }
}
