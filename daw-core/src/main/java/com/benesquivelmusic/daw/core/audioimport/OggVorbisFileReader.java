package com.benesquivelmusic.daw.core.audioimport;

import com.benesquivelmusic.daw.core.audio.NativeLibraryLoader;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Reads OGG Vorbis audio files and decodes them into normalized
 * {@code float[][]} arrays using the native libvorbisfile library
 * via the FFM API (JEP 454).
 *
 * <p>This reader uses the same vendored libogg/libvorbis/libvorbisfile
 * native libraries that the OGG Vorbis exporter uses, providing a unified
 * native codec stack for both import and export. No Java SPI dependencies
 * are required.</p>
 *
 * <p>The decode pipeline:</p>
 * <ol>
 *   <li>Open the file via {@code ov_fopen}</li>
 *   <li>Query channels and sample rate via {@code ov_info}</li>
 *   <li>Query total PCM frames via {@code ov_pcm_total}</li>
 *   <li>Loop {@code ov_read_float} to fill a {@code float[channels][frames]} array</li>
 *   <li>Close via {@code ov_clear}</li>
 * </ol>
 */
public final class OggVorbisFileReader {

    // Platform-aware C ABI type for 'long' — 8 bytes on Linux/macOS x86_64,
    // 4 bytes on Windows x86_64 (LLP64 model).
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

    // vorbis_info field offsets — we only need 'channels' (int at offset 4)
    // and 'rate' (C long at offset 8) from the struct returned by ov_info.
    // struct vorbis_info { int version; int channels; long rate; ... }
    private static final long VI_CHANNELS_OFFSET;
    private static final long VI_RATE_OFFSET;

    static {
        long offset = 0;
        // version: int
        offset += ValueLayout.JAVA_INT.byteSize(); // 4
        VI_CHANNELS_OFFSET = offset;
        // channels: int
        offset += ValueLayout.JAVA_INT.byteSize(); // 8
        // align for C long
        long longAlign = C_LONG.byteAlignment();
        offset = (offset + longAlign - 1) & ~(longAlign - 1);
        VI_RATE_OFFSET = offset;
    }

    /**
     * Size of the OggVorbis_File struct. Computed from the C struct layout
     * with all nested structs (ogg_sync_state, ogg_stream_state,
     * vorbis_dsp_state, vorbis_block, ov_callbacks) using platform-correct
     * C ABI types. See vorbis/vorbisfile.h.
     */
    static final long SIZEOF_OGG_VORBIS_FILE = computeOggVorbisFileSize();

    // libvorbisfile error codes (from vorbis/codec.h)
    private static final int OV_EREAD = -128;
    private static final int OV_EFAULT = -129;
    private static final int OV_EIMPL = -130;
    private static final int OV_EINVAL = -131;
    private static final int OV_ENOTVORBIS = -132;
    private static final int OV_EBADHEADER = -133;
    private static final int OV_EVERSION = -134;
    private static final int OV_ENOTAUDIO = -135;
    private static final int OV_EBADPACKET = -136;
    private static final int OV_EBADLINK = -137;
    private static final int OV_ENOSEEK = -138;

    private OggVorbisFileReader() {
        // utility class
    }

