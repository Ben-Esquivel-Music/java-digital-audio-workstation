package com.benesquivelmusic.daw.core.spatial.room;

import com.benesquivelmusic.daw.core.analysis.FftUtils;
import com.benesquivelmusic.daw.sdk.spatial.ImpulseResponse;

import java.util.Arrays;
import java.util.Objects;
import java.util.Random;

/**
 * Resynthesizes the late reverb tail of a spatial room impulse response (RIR)
 * with configurable anisotropic multi-slope decay.
 *
 * <p>This enables extending or modifying measured room impulse responses —
 * for example, extending the decay time of a short RIR or adjusting the
 * directional decay balance without re-measuring the room.</p>
 *
 * <p>The algorithm follows the approach described in "Resynthesis of Spatial
 * Room Impulse Response Tails With Anisotropic Multi-Slope Decays" (AES, 2022):
 * </p>
 * <ol>
 *   <li>Analyze measured RIR: extract energy decay curve per frequency band
 *       per spatial channel using Schroeder backward integration</li>
 *   <li>Resynthesize the late tail using shaped Gaussian noise filtered to
 *       match the original spectral envelope</li>
 *   <li>Support independent decay time per direction (anisotropic) and per
 *       frequency band</li>
 *   <li>Crossfade the resynthesized tail with the measured early reflections
 *       at a configurable mixing time</li>
 * </ol>
 *
 * <p>Compatible with multi-channel impulse responses such as those loaded
 * from SOFA files via {@link com.benesquivelmusic.daw.core.spatial.binaural.SofaFileParser}.</p>
 *
 * @see FftUtils
 * @see com.benesquivelmusic.daw.acoustics.simulator.AcousticsRoomSimulator
 */
public final class SpatialRirResynthesizer {

    /** Default number of octave frequency bands for analysis. */
    static final int DEFAULT_NUM_BANDS = 6;

    /** Default mixing time in seconds where early reflections end and resynthesized tail begins. */
    static final double DEFAULT_MIXING_TIME_SECONDS = 0.05;

    /** Default crossfade duration in seconds for the early-to-late transition. */
    static final double DEFAULT_CROSSFADE_DURATION_SECONDS = 0.01;

    /** FFT size used for spectral envelope extraction. */
    static final int FFT_SIZE = 1024;

    /** Octave band center frequencies in Hz for decay analysis. */
    static final double[] BAND_CENTER_FREQUENCIES = {125, 250, 500, 1000, 2000, 4000};

    private final int sampleRate;
    private final int numBands;
    private double mixingTimeSeconds;
    private double crossfadeDurationSeconds;
    private final Random random;

    /**
     * Creates a resynthesizer with the given sample rate and default settings.
     *
     * @param sampleRate the audio sample rate in Hz
     * @throws IllegalArgumentException if sampleRate is not positive
     */
    public SpatialRirResynthesizer(int sampleRate) {
        this(sampleRate, DEFAULT_NUM_BANDS);
    }

