package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.core.mixer.InsertEffect;

import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Arrays;
import java.util.Objects;

/**
 * Hearing-loss simulation processor for accessible monitoring.
 *
 * <p>Models common hearing impairments — age-related (presbycusis), mild
 * high-frequency loss, and noise-induced hearing loss — so that audio
 * engineers can preview how their mixes will sound to listeners with
 * reduced hearing sensitivity. Intended as a <strong>monitoring-only</strong>
 * insert: when {@link #setBypassed(boolean) bypassed} the processor is
 * transparent, which allows the host DAW to disable it automatically
 * during export / bounce.</p>
 *
 * <h2>Model</h2>
 * <ul>
 *   <li><strong>Audiogram-based attenuation</strong> — a hearing threshold
 *       (in dB HL) is configurable at each ISO octave-band center frequency
 *       from 250&nbsp;Hz to 8&nbsp;kHz. The attenuation is realised by a
 *       serial chain of peak-EQ {@link BiquadFilter} stages whose gain is
 *       the negative of the corresponding threshold, producing a
 *       frequency-dependent gain reduction that matches the audiogram
 *       shape.</li>
 *   <li><strong>Loudness recruitment</strong> — impaired ears compress
 *       the perceived dynamic range at affected frequencies (reduced range
 *       between audibility threshold and loudness discomfort level). This
 *       is simulated with a parallel per-band bandpass tap feeding an
 *       individual {@link CompressorProcessor}; the per-band outputs are
 *       summed into the main path, scaled by the
 *       {@link #getRecruitmentLevel() recruitment level}.</li>
 *   <li><strong>Broadened auditory filters</strong> — damaged cochleas show
 *       reduced frequency selectivity. The {@link #getFilterBroadening()
 *       filter-broadening factor} lowers the Q of the audiogram
 *       attenuation stages, widening each band's skirts to mimic the loss
 *       of frequency resolution.</li>
 * </ul>
 *
 * <h2>Presets</h2>
 * <ul>
 *   <li>{@link Preset#MILD_HIGH_FREQUENCY} — mild high-frequency loss</li>
 *   <li>{@link Preset#PRESBYCUSIS} — moderate age-related loss</li>
 *   <li>{@link Preset#NOISE_INDUCED} — 4&nbsp;kHz notch from noise exposure</li>
 *   <li>{@link Preset#NORMAL} — 0&nbsp;dB HL across the audiogram (flat)</li>
 * </ul>
 *
 * <h2>AES Research References</h2>
 * <ul>
 *   <li>Investigation of a Real-Time Hearing Loss Simulation for use in
 *       Audio Production (2020) — audiogram-based simulator with
 *       frequency-dependent gain reduction, loudness recruitment, and
 *       reduced frequency selectivity.</li>
 *   <li>Developing plugins for your ears (2021) — hearing-related audio
 *       plugin design and hearing-loss awareness tooling.</li>
 * </ul>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
@RealTimeSafe
@InsertEffect(type = "HEARING_LOSS_SIMULATOR", displayName = "Hearing Loss Simulator")
public final class HearingLossSimulator implements AudioProcessor {

    /** ISO octave-band audiogram center frequencies (Hz), 250 Hz – 8 kHz. */
    public static final double[] AUDIOGRAM_FREQUENCIES = {
            250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0
    };

    /** Number of audiogram bands. */
    public static final int BAND_COUNT = AUDIOGRAM_FREQUENCIES.length;

    /** Maximum supported hearing threshold (dB HL). */
    public static final double MAX_THRESHOLD_DB = 80.0;

    /** Common hearing-profile presets. */
    public enum Preset {
        /** Normal hearing — 0 dB HL across the audiogram. */
        NORMAL(new double[]{0, 0, 0, 0, 0, 0}),
        /** Mild high-frequency loss — characteristic of early noise exposure
         *  or light presbycusis. */
        MILD_HIGH_FREQUENCY(new double[]{0, 0, 5, 15, 25, 35}),
        /** Moderate age-related (presbycusis) loss — gently sloping from
         *  low to high frequencies. */
        PRESBYCUSIS(new double[]{5, 10, 15, 25, 40, 55}),
        /** Noise-induced hearing loss — classic 4 kHz notch. */
        NOISE_INDUCED(new double[]{0, 5, 5, 15, 45, 30});

        private final double[] thresholdsDb;

        Preset(double[] thresholdsDb) {
            this.thresholdsDb = thresholdsDb;
        }

        /** Returns a copy of the preset's audiogram thresholds (dB HL). */
        public double[] thresholdsDb() {
            return Arrays.copyOf(thresholdsDb, thresholdsDb.length);
        }
    }

    private final int channels;
    private final double sampleRate;

    private final double[] thresholdsDb = new double[BAND_COUNT];
    private double recruitmentLevel = 0.5;
    private double filterBroadening = 1.0;
    private boolean bypassed;

    // Serial audiogram attenuation: [band][channel]
    private final BiquadFilter[][] attenuationFilters;

    // Parallel recruitment bandpass taps: [band][channel]
    private final BiquadFilter[][] recruitmentBandpass;

    // Per-band recruitment compressors (one per band, each handling all channels)
    private final CompressorProcessor[] recruitmentCompressors;

    // Scratch buffers for recruitment processing
    private float[][] bandTap;
    private float[][] bandOut;

    /**
     * Creates a hearing-loss simulator initialised to normal hearing
     * (0 dB HL across all bands, no recruitment).
     *
     * @param channels   number of audio channels (must be positive)
     * @param sampleRate the sample rate in Hz (must be positive)
     */
    public HearingLossSimulator(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;

        this.attenuationFilters = new BiquadFilter[BAND_COUNT][channels];
        this.recruitmentBandpass = new BiquadFilter[BAND_COUNT][channels];
        this.recruitmentCompressors = new CompressorProcessor[BAND_COUNT];
        for (int band = 0; band < BAND_COUNT; band++) {
            for (int ch = 0; ch < channels; ch++) {
                attenuationFilters[band][ch] = BiquadFilter.create(
                        BiquadFilter.FilterType.PEAK_EQ, sampleRate,
                        clampedBandFrequency(band), baseQ(), 0.0);
                recruitmentBandpass[band][ch] = BiquadFilter.create(
                        BiquadFilter.FilterType.BAND_PASS, sampleRate,
                        clampedBandFrequency(band), 1.0, 0.0);
            }
            CompressorProcessor comp = new CompressorProcessor(channels, sampleRate);
            comp.setThresholdDb(-30.0);
            comp.setRatio(6.0);
            comp.setAttackMs(5.0);
            comp.setReleaseMs(80.0);
            comp.setKneeDb(6.0);
            comp.setMakeupGainDb(0.0);
            recruitmentCompressors[band] = comp;
        }

        this.bandTap = new float[channels][0];
        this.bandOut = new float[channels][0];
    }

    // ---- Audiogram configuration ----

    /**
     * Returns the hearing threshold (dB HL) for the specified audiogram band.
     *
     * @param bandIndex the band index (0 .. {@link #BAND_COUNT}-1)
     * @return the threshold in dB HL (0 = normal, positive = loss)
     */
    public double getBandThresholdDb(int bandIndex) {
        return thresholdsDb[bandIndex];
    }

    /**
     * Sets the hearing threshold (dB HL) for the specified audiogram band.
     * Negative values are clamped to 0; values above {@link #MAX_THRESHOLD_DB}
     * are clamped to the maximum.
     *
     * @param bandIndex  the band index
     * @param thresholdDb the threshold in dB HL
     */
    public void setBandThresholdDb(int bandIndex, double thresholdDb) {
        thresholdsDb[bandIndex] = clampThreshold(thresholdDb);
        updateAttenuationBand(bandIndex);
    }

    /**
     * Sets the full audiogram in one call.
     *
     * @param thresholdsDb array of {@link #BAND_COUNT} thresholds (dB HL)
     */
    public void setAudiogram(double[] thresholdsDb) {
        Objects.requireNonNull(thresholdsDb, "thresholdsDb must not be null");
        if (thresholdsDb.length != BAND_COUNT) {
            throw new IllegalArgumentException(
                    "thresholdsDb length " + thresholdsDb.length
                            + " does not match band count " + BAND_COUNT);
        }
        for (int i = 0; i < BAND_COUNT; i++) {
            this.thresholdsDb[i] = clampThreshold(thresholdsDb[i]);
        }
        updateAllAttenuationBands();
    }

    /** Returns a copy of the current audiogram thresholds (dB HL). */
    public double[] getAudiogram() {
        return Arrays.copyOf(thresholdsDb, thresholdsDb.length);
    }

    /**
     * Returns the center frequency (Hz) of the specified audiogram band.
     *
     * @param bandIndex the band index
     * @return the band's ISO octave center frequency
     */
    public double getBandFrequency(int bandIndex) {
        return AUDIOGRAM_FREQUENCIES[bandIndex];
    }

    /** Applies the specified hearing-profile preset. */
    public void applyPreset(Preset preset) {
        Objects.requireNonNull(preset, "preset must not be null");
        setAudiogram(preset.thresholdsDb);
    }

    // ---- Recruitment / filter-broadening ----

    /**
     * Returns the loudness-recruitment blend level in [0, 1]. A value of
     * {@code 0} disables recruitment; {@code 1} sums the fully compressed
     * per-band signals into the output.
     */
    @ProcessorParam(id = 0, name = "Recruitment", min = 0.0, max = 1.0, defaultValue = 0.5)
    public double getRecruitmentLevel() {
        return recruitmentLevel;
    }

    /**
     * Sets the loudness-recruitment blend level (clamped to [0, 1]).
     *
     * @param level recruitment level (0 disables, 1 full effect)
     */
    public void setRecruitmentLevel(double level) {
        this.recruitmentLevel = Math.max(0.0, Math.min(1.0, level));
    }

    /**
     * Returns the auditory-filter broadening factor. {@code 1.0} is
     * nominal (healthy frequency selectivity); larger values broaden the
     * audiogram attenuation bands to mimic reduced frequency resolution.
     */
    @ProcessorParam(id = 1, name = "Filter Broadening", min = 1.0, max = 5.0, defaultValue = 1.0)
    public double getFilterBroadening() {
        return filterBroadening;
    }

    /**
     * Sets the filter-broadening factor (≥ 1).
     *
     * @param factor broadening factor — 1 = nominal Q, larger widens
     *               audiogram bands
     */
    public void setFilterBroadening(double factor) {
        if (factor < 1.0) {
            throw new IllegalArgumentException("factor must be >= 1.0: " + factor);
        }
        this.filterBroadening = factor;
        updateAllAttenuationBands();
    }

    /**
     * Returns whether the simulator is bypassed. Bypass makes the processor
     * transparent and is used by the host to disable monitoring effects
     * during export.
     */
    public boolean isBypassed() {
        return bypassed;
    }

    /** Sets the bypass state. When bypassed, audio passes through unchanged. */
    public void setBypassed(boolean bypassed) {
        this.bypassed = bypassed;
    }

    /**
     * Returns the compressor used to model recruitment in the specified band.
     * Exposed so advanced users can fine-tune per-band dynamics.
     */
    public CompressorProcessor getRecruitmentCompressor(int bandIndex) {
        return recruitmentCompressors[bandIndex];
    }

    // ---- AudioProcessor ----

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int activeChannels = Math.min(channels,
                Math.min(inputBuffer.length, outputBuffer.length));

        // Pass-through copy.
        for (int ch = 0; ch < activeChannels; ch++) {
            System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
        }

        if (bypassed) {
            return;
        }

        // Serial audiogram attenuation on the output signal.
        for (int band = 0; band < BAND_COUNT; band++) {
            if (thresholdsDb[band] == 0.0) {
                continue;
            }
            for (int ch = 0; ch < activeChannels; ch++) {
                attenuationFilters[band][ch].process(outputBuffer[ch], 0, numFrames);
            }
        }

        // Parallel per-band recruitment.
        if (recruitmentLevel <= 0.0) {
            return;
        }
        ensureScratchBuffers(numFrames);

        for (int band = 0; band < BAND_COUNT; band++) {
            if (thresholdsDb[band] <= 0.0) {
                continue;
            }
            // Tap: bandpass the (attenuated) output into bandTap.
            for (int ch = 0; ch < activeChannels; ch++) {
                float[] src = outputBuffer[ch];
                float[] dst = bandTap[ch];
                for (int i = 0; i < numFrames; i++) {
                    dst[i] = recruitmentBandpass[band][ch].processSample(src[i]);
                }
            }
            // Compress the band-limited tap.
            recruitmentCompressors[band].process(bandTap, bandOut, numFrames);
            // Mix the compressed band into the output.
            double gain = recruitmentLevel;
            for (int ch = 0; ch < activeChannels; ch++) {
                float[] out = outputBuffer[ch];
                float[] wet = bandOut[ch];
                for (int i = 0; i < numFrames; i++) {
                    out[i] += (float) (wet[i] * gain);
                }
            }
        }
    }

    @Override
    public void reset() {
        for (BiquadFilter[] bandFilters : attenuationFilters) {
            for (BiquadFilter f : bandFilters) {
                f.reset();
            }
        }
        for (BiquadFilter[] bandFilters : recruitmentBandpass) {
            for (BiquadFilter f : bandFilters) {
                f.reset();
            }
        }
        for (CompressorProcessor c : recruitmentCompressors) {
            c.reset();
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

    // ---- Internal helpers ----

    private void updateAllAttenuationBands() {
        for (int band = 0; band < BAND_COUNT; band++) {
            updateAttenuationBand(band);
        }
    }

    private void updateAttenuationBand(int band) {
        double freq = clampedBandFrequency(band);
        double q = baseQ() / filterBroadening;
        double gainDb = -thresholdsDb[band];
        for (int ch = 0; ch < channels; ch++) {
            attenuationFilters[band][ch].recalculate(
                    BiquadFilter.FilterType.PEAK_EQ, sampleRate, freq, q, gainDb);
        }
    }

    private double clampedBandFrequency(int band) {
        // Keep the center frequency strictly below Nyquist so the biquad
        // design remains numerically well-conditioned at low sample rates.
        double nyquist = sampleRate / 2.0;
        double freq = AUDIOGRAM_FREQUENCIES[band];
        double maxFreq = nyquist * 0.98;
        return Math.min(freq, maxFreq);
    }

    private static double baseQ() {
        // Octave-bandwidth peak EQ has Q ≈ 1.414.
        return 1.414;
    }

    private static double clampThreshold(double thresholdDb) {
        if (thresholdDb < 0.0) {
            return 0.0;
        }
        return Math.min(thresholdDb, MAX_THRESHOLD_DB);
    }

    private void ensureScratchBuffers(int numFrames) {
        if (bandTap.length != channels || bandTap[0].length < numFrames) {
            bandTap = new float[channels][numFrames];
            bandOut = new float[channels][numFrames];
        }
    }
}
