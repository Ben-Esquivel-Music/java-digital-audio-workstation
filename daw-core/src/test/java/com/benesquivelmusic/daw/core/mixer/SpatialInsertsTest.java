package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.plugin.PluginCapabilities;
import com.benesquivelmusic.daw.core.plugin.PluginCapabilityIntrospector;
import com.benesquivelmusic.daw.core.plugin.ProcessorCapabilities;
import com.benesquivelmusic.daw.core.spatial.AirAbsorptionFilter;
import com.benesquivelmusic.daw.core.spatial.AmbienceUpmixer;
import com.benesquivelmusic.daw.core.spatial.ambisonics.AFormatConverter;
import com.benesquivelmusic.daw.core.spatial.ambisonics.AmbisonicBinauralDecoder;
import com.benesquivelmusic.daw.core.spatial.ambisonics.AmbisonicDecoder;
import com.benesquivelmusic.daw.core.spatial.ambisonics.AmbisonicEncoder;
import com.benesquivelmusic.daw.core.spatial.ambisonics.AmbisonicEnhancer;
import com.benesquivelmusic.daw.core.spatial.ambisonics.AmbisonicRotator;
import com.benesquivelmusic.daw.core.spatial.ambisonics.AsdmProcessor;
import com.benesquivelmusic.daw.core.spatial.binaural.BinauralExternalizationProcessor;
import com.benesquivelmusic.daw.core.spatial.binaural.BinauralMonitoringProcessor;
import com.benesquivelmusic.daw.core.spatial.binaural.DefaultBinauralRenderer;
import com.benesquivelmusic.daw.core.spatial.binaural.StereoToBinauralConverter;
import com.benesquivelmusic.daw.core.spatial.panner.PanningTableSynthesizer;
import com.benesquivelmusic.daw.core.spatial.panner.VbapPanner;
import com.benesquivelmusic.daw.core.spatial.room.DirectionalFdnProcessor;
import com.benesquivelmusic.daw.core.spatial.room.FdnRoomSimulator;
import com.benesquivelmusic.daw.core.testsupport.ModuleClassScanner;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.spatial.AmbisonicOrder;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Story 322 — every concrete top-level {@link AudioProcessor} under
 * {@code core.spatial} carries
 * {@code @ProcessorCapability(}{@link ProcessorCapabilities#SPATIAL}{@code )}
 * (the first production use of the tag), pinned by a package scan so a new
 * one cannot be silently left out; and
 * {@link SpatialInserts#hasSpatialNode} reads the tag through the cached
 * introspector to answer whether a channel's chain contains a spatial node
 * — the fact the 3D-panner affordance is gated on (Audio Engine Wiring
 * Design Book §5.6 "3D panner button").
 */
class SpatialInsertsTest {

    private static final String SPATIAL_PACKAGE = "com.benesquivelmusic.daw.core.spatial";

    private static final List<Class<? extends AudioProcessor>> SPATIAL_PROCESSORS = List.of(
            AFormatConverter.class,
            AirAbsorptionFilter.class,
            AmbienceUpmixer.class,
            AmbisonicBinauralDecoder.class,
            AmbisonicDecoder.class,
            AmbisonicEncoder.class,
            AmbisonicEnhancer.class,
            AmbisonicRotator.class,
            AsdmProcessor.class,
            BinauralExternalizationProcessor.class,
            BinauralMonitoringProcessor.class,
            DefaultBinauralRenderer.class,
            DirectionalFdnProcessor.class,
            FdnRoomSimulator.class,
            PanningTableSynthesizer.class,
            StereoToBinauralConverter.class,
            VbapPanner.class);

    @Test
    void everyListedSpatialProcessorCarriesTheSpatialTagThroughTheIntrospector() {
        for (Class<? extends AudioProcessor> cls : SPATIAL_PROCESSORS) {
            PluginCapabilities caps = PluginCapabilityIntrospector.capabilitiesOf(cls);
            assertThat(caps.customCapabilities())
                    .as("%s declares @ProcessorCapability(SPATIAL)", cls.getSimpleName())
                    .contains(ProcessorCapabilities.SPATIAL);
            assertThat(caps.hasCustomCapability(ProcessorCapabilities.SPATIAL)).isTrue();
        }
    }

    /**
     * The tag check above only covers the classes listed in
     * {@link #SPATIAL_PROCESSORS}; this pins that list to what actually lives
     * under {@code core.spatial}, so a processor added there fails loudly
     * until it is listed (and therefore tag-checked) instead of silently
     * leaving its channel without the 3D-panner affordance.
     */
    @Test
    void tagCheckedListIsExactlyTheConcreteProcessorsUnderCoreSpatial() {
        Set<Class<?>> discovered = concreteAudioProcessorsUnder(SPATIAL_PACKAGE);
        Set<Class<?>> listed = Set.copyOf(SPATIAL_PROCESSORS);

        assertThat(discovered)
                .as("non-vacuity: the scan of %s must find a known spatial processor", SPATIAL_PACKAGE)
                .contains(AmbisonicEncoder.class);
        assertThat(namesOfClassesOnlyIn(discovered, listed))
                .as("concrete AudioProcessors under %s missing from SPATIAL_PROCESSORS: tag each "
                        + "with @ProcessorCapability(ProcessorCapabilities.SPATIAL) and list it here, "
                        + "or, if one is genuinely not a spatial node, add an explicit exclusion "
                        + "to this test", SPATIAL_PACKAGE)
                .isEmpty();
        assertThat(namesOfClassesOnlyIn(listed, discovered))
                .as("SPATIAL_PROCESSORS entries that are not concrete AudioProcessors under %s",
                        SPATIAL_PACKAGE)
                .isEmpty();
    }

    @Test
    void nonSpatialProcessorsDoNotCarryTheTag() {
        assertThat(PluginCapabilityIntrospector.capabilitiesOf(CompressorProcessor.class)
                .customCapabilities()).doesNotContain(ProcessorCapabilities.SPATIAL);
        assertThat(PluginCapabilityIntrospector.capabilitiesOf(Passthrough.class)
                .customCapabilities()).isEmpty();
        assertThat(SpatialInserts.isSpatial(new Passthrough())).isFalse();
        assertThat(SpatialInserts.isSpatial(null)).isFalse();
    }

    @Test
    void vocabularyConstantIsTheLiteralTheAnnotationsUse() {
        assertThat(ProcessorCapabilities.SPATIAL).isEqualTo("spatial");
    }

    @Test
    void channelWithoutInsertsOrWithOnlyNonSpatialInsertsHasNoSpatialNode() {
        MixerChannel channel = new MixerChannel("Vox");
        assertThat(SpatialInserts.hasSpatialNode(channel)).isFalse();

        channel.addInsert(new InsertSlot("Pass", new Passthrough()));
        channel.addInsert(new InsertSlot("Comp", new CompressorProcessor(2, 44_100.0)));

        assertThat(SpatialInserts.hasSpatialNode(channel)).isFalse();
    }

    @Test
    void channelGainsAndLosesItsSpatialNodeWithTheInsertAndBypassDoesNotHideIt() {
        MixerChannel channel = new MixerChannel("Vox");
        channel.addInsert(new InsertSlot("Pass", new Passthrough()));
        InsertSlot encoder = new InsertSlot("Encoder", new AmbisonicEncoder(AmbisonicOrder.FIRST));

        channel.addInsert(encoder);
        assertThat(SpatialInserts.hasSpatialNode(channel)).isTrue();

        channel.setInsertBypassed(1, true);
        assertThat(SpatialInserts.hasSpatialNode(channel))
                .as("a bypassed spatial node still exists in the chain")
                .isTrue();

        channel.removeInsert(encoder);
        assertThat(SpatialInserts.hasSpatialNode(channel)).isFalse();
    }

    @Test
    void channelWhoseChainHoldsTheDirectionalFdnReverbHasASpatialNode() {
        DirectionalFdnProcessor fdn = new DirectionalFdnProcessor(48_000, 8.0, 1.5, 0.3, 1.0);
        MixerChannel channel = new MixerChannel("Room");
        channel.addInsert(new InsertSlot("Pass", new Passthrough()));
        channel.addInsert(new InsertSlot("Directional FDN", fdn));

        assertThat(SpatialInserts.hasSpatialNode(channel))
                .as("a chain holding a DirectionalFdnProcessor has a spatial node")
                .isTrue();
        assertThat(SpatialInserts.isSpatial(fdn))
                .as("DirectionalFdnProcessor (first-order Ambisonic output) is a spatial node")
                .isTrue();
    }

    @Test
    void rejectsNullChannel() {
        assertThatNullPointerException().isThrownBy(() -> SpatialInserts.hasSpatialNode(null));
    }

    private static Set<Class<?>> concreteAudioProcessorsUnder(String packagePrefix) {
        ClassLoader loader = SpatialInsertsTest.class.getClassLoader();
        return ModuleClassScanner.classNamesUnder(packagePrefix).stream()
                .map(className -> loadWithoutInitializing(className, loader))
                .filter(SpatialInsertsTest::isConcreteAudioProcessor)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Loads without running static initializers; a load failure fails the test, never skips. */
    private static Class<?> loadWithoutInitializing(String className, ClassLoader loader) {
        try {
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new AssertionError(
                    "class under " + SPATIAL_PACKAGE + " failed to load: " + className, e);
        }
    }

    private static boolean isConcreteAudioProcessor(Class<?> cls) {
        return AudioProcessor.class.isAssignableFrom(cls)
                && !cls.isInterface()
                && !Modifier.isAbstract(cls.getModifiers());
    }

    private static SortedSet<String> namesOfClassesOnlyIn(Set<Class<?>> these, Set<Class<?>> notThose) {
        return these.stream()
                .filter(cls -> !notThose.contains(cls))
                .map(Class::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Untagged processor: the negative control. */
    private static final class Passthrough implements AudioProcessor {
        @Override
        public void process(float[][] in, float[][] out, int n) {
            for (int ch = 0; ch < Math.min(in.length, out.length); ch++) {
                System.arraycopy(in[ch], 0, out[ch], 0, n);
            }
        }

        @Override
        public void reset() { }

        @Override
        public int getInputChannelCount() { return 2; }

        @Override
        public int getOutputChannelCount() { return 2; }
    }
}
