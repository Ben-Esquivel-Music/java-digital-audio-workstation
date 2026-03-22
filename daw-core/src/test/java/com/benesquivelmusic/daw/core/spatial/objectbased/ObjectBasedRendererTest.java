package com.benesquivelmusic.daw.core.spatial.objectbased;

import com.benesquivelmusic.daw.sdk.spatial.ObjectMetadata;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class ObjectBasedRendererTest {

    private static final int NUM_SAMPLES = 32;

    @Test
    void shouldRouteBedChannelToCorrectSpeaker() {
        var renderer = new ObjectBasedRenderer(SpeakerLayout.LAYOUT_7_1_4);

        var beds = List.of(new BedChannel("bed-C", SpeakerLabel.C));
        float[] audio = new float[NUM_SAMPLES];
        Arrays.fill(audio, 0.8f);
        var bedAudio = List.of(audio);

        float[][] output = renderer.render(beds, bedAudio,
                List.of(), List.of(), NUM_SAMPLES);

        // C is at index 2 in 7.1.4
        assertThat(output).hasNumberOfRows(12);
        assertThat((double) output[2][0]).isCloseTo(0.8, offset(0.001));

        // Other channels should be silent
        assertThat((double) output[0][0]).isCloseTo(0.0, offset(0.001));
        assertThat((double) output[1][0]).isCloseTo(0.0, offset(0.001));
    }

    @Test
    void shouldRouteBedChannelWithGainAttenuation() {
        var renderer = new ObjectBasedRenderer(SpeakerLayout.LAYOUT_7_1_4);

        var beds = List.of(new BedChannel("bed-L", SpeakerLabel.L, 0.5));
        float[] audio = new float[NUM_SAMPLES];
        Arrays.fill(audio, 1.0f);

        float[][] output = renderer.render(beds, List.of(audio),
                List.of(), List.of(), NUM_SAMPLES);

        // L at index 0, gain 0.5
        assertThat((double) output[0][0]).isCloseTo(0.5, offset(0.001));
    }

    @Test
    void shouldRenderObjectToNearestSpeakers() {
        var renderer = new ObjectBasedRenderer(SpeakerLayout.LAYOUT_7_1_4);

        // Object at front-center (x=0, y=1, z=0) should mostly go to C speaker
        var obj = new AudioObject("obj-1", new ObjectMetadata(0.0, 1.0, 0.0, 0.0, 1.0));
        float[] audio = new float[NUM_SAMPLES];
        Arrays.fill(audio, 1.0f);

        float[][] output = renderer.render(List.of(), List.of(),
                List.of(obj), List.of(audio), NUM_SAMPLES);

        // Center (index 2) should have the highest gain
        double centerGain = output[2][0];
        assertThat(centerGain).isGreaterThan(0.0);

        // Center should be the dominant channel
        for (int ch = 0; ch < 12; ch++) {
            if (ch == 2 || ch == 3) { // skip C itself and LFE
                continue;
            }
            assertThat(centerGain).as("C should dominate over channel %d", ch)
                    .isGreaterThanOrEqualTo((double) output[ch][0]);
        }
    }

    @Test
    void shouldApplyObjectGain() {
        var renderer = new ObjectBasedRenderer(SpeakerLayout.LAYOUT_STEREO);

        var obj = new AudioObject("obj-1", new ObjectMetadata(0.0, 1.0, 0.0, 0.0, 0.5));
        float[] audio = new float[NUM_SAMPLES];
        Arrays.fill(audio, 1.0f);

        float[][] output = renderer.render(List.of(), List.of(),
                List.of(obj), List.of(audio), NUM_SAMPLES);

        // Total energy across speakers should reflect 0.5 gain
        double totalGain = 0;
        for (int ch = 0; ch < output.length; ch++) {
            totalGain += output[ch][0];
        }
        assertThat(totalGain).isCloseTo(0.5, offset(0.01));
    }

    @Test
    void shouldMixBedsAndObjectsTogether() {
        var renderer = new ObjectBasedRenderer(SpeakerLayout.LAYOUT_STEREO);

        // Bed on L at 0.5
        var beds = List.of(new BedChannel("bed-L", SpeakerLabel.L));
        float[] bedAudio = new float[NUM_SAMPLES];
        Arrays.fill(bedAudio, 0.5f);

        // Object centered
        var obj = new AudioObject("obj-1", new ObjectMetadata(0.0, 1.0, 0.0, 0.0, 1.0));
        float[] objAudio = new float[NUM_SAMPLES];
        Arrays.fill(objAudio, 0.3f);

        float[][] output = renderer.render(beds, List.of(bedAudio),
                List.of(obj), List.of(objAudio), NUM_SAMPLES);

        // L channel should have bed + some object contribution
        assertThat((double) output[0][0]).isGreaterThan(0.5);
    }

    @Test
    void shouldSkipBedOnMissingSpeaker() {
        var renderer = new ObjectBasedRenderer(SpeakerLayout.LAYOUT_STEREO);

        // Assign bed to Center, which is not in stereo layout
        var beds = List.of(new BedChannel("bed-C", SpeakerLabel.C));
        float[] audio = new float[NUM_SAMPLES];
        Arrays.fill(audio, 1.0f);

        float[][] output = renderer.render(beds, List.of(audio),
                List.of(), List.of(), NUM_SAMPLES);

        // No signal should be routed
        assertThat((double) output[0][0]).isCloseTo(0.0, offset(0.001));
        assertThat((double) output[1][0]).isCloseTo(0.0, offset(0.001));
    }
}
