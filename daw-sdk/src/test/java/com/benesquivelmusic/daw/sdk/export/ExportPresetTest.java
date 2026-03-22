package com.benesquivelmusic.daw.sdk.export;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExportPresetTest {

    @Test
    void cdPresetShouldBeWav44100_16BitWithTpdf() {
        ExportPreset preset = ExportPreset.CD;
        assertThat(preset.name()).isEqualTo("CD Quality");
        assertThat(preset.config().format()).isEqualTo(AudioExportFormat.WAV);
        assertThat(preset.config().sampleRate()).isEqualTo(44_100);
        assertThat(preset.config().bitDepth()).isEqualTo(16);
        assertThat(preset.config().ditherType()).isEqualTo(DitherType.TPDF);
    }

    @Test
    void streamingPresetShouldBeFlac44100_16BitWithTpdf() {
        ExportPreset preset = ExportPreset.STREAMING;
        assertThat(preset.name()).isEqualTo("Streaming");
        assertThat(preset.config().format()).isEqualTo(AudioExportFormat.FLAC);
        assertThat(preset.config().sampleRate()).isEqualTo(44_100);
        assertThat(preset.config().bitDepth()).isEqualTo(16);
        assertThat(preset.config().ditherType()).isEqualTo(DitherType.TPDF);
    }

    @Test
    void hiResPresetShouldBeFlac96000_24BitNoDither() {
        ExportPreset preset = ExportPreset.HI_RES;
        assertThat(preset.name()).isEqualTo("Hi-Res");
        assertThat(preset.config().format()).isEqualTo(AudioExportFormat.FLAC);
        assertThat(preset.config().sampleRate()).isEqualTo(96_000);
        assertThat(preset.config().bitDepth()).isEqualTo(24);
        assertThat(preset.config().ditherType()).isEqualTo(DitherType.NONE);
    }

    @Test
    void vinylPresetShouldBeWav96000_24BitNoDither() {
        ExportPreset preset = ExportPreset.VINYL;
        assertThat(preset.name()).isEqualTo("Vinyl Pre-Master");
        assertThat(preset.config().format()).isEqualTo(AudioExportFormat.WAV);
        assertThat(preset.config().sampleRate()).isEqualTo(96_000);
        assertThat(preset.config().bitDepth()).isEqualTo(24);
        assertThat(preset.config().ditherType()).isEqualTo(DitherType.NONE);
    }
}