    /**
     * Reads an OGG Vorbis file and returns the decoded audio data.
     *
     * @param path the path to the OGG file
     * @return the decoded audio result containing samples and format info
     * @throws IOException if an I/O error occurs, the file cannot be decoded
     *                     as OGG Vorbis, or the native vorbisfile library is
     *                     not available
     */
    public static AudioReadResult read(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        if (!Files.exists(path)) {
            throw new IOException("File does not exist: " + path);
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("Not a regular file: " + path);
        }

        try (Arena arena = Arena.ofConfined()) {
            LibVorbisFileBindings lib = new LibVorbisFileBindings(arena);

            // Allocate OggVorbis_File struct
            MemorySegment vf = arena.allocate(SIZEOF_OGG_VORBIS_FILE);

            // Open the file — ov_fopen takes a C string path.
            // On Unix/macOS the filesystem encoding is UTF-8.
            // On Windows, fopen() expects the process ANSI codepage, so we
            // use the JVM's "native.encoding" property (JDK 17+) which
            // reflects the OS native charset rather than Java's default UTF-8.
            String pathStr = path.toAbsolutePath().toString();
            Charset pathCharset = resolveNativePathCharset();
            MemorySegment nativePath = arena.allocateFrom(pathStr, pathCharset);
            int openResult = (int) lib.ovFopen.invoke(nativePath, vf);
            if (openResult != 0) {
                throw new IOException("OGG Vorbis open failed: " + errorMessage(openResult));
            }

            try {
                // Query stream info (channels, sample rate)
                MemorySegment viPtr = (MemorySegment) lib.ovInfo.invoke(vf, -1);
                if (viPtr.equals(MemorySegment.NULL)) {
                    throw new IOException("OGG Vorbis decode failed: ov_info returned null");
                }
                // Reinterpret to access vorbis_info fields
                viPtr = viPtr.reinterpret(VI_RATE_OFFSET + C_LONG.byteSize());
                int channels = viPtr.get(ValueLayout.JAVA_INT, VI_CHANNELS_OFFSET);
                long rate;
                if (C_LONG.byteSize() == 8) {
                    rate = viPtr.get(ValueLayout.JAVA_LONG, VI_RATE_OFFSET);
                } else {
                    rate = viPtr.get(ValueLayout.JAVA_INT, VI_RATE_OFFSET);
                }
                if (rate <= 0 || rate > Integer.MAX_VALUE) {
                    throw new IOException("OGG Vorbis decode failed: invalid stream info "
                            + "(channels=" + channels + ", sampleRate=" + rate + ")");
                }
                int sampleRate = (int) rate;

                if (channels <= 0) {
                    throw new IOException("OGG Vorbis decode failed: invalid stream info "
                            + "(channels=" + channels + ", sampleRate=" + sampleRate + ")");
                }

                // Query total PCM frames (-1 = entire stream)
                long totalFrames = (long) lib.ovPcmTotal.invoke(vf, -1);
                if (totalFrames <= 0) {
                    throw new IOException("OGG Vorbis decode failed: ov_pcm_total returned "
                            + totalFrames);
                }

                // Allocate output buffer
                if (totalFrames > Integer.MAX_VALUE) {
                    throw new IOException("OGG Vorbis file too large: " + totalFrames + " frames");
                }
                float[][] audioData = new float[channels][(int) totalFrames];

                // Decode loop — ov_read_float returns frames decoded per call
                MemorySegment pcmPtr = arena.allocate(ValueLayout.ADDRESS);
                MemorySegment bitstreamPtr = arena.allocate(ValueLayout.JAVA_INT);
                int framesRead = 0;

                while (framesRead < (int) totalFrames) {
                    int framesToRead = (int) totalFrames - framesRead;
                    long ret;
                    if (C_LONG.byteSize() == 8) {
                        ret = (long) lib.ovReadFloat.invoke(vf, pcmPtr,
                                framesToRead, bitstreamPtr);
                    } else {
                        ret = (int) lib.ovReadFloat.invoke(vf, pcmPtr,
                                framesToRead, bitstreamPtr);
                    }

                    if (ret == 0) {
                        // End of stream — may happen before totalFrames due to
                        // decoder warm-up discard
                        break;
                    }
                    if (ret < 0) {
                        throw new IOException("OGG Vorbis decode error: "
                                + errorMessage((int) ret));
                    }

                    int decodedFrames = (int) ret;

                    // pcmPtr is float*** — dereference to get float**
                    MemorySegment channelArrayPtr = pcmPtr.get(ValueLayout.ADDRESS, 0);
                    channelArrayPtr = channelArrayPtr.reinterpret(
                            (long) channels * ValueLayout.ADDRESS.byteSize());

                    for (int ch = 0; ch < channels; ch++) {
                        MemorySegment channelBuf = channelArrayPtr.getAtIndex(
                                ValueLayout.ADDRESS, ch);
                        channelBuf = channelBuf.reinterpret(
                                (long) decodedFrames * Float.BYTES);

                        // Bulk copy native float buffer → Java array
                        MemorySegment destination = MemorySegment.ofArray(audioData[ch])
                                .asSlice((long) framesRead * Float.BYTES,
                                        (long) decodedFrames * Float.BYTES);
                        destination.copyFrom(channelBuf);
                    }

                    framesRead += decodedFrames;
                }

                // If we got fewer frames than expected, trim the arrays
                if (framesRead < (int) totalFrames) {
                    float[][] trimmed = new float[channels][framesRead];
                    for (int ch = 0; ch < channels; ch++) {
                        System.arraycopy(audioData[ch], 0, trimmed[ch], 0, framesRead);
                    }
                    audioData = trimmed;
                }

                if (framesRead == 0) {
                    throw new IOException("No audio data decoded from OGG Vorbis file: " + path);
                }

                // Vorbis is a lossy codec; report bitDepth as 0 (unknown/not applicable)
                return new AudioReadResult(audioData, sampleRate, channels, 0);
            } finally {
                lib.ovClear.invoke(vf);
            }
        } catch (IOException | UnsupportedOperationException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("OGG Vorbis decoding failed: " + t.getMessage(), t);
        }
    }

    /**
     * Translates a libvorbisfile error code to a human-readable message.
     */
    private static String errorMessage(int code) {
        return switch (code) {
            case OV_EREAD -> "OV_EREAD: read error from media";
            case OV_EFAULT -> "OV_EFAULT: internal logic fault";
            case OV_EIMPL -> "OV_EIMPL: feature not implemented";
            case OV_EINVAL -> "OV_EINVAL: invalid argument or corrupt state";
            case OV_ENOTVORBIS -> "OV_ENOTVORBIS: file is not a valid Ogg Vorbis stream";
            case OV_EBADHEADER -> "OV_EBADHEADER: invalid Vorbis bitstream header";
            case OV_EVERSION -> "OV_EVERSION: Vorbis version mismatch";
            case OV_ENOTAUDIO -> "OV_ENOTAUDIO: not an audio stream";
            case OV_EBADPACKET -> "OV_EBADPACKET: invalid packet";
            case OV_EBADLINK -> "OV_EBADLINK: invalid stream section or corrupt link";
            case OV_ENOSEEK -> "OV_ENOSEEK: stream is not seekable";
            default -> "unknown error code " + code;
        };
    }

