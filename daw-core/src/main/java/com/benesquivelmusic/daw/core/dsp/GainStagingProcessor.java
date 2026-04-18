package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.core.mixer.InsertEffect;

import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

/**
 * Simple gain staging processor for the mastering chain.
 *
 * <p>Applies a configurable gain (in dB) to all channels uniformly.
 * Used as the first stage in the mastering chain to set the initial
 * level before processing.</p>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
@InsertEffect(type = "GAIN_STAGING", displayName = "Gain Staging")
public final class GainStagingProcessor implements AudioProcessor {

    private final int channels;
    private double gainDb;
    private double gainLinear;

    /**
     * Creates a gain staging processor with the specified gain.
     *
     * @param channels number of audio channels
     * @param gainDb   the initial gain in dB
     */
    public GainStagingProcessor(int channels, double gainDb) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        this.channels = channels;
        setGainDb(gainDb);
    }

    /**
     * Factory method used by the {@link com.benesquivelmusic.daw.core.mixer.ProcessorRegistry}
     * to honor the uniform {@code (int channels, double sampleRate)} registry
     * contract. Gain staging has no sample-rate dependency, so the
     * {@code sampleRate} argument is ignored and the processor is initialized
     * with {@code gainDb == 0.0}; callers can adjust the gain afterwards via
     * {@link #setGainDb(double)}.
     *
     * @param channels   number of audio channels
     * @param sampleRate the sample rate in Hz (unused; kept for registry contract)
     * @return a new processor with 0 dB gain
     */
    public static GainStagingProcessor createInsertEffect(int channels, double sampleRate) {
        // sampleRate intentionally ignored — gain staging is sample-rate independent.
        return new GainStagingProcessor(channels, 0.0);
    }

    @RealTimeSafe
    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int activeCh = Math.min(channels, inputBuffer.length);
        if (gainDb == 0.0) {
            for (int ch = 0; ch < activeCh; ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
            return;
        }
        double g = gainLinear;
        for (int ch = 0; ch < activeCh; ch++) {
            for (int i = 0; i < numFrames; i++) {
                outputBuffer[ch][i] = (float) (inputBuffer[ch][i] * g);
            }
        }
    }

    /** Returns the current gain in dB. */
    @ProcessorParam(id = 0, name = "Gain", min = -24.0, max = 24.0, defaultValue = 0.0, unit = "dB")
    public double getGainDb() {
        return gainDb;
    }

    /**
     * Sets the gain in dB.
     *
     * @param gainDb the gain in dB
     */
    public void setGainDb(double gainDb) {
        this.gainDb = gainDb;
        this.gainLinear = Math.pow(10.0, gainDb / 20.0);
    }

    @Override
    public void reset() {
        // stateless — no internal state to reset
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