    /**
     * Creates a resynthesizer with the given sample rate and number of frequency bands.
     *
     * @param sampleRate the audio sample rate in Hz
     * @param numBands   the number of octave frequency bands for analysis
     * @throws IllegalArgumentException if sampleRate is not positive or numBands is not in [1, 6]
     */
    public SpatialRirResynthesizer(int sampleRate, int numBands) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (numBands < 1 || numBands > BAND_CENTER_FREQUENCIES.length) {
            throw new IllegalArgumentException(
                    "numBands must be between 1 and %d: %d".formatted(BAND_CENTER_FREQUENCIES.length, numBands));
        }
        this.sampleRate = sampleRate;
        this.numBands = numBands;
        this.mixingTimeSeconds = DEFAULT_MIXING_TIME_SECONDS;
        this.crossfadeDurationSeconds = DEFAULT_CROSSFADE_DURATION_SECONDS;
        this.random = new Random(42);
    }

    /**
     * Package-private constructor for testing with a deterministic random seed.
     */
    SpatialRirResynthesizer(int sampleRate, int numBands, long seed) {
        this(sampleRate, numBands);
        this.random.setSeed(seed);
    }

    /**
     * Returns the sample rate.
     *
     * @return the sample rate in Hz
     */
    public int getSampleRate() {
        return sampleRate;
    }

    /**
     * Returns the number of frequency bands used for analysis.
     *
     * @return the number of bands
     */
    public int getNumBands() {
        return numBands;
    }

    /**
     * Returns the mixing time in seconds.
     *
     * @return the mixing time
     */
    public double getMixingTime() {
        return mixingTimeSeconds;
    }

    /**
     * Sets the mixing time — the point where the measured early reflections
     * end and the resynthesized late tail begins.
     *
     * @param seconds the mixing time in seconds
     * @throws IllegalArgumentException if seconds is not positive
     */
    public void setMixingTime(double seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("mixingTime must be positive: " + seconds);
        }
        this.mixingTimeSeconds = seconds;
    }

    /**
     * Returns the crossfade duration in seconds.
     *
     * @return the crossfade duration
     */
    public double getCrossfadeDuration() {
        return crossfadeDurationSeconds;
    }

    /**
     * Sets the crossfade duration for the transition between measured early
     * reflections and the resynthesized tail.
     *
     * @param seconds the crossfade duration in seconds
     * @throws IllegalArgumentException if seconds is not positive
     */
    public void setCrossfadeDuration(double seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("crossfadeDuration must be positive: " + seconds);
        }
        this.crossfadeDurationSeconds = seconds;
    }

    /**
     * Analyzes the energy decay of each channel and frequency band in the
     * given impulse response using Schroeder backward integration.
     *
     * <p>Returns a matrix of estimated RT60 decay times in seconds,
     * indexed as {@code [channel][band]}.</p>
     *
     * @param ir the measured impulse response to analyze
     * @return the per-channel, per-band RT60 estimates in seconds
     * @throws NullPointerException if ir is null
     */
    public double[][] analyzeDecay(ImpulseResponse ir) {
        Objects.requireNonNull(ir, "ir must not be null");
        int channels = ir.channelCount();
        double[][] decayTimes = new double[channels][numBands];

        for (int ch = 0; ch < channels; ch++) {
            float[] channelData = ir.samples()[ch];
            decayTimes[ch] = analyzeChannelDecay(channelData);
        }
        return decayTimes;
    }

    /**
     * Resynthesizes the impulse response with automatically analyzed decay
     * times extended to the target duration.
     *
     * <p>The measured early reflections (up to the mixing time) are preserved,
     * and the late reverb tail is replaced with shaped Gaussian noise that
     * matches the spectral envelope and follows the analyzed decay slopes.</p>
     *
     * @param measuredIr            the measured impulse response
     * @param targetDurationSeconds the target duration for the output IR in seconds
     * @return the resynthesized impulse response
     * @throws NullPointerException     if measuredIr is null
     * @throws IllegalArgumentException if targetDurationSeconds is not positive
     */
    public ImpulseResponse resynthesize(ImpulseResponse measuredIr,
                                        double targetDurationSeconds) {
        double[][] decayTimes = analyzeDecay(measuredIr);
        return resynthesize(measuredIr, targetDurationSeconds, decayTimes);
    }

    /**
     * Resynthesizes the impulse response with the given per-channel, per-band
     * decay times, enabling anisotropic multi-slope decay control.
     *
     * <p>The measured early reflections (up to the mixing time) are preserved,
     * and the late reverb tail is replaced with shaped Gaussian noise filtered
     * to match the spectral envelope with the specified decay slopes per
     * direction and frequency band.</p>
     *
     * @param measuredIr            the measured impulse response
     * @param targetDurationSeconds the target duration for the output IR in seconds
     * @param decayTimes            per-channel, per-band RT60 values in seconds,
     *                              indexed as {@code [channel][band]}
     * @return the resynthesized impulse response
     * @throws NullPointerException     if measuredIr or decayTimes is null
     * @throws IllegalArgumentException if targetDurationSeconds is not positive,
     *                                  or if decayTimes dimensions do not match
     */
    public ImpulseResponse resynthesize(ImpulseResponse measuredIr,
                                        double targetDurationSeconds,
                                        double[][] decayTimes) {
        Objects.requireNonNull(measuredIr, "measuredIr must not be null");
        Objects.requireNonNull(decayTimes, "decayTimes must not be null");
        if (targetDurationSeconds <= 0) {
            throw new IllegalArgumentException(
                    "targetDurationSeconds must be positive: " + targetDurationSeconds);
        }
        int channels = measuredIr.channelCount();
        if (decayTimes.length != channels) {
            throw new IllegalArgumentException(
                    "decayTimes must have %d channels but has %d"
                            .formatted(channels, decayTimes.length));
        }
        for (int ch = 0; ch < channels; ch++) {
            if (decayTimes[ch].length != numBands) {
                throw new IllegalArgumentException(
                        "decayTimes[%d] must have %d bands but has %d"
                                .formatted(ch, numBands, decayTimes[ch].length));
            }
        }

        int targetLength = (int) (targetDurationSeconds * sampleRate);
        targetLength = Math.max(targetLength, measuredIr.lengthInSamples());

        float[][] outputSamples = new float[channels][];
        for (int ch = 0; ch < channels; ch++) {
            outputSamples[ch] = resynthesizeChannel(
                    measuredIr.samples()[ch], decayTimes[ch], targetLength);
        }

        return new ImpulseResponse(outputSamples, sampleRate);
    }

    // ----------------------------------------------------------------
    // Channel-level analysis
    // ----------------------------------------------------------------

    /**
     * Analyzes a single channel's decay per frequency band.
     * Returns RT60 estimates for each band.
     */
    private double[] analyzeChannelDecay(float[] channel) {
        double[] bandDecayTimes = new double[numBands];
        int mixingSample = (int) (mixingTimeSeconds * sampleRate);
        mixingSample = Math.min(mixingSample, channel.length);

        // Analyze decay starting from the mixing time
        int analysisLength = channel.length - mixingSample;
        if (analysisLength <= 0) {
            // IR is shorter than mixing time; estimate from full IR
            Arrays.fill(bandDecayTimes, estimateFullBandDecay(channel));
            return bandDecayTimes;
        }

        for (int b = 0; b < numBands; b++) {
            double centerFreq = BAND_CENTER_FREQUENCIES[b];
            float[] bandFiltered = bandpassFilter(channel, centerFreq);
            bandDecayTimes[b] = schroederDecayEstimate(bandFiltered, mixingSample);
        }
        return bandDecayTimes;
    }

    /**
     * Estimates the overall RT60 from the full-band energy decay curve
     * using Schroeder backward integration.
     */
    private double estimateFullBandDecay(float[] channel) {
        return schroederDecayEstimate(channel, 0);
    }

    /**
     * Estimates RT60 from the energy decay curve using Schroeder backward
     * integration, starting from the given sample offset.
     *
     * <p>Computes the energy decay curve (EDC) as the reverse-cumulative
     * sum of squared samples. The RT60 is estimated by fitting a linear
     * slope to the EDC in dB and extrapolating to −60 dB.</p>
     */
    double schroederDecayEstimate(float[] signal, int startSample) {
        int length = signal.length - startSample;
        if (length <= 1) {
            return 0.1; // minimum fallback
        }

        // Compute energy decay curve via backward integration
        double[] edc = new double[length];
        edc[length - 1] = (double) signal[signal.length - 1] * signal[signal.length - 1];
        for (int i = length - 2; i >= 0; i--) {
            double sample = signal[startSample + i];
            edc[i] = edc[i + 1] + sample * sample;
        }

        // Normalize EDC and convert to dB
        double maxEnergy = edc[0];
        if (maxEnergy <= 0) {
            return 0.1; // silence
        }

        // Find the time to decay by 20 dB (T20) and extrapolate to RT60
        double threshold20dB = maxEnergy * 0.01; // -20 dB
        int decaySample = -1;
        for (int i = 0; i < length; i++) {
            if (edc[i] <= threshold20dB) {
                decaySample = i;
                break;
            }
        }

        if (decaySample <= 0) {
            // Decay not reached within the signal; estimate from available data
            double threshold10dB = maxEnergy * 0.1; // -10 dB
            for (int i = 0; i < length; i++) {
                if (edc[i] <= threshold10dB) {
                    decaySample = i;
                    break;
                }
            }
            if (decaySample <= 0) {
                return (double) length / sampleRate * 3.0; // rough estimate
            }
            // Extrapolate T10 to RT60
            return (double) decaySample / sampleRate * 6.0;
        }

        // Extrapolate T20 to RT60 (multiply by 3)
        return (double) decaySample / sampleRate * 3.0;
    }

    // ----------------------------------------------------------------
    // Channel-level resynthesis
    // ----------------------------------------------------------------

    /**
     * Resynthesizes a single channel: preserves early reflections and
     * replaces the late tail with shaped noise.
     */
    private float[] resynthesizeChannel(float[] measured, double[] bandDecayTimes,
                                         int targetLength) {
        float[] output = new float[targetLength];
        int mixingSample = (int) (mixingTimeSeconds * sampleRate);
        mixingSample = Math.min(mixingSample, measured.length);
        int crossfadeSamples = (int) (crossfadeDurationSeconds * sampleRate);
        crossfadeSamples = Math.max(crossfadeSamples, 1);

        // Copy early reflections
        int earlyEnd = Math.min(mixingSample + crossfadeSamples, measured.length);
        System.arraycopy(measured, 0, output, 0, Math.min(earlyEnd, targetLength));

        // Extract spectral envelope at the mixing time for shaping the noise
        double[] spectralEnvelope = extractSpectralEnvelope(measured, mixingSample);

        // Generate shaped noise tail
        float[] syntheticTail = generateShapedNoiseTail(
                spectralEnvelope, bandDecayTimes, mixingSample, targetLength);

        // Crossfade: fade out measured, fade in synthetic
        int crossfadeStart = Math.max(0, mixingSample - crossfadeSamples / 2);
        int crossfadeEnd = Math.min(targetLength, crossfadeStart + crossfadeSamples);

        for (int i = crossfadeStart; i < crossfadeEnd; i++) {
            float alpha = (float) (i - crossfadeStart) / crossfadeSamples;
            float measuredSample = (i < measured.length) ? measured[i] : 0.0f;
            output[i] = (1.0f - alpha) * measuredSample + alpha * syntheticTail[i];
        }

        // Fill remainder with synthetic tail only
        for (int i = crossfadeEnd; i < targetLength; i++) {
            output[i] = syntheticTail[i];
        }

        return output;
    }

    // ----------------------------------------------------------------
    // Spectral analysis
    // ----------------------------------------------------------------

    /**
     * Extracts the spectral envelope around the mixing time using FFT.
     * Returns magnitude spectrum (half-spectrum, size = FFT_SIZE/2 + 1).
     */
    double[] extractSpectralEnvelope(float[] signal, int centerSample) {
        int halfFft = FFT_SIZE / 2 + 1;
        double[] real = new double[FFT_SIZE];
        double[] imag = new double[FFT_SIZE];

        // Window the signal around the mixing time
        double[] window = FftUtils.createHannWindow(FFT_SIZE);
        int start = Math.max(0, centerSample - FFT_SIZE / 2);
        for (int i = 0; i < FFT_SIZE; i++) {
            int idx = start + i;
            if (idx >= 0 && idx < signal.length) {
                real[i] = signal[idx] * window[i];
            }
        }

        FftUtils.fft(real, imag);

        // Compute magnitude spectrum
        double[] magnitudes = new double[halfFft];
        for (int i = 0; i < halfFft; i++) {
            magnitudes[i] = Math.sqrt(real[i] * real[i] + imag[i] * imag[i]);
        }
        return magnitudes;
    }

    // ----------------------------------------------------------------
    // Bandpass filtering (simple 2nd-order IIR)
    // ----------------------------------------------------------------

    /**
     * Applies a bandpass filter centered at the given frequency.
     * Uses a simple 2nd-order IIR filter for band isolation.
     */
    float[] bandpassFilter(float[] input, double centerFreq) {
        float[] output = new float[input.length];
        double omega = 2.0 * Math.PI * centerFreq / sampleRate;
        double bandwidth = 1.0; // octave bandwidth
        double alpha = Math.sin(omega) * Math.sinh(Math.log(2.0) / 2.0 * bandwidth * omega / Math.sin(omega));

        double b0 = alpha;
        double b1 = 0.0;
        double b2 = -alpha;
        double a0 = 1.0 + alpha;
        double a1 = -2.0 * Math.cos(omega);
        double a2 = 1.0 - alpha;

        // Normalize
        b0 /= a0;
        b1 /= a0;
        b2 /= a0;
        a1 /= a0;
        a2 /= a0;

        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < input.length; i++) {
            double x0 = input[i];
            double y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            output[i] = (float) y0;
            x2 = x1;
            x1 = x0;
            y2 = y1;
            y1 = y0;
        }
        return output;
    }

    // ----------------------------------------------------------------
    // Noise tail generation
    // ----------------------------------------------------------------

    /**
     * Generates the resynthesized late reverb tail using shaped Gaussian noise.
     *
     * <p>For each frequency band, white Gaussian noise is filtered to the band
     * and multiplied by an exponential decay envelope derived from the per-band
     * RT60. The band signals are summed and shaped by the overall spectral
     * envelope extracted from the measured IR at the mixing time.</p>
     */
    private float[] generateShapedNoiseTail(double[] spectralEnvelope,
                                             double[] bandDecayTimes,
                                             int mixingSample,
                                             int targetLength) {
        float[] tail = new float[targetLength];

        // Generate per-band decaying noise and accumulate
        for (int b = 0; b < numBands; b++) {
            double rt60 = Math.max(bandDecayTimes[b], 0.01);
            double centerFreq = BAND_CENTER_FREQUENCIES[b];
            float[] bandNoise = generateBandNoise(centerFreq, rt60, mixingSample, targetLength);
            for (int i = mixingSample; i < targetLength; i++) {
                tail[i] += bandNoise[i];
            }
        }

        // Shape by spectral envelope: apply overall energy matching
        applySpectralShaping(tail, spectralEnvelope, mixingSample, targetLength);

        return tail;
    }

    /**
     * Generates bandpass-filtered decaying Gaussian noise for one frequency band.
     */
    private float[] generateBandNoise(double centerFreq, double rt60,
                                       int startSample, int totalLength) {
        // Generate white Gaussian noise
        float[] noise = new float[totalLength];
        for (int i = startSample; i < totalLength; i++) {
            noise[i] = (float) random.nextGaussian();
        }

        // Apply bandpass filter
        float[] filtered = bandpassFilter(noise, centerFreq);

        // Apply exponential decay envelope
        double decayRate = -6.9078 / (rt60 * sampleRate); // ln(0.001) / (RT60 * sr)
        for (int i = startSample; i < totalLength; i++) {
            double envelope = Math.exp(decayRate * (i - startSample));
            filtered[i] *= (float) envelope;
        }

        return filtered;
    }

    /**
     * Applies spectral shaping to the noise tail using FFT overlap-add.
     * Matches the noise spectral envelope to the measured IR envelope.
     */
    private void applySpectralShaping(float[] tail, double[] targetEnvelope,
                                       int startSample, int totalLength) {
        int hopSize = FFT_SIZE / 2;
        double[] window = FftUtils.createHannWindow(FFT_SIZE);

        for (int pos = startSample; pos < totalLength - FFT_SIZE; pos += hopSize) {
            double[] real = new double[FFT_SIZE];
            double[] imag = new double[FFT_SIZE];

            // Window the current block
            for (int i = 0; i < FFT_SIZE; i++) {
                if (pos + i < totalLength) {
                    real[i] = tail[pos + i] * window[i];
                }
            }

            // Forward FFT
            FftUtils.fft(real, imag);

            // Compute current magnitude and apply spectral shaping
            int halfFft = Math.min(FFT_SIZE / 2 + 1, targetEnvelope.length);
            for (int i = 0; i < halfFft; i++) {
                double currentMag = Math.sqrt(real[i] * real[i] + imag[i] * imag[i]);
                if (currentMag > 1e-12) {
                    double gain = targetEnvelope[i] / currentMag;
                    gain = Math.min(gain, 10.0); // limit gain to avoid blowup
                    real[i] *= gain;
                    imag[i] *= gain;
                    // Mirror for negative frequencies
                    if (i > 0 && i < FFT_SIZE / 2) {
                        real[FFT_SIZE - i] *= gain;
                        imag[FFT_SIZE - i] *= gain;
                    }
                }
            }

            // Inverse FFT
            FftUtils.ifft(real, imag);

            // Overlap-add back
            for (int i = 0; i < FFT_SIZE; i++) {
                if (pos + i < totalLength) {
                    tail[pos + i] = (float) (real[i] * window[i]);
                }
            }
        }
    }
}
