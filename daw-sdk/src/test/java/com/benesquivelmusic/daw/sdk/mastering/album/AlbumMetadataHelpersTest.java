package com.benesquivelmusic.daw.sdk.mastering.album;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlbumMetadataHelpersTest {

    @Test
    void propagateArtistShouldOverwriteEveryTrack() {
        List<AlbumTrackMetadata> tracks = List.of(
                AlbumTrackMetadata.ofTitle("T1"),
                AlbumTrackMetadata.ofTitle("T2").withArtist("Original"),
                AlbumTrackMetadata.ofTitle("T3"));

        List<AlbumTrackMetadata> out =
                AlbumMetadataHelpers.propagateArtist(tracks, "Album Artist");

        assertThat(out).extracting(AlbumTrackMetadata::artist)
                .containsExactly("Album Artist", "Album Artist", "Album Artist");
        // Source is unchanged.
        assertThat(tracks.get(1).artist()).isEqualTo("Original");
    }

    @Test
    void propagateComposerShouldOverwriteEveryTrack() {
        List<AlbumTrackMetadata> tracks = List.of(
                AlbumTrackMetadata.ofTitle("T1"),
                AlbumTrackMetadata.ofTitle("T2"));

        List<AlbumTrackMetadata> out =
                AlbumMetadataHelpers.propagateComposer(tracks, "John Doe");

        assertThat(out).extracting(AlbumTrackMetadata::composer)
                .containsOnly("John Doe");
    }

    @Test
    void autoGenerateIsrcShouldIncrementDesignation() {
        List<AlbumTrackMetadata> tracks = List.of(
                AlbumTrackMetadata.ofTitle("T1"),
                AlbumTrackMetadata.ofTitle("T2"),
                AlbumTrackMetadata.ofTitle("T3"));

        List<AlbumTrackMetadata> out =
                AlbumMetadataHelpers.autoGenerateIsrcSequence(tracks, "US-RC1-26-00042");

        assertThat(out).extracting(AlbumTrackMetadata::isrc)
                .containsExactly("US-RC1-26-00042", "US-RC1-26-00043", "US-RC1-26-00044");
    }

    @Test
    void autoGenerateIsrcShouldAcceptTightForm() {
        List<AlbumTrackMetadata> tracks = List.of(AlbumTrackMetadata.ofTitle("T1"));
        List<AlbumTrackMetadata> out =
                AlbumMetadataHelpers.autoGenerateIsrcSequence(tracks, "USRC12600042");
        assertThat(out.get(0).isrc()).isEqualTo("US-RC1-26-00042");
    }

    @Test
    void autoGenerateIsrcShouldRejectInvalidFirstIsrc() {
        List<AlbumTrackMetadata> tracks = List.of(AlbumTrackMetadata.ofTitle("T1"));
        assertThatThrownBy(() ->
                AlbumMetadataHelpers.autoGenerateIsrcSequence(tracks, "BAD"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void autoGenerateIsrcOnEmptyShouldReturnEmpty() {
        assertThat(AlbumMetadataHelpers.autoGenerateIsrcSequence(List.of(), "US-RC1-26-00042"))
                .isEmpty();
    }
}
