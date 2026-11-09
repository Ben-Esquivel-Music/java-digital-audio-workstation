package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.Objects;
import java.util.Optional;

/**
 * An external JAR plugin, as a test stands one in for (story 334): its
 * processor declares one {@link ProcessorParam}, a gain whose value its
 * output depends on, so its insert saves name-keyed parameters. The setter
 * refuses {@link #REFUSED_GAIN}, a value inside the declared range. Public,
 * like its processor: {@code ReflectivePresetSerializer} reads accessors
 * through the public lookup.
 */
public final class AnnotatedJarTestPlugin implements DawPlugin {

    /** The plugin's stable id. */
    public static final String ID = "com.example.annotated.gain";
    /** The plugin's name, which its insert shows. */
    public static final String NAME = "Annotated Gain";
    /** A gain inside [0, 1] the processor's setter refuses. */
    public static final double REFUSED_GAIN = 0.25;

    private final String version;
    private GainProcessor processor;
    private boolean disposed;

    /** A plugin at version 1.0.0. */
    public AnnotatedJarTestPlugin() {
        this("1.0.0");
    }

    /** A plugin at {@code version}. */
    public AnnotatedJarTestPlugin(String version) {
        this.version = Objects.requireNonNull(version, "version must not be null");
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return new PluginDescriptor(ID, NAME, version, "Example Audio", PluginType.EFFECT);
    }

    @Override
    public void initialize(PluginContext context) {
        processor = new GainProcessor(context.getAudioChannels());
    }

    @Override
    public void activate() {
    }

    @Override
    public void deactivate() {
    }

    @Override
    public void dispose() {
        disposed = true;
    }

    /** Whether {@link #dispose()} ran. */
    public boolean isDisposed() {
        return disposed;
    }

    @Override
    public Optional<AudioProcessor> asAudioProcessor() {
        return Optional.ofNullable(processor);
    }

    /** The processor, once initialized. */
    public GainProcessor processor() {
        return processor;
    }

    /** Multiplies every channel by its gain. */
    public static final class GainProcessor implements AudioProcessor {

        private final int channels;
        private double gain = 1.0;

        GainProcessor(int channels) {
            this.channels = channels;
        }

        @ProcessorParam(id = 0, name = "Gain", min = 0.0, max = 1.0, defaultValue = 1.0)
        public double getGain() {
            return gain;
        }

        public void setGain(double gain) {
            if (gain == REFUSED_GAIN) {
                throw new IllegalArgumentException("gain " + gain + " is not supported");
            }
            this.gain = gain;
        }

        @Override
        public void process(float[][] input, float[][] output, int frames) {
            for (int channel = 0; channel < output.length; channel++) {
                for (int frame = 0; frame < frames; frame++) {
                    output[channel][frame] = (float) (input[channel][frame] * gain);
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
}
