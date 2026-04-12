package com.benesquivelmusic.daw.core.audioimport;

import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;
import com.benesquivelmusic.daw.core.export.OggVorbisExporter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        assumeTrue(isVorbisFileAvailable(),
                "libvorbisfile not available — skipping");
        Path textFile = tempDir.resolve("not_ogg.ogg");
        Files.writeString(textFile, "This is not an OGG file");

        // Should throw IOException (not a valid Vorbis stream)
        assertThatThrownBy(() -> OggVorbisFileReader.read(textFile))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("OGG Vorbis");
    }

    @Test
    void shouldIncludeFormatNameInError() throws IOException {
        assumeTrue(isVorbisFileAvailable(),
                "libvorbisfile not available — skipping");
        Path fakeFile = tempDir.resolve("fake.ogg");
        Files.writeString(fakeFile, "not ogg data");

        assertThatThrownBy(() -> OggVorbisFileReader.read(fakeFile))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("OGG Vorbis");
    }

    @Test
    void shouldRoundTripStereoAudio() throws IOException {
        assumeTrue(isVorbisFileAvailable(),
                "libvorbisfile not available — skipping");
        assumeTrue(isVorbisEncAvailable(),
                "libvorbisenc not available — skipping round-trip test");

        int sampleRate = 44100;
        int channels = 2;
        double duration = 1.0;
        double freq = 440.0;
        int numSamples = (int) (sampleRate * duration);
        float[][] original = new float[channels][numSamples];
        for (int i = 0; i < numSamples; i++) {
            float value = (float) Math.sin(2.0 * Math.PI * freq * i / sampleRate);
            original[0][i] = value;
            original[1][i] = value;
        }

        // Encode via OggVorbisExporter
        Path oggFile = tempDir.resolve("roundtrip.ogg");
        OggVorbisExporter.write(original, sampleRate, 16, DitherType.NONE,
                AudioMetadata.EMPTY, 0.8, oggFile);

        // Decode via OggVorbisFileReader (FFM-based)
        AudioReadResult result = OggVorbisFileReader.read(oggFile);

        assertThat(result.channels()).isEqualTo(channels);
        assertThat(result.sampleRate()).isEqualTo(sampleRate);
        // Vorbis is lossy and has encoder/decoder warm-up, so decoded length
        // may differ slightly from original — allow up to ~5% tolerance
        assertThat(result.numFrames())
                .isGreaterThan((int) (numSamples * 0.95))
                .isLessThan((int) (numSamples * 1.05));
    }

    @Test
    void shouldRoundTripMonoAudio() throws IOException {
        assumeTrue(isVorbisFileAvailable(),
                "libvorbisfile not available — skipping");
        assumeTrue(isVorbisEncAvailable(),
                "libvorbisenc not available — skipping round-trip test");

        int sampleRate = 48000;
        int channels = 1;
        int numSamples = (int) (sampleRate * 0.5);
        float[][] original = new float[channels][numSamples];
        for (int i = 0; i < numSamples; i++) {
            original[0][i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate);
        }

        Path oggFile = tempDir.resolve("mono_roundtrip.ogg");
        OggVorbisExporter.write(original, sampleRate, 16, DitherType.NONE,
                AudioMetadata.EMPTY, 0.5, oggFile);

        AudioReadResult result = OggVorbisFileReader.read(oggFile);

        assertThat(result.channels()).isEqualTo(channels);
        assertThat(result.sampleRate()).isEqualTo(sampleRate);
        assertThat(result.numFrames()).isGreaterThan(0);
        // Lossy bitDepth is reported as 0
        assertThat(result.bitDepth()).isEqualTo(0);
    }

    /**
     * Checks if libvorbisfile is available on this system.
     * Uses the same strategy as NativeCodecAvailability in the export package.
     */
    private static boolean isVorbisFileAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] names;
        if (os.contains("win")) {
            names = new String[]{"vorbisfile.dll", "libvorbisfile.dll"};
        } else if (os.contains("mac")) {
            names = new String[]{"libvorbisfile.3.dylib", "libvorbisfile.dylib"};
        } else {
            names = new String[]{"libvorbisfile.so.3", "libvorbisfile.so"};
        }
        return isAnyLibraryAvailable(names);
    }

    /**
     * Checks if libvorbisenc is available (needed for encoding in round-trip tests).
     */
    private static boolean isVorbisEncAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] names;
        if (os.contains("win")) {
            names = new String[]{"vorbisenc.dll", "libvorbisenc.dll"};
        } else if (os.contains("mac")) {
            names = new String[]{"libvorbisenc.2.dylib", "libvorbisenc.dylib"};
        } else {
            names = new String[]{"libvorbisenc.so.2", "libvorbisenc.so"};
        }
        return isAnyLibraryAvailable(names);
    }

    private static boolean isAnyLibraryAvailable(String[] names) {
        // 1. Try OS-level loader
        for (String name : names) {
            try (Arena arena = Arena.ofConfined()) {
                SymbolLookup.libraryLookup(name, arena);
                return true;
            } catch (IllegalArgumentException _) {
                // try next
            }
        }

        // 2. Search java.library.path
        String libraryPath = System.getProperty("java.library.path", "");
        if (!libraryPath.isEmpty()) {
            for (String dir : libraryPath.split(File.pathSeparator)) {
                if (dir.isBlank()) continue;
                for (String name : names) {
                    Path candidate = Path.of(dir, name);
                    if (Files.isRegularFile(candidate)) {
                        try (Arena arena = Arena.ofConfined()) {
                            SymbolLookup.libraryLookup(candidate, arena);
                            return true;
                        } catch (IllegalArgumentException _) {
                            // not loadable
                        }
                    }
                }
            }
        }
        return false;
    }
}
