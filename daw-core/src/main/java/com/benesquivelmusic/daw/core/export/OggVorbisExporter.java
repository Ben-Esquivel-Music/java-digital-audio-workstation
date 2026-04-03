package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Exports audio data to OGG Vorbis format using FFM API (JEP 454)
 * bindings to the native libvorbis, libvorbisenc, and libogg libraries.
 *
 * <p>Vorbis is an open-source lossy audio codec that typically achieves
 * better quality than MP3 at equivalent bitrates, making it popular
 * in game engines and open-source projects.</p>
 *
 * <p>The export pipeline:</p>
 * <ol>
 *   <li>Initialize Vorbis encoder with VBR quality from the config</li>
 *   <li>Write Vorbis headers (identification, comment, codebook) into Ogg pages</li>
 *   <li>Feed float audio data directly to Vorbis analysis (no PCM conversion needed)</li>
 *   <li>Collect encoded Ogg pages and write to disk</li>
 * </ol>
 *
 * <p>Unlike MP3 and AAC, Vorbis natively accepts float PCM input, so
 * dithering is only applied conceptually (the encoder's own quantization
 * handles precision reduction).</p>
 *
 * @throws UnsupportedOperationException if native libraries are not installed
 */
public final class OggVorbisExporter {

    // Struct sizes for x86_64 Linux (determined at build time)
    private static final long SIZEOF_VORBIS_INFO = 56;
    private static final long SIZEOF_VORBIS_COMMENT = 32;
    private static final long SIZEOF_VORBIS_DSP_STATE = 144;
    private static final long SIZEOF_VORBIS_BLOCK = 192;
    private static final long SIZEOF_OGG_STREAM_STATE = 408;
    private static final long SIZEOF_OGG_PAGE = 32;
    private static final long SIZEOF_OGG_PACKET = 48;

    // ogg_page field offsets (x86_64)
    private static final long OGG_PAGE_HEADER = 0;
    private static final long OGG_PAGE_HEADER_LEN = 8;
    private static final long OGG_PAGE_BODY = 16;
    private static final long OGG_PAGE_BODY_LEN = 24;

    /** Number of samples to feed to the Vorbis encoder per chunk. */
    private static final int ENCODE_CHUNK_FRAMES = 4096;

    private OggVorbisExporter() {
        // utility class
    }

