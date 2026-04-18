package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.core.audio.StretchQuality;
import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Arrays;
import java.util.Objects;

/**
 * Phase-vocoder-based pitch-shift processor.
 *
 * <p>Changes the pitch of audio without affecting its duration. Internally,
 * this works by time-stretching the audio by the inverse of the desired
 * pitch ratio and then resampling to restore the original duration.</p>
 *
 * <p>Pitch shift is specified in semitones (fractional values represent
 * cent adjustments):
 * <ul>
 *   <li>{@code 0.0} – no change (passthrough)</li>
 *   <li>{@code 12.0} – one octave up</li>
 *   <li>{@code -12.0} – one octave down</li>
 *   <li>{@code 0.5} – 50 cents up</li>
 * </ul>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class PitchShiftProcessor implements AudioProcessor {

    private final int channels;
    private final double sampleRate;
    private double pitchShiftSemitones;
    private StretchQuality quality;

    private int fftSize;
    private int overlapFactor;
    private int hopAnalysis;

    // Per-channel state
    private double[][] lastPhase;
    private double[][] synthPhase;
    private double[] window;
    private float[][] inputRing;
    private float[][] outputRing;
    private int[] inputWritePos;
    private double[] resamplePos;

    /**
     * Creates a pitch-shift processor with default settings.
     *
     * @param channels   number of audio channels (must be positive)
     * @param sampleRate the sample rate in Hz (must be positive)
     */
    public PitchShiftProcessor(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.pitchShiftSemitones = 0.0;
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

        int halfSpectrum = fftSize / 2 + 1;
        int ringSize = fftSize * 4;

        lastPhase = new double[channels][halfSpectrum];
        synthPhase = new double[channels][halfSpectrum];
        inputRing = new float[channels][ringSize];
        outputRing = new float[channels][ringSize];
        inputWritePos = new int[channels];
        resamplePos = new double[channels];

        window = new double[fftSize];
        for (int i = 0; i < fftSize; i++) {
            window[i] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / fftSize));
        }
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        if (Math.abs(pitchShiftSemitones) < 1e-6) {
            // Passthrough when no pitch shift is applied
            int activeCh = Math.min(channels, inputBuffer.length);
            for (int ch = 0; ch < activeCh; ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
            return;
        }

        double pitchRatio = Math.pow(2.0, pitchShiftSemitones / 12.0);
        int activeCh = Math.min(channels, inputBuffer.length);
        for (int ch = 0; ch < activeCh; ch++) {
            processChannel(inputBuffer[ch], outputBuffer[ch], numFrames, ch, pitchRatio);
        }
    }

    private void processChannel(float[] input, float[] output, int numFrames,
                                int ch, double pitchRatio) {
        int halfSpectrum = fftSize / 2 + 1;
        int ringSize = inputRing[ch].length;
        double expectedPhaseDiff = 2.0 * Math.PI * hopAnalysis / fftSize;

        // The pitch-shift approach: analyze at hopAnalysis, resynthesize at
        // hopSynthesis = hopAnalysis / pitchRatio, then resample output by pitchRatio
        int hopSynthesis = Math.max(1, (int) Math.round(hopAnalysis / pitchRatio));

        // Feed input into ring buffer
        for (int i = 0; i < numFrames; i++) {
            inputRing[ch][inputWritePos[ch] % ringSize] = input[i];
            inputWritePos[ch]++;
        }

        // Process frames while we have enough input
        int framesAvailable = inputWritePos[ch];
        int processedFrames = 0;

        while (framesAvailable >= fftSize && processedFrames < numFrames) {
            // Extract windowed frame
            double[] frame = new double[fftSize];
            int startIdx = inputWritePos[ch] - framesAvailable;
            for (int i = 0; i < fftSize; i++) {
                frame[i] = inputRing[ch][(startIdx + i) % ringSize] * window[i];
            }

            // DFT analysis
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

            // Phase vocoder with pitch-shifted resynthesis
            double[] synthFrame = new double[fftSize];
            for (int k = 0; k < halfSpectrum; k++) {
                double phaseDiff = phases[k] - lastPhase[ch][k];
                lastPhase[ch][k] = phases[k];

                phaseDiff -= k * expectedPhaseDiff;
                phaseDiff = phaseDiff - 2.0 * Math.PI * Math.round(phaseDiff / (2.0 * Math.PI));
                double trueFreq = k * expectedPhaseDiff + phaseDiff;

                synthPhase[ch][k] += trueFreq * ((double) hopSynthesis / hopAnalysis);

                for (int n = 0; n < fftSize; n++) {
                    double angle = 2.0 * Math.PI * k * n / fftSize;
                    double contribution = magnitudes[k] * Math.cos(angle + synthPhase[ch][k]);
                    synthFrame[n] += (k == 0 || k == halfSpectrum - 1)
                            ? contribution
                            : 2.0 * contribution;
                }
            }

            // Normalize, window, and overlap-add
            double normFactor = 1.0 / fftSize;
            for (int i = 0; i < fftSize; i++) {
                int pos = (processedFrames + i) % ringSize;
                outputRing[ch][pos] += (float) (synthFrame[i] * normFactor * window[i]);
            }

            processedFrames += hopSynthesis;
            framesAvailable -= hopAnalysis;
        }

        // Resample from the output ring into the final output buffer
        for (int i = 0; i < numFrames; i++) {
            int idx = (int) resamplePos[ch];
            double frac = resamplePos[ch] - idx;
            int pos0 = idx % ringSize;
            int pos1 = (idx + 1) % ringSize;
            output[i] = (float) (outputRing[ch][pos0] * (1.0 - frac)
                    + outputRing[ch][pos1] * frac);
            outputRing[ch][pos0] = 0.0f;
            resamplePos[ch] += pitchRatio;
        }

        // Shift input write position to keep accumulator bounded
        if (inputWritePos[ch] > ringSize * 2) {
            inputWritePos[ch] = ringSize;
        }
    }

    /** Returns the current pitch shift in semitones. */
    @ProcessorParam(id = 0, name = "Pitch Shift", min = -24.0, max = 24.0, defaultValue = 0.0, unit = "semitones")
    public double getPitchShiftSemitones() {
        return pitchShiftSemitones;
    }

    /**
     * Sets the pitch shift in semitones.
     *
     * @param pitchShiftSemitones the shift amount (must be between -24 and 24 inclusive)
     */
    public void setPitchShiftSemitones(double pitchShiftSemitones) {
        if (pitchShiftSemitones < -24.0 || pitchShiftSemitones > 24.0) {
            throw new IllegalArgumentException(
                    "pitchShiftSemitones must be in [-24, 24]: " + pitchShiftSemitones);
        }
        this.pitchShiftSemitones = pitchShiftSemitones;
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
            Arrays.fill(lastPhase[ch], 0.0);
            Arrays.fill(synthPhase[ch], 0.0);
            Arrays.fill(inputRing[ch], 0.0f);
            Arrays.fill(outputRing[ch], 0.0f);
            inputWritePos[ch] = 0;
            resamplePos[ch] = 0.0;
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
