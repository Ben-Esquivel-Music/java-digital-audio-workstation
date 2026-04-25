package com.benesquivelmusic.daw.sdk.spatial;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImmersiveFormatTest {

    @Test
    void channelCountMatchesUnderlyingLayout() {
        assertThat(ImmersiveFormat.FORMAT_5_1.channelCount()).isEqualTo(6);
        assertThat(ImmersiveFormat.FORMAT_5_1_4.channelCount()).isEqualTo(10);
        assertThat(ImmersiveFormat.FORMAT_7_1_4.channelCount()).isEqualTo(12);
        assertThat(ImmersiveFormat.FORMAT_9_1_6.channelCount()).isEqualTo(16);
    }

    @Test
    void displayNamesMatchLayoutNames() {
        assertThat(ImmersiveFormat.FORMAT_7_1_4.displayName()).isEqualTo("7.1.4");
        assertThat(ImmersiveFormat.FORMAT_9_1_6.displayName()).isEqualTo("9.1.6");
    }

    @Test
    void fromDisplayNameRoundTrips() {
        for (ImmersiveFormat format : ImmersiveFormat.values()) {
            assertThat(ImmersiveFormat.fromDisplayName(format.displayName()))
                    .isEqualTo(format);
        }
    }

    @Test
    void fromDisplayNameFallsBackTo714ForUnknownNames() {
        assertThat(ImmersiveFormat.fromDisplayName("nonsense"))
                .isEqualTo(ImmersiveFormat.FORMAT_7_1_4);
    }
}