    /**
     * Writes audio data to an OGG Vorbis file.
     *
     * @param audioData  audio samples as {@code [channel][sample]} in [-1.0, 1.0]
     * @param sampleRate the sample rate in Hz
     * @param bitDepth   the source bit depth (informational; Vorbis handles float input)
     * @param ditherType the dithering algorithm (informational for Vorbis; the encoder
     *                   applies its own psychoacoustic quantization)
     * @param metadata   metadata to embed as Vorbis comments
     * @param quality    encoder quality in [0.0, 1.0] mapped to Vorbis quality [-0.1, 1.0]
     * @param outputPath the output file path
     * @throws IOException if an I/O error occurs
     * @throws UnsupportedOperationException if native Vorbis/Ogg libraries are not available
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
            LibVorbis lib = new LibVorbis(arena);

            // Allocate native structs
            MemorySegment vi = arena.allocate(SIZEOF_VORBIS_INFO);
            MemorySegment vc = arena.allocate(SIZEOF_VORBIS_COMMENT);
            MemorySegment vd = arena.allocate(SIZEOF_VORBIS_DSP_STATE);
            MemorySegment vb = arena.allocate(SIZEOF_VORBIS_BLOCK);
            MemorySegment os = arena.allocate(SIZEOF_OGG_STREAM_STATE);
            MemorySegment og = arena.allocate(SIZEOF_OGG_PAGE);
            MemorySegment op = arena.allocate(SIZEOF_OGG_PACKET);

            // Initialize Vorbis info and encoder
            lib.vorbisInfoInit.invoke(vi);

            // Map quality [0.0, 1.0] to Vorbis quality [-0.1, 1.0]
            float vorbisQuality = (float) (quality * 1.1 - 0.1);
            vorbisQuality = Math.max(-0.1f, Math.min(1.0f, vorbisQuality));

            int ret = (int) lib.vorbisEncodeInitVbr.invoke(vi, channels, sampleRate,
                    vorbisQuality);
            if (ret != 0) {
                lib.vorbisInfoClear.invoke(vi);
                throw new IOException("Vorbis encoder initialization failed: " + ret);
            }

            try {
                // Set up Vorbis comments (metadata)
                lib.vorbisCommentInit.invoke(vc);
                addVorbisComment(lib, vc, "TITLE", metadata.title(), arena);
                addVorbisComment(lib, vc, "ARTIST", metadata.artist(), arena);
                addVorbisComment(lib, vc, "ALBUM", metadata.album(), arena);

                // Initialize DSP state and block
                lib.vorbisAnalysisInit.invoke(vd, vi);
                lib.vorbisBlockInit.invoke(vd, vb);

                // Initialize Ogg stream with a random serial number
                int serialNo = (int) (System.nanoTime() & 0x7FFFFFFF);
                lib.oggStreamInit.invoke(os, serialNo);

                try (OutputStream out = Files.newOutputStream(outputPath)) {
                    // Write Vorbis headers
                    MemorySegment header = arena.allocate(SIZEOF_OGG_PACKET);
                    MemorySegment headerComm = arena.allocate(SIZEOF_OGG_PACKET);
                    MemorySegment headerCode = arena.allocate(SIZEOF_OGG_PACKET);

                    lib.vorbisAnalysisHeaderout.invoke(vd, vc, header, headerComm, headerCode);

                    lib.oggStreamPacketin.invoke(os, header);
                    lib.oggStreamPacketin.invoke(os, headerComm);
                    lib.oggStreamPacketin.invoke(os, headerCode);

                    // Flush header pages
                    while ((int) lib.oggStreamFlush.invoke(os, og) != 0) {
                        writeOggPage(out, og);
                    }

                    // Encode audio data
                    int samplesWritten = 0;
                    while (samplesWritten < numSamples) {
                        int framesToEncode = Math.min(ENCODE_CHUNK_FRAMES,
                                numSamples - samplesWritten);

                        // Get the Vorbis analysis buffer
                        MemorySegment bufferPtr = (MemorySegment) lib.vorbisAnalysisBuffer.invoke(
                                vd, framesToEncode);
                        // bufferPtr is a float** — array of pointers to float arrays
                        bufferPtr = bufferPtr.reinterpret(
                                (long) channels * ValueLayout.ADDRESS.byteSize());

                        for (int ch = 0; ch < channels; ch++) {
                            MemorySegment channelBuf = bufferPtr.getAtIndex(
                                    ValueLayout.ADDRESS, ch);
                            channelBuf = channelBuf.reinterpret(
                                    (long) framesToEncode * Float.BYTES);

                            for (int i = 0; i < framesToEncode; i++) {
                                float sample = audioData[ch][samplesWritten + i];
                                sample = Math.max(-1.0f, Math.min(1.0f, sample));
                                channelBuf.setAtIndex(ValueLayout.JAVA_FLOAT, i, sample);
                            }
                        }

                        lib.vorbisAnalysisWrote.invoke(vd, framesToEncode);
                        drainVorbisBlocks(lib, vd, vb, os, og, op, out);

                        samplesWritten += framesToEncode;
                    }

                    // Signal end of stream
                    lib.vorbisAnalysisWrote.invoke(vd, 0);
                    drainVorbisBlocks(lib, vd, vb, os, og, op, out);
                }
            } finally {
                lib.oggStreamClear.invoke(os);
                lib.vorbisBlockClear.invoke(vb);
                lib.vorbisDspClear.invoke(vd);
                lib.vorbisCommentClear.invoke(vc);
                lib.vorbisInfoClear.invoke(vi);
            }
        } catch (IOException | UnsupportedOperationException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("OGG Vorbis encoding failed: " + t.getMessage(), t);
        }
    }

    /**
     * Drains all available Vorbis blocks, encoding them and writing
     * the resulting Ogg pages to the output stream.
     */
    private static void drainVorbisBlocks(LibVorbis lib, MemorySegment vd,
                                          MemorySegment vb, MemorySegment os,
                                          MemorySegment og, MemorySegment op,
                                          OutputStream out)
            throws Throwable {
        while ((int) lib.vorbisAnalysisBlockout.invoke(vd, vb) == 1) {
            lib.vorbisAnalysis.invoke(vb, MemorySegment.NULL);
            lib.vorbisBitrateAddblock.invoke(vb);

            while ((int) lib.vorbisBitrateFlushpacket.invoke(vd, op) != 0) {
                lib.oggStreamPacketin.invoke(os, op);

                while ((int) lib.oggStreamPageout.invoke(os, og) != 0) {
                    writeOggPage(out, og);
                }
            }
        }
    }

