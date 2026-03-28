package com.benesquivelmusic.daw.sdk.mastering;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AlbumExportTypeTest {

    @Test
    void shouldHaveTwoExportTypes() {
        assertThat(AlbumExportType.values()).hasSize(2);
    }

    @Test
    void shouldContainSingleContinuous() {
        assertThat(AlbumExportType.valueOf("SINGLE_CONTINUOUS"))
                .isEqualTo(AlbumExportType.SINGLE_CONTINUOUS);
    }

    @Test
    void shouldContainIndividualTracks() {
        assertThat(AlbumExportType.valueOf("INDIVIDUAL_TRACKS"))
                .isEqualTo(AlbumExportType.INDIVIDUAL_TRACKS);
    }
}
