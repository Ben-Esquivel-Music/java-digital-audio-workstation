package com.benesquivelmusic.daw.core.plugin.testjar;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.Optional;

/**
 * Minimal external DawPlugin implementation used by contract tests to verify
 * that a plugin loaded from an external JAR via {@code ExternalPluginLoader}
 * satisfies the {@link DawPlugin#asAudioProcessor()} contract.
 *
 * <p>This class is compiled into the test classpath and re-packaged into a
 * JAR at test time to simulate a real external plugin load.</p>
 */
public class ExternalEffectPlugin implements DawPlugin {

    private boolean initialized;

    public ExternalEffectPlugin() {
        // Public no-arg constructor required by ExternalPluginLoader
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return new PluginDescriptor(
                "test-ext-effect", "External Test Effect", "1.0",
                "Test", PluginType.EFFECT);
    }

    @Override
    public void initialize(PluginContext context) {
        initialized = true;
    }

    @Override
    public void activate() {}

    @Override
    public void deactivate() {}

    @Override
    public void dispose() {
        initialized = false;
    }

    @Override
    public Optional<AudioProcessor> asAudioProcessor() {
        if (!initialized) {
            return Optional.empty();
        }
        return Optional.of(new PassThroughProcessor());
    }

    /**
     * A trivial pass-through processor that copies input to output unchanged.
     */
    private static final class PassThroughProcessor implements AudioProcessor {

        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            for (int ch = 0; ch < Math.min(inputBuffer.length, outputBuffer.length); ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
        }

        @Override
        public void reset() {}

        @Override
        public int getInputChannelCount() {
            return 2;
        }

        @Override
        public int getOutputChannelCount() {
            return 2;
        }
    }
}
