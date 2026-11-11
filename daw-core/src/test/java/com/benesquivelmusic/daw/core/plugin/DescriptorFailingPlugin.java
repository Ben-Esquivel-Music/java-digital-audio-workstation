package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.mixer.PassThroughProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * An external JAR plugin whose {@link #getDescriptor()} a test can make throw
 * an {@link AssertionError} — the plugin's own code failing with an error
 * that is not a {@link LinkageError} — or another error it names, and which
 * counts the instances disposed. Public, with a public no-argument
 * constructor, as {@link ExternalPluginLoader} needs; it passes audio
 * through.
 */
public final class DescriptorFailingPlugin implements DawPlugin {

    /** The plugin's stable id. */
    public static final String ID = "com.example.failing";
    /** The plugin's version. */
    public static final String VERSION = "1.0";

    /** What {@link #getDescriptor()} throws; {@code null} while it describes normally. */
    private static volatile Supplier<? extends Error> failure;
    private static final AtomicInteger DISPOSALS = new AtomicInteger();

    private int channels = 2;

    /** Makes every {@link #getDescriptor()} from now on throw an {@link AssertionError}. */
    public static void failFromNowOn() {
        failFromNowOn(() -> new AssertionError("the plugin cannot describe itself"));
    }

    /** Makes every {@link #getDescriptor()} from now on throw what {@code error} makes. */
    public static void failFromNowOn(Supplier<? extends Error> error) {
        failure = Objects.requireNonNull(error, "error must not be null");
    }

    /** Describes normally again and forgets the disposals counted so far. */
    public static void reset() {
        failure = null;
        DISPOSALS.set(0);
    }

    /** How many instances were disposed since {@link #reset()}. */
    public static int disposals() {
        return DISPOSALS.get();
    }

    @Override
    public PluginDescriptor getDescriptor() {
        Supplier<? extends Error> thrown = failure;
        if (thrown != null) {
            throw thrown.get();
        }
        return new PluginDescriptor(ID, "Failing Gain", VERSION, "Example Audio", PluginType.EFFECT);
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
        DISPOSALS.incrementAndGet();
    }

    @Override
    public Optional<AudioProcessor> asAudioProcessor() {
        return Optional.of(new PassThroughProcessor(channels));
    }
}
