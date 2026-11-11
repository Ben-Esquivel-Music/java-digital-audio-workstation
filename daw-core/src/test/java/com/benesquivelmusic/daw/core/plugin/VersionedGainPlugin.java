package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.Optional;

/**
 * Two releases of one external JAR plugin — one id, versions 1.1 and 1.2,
 * each its own class, as two JARs of one vendor's plugin would hold them
 * (story 334, studio ruling E20's replacement and E21's version line). The
 * processor declares one {@link ProcessorParam}, a gain its output depends
 * on. Public, with public no-argument constructors, as
 * {@link ExternalPluginLoader} and {@code ReflectivePresetSerializer}'s
 * public lookup need.
 */
public abstract sealed class VersionedGainPlugin implements DawPlugin
        permits VersionedGainPlugin.Release11, VersionedGainPlugin.Release12 {

    /** The plugin's stable id, the same in every release. */
    public static final String ID = "com.example.versioned.gain";
    /** The plugin's name, which its insert shows. */
    public static final String NAME = "Versioned Gain";

    private final String version;
    private Gain processor;

    private VersionedGainPlugin(String version) {
        this.version = version;
    }

    /** Release 1.1. */
    public static final class Release11 extends VersionedGainPlugin {
        public Release11() {
            super("1.1");
        }
    }

    /** Release 1.2. */
    public static final class Release12 extends VersionedGainPlugin {
        public Release12() {
            super("1.2");
        }
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return new PluginDescriptor(ID, NAME, version, "Example Audio", PluginType.EFFECT);
    }

    @Override
    public void initialize(PluginContext context) {
        processor = new Gain(context.getAudioChannels());
    }

    @Override
    public void activate() {
    }

    @Override
    public void deactivate() {
    }

    @Override
    public void dispose() {
    }

    @Override
    public Optional<AudioProcessor> asAudioProcessor() {
        return Optional.ofNullable(processor);
    }

    /** The processor, once initialized. */
    public Gain processor() {
        return processor;
    }

    /** Multiplies every channel by its gain. */
    public static final class Gain implements AudioProcessor {

        private final int channels;
        private double gain = 1.0;

        Gain(int channels) {
            this.channels = channels;
        }

        @ProcessorParam(id = 0, name = "Gain", min = 0.0, max = 1.0, defaultValue = 1.0)
        public double getGain() {
            return gain;
        }

        public void setGain(double gain) {
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
