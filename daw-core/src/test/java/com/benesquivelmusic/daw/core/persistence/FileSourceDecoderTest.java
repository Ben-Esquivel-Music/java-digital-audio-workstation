package com.benesquivelmusic.daw.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.benesquivelmusic.daw.core.persistence.AudioFileFixtures.wav;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The memory the production decoder charges a single file before it decodes
 * it (decisions D10, D29): the file's bytes, which the readers hold while
 * decoding, one decoded copy, and a second one for the conversion to the
 * project rate only when the file's header gives another rate.
 */
class FileSourceDecoderTest {

    private static final int PROJECT_RATE = 48_000;
    private static final int CHANNELS = 2;

    @TempDir
    Path tempDir;

    @Test
    void aWavAtTheProjectRateIsChargedItsBytesAndOneDecodedCopy() throws Exception {
        int frames = 4_800;
        Path file = wav(tempDir.resolve("at-project-rate.wav"), PROJECT_RATE, frames, CHANNELS);

        long cost = FileSourceDecoder.INSTANCE.estimateCost(
                new AssetReference.SingleFile(file.toString()), PROJECT_RATE);

        long decoded = (long) frames * CHANNELS * Float.BYTES;
        assertThat(cost).as("the conversion returns the decoded array itself: no second copy")
                .isEqualTo(Files.size(file) + decoded);
    }

    @Test
    void aWavAtAnotherRateIsChargedItsBytesTheDecodedCopyAndTheConvertedCopy() throws Exception {
        int frames = 4_410;
        Path file = wav(tempDir.resolve("at-44k1.wav"), 44_100, frames, CHANNELS);

        long cost = FileSourceDecoder.INSTANCE.estimateCost(
                new AssetReference.SingleFile(file.toString()), PROJECT_RATE);

        long decoded = (long) frames * CHANNELS * Float.BYTES;
        long converted = (long) Math.ceil(frames * CHANNELS * (double) PROJECT_RATE / 44_100) * Float.BYTES;
        assertThat(cost).as("0.1 s at 44.1 kHz, then the same 0.1 s at 48 kHz")
                .isEqualTo(Files.size(file) + decoded + converted);
    }
}
