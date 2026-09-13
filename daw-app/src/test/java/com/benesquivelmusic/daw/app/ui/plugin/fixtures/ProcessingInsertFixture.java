package com.benesquivelmusic.daw.app.ui.plugin.fixtures;
import com.benesquivelmusic.daw.sdk.plugin.*;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import java.util.Optional;
public final class ProcessingInsertFixture implements DawPlugin, AudioProcessor {
        public boolean initialized;
        public volatile int disposalCount;
        @Override public PluginDescriptor getDescriptor() {
            return new PluginDescriptor("test.graph.ownership", "Graph ownership", "1", "Test", PluginType.EFFECT);
        }
        @Override public Optional<AudioProcessor> asAudioProcessor() { return Optional.of(this); }
        @Override public void initialize(PluginContext context) { initialized = true; }
        @Override public void activate() { }
        @Override public void deactivate() { }
        @Override public void dispose() { disposalCount++; }
        @Override public void process(float[][] input, float[][] output, int frames) {
            if (disposalCount != 0) throw new IllegalStateException("Disposed plugin reached render");
            for (int ch = 0; ch < output.length; ch++) System.arraycopy(input[ch], 0, output[ch], 0, frames);
        }
        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
    }
