package com.benesquivelmusic.daw.core.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
                "libportaudio", "FluidSynth", "libmp3lame", "libfdk-aac",
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

    @Test
    void isAvailableShouldRejectNullBaseName() {
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> NativeLibraryDetector.isAvailable(null));
    }

    @Test
    void isAvailableShouldReturnFalseForNonExistentLibrary() {
        assertThat(NativeLibraryDetector.isAvailable("nonexistent_library_xyz_zz"))
                .isFalse();
    }

    /**
     * Story 224 — on Windows builds the bundled {@code asioshim.dll}
     * must be resolvable so the FFM upcall in {@code AsioFormatChangeShim}
     * can install itself. On Linux / macOS the asioshim entry is
     * intentionally absent from {@link NativeLibraryDetector#detectAll()}
     * (Steinberg ASIO SDK is Windows-only — see story 224 non-goals),
     * so this assertion is gated on Windows only and additionally
     * requires the library to be present on the FFM library path
     * (skipped on a fresh Windows checkout that has not yet built the
     * shim).
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void asioshimShouldBeResolvableOnWindowsWhenBundled() {
        assumeTrue(NativeLibraryDetector.isAvailable("asioshim"),
                "asioshim.dll not on java.library.path — skip "
                        + "(build the native libs with -DASIO_SDK_DIR=...)");
        List<NativeLibraryStatus> results = NativeLibraryDetector.detectAll();
        NativeLibraryStatus asioshim = results.stream()
                .filter(s -> s.libraryName().equals("asioshim"))
                .findFirst()
                .orElseThrow();
        assertThat(asioshim.available()).isTrue();
        assertThat(asioshim.detectedPath()).isNotEmpty();
    }

    @Test
    void asioshimEntryShouldBeWindowsOnly() {
        List<String> names = NativeLibraryDetector.detectAll().stream()
                .map(NativeLibraryStatus::libraryName)
                .toList();
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (isWindows) {
            assertThat(names).contains("asioshim");
        } else {
            assertThat(names).doesNotContain("asioshim");
        }
    }
}
