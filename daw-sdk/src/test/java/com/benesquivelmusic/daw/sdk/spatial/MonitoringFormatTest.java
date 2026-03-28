package com.benesquivelmusic.daw.sdk.spatial;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MonitoringFormatTest {

    @Test
    void shouldHaveCorrectDisplayNames() {
        assertThat(MonitoringFormat.IMMERSIVE_7_1_4.displayName()).isEqualTo("7.1.4");
        assertThat(MonitoringFormat.SURROUND_5_1.displayName()).isEqualTo("5.1");
        assertThat(MonitoringFormat.STEREO.displayName()).isEqualTo("Stereo");
        assertThat(MonitoringFormat.MONO.displayName()).isEqualTo("Mono");
    }

    @Test
    void shouldHaveCorrectChannelCounts() {
        assertThat(MonitoringFormat.IMMERSIVE_7_1_4.channelCount()).isEqualTo(12);
        assertThat(MonitoringFormat.SURROUND_5_1.channelCount()).isEqualTo(6);
        assertThat(MonitoringFormat.STEREO.channelCount()).isEqualTo(2);
        assertThat(MonitoringFormat.MONO.channelCount()).isEqualTo(1);
    }

    @Test
    void shouldMapToCorrectSpeakerLayouts() {
        assertThat(MonitoringFormat.IMMERSIVE_7_1_4.toSpeakerLayout())
                .isEqualTo(SpeakerLayout.LAYOUT_7_1_4);
        assertThat(MonitoringFormat.SURROUND_5_1.toSpeakerLayout())
                .isEqualTo(SpeakerLayout.LAYOUT_5_1);
        assertThat(MonitoringFormat.STEREO.toSpeakerLayout())
                .isEqualTo(SpeakerLayout.LAYOUT_STEREO);
        assertThat(MonitoringFormat.MONO.toSpeakerLayout())
                .isEqualTo(SpeakerLayout.LAYOUT_MONO);
    }

    @Test
    void shouldHaveFourValues() {
        assertThat(MonitoringFormat.values()).hasSize(4);
    }

    @Test
    void shouldOrderFromMostToFewestChannels() {
        MonitoringFormat[] values = MonitoringFormat.values();
        for (int i = 1; i < values.length; i++) {
            assertThat(values[i].channelCount())
                    .isLessThan(values[i - 1].channelCount());
        }
    }
}
