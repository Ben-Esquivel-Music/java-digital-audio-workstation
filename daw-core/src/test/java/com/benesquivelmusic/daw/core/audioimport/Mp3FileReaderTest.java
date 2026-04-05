package com.benesquivelmusic.daw.core.audioimport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Mp3FileReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRejectNullPath() {
        assertThatThrownBy(() -> Mp3FileReader.read(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNonExistentFile() {
        Path nonExistent = tempDir.resolve("missing.mp3");

        assertThatThrownBy(() -> Mp3FileReader.read(nonExistent))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void shouldRejectInvalidFile() throws IOException {
        Path textFile = tempDir.resolve("not_mp3.mp3");
        Files.writeString(textFile, "This is not an MP3 file");

        // Should throw IOException (unsupported format or invalid data)
        assertThatThrownBy(() -> Mp3FileReader.read(textFile))
                .isInstanceOf(IOException.class);
    }

    @Test
    void shouldIncludeFormatNameInError() throws IOException {
        Path fakeFile = tempDir.resolve("fake.mp3");
        Files.writeString(fakeFile, "not mp3 data");

        assertThatThrownBy(() -> Mp3FileReader.read(fakeFile))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("MP3");
    }
}
