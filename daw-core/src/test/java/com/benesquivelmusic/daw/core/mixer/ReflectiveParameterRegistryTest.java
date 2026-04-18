package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.dsp.AnalogDistortionProcessor;
import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReflectiveParameterRegistryTest {

    private static final double EPS = 1e-9;

    @Test
    void shouldDiscoverAllAnnotatedParametersOnCompressor() {
        List<PluginParameter> params =
                ReflectiveParameterRegistry.getParameterDescriptors(CompressorProcessor.class);
        // Compressor has 6 annotated parameters (Threshold, Ratio, Attack,
        // Release, Knee, Makeup Gain).
        assertThat(params).hasSize(6);
        assertThat(params.get(0).id()).isEqualTo(0);
        assertThat(params.get(0).name()).isEqualTo("Threshold (dB)");
        assertThat(params.get(0).minValue()).isEqualTo(-60.0);
        assertThat(params.get(0).maxValue()).isEqualTo(0.0);
        assertThat(params.get(0).defaultValue()).isEqualTo(-20.0);
    }

    @Test
    void shouldReportNoParametersForUnannotatedClass() {
        assertThat(ReflectiveParameterRegistry.hasAnnotatedParameters(Unannotated.class))
                .isFalse();
        assertThat(ReflectiveParameterRegistry.getParameterDescriptors(Unannotated.class))
                .isEmpty();
    }

    @Test
    void setterViaRegistryShouldMatchDirectSetter() {
        var p1 = new CompressorProcessor(2, 48_000.0);
        var p2 = new CompressorProcessor(2, 48_000.0);

        BiConsumer<Integer, Double> handler =
                ReflectiveParameterRegistry.createParameterHandler(p1);
        handler.accept(0, -12.5);   // Threshold
        handler.accept(1, 8.0);     // Ratio
        handler.accept(5, 6.0);     // Makeup Gain

        p2.setThresholdDb(-12.5);
        p2.setRatio(8.0);
        p2.setMakeupGainDb(6.0);

        assertThat(p1.getThresholdDb()).isEqualTo(p2.getThresholdDb());
        assertThat(p1.getRatio()).isEqualTo(p2.getRatio());
        assertThat(p1.getMakeupGainDb()).isEqualTo(p2.getMakeupGainDb());
    }

    @Test
    void shouldRoundTripValuesThroughRegistry() {
        var processor = new AnalogDistortionProcessor(2, 48_000.0);
        BiConsumer<Integer, Double> handler =
                ReflectiveParameterRegistry.createParameterHandler(processor);

        handler.accept(0, 18.0);     // Drive dB
        handler.accept(1, -0.25);    // Tone
        handler.accept(4, 3.5);      // Output Level dB

        Map<Integer, Double> values =
                ReflectiveParameterRegistry.getParameterValues(processor);

        assertThat(values.get(0)).isCloseTo(18.0, org.assertj.core.data.Offset.offset(EPS));
        assertThat(values.get(1)).isCloseTo(-0.25, org.assertj.core.data.Offset.offset(EPS));
        assertThat(values.get(4)).isCloseTo(3.5, org.assertj.core.data.Offset.offset(EPS));
    }

    @Test
    void unknownParameterIdShouldBeIgnored() {
        var processor = new CompressorProcessor(2, 48_000.0);
        BiConsumer<Integer, Double> handler =
                ReflectiveParameterRegistry.createParameterHandler(processor);
        // Must not throw
        handler.accept(999, 42.0);
    }

    @Test
    void cacheShouldReturnSameDescriptorsAcrossCalls() {
        List<PluginParameter> first =
                ReflectiveParameterRegistry.getParameterDescriptors(CompressorProcessor.class);
        List<PluginParameter> second =
                ReflectiveParameterRegistry.getParameterDescriptors(CompressorProcessor.class);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void duplicateIdsShouldBeRejected() {
        assertThatThrownBy(() ->
                ReflectiveParameterRegistry.getParameterDescriptors(DuplicateIds.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate @ProcessorParam id");
    }

    @Test
    void missingSetterShouldBeRejected() {
        assertThatThrownBy(() ->
                ReflectiveParameterRegistry.getParameterDescriptors(MissingSetter.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No matching setter");
    }

    @Test
    void insertEffectFactoryShouldFallBackForUnannotatedType() {
        // PARAMETRIC_EQ has no @ProcessorParam-annotated doubles, so the
        // factory must fall back to the switch-based logic.
        List<PluginParameter> params =
                InsertEffectFactory.getParameterDescriptors(InsertEffectType.PARAMETRIC_EQ);
        assertThat(params).isEmpty();
    }

    // ── Helper classes ──────────────────────────────────────────────────────

    /** Class with no {@link ProcessorParam} annotations. */
    public static final class Unannotated {
        public double getSomething() { return 0.0; }
        public void setSomething(double value) { /* no-op */ }
    }

    /** Class with duplicate parameter ids (should trigger validation). */
    public static final class DuplicateIds {
        private double a;
        private double b;

        @ProcessorParam(id = 0, name = "A", min = 0.0, max = 1.0, defaultValue = 0.0)
        public double getA() { return a; }
        public void setA(double a) { this.a = a; }

        @ProcessorParam(id = 0, name = "B", min = 0.0, max = 1.0, defaultValue = 0.0)
        public double getB() { return b; }
        public void setB(double b) { this.b = b; }
    }

    /** Class annotated with no matching setter. */
    public static final class MissingSetter {
        @ProcessorParam(id = 0, name = "X", min = 0.0, max = 1.0, defaultValue = 0.0)
        public double getX() { return 0.0; }
        // intentionally no setX(double)
    }
}
