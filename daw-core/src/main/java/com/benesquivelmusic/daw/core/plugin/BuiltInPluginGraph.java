package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.ProcessorRegistry;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;

import java.util.Objects;

/** Creates the single graph-owned instance used by every plugin entry point. */
public final class BuiltInPluginGraph {
    private final ProcessorRegistry registry;

    public BuiltInPluginGraph(ProcessorRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    public InsertSlot createSlot(BuiltInDawPlugin plugin, PluginContext context,
                                        Metronome engineMetronome) {
        Objects.requireNonNull(plugin);
        if (plugin instanceof MetronomePlugin metronome) {
            metronome.bindMetronome(Objects.requireNonNull(engineMetronome, "engine metronome"));
        }
        try {
            if (plugin instanceof MidiEffectPlugin) {
                throw new IllegalArgumentException(
                        "MIDI effects are not supported in audio insert slots: " + plugin.getDescriptor().name());
            }
            plugin.initialize(context);
            plugin.activate();
            AudioProcessor processor = plugin.asAudioProcessor()
                    .orElseGet(() -> new UtilityProcessor(context.getAudioChannels()));
            return new InsertSlot(plugin.getDescriptor().name(), processor,
                    registry.inferType(processor), plugin);
        } catch (RuntimeException | Error failure) {
            try {
                plugin.dispose();
            } catch (RuntimeException | Error disposalFailure) {
                failure.addSuppressed(disposalFailure);
            }
            throw failure;
        }
    }

    /** Utility slots occupy the graph without duplicating an engine-owned signal. */
    private record UtilityProcessor(int channels) implements AudioProcessor {
        @Override @RealTimeSafe
        public void process(float[][] input, float[][] output, int frames) {
            for (int ch = 0; ch < output.length; ch++) {
                if (ch < input.length) {
                    System.arraycopy(input[ch], 0, output[ch], 0, frames);
                } else {
                    java.util.Arrays.fill(output[ch], 0, frames, 0f);
                }
            }
        }
        @Override public void reset() { }
        @Override public int getInputChannelCount() { return channels; }
        @Override public int getOutputChannelCount() { return channels; }
    }
}
