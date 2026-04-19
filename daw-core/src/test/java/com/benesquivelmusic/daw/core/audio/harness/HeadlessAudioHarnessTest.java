package com.benesquivelmusic.daw.core.audio.harness;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Self-tests for {@link HeadlessAudioHarness}, verifying determinism,
 * timeout enforcement, golden-file round-trips, and that a sine-wave
 * input is captured verbatim through the engine's passthrough.
 */
@ExtendWith(HeadlessAudioExtension.class)
class HeadlessAudioHarnessTest {

    private static final AudioFormat FORMAT = new AudioFormat(44_100.0, 1, 16, 128);

    /** Deterministic 440 Hz sine generator (mathematical reference). */
    private static HeadlessAudioBackend.InputGenerator sineGenerator(double freqHz, double sampleRate) {
        return (input, numFrames, framesRendered) -> {
            for (int ch = 0; ch < input.length; ch++) {
                for (int i = 0; i < numFrames; i++) {
                    long n = framesRendered + i;
                    input[ch][i] = (float) Math.sin(2.0 * Math.PI * freqHz * n / sampleRate);
                }
            }
        };
    }

    @Test
    void loadingAndRenderingEmptyProjectProducesSilence() {
        try (var harness = new HeadlessAudioHarness(FORMAT)) {
            var project = new com.benesquivelmusic.daw.core.project.DawProject("empty", FORMAT);
            harness.load(project);

            double[][] audio = harness.renderRange(0, 256);

            assertThat(audio).hasDimensions(1, 256);
            // Empty project (no tracks) should render silence
            for (int i = 0; i < 256; i++) {
                assertThat(audio[0][i]).isEqualTo(0.0);
            }
            // Transport should have been paused after render
            assertThat(project.getTransport().getState())
                    .isEqualTo(com.benesquivelmusic.daw.core.transport.TransportState.PAUSED);
        }
    }

    @Test
    void closeIsIdempotent() {
        var harness = new HeadlessAudioHarness(FORMAT);
        harness.renderRange(0, 128);
        harness.close();
        harness.close(); // should not throw
        assertThat(harness.getEngine().isRunning()).isFalse();
    }

    @Test
    void sineInputIsPassedThroughToOutputMathematically() {
        try (var harness = new HeadlessAudioHarness(FORMAT)) {
            harness.setInputGenerator(sineGenerator(440.0, FORMAT.sampleRate()));
            double[][] audio = harness.renderRange(0, 1024);

            assertThat(audio).hasDimensions(1, 1024);
            // AudioEngine passthrough writes inputBuffer directly to outputBuffer
            // when no transport/mixer/tracks are configured, so the captured
            // output must equal the mathematical sine reference sample-for-sample.
            for (int i = 0; i < 1024; i++) {
                double expected = Math.sin(2.0 * Math.PI * 440.0 * i / FORMAT.sampleRate());
                assertThat(audio[0][i]).as("sample[%d]", i).isCloseTo(expected, within(1e-6));
            }
        }
    }

    @Test
    void rendersAreDeterministicAcrossRuns(HeadlessAudioHarness harness) {
        harness.setInputGenerator(sineGenerator(220.0, FORMAT.sampleRate()));
        double[][] first = harness.renderRange(0, 512);
        double[][] second = harness.renderRange(0, 512);
        assertThat(first).isDeepEqualTo(second);
    }

    @Test
    void harnessReusedViaExtensionCarriesSeededRandom(HeadlessAudioHarness harness) {
        assertThat(harness.getSeed()).isEqualTo(HeadlessAudioHarness.DEFAULT_SEED);
        long first = harness.getRandom().nextLong();
        harness.withSeed(HeadlessAudioHarness.DEFAULT_SEED);
        long second = harness.getRandom().nextLong();
        assertThat(first).isEqualTo(second);
    }

    @Test
    void invalidRangeIsRejected(HeadlessAudioHarness harness) {
        assertThatThrownBy(() -> harness.renderRange(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> harness.renderRange(10, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroLengthRangeReturnsEmptyBuffer(HeadlessAudioHarness harness) {
        double[][] audio = harness.renderRange(100, 100);
        assertThat(audio).isEmpty();
    }

    @Test
    void timeoutIsEnforcedWhenBudgetIsExhausted() {
        try (var harness = new HeadlessAudioHarness(FORMAT)) {
            // Install an input generator that sleeps so each block blows the budget.
            harness.setInputGenerator((input, n, off) -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            harness.setTimeout(Duration.ofMillis(1)); // ridiculously tight

            assertThatThrownBy(() -> harness.renderRange(0, FORMAT.bufferSize() * 4))
                    .isInstanceOf(HeadlessAudioHarness.HeadlessTimeoutException.class);
        }
    }

    @Test
    void playAtSpeedRejectsNonPositiveValues(HeadlessAudioHarness harness) {
        assertThatThrownBy(() -> harness.playAtSpeed(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> harness.playAtSpeed(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> harness.playAtSpeed(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> harness.playAtSpeed(Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        harness.playAtSpeed(100.0);
        assertThat(harness.getSpeedFactor()).isEqualTo(100.0);
    }

    @Test
    void goldenFileRoundTripMatchesWithinTolerance(@TempDir Path tmp) {
        Path golden = tmp.resolve("sine440.dawg");
        try (var harness = new HeadlessAudioHarness(FORMAT)) {
            harness.setInputGenerator(sineGenerator(440.0, FORMAT.sampleRate()));
            double[][] audio = harness.renderRange(0, 512);
            GoldenAudioFile.write(golden, audio);

            // Fresh render should match the golden file bit-for-bit.
            double[][] again = harness.renderRange(0, 512);
            HeadlessAudioHarness.assertRenderMatches(golden, again, -120.0);
        }
    }

    @Test
    void goldenFileMismatchThrowsAssertionError(@TempDir Path tmp) {
        Path golden = tmp.resolve("silence.dawg");
        double[][] silence = new double[1][256];
        GoldenAudioFile.write(golden, silence);

        double[][] ramp = new double[1][256];
        for (int i = 0; i < ramp[0].length; i++) {
            ramp[0][i] = 0.5; // far louder than tolerance
        }
        assertThatThrownBy(() -> HeadlessAudioHarness.assertRenderMatches(golden, ramp, -90.0))
                .isInstanceOf(AssertionError.class);
    }
}
