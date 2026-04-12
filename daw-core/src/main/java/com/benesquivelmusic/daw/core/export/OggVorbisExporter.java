package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

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

    // Platform-aware C ABI type for 'long' — 8 bytes on Linux/macOS x86_64,
    // 4 bytes on Windows x86_64 (LLP64 model). Obtained from the native
    // linker's canonical layout map (JEP 454).
    static final ValueLayout C_LONG = resolveCLongLayout();

    private static ValueLayout resolveCLongLayout() {
        MemoryLayout longLayout = Linker.nativeLinker().canonicalLayouts().get("long");
        if (longLayout == null) {
            throw new UnsupportedOperationException(
                    "Native C ABI layout for 'long' is not available from the platform linker");
        }
        if (!(longLayout instanceof ValueLayout valueLayout)) {
            throw new UnsupportedOperationException(
                    "Native C ABI layout for 'long' is not a ValueLayout: "
                            + longLayout.getClass().getName());
        }
        return valueLayout;
    }

    // Struct sizes computed from C struct declarations in ogg/ogg.h and
    // vorbis/codec.h using platform-correct C ABI types (C_INT, C_LONG,
    // C_POINTER) rather than hardcoded byte counts.
    static final long SIZEOF_VORBIS_INFO = computeStructSize(
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,       // version, channels
            C_LONG, C_LONG, C_LONG, C_LONG, C_LONG,           // rate, bitrate_*
            ValueLayout.ADDRESS);                              // codec_setup
    static final long SIZEOF_VORBIS_COMMENT = computeStructSize(
            ValueLayout.ADDRESS, ValueLayout.ADDRESS,          // user_comments, comment_lengths
            ValueLayout.JAVA_INT,                              // comments
            ValueLayout.ADDRESS);                              // vendor
    static final long SIZEOF_VORBIS_DSP_STATE = computeVorbisDspStateSize();
    static final long SIZEOF_VORBIS_BLOCK = computeVorbisBlockSize();
    static final long SIZEOF_OGG_STREAM_STATE = computeOggStreamStateSize();
    static final long SIZEOF_OGG_PAGE = computeStructSize(
            ValueLayout.ADDRESS, C_LONG, ValueLayout.ADDRESS, C_LONG);
    static final long SIZEOF_OGG_PACKET = computeStructSize(
            ValueLayout.ADDRESS, C_LONG, C_LONG, C_LONG,
            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG);

    // ogg_page field offsets — computed from struct layout for portability
    // across platforms where C 'long' and pointer sizes differ.
    static final long[] OGG_PAGE_OFFSETS = computeFieldOffsets(
            ValueLayout.ADDRESS, C_LONG, ValueLayout.ADDRESS, C_LONG);
    private static final long OGG_PAGE_HEADER = OGG_PAGE_OFFSETS[0];
    private static final long OGG_PAGE_HEADER_LEN = OGG_PAGE_OFFSETS[1];
    private static final long OGG_PAGE_BODY = OGG_PAGE_OFFSETS[2];
    private static final long OGG_PAGE_BODY_LEN = OGG_PAGE_OFFSETS[3];

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
        long headerLen = readCLong(og, OGG_PAGE_HEADER_LEN);
        MemorySegment bodyPtr = og.get(ValueLayout.ADDRESS, OGG_PAGE_BODY);
        long bodyLen = readCLong(og, OGG_PAGE_BODY_LEN);

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
     * Reads a C {@code long} field from a memory segment at the given byte offset.
     * C {@code long} is 8 bytes on Linux/macOS x86_64 but 4 bytes on Windows x86_64.
     */
    private static long readCLong(MemorySegment segment, long offset) {
        if (C_LONG.byteSize() == 8) {
            return segment.get(ValueLayout.JAVA_LONG, offset);
        } else {
            return segment.get(ValueLayout.JAVA_INT, offset);
        }
    }

    /**
     * Computes C struct size following standard ABI padding/alignment rules.
     * Each field is placed at the next offset that satisfies its alignment,
     * and the total size is rounded up to the struct's overall alignment
     * (the maximum alignment of any field).
     */
    static long computeStructSize(MemoryLayout... fields) {
        long offset = 0;
        long maxAlign = 1;
        for (MemoryLayout field : fields) {
            long align = field.byteAlignment();
            offset = (offset + align - 1) & ~(align - 1);
            offset += field.byteSize();
            maxAlign = Math.max(maxAlign, align);
        }
        return (offset + maxAlign - 1) & ~(maxAlign - 1);
    }

    /**
     * Computes byte offsets for each field in a C struct following standard
     * ABI padding/alignment rules.
     */
    static long[] computeFieldOffsets(MemoryLayout... fields) {
        long[] offsets = new long[fields.length];
        long offset = 0;
        for (int i = 0; i < fields.length; i++) {
            long align = fields[i].byteAlignment();
            offset = (offset + align - 1) & ~(align - 1);
            offsets[i] = offset;
            offset += fields[i].byteSize();
        }
        return offsets;
    }

    /**
     * Creates a {@link MemoryLayout} representing a nested C struct with the
     * correct size and alignment, for use as a field in a parent struct.
     */
    private static MemoryLayout asNestedStruct(MemoryLayout... fields) {
        long size = computeStructSize(fields);
        long maxAlign = 1;
        for (MemoryLayout field : fields) {
            maxAlign = Math.max(maxAlign, field.byteAlignment());
        }
        return MemoryLayout.sequenceLayout(size, ValueLayout.JAVA_BYTE)
                .withByteAlignment(maxAlign);
    }

    // vorbis_dsp_state: see vorbis/codec.h
    private static long computeVorbisDspStateSize() {
        return computeStructSize(
                ValueLayout.JAVA_INT,                          // analysisp
                ValueLayout.ADDRESS,                           // vi
                ValueLayout.ADDRESS, ValueLayout.ADDRESS,      // pcm, pcmret
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,    // pcm_storage, pcm_current
                ValueLayout.JAVA_INT,                          // pcm_returned
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,    // preextrapolate, eofflag
                C_LONG, C_LONG, C_LONG, C_LONG,                // lW, W, nW, centerW
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,  // granulepos, sequence
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,  // glue_bits, time_bits
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,  // floor_bits, res_bits
                ValueLayout.ADDRESS);                          // backend_state
    }

    // vorbis_block: see vorbis/codec.h (contains nested oggpack_buffer)
    private static long computeVorbisBlockSize() {
        // oggpack_buffer: { long endbyte; int endbit; uchar *buffer; uchar *ptr; long storage; }
        MemoryLayout oggpackBuffer = asNestedStruct(
                C_LONG, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, C_LONG);

        return computeStructSize(
                ValueLayout.ADDRESS,                           // pcm (float **)
                oggpackBuffer,                                 // opb
                C_LONG, C_LONG, C_LONG,                        // lW, W, nW
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,    // pcmend, mode
                ValueLayout.JAVA_INT,                          // eofflag
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,  // granulepos, sequence
                ValueLayout.ADDRESS,                           // vd (vorbis_dsp_state *)
                ValueLayout.ADDRESS,                           // localstore
                C_LONG, C_LONG, C_LONG,                        // localtop, localalloc, totaluse
                ValueLayout.ADDRESS,                           // reap (alloc_chain *)
                C_LONG, C_LONG, C_LONG, C_LONG,                // glue_bits, time_bits, floor_bits, res_bits
                ValueLayout.ADDRESS);                          // internal
    }

    // ogg_stream_state: see ogg/ogg.h
    private static long computeOggStreamStateSize() {
        return computeStructSize(
                ValueLayout.ADDRESS,                           // body_data
                C_LONG, C_LONG, C_LONG,                        // body_storage, body_fill, body_returned
                ValueLayout.ADDRESS,                           // lacing_vals
                ValueLayout.ADDRESS,                           // granule_vals
                C_LONG, C_LONG, C_LONG, C_LONG,                // lacing_storage, lacing_fill, lacing_packet, lacing_returned
                MemoryLayout.sequenceLayout(282, ValueLayout.JAVA_BYTE), // header[282]
                ValueLayout.JAVA_INT,                          // header_fill
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,    // e_o_s, b_o_s
                C_LONG, C_LONG,                                // serialno, pageno
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG); // packetno, granulepos
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
            SymbolLookup vorbisLib = loadLibrary(arena, "vorbis", 0);
            SymbolLookup vorbisEncLib = loadLibrary(arena, "vorbisenc", 2);
            SymbolLookup oggLib = loadLibrary(arena, "ogg", 0);

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

            // vorbis_encode functions — channels and rate are C 'long'
            vorbisEncodeInitVbr = linker.downcallHandle(
                    vorbisEncLib.find("vorbis_encode_init_vbr").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, C_LONG,
                            C_LONG, ValueLayout.JAVA_FLOAT));

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

        /**
         * Loads a native library in an OS-aware way, preferring bundled
         * libraries in {@code java.library.path} over system-installed ones.
         *
         * @param arena     the arena for the library lifetime
         * @param baseName  the library base name (e.g. "vorbis", "vorbisenc", "ogg")
         * @param soVersion the SONAME version number (e.g. 0 for libvorbis.so.0)
         * @return a {@link SymbolLookup} for the loaded library
         * @throws UnsupportedOperationException if the library cannot be found
         */
        private static SymbolLookup loadLibrary(Arena arena, String baseName,
                                                 int soVersion) {
            String os = System.getProperty("os.name", "").toLowerCase();
            String[] names;
            if (os.contains("win")) {
                names = new String[]{baseName + ".dll", "lib" + baseName + ".dll"};
            } else if (os.contains("mac")) {
                names = new String[]{
                        "lib" + baseName + "." + soVersion + ".dylib",
                        "lib" + baseName + ".dylib"};
            } else {
                names = new String[]{
                        "lib" + baseName + ".so." + soVersion,
                        "lib" + baseName + ".so"};
            }

            // 1. Prefer bundled libraries in java.library.path
            Optional<SymbolLookup> bundled = searchLibraryPath(arena, names);
            if (bundled.isPresent()) {
                return bundled.get();
            }

            // 2. Fall back to OS-level library loader (system-installed)
            for (String name : names) {
                try {
                    return SymbolLookup.libraryLookup(name, arena);
                } catch (IllegalArgumentException _) {
                    // try next candidate
                }
            }

            // Platform-aware, library-specific error message
            String installHint;
            if (os.contains("win")) {
                installHint = "build with CMake and ensure " + baseName
                        + ".dll is in the application directory or PATH";
            } else if (os.contains("mac")) {
                installHint = "'brew install " + (baseName.equals("ogg") ? "libogg" : "libvorbis")
                        + "' on macOS";
            } else {
                String debPkg = switch (baseName) {
                    case "ogg" -> "libogg0";
                    case "vorbis" -> "libvorbis0a";
                    case "vorbisenc" -> "libvorbisenc2";
                    default -> "lib" + baseName + "0";
                };
                installHint = "'apt install " + debPkg + "' on Debian/Ubuntu";
            }
            String searchedNames = String.join(", ", names);
            String libraryPath = System.getProperty("java.library.path", "");
            throw new UnsupportedOperationException(
                    "Could not load lib" + baseName + " from bundled native directory "
                            + (libraryPath.isEmpty() ? "(none configured)" : libraryPath)
                            + " or system libraries (tried: " + searchedNames + "). "
                            + "Install lib" + baseName + " (e.g., " + installHint + ").");
        }

        /**
         * Searches {@code java.library.path} directories for any of the given
         * library filenames, loading via {@link SymbolLookup#libraryLookup(Path, Arena)}.
         */
        private static Optional<SymbolLookup> searchLibraryPath(Arena arena,
                                                                 String... fileNames) {
            String libraryPath = System.getProperty("java.library.path", "");
            if (libraryPath.isEmpty()) {
                return Optional.empty();
            }
            for (String dir : libraryPath.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue; // skip empty entries
                }
                try {
                    Path dirPath = Path.of(dir).normalize();
                    if (dirPath.toString().isEmpty()) {
                        continue; // skip empty normalized entries
                    }
                    for (String fileName : fileNames) {
                        Path candidate = dirPath.resolve(fileName).toAbsolutePath();
                        if (Files.isRegularFile(candidate)) {
                            try {
                                return Optional.of(
                                        SymbolLookup.libraryLookup(candidate, arena));
                            } catch (IllegalArgumentException _) {
                                // file exists but not loadable — try next
                            }
                        }
                    }
                } catch (InvalidPathException _) {
                    // malformed path segment — skip
                }
            }
            return Optional.empty();
        }
    }
}
