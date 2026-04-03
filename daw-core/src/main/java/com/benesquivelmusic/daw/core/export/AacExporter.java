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
 * Exports audio data to AAC (Advanced Audio Coding) format using
 * FFM API (JEP 454) bindings to the Fraunhofer FDK AAC encoder library.
 *
 * <p>AAC is the successor to MP3 and offers better audio quality at
 * similar bitrates. It is the default audio format for Apple ecosystem
 * distribution (iTunes, Apple Music, Apple Podcasts).</p>
 *
 * <p>The export pipeline:</p>
 * <ol>
 *   <li>Apply dithering to convert float audio to 16-bit signed PCM</li>
 *   <li>Configure FDK-AAC encoder with AAC-LC profile and ADTS transport</li>
 *   <li>Feed interleaved 16-bit PCM to {@code aacEncEncode}</li>
 *   <li>Write ADTS-framed AAC output to disk</li>
 * </ol>
 *
 * <p>The output uses ADTS (Audio Data Transport Stream) framing, which
 * is self-synchronizing and widely supported by media players.</p>
 *
 * @throws UnsupportedOperationException if {@code libfdk-aac} is not installed
 */
public final class AacExporter {

    // FDK-AAC parameter IDs
    private static final int AACENC_AOT = 0x0100;
    private static final int AACENC_BITRATE = 0x0101;
    private static final int AACENC_SAMPLERATE = 0x0103;
    private static final int AACENC_CHANNELMODE = 0x0106;
    private static final int AACENC_TRANSMUX = 0x0300;
    private static final int AACENC_AFTERBURNER = 0x0200;

    // Audio Object Types
    private static final int AOT_AAC_LC = 2;

    // Transport types
    private static final int TT_MP4_ADTS = 2;

    // Channel modes
    private static final int MODE_1 = 1; // mono
    private static final int MODE_2 = 2; // stereo

    // Buffer identifiers
    private static final int IN_AUDIO_DATA = 0;
    private static final int OUT_BITSTREAM_DATA = 3;

    // AACENC_BufDesc struct layout (x86_64)
    private static final long BUFDESC_NUM_BUFS = 0;
    private static final long BUFDESC_BUFS = 8;
    private static final long BUFDESC_BUF_IDS = 16;
    private static final long BUFDESC_BUF_SIZES = 24;
    private static final long BUFDESC_BUF_EL_SIZES = 32;
    private static final long SIZEOF_BUFDESC = 40;

    // AACENC_InArgs struct layout — over-allocated for ABI safety across
    // FDK-AAC versions that may include additional fields beyond the two
    // documented INT fields (numInSamples, numAncBytes).
    private static final long INARGS_NUM_IN_SAMPLES = 0;
    private static final long SIZEOF_INARGS = 32;

    // AACENC_OutArgs struct layout — over-allocated for ABI safety
    private static final long OUTARGS_NUM_OUT_BYTES = 0;
    private static final long OUTARGS_NUM_IN_SAMPLES = 4;
    private static final long SIZEOF_OUTARGS = 32;

    // AACENC_InfoStruct layout
    private static final long INFO_FRAME_LENGTH = 16;
    private static final long SIZEOF_INFO = 96;

    // Success return code
    private static final int AACENC_OK = 0x0000;

    private AacExporter() {
        // utility class
    }

