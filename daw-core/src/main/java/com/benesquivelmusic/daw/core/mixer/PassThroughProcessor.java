package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Arrays;

/**
 * An insert processor whose output equals its input on every channel the
 * input has (an output channel beyond them is silent), at zero latency. It
 * does so in both mix precisions: {@link #processDouble} copies the 64-bit
 * bus exactly, so the slot neither rounds it to {@code float} nor allocates
 * on the audio thread, as the SDK's default narrowing adapter would. A
 * first-party plugin with no audio processor of its own occupies its insert
 * slot with one ({@code BuiltInPluginGraph}), and so does an offline insert —
 * one a project open could not load ({@link InsertSlot#ofOffline}) — so the
 * slot holds its place in the chain without altering the signal (story 334,
 * studio ruling E1: a unity pass-through at the slot's position, whatever its
 * bypass flag, reporting no latency).
 *
 * @param channels the input and output channel count, at least 1
 */
public record PassThroughProcessor(int channels) implements AudioProcessor {

    public PassThroughProcessor {
        if (channels < 1) {
            throw new IllegalArgumentException("channels must be >= 1: " + channels);
        }
    }

    @Override
    @RealTimeSafe
    public void process(float[][] input, float[][] output, int frames) {
        for (int ch = 0; ch < output.length; ch++) {
            if (ch < input.length) {
                System.arraycopy(input[ch], 0, output[ch], 0, frames);
            } else {
                Arrays.fill(output[ch], 0, frames, 0f);
            }
        }
    }

    @Override
    @RealTimeSafe
    public void processDouble(double[][] input, double[][] output, int frames) {
        for (int ch = 0; ch < output.length; ch++) {
            if (ch < input.length) {
                System.arraycopy(input[ch], 0, output[ch], 0, frames);
            } else {
                Arrays.fill(output[ch], 0, frames, 0.0);
            }
        }
    }

    @Override
    public boolean supportsDouble() {
        return true;
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
