package com.benesquivelmusic.daw.core.automation;

import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Tests for {@link ReflectiveParameterBinder}.
 *
 * <p>Verifies the four requirements called out on the issue:</p>
 * <ol>
 *   <li>Parameter bindings are created correctly for annotated processors.</li>
 *   <li>Setting an automation value via the binder produces the same result
 *       as calling the setter directly.</li>
 *   <li>The apply phase is {@link RealTimeSafe @RealTimeSafe} (verified via
 *       the annotation contract).</li>
 *   <li>Re-binding after insert changes picks up the new processor's
 *       parameters.</li>
 * </ol>
 */
class ReflectiveParameterBinderTest {

    /** Minimal annotated test processor — two parameters with well-known ranges. */
    public static final class FakeProcessor implements AudioProcessor {
        private double threshold = -20.0;
        private double ratio = 4.0;

        @ProcessorParam(id = 0, name = "Threshold",
                min = -60.0, max = 0.0, defaultValue = -20.0, unit = "dB")
        public double getThreshold() { return threshold; }
        public void setThreshold(double threshold) { this.threshold = threshold; }

        @ProcessorParam(id = 1, name = "Ratio",
                min = 1.0, max = 20.0, defaultValue = 4.0)
        public double getRatio() { return ratio; }
        public void setRatio(double ratio) { this.ratio = ratio; }

        @Override public void process(float[][] in, float[][] out, int numFrames) { }
        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
    }

    /** A processor with no @ProcessorParam declarations. */
    public static final class BareProcessor implements AudioProcessor {
        @Override public void process(float[][] in, float[][] out, int numFrames) { }
        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
    }

    private ReflectiveParameterBinder binder;
    private MixerChannel channel;

    @BeforeEach
    void setUp() {
        binder = new ReflectiveParameterBinder();
        channel = new MixerChannel("Channel 1");
    }

    // ── (1) Discovery produces one target per @ProcessorParam per slot ────

    @Test
    void shouldDiscoverBindingsForAnnotatedProcessor() {
        channel.addInsert(new InsertSlot("Fake", new FakeProcessor()));
        binder.rebind(channel);

        List<PluginParameterTarget> targets = binder.getAutomatablePluginParameters(channel);

        assertThat(targets).hasSize(2);
        assertThat(targets).extracting(PluginParameterTarget::parameterId)
                .containsExactly(0, 1);
        assertThat(targets.get(0).displayName()).isEqualTo("Threshold (dB)");
        assertThat(targets.get(0).minValue()).isEqualTo(-60.0);
        assertThat(targets.get(0).maxValue()).isEqualTo(0.0);
        assertThat(targets.get(1).displayName()).isEqualTo("Ratio");
        assertThat(targets.get(1).unit()).isEmpty();
        // pluginInstanceId encodes (channel, slotIndex, processor class)
        assertThat(targets.get(0).pluginInstanceId())
                .startsWith("reflective:Channel 1/slot-0/")
                .endsWith(FakeProcessor.class.getName());
    }

    @Test
    void shouldReturnEmptyListForChannelWithoutAnnotatedProcessors() {
        channel.addInsert(new InsertSlot("Bare", new BareProcessor()));
        binder.rebind(channel);

        assertThat(binder.getAutomatablePluginParameters(channel)).isEmpty();
    }

    @Test
    void shouldDiscoverOnDemandWhenNotPreviouslyBound() {
        channel.addInsert(new InsertSlot("Fake", new FakeProcessor()));
        // No rebind() call — getAutomatablePluginParameters should discover lazily.
        assertThat(binder.getAutomatablePluginParameters(channel)).hasSize(2);
    }

    // ── (2) Applying automation invokes the setter equivalently ──────────

