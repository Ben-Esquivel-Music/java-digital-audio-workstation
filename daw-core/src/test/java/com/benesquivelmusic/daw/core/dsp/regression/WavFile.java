package com.benesquivelmusic.daw.core.dsp.regression;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal canonical PCM WAV file I/O for the DSP regression test framework.
 *
 * <p>Supports 16-bit little-endian PCM ({@code WAVE_FORMAT_PCM = 1}), the
 * one canonical encoding the framework uses for both committed test signals
 * and golden files. Keeping the format strictly fixed gives byte-exact
 * reproducibility across platforms and JDKs — which is what golden-file
 * regression testing requires.</p>
 *
 * <p>This is deliberately a tiny self-contained reader/writer rather than
 * routing through {@code javax.sound.sampled} so that the regression suite
 * is independent of the host's audio mixer, which differs subtly between
 * Linux/macOS/Windows JDK builds.</p>
 */
public final class WavFile {

    /** Signed 16-bit PCM full-scale value (used for {@code float} ↔ {@code int16} conversion). */
    private static final float INT16_SCALE = 32767.0f;

    private WavFile() {}

    /** Immutable, value-typed result of a successful WAV read. */
    public record Audio(float[][] samples, int sampleRate) {
        public Audio {
            if (samples == null || samples.length == 0) {
                throw new IllegalArgumentException("samples must be non-empty");
            }
            if (sampleRate <= 0) {
                throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
            }
            // Defensive shape check — every channel must have the same frame count.
            int frames = samples[0].length;
            for (float[] ch : samples) {
                if (ch.length != frames) {
                    throw new IllegalArgumentException("ragged channels not allowed");
                }
            }
        }

        /** @return number of channels. */
        public int channels() { return samples.length; }

        /** @return number of frames per channel. */
        public int frames() { return samples[0].length; }
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    /** Reads a 16-bit PCM WAV file from disk. */
    public static Audio read(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return read(in);
        }
    }

    /** Reads a 16-bit PCM WAV file from a classpath resource. */
    public static Audio readResource(String resourcePath) throws IOException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream in = cl.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Classpath resource not found: " + resourcePath);
            }
            return read(in);
        }
    }

    /** Reads a 16-bit PCM WAV stream and decodes it to per-channel float samples in [-1, 1]. */
    public static Audio read(InputStream in) throws IOException {
        byte[] all = in.readAllBytes();
        ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        if (all.length < 44) {
            throw new IOException("File too short to be a WAV (length=" + all.length + ")");
        }
        if (bb.getInt(0) != 0x46464952 /* "RIFF" */) {
            throw new IOException("Not a RIFF file");
        }
        if (bb.getInt(8) != 0x45564157 /* "WAVE" */) {
            throw new IOException("Not a WAVE file");
        }
        int pos = 12;
        int audioFormat = -1, channels = -1, sampleRate = -1, bitsPerSample = -1;
        int dataOffset = -1, dataLen = -1;
        while (pos + 8 <= all.length) {
            int id = bb.getInt(pos);
            int size = bb.getInt(pos + 4);
            int payload = pos + 8;
            if (id == 0x20746d66 /* "fmt " */) {
                if (size < 16) throw new IOException("Truncated fmt chunk");
                audioFormat = bb.getShort(payload) & 0xFFFF;
                channels = bb.getShort(payload + 2) & 0xFFFF;
                sampleRate = bb.getInt(payload + 4);
                bitsPerSample = bb.getShort(payload + 14) & 0xFFFF;
            } else if (id == 0x61746164 /* "data" */) {
                dataOffset = payload;
                dataLen = size;
                break;
            }
            pos = payload + size + (size & 1); // chunks are word-aligned
        }
        if (audioFormat != 1) {
            throw new IOException("Only PCM (format=1) supported, got " + audioFormat);
        }
        if (bitsPerSample != 16) {
            throw new IOException("Only 16-bit samples supported, got " + bitsPerSample);
        }
        if (dataOffset < 0 || dataLen < 0) {
            throw new IOException("WAV is missing data chunk");
        }
        if (channels <= 0 || sampleRate <= 0) {
            throw new IOException("Invalid channel/sample-rate header");
        }
        int frames = dataLen / (2 * channels);
        float[][] out = new float[channels][frames];
        int p = dataOffset;
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < channels; c++) {
                short s = bb.getShort(p);
                p += 2;
                out[c][f] = s / INT16_SCALE;
            }
        }
        return new Audio(out, sampleRate);
    }

    // ── Writing ─────────────────────────────────────────────────────────────

    /** Writes per-channel float samples to disk as a 16-bit PCM WAV. */
    public static void write(Path path, float[][] samples, int sampleRate) throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (OutputStream out = Files.newOutputStream(path)) {
            write(out, samples, sampleRate);
        }
    }

    /** Encodes per-channel float samples as a 16-bit PCM WAV stream. */
    public static void write(OutputStream out, float[][] samples, int sampleRate) throws IOException {
        if (samples == null || samples.length == 0) {
            throw new IllegalArgumentException("samples must be non-empty");
        }
        int channels = samples.length;
        int frames = samples[0].length;
        int dataLen = frames * channels * 2;
        ByteBuffer bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0x46464952);                   // "RIFF"
        bb.putInt(36 + dataLen);                 // file size − 8
        bb.putInt(0x45564157);                   // "WAVE"
        bb.putInt(0x20746d66);                   // "fmt "
        bb.putInt(16);                           // PCM fmt chunk size
        bb.putShort((short) 1);                  // audioFormat = PCM
        bb.putShort((short) channels);
        bb.putInt(sampleRate);
        bb.putInt(sampleRate * channels * 2);    // byte rate
        bb.putShort((short) (channels * 2));     // block align
        bb.putShort((short) 16);                 // bits per sample
        bb.putInt(0x61746164);                   // "data"
        bb.putInt(dataLen);
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < channels; c++) {
                float v = samples[c][f];
                if (v >  1.0f) v =  1.0f;
                if (v < -1.0f) v = -1.0f;
                bb.putShort((short) Math.round(v * INT16_SCALE));
            }
        }
        out.write(bb.array());
    }

    /** Convenience: encode to an in-memory byte array. */
    public static byte[] toByteArray(float[][] samples, int sampleRate) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            write(baos, samples, sampleRate);
        } catch (IOException e) {
            throw new RuntimeException(e); // ByteArrayOutputStream cannot actually throw
        }
        return baos.toByteArray();
    }
}
