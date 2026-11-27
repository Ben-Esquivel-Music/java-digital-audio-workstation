package com.benesquivelmusic.daw.core.mixer.classpath;

import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

/**
 * A processor no registry knows, whose {@code @ProcessorParam} setter
 * allocates on every call: {@link InsertParameterAllocationMain} measures it
 * after the rack, so a test can show that the measurement reports an
 * allocation on the drain's path. Public, with public accessors, because the
 * insert binds them through the public lookup.
 */
public final class AllocatingParameterProcessor implements AudioProcessor {

    private final int channels;
    private double gain = 0.5;
    private double[] appliedGain = new double[1];

    /**
     * A processor of {@code channels} channels.
     *
     * @param channels the channel count
     */
    public AllocatingParameterProcessor(int channels) {
        this.channels = channels;
    }

    /** @return the gain */
    @ProcessorParam(id = 0, name = "Gain", min = 0.0, max = 1.0, defaultValue = 0.5)
    public double getGain() {
        return gain;
    }

    /** @param gain the gain, kept in a new array on every call */
    public void setGain(double gain) {
        this.gain = gain;
        appliedGain = new double[] {gain};
    }

    @Override
    public void process(float[][] input, float[][] output, int frames) {
        for (int channel = 0; channel < output.length; channel++) {
            for (int frame = 0; frame < frames; frame++) {
                output[channel][frame] = (float) (input[channel][frame] * appliedGain[0]);
            }
        }
    }

    @Override
    public void reset() {
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
