package com.benesquivelmusic.daw.sdk.spatial;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HrtfProfileTest {

    @Test
    void shouldHaveThreeProfiles() {
        assertThat(HrtfProfile.values()).hasSize(3);
    }

    @Test
    void shouldReturnCorrectDisplayNames() {
        assertThat(HrtfProfile.SMALL.displayName()).isEqualTo("Small");
        assertThat(HrtfProfile.MEDIUM.displayName()).isEqualTo("Medium");
        assertThat(HrtfProfile.LARGE.displayName()).isEqualTo("Large");
    }

    @Test
    void shouldReturnCorrectHeadCircumferences() {
        assertThat(HrtfProfile.SMALL.headCircumferenceCm()).isEqualTo(53.0);
        assertThat(HrtfProfile.MEDIUM.headCircumferenceCm()).isEqualTo(57.0);
        assertThat(HrtfProfile.LARGE.headCircumferenceCm()).isEqualTo(61.0);
    }

    @Test
    void shouldHaveIncreasingHeadCircumferences() {
        assertThat(HrtfProfile.SMALL.headCircumferenceCm())
                .isLessThan(HrtfProfile.MEDIUM.headCircumferenceCm());
        assertThat(HrtfProfile.MEDIUM.headCircumferenceCm())
                .isLessThan(HrtfProfile.LARGE.headCircumferenceCm());
    }
}
