package com.benesquivelmusic.daw.core.spatial.binaural;

import com.benesquivelmusic.daw.sdk.spatial.HrtfData;
import com.benesquivelmusic.daw.sdk.spatial.MonitoringMode;
import com.benesquivelmusic.daw.sdk.spatial.SphericalCoordinate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class DefaultBinauralRendererTest {

    private static final int BLOCK_SIZE = 64;
    private static final double SAMPLE_RATE = 44100.0;
    private DefaultBinauralRenderer renderer;
    private HrtfData hrtfData;

    @BeforeEach
    void setUp() {
        renderer = new DefaultBinauralRenderer(SAMPLE_RATE, BLOCK_SIZE);

        // Create HRTF data with distinct left/right signatures per direction
        List<SphericalCoordinate> positions = List.of(
                new SphericalCoordinate(0, 0, 1.0),    // front
                new SphericalCoordinate(90, 0, 1.0),   // left
                new SphericalCoordinate(180, 0, 1.0),  // back
                new SphericalCoordinate(270, 0, 1.0)   // right
        );

        float[][][] ir = new float[4][2][BLOCK_SIZE];
        float[][] delays = new float[4][2];

        // Front: louder left, quieter right (slight left bias)
        ir[0][0][0] = 0.9f;
        ir[0][1][0] = 0.8f;

        // Left: much louder left, quieter right
        ir[1][0][0] = 1.0f;
        ir[1][1][0] = 0.3f;

        // Back: equal both ears
        ir[2][0][0] = 0.5f;
        ir[2][1][0] = 0.5f;

        // Right: quieter left, much louder right
        ir[3][0][0] = 0.3f;
        ir[3][1][0] = 1.0f;

        // ITD delays (in samples)
        delays[0] = new float[]{0, 0};      // front: no ITD
        delays[1] = new float[]{0, 5};      // left: right ear delayed
        delays[2] = new float[]{0, 0};      // back: no ITD
        delays[3] = new float[]{5, 0};      // right: left ear delayed

        hrtfData = new HrtfData("TestHRTF", SAMPLE_RATE, positions, ir, delays);
    }

    @Test
    void shouldPassThroughInSpeakerMode() {
        renderer.setMonitoringMode(MonitoringMode.SPEAKER);
        renderer.loadHrtfData(hrtfData);

        float[][] input = {{0.5f, 0.3f, -0.2f}, {-0.1f, 0.7f, 0.4f}};
        float[][] output = new float[2][3];
        renderer.process(input, output, 3);

        assertThat(output[0]).containsExactly(input[0]);
        assertThat(output[1]).containsExactly(input[1]);
    }

    @Test
    void shouldProduceStereoOutputInBinauralMode() {
        renderer.loadHrtfData(hrtfData);
        renderer.setSourcePosition(new SphericalCoordinate(0, 0, 1.0));

        // Prime with zeros, then process actual signal
        float[][] zeroInput = {new float[BLOCK_SIZE]};
        float[][] zeroOutput = new float[2][BLOCK_SIZE];
        renderer.process(zeroInput, zeroOutput, BLOCK_SIZE);

        float[] monoInput = new float[BLOCK_SIZE];
        monoInput[0] = 1.0f; // impulse
        float[][] input = {monoInput};
        float[][] output = new float[2][BLOCK_SIZE];
        renderer.process(input, output, BLOCK_SIZE);

        // Both channels should have non-zero content
        boolean leftHasSignal = false;
        boolean rightHasSignal = false;
        for (int i = 0; i < BLOCK_SIZE; i++) {
            if (Math.abs(output[0][i]) > 1e-6f) leftHasSignal = true;
            if (Math.abs(output[1][i]) > 1e-6f) rightHasSignal = true;
        }
        assertThat(leftHasSignal).isTrue();
        assertThat(rightHasSignal).isTrue();
    }

    @Test
    void shouldDifferentiateBetweenLeftAndRightForLateralSource() {
        renderer.loadHrtfData(hrtfData);
        renderer.setSourcePosition(new SphericalCoordinate(90, 0, 1.0)); // left

        // Prime
        float[][] zeroInput = {new float[BLOCK_SIZE]};
        float[][] zeroOutput = new float[2][BLOCK_SIZE];
        renderer.process(zeroInput, zeroOutput, BLOCK_SIZE);

        // Process impulse
        float[] monoInput = new float[BLOCK_SIZE];
        monoInput[0] = 1.0f;
        float[][] input = {monoInput};
        float[][] output = new float[2][BLOCK_SIZE];
        renderer.process(input, output, BLOCK_SIZE);

        // For a left source, left channel should be louder than right
        double leftPeak = 0, rightPeak = 0;
        for (int i = 0; i < BLOCK_SIZE; i++) {
            leftPeak = Math.max(leftPeak, Math.abs(output[0][i]));
            rightPeak = Math.max(rightPeak, Math.abs(output[1][i]));
        }
        assertThat(leftPeak).isGreaterThan(rightPeak);
    }

    @Test
    void shouldStartInBinauralMode() {
        assertThat(renderer.getMonitoringMode()).isEqualTo(MonitoringMode.BINAURAL);
    }

    @Test
    void shouldSwitchMonitoringMode() {
        renderer.setMonitoringMode(MonitoringMode.SPEAKER);
        assertThat(renderer.getMonitoringMode()).isEqualTo(MonitoringMode.SPEAKER);

        renderer.setMonitoringMode(MonitoringMode.BINAURAL);
        assertThat(renderer.getMonitoringMode()).isEqualTo(MonitoringMode.BINAURAL);
    }

    @Test
    void shouldUpdateSourcePosition() {
        var pos = new SphericalCoordinate(45, 10, 1.5);
        renderer.loadHrtfData(hrtfData);
        renderer.setSourcePosition(pos);
        assertThat(renderer.getSourcePosition()).isEqualTo(pos);
    }

    @Test
    void shouldSetCrossfadeDuration() {
        renderer.setCrossfadeDurationMs(50.0);
        // No exception
    }

    @Test
    void shouldRejectNegativeCrossfadeDuration() {
        assertThatThrownBy(() -> renderer.setCrossfadeDurationMs(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullMonitoringMode() {
        assertThatThrownBy(() -> renderer.setMonitoringMode(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullSourcePosition() {
        assertThatThrownBy(() -> renderer.setSourcePosition(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullHrtfData() {
        assertThatThrownBy(() -> renderer.loadHrtfData(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReturnCorrectChannelCounts() {
        assertThat(renderer.getInputChannelCount()).isEqualTo(1);
        assertThat(renderer.getOutputChannelCount()).isEqualTo(2);
    }

    @Test
    void shouldRejectInvalidConstructorArgs() {
        assertThatThrownBy(() -> new DefaultBinauralRenderer(0, BLOCK_SIZE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefaultBinauralRenderer(SAMPLE_RATE, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldResetWithoutError() {
        renderer.loadHrtfData(hrtfData);
        renderer.reset();
        // Process after reset should work
        float[][] input = {new float[BLOCK_SIZE]};
        float[][] output = new float[2][BLOCK_SIZE];
        renderer.process(input, output, BLOCK_SIZE);
    }

    @Test
    void shouldHandlePassThroughWithoutHrtfLoaded() {
        // No HRTF loaded — should pass through without errors
        float[][] input = {new float[BLOCK_SIZE], new float[BLOCK_SIZE]};
        float[][] output = new float[2][BLOCK_SIZE];
        input[0][0] = 0.5f;
        input[1][0] = 0.3f;
        renderer.process(input, output, BLOCK_SIZE);

        assertThat(output[0][0]).isCloseTo(0.5f, within(1e-6f));
        assertThat(output[1][0]).isCloseTo(0.3f, within(1e-6f));
    }
}
