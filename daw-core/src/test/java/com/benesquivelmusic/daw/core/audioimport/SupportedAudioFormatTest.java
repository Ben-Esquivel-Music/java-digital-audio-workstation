package com.benesquivelmusic.daw.core.audioimport;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SupportedAudioFormatTest {

    @Test
    void shouldDetectWavExtension() {
        Optional<SupportedAudioFormat> result = SupportedAudioFormat.fromPath(Path.of("song.wav"));

        assertThat(result).contains(SupportedAudioFormat.WAV);
    }

    @Test
    void shouldDetectFlacExtension() {
        Optional<SupportedAudioFormat> result = SupportedAudioFormat.fromPath(Path.of("song.flac"));

        assertThat(result).contains(SupportedAudioFormat.FLAC);
    }

    @Test
    void shouldDetectMp3Extension() {
        Optional<SupportedAudioFormat> result = SupportedAudioFormat.fromPath(Path.of("song.mp3"));

        assertThat(result).contains(SupportedAudioFormat.MP3);
    }

    @Test
    void shouldDetectAiffExtension() {
        Optional<SupportedAudioFormat> result = SupportedAudioFormat.fromPath(Path.of("song.aiff"));

        assertThat(result).contains(SupportedAudioFormat.AIFF);
    }

    @Test
    void shouldDetectAifAsAiffAlias() {
        Optional<SupportedAudioFormat> result = SupportedAudioFormat.fromPath(Path.of("song.aif"));

        assertThat(result).contains(SupportedAudioFormat.AIFF);
    }

    @Test
    void shouldDetectOggExtension() {
        Optional<SupportedAudioFormat> result = SupportedAudioFormat.fromPath(Path.of("song.ogg"));

        assertThat(result).contains(SupportedAudioFormat.OGG);
    }

    @Test
    void shouldHandleCaseInsensitiveExtension() {
        assertThat(SupportedAudioFormat.fromPath(Path.of("song.WAV"))).contains(SupportedAudioFormat.WAV);
        assertThat(SupportedAudioFormat.fromPath(Path.of("song.Wav"))).contains(SupportedAudioFormat.WAV);
        assertThat(SupportedAudioFormat.fromPath(Path.of("song.FLAC"))).contains(SupportedAudioFormat.FLAC);
    }

    @Test
    void shouldReturnEmptyForUnsupportedExtension() {
        assertThat(SupportedAudioFormat.fromPath(Path.of("document.pdf"))).isEmpty();
        assertThat(SupportedAudioFormat.fromPath(Path.of("video.mp4"))).isEmpty();
        assertThat(SupportedAudioFormat.fromPath(Path.of("readme.txt"))).isEmpty();
    }

    @Test
    void shouldReturnEmptyForNoExtension() {
        assertThat(SupportedAudioFormat.fromPath(Path.of("noext"))).isEmpty();
    }

    @Test
    void shouldReturnEmptyForDotOnly() {
        assertThat(SupportedAudioFormat.fromPath(Path.of("file."))).isEmpty();
    }

    @Test
    void shouldReturnEmptyForNullPath() {
        assertThat(SupportedAudioFormat.fromPath(null)).isEmpty();
    }

    @Test
    void shouldDetectSupportedFile() {
        assertThat(SupportedAudioFormat.isSupported(Path.of("song.wav"))).isTrue();
        assertThat(SupportedAudioFormat.isSupported(Path.of("song.flac"))).isTrue();
    }

    @Test
    void shouldDetectUnsupportedFile() {
        assertThat(SupportedAudioFormat.isSupported(Path.of("doc.pdf"))).isFalse();
        assertThat(SupportedAudioFormat.isSupported(Path.of("noext"))).isFalse();
    }

    @Test
    void shouldReturnCorrectExtensions() {
        assertThat(SupportedAudioFormat.WAV.getExtension()).isEqualTo("wav");
        assertThat(SupportedAudioFormat.FLAC.getExtension()).isEqualTo("flac");
        assertThat(SupportedAudioFormat.MP3.getExtension()).isEqualTo("mp3");
        assertThat(SupportedAudioFormat.AIFF.getExtension()).isEqualTo("aiff");
        assertThat(SupportedAudioFormat.OGG.getExtension()).isEqualTo("ogg");
    }

    @Test
    void shouldHandlePathWithDirectories() {
        Optional<SupportedAudioFormat> result = SupportedAudioFormat.fromPath(
                Path.of("/home/user/music/song.wav"));

        assertThat(result).contains(SupportedAudioFormat.WAV);
    }
}
