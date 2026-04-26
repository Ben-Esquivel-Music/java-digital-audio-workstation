package com.benesquivelmusic.daw.core.dsp.reverb;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Bundled impulse-response library shipped with the DAW.
 *
 * <p>The library exposes a small, license-clean catalogue of synthetic
 * impulse responses representing common reverb spaces — small/medium/large
 * rooms, halls, plates, springs, cathedrals — without bundling proprietary
 * recordings. Each IR is generated deterministically from a seeded random
 * source so test results are reproducible across platforms and JVMs.</p>
 *
 * <p>Each entry has a stable {@link Entry#id() id} suitable for persistence
 * (project files reference IRs by id, never by file path). Custom user IRs
 * loaded from disk are out of scope for this catalogue and are handled
 * directly by {@link ConvolutionReverbProcessor#setImpulseResponse}.</p>
 */
public final class ImpulseResponseLibrary {

    /**
     * A single bundled IR entry.
     *
     * @param id          stable identifier used in project files (lowercase, dash-separated)
     * @param displayName human-readable label for the UI
     * @param lengthSec   nominal IR length in seconds
     */
    public record Entry(String id, String displayName, double lengthSec) {}

    /** All bundled IR entries, in stable display order. */
    public static final List<Entry> ENTRIES = List.of(
            new Entry("small-room",  "Small Room",     0.6),
            new Entry("medium-room", "Medium Room",    1.2),
            new Entry("large-room",  "Large Room",     2.0),
            new Entry("hall",        "Concert Hall",   3.5),
            new Entry("cathedral",   "Cathedral",      6.0),
            new Entry("plate",       "Vintage Plate",  2.5),
            new Entry("spring",      "Spring Tank",    1.8),
            new Entry("chamber",     "Echo Chamber",   1.5));

    /** Resource path prefix for any optional WAV files dropped in alongside. */
    public static final String RESOURCE_PREFIX = "/impulse-responses/";

    /** Cache of generated IRs keyed by {@code id + "@" + sampleRate}. */
    private static final ConcurrentMap<String, float[][]> CACHE = new ConcurrentHashMap<>();

    private ImpulseResponseLibrary() {}

    /**
     * Returns the bundled entry with the given id, or {@code null} if none.
     *
     * @param id the IR identifier
     * @return the matching entry, or {@code null}
     */
    public static Entry findById(String id) {
        if (id == null) {
            return null;
        }
        for (Entry e : ENTRIES) {
            if (e.id.equals(id)) {
                return e;
            }
        }
        return null;
    }

    /**
     * Loads a bundled stereo impulse response at the given sample rate.
     *
     * <p>This method may allocate and is therefore safe to call only from
     * a worker thread (e.g. the IR-preparation virtual thread); the audio
     * thread must not invoke it.</p>
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>If a WAV file is present at
     *       {@code /impulse-responses/{id}.wav}, decode it.</li>
     *   <li>Otherwise synthesize a deterministic exponentially-decaying
     *       diffuse-noise IR with parameters chosen to match the entry's
     *       displayed character.</li>
     * </ol>
     *
     * @param id         a bundled entry id
     * @param sampleRate sample rate in Hz
     * @return a {@code [2][N]} stereo IR (left/right channels)
     * @throws IllegalArgumentException if {@code id} is unknown
     */
    public static float[][] load(String id, double sampleRate) {
        Entry entry = findById(id);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown impulse-response id: " + id);
        }
        String key = id + "@" + (int) Math.round(sampleRate);
        return CACHE.computeIfAbsent(key, _ -> loadOrSynthesize(entry, sampleRate));
    }

    private static float[][] loadOrSynthesize(Entry entry, double sampleRate) {
        try (InputStream in = ImpulseResponseLibrary.class.getResourceAsStream(
                RESOURCE_PREFIX + entry.id + ".wav")) {
            if (in != null) {
                return WavIo.decode(in.readAllBytes(), sampleRate);
            }
        } catch (IOException ignored) {
            // fall through to synthesis
        }
        return synthesize(entry, sampleRate);
    }

    /**
     * Loads an impulse response from a user-provided file. Decodes WAV; for
     * everything else a clear exception is thrown — callers should run this
     * on a worker thread because file I/O blocks.
     *
     * @param path       path to a {@code .wav} file
     * @param sampleRate target sample rate (the file is resampled if needed)
     * @return a {@code [channels][N]} IR
     * @throws IOException if the file cannot be read or decoded
     */
    public static float[][] loadFromFile(Path path, double sampleRate) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return WavIo.decode(bytes, sampleRate);
    }

    /**
     * Synthesizes a deterministic stereo impulse response.
     *
     * <p>Uses a sparse early-reflection burst followed by exponentially
     * decaying noise. A high-frequency damping coefficient is applied so the
     * IR has a perceptually natural roll-off. The two channels are
     * independently seeded for natural decorrelation.</p>
     */
    private static float[][] synthesize(Entry entry, double sampleRate) {
        int n = Math.max(1, (int) Math.round(entry.lengthSec * sampleRate));
        float[][] ir = new float[2][n];
        for (int ch = 0; ch < 2; ch++) {
            // Deterministic seed: mix entry id hash with channel
            Random rng = new Random((long) entry.id.hashCode() * 1315423911L + ch);
            // Decay constant such that level at the end is ~ -60 dB
            double tau = entry.lengthSec / 6.9;
            double damp = 0.0;       // one-pole low-pass state
            double dampCoeff = 0.85; // softer high frequencies on tails
            // Sparse early reflections in the first 30 ms
            int earlyN = Math.min(n, (int) (0.030 * sampleRate));
            for (int i = 0; i < Math.min(20, earlyN); i++) {
                int idx = rng.nextInt(Math.max(1, earlyN));
                ir[ch][idx] += (rng.nextFloat() * 2f - 1f) * 0.7f;
            }
            // Diffuse exponentially decaying tail
            for (int i = 0; i < n; i++) {
                double t = i / sampleRate;
                double envelope = Math.exp(-t / tau);
                double noise = rng.nextDouble() * 2.0 - 1.0;
                damp = damp * dampCoeff + noise * (1.0 - dampCoeff);
                ir[ch][i] += (float) (damp * envelope * 0.5);
            }
        }
        return normalizePeak(ir, 0.95f);
    }

    /** Normalizes an IR so that its peak absolute value equals {@code peak}. */
    public static float[][] normalizePeak(float[][] ir, float peak) {
        float max = 0f;
        for (float[] ch : ir) {
            for (float v : ch) {
                float a = Math.abs(v);
                if (a > max) max = a;
            }
        }
        if (max <= 0f) {
            return ir;
        }
        float scale = peak / max;
        for (float[] ch : ir) {
            for (int i = 0; i < ch.length; i++) {
                ch[i] *= scale;
            }
        }
        return ir;
    }

    /**
     * Minimal WAV reader/writer used for bundled and user-loaded IR files.
     *
     * <p>Supports PCM 16-bit, 24-bit, 32-bit, and 32-bit IEEE float WAVs
     * (the formats conventionally used for distributing IRs). Integer PCM
     * samples are converted to normalized {@code [-1.0, 1.0]} floats. Mono
     * WAVs are duplicated to stereo; stereo files are returned as-is.
     * When the WAV sample rate differs from {@code targetSampleRate}, the
     * decoded audio is resampled to the requested rate using linear
     * interpolation.</p>
     */
    static final class WavIo {

        private WavIo() {}

        static float[][] decode(byte[] bytes, double targetSampleRate) throws IOException {
            ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (bytes.length < 44 || buf.getInt() != 0x46464952 /* "RIFF" */) {
                throw new IOException("Not a RIFF/WAV file");
            }
            buf.getInt(); // file size
            if (buf.getInt() != 0x45564157 /* "WAVE" */) {
                throw new IOException("Not a WAVE file");
            }
            short numChannels = 0;
            int sampleRate = 0;
            short bitsPerSample = 0;
            short audioFormat = 1;
            byte[] data = null;
            while (buf.remaining() >= 8) {
                int chunkId = buf.getInt();
                int chunkSize = buf.getInt();
                if (chunkId == 0x20746d66 /* "fmt " */) {
                    int start = buf.position();
                    audioFormat = buf.getShort();
                    numChannels = buf.getShort();
                    sampleRate = buf.getInt();
                    buf.getInt(); // byte rate
                    buf.getShort(); // block align
                    bitsPerSample = buf.getShort();
                    buf.position(start + chunkSize + (chunkSize & 1));
                } else if (chunkId == 0x61746164 /* "data" */) {
                    data = new byte[chunkSize];
                    buf.get(data);
                    // RIFF chunks are word-aligned; skip the trailing pad byte
                    // when chunkSize is odd so subsequent parsing stays in sync.
                    if ((chunkSize & 1) != 0 && buf.remaining() > 0) {
                        buf.position(buf.position() + 1);
                    }
                    break;
                } else {
                    // Word-align odd chunk sizes per the RIFF spec.
                    buf.position(buf.position() + chunkSize + (chunkSize & 1));
                }
            }
            if (data == null || numChannels <= 0 || bitsPerSample <= 0) {
                throw new IOException("WAV missing fmt/data chunks");
            }
            int bytesPerSample = bitsPerSample / 8;
            int frames = data.length / (bytesPerSample * numChannels);
            float[][] out = new float[Math.max(2, numChannels)][frames];
            ByteBuffer db = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < frames; i++) {
                for (int c = 0; c < numChannels; c++) {
                    float v;
                    if (audioFormat == 3 && bitsPerSample == 32) {
                        v = db.getFloat();
                    } else if (bitsPerSample == 16) {
                        v = db.getShort() / 32768f;
                    } else if (bitsPerSample == 24) {
                        int b0 = db.get() & 0xFF;
                        int b1 = db.get() & 0xFF;
                        int b2 = db.get();
                        int s = (b2 << 16) | (b1 << 8) | b0;
                        v = s / 8388608f;
                    } else if (bitsPerSample == 32) {
                        v = db.getInt() / (float) Integer.MAX_VALUE;
                    } else {
                        throw new IOException("Unsupported WAV format: " + bitsPerSample + " bits, format " + audioFormat);
                    }
                    out[c][i] = v;
                }
                if (numChannels == 1) {
                    out[1][i] = out[0][i];
                }
            }
            // Naive resample if sample rates differ (linear interpolation)
            if (sampleRate > 0 && Math.abs(sampleRate - targetSampleRate) > 0.5) {
                return linearResample(out, sampleRate, targetSampleRate);
            }
            return out;
        }

        private static float[][] linearResample(float[][] in, double srcRate, double dstRate) {
            int srcLen = in[0].length;
            int dstLen = (int) Math.round(srcLen * dstRate / srcRate);
            float[][] out = new float[in.length][dstLen];
            for (int c = 0; c < in.length; c++) {
                for (int i = 0; i < dstLen; i++) {
                    double srcPos = i * srcRate / dstRate;
                    int idx = (int) srcPos;
                    double frac = srcPos - idx;
                    float a = in[c][Math.min(idx, srcLen - 1)];
                    float b = in[c][Math.min(idx + 1, srcLen - 1)];
                    out[c][i] = (float) (a + (b - a) * frac);
                }
            }
            return out;
        }
    }
}
