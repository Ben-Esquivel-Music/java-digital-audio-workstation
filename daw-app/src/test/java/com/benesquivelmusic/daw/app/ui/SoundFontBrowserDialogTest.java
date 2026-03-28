package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.midi.SoundFontPreset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SoundFontBrowserDialogTest {

    @Test
    void formatPresetDisplayShouldIncludeBankProgramAndName() {
        SoundFontPreset preset = new SoundFontPreset(0, 0, "Acoustic Grand Piano");

        String display = SoundFontBrowserDialog.formatPresetDisplay(preset);

        assertThat(display).isEqualTo("000:000 — Acoustic Grand Piano");
    }

    @Test
    void formatPresetDisplayShouldPadBankAndProgram() {
        SoundFontPreset preset = new SoundFontPreset(128, 48, "String Ensemble");

        String display = SoundFontBrowserDialog.formatPresetDisplay(preset);

        assertThat(display).isEqualTo("128:048 — String Ensemble");
    }

    @Test
    void formatPresetDisplayShouldHandleHighBankNumbers() {
        SoundFontPreset preset = new SoundFontPreset(16383, 127, "Drums");

        String display = SoundFontBrowserDialog.formatPresetDisplay(preset);

        assertThat(display).isEqualTo("16383:127 — Drums");
    }
}
