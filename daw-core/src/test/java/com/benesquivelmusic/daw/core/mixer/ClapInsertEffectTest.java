package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.plugin.clap.ClapPluginHost;
import com.benesquivelmusic.daw.sdk.plugin.ExternalPluginFormat;
import com.benesquivelmusic.daw.sdk.plugin.ExternalPluginHost;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClapInsertEffectTest {

    @Test
    void shouldCreateWithPluginHost() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/plugins/test.clap"));
        ClapInsertEffect effect = new ClapInsertEffect(host);

        assertThat(effect.getPluginHost()).isSameAs(host);
        assertThat(effect.isFaulted()).isFalse();
    }

    @Test
    void shouldRejectNullPluginHost() {
        assertThatThrownBy(() -> new ClapInsertEffect(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("pluginHost");
    }

    @Test
    void shouldDelegateChannelCountsToHost() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/plugins/test.clap"), 0, 2, 2);
        ClapInsertEffect effect = new ClapInsertEffect(host);

        assertThat(effect.getInputChannelCount()).isEqualTo(2);
        assertThat(effect.getOutputChannelCount()).isEqualTo(2);
    }

    @Test
    void shouldDelegateProcessToHost() {
        // ClapPluginHost passes through when not processing (not activated)
        ClapPluginHost host = new ClapPluginHost(Path.of("/plugins/test.clap"));
        ClapInsertEffect effect = new ClapInsertEffect(host);

        float[][] input = {{1.0f, 0.5f, -0.5f}};
        float[][] output = {{0.0f, 0.0f, 0.0f}};
        effect.process(input, output, 3);

        // Pass-through behavior from ClapPluginHost when not processing
        assertThat(output[0]).containsExactly(1.0f, 0.5f, -0.5f);
    }

    @Test
    void shouldFallBackToPassThroughOnProcessingError() {
        ClapInsertEffect effect = new ClapInsertEffect(new ThrowingPluginHost());

        float[][] input = {{1.0f, 0.5f}};
        float[][] output = {{0.0f, 0.0f}};
        effect.process(input, output, 2);

        // Should have fallen back to pass-through
        assertThat(output[0]).containsExactly(1.0f, 0.5f);
        assertThat(effect.isFaulted()).isTrue();
    }

    @Test
    void shouldStayFaultedOnSubsequentCalls() {
        ClapInsertEffect effect = new ClapInsertEffect(new ThrowingPluginHost());

        float[][] input = {{1.0f}};
        float[][] output = {{0.0f}};

        // First call — triggers fault
        effect.process(input, output, 1);
        assertThat(effect.isFaulted()).isTrue();

        // Second call — should still pass through without calling the plugin
        float[][] input2 = {{0.75f}};
        float[][] output2 = {{0.0f}};
        effect.process(input2, output2, 1);
        assertThat(output2[0][0]).isEqualTo(0.75f);
    }

    @Test
    void shouldClearFaultOnReset() {
        ClapInsertEffect effect = new ClapInsertEffect(new ThrowingPluginHost());

        float[][] input = {{1.0f}};
        float[][] output = {{0.0f}};
        effect.process(input, output, 1);
        assertThat(effect.isFaulted()).isTrue();

        effect.reset();
        assertThat(effect.isFaulted()).isFalse();
    }

    @Test
    void shouldResetWithoutErrorOnNonFaultedPlugin() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/plugins/test.clap"));
        ClapInsertEffect effect = new ClapInsertEffect(host);

        // Should not throw
        effect.reset();
        assertThat(effect.isFaulted()).isFalse();
    }

    @Test
    void shouldHandleStereoPassThrough() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/plugins/test.clap"), 0, 2, 2);
        ClapInsertEffect effect = new ClapInsertEffect(host);

        float[][] input = {{1.0f, 0.5f}, {-1.0f, -0.5f}};
        float[][] output = {{0.0f, 0.0f}, {0.0f, 0.0f}};
        effect.process(input, output, 2);

        assertThat(output[0]).containsExactly(1.0f, 0.5f);
        assertThat(output[1]).containsExactly(-1.0f, -0.5f);
    }

    // --- Test helper: an ExternalPluginHost that throws during processing ---

    private static final class ThrowingPluginHost implements ExternalPluginHost {

        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            throw new RuntimeException("Simulated plugin crash");
        }

        @Override
        public void reset() {
        }

        @Override
        public int getInputChannelCount() {
            return 2;
        }

        @Override
        public int getOutputChannelCount() {
            return 2;
        }

        @Override
        public ExternalPluginFormat getFormat() {
            return ExternalPluginFormat.CLAP;
        }

        @Override
        public List<PluginParameter> getParameters() {
            return List.of();
        }

        @Override
        public double getParameterValue(int parameterId) {
            return 0;
        }

        @Override
        public void setParameterValue(int parameterId, double value) {
        }

        @Override
        public int getLatencySamples() {
            return 0;
        }

        @Override
        public byte[] saveState() {
            return new byte[0];
        }

        @Override
        public void loadState(byte[] state) {
        }

        @Override
        public PluginDescriptor getDescriptor() {
            return new PluginDescriptor("test.throwing", "ThrowingPlugin", "1.0", "Test", PluginType.EFFECT);
        }

        @Override
        public void initialize(PluginContext context) {
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
    }
}
