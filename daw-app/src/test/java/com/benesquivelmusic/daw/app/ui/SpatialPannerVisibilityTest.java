package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.spatial.binaural.StereoToBinauralConverter;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 — "3D panner button: hidden until a spatial node exists in the
 * channel chain" (Audio Engine Wiring Design Book §5.6). The button's
 * {@code visible} and {@code managed} are bound to
 * {@code ChannelVM.spatialNodePresent}, derived from the real insert chain
 * through the {@code @ProcessorCapability(SPATIAL)} tag: absent for a channel
 * without a spatial insert, present as soon as one is inserted, gone again
 * when it is removed — and a non-spatial insert does not count.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SpatialPannerVisibilityTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 256);

    @Test
    void pannerButtonFollowsTheChannelsSpatialInserts() throws Exception {
        DawProject project = new DawProject("Spatial", FORMAT);
        Track plain = project.createAudioTrack("Plain");
        Track binaural = project.createAudioTrack("Binaural");
        MixerChannel plainCh = project.getMixerChannelForTrack(plain);
        MixerChannel binauralCh = project.getMixerChannelForTrack(binaural);

        ArrangementStripFixture.onFx(() -> {
            MixerView view = new MixerView(project);
            try {
                List<MixerView.TrackStripHandles> tracks = view.getTrackStrips();
                assertThat(tracks).hasSize(2);
                assertHidden(tracks.get(0), "plain (no inserts)");
                assertHidden(tracks.get(1), "binaural (no inserts yet)");

                // A tagged spatial processor from core/spatial enters the chain.
                binauralCh.addInsert(new InsertSlot("Binaural",
                        new StereoToBinauralConverter(FORMAT.sampleRate(), FORMAT.bufferSize())));
                assertShown(tracks.get(1), "binaural after inserting a spatial node");
                assertHidden(tracks.get(0), "plain is unaffected by the other channel");

                // A non-spatial insert does not count.
                plainCh.addInsert(new InsertSlot("Passthrough", passthrough()));
                assertHidden(tracks.get(0), "plain with a non-spatial insert");

                // The fact survives a rebuild (seeded from the chain at bind time) …
                view.refresh();
                List<MixerView.TrackStripHandles> rebuilt = view.getTrackStrips();
                assertHidden(rebuilt.get(0), "plain after refresh");
                assertShown(rebuilt.get(1), "binaural after refresh");

                // … and disappears with the node.
                binauralCh.removeInsert(0);
                assertHidden(rebuilt.get(1), "binaural after removing the spatial node");
            } finally {
                view.dispose();
            }
        });
    }

    private static void assertHidden(MixerView.TrackStripHandles handles, String what) {
        assertThat(handles.pannerBtn().isVisible()).as(what + ": 3D visible").isFalse();
        assertThat(handles.pannerBtn().isManaged()).as(what + ": 3D managed").isFalse();
    }

    private static void assertShown(MixerView.TrackStripHandles handles, String what) {
        assertThat(handles.pannerBtn().isVisible()).as(what + ": 3D visible").isTrue();
        assertThat(handles.pannerBtn().isManaged()).as(what + ": 3D managed").isTrue();
    }

    /** A stereo pass-through with no capability tags. */
    private static AudioProcessor passthrough() {
        return new AudioProcessor() {
            @Override
            public void process(float[][] in, float[][] out, int numFrames) {
                for (int ch = 0; ch < Math.min(in.length, out.length); ch++) {
                    System.arraycopy(in[ch], 0, out[ch], 0, numFrames);
                }
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
        };
    }
}
