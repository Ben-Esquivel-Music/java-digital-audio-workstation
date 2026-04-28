package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.mastering.DitherProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DitherPluginTest {

    @Test
    void shouldHavePublicNoArgConstructor() {
        assertThat(new DitherPlugin()).isNotNull();
    }

    @Test
    void shouldReturnMenuLabel() {
        assertThat(new DitherPlugin().getMenuLabel()).isEqualTo("Dither");
    }

    @Test
    void shouldReturnMasteringCategory() {
        assertThat(new DitherPlugin().getCategory())
                .isEqualTo(BuiltInPluginCategory.MASTERING);
    }

    @Test
    void shouldBeAnnotatedAsTerminal() {
        BuiltInPlugin meta = DitherPlugin.class.getAnnotation(BuiltInPlugin.class);
        assertThat(meta).isNotNull();
        assertThat(meta.terminal())
                .as("DitherPlugin must be terminal — last stage of the mastering chain")
                .isTrue();
    }

    @Test
    void shouldReturnDescriptorWithEffectType() {
        var d = new DitherPlugin().getDescriptor();
        assertThat(d.type()).isEqualTo(PluginType.EFFECT);
        assertThat(d.name()).isEqualTo("Dither");
        assertThat(d.id()).isEqualTo(DitherPlugin.PLUGIN_ID);
        assertThat(d.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void shouldImplementDawPluginLifecycle() {
        var plugin = new DitherPlugin();
        plugin.initialize(stubContext());
        plugin.activate();
        assertThat(plugin.isActive()).isTrue();
        plugin.deactivate();
        assertThat(plugin.isActive()).isFalse();
        plugin.dispose();
    }

    @Test
    void shouldReturnProcessorAfterInitialize() {
        var plugin = new DitherPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.getProcessor()).isInstanceOf(DitherProcessor.class);
        assertThat(plugin.asAudioProcessor()).isPresent();
        assertThat(plugin.asAudioProcessor().orElseThrow()).isSameAs(plugin.getProcessor());
        // Default config: 16-bit, TPDF, flat shape.
        assertThat(plugin.getProcessor().getTargetBitDepth()).isEqualTo(16);
        assertThat(plugin.getProcessor().getType()).isEqualTo(DitherProcessor.DitherType.TPDF);
        assertThat(plugin.getProcessor().getShape()).isEqualTo(DitherProcessor.NoiseShape.FLAT);
    }

    @Test
    void shouldClearProcessorOnDispose() {
        var plugin = new DitherPlugin();
        plugin.initialize(stubContext());
        plugin.dispose();
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void shouldExposeBitDepthTypeShapeParameters() {
        var plugin = new DitherPlugin();
        assertThat(plugin.getParameters()).hasSize(3);
        assertThat(plugin.getParameters().stream().map(p -> p.name()))
                .containsExactly("Bit Depth", "Type", "Shape");
    }

    @Test
    void shouldHaveDistinctIdFromOtherMasteringPlugins() {
        assertThat(DitherPlugin.PLUGIN_ID)
                .isNotEqualTo(TruePeakLimiterPlugin.PLUGIN_ID);
    }

    @Test
    void shouldAppearInBuiltInDawPluginPermittedSet() {
        var classes = java.util.Arrays.stream(BuiltInDawPlugin.class.getPermittedSubclasses())
                .toList();
        assertThat(classes).contains(DitherPlugin.class);
    }

    @Test
    void shouldRouteAutomationValuesToProcessor() {
        var plugin = new DitherPlugin();
        plugin.initialize(stubContext());
        // Bit Depth — snapped to 16/20/24
        plugin.setAutomatableParameter(0, 24.0);
        assertThat(plugin.getProcessor().getTargetBitDepth()).isEqualTo(24);
        plugin.setAutomatableParameter(0, 21.0);
        assertThat(plugin.getProcessor().getTargetBitDepth()).isEqualTo(20);
        plugin.setAutomatableParameter(0, 17.0);
        assertThat(plugin.getProcessor().getTargetBitDepth()).isEqualTo(16);

        // Type — RPDF=1
        plugin.setAutomatableParameter(1, 1.0);
        assertThat(plugin.getProcessor().getType()).isEqualTo(DitherProcessor.DitherType.RPDF);
        // Type — NOISE_SHAPED=3
        plugin.setAutomatableParameter(1, 3.0);
        assertThat(plugin.getProcessor().getType())
                .isEqualTo(DitherProcessor.DitherType.NOISE_SHAPED);

        // Shape — WEIGHTED=1
        plugin.setAutomatableParameter(2, 1.0);
        assertThat(plugin.getProcessor().getShape())
                .isEqualTo(DitherProcessor.NoiseShape.WEIGHTED);
    }

    @Test
    void shouldClampOutOfRangeAutomationValues() {
        var plugin = new DitherPlugin();
        plugin.initialize(stubContext());
        plugin.setAutomatableParameter(1, 999.0); // type
        plugin.setAutomatableParameter(2, -5.0);  // shape
        // No exception — values clamped to nearest valid enum.
        assertThat(plugin.getProcessor().getType()).isNotNull();
        assertThat(plugin.getProcessor().getShape()).isNotNull();
    }

    @Test
    void setAutomatableParameterShouldBeNoOpBeforeInitialize() {
        new DitherPlugin().setAutomatableParameter(0, 24.0);
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 48_000; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }
}
