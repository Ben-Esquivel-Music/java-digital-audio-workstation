package com.benesquivelmusic.daw.sdk.export;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AudioExportFormatTest {

    @Test
    void wavShouldBeLossless() {
        assertThat(AudioExportFormat.WAV.isLossless()).isTrue();
        assertThat(AudioExportFormat.WAV.fileExtension()).isEqualTo("wav");
    }

    @Test
    void flacShouldBeLossless() {
        assertThat(AudioExportFormat.FLAC.isLossless()).isTrue();
        assertThat(AudioExportFormat.FLAC.fileExtension()).isEqualTo("flac");
    }

    @Test
    void oggShouldBeLossy() {
        assertThat(AudioExportFormat.OGG.isLossless()).isFalse();
        assertThat(AudioExportFormat.OGG.fileExtension()).isEqualTo("ogg");
    }

    @Test
    void mp3ShouldBeLossy() {
        assertThat(AudioExportFormat.MP3.isLossless()).isFalse();
        assertThat(AudioExportFormat.MP3.fileExtension()).isEqualTo("mp3");
    }

    @Test
    void aacShouldBeLossy() {
        assertThat(AudioExportFormat.AAC.isLossless()).isFalse();
        assertThat(AudioExportFormat.AAC.fileExtension()).isEqualTo("aac");
    }
}
