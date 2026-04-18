package com.benesquivelmusic.daw.core.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NativeLibraryDetectorTest {

    @Test
    void detectAllShouldReturnAllKnownLibraries() {
        List<NativeLibraryStatus> results = NativeLibraryDetector.detectAll();

        assertThat(results).isNotEmpty();

        // All expected libraries should be present
        List<String> names = results.stream()
                .map(NativeLibraryStatus::libraryName)
                .toList();
        assertThat(names).contains(
                "libportaudio", "FluidSynth", "libmp3lame",
                "libogg", "libvorbis", "libvorbisenc", "libvorbisfile");
    }

    @Test
    void detectAllShouldReturnImmutableList() {
        List<NativeLibraryStatus> results = NativeLibraryDetector.detectAll();

        assertThat(results).isUnmodifiable();
    }

    @Test
    void eachStatusShouldHaveNonNullFields() {
        List<NativeLibraryStatus> results = NativeLibraryDetector.detectAll();

        for (NativeLibraryStatus status : results) {
            assertThat(status.libraryName()).isNotNull().isNotBlank();
            assertThat(status.requiredFor()).isNotNull().isNotBlank();
            assertThat(status.detectedPath()).isNotNull();
        }
    }

    @Test
    void oggAndVorbisLibrariesShouldBeRequiredForImportAndExport() {
        List<NativeLibraryStatus> results = NativeLibraryDetector.detectAll();

        results.stream()
                .filter(s -> s.libraryName().contains("ogg")
                        || s.libraryName().contains("vorbis"))
                .forEach(s -> assertThat(s.requiredFor())
                        .isEqualTo("OGG Vorbis import and export"));
    }

    @Test
    void availableLibraryShouldHaveNonEmptyPath() {
        List<NativeLibraryStatus> results = NativeLibraryDetector.detectAll();

        results.stream()
                .filter(NativeLibraryStatus::available)
                .forEach(s -> assertThat(s.detectedPath()).isNotEmpty());
    }

    @Test
    void missingLibraryShouldHaveEmptyPath() {
        List<NativeLibraryStatus> results = NativeLibraryDetector.detectAll();

        results.stream()
                .filter(s -> !s.available())
                .forEach(s -> assertThat(s.detectedPath()).isEmpty());
    }

    @Test
    void detectShouldReturnMissingForNonExistentLibrary() {
        String os = System.getProperty("os.name", "").toLowerCase();
        NativeLibraryStatus status = NativeLibraryDetector.detect(
                os, "libnonexistent", "nonexistent_library_xyz", 99,
                "test only");

        assertThat(status.available()).isFalse();
        assertThat(status.detectedPath()).isEmpty();
        assertThat(status.libraryName()).isEqualTo("libnonexistent");
    }
}
