package com.benesquivelmusic.daw.core.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransientDetectorTest {

    @Test
    void shouldCreateWithDefaults() {
        TransientDetector detector = new TransientDetector(1024);
        assertThat(detector.getBlockSize()).isEqualTo(1024);
        assertThat(detector.getSensitivityThreshold()).isEqualTo(3.0);
        assertThat(detector.getLongTermDecay()).isEqualTo(0.99);
    }

    @Test
    void shouldCreateWithCustomSensitivity() {
        TransientDetector detector = new TransientDetector(2048, 2.5);
        assertThat(detector.getBlockSize()).isEqualTo(2048);
        assertThat(detector.getSensitivityThreshold()).isEqualTo(2.5);
        assertThat(detector.getLongTermDecay()).isEqualTo(0.99);
    }

    @Test
    void shouldCreateWithFullConfiguration() {
        TransientDetector detector = new TransientDetector(512, 4.0, 0.95);
        assertThat(detector.getBlockSize()).isEqualTo(512);
        assertThat(detector.getSensitivityThreshold()).isEqualTo(4.0);
        assertThat(detector.getLongTermDecay()).isEqualTo(0.95);
    }

    @Test
    void shouldRejectNonPowerOfTwoBlockSize() {
        assertThatThrownBy(() -> new TransientDetector(1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectZeroBlockSize() {
        assertThatThrownBy(() -> new TransientDetector(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeBlockSize() {
        assertThatThrownBy(() -> new TransientDetector(-1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectZeroSensitivity() {
        assertThatThrownBy(() -> new TransientDetector(1024, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeSensitivity() {
        assertThatThrownBy(() -> new TransientDetector(1024, -1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidLongTermDecay() {
        assertThatThrownBy(() -> new TransientDetector(1024, 3.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransientDetector(1024, 3.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransientDetector(1024, 3.0, -0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullBlock() {
        TransientDetector detector = new TransientDetector(1024);
        assertThatThrownBy(() -> detector.detect(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectBlockWithWrongLength() {
        TransientDetector detector = new TransientDetector(1024);
        float[] wrongSize = new float[512];
        assertThatThrownBy(() -> detector.detect(wrongSize))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldNotDetectTransientOnFirstBlock() {
        TransientDetector detector = new TransientDetector(1024);
        float[] block = new float[1024];
        // Some non-zero signal
        for (int i = 0; i < block.length; i++) {
            block[i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }

        TransientDetector.Result result = detector.detect(block);

        // First block cannot detect a transient (no history yet)
        assertThat(result.transientDetected()).isFalse();
        assertThat(result.temporalEnergyRatio()).isEqualTo(1.0);
    }

    @Test
    void shouldNotDetectTransientInSilence() {
        TransientDetector detector = new TransientDetector(1024);
        float[] silence = new float[1024];

        // Feed several silent blocks
        for (int i = 0; i < 10; i++) {
            TransientDetector.Result result = detector.detect(silence);
            assertThat(result.transientDetected()).isFalse();
        }
    }

    @Test
    void shouldNotDetectTransientInSteadyTone() {
        TransientDetector detector = new TransientDetector(1024, 3.0);
        double sampleRate = 44100.0;

        // Feed many blocks of a constant sine wave
        for (int blockIdx = 0; blockIdx < 50; blockIdx++) {
            float[] block = new float[1024];
            int offset = blockIdx * 1024;
            for (int i = 0; i < block.length; i++) {
                block[i] = (float) (0.5 * Math.sin(
                        2.0 * Math.PI * 440.0 * (offset + i) / sampleRate));
            }
            TransientDetector.Result result = detector.detect(block);
            // After the initial warm-up, a steady tone should not trigger transients
            if (blockIdx > 5) {
                assertThat(result.transientDetected()).isFalse();
            }
        }
    }

    @Test
    void shouldDetectTransientOnSilenceToBurst() {
        TransientDetector detector = new TransientDetector(1024, 2.0);
        double sampleRate = 44100.0;
        float[] silence = new float[1024];

        // Establish baseline with silent blocks
        for (int i = 0; i < 20; i++) {
            detector.detect(silence);
        }

        // Sudden loud burst
        float[] burst = new float[1024];
        for (int i = 0; i < burst.length; i++) {
            burst[i] = (float) (0.9 * Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate));
        }

        TransientDetector.Result result = detector.detect(burst);

        assertThat(result.transientDetected()).isTrue();
        assertThat(result.temporalEnergyRatio()).isGreaterThan(1.0);
        assertThat(result.spectralFlux()).isGreaterThan(0.0);
    }

    @Test
    void shouldDetectTransientOnSuddenAmplitudeIncrease() {
        TransientDetector detector = new TransientDetector(1024, 2.0);
        double sampleRate = 44100.0;

        // Establish baseline with quiet signal
        for (int blockIdx = 0; blockIdx < 20; blockIdx++) {
            float[] block = new float[1024];
            int offset = blockIdx * 1024;
            for (int i = 0; i < block.length; i++) {
                block[i] = (float) (0.01 * Math.sin(
                        2.0 * Math.PI * 440.0 * (offset + i) / sampleRate));
            }
            detector.detect(block);
        }

        // Sudden loud block
        float[] loud = new float[1024];
        int offset = 20 * 1024;
        for (int i = 0; i < loud.length; i++) {
            loud[i] = (float) (0.95 * Math.sin(
                    2.0 * Math.PI * 440.0 * (offset + i) / sampleRate));
        }

        TransientDetector.Result result = detector.detect(loud);

        assertThat(result.transientDetected()).isTrue();
    }

    @Test
    void shouldReturnPositiveSpectralFluxOnSpectralChange() {
        TransientDetector detector = new TransientDetector(1024, 2.0);
        double sampleRate = 44100.0;

        // Establish baseline with 220 Hz
        for (int blockIdx = 0; blockIdx < 20; blockIdx++) {
            float[] block = new float[1024];
            int offset = blockIdx * 1024;
            for (int i = 0; i < block.length; i++) {
                block[i] = (float) (0.5 * Math.sin(
                        2.0 * Math.PI * 220.0 * (offset + i) / sampleRate));
            }
            detector.detect(block);
        }

        // Switch to a very different frequency (4000 Hz) to create spectral change
        float[] changed = new float[1024];
        int offset = 20 * 1024;
        for (int i = 0; i < changed.length; i++) {
            changed[i] = (float) (0.5 * Math.sin(
                    2.0 * Math.PI * 4000.0 * (offset + i) / sampleRate));
        }

        TransientDetector.Result result = detector.detect(changed);

        assertThat(result.spectralFlux()).isGreaterThan(0.0);
    }

    @Test
    void shouldResetState() {
        TransientDetector detector = new TransientDetector(1024, 2.0);
        double sampleRate = 44100.0;

        // Feed some blocks
        for (int blockIdx = 0; blockIdx < 10; blockIdx++) {
            float[] block = new float[1024];
            int offset = blockIdx * 1024;
            for (int i = 0; i < block.length; i++) {
                block[i] = (float) (0.5 * Math.sin(
                        2.0 * Math.PI * 440.0 * (offset + i) / sampleRate));
            }
            detector.detect(block);
        }

        detector.reset();

        // After reset, first block should behave like initial block
        float[] block = new float[1024];
        for (int i = 0; i < block.length; i++) {
            block[i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate));
        }

        TransientDetector.Result result = detector.detect(block);

        assertThat(result.transientDetected()).isFalse();
        assertThat(result.temporalEnergyRatio()).isEqualTo(1.0);
    }

    @Test
    void resultRecordShouldExposeAllFields() {
        var result = new TransientDetector.Result(true, 5.0, 42.0);
        assertThat(result.transientDetected()).isTrue();
        assertThat(result.temporalEnergyRatio()).isEqualTo(5.0);
        assertThat(result.spectralFlux()).isEqualTo(42.0);
    }

    @Test
    void shouldHandleImpulsiveTransient() {
        TransientDetector detector = new TransientDetector(1024, 2.0);

        // Establish baseline with silence
        float[] silence = new float[1024];
        for (int i = 0; i < 20; i++) {
            detector.detect(silence);
        }

        // Single-sample impulse (like a drum hit or click)
        float[] impulse = new float[1024];
        impulse[0] = 1.0f;

        TransientDetector.Result result = detector.detect(impulse);

        assertThat(result.transientDetected()).isTrue();
    }

    @Test
    void sensitivityShouldAffectDetection() {
        double sampleRate = 44100.0;

        // Create a quiet baseline and a moderately louder block
        float[] quiet = new float[1024];
        for (int i = 0; i < quiet.length; i++) {
            quiet[i] = (float) (0.1 * Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate));
        }
        float[] louder = new float[1024];
        int offset = 20 * 1024;
        for (int i = 0; i < louder.length; i++) {
            louder[i] = (float) (0.4 * Math.sin(
                    2.0 * Math.PI * 440.0 * (offset + i) / sampleRate));
        }

        // High sensitivity (low threshold) — should detect
        TransientDetector sensitive = new TransientDetector(1024, 1.5);
        for (int i = 0; i < 20; i++) {
            sensitive.detect(quiet);
        }
        TransientDetector.Result sensitiveResult = sensitive.detect(louder);

        // Low sensitivity (high threshold) — should not detect the same signal
        TransientDetector insensitive = new TransientDetector(1024, 50.0);
        for (int i = 0; i < 20; i++) {
            insensitive.detect(quiet);
        }
        TransientDetector.Result insensitiveResult = insensitive.detect(louder);

        // The sensitive detector should detect while the insensitive one should not
        assertThat(sensitiveResult.transientDetected()).isTrue();
        assertThat(insensitiveResult.transientDetected()).isFalse();
    }
}
