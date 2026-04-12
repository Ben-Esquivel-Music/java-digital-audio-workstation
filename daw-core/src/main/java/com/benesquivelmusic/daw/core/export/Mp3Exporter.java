package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Exports audio data to MP3 (MPEG-1 Audio Layer III) format using
 * FFM API (JEP 454) bindings to the LAME encoding library.
 *
 * <p>LAME is the de-facto standard open-source MP3 encoder. This exporter
 * uses {@link Linker#nativeLinker()} and {@link SymbolLookup#libraryLookup}
 * to dynamically bind to {@code libmp3lame} at runtime, avoiding any
 * compile-time native dependency.</p>
 *
 * <p>The export pipeline:</p>
 * <ol>
 *   <li>Apply dithering to convert float audio to 16-bit signed PCM</li>
 *   <li>Configure LAME with the requested quality/bitrate and metadata</li>
 *   <li>Encode interleaved 16-bit PCM via {@code lame_encode_buffer_interleaved}</li>
 *   <li>Flush and write the MP3 bitstream to disk</li>
 * </ol>
 *
 * @throws UnsupportedOperationException if {@code libmp3lame} is not installed
 */
public final class Mp3Exporter {

    /** VBR mode constant: VBR default (mtrh). */
    private static final int VBR_DEFAULT = 4;

    /** Number of samples per MP3 encode call. */
    private static final int ENCODE_CHUNK_FRAMES = 8192;

    private Mp3Exporter() {
        // utility class
    }

    /**
     * Writes audio data to an MP3 file using LAME via FFM.
     *
     * @param audioData  audio samples as {@code [channel][sample]} in [-1.0, 1.0]
     * @param sampleRate the sample rate in Hz
     * @param bitDepth   the source bit depth (dithering applied to reduce to 16-bit)
     * @param ditherType the dithering algorithm for bit-depth reduction
     * @param metadata   metadata to embed as ID3 tags
     * @param quality    encoder quality in [0.0, 1.0] where 1.0 is highest quality
     * @param outputPath the output file path
     * @throws IOException if an I/O error occurs
     * @throws UnsupportedOperationException if libmp3lame is not available
     */
    public static void write(float[][] audioData, int sampleRate, int bitDepth,
                             DitherType ditherType, AudioMetadata metadata,
                             double quality, Path outputPath) throws IOException {

        Objects.requireNonNull(audioData, "audioData must not be null");
        Objects.requireNonNull(ditherType, "ditherType must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(outputPath, "outputPath must not be null");
        if (audioData.length == 0) {
            throw new IllegalArgumentException("audioData must have at least one channel");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }

        int channels = audioData.length;
        int numSamples = audioData[0].length;

        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup lame = loadLameLibrary(arena);
            Linker linker = Linker.nativeLinker();

            // Resolve LAME functions
            MethodHandle lameInit = linker.downcallHandle(
                    lame.find("lame_init").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS));
            MethodHandle lameSetNumChannels = linker.downcallHandle(
                    lame.find("lame_set_num_channels").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            MethodHandle lameSetInSamplerate = linker.downcallHandle(
                    lame.find("lame_set_in_samplerate").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            MethodHandle lameSetVBR = linker.downcallHandle(
                    lame.find("lame_set_VBR").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            MethodHandle lameSetVBRQuality = linker.downcallHandle(
                    lame.find("lame_set_VBR_quality").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT));
            MethodHandle lameSetQuality = linker.downcallHandle(
                    lame.find("lame_set_quality").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            MethodHandle id3tagInit = linker.downcallHandle(
                    lame.find("id3tag_init").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            MethodHandle id3tagAddV2 = linker.downcallHandle(
                    lame.find("id3tag_add_v2").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            MethodHandle id3tagSetTitle = linker.downcallHandle(
                    lame.find("id3tag_set_title").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle id3tagSetArtist = linker.downcallHandle(
                    lame.find("id3tag_set_artist").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle id3tagSetAlbum = linker.downcallHandle(
                    lame.find("id3tag_set_album").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle lameInitParams = linker.downcallHandle(
                    lame.find("lame_init_params").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            MethodHandle lameEncodeBufferInterleaved = linker.downcallHandle(
                    lame.find("lame_encode_buffer_interleaved").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            MethodHandle lameEncodeFlush = linker.downcallHandle(
                    lame.find("lame_encode_flush").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            MethodHandle lameClose = linker.downcallHandle(
                    lame.find("lame_close").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

            // Initialize LAME
            MemorySegment gfp = (MemorySegment) lameInit.invoke();
            if (gfp.equals(MemorySegment.NULL)) {
                throw new IOException("LAME initialization failed");
            }

            try {
                // Configure encoder
                lameSetNumChannels.invoke(gfp, channels);
                lameSetInSamplerate.invoke(gfp, sampleRate);

                // Clamp quality to the supported [0.0, 1.0] range
                double clampedQuality = Math.max(0.0, Math.min(1.0, quality));

                // Use VBR with quality mapped from [0.0, 1.0] to LAME's [9, 0]
                // LAME VBR quality: 0 = highest, 9 = lowest
                lameSetVBR.invoke(gfp, VBR_DEFAULT);
                float vbrQuality = (float) ((1.0 - clampedQuality) * 9.0);
                lameSetVBRQuality.invoke(gfp, vbrQuality);

                // Internal algorithm quality: 0 = best, 9 = fastest
                int algoQuality = (int) ((1.0 - clampedQuality) * 7.0);
                lameSetQuality.invoke(gfp, algoQuality);

                // Configure ID3 tags — let LAME automatically write ID3v2 at
                // the start of the bitstream (the default behavior)
                id3tagInit.invoke(gfp);
                id3tagAddV2.invoke(gfp);
                setId3Tag(id3tagSetTitle, gfp, metadata.title(), arena);
                setId3Tag(id3tagSetArtist, gfp, metadata.artist(), arena);
                setId3Tag(id3tagSetAlbum, gfp, metadata.album(), arena);

                int initResult = (int) lameInitParams.invoke(gfp);
                if (initResult < 0) {
                    throw new IOException("LAME parameter initialization failed: " + initResult);
                }

                // Prepare ditherers for 16-bit conversion
                TpdfDitherer tpdf = (ditherType == DitherType.TPDF) ? new TpdfDitherer() : null;
                NoiseShapedDitherer[] noiseShaped = null;
                if (ditherType == DitherType.NOISE_SHAPED) {
                    noiseShaped = new NoiseShapedDitherer[channels];
                    for (int ch = 0; ch < channels; ch++) {
                        noiseShaped[ch] = new NoiseShapedDitherer();
                    }
                }

                // MP3 output buffer — LAME recommends 1.25 * num_samples + 7200
                int mp3BufSize = (int) (1.25 * ENCODE_CHUNK_FRAMES * channels) + 7200;
                MemorySegment mp3Buf = arena.allocate(mp3BufSize);
                byte[] mp3Bytes = new byte[mp3BufSize];

                try (OutputStream out = Files.newOutputStream(outputPath)) {
                    // Encode audio in chunks
                    int samplesWritten = 0;
                    MemorySegment pcmBuf = arena.allocate(
                            (long) ENCODE_CHUNK_FRAMES * channels * Short.BYTES);

                    while (samplesWritten < numSamples) {
                        int framesToEncode = Math.min(ENCODE_CHUNK_FRAMES,
                                numSamples - samplesWritten);

                        // Convert float to interleaved 16-bit PCM
                        for (int i = 0; i < framesToEncode; i++) {
                            for (int ch = 0; ch < channels; ch++) {
                                double sample = audioData[ch][samplesWritten + i];
                                sample = Math.max(-1.0, Math.min(1.0, sample));
                                short pcmSample = (short) quantize16(sample, tpdf,
                                        noiseShaped != null ? noiseShaped[ch] : null);
                                pcmBuf.setAtIndex(ValueLayout.JAVA_SHORT,
                                        (long) i * channels + ch, pcmSample);
                            }
                        }

                        int encoded = (int) lameEncodeBufferInterleaved.invoke(
                                gfp, pcmBuf, framesToEncode, mp3Buf, mp3BufSize);
                        if (encoded < 0) {
                            throw new IOException("LAME encoding error: " + encoded);
                        }
                        if (encoded > 0) {
                            MemorySegment.copy(mp3Buf, ValueLayout.JAVA_BYTE, 0,
                                    mp3Bytes, 0, encoded);
                            out.write(mp3Bytes, 0, encoded);
                        }

                        samplesWritten += framesToEncode;
                    }

                    // Flush remaining data
                    int flushed = (int) lameEncodeFlush.invoke(gfp, mp3Buf, mp3BufSize);
                    if (flushed > 0) {
                        MemorySegment.copy(mp3Buf, ValueLayout.JAVA_BYTE, 0,
                                mp3Bytes, 0, flushed);
                        out.write(mp3Bytes, 0, flushed);
                    }
                }
            } finally {
                lameClose.invoke(gfp);
            }
        } catch (IOException | UnsupportedOperationException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("MP3 encoding failed: " + t.getMessage(), t);
        }
    }

    private static SymbolLookup loadLameLibrary(Arena arena) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] names;   // bare names for system-installed libraries (OS loader)
        String[] files;   // filenames for java.library.path search
        if (os.contains("win")) {
            names = new String[]{"libmp3lame", "mp3lame", "lame"};
            files = new String[]{"libmp3lame.dll", "mp3lame.dll", "lame.dll"};
        } else if (os.contains("mac")) {
            names = new String[]{"libmp3lame.dylib", "libmp3lame.0.dylib"};
            files = names;
        } else {
            names = new String[]{"libmp3lame.so.0", "libmp3lame.so"};
            files = names;
        }

        // 1. Try OS-level library loader (finds system-installed copies)
        for (String name : names) {
            try {
                return SymbolLookup.libraryLookup(name, arena);
            } catch (IllegalArgumentException _) {
                // try next candidate
            }
        }

        // 2. Search java.library.path directories (finds project-built copies)
        Optional<SymbolLookup> lookup = searchLibraryPath(arena, files);
        if (lookup.isPresent()) {
            return lookup.get();
        }

        throw new UnsupportedOperationException(
                "MP3 export requires libmp3lame. "
                        + "Install LAME (e.g., 'apt install libmp3lame0' on Debian/Ubuntu, "
                        + "'brew install lame' on macOS, "
                        + "or build with CMake on Windows).");
    }

    /**
     * Searches {@code java.library.path} directories for any of the given
     * library filenames, loading via {@link SymbolLookup#libraryLookup(Path, Arena)}.
     */
    private static Optional<SymbolLookup> searchLibraryPath(Arena arena, String... fileNames) {
        String libraryPath = System.getProperty("java.library.path", "");
        if (libraryPath.isEmpty()) {
            return Optional.empty();
        }
        for (String dir : libraryPath.split(java.io.File.pathSeparator)) {
            for (String fileName : fileNames) {
                Path candidate = Path.of(dir, fileName);
                if (Files.isRegularFile(candidate)) {
                    try {
                        return Optional.of(SymbolLookup.libraryLookup(candidate, arena));
                    } catch (IllegalArgumentException _) {
                        // file exists but not loadable — try next
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static void setId3Tag(MethodHandle setter, MemorySegment gfp,
                                  String value, Arena arena) throws Throwable {
        if (value != null && !value.isEmpty()) {
            MemorySegment nativeStr = arena.allocateFrom(value);
            setter.invoke(gfp, nativeStr);
        }
    }

    private static long quantize16(double sample, TpdfDitherer tpdf,
                                   NoiseShapedDitherer noiseShaped) {
        if (tpdf != null) {
            return (long) tpdf.dither(sample, 16);
        } else if (noiseShaped != null) {
            return (long) noiseShaped.dither(sample, 16);
        } else {
            return Math.round(sample * 32767.0);
        }
    }
}
