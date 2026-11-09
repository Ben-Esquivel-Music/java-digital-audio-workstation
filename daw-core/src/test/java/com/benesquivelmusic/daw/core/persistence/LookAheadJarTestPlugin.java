package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An external JAR plugin, as a test stands one in for (story 334, ruling-4
 * Q8): a look-ahead compressor whose processor derives its latency from its
 * look-ahead buffer, which only its {@link #LOOK_AHEAD} parameter allocates —
 * until that parameter is set, {@code getLatencySamples()} throws a
 * {@link NullPointerException}, as such a plugin's does before the buffer
 * exists. Its processor exists from construction, before the plugin is
 * initialized, so the query throws on any instance until the parameter is
 * set — the catalogue instance the registry's startup check loads and never
 * initializes included, which lets ruling-4 Q8 (5) catch a check that asks
 * for the latency. {@link #initialize} only sets the processor's channel
 * count, stereo until then. It passes audio through and counts its
 * disposals. Public, with a public no-argument constructor, as
 * {@code ExternalPluginLoader} needs. Its id, version and name are studio
 * ruling E6's examples.
 */
public final class LookAheadJarTestPlugin implements DawPlugin {

    /** The plugin's stable id. */
    public static final String ID = "com.example.comp";
    /** The plugin's version. */
    public static final String VERSION = "1.2.0";
    /** The plugin's name, which its insert shows. */
    public static final String NAME = "Comp X";
    /** The look-ahead, in samples: the latency it sets. */
    public static final int LOOK_AHEAD = 0;

    private final AtomicInteger disposals = new AtomicInteger();
    private final LookAheadProcessor processor = new LookAheadProcessor();

    @Override
    public PluginDescriptor getDescriptor() {
        return new PluginDescriptor(ID, NAME, VERSION, "Example Audio", PluginType.EFFECT);
    }

    @Override
    public void initialize(PluginContext context) {
        processor.useChannels(context.getAudioChannels());
    }

    @Override
    public void activate() {
    }

    @Override
    public void deactivate() {
    }

    @Override
    public void dispose() {
        disposals.incrementAndGet();
    }

    /** How many times this instance was disposed. */
    public int disposals() {
        return disposals.get();
    }

    @Override
    public List<PluginParameter> getParameters() {
        return List.of(new PluginParameter(LOOK_AHEAD, "Look-ahead", 0.0, 4096.0, 0.0));
    }

    @Override
    public void setAutomatableParameter(int parameterId, double value) {
        if (parameterId == LOOK_AHEAD) {
            processor.allocateLookAhead((int) value);
        }
    }

    @Override
    public Optional<AudioProcessor> asAudioProcessor() {
        return Optional.of(processor);
    }

    /** Passes audio through; its latency is the length of a look-ahead buffer nothing allocates at first. */
    public static final class LookAheadProcessor implements AudioProcessor {

        private volatile int channels = 2;
        private volatile float[] lookAhead;

        void useChannels(int count) {
            channels = count;
        }

        void allocateLookAhead(int samples) {
            lookAhead = new float[samples];
        }

        @Override
        public int getLatencySamples() {
            return lookAhead.length;
        }

        @Override
        public void process(float[][] input, float[][] output, int frames) {
            for (int channel = 0; channel < output.length; channel++) {
                System.arraycopy(input[channel], 0, output[channel], 0, frames);
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
