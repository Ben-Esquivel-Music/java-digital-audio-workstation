package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.EffectsChain;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;

import java.util.Random;

/**
 * Renders one second of a reference program through a strip's inserts,
 * starting from reset, so a test judges a reopened chain by matched output
 * — bit-equal or not — rather than by the values it stores (story 334,
 * studio findings G13, G14, G18; {@code studio-mixing} "Mixing decisions").
 * The program is a −12 dBFS 997 Hz sine plus a seeded noise burst, with
 * different content on every channel.
 */
public final class StripRenders {

    /** The block size the program is rendered in. */
    public static final int BLOCK_FRAMES = 256;

    private static final double SINE_HZ = 997.0;
    private static final float SINE_AMPLITUDE = (float) Math.pow(10.0, -12.0 / 20.0);
    private static final float NOISE_AMPLITUDE = 0.25f;
    private static final long NOISE_SEED = 334L;

    private StripRenders() {
    }

    /**
     * One second of the reference program through {@code channel}'s inserts,
     * the chain reset first.
     *
     * @param channel the strip
     * @param format  the project's audio format: the channel count and the sample rate
     * @return the rendered samples, per channel
     */
    public static float[][] render(MixerChannel channel, AudioFormat format) {
        int channels = format.channels();
        int frames = (int) format.sampleRate();
        float[][] program = program(channels, frames, format.sampleRate());
        channel.prepareEffectsChain(channels, BLOCK_FRAMES);
        EffectsChain chain = channel.getEffectsChain();
        chain.reset();
        float[][] rendered = new float[channels][frames];
        float[][] input = new float[channels][BLOCK_FRAMES];
        float[][] output = new float[channels][BLOCK_FRAMES];
        for (int start = 0; start < frames; start += BLOCK_FRAMES) {
            int block = Math.min(BLOCK_FRAMES, frames - start);
            for (int c = 0; c < channels; c++) {
                System.arraycopy(program[c], start, input[c], 0, block);
            }
            chain.process(input, output, block);
            for (int c = 0; c < channels; c++) {
                System.arraycopy(output[c], 0, rendered[c], start, block);
            }
        }
        return rendered;
    }

    private static float[][] program(int channels, int frames, double sampleRate) {
        float[][] program = new float[channels][frames];
        int burstStart = frames / 4;
        int burstEnd = burstStart + frames / 20;
        for (int c = 0; c < channels; c++) {
            Random noise = new Random(NOISE_SEED + c);
            double phase = c * Math.PI / 3.0;
            for (int frame = 0; frame < frames; frame++) {
                double angle = 2.0 * Math.PI * SINE_HZ * frame / sampleRate + phase;
                float sample = (float) (SINE_AMPLITUDE * Math.sin(angle));
                if (frame >= burstStart && frame < burstEnd) {
                    sample += (float) (NOISE_AMPLITUDE * (2.0 * noise.nextDouble() - 1.0));
                }
                program[c][frame] = sample;
            }
        }
        return program;
    }
}
