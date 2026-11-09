package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.List;
import java.util.Optional;

/**
 * An external JAR plugin, as a test stands one in for (story 334), whose
 * processor declares no {@code @ProcessorParam}: it declares its parameters
 * through {@link #getParameters()} and takes their values through
 * {@link #setAutomatableParameter}, so its insert saves the slot's parameter
 * store by id. Its output is its input times {@link #LEVEL}; its setter
 * refuses {@link #REFUSED_LEVEL}, a value inside the declared range, and the
 * test reads what the plugin holds through {@link #value(int)}.
 */
public final class StoreParameterJarTestPlugin implements DawPlugin {

    /** The plugin's stable id. */
    public static final String ID = "com.example.store.knobs";
    /** The plugin's name, which its insert shows. */
    public static final String NAME = "Knob Box";
    /** The output level, 0–1, default 0.5. */
    public static final int LEVEL = 0;
    /** A drive amount, 0–10, default 2.0; ids need not be dense. */
    public static final int DRIVE = 3;
    /** A level inside [0, 1] the setter refuses. */
    public static final double REFUSED_LEVEL = 0.75;

    private double level = 0.5;
    private double drive = 2.0;
    private int channels = 2;
    private boolean disposed;

    @Override
    public PluginDescriptor getDescriptor() {
        return new PluginDescriptor(ID, NAME, "2.0", "Example Audio", PluginType.EFFECT);
    }

    @Override
    public void initialize(PluginContext context) {
        channels = context.getAudioChannels();
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
    public List<PluginParameter> getParameters() {
        return List.of(new PluginParameter(LEVEL, "Level", 0.0, 1.0, 0.5),
                new PluginParameter(DRIVE, "Drive", 0.0, 10.0, 2.0));
    }

    @Override
    public void setAutomatableParameter(int parameterId, double value) {
        switch (parameterId) {
            case LEVEL -> {
                if (value == REFUSED_LEVEL) {
                    throw new IllegalArgumentException("level " + value + " is not supported");
                }
                level = value;
            }
            case DRIVE -> drive = value;
            default -> { }
        }
    }

    /** The value the plugin holds for {@code parameterId}. */
    public double value(int parameterId) {
        return switch (parameterId) {
            case LEVEL -> level;
            case DRIVE -> drive;
            default -> throw new IllegalArgumentException("no parameter " + parameterId);
        };
    }

    @Override
    public Optional<AudioProcessor> asAudioProcessor() {
        return Optional.of(processor);
    }

    private final AudioProcessor processor = new AudioProcessor() {
        @Override
        public void process(float[][] input, float[][] output, int frames) {
            for (int channel = 0; channel < output.length; channel++) {
                for (int frame = 0; frame < frames; frame++) {
                    output[channel][frame] = (float) (input[channel][frame] * level);
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
    };
}