    /**
     * Writes an Ogg page (header + body) to the output stream.
     */
    private static void writeOggPage(OutputStream out, MemorySegment og) throws IOException {
        MemorySegment headerPtr = og.get(ValueLayout.ADDRESS, OGG_PAGE_HEADER);
        long headerLen = og.get(ValueLayout.JAVA_LONG, OGG_PAGE_HEADER_LEN);
        MemorySegment bodyPtr = og.get(ValueLayout.ADDRESS, OGG_PAGE_BODY);
        long bodyLen = og.get(ValueLayout.JAVA_LONG, OGG_PAGE_BODY_LEN);

        if (headerLen > 0) {
            byte[] headerBytes = headerPtr.reinterpret(headerLen)
                    .toArray(ValueLayout.JAVA_BYTE);
            out.write(headerBytes);
        }
        if (bodyLen > 0) {
            byte[] bodyBytes = bodyPtr.reinterpret(bodyLen)
                    .toArray(ValueLayout.JAVA_BYTE);
            out.write(bodyBytes);
        }
    }

    private static void addVorbisComment(LibVorbis lib, MemorySegment vc,
                                         String tag, String value,
                                         Arena arena) throws Throwable {
        if (value != null && !value.isEmpty()) {
            MemorySegment nativeTag = arena.allocateFrom(tag);
            MemorySegment nativeValue = arena.allocateFrom(value);
            lib.vorbisCommentAddTag.invoke(vc, nativeTag, nativeValue);
        }
    }

    /**
     * Holds FFM method handles for all required libvorbis, libvorbisenc,
     * and libogg functions.
     */
    private static final class LibVorbis {
        final MethodHandle vorbisInfoInit;
        final MethodHandle vorbisInfoClear;
        final MethodHandle vorbisCommentInit;
        final MethodHandle vorbisCommentClear;
        final MethodHandle vorbisCommentAddTag;
        final MethodHandle vorbisEncodeInitVbr;
        final MethodHandle vorbisAnalysisInit;
        final MethodHandle vorbisBlockInit;
        final MethodHandle vorbisBlockClear;
        final MethodHandle vorbisDspClear;
        final MethodHandle vorbisAnalysisHeaderout;
        final MethodHandle vorbisAnalysisBuffer;
        final MethodHandle vorbisAnalysisWrote;
        final MethodHandle vorbisAnalysisBlockout;
        final MethodHandle vorbisAnalysis;
        final MethodHandle vorbisBitrateAddblock;
        final MethodHandle vorbisBitrateFlushpacket;
        final MethodHandle oggStreamInit;
        final MethodHandle oggStreamClear;
        final MethodHandle oggStreamPacketin;
        final MethodHandle oggStreamPageout;
        final MethodHandle oggStreamFlush;