    /**
     * Writes audio data to an AAC file (ADTS framing) using FDK-AAC via FFM.
     *
     * @param audioData  audio samples as {@code [channel][sample]} in [-1.0, 1.0]
     * @param sampleRate the sample rate in Hz
     * @param bitDepth   the source bit depth (dithering applied to reduce to 16-bit)
     * @param ditherType the dithering algorithm for bit-depth reduction
     * @param metadata   metadata (not embedded in ADTS; retained for API consistency)
     * @param quality    encoder quality in [0.0, 1.0] mapped to AAC bitrate
     * @param outputPath the output file path
     * @throws IOException if an I/O error occurs
     * @throws UnsupportedOperationException if libfdk-aac is not available
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
            SymbolLookup fdkAac = loadFdkAacLibrary(arena);
            Linker linker = Linker.nativeLinker();

            // Resolve FDK-AAC functions
            MethodHandle aacEncOpen = linker.downcallHandle(
                    fdkAac.find("aacEncOpen").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT));
            MethodHandle aacEncoderSetParam = linker.downcallHandle(
                    fdkAac.find("aacEncoder_SetParam").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT));
            MethodHandle aacEncEncode = linker.downcallHandle(
                    fdkAac.find("aacEncEncode").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));
            MethodHandle aacEncInfo = linker.downcallHandle(
                    fdkAac.find("aacEncInfo").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle aacEncClose = linker.downcallHandle(
                    fdkAac.find("aacEncClose").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

            // Open encoder — handle is stored via pointer-to-pointer
            MemorySegment handlePtr = arena.allocate(ValueLayout.ADDRESS);
            int err = (int) aacEncOpen.invoke(handlePtr, 0, channels);
            if (err != AACENC_OK) {
                throw new IOException("FDK-AAC encoder open failed: " + err);
            }

            MemorySegment handle = handlePtr.get(ValueLayout.ADDRESS, 0);

            try {
                // Configure encoder
                checkAac(aacEncoderSetParam, handle, AACENC_AOT, AOT_AAC_LC);
                checkAac(aacEncoderSetParam, handle, AACENC_SAMPLERATE, sampleRate);
                checkAac(aacEncoderSetParam, handle, AACENC_CHANNELMODE,
                        channels == 1 ? MODE_1 : MODE_2);

                // Clamp quality to the supported [0.0, 1.0] range before mapping
                // to bitrate per channel (64–192 kbps per channel).
                double clampedQuality = Math.max(0.0, Math.min(1.0, quality));
                int bitratePerChannel = (int) (64000 + clampedQuality * 128000);
                checkAac(aacEncoderSetParam, handle, AACENC_BITRATE,
                        bitratePerChannel * channels);

                checkAac(aacEncoderSetParam, handle, AACENC_TRANSMUX, TT_MP4_ADTS);
                checkAac(aacEncoderSetParam, handle, AACENC_AFTERBURNER, 1);

                // Initialize encoder by calling aacEncEncode with NULL args
                err = (int) aacEncEncode.invoke(handle,
                        MemorySegment.NULL, MemorySegment.NULL,
                        MemorySegment.NULL, MemorySegment.NULL);
                if (err != AACENC_OK) {
                    throw new IOException("FDK-AAC encoder initialization failed: " + err);
                }

                // Get encoder info for frame size
                MemorySegment info = arena.allocate(SIZEOF_INFO);
                err = (int) aacEncInfo.invoke(handle, info);
                if (err != AACENC_OK) {
                    throw new IOException("FDK-AAC encoder info failed: " + err);
                }
                int frameLength = info.get(ValueLayout.JAVA_INT, INFO_FRAME_LENGTH);

                // Prepare ditherers
                TpdfDitherer tpdf = (ditherType == DitherType.TPDF) ? new TpdfDitherer() : null;
                NoiseShapedDitherer[] noiseShaped = null;
                if (ditherType == DitherType.NOISE_SHAPED) {
                    noiseShaped = new NoiseShapedDitherer[channels];
                    for (int ch = 0; ch < channels; ch++) {
                        noiseShaped[ch] = new NoiseShapedDitherer();
                    }
                }

                // Allocate buffers
                int inputSamplesPerFrame = frameLength * channels;
                int pcmBufSize = inputSamplesPerFrame * Short.BYTES;
                MemorySegment pcmBuf = arena.allocate(pcmBufSize);

                int outBufSize = 20480; // generous output buffer for one frame
                MemorySegment outBuf = arena.allocate(outBufSize);
                byte[] outBytes = new byte[outBufSize];

                // Buffer descriptor components
                MemorySegment inBufId = arena.allocate(ValueLayout.JAVA_INT);
                inBufId.set(ValueLayout.JAVA_INT, 0, IN_AUDIO_DATA);
                MemorySegment inBufSize = arena.allocate(ValueLayout.JAVA_INT);
                MemorySegment inBufElSize = arena.allocate(ValueLayout.JAVA_INT);
                inBufElSize.set(ValueLayout.JAVA_INT, 0, Short.BYTES);
                MemorySegment inBufPtr = arena.allocate(ValueLayout.ADDRESS);

                MemorySegment outBufId = arena.allocate(ValueLayout.JAVA_INT);
                outBufId.set(ValueLayout.JAVA_INT, 0, OUT_BITSTREAM_DATA);
                MemorySegment outBufSizeArr = arena.allocate(ValueLayout.JAVA_INT);
                outBufSizeArr.set(ValueLayout.JAVA_INT, 0, outBufSize);
                MemorySegment outBufElSize = arena.allocate(ValueLayout.JAVA_INT);
                outBufElSize.set(ValueLayout.JAVA_INT, 0, 1);
                MemorySegment outBufPtr = arena.allocate(ValueLayout.ADDRESS);
                outBufPtr.set(ValueLayout.ADDRESS, 0, outBuf);

                // Build buffer descriptors
                MemorySegment inBufDesc = arena.allocate(SIZEOF_BUFDESC);
                MemorySegment outBufDesc = arena.allocate(SIZEOF_BUFDESC);

                outBufDesc.set(ValueLayout.JAVA_INT, BUFDESC_NUM_BUFS, 1);
                outBufDesc.set(ValueLayout.ADDRESS, BUFDESC_BUFS, outBufPtr);
                outBufDesc.set(ValueLayout.ADDRESS, BUFDESC_BUF_IDS, outBufId);
                outBufDesc.set(ValueLayout.ADDRESS, BUFDESC_BUF_SIZES, outBufSizeArr);
                outBufDesc.set(ValueLayout.ADDRESS, BUFDESC_BUF_EL_SIZES, outBufElSize);

                MemorySegment inArgs = arena.allocate(SIZEOF_INARGS);
                MemorySegment outArgs = arena.allocate(SIZEOF_OUTARGS);

                try (OutputStream out = Files.newOutputStream(outputPath)) {
                    int samplesRead = 0;

                    while (samplesRead < numSamples) {
                        int framesToRead = Math.min(frameLength, numSamples - samplesRead);

                        // Convert float to interleaved 16-bit PCM
                        for (int i = 0; i < framesToRead; i++) {
                            for (int ch = 0; ch < channels; ch++) {
                                double sample = audioData[ch][samplesRead + i];
                                sample = Math.max(-1.0, Math.min(1.0, sample));
                                short pcmSample = (short) quantize16(sample, tpdf,
                                        noiseShaped != null ? noiseShaped[ch] : null);
                                pcmBuf.setAtIndex(ValueLayout.JAVA_SHORT,
                                        (long) i * channels + ch, pcmSample);
                            }
                        }

                        // Pad remaining samples with silence if last frame is incomplete
                        for (int i = framesToRead * channels; i < inputSamplesPerFrame; i++) {
                            pcmBuf.setAtIndex(ValueLayout.JAVA_SHORT, i, (short) 0);
                        }

                        // Set up input buffer descriptor
                        inBufPtr.set(ValueLayout.ADDRESS, 0, pcmBuf);
                        inBufSize.set(ValueLayout.JAVA_INT, 0, framesToRead * channels * Short.BYTES);

                        inBufDesc.set(ValueLayout.JAVA_INT, BUFDESC_NUM_BUFS, 1);
                        inBufDesc.set(ValueLayout.ADDRESS, BUFDESC_BUFS, inBufPtr);
                        inBufDesc.set(ValueLayout.ADDRESS, BUFDESC_BUF_IDS, inBufId);
                        inBufDesc.set(ValueLayout.ADDRESS, BUFDESC_BUF_SIZES, inBufSize);
                        inBufDesc.set(ValueLayout.ADDRESS, BUFDESC_BUF_EL_SIZES, inBufElSize);

                        inArgs.set(ValueLayout.JAVA_INT, INARGS_NUM_IN_SAMPLES,
                                framesToRead * channels);

                        err = (int) aacEncEncode.invoke(handle,
                                inBufDesc, outBufDesc, inArgs, outArgs);
                        if (err != AACENC_OK) {
                            throw new IOException("FDK-AAC encoding error: " + err);
                        }

                        int outBytes2 = outArgs.get(ValueLayout.JAVA_INT,
                                OUTARGS_NUM_OUT_BYTES);
                        if (outBytes2 > 0) {
                            MemorySegment.copy(outBuf, ValueLayout.JAVA_BYTE, 0,
                                    outBytes, 0, outBytes2);
                            out.write(outBytes, 0, outBytes2);
                        }

                        samplesRead += framesToRead;
                    }

                    // Flush encoder — feed empty input to get remaining frames
                    flushEncoder(handle, aacEncEncode, inArgs, outBufDesc,
                            outArgs, outBuf, outBytes, out, arena);
                }
            } finally {
                aacEncClose.invoke(handlePtr);
            }
        } catch (IOException | UnsupportedOperationException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("AAC encoding failed: " + t.getMessage(), t);
        }
    }

    private static void flushEncoder(MemorySegment handle, MethodHandle aacEncEncode,
                                     MemorySegment inArgs, MemorySegment outBufDesc,
                                     MemorySegment outArgs, MemorySegment outBuf,
                                     byte[] outBytes, OutputStream out,
                                     Arena arena) throws Throwable {
        // Signal EOF by setting numInSamples to -1
        inArgs.set(ValueLayout.JAVA_INT, INARGS_NUM_IN_SAMPLES, -1);

        MemorySegment emptyBufDesc = arena.allocate(SIZEOF_BUFDESC);
        emptyBufDesc.set(ValueLayout.JAVA_INT, BUFDESC_NUM_BUFS, 0);

        for (int i = 0; i < 10; i++) { // guard against infinite loop
            int err = (int) aacEncEncode.invoke(handle,
                    emptyBufDesc, outBufDesc, inArgs, outArgs);
            if (err != AACENC_OK) {
                break; // AACENC_ENCODE_EOF or error
            }
            int numOutBytes = outArgs.get(ValueLayout.JAVA_INT, OUTARGS_NUM_OUT_BYTES);
            if (numOutBytes > 0) {
                MemorySegment.copy(outBuf, ValueLayout.JAVA_BYTE, 0,
                        outBytes, 0, numOutBytes);
                out.write(outBytes, 0, numOutBytes);
            } else {
                break;
            }
        }
    }

    private static void checkAac(MethodHandle aacEncoderSetParam,
                                 MemorySegment handle,
                                 int param, int value) throws Throwable {
        int err = (int) aacEncoderSetParam.invoke(handle, param, value);
        if (err != AACENC_OK) {
            throw new IOException("FDK-AAC set parameter 0x"
                    + Integer.toHexString(param) + " failed: " + err);
        }
    }

    private static SymbolLookup loadFdkAacLibrary(Arena arena) {
        try {
            return SymbolLookup.libraryLookup("libfdk-aac.so.2", arena);
        } catch (IllegalArgumentException e1) {
            try {
                return SymbolLookup.libraryLookup("libfdk-aac.so", arena);
            } catch (IllegalArgumentException e2) {
                throw new UnsupportedOperationException(
                        "AAC export requires libfdk-aac. "
                                + "Install FDK-AAC (e.g., 'apt install libfdk-aac2' "
                                + "on Debian/Ubuntu).");
            }
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
