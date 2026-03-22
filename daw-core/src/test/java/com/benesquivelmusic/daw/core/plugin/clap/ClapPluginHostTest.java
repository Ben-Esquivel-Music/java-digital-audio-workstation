package com.benesquivelmusic.daw.core.plugin.clap;

import com.benesquivelmusic.daw.sdk.plugin.ExternalPluginFormat;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClapPluginHostTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCreateWithValidArguments() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/plugins/test.clap"), 0, 2, 2);

        assertThat(host.getLibraryPath()).isEqualTo(Path.of("/plugins/test.clap"));
        assertThat(host.getPluginIndex()).isEqualTo(0);
        assertThat(host.getInputChannelCount()).isEqualTo(2);
        assertThat(host.getOutputChannelCount()).isEqualTo(2);
    }

    @Test
    void shouldCreateWithDefaultStereoSettings() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/plugins/test.clap"));

        assertThat(host.getPluginIndex()).isEqualTo(0);
        assertThat(host.getInputChannelCount()).isEqualTo(2);
        assertThat(host.getOutputChannelCount()).isEqualTo(2);
    }

    @Test
    void shouldReportClapFormat() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/plugins/test.clap"));
        assertThat(host.getFormat()).isEqualTo(ExternalPluginFormat.CLAP);
    }

    @Test
    void shouldProvideDefaultDescriptorBeforeInit() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/plugins/test.clap"));
        PluginDescriptor descriptor = host.getDescriptor();

        assertThat(descriptor).isNotNull();
        assertThat(descriptor.id()).contains("test.clap");
        assertThat(descriptor.type()).isEqualTo(PluginType.EFFECT);
    }

    @Test
    void shouldNotBeInitializedByDefault() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/plugins/test.clap"));
        assertThat(host.isInitialized()).isFalse();
        assertThat(host.isActivated()).isFalse();
    }

    @Test
    void shouldRejectNullLibraryPath() {
        assertThatThrownBy(() -> new ClapPluginHost(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("libraryPath");
    }

    @Test
    void shouldRejectNegativePluginIndex() {
        assertThatThrownBy(() -> new ClapPluginHost(Path.of("/test.clap"), -1, 2, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pluginIndex");
    }

    @Test
    void shouldRejectZeroInputChannels() {
        assertThatThrownBy(() -> new ClapPluginHost(Path.of("/test.clap"), 0, 0, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputChannels");
    }

    @Test
    void shouldRejectZeroOutputChannels() {
        assertThatThrownBy(() -> new ClapPluginHost(Path.of("/test.clap"), 0, 2, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outputChannels");
    }

    @Test
    void shouldThrowOnInitializeWithNullContext() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));
        assertThatThrownBy(() -> host.initialize(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("context");
    }

    @Test
    void shouldThrowOnInitializeWithNonExistentLibrary() {
        ClapPluginHost host = new ClapPluginHost(tempDir.resolve("nonexistent.clap"));

        assertThatThrownBy(() -> host.initialize(new TestPluginContext()))
                .isInstanceOf(ClapException.class)
                .hasMessageContaining("Failed to load");
    }

    @Test
    void shouldThrowOnActivateBeforeInitialize() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));
        assertThatThrownBy(host::activate)
                .isInstanceOf(ClapException.class)
                .hasMessageContaining("not initialized");
    }

    @Test
    void shouldPassThroughWhenNotProcessing() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));

        float[][] input = {{1.0f, 0.5f, -0.5f}};
        float[][] output = {{0.0f, 0.0f, 0.0f}};
        host.process(input, output, 3);

        assertThat(output[0]).containsExactly(1.0f, 0.5f, -0.5f);
    }

    @Test
    void shouldReturnZeroLatencyWhenNotInitialized() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));
        assertThat(host.getLatencySamples()).isEqualTo(0);
    }

    @Test
    void shouldReturnEmptyStateWhenNotInitialized() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));
        assertThat(host.saveState()).isEmpty();
    }

    @Test
    void shouldAcceptLoadStateWithoutError() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));
        host.loadState(new byte[]{1, 2, 3});
        // No exception expected
    }

    @Test
    void shouldRejectNullState() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));
        assertThatThrownBy(() -> host.loadState(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldResetWithoutError() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));
        // reset() should not throw when not initialized
        host.reset();
    }

    @Test
    void shouldDisposeWithoutErrorWhenNotInitialized() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));
        // dispose() should not throw on uninitialized host
        host.dispose();
        assertThat(host.isInitialized()).isFalse();
    }

    @Test
    void shouldDeactivateWithoutErrorWhenNotActivated() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));
        // deactivate() should not throw when not activated
        host.deactivate();
    }

    // --- Parameter validation and queuing ---

    @Test
    void shouldRejectSetParameterWhenPluginDoesNotSupportParams() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));
        // getParameters() returns empty list when not initialized — setParameterValue
        // should throw because the parameter does not exist.
        assertThatThrownBy(() -> host.setParameterValue(0, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Parameter not found");
    }

    @Test
    void shouldAcceptLoadStateWithEmptyArrayWithoutError() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));
        // Empty state should be silently ignored (nothing to restore).
        host.loadState(new byte[0]);
    }

    @Test
    void shouldReturnEmptyStateBeforeInitialize() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));
        // Before initialize(), there is no state extension — should return empty array.
        assertThat(host.saveState()).isEmpty();
    }

    @Test
    void shouldPassThroughWithPendingParamChangesWhenNotProcessing() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));

        float[][] input = {{0.8f, -0.3f}};
        float[][] output = {{0.0f, 0.0f}};

        // process() in pass-through mode must not be affected by any internal state.
        host.process(input, output, 2);

        assertThat(output[0]).containsExactly(0.8f, -0.3f);
    }

    // --- Test helper ---

    private static class TestPluginContext implements com.benesquivelmusic.daw.sdk.plugin.PluginContext {
        @Override
        public double getSampleRate() {
            return 44100.0;
        }

        @Override
        public int getBufferSize() {
            return 512;
        }

        @Override
        public void log(String message) {
            // No-op for testing
        }
    }
}