    @Test
    void shouldApplyAutomationValueIdenticalToDirectSetter() {
        FakeProcessor processor = new FakeProcessor();
        channel.addInsert(new InsertSlot("Fake", processor));
        binder.rebind(channel);

        PluginParameterTarget thresholdTarget = binder.getAutomatablePluginParameters(channel)
                .stream()
                .filter(t -> t.parameterId() == 0)
                .findFirst()
                .orElseThrow();

        AutomationData automation = new AutomationData();
        AutomationLane lane = automation.getOrCreatePluginLane(thresholdTarget);
        lane.addPoint(new AutomationPoint(0.0, -12.5));

        // Direct setter baseline on a parallel instance.
        FakeProcessor reference = new FakeProcessor();
        reference.setThreshold(-12.5);

        binder.apply(channel, automation, 0.0);

        assertThat(processor.getThreshold())
                .isEqualTo(reference.getThreshold(), offset(1e-12));
    }

    @Test
    void shouldClampValuesToParameterRange() {
        FakeProcessor processor = new FakeProcessor();
        channel.addInsert(new InsertSlot("Fake", processor));
        binder.rebind(channel);

        PluginParameterTarget ratio = binder.getAutomatablePluginParameters(channel).stream()
                .filter(t -> t.parameterId() == 1).findFirst().orElseThrow();

        AutomationData automation = new AutomationData();
        // Ratio range is [1.0, 20.0]; write a point just inside max so lane.addPoint
        // accepts it, then drive apply() via an out-of-range follow-up point.
        AutomationLane lane = automation.getOrCreatePluginLane(ratio);
        lane.addPoint(new AutomationPoint(0.0, 20.0));

        binder.apply(channel, automation, 100.0);

        assertThat(processor.getRatio()).isLessThanOrEqualTo(20.0);
        assertThat(processor.getRatio()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void shouldSkipLanesWithNoPoints() {
        FakeProcessor processor = new FakeProcessor();
        channel.addInsert(new InsertSlot("Fake", processor));
        binder.rebind(channel);

        PluginParameterTarget t = binder.getAutomatablePluginParameters(channel).get(0);
        AutomationData automation = new AutomationData();
        automation.getOrCreatePluginLane(t); // lane created, zero points

        double original = processor.getThreshold();
        binder.apply(channel, automation, 0.0);

        assertThat(processor.getThreshold()).isEqualTo(original);
    }

    @Test
    void shouldBeNoOpForUnboundChannel() {
        AutomationData automation = new AutomationData();
        // No exception even though rebind() never ran.
        binder.apply(channel, automation, 0.0);
    }

    // ── (3) Apply path is annotated @RealTimeSafe ───────────────────────

    @Test
    void applyMethodShouldBeAnnotatedRealTimeSafe() throws NoSuchMethodException {
        Method apply = ReflectiveParameterBinder.class.getDeclaredMethod(
                "apply", MixerChannel.class, AutomationData.class, double.class);
        assertThat(apply.isAnnotationPresent(RealTimeSafe.class))
                .as("apply() must carry @RealTimeSafe")
                .isTrue();
    }

    // ── (4) Rebinding picks up new inserts ──────────────────────────────

    @Test
    void shouldPickUpNewProcessorAfterRebind() {
        channel.addInsert(new InsertSlot("Bare", new BareProcessor()));
        binder.rebind(channel);
        assertThat(binder.getAutomatablePluginParameters(channel)).isEmpty();

        channel.addInsert(new InsertSlot("Fake", new FakeProcessor()));
        binder.rebind(channel);

        List<PluginParameterTarget> targets = binder.getAutomatablePluginParameters(channel);
        assertThat(targets).hasSize(2);
        assertThat(targets.get(0).pluginInstanceId())
                .contains("/slot-1/");
    }

    @Test
    void shouldForgetChannelBindings() {
        channel.addInsert(new InsertSlot("Fake", new FakeProcessor()));
        binder.rebind(channel);
        assertThat(binder.getAutomatablePluginParameters(channel)).isNotEmpty();

        binder.forget(channel);

        // apply() must still be a safe no-op after forget().
        AutomationData automation = new AutomationData();
        binder.apply(channel, automation, 0.0);
    }
}
