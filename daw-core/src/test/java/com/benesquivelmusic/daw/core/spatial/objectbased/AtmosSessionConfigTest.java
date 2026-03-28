package com.benesquivelmusic.daw.core.spatial.objectbased;

import com.benesquivelmusic.daw.sdk.spatial.ObjectMetadata;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtmosSessionConfigTest {

    @Test
    void shouldCreateWithDefaults() {
        AtmosSessionConfig config = new AtmosSessionConfig();

        assertThat(config.getLayout()).isEqualTo(SpeakerLayout.LAYOUT_7_1_4);
        assertThat(config.getSampleRate()).isEqualTo(48000);
        assertThat(config.getBitDepth()).isEqualTo(24);
        assertThat(config.getBedChannels()).isEmpty();
        assertThat(config.getAudioObjects()).isEmpty();
        assertThat(config.getTotalTrackCount()).isZero();
    }

    @Test
    void shouldCreateWithCustomParameters() {
        AtmosSessionConfig config = new AtmosSessionConfig(
                SpeakerLayout.LAYOUT_5_1_4, 96000, 32);

        assertThat(config.getLayout()).isEqualTo(SpeakerLayout.LAYOUT_5_1_4);
        assertThat(config.getSampleRate()).isEqualTo(96000);
        assertThat(config.getBitDepth()).isEqualTo(32);
    }

    @Test
    void shouldRejectNullLayout() {
        assertThatThrownBy(() -> new AtmosSessionConfig(null, 48000, 24))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectInvalidSampleRate() {
        assertThatThrownBy(() -> new AtmosSessionConfig(SpeakerLayout.LAYOUT_7_1_4, 22050, 24))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("22050");
    }

    @Test
    void shouldRejectInvalidBitDepth() {
        assertThatThrownBy(() -> new AtmosSessionConfig(SpeakerLayout.LAYOUT_7_1_4, 48000, 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8");
    }

    @Test
    void shouldAddAndRemoveBedChannels() {
        AtmosSessionConfig config = new AtmosSessionConfig();
        BedChannel bed = new BedChannel("track-1", SpeakerLabel.L);

        config.addBedChannel(bed);
        assertThat(config.getBedChannels()).hasSize(1);
        assertThat(config.getTotalTrackCount()).isEqualTo(1);

        boolean removed = config.removeBedChannel(bed);
        assertThat(removed).isTrue();
        assertThat(config.getBedChannels()).isEmpty();
    }

    @Test
    void shouldClearBedChannels() {
        AtmosSessionConfig config = new AtmosSessionConfig();
        config.addBedChannel(new BedChannel("t1", SpeakerLabel.L));
        config.addBedChannel(new BedChannel("t2", SpeakerLabel.R));

        config.clearBedChannels();
        assertThat(config.getBedChannels()).isEmpty();
    }

    @Test
    void shouldAddAndRemoveAudioObjects() {
        AtmosSessionConfig config = new AtmosSessionConfig();
        AudioObject obj = new AudioObject("track-1");

        config.addAudioObject(obj);
        assertThat(config.getAudioObjects()).hasSize(1);
        assertThat(config.getTotalTrackCount()).isEqualTo(1);

        boolean removed = config.removeAudioObject(obj);
        assertThat(removed).isTrue();
        assertThat(config.getAudioObjects()).isEmpty();
    }

    @Test
    void shouldClearAudioObjects() {
        AtmosSessionConfig config = new AtmosSessionConfig();
        config.addAudioObject(new AudioObject("o1"));
        config.addAudioObject(new AudioObject("o2"));

        config.clearAudioObjects();
        assertThat(config.getAudioObjects()).isEmpty();
    }

    @Test
    void shouldRejectNullBedChannel() {
        AtmosSessionConfig config = new AtmosSessionConfig();
        assertThatThrownBy(() -> config.addBedChannel(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullAudioObject() {
        AtmosSessionConfig config = new AtmosSessionConfig();
        assertThatThrownBy(() -> config.addAudioObject(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldSetLayout() {
        AtmosSessionConfig config = new AtmosSessionConfig();
        config.setLayout(SpeakerLayout.LAYOUT_5_1);
        assertThat(config.getLayout()).isEqualTo(SpeakerLayout.LAYOUT_5_1);
    }

    @Test
    void shouldSetSampleRate() {
        AtmosSessionConfig config = new AtmosSessionConfig();
        config.setSampleRate(96000);
        assertThat(config.getSampleRate()).isEqualTo(96000);
    }

    @Test
    void shouldSetBitDepth() {
        AtmosSessionConfig config = new AtmosSessionConfig();
        config.setBitDepth(32);
        assertThat(config.getBitDepth()).isEqualTo(32);
    }

    @Test
    void shouldValidateValidSession() {
        AtmosSessionConfig config = new AtmosSessionConfig();
        config.addBedChannel(new BedChannel("t1", SpeakerLabel.L));
        config.addBedChannel(new BedChannel("t2", SpeakerLabel.R));
        config.addAudioObject(new AudioObject("o1"));

        List<String> errors = config.validate();
        assertThat(errors).isEmpty();
    }

    @Test
    void shouldValidateInvalidSession() {
        AtmosSessionConfig config = new AtmosSessionConfig();
        // Add duplicate bed channel to same speaker
        config.addBedChannel(new BedChannel("t1", SpeakerLabel.L));
        config.addBedChannel(new BedChannel("t2", SpeakerLabel.L));

        List<String> errors = config.validate();
        assertThat(errors).anyMatch(e -> e.contains("Duplicate"));
    }

    @Test
    void shouldCalculateTotalTrackCount() {
        AtmosSessionConfig config = new AtmosSessionConfig();
        config.addBedChannel(new BedChannel("b1", SpeakerLabel.L));
        config.addBedChannel(new BedChannel("b2", SpeakerLabel.R));
        config.addAudioObject(new AudioObject("o1"));
        config.addAudioObject(new AudioObject("o2"));
        config.addAudioObject(new AudioObject("o3"));

        assertThat(config.getTotalTrackCount()).isEqualTo(5);
    }

    @Test
    void shouldCalculateMaxObjectsForBedCount() {
        assertThat(AtmosSessionConfig.maxObjectsForBedCount(0)).isEqualTo(118);
        assertThat(AtmosSessionConfig.maxObjectsForBedCount(12)).isEqualTo(116);
        assertThat(AtmosSessionConfig.maxObjectsForBedCount(10)).isEqualTo(118);
    }

    @Test
    void shouldReturnUnmodifiableViews() {
        AtmosSessionConfig config = new AtmosSessionConfig();
        config.addBedChannel(new BedChannel("t1", SpeakerLabel.L));
        config.addAudioObject(new AudioObject("o1"));

        assertThatThrownBy(() -> config.getBedChannels().add(new BedChannel("t2", SpeakerLabel.R)))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> config.getAudioObjects().add(new AudioObject("o2")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldAcceptAllValidSampleRates() {
        AtmosSessionConfig config = new AtmosSessionConfig();
        for (int rate : new int[]{44100, 48000, 88200, 96000}) {
            config.setSampleRate(rate);
            assertThat(config.getSampleRate()).isEqualTo(rate);
        }
    }

    @Test
    void shouldAcceptAllValidBitDepths() {
        AtmosSessionConfig config = new AtmosSessionConfig();
        for (int depth : new int[]{16, 24, 32}) {
            config.setBitDepth(depth);
            assertThat(config.getBitDepth()).isEqualTo(depth);
        }
    }
}