        LibVorbis(Arena arena) {
            SymbolLookup vorbisLib = loadLibrary(arena, "libvorbis.so.0", "libvorbis.so");
            SymbolLookup vorbisEncLib = loadLibrary(arena, "libvorbisenc.so.2", "libvorbisenc.so");
            SymbolLookup oggLib = loadLibrary(arena, "libogg.so.0", "libogg.so");

            Linker linker = Linker.nativeLinker();

            // vorbis_info functions
            vorbisInfoInit = linker.downcallHandle(
                    vorbisLib.find("vorbis_info_init").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            vorbisInfoClear = linker.downcallHandle(
                    vorbisLib.find("vorbis_info_clear").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

            // vorbis_comment functions
            vorbisCommentInit = linker.downcallHandle(
                    vorbisLib.find("vorbis_comment_init").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            vorbisCommentClear = linker.downcallHandle(
                    vorbisLib.find("vorbis_comment_clear").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            vorbisCommentAddTag = linker.downcallHandle(
                    vorbisLib.find("vorbis_comment_add_tag").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // vorbis_encode functions
            vorbisEncodeInitVbr = linker.downcallHandle(
                    vorbisEncLib.find("vorbis_encode_init_vbr").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_FLOAT));

            // vorbis_analysis functions
            vorbisAnalysisInit = linker.downcallHandle(
                    vorbisLib.find("vorbis_analysis_init").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            vorbisBlockInit = linker.downcallHandle(
                    vorbisLib.find("vorbis_block_init").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            vorbisBlockClear = linker.downcallHandle(
                    vorbisLib.find("vorbis_block_clear").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            vorbisDspClear = linker.downcallHandle(
                    vorbisLib.find("vorbis_dsp_clear").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            vorbisAnalysisHeaderout = linker.downcallHandle(
                    vorbisLib.find("vorbis_analysis_headerout").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));
            vorbisAnalysisBuffer = linker.downcallHandle(
                    vorbisLib.find("vorbis_analysis_buffer").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            vorbisAnalysisWrote = linker.downcallHandle(
                    vorbisLib.find("vorbis_analysis_wrote").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            vorbisAnalysisBlockout = linker.downcallHandle(
                    vorbisLib.find("vorbis_analysis_blockout").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            vorbisAnalysis = linker.downcallHandle(
                    vorbisLib.find("vorbis_analysis").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            vorbisBitrateAddblock = linker.downcallHandle(
                    vorbisLib.find("vorbis_bitrate_addblock").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            vorbisBitrateFlushpacket = linker.downcallHandle(
                    vorbisLib.find("vorbis_bitrate_flushpacket").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // ogg_stream functions
            oggStreamInit = linker.downcallHandle(
                    oggLib.find("ogg_stream_init").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            oggStreamClear = linker.downcallHandle(
                    oggLib.find("ogg_stream_clear").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            oggStreamPacketin = linker.downcallHandle(
                    oggLib.find("ogg_stream_packetin").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            oggStreamPageout = linker.downcallHandle(
                    oggLib.find("ogg_stream_pageout").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            oggStreamFlush = linker.downcallHandle(
                    oggLib.find("ogg_stream_flush").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        }

        private static SymbolLookup loadLibrary(Arena arena, String soName,
                                                 String fallbackName) {
            try {
                return SymbolLookup.libraryLookup(soName, arena);
            } catch (IllegalArgumentException e1) {
                try {
                    return SymbolLookup.libraryLookup(fallbackName, arena);
                } catch (IllegalArgumentException e2) {
                    throw new UnsupportedOperationException(
                            "OGG Vorbis export requires " + soName + ". "
                                    + "Install libogg and libvorbis "
                                    + "(e.g., 'apt install libvorbisenc2 libogg0' on Debian/Ubuntu).");
                }
            }
        }
    }
}
