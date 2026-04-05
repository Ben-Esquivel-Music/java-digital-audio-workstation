package com.benesquivelmusic.daw.core.spatial.binaural;

import com.benesquivelmusic.daw.sdk.spatial.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class BinauralMonitorControllerTest {

    private static final int BLOCK_SIZE = 64;
    private static final double SAMPLE_RATE = 44100.0;
    private BinauralMonitorController controller;

    @BeforeEach
    void setUp() {
        controller = new BinauralMonitorController(SAMPLE_RATE, BLOCK_SIZE);
    }

    // ---- Constructor validation ---------------------------------------------

    @Test
    void shouldRejectInvalidSampleRate() {
        assertThatThrownBy(() -> new BinauralMonitorController(0, BLOCK_SIZE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BinauralMonitorController(-1, BLOCK_SIZE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidBlockSize() {
        assertThatThrownBy(() -> new BinauralMonitorController(SAMPLE_RATE, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BinauralMonitorController(SAMPLE_RATE, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Default state ------------------------------------------------------

    @Test
    void shouldDefaultToSpeakerMode() {
        assertThat(controller.getMonitoringMode()).isEqualTo(MonitoringMode.SPEAKER);
    }

    @Test
    void shouldDefaultToSpeakersDisplayName() {
        assertThat(controller.getMonitoringModeDisplayName()).isEqualTo("Speakers");
    }

    @Test
    void shouldDefaultToNoActiveProfile() {
        assertThat(controller.getActiveProfile()).isNull();
    }

    @Test
    void shouldDefaultToNoActiveHrtfData() {
        assertThat(controller.getActiveHrtfData()).isNull();
    }

    @Test
    void shouldDefaultToExternalizationDisabled() {
        assertThat(controller.isExternalizationEnabled()).isFalse();
    }

    @Test
    void shouldDefaultToNoFoldDownTarget() {
        assertThat(controller.getFoldDownTarget()).isNull();
    }

    @Test
    void shouldDefaultToNoCustomHrtf() {
        assertThat(controller.isCustomHrtfActive()).isFalse();
        assertThat(controller.getCustomHrtfName()).isNull();
    }

    // ---- Monitoring mode (A/B switching) ------------------------------------

    @Test
    void shouldSetMonitoringMode() {
        controller.setMonitoringMode(MonitoringMode.BINAURAL);
        assertThat(controller.getMonitoringMode()).isEqualTo(MonitoringMode.BINAURAL);

        controller.setMonitoringMode(MonitoringMode.SPEAKER);
        assertThat(controller.getMonitoringMode()).isEqualTo(MonitoringMode.SPEAKER);
    }

    @Test
    void shouldToggleMonitoringMode() {
        assertThat(controller.getMonitoringMode()).isEqualTo(MonitoringMode.SPEAKER);

        controller.toggleMonitoringMode();
        assertThat(controller.getMonitoringMode()).isEqualTo(MonitoringMode.BINAURAL);

        controller.toggleMonitoringMode();
        assertThat(controller.getMonitoringMode()).isEqualTo(MonitoringMode.SPEAKER);
    }

    @Test
    void shouldRejectNullMonitoringMode() {
        assertThatThrownBy(() -> controller.setMonitoringMode(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldDisplayBinauralModeLabel() {
        controller.setMonitoringMode(MonitoringMode.BINAURAL);
        assertThat(controller.getMonitoringModeDisplayName()).isEqualTo("Binaural");
    }

    @Test
    void shouldDisplaySpeakerModeLabel() {
        controller.setMonitoringMode(MonitoringMode.SPEAKER);
        assertThat(controller.getMonitoringModeDisplayName()).isEqualTo("Speakers");
    }

    // ---- HRTF profile selection ---------------------------------------------

    @Test
    void shouldSelectBuiltInProfile() {
        controller.selectProfile(HrtfProfile.MEDIUM);
        assertThat(controller.getActiveProfile()).isEqualTo(HrtfProfile.MEDIUM);
        assertThat(controller.getActiveHrtfData()).isNotNull();
        assertThat(controller.isCustomHrtfActive()).isFalse();
    }

    @Test
    void shouldSelectSmallProfile() {
        controller.selectProfile(HrtfProfile.SMALL);
        assertThat(controller.getActiveProfile()).isEqualTo(HrtfProfile.SMALL);
        assertThat(controller.getActiveHrtfData()).isNotNull();
        assertThat(controller.getActiveHrtfData().profileName()).contains("Small");
    }

    @Test
    void shouldSelectLargeProfile() {
        controller.selectProfile(HrtfProfile.LARGE);
        assertThat(controller.getActiveProfile()).isEqualTo(HrtfProfile.LARGE);
        assertThat(controller.getActiveHrtfData()).isNotNull();
        assertThat(controller.getActiveHrtfData().profileName()).contains("Large");
    }

    @Test
    void shouldGenerateDistinctHrtfDataPerProfile() {
        controller.selectProfile(HrtfProfile.SMALL);
        HrtfData smallData = controller.getActiveHrtfData();

        controller.selectProfile(HrtfProfile.LARGE);
        HrtfData largeData = controller.getActiveHrtfData();

        // Different profiles should produce different profile names
        assertThat(smallData.profileName()).isNotEqualTo(largeData.profileName());
    }

    @Test
    void shouldRejectNullProfile() {
        assertThatThrownBy(() -> controller.selectProfile(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReturnAllAvailableProfiles() {
        List<HrtfProfile> profiles = controller.getAvailableProfiles();
        assertThat(profiles).containsExactly(HrtfProfile.SMALL, HrtfProfile.MEDIUM, HrtfProfile.LARGE);
    }

    @Test
    void shouldClearCustomHrtfWhenSelectingProfile() {
        // Load custom HRTF first
        HrtfData customData = createTestHrtfData("CustomHRTF");
        controller.loadCustomHrtfData(customData);
        assertThat(controller.isCustomHrtfActive()).isTrue();

        // Select built-in profile
        controller.selectProfile(HrtfProfile.MEDIUM);
        assertThat(controller.isCustomHrtfActive()).isFalse();
        assertThat(controller.getCustomHrtfName()).isNull();
        assertThat(controller.getActiveProfile()).isEqualTo(HrtfProfile.MEDIUM);
    }

    // ---- Custom HRTF import -------------------------------------------------

    @Test
    void shouldLoadCustomHrtfData() {
        HrtfData customData = createTestHrtfData("MyCustomHRTF");
        controller.loadCustomHrtfData(customData);

        assertThat(controller.isCustomHrtfActive()).isTrue();
        assertThat(controller.getCustomHrtfName()).isEqualTo("MyCustomHRTF");
        assertThat(controller.getActiveHrtfData()).isSameAs(customData);
        assertThat(controller.getActiveProfile()).isNull();
    }

    @Test
    void shouldClearProfileWhenLoadingCustomHrtf() {
        controller.selectProfile(HrtfProfile.MEDIUM);
        assertThat(controller.getActiveProfile()).isEqualTo(HrtfProfile.MEDIUM);

        HrtfData customData = createTestHrtfData("CustomHRTF");
        controller.loadCustomHrtfData(customData);
        assertThat(controller.getActiveProfile()).isNull();
        assertThat(controller.isCustomHrtfActive()).isTrue();
    }

    @Test
    void shouldRejectNullCustomHrtfData() {
        assertThatThrownBy(() -> controller.loadCustomHrtfData(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- Externalization processor ------------------------------------------

    @Test
    void shouldEnableExternalization() {
        controller.setExternalizationEnabled(true);
        assertThat(controller.isExternalizationEnabled()).isTrue();
    }

    @Test
    void shouldDisableExternalization() {
        controller.setExternalizationEnabled(true);
        controller.setExternalizationEnabled(false);
        assertThat(controller.isExternalizationEnabled()).isFalse();
    }

    // ---- Fold-down monitoring -----------------------------------------------

    @Test
    void shouldSetFoldDownTarget() {
        controller.setFoldDownTarget(SpeakerLayout.LAYOUT_STEREO);
        assertThat(controller.getFoldDownTarget()).isEqualTo(SpeakerLayout.LAYOUT_STEREO);
    }

    @Test
    void shouldClearFoldDownTarget() {
        controller.setFoldDownTarget(SpeakerLayout.LAYOUT_STEREO);
        controller.setFoldDownTarget(null);
        assertThat(controller.getFoldDownTarget()).isNull();
    }

    @Test
    void shouldSetFoldDownTo51() {
        controller.setFoldDownTarget(SpeakerLayout.LAYOUT_5_1);
        assertThat(controller.getFoldDownTarget()).isEqualTo(SpeakerLayout.LAYOUT_5_1);
    }

    // ---- Audio processing ---------------------------------------------------

    @Test
    void shouldPassThroughInSpeakerMode() {
        float[][] input = new float[2][BLOCK_SIZE];
        float[][] output = new float[2][BLOCK_SIZE];
        input[0][0] = 0.5f;
        input[1][0] = 0.3f;

        controller.process(input, output, BLOCK_SIZE);

        assertThat(output[0][0]).isCloseTo(0.5f, within(1e-6f));
        assertThat(output[1][0]).isCloseTo(0.3f, within(1e-6f));
    }

    @Test
    void shouldProcessInBinauralModeWithProfile() {
        controller.selectProfile(HrtfProfile.MEDIUM);
        controller.setMonitoringMode(MonitoringMode.BINAURAL);

        // Prime with zeros
        float[][] zeroInput = new float[2][BLOCK_SIZE];
        float[][] zeroOutput = new float[2][BLOCK_SIZE];
        controller.process(zeroInput, zeroOutput, BLOCK_SIZE);

        // Process impulse
        float[][] input = new float[2][BLOCK_SIZE];
        float[][] output = new float[2][BLOCK_SIZE];
        input[0][0] = 1.0f;
        input[1][0] = 1.0f;

        controller.process(input, output, BLOCK_SIZE);

        // Should produce binaural output (non-zero on both channels)
        boolean hasLeftSignal = false;
        boolean hasRightSignal = false;
        for (int i = 0; i < BLOCK_SIZE; i++) {
            if (Math.abs(output[0][i]) > 1e-6f) hasLeftSignal = true;
            if (Math.abs(output[1][i]) > 1e-6f) hasRightSignal = true;
        }
        assertThat(hasLeftSignal).isTrue();
        assertThat(hasRightSignal).isTrue();
    }

    @Test
    void shouldPassThroughInBinauralModeWithoutHrtf() {
        controller.setMonitoringMode(MonitoringMode.BINAURAL);

        float[][] input = new float[2][BLOCK_SIZE];
        float[][] output = new float[2][BLOCK_SIZE];
        input[0][0] = 0.5f;
        input[1][0] = 0.3f;

        controller.process(input, output, BLOCK_SIZE);

        // Without HRTF data loaded, binaural renderer passes through
        assertThat(output[0][0]).isCloseTo(0.5f, within(1e-6f));
        assertThat(output[1][0]).isCloseTo(0.3f, within(1e-6f));
    }

    @Test
    void shouldApplyFoldDownInSpeakerMode() {
        controller.setFoldDownTarget(SpeakerLayout.LAYOUT_STEREO);

        // Create 6-channel 5.1 input
        float[][] input = new float[6][BLOCK_SIZE];
        input[0][0] = 1.0f; // L
        input[1][0] = 1.0f; // R
        input[2][0] = 1.0f; // C

        float[][] output = new float[2][BLOCK_SIZE];
        controller.process(input, output, BLOCK_SIZE);

        // Output should be stereo fold-down of 5.1
        // L = L + 0.707*C = 1.0 + 0.707 ≈ 1.707
        assertThat((double) output[0][0]).isCloseTo(1.0 + Math.sqrt(0.5), within(0.01));
    }

    @Test
    void shouldNotFoldDownWhenInputMatchesTarget() {
        controller.setFoldDownTarget(SpeakerLayout.LAYOUT_STEREO);

        float[][] input = new float[2][BLOCK_SIZE];
        input[0][0] = 0.5f;
        input[1][0] = 0.3f;

        float[][] output = new float[2][BLOCK_SIZE];
        controller.process(input, output, BLOCK_SIZE);

        // Stereo input should pass through unchanged (already matches target)
        assertThat(output[0][0]).isCloseTo(0.5f, within(1e-6f));
        assertThat(output[1][0]).isCloseTo(0.3f, within(1e-6f));
    }

    @Test
    void shouldProcessWithExternalizationEnabled() {
        controller.selectProfile(HrtfProfile.MEDIUM);
        controller.setMonitoringMode(MonitoringMode.BINAURAL);
        controller.setExternalizationEnabled(true);

        float[][] input = new float[2][BLOCK_SIZE];
        float[][] output = new float[2][BLOCK_SIZE];
        input[0][0] = 0.5f;
        input[1][0] = 0.3f;

        // Should not throw
        controller.process(input, output, BLOCK_SIZE);
    }

    @Test
    void shouldResetWithoutError() {
        controller.selectProfile(HrtfProfile.MEDIUM);
        controller.setMonitoringMode(MonitoringMode.BINAURAL);
        controller.setExternalizationEnabled(true);

        controller.reset();

        // Should process cleanly after reset
        float[][] input = new float[2][BLOCK_SIZE];
        float[][] output = new float[2][BLOCK_SIZE];
        controller.process(input, output, BLOCK_SIZE);
    }

    // ---- Helpers ------------------------------------------------------------

    private HrtfData createTestHrtfData(String profileName) {
        List<SphericalCoordinate> positions = List.of(
                new SphericalCoordinate(0, 0, 1.0),
                new SphericalCoordinate(90, 0, 1.0),
                new SphericalCoordinate(180, 0, 1.0),
                new SphericalCoordinate(270, 0, 1.0)
        );

        float[][][] ir = new float[4][2][BLOCK_SIZE];
        ir[0][0][0] = 0.9f;
        ir[0][1][0] = 0.8f;
        ir[1][0][0] = 1.0f;
        ir[1][1][0] = 0.3f;
        ir[2][0][0] = 0.5f;
        ir[2][1][0] = 0.5f;
        ir[3][0][0] = 0.3f;
        ir[3][1][0] = 1.0f;

        float[][] delays = new float[4][2];
        delays[1][1] = 5;
        delays[3][0] = 5;

        return new HrtfData(profileName, SAMPLE_RATE, positions, ir, delays);
    }
}
