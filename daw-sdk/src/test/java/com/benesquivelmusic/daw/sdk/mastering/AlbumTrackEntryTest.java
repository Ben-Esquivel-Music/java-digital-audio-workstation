package com.benesquivelmusic.daw.sdk.mastering;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlbumTrackEntryTest {

    @Test
    void shouldCreateWithDefaults() {
        AlbumTrackEntry entry = AlbumTrackEntry.of("Track 1", 180.0);

        assertThat(entry.title()).isEqualTo("Track 1");
        assertThat(entry.durationSeconds()).isEqualTo(180.0);
        assertThat(entry.preGapSeconds()).isEqualTo(AlbumTrackEntry.DEFAULT_PRE_GAP_SECONDS);
        assertThat(entry.crossfadeDuration()).isEqualTo(0.0);
        assertThat(entry.crossfadeCurve()).isEqualTo(CrossfadeCurve.LINEAR);
        assertThat(entry.isrc()).isNull();
    }

    @Test
    void shouldCreateWithAllFields() {
        AlbumTrackEntry entry = new AlbumTrackEntry("Track 2", "Some Artist", "USRC12345678",
                240.0, 3.0, 2.0, CrossfadeCurve.EQUAL_POWER);

        assertThat(entry.title()).isEqualTo("Track 2");
        assertThat(entry.artist()).isEqualTo("Some Artist");
        assertThat(entry.isrc()).isEqualTo("USRC12345678");
        assertThat(entry.durationSeconds()).isEqualTo(240.0);
        assertThat(entry.preGapSeconds()).isEqualTo(3.0);
        assertThat(entry.crossfadeDuration()).isEqualTo(2.0);
        assertThat(entry.crossfadeCurve()).isEqualTo(CrossfadeCurve.EQUAL_POWER);
    }

    @Test
    void shouldUpdatePreGap() {
        AlbumTrackEntry entry = AlbumTrackEntry.of("Track", 180.0);
        AlbumTrackEntry updated = entry.withPreGapSeconds(5.0);

        assertThat(updated.preGapSeconds()).isEqualTo(5.0);
        assertThat(updated.title()).isEqualTo("Track");
        assertThat(updated.durationSeconds()).isEqualTo(180.0);
    }

    @Test
    void shouldUpdateCrossfade() {
        AlbumTrackEntry entry = AlbumTrackEntry.of("Track", 180.0);
        AlbumTrackEntry updated = entry.withCrossfade(3.0, CrossfadeCurve.S_CURVE);

        assertThat(updated.crossfadeDuration()).isEqualTo(3.0);
        assertThat(updated.crossfadeCurve()).isEqualTo(CrossfadeCurve.S_CURVE);
    }

    @Test
    void shouldRejectNegativeDuration() {
        assertThatThrownBy(() -> AlbumTrackEntry.of("Track", -1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectPreGapAboveMax() {
        assertThatThrownBy(() -> new AlbumTrackEntry("Track", null, null, 180.0,
                11.0, 0.0, CrossfadeCurve.LINEAR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativePreGap() {
        assertThatThrownBy(() -> new AlbumTrackEntry("Track", null, null, 180.0,
                -1.0, 0.0, CrossfadeCurve.LINEAR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeCrossfade() {
        assertThatThrownBy(() -> new AlbumTrackEntry("Track", null, null, 180.0,
                2.0, -1.0, CrossfadeCurve.LINEAR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullTitle() {
        assertThatThrownBy(() -> AlbumTrackEntry.of(null, 180.0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullCurve() {
        assertThatThrownBy(() -> new AlbumTrackEntry("Track", null, null, 180.0,
                2.0, 0.0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldDefaultToNullArtist() {
        AlbumTrackEntry entry = AlbumTrackEntry.of("Track", 180.0);

        assertThat(entry.artist()).isNull();
    }

    @Test
    void shouldUpdateArtist() {
        AlbumTrackEntry entry = AlbumTrackEntry.of("Track", 180.0);
        AlbumTrackEntry updated = entry.withArtist("New Artist");

        assertThat(updated.artist()).isEqualTo("New Artist");
        assertThat(updated.title()).isEqualTo("Track");
        assertThat(updated.durationSeconds()).isEqualTo(180.0);
    }

    @Test
    void shouldUpdateIsrc() {
        AlbumTrackEntry entry = AlbumTrackEntry.of("Track", 180.0);
        AlbumTrackEntry updated = entry.withIsrc("USRC99999999");

        assertThat(updated.isrc()).isEqualTo("USRC99999999");
        assertThat(updated.title()).isEqualTo("Track");
    }

    @Test
    void withArtistShouldPreserveOtherFields() {
        AlbumTrackEntry entry = new AlbumTrackEntry("T", "OldArtist", "ISRC123",
                200.0, 3.0, 1.5, CrossfadeCurve.S_CURVE);
        AlbumTrackEntry updated = entry.withArtist("NewArtist");

        assertThat(updated.title()).isEqualTo("T");
        assertThat(updated.artist()).isEqualTo("NewArtist");
        assertThat(updated.isrc()).isEqualTo("ISRC123");
        assertThat(updated.durationSeconds()).isEqualTo(200.0);
        assertThat(updated.preGapSeconds()).isEqualTo(3.0);
        assertThat(updated.crossfadeDuration()).isEqualTo(1.5);
        assertThat(updated.crossfadeCurve()).isEqualTo(CrossfadeCurve.S_CURVE);
    }
}
