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
import com.benesquivelmusic.daw.core.spatial.binaural.StereoToBinauralConverter;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.spatial.AmbisonicOrder;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Story 322 — the twelve spatial {@link AudioProcessor}s carry
 * {@code @ProcessorCapability(}{@link ProcessorCapabilities#SPATIAL}{@code )}
 * (the first production use of the tag), and
 * {@link SpatialInserts#hasSpatialNode} reads it through the cached
 * introspector to answer whether a channel's chain contains a spatial node
 * — the fact the 3D-panner affordance is gated on (Audio Engine Wiring
 * Design Book §5.6 "3D panner button").
 */
class SpatialInsertsTest {

    private static final List<Class<? extends AudioProcessor>> SPATIAL_PROCESSORS = List.of(
            AirAbsorptionFilter.class,
            AmbienceUpmixer.class,
            AFormatConverter.class,
            AmbisonicBinauralDecoder.class,
            AmbisonicDecoder.class,
            AmbisonicEncoder.class,
            AmbisonicEnhancer.class,
            AmbisonicRotator.class,
            AsdmProcessor.class,
            BinauralExternalizationProcessor.class,
            BinauralMonitoringProcessor.class,
            StereoToBinauralConverter.class);

    @Test
    void allTwelveSpatialProcessorsCarryTheSpatialTagThroughTheIntrospector() {
        assertThat(SPATIAL_PROCESSORS).hasSize(12);
        for (Class<? extends AudioProcessor> cls : SPATIAL_PROCESSORS) {
            PluginCapabilities caps = PluginCapabilityIntrospector.capabilitiesOf(cls);
            assertThat(caps.customCapabilities())
                    .as("%s declares @ProcessorCapability(SPATIAL)", cls.getSimpleName())
                    .contains(ProcessorCapabilities.SPATIAL);
            assertThat(caps.hasCustomCapability(ProcessorCapabilities.SPATIAL)).isTrue();
        }
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
    void rejectsNullChannel() {
        assertThatNullPointerException().isThrownBy(() -> SpatialInserts.hasSpatialNode(null));
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
