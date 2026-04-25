package com.benesquivelmusic.daw.core.spatial.objectbased;

import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BedChannelTest {

    @Test
    void shouldCreateBedChannelWithUnityGain() {
        BedChannel bed = new BedChannel("track-1", SpeakerLabel.L);
        assertThat(bed.trackId()).isEqualTo("track-1");
        assertThat(bed.speakerLabel()).isEqualTo(SpeakerLabel.L);
        assertThat(bed.gain()).isEqualTo(1.0);
    }

    @Test
    void shouldCreateBedChannelWithCustomGain() {
        BedChannel bed = new BedChannel("track-1", SpeakerLabel.C, 0.5);
        assertThat(bed.gain()).isEqualTo(0.5);
    }

    @Test
    void shouldRejectInvalidGain() {
        assertThatThrownBy(() -> new BedChannel("track-1", SpeakerLabel.L, -0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BedChannel("track-1", SpeakerLabel.L, 1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullTrackId() {
        assertThatThrownBy(() -> new BedChannel(null, SpeakerLabel.L))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullSpeakerLabel() {
        assertThatThrownBy(() -> new BedChannel("track-1", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRouteBedChannelToCorrectSpeakerIndex() {
        SpeakerLayout layout = SpeakerLayout.LAYOUT_7_1_4;
        BedChannel bed = new BedChannel("track-1", SpeakerLabel.C);
        int idx = layout.indexOf(bed.speakerLabel());
        assertThat(idx).isEqualTo(2); // C is at index 2 in 7.1.4
    }

    @Test
    void shouldRouteBedChannelsToAll714Positions() {
        SpeakerLayout layout = SpeakerLayout.LAYOUT_7_1_4;

        // Iterate over the speakers that compose the 7.1.4 layout (rather
        // than every SpeakerLabel value) so the test stays correct as new
        // labels are added for wider formats like 9.1.6.
        for (int i = 0; i < layout.channelCount(); i++) {
            SpeakerLabel label = layout.speakers().get(i);
            BedChannel bed = new BedChannel("track-" + i, label);
            int idx = layout.indexOf(bed.speakerLabel());
            assertThat(idx)
                    .as("Speaker %s should be at index %d", label, i)
                    .isEqualTo(i);
        }
    }
}
