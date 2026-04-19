package com.benesquivelmusic.daw.core.audio.harness;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Binary golden-file format for rendered audio used by
 * {@link HeadlessAudioHarness#assertRenderMatches(Path, double[][], double)}.
 *
 * <p>File layout (big-endian): magic {@code "DAWG"} · version {@code 1} ·
 * channel count · frame count · samples ({@code double}, channel-interleaved by
 * frame).</p>
 */
public final class GoldenAudioFile {

    private static final int MAGIC = 0x44415747; // 'D','A','W','G'
    private static final int VERSION = 1;

    private GoldenAudioFile() {}

    /**
     * Writes the given audio buffer to {@code file} in the golden format.
     *
     * @param file  the destination path
     * @param audio the audio data {@code [channel][frame]}
     * @throws UncheckedIOException if the file cannot be written
     */
    public static void write(Path file, double[][] audio) {
        if (audio == null || audio.length == 0) {
            throw new IllegalArgumentException("audio must have at least one channel");
        }
        int channels = audio.length;
        int frames = audio[0].length;
        for (int ch = 1; ch < channels; ch++) {
            if (audio[ch].length != frames) {
                throw new IllegalArgumentException(
                        "All channels must have the same length: channel " + ch + " has " + audio[ch].length);
            }
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(channels);
                out.writeInt(frames);
                for (int i = 0; i < frames; i++) {
                    for (int ch = 0; ch < channels; ch++) {
                        out.writeDouble(audio[ch][i]);
                    }
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to write golden file: " + file, ex);
        }
    }

    /**
     * Reads a golden audio file produced by {@link #write(Path, double[][])}.
     *
     * @param file the golden file to read
     * @return the audio data {@code [channel][frame]}
     * @throws UncheckedIOException if the file cannot be read or is malformed
     */
    public static double[][] read(Path file) {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            int magic = in.readInt();
            if (magic != MAGIC) {
                throw new IOException("Not a DAWG golden audio file: " + file);
            }
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("Unsupported DAWG version " + version + " in " + file);
            }
            int channels = in.readInt();
            int frames = in.readInt();
            if (channels <= 0 || frames < 0) {
                throw new IOException("Invalid dimensions channels=" + channels + " frames=" + frames);
            }
            double[][] audio = new double[channels][frames];
            for (int i = 0; i < frames; i++) {
                for (int ch = 0; ch < channels; ch++) {
                    audio[ch][i] = in.readDouble();
                }
            }
            return audio;
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read golden file: " + file, ex);
        }
    }

    /**
     * Asserts {@code actual} matches the golden file within the given
     * tolerance. Tolerance is expressed in dBFS relative to full scale
     * ({@code ±1.0}): e.g., {@code -90.0} ≈ {@code 3.16e-5} per-sample
     * linear tolerance.
     *
     * @param goldenFile    the golden file on disk
     * @param actual        the rendered audio
     * @param toleranceDbfs maximum per-sample deviation, in dBFS
     * @throws AssertionError if the buffers differ beyond the tolerance or
     *                        have different shapes
     */
    public static void assertMatches(Path goldenFile, double[][] actual, double toleranceDbfs) {
        if (actual == null) {
            throw new AssertionError("actual buffer is null");
        }
        double[][] expected = read(goldenFile);
        if (expected.length != actual.length) {
            throw new AssertionError("Channel count mismatch: expected="
                    + expected.length + " actual=" + actual.length);
        }
        double linearTolerance = Math.pow(10.0, toleranceDbfs / 20.0);
        for (int ch = 0; ch < expected.length; ch++) {
            if (expected[ch].length != actual[ch].length) {
                throw new AssertionError("Frame count mismatch on channel " + ch
                        + ": expected=" + expected[ch].length + " actual=" + actual[ch].length);
            }
            for (int i = 0; i < expected[ch].length; i++) {
                double diff = Math.abs(expected[ch][i] - actual[ch][i]);
                if (diff > linearTolerance) {
                    throw new AssertionError(String.format(
                            "Sample mismatch at channel=%d frame=%d: expected=%.9f actual=%.9f "
                                    + "diff=%.9e tolerance=%.9e (%.1f dBFS)",
                            ch, i, expected[ch][i], actual[ch][i],
                            diff, linearTolerance, toleranceDbfs));
                }
            }
        }
    }
}
