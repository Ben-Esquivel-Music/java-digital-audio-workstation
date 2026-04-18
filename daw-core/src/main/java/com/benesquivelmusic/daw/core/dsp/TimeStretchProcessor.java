package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.core.audio.StretchQuality;
import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Arrays;
import java.util.Objects;

/**
 * Phase-vocoder-based time-stretch processor.
 *
 * <p>Changes the duration of audio without affecting its pitch by
 * decomposing the signal into overlapping windowed frames via the
 * Short-Time Fourier Transform (STFT), resynthesizing with adjusted
 * hop sizes, and overlap-adding the result.</p>
 *
 * <p>The stretch ratio controls the time scaling factor:
 * <ul>
 *   <li>{@code 1.0} – no change (passthrough)</li>
 *   <li>{@code 2.0} – double the duration (half speed)</li>
 *   <li>{@code 0.5} – halve the duration (double speed)</li>
 * </ul>
 *
 * <p>The {@link StretchQuality} setting controls the FFT size and overlap
 * factor, trading off between processing cost and audio quality.</p>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class TimeStretchProcessor implements AudioProcessor {

    private final int channels;
    private final double sampleRate;
    private double stretchRatio;
    private StretchQuality quality;

    private int fftSize;
    private int hopAnalysis;
    private int hopSynthesis;
    private int overlapFactor;

    // Per-channel STFT state
    private float[][] inputAccumulator;
    private float[][] outputAccumulator;
    private int[] inputAccumulatorPos;
    private int[] outputAccumulatorPos;
    private double[][] lastPhase;
    private double[][] synthPhase;
    private double[] window;

    /**
     * Creates a time-stretch processor with default settings.
     *
     * @param channels   number of audio channels (must be positive)
     * @param sampleRate the sample rate in Hz (must be positive)
     */
    public TimeStretchProcessor(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.stretchRatio = 1.0;
        this.quality = StretchQuality.MEDIUM;
        initializeBuffers();
    }

    private void initializeBuffers() {
        overlapFactor = switch (quality) {
            case LOW -> 2;
            case MEDIUM -> 4;
            case HIGH -> 8;
        };

        fftSize = switch (quality) {
            case LOW -> 1024;
            case MEDIUM -> 2048;
            case HIGH -> 4096;
        };

        hopAnalysis = fftSize / overlapFactor;
        hopSynthesis = Math.max(1, (int) Math.round(hopAnalysis * stretchRatio));

        int halfSpectrum = fftSize / 2 + 1;
        int accumSize = fftSize * 4;

        inputAccumulator = new float[channels][accumSize];
        outputAccumulator = new float[channels][accumSize];
        inputAccumulatorPos = new int[channels];
        outputAccumulatorPos = new int[channels];
        lastPhase = new double[channels][halfSpectrum];
        synthPhase = new double[channels][halfSpectrum];

        window = new double[fftSize];
        for (int i = 0; i < fftSize; i++) {
            window[i] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / fftSize));
        }
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        if (Math.abs(stretchRatio - 1.0) < 1e-6) {
            // Passthrough when no stretching is applied
            int activeCh = Math.min(channels, inputBuffer.length);
            for (int ch = 0; ch < activeCh; ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
            return;
        }

        int activeCh = Math.min(channels, inputBuffer.length);
        for (int ch = 0; ch < activeCh; ch++) {
            processChannel(inputBuffer[ch], outputBuffer[ch], numFrames, ch);
        }
    }

    private void processChannel(float[] input, float[] output, int numFrames, int ch) {
        int halfSpectrum = fftSize / 2 + 1;
        double expectedPhaseDiff = 2.0 * Math.PI * hopAnalysis / fftSize;
        int accumSize = inputAccumulator[ch].length;

        // Feed input samples into the accumulator
        for (int i = 0; i < numFrames; i++) {
            inputAccumulator[ch][inputAccumulatorPos[ch] % accumSize] = input[i];
            inputAccumulatorPos[ch]++;
        }

        // Process available frames
        int outputWritten = 0;
        while (outputWritten < numFrames && inputAccumulatorPos[ch] >= fftSize) {
            // Extract windowed frame from input accumulator
            double[] frame = new double[fftSize];
            int startPos = inputAccumulatorPos[ch] - fftSize;
            for (int i = 0; i < fftSize; i++) {
                frame[i] = inputAccumulator[ch][(startPos + i) % accumSize] * window[i];
            }

            // Simple DFT analysis (real-valued optimization for magnitude/phase)
            double[] magnitudes = new double[halfSpectrum];
            double[] phases = new double[halfSpectrum];
            for (int k = 0; k < halfSpectrum; k++) {
                double real = 0.0;
                double imag = 0.0;
                for (int n = 0; n < fftSize; n++) {
                    double angle = -2.0 * Math.PI * k * n / fftSize;
                    real += frame[n] * Math.cos(angle);
                    imag += frame[n] * Math.sin(angle);
                }
                magnitudes[k] = Math.sqrt(real * real + imag * imag);
                phases[k] = Math.atan2(imag, real);
            }

            // Phase vocoder: compute instantaneous frequency and advance synthesis phase
            double[] synthFrame = new double[fftSize];
            for (int k = 0; k < halfSpectrum; k++) {
                double phaseDiff = phases[k] - lastPhase[ch][k];
                lastPhase[ch][k] = phases[k];

                // Remove expected phase advance
                phaseDiff -= k * expectedPhaseDiff;

                // Wrap to [-π, π]
                phaseDiff = phaseDiff - 2.0 * Math.PI * Math.round(phaseDiff / (2.0 * Math.PI));

                // True frequency deviation
                double trueFreq = k * expectedPhaseDiff + phaseDiff;

                // Advance synthesis phase by the synthesis hop
                synthPhase[ch][k] += trueFreq * ((double) hopSynthesis / hopAnalysis);

                // Inverse DFT contribution for this bin
                for (int n = 0; n < fftSize; n++) {
                    double angle = 2.0 * Math.PI * k * n / fftSize;
                    double contribution = magnitudes[k] * Math.cos(angle + synthPhase[ch][k]);
                    synthFrame[n] += (k == 0 || k == halfSpectrum - 1)
                            ? contribution
                            : 2.0 * contribution;
                }
            }

            // Normalize and window the synthesis frame
            double normFactor = 1.0 / fftSize;
            for (int i = 0; i < fftSize; i++) {
                synthFrame[i] *= normFactor * window[i];
            }

            // Overlap-add to output accumulator
            for (int i = 0; i < fftSize; i++) {
                int pos = (outputAccumulatorPos[ch] + i) % accumSize;
                outputAccumulator[ch][pos] += (float) synthFrame[i];
            }

            // Read output samples
            int samplesToRead = Math.min(hopSynthesis, numFrames - outputWritten);
            for (int i = 0; i < samplesToRead; i++) {
                output[outputWritten + i] = outputAccumulator[ch][outputAccumulatorPos[ch] % accumSize];
                outputAccumulator[ch][outputAccumulatorPos[ch] % accumSize] = 0.0f;
                outputAccumulatorPos[ch]++;
            }
            outputWritten += samplesToRead;

            // Advance input by analysis hop
            inputAccumulatorPos[ch] -= hopAnalysis;
            // Shift remaining input data
            if (inputAccumulatorPos[ch] > 0) {
                int remaining = inputAccumulatorPos[ch];
                float[] temp = new float[remaining];
                int srcStart = (startPos + hopAnalysis) % accumSize;
                for (int i = 0; i < remaining; i++) {
                    temp[i] = inputAccumulator[ch][(srcStart + i) % accumSize];
                }
                Arrays.fill(inputAccumulator[ch], 0.0f);
                System.arraycopy(temp, 0, inputAccumulator[ch], 0, remaining);
            }
        }

        // Zero-fill any remaining output
        for (int i = outputWritten; i < numFrames; i++) {
            output[i] = 0.0f;
        }
    }

    /** Returns the current stretch ratio. */
    @ProcessorParam(id = 0, name = "Stretch Ratio", min = 0.25, max = 4.0, defaultValue = 1.0)
    public double getStretchRatio() {
        return stretchRatio;
    }

    /**
     * Sets the time-stretch ratio.
     *
     * @param stretchRatio the ratio (must be between 0.25 and 4.0 inclusive)
     */
    public void setStretchRatio(double stretchRatio) {
        if (stretchRatio < 0.25 || stretchRatio > 4.0) {
            throw new IllegalArgumentException(
                    "stretchRatio must be in [0.25, 4.0]: " + stretchRatio);
        }
        this.stretchRatio = stretchRatio;
        initializeBuffers();
    }

    /** Returns the current quality setting. */
    public StretchQuality getQuality() {
        return quality;
    }

    /**
     * Sets the quality setting and reinitializes internal buffers.
     *
     * @param quality the quality setting (must not be {@code null})
     */
    public void setQuality(StretchQuality quality) {
        this.quality = Objects.requireNonNull(quality, "quality must not be null");
        initializeBuffers();
    }

    /** Returns the FFT size used by this processor. */
    public int getFftSize() {
        return fftSize;
    }

    @Override
    public void reset() {
        for (int ch = 0; ch < channels; ch++) {
            Arrays.fill(inputAccumulator[ch], 0.0f);
            Arrays.fill(outputAccumulator[ch], 0.0f);
            Arrays.fill(lastPhase[ch], 0.0);
            Arrays.fill(synthPhase[ch], 0.0);
            inputAccumulatorPos[ch] = 0;
            outputAccumulatorPos[ch] = 0;
        }
    }

    @Override
    public int getInputChannelCount() {
        return channels;
    }

    @Override
    public int getOutputChannelCount() {
        return channels;
    }
}
