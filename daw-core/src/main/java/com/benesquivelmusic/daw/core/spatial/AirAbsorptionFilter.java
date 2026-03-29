package com.benesquivelmusic.daw.core.spatial;

import com.benesquivelmusic.daw.core.dsp.BiquadFilter;
import com.benesquivelmusic.daw.core.spatial.panner.InverseSquareAttenuation;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Objects;

/**
 * Frequency-dependent air absorption filter that models the high-frequency
 * attenuation of sound traveling through air per ISO 9613-1.
 *
 * <p>Distant sources sound progressively duller as high frequencies are
 * absorbed by the atmosphere. This filter models that phenomenon using
 * ISO 9613-1 atmospheric absorption coefficients as a function of distance,
 * temperature (°C), and relative humidity (%).</p>
 *
 * <p>Implementation uses a cascade of three {@link BiquadFilter} high-shelf
 * stages at 1 kHz, 4 kHz, and 8 kHz. The shelf gain at each band is derived
 * from the ISO 9613-1 absorption coefficient multiplied by distance, producing
 * a smooth spectral rolloff that closely approximates the continuous
 * absorption curve.</p>
 *
 * <p>Integrates with {@link InverseSquareAttenuation} for complete distance
 * rendering: the attenuation model handles amplitude rolloff (inverse square
 * law) while this filter handles spectral (frequency-dependent) rolloff.
 * During {@link #process}, both the cascaded absorption filters and the
 * distance gain from the attenuation model are applied.</p>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class AirAbsorptionFilter implements AudioProcessor {

    /** Number of high-shelf filter stages approximating the ISO 9613-1 curve. */
    private static final int NUM_BANDS = 3;

    /** Center frequencies for the shelf filter stages (Hz). */
    private static final double[] BAND_FREQUENCIES = {1000.0, 4000.0, 8000.0};

    /** Q factor for the shelf filters (Butterworth). */
    private static final double SHELF_Q = 0.707;

    /** Reference temperature in Kelvin (20 °C). */
    private static final double REFERENCE_TEMP_K = 293.15;

    /** Maximum attenuation per band to prevent extreme filter gains (dB). */
    private static final double MAX_ATTENUATION_DB = 60.0;

    private final int channels;
    private final double sampleRate;
    private final InverseSquareAttenuation attenuationModel;

    /** Per-channel, per-band biquad filters: filters[channel][band]. */
    private final BiquadFilter[][] filters;

    private double distanceMeters;
    private double temperatureCelsius;
    private double relativeHumidityPercent;

    /**
     * Creates an air absorption filter with default environmental conditions
     * (20 °C, 50% relative humidity) at the attenuation model's reference distance.
     *
     * @param channels         number of audio channels (must be positive)
     * @param sampleRate       the sample rate in Hz (must be positive)
     * @param attenuationModel the distance attenuation model for gain rolloff
     */
    public AirAbsorptionFilter(int channels, double sampleRate,
                               InverseSquareAttenuation attenuationModel) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        Objects.requireNonNull(attenuationModel, "attenuationModel must not be null");

        this.channels = channels;
        this.sampleRate = sampleRate;
        this.attenuationModel = attenuationModel;
        this.distanceMeters = attenuationModel.getReferenceDistance();
        this.temperatureCelsius = 20.0;
        this.relativeHumidityPercent = 50.0;

        filters = new BiquadFilter[channels][NUM_BANDS];
        for (int ch = 0; ch < channels; ch++) {
            for (int b = 0; b < NUM_BANDS; b++) {
                filters[ch][b] = BiquadFilter.create(BiquadFilter.FilterType.HIGH_SHELF,
                        sampleRate, BAND_FREQUENCIES[b], SHELF_Q, 0.0);
            }
        }
        updateCoefficients();
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int activeCh = Math.min(channels, inputBuffer.length);
        double gain = attenuationModel.computeGain(distanceMeters);

        for (int ch = 0; ch < activeCh; ch++) {
            System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);

            for (int b = 0; b < NUM_BANDS; b++) {
                filters[ch][b].process(outputBuffer[ch], 0, numFrames);
            }

            float gainF = (float) gain;
            for (int i = 0; i < numFrames; i++) {
                outputBuffer[ch][i] *= gainF;
            }
        }
    }

    @Override
    public void reset() {
        for (int ch = 0; ch < channels; ch++) {
            for (int b = 0; b < NUM_BANDS; b++) {
                filters[ch][b].reset();
            }
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

    // --- Parameter accessors ---

    /** Returns the current source distance in meters. */
    public double getDistance() {
        return distanceMeters;
    }

    /**
     * Sets the source distance and recalculates filter coefficients.
     *
     * @param distanceMeters the distance in meters (must be non-negative)
     */
    public void setDistance(double distanceMeters) {
        if (distanceMeters < 0) {
            throw new IllegalArgumentException(
                    "distanceMeters must be non-negative: " + distanceMeters);
        }
        this.distanceMeters = distanceMeters;
        updateCoefficients();
    }

    /** Returns the current temperature in degrees Celsius. */
    public double getTemperature() {
        return temperatureCelsius;
    }

    /**
     * Sets the ambient temperature and recalculates filter coefficients.
     *
     * @param temperatureCelsius the temperature in °C (must be in [-20, 50])
     */
    public void setTemperature(double temperatureCelsius) {
        if (temperatureCelsius < -20 || temperatureCelsius > 50) {
            throw new IllegalArgumentException(
                    "temperatureCelsius must be in [-20, 50]: " + temperatureCelsius);
        }
        this.temperatureCelsius = temperatureCelsius;
        updateCoefficients();
    }

    /** Returns the current relative humidity in percent. */
    public double getHumidity() {
        return relativeHumidityPercent;
    }

    /**
     * Sets the relative humidity and recalculates filter coefficients.
     *
     * @param relativeHumidityPercent the humidity in % (must be in [10, 100])
     */
    public void setHumidity(double relativeHumidityPercent) {
        if (relativeHumidityPercent < 10 || relativeHumidityPercent > 100) {
            throw new IllegalArgumentException(
                    "relativeHumidityPercent must be in [10, 100]: " + relativeHumidityPercent);
        }
        this.relativeHumidityPercent = relativeHumidityPercent;
        updateCoefficients();
    }

    /** Returns the associated distance attenuation model. */
    public InverseSquareAttenuation getAttenuationModel() {
        return attenuationModel;
    }

    // --- ISO 9613-1 computation ---

    /**
     * Computes the atmospheric absorption coefficient in dB/m at the given
     * frequency per ISO 9613-1 (standard atmospheric pressure).
     *
     * @param frequencyHz the frequency in Hz
     * @return the absorption coefficient α in dB/m
     */
    double computeAbsorptionCoefficient(double frequencyHz) {
        double tempK = temperatureCelsius + 273.15;
        double tRatio = tempK / REFERENCE_TEMP_K;
        double h = relativeHumidityPercent;

        // Oxygen relaxation frequency (Hz)
        double frO = 24.0 + 4.04e4 * h * (0.02 + h) / (0.391 + h);

        // Nitrogen relaxation frequency (Hz)
        double frN = Math.pow(tRatio, -0.5)
                * (9.0 + 280.0 * h * Math.exp(-4.170 * (Math.pow(tRatio, -1.0 / 3.0) - 1.0)));

        double f2 = frequencyHz * frequencyHz;

        return 8.686 * f2 * (
                1.84e-11 * Math.sqrt(tRatio)
                + Math.pow(tRatio, -2.5) * (
                        0.01275 * Math.exp(-2239.1 / tempK) / (frO + f2 / frO)
                        + 0.1068 * Math.exp(-3352.0 / tempK) / (frN + f2 / frN)
                )
        );
    }

    /**
     * Recalculates all biquad filter coefficients from the current distance,
     * temperature, and humidity parameters.
     */
    private void updateCoefficients() {
        for (int b = 0; b < NUM_BANDS; b++) {
            double alpha = computeAbsorptionCoefficient(BAND_FREQUENCIES[b]);
            double attenuationDb = -alpha * distanceMeters;
            attenuationDb = Math.max(attenuationDb, -MAX_ATTENUATION_DB);

            for (int ch = 0; ch < channels; ch++) {
                filters[ch][b].recalculate(BiquadFilter.FilterType.HIGH_SHELF,
                        sampleRate, BAND_FREQUENCIES[b], SHELF_Q, attenuationDb);
            }
        }
    }
}
