package com.benesquivelmusic.daw.app.ui.plugin.fixtures;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Story 334 fixture: an external JAR plugin whose output is its input times
 * its {@link #LEVEL} parameter — declared through {@link #getParameters()},
 * so its insert saves the slot's parameter store by id — and which records
 * the thread that constructs each instance ({@link #CONSTRUCTED_ON}), so a
 * test can tell that a door constructed none (studio-engineer ruling R1).
 * Public top-level type with a public no-arg constructor, in the package the
 * test launch opens to {@code daw.core} for {@code ExternalPluginLoader}.
 */
public final class LevelInsertFixture implements DawPlugin {

    /** The plugin's stable id. */
    public static final String ID = "test.level";
    /** The plugin's version. */
    public static final String VERSION = "1.0";
    /** The output level, 0–1, default 0.5. */
    public static final int LEVEL = 0;

    /** The threads that constructed an instance, in order; a test clears it. */
    public static final List<Thread> CONSTRUCTED_ON = new CopyOnWriteArrayList<>();

    private volatile double level = 0.5;
    private volatile int channels = 2;

    public LevelInsertFixture() {
        CONSTRUCTED_ON.add(Thread.currentThread());
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return new PluginDescriptor(ID, "Level", VERSION, "Test", PluginType.EFFECT);
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
    }

    @Override
    public List<PluginParameter> getParameters() {
        return List.of(new PluginParameter(LEVEL, "Level", 0.0, 1.0, 0.5));
    }

    @Override
    public void setAutomatableParameter(int parameterId, double value) {
        if (parameterId == LEVEL) {
            level = value;
        }
    }

    /** The level the plugin holds. */
    public double level() {
        return level;
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