    /**
     * Returns the charset that the platform's C runtime {@code fopen()} expects
     * for file paths. On Unix/macOS this is UTF-8; on Windows it is typically
     * the ANSI codepage (e.g., windows-1252). Since JDK 18+ defaults Java's
     * {@code Charset.defaultCharset()} to UTF-8 regardless of platform, we
     * instead read the {@code native.encoding} system property (JDK 17+) which
     * reports the actual OS native encoding.
     */
    private static Charset resolveNativePathCharset() {
        String nativeEncoding = System.getProperty("native.encoding");
        if (nativeEncoding != null) {
            try {
                return Charset.forName(nativeEncoding);
            } catch (IllegalArgumentException _) {
                // unsupported charset name — fall through to UTF-8
            }
        }
        return StandardCharsets.UTF_8;
    }

    // ── Struct size computation ────────────────────────────────────────

    /**
     * Computes the size of the OggVorbis_File struct following standard
     * C ABI padding/alignment rules. See vorbis/vorbisfile.h.
     */
    private static long computeOggVorbisFileSize() {
        // ogg_sync_state: { unsigned char *data; int storage, fill, returned,
        //                    unsynced, headerbytes, bodybytes; }
        MemoryLayout oggSyncState = asNestedStruct(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);

        // ov_callbacks: { read_func, seek_func, close_func, tell_func }
        MemoryLayout ovCallbacks = asNestedStruct(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS);

        // oggpack_buffer: { long endbyte; int endbit; uchar *buffer; uchar *ptr; long storage; }
        MemoryLayout oggpackBuffer = asNestedStruct(
                C_LONG, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, C_LONG);

        // ogg_stream_state (from ogg/ogg.h)
        MemoryLayout oggStreamState = asNestedStruct(
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

        // vorbis_dsp_state (from vorbis/codec.h)
        MemoryLayout vorbisDspState = asNestedStruct(
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

        // vorbis_block (from vorbis/codec.h, contains nested oggpack_buffer)
        MemoryLayout vorbisBlock = asNestedStruct(
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

        // OggVorbis_File struct (from vorbis/vorbisfile.h)
        return computeStructSize(
                ValueLayout.ADDRESS,                           // datasource
                ValueLayout.JAVA_INT,                          // seekable
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,  // offset, end (ogg_int64_t)
                oggSyncState,                                  // oy
                ValueLayout.JAVA_INT,                          // links
                ValueLayout.ADDRESS, ValueLayout.ADDRESS,      // offsets, dataoffsets
                ValueLayout.ADDRESS, ValueLayout.ADDRESS,      // serialnos, pcmlengths
                ValueLayout.ADDRESS, ValueLayout.ADDRESS,      // vi, vc
                ValueLayout.JAVA_LONG,                         // pcm_offset (ogg_int64_t)
                ValueLayout.JAVA_INT,                          // ready_state
                C_LONG,                                        // current_serialno
                ValueLayout.JAVA_INT,                          // current_link
                ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, // bittrack, samptrack
                oggStreamState,                                // os
                vorbisDspState,                                // vd
                vorbisBlock,                                   // vb
                ovCallbacks);                                  // callbacks
    }

    /**
     * Computes C struct size following standard ABI padding/alignment rules.
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

    // ── FFM bindings for libvorbisfile ─────────────────────────────────

    /**
     * Holds FFM method handles for the libvorbisfile decode functions.
     */
    private static final class LibVorbisFileBindings {
        final MethodHandle ovFopen;
        final MethodHandle ovInfo;
        final MethodHandle ovPcmTotal;
        final MethodHandle ovReadFloat;
        final MethodHandle ovClear;

        LibVorbisFileBindings(Arena arena) {
            SymbolLookup vorbisFileLib = NativeLibraryLoader.loadLibrary(
                    arena, "vorbisfile", 3);
            Linker linker = Linker.nativeLinker();

            // int ov_fopen(const char *path, OggVorbis_File *vf)
            ovFopen = linker.downcallHandle(
                    vorbisFileLib.find("ov_fopen").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // vorbis_info *ov_info(OggVorbis_File *vf, int link)
            ovInfo = linker.downcallHandle(
                    vorbisFileLib.find("ov_info").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            // ogg_int64_t ov_pcm_total(OggVorbis_File *vf, int i)
            ovPcmTotal = linker.downcallHandle(
                    vorbisFileLib.find("ov_pcm_total").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            // long ov_read_float(OggVorbis_File *vf, float ***pcm_channels,
            //                    int samples, int *bitstream)
            ovReadFloat = linker.downcallHandle(
                    vorbisFileLib.find("ov_read_float").orElseThrow(),
                    FunctionDescriptor.of(C_LONG,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

            // int ov_clear(OggVorbis_File *vf)
            ovClear = linker.downcallHandle(
                    vorbisFileLib.find("ov_clear").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        }
    }
}
