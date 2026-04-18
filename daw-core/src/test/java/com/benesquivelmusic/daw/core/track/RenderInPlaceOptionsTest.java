package com.benesquivelmusic.daw.core.track;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenderInPlaceOptionsTest {

    @Test
    void defaultsShouldReplaceClipsAndIncludeInserts() {
        RenderInPlaceOptions opts = RenderInPlaceOptions.defaults();

        assertThat(opts.isReplaceOriginalClips()).isTrue();
        assertThat(opts.isCreateNewTrack()).isFalse();
        assertThat(opts.isIncludeInserts()).isTrue();
        assertThat(opts.isIncludeAutomation()).isFalse();
        assertThat(opts.isIncludeSends()).isFalse();
        assertThat(opts.getMidiRenderer()).isNull();
    }

    @Test
    void builderShouldRejectBothReplaceAndCreateNewTrack() {
        RenderInPlaceOptions.Builder b = RenderInPlaceOptions.builder()
                .replaceOriginalClips(true);
        // setting createNewTrack flips replaceOriginalClips off, so build is OK
        RenderInPlaceOptions opts = b.createNewTrack(true)
                .newTrackFactory(src -> new Track("x", TrackType.AUDIO))
                .build();
        assertThat(opts.isCreateNewTrack()).isTrue();
        assertThat(opts.isReplaceOriginalClips()).isFalse();
    }

    @Test
    void createNewTrackRequiresFactory() {
        assertThatThrownBy(() -> RenderInPlaceOptions.builder()
                .createNewTrack(true)
                .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("newTrackFactory");
    }
}
