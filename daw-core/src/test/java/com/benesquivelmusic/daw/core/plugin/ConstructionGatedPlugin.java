package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * An external JAR plugin whose construction a test can hold: the startup
 * restore that checks it, or an open that resolves it, waits in its
 * constructor until the test releases it. It records the thread of every
 * construction. Public, with a public no-argument constructor, as
 * {@link ExternalPluginLoader} needs; its output is half its input.
 */
public final class ConstructionGatedPlugin implements DawPlugin {

    /** The plugin's stable id. */
    public static final String ID = "com.example.gated";
    /** The plugin's name, which its insert shows. */
    public static final String NAME = "Gated Gain";
    /** The plugin's version. */
    public static final String VERSION = "1.0";

    /** How long a held construction waits at most, so a failed test never leaves a thread waiting for good. */
    private static final long HOLD_LIMIT_SECONDS = 60;

    private static volatile CountDownLatch gate = new CountDownLatch(0);
    private static final List<String> CONSTRUCTING_THREADS = new CopyOnWriteArrayList<>();

    private int channels = 2;

    /** Constructs the plugin once the gate is open. */
    public ConstructionGatedPlugin() {
        CONSTRUCTING_THREADS.add(Thread.currentThread().getName());
        boolean interrupted = false;
        while (true) {
            try {
                gate.await(HOLD_LIMIT_SECONDS, TimeUnit.SECONDS);
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Holds every construction from now until {@link #release()}. */
    public static void hold() {
        gate = new CountDownLatch(1);
    }

    /** Lets every held and later construction finish. */
    public static void release() {
        gate.countDown();
    }

    /** The names of the threads that constructed the plugin since {@link #forgetConstructions()}. */
    public static List<String> constructingThreads() {
        return List.copyOf(CONSTRUCTING_THREADS);
    }

    /** Forgets the constructions recorded so far. */
    public static void forgetConstructions() {
        CONSTRUCTING_THREADS.clear();
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return new PluginDescriptor(ID, NAME, VERSION, "Example Audio", PluginType.EFFECT);
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
    public Optional<AudioProcessor> asAudioProcessor() {
        return Optional.of(processor);
    }

    private final AudioProcessor processor = new AudioProcessor() {
        @Override
        public void process(float[][] input, float[][] output, int frames) {
            for (int channel = 0; channel < output.length; channel++) {
                for (int frame = 0; frame < frames; frame++) {
                    output[channel][frame] = input[channel][frame] * 0.5f;
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
