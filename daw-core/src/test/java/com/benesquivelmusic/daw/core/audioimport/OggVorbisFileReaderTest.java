package com.benesquivelmusic.daw.core.audioimport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OggVorbisFileReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRejectNullPath() {
        assertThatThrownBy(() -> OggVorbisFileReader.read(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNonExistentFile() {
        Path nonExistent = tempDir.resolve("missing.ogg");

        assertThatThrownBy(() -> OggVorbisFileReader.read(nonExistent))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void shouldRejectInvalidFile() throws IOException {
        Path textFile = tempDir.resolve("not_ogg.ogg");
        Files.writeString(textFile, "This is not an OGG file");

        // Should throw IOException (unsupported format or invalid data)
        assertThatThrownBy(() -> OggVorbisFileReader.read(textFile))
                .isInstanceOf(IOException.class);
    }

    @Test
    void shouldIncludeFormatNameInError() throws IOException {
        Path fakeFile = tempDir.resolve("fake.ogg");
        Files.writeString(fakeFile, "not ogg data");

        assertThatThrownBy(() -> OggVorbisFileReader.read(fakeFile))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("OGG Vorbis");
    }
}
