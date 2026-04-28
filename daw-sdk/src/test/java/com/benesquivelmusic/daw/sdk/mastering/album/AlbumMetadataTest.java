package com.benesquivelmusic.daw.sdk.mastering.album;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlbumMetadataTest {

    @Test
    void ofShouldDefaultOptionalFields() {
        AlbumMetadata m = AlbumMetadata.of("My Album", "My Artist");

        assertThat(m.title()).isEqualTo("My Album");
        assertThat(m.artist()).isEqualTo("My Artist");
        assertThat(m.year()).isZero();
        assertThat(m.genre()).isNull();
        assertThat(m.upcEan()).isNull();
        assertThat(m.releaseDate()).isEmpty();
    }

    @Test
    void shouldRejectNullTitleOrArtist() {
        assertThatThrownBy(() ->
                new AlbumMetadata(null, "A", 0, null, null, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                new AlbumMetadata("T", null, 0, null, null, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectEmptyTitleOrArtist() {
        assertThatThrownBy(() ->
                new AlbumMetadata("", "A", 0, null, null, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new AlbumMetadata("T", "", 0, null, null, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectBlankTitleOrArtist() {
        assertThatThrownBy(() ->
                new AlbumMetadata("   ", "A", 0, null, null, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
        assertThatThrownBy(() ->
                new AlbumMetadata("T", "  ", 0, null, null, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void shouldRejectOutOfRangeYear() {
        assertThatThrownBy(() ->
                new AlbumMetadata("T", "A", 1700, null, null, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptUpcA12Digits() {
        AlbumMetadata m = AlbumMetadata.of("T", "A").withUpcEan("123456789012");
        assertThat(m.upcEan()).isEqualTo("123456789012");
    }

    @Test
    void shouldAcceptEan13Digits() {
        AlbumMetadata m = AlbumMetadata.of("T", "A").withUpcEan("1234567890123");
        assertThat(m.upcEan()).isEqualTo("1234567890123");
    }

    @Test
    void shouldRejectMalformedUpcEan() {
        assertThatThrownBy(() -> AlbumMetadata.of("T", "A").withUpcEan("12345"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AlbumMetadata.of("T", "A").withUpcEan("123456789012X"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withReleaseDateShouldStoreOptional() {
        LocalDate d = LocalDate.of(2026, 4, 28);
        AlbumMetadata m = AlbumMetadata.of("T", "A").withReleaseDate(Optional.of(d));
        assertThat(m.releaseDate()).contains(d);
    }
}
