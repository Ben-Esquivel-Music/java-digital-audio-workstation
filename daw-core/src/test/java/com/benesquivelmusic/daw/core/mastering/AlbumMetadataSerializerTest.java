package com.benesquivelmusic.daw.core.mastering;

import com.benesquivelmusic.daw.sdk.mastering.AlbumTrackEntry;
import com.benesquivelmusic.daw.sdk.mastering.album.AlbumMetadata;
import com.benesquivelmusic.daw.sdk.mastering.album.AlbumTrackMetadata;
import com.benesquivelmusic.daw.sdk.mastering.album.CdText;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AlbumMetadataSerializerTest {

    @Test
    void roundTripPreservesAlbumAndTrackMetadata() {
        AlbumSequence seq = new AlbumSequence("My Album", "My Artist");
        seq.setAlbumMetadata(new AlbumMetadata(
                "My Album", "My Artist", 2026, "Indie",
                "123456789012", Optional.of(LocalDate.of(2026, 4, 28))));
        seq.addTrack(AlbumTrackEntry.of("Track 1", 180.0));
        seq.addTrack(AlbumTrackEntry.of("Track 2", 240.0));

        seq.setTrackMetadata(0, new AlbumTrackMetadata(
                "Track 1", "Solo Artist", "Composer A", "US-RC1-26-00042",
                Optional.of(new CdText("Smith", "Jones", "Mastered at X", "123456789012")),
                Map.of("explicit", "false", "language", "en")));
        seq.setTrackMetadata(1, AlbumTrackMetadata.ofTitle("Track 2")
                .withIsrc("US-RC1-26-00043"));

        String xml = AlbumMetadataSerializer.toXml(seq);

        // Reload into a fresh sequence with the same number of tracks.
        AlbumSequence restored = new AlbumSequence("placeholder", "placeholder");
        restored.addTrack(AlbumTrackEntry.of("placeholder 1", 180.0));
        restored.addTrack(AlbumTrackEntry.of("placeholder 2", 240.0));
        AlbumMetadataSerializer.fromXml(restored, xml);

        AlbumMetadata album = restored.getAlbumMetadata();
        assertThat(album.title()).isEqualTo("My Album");
        assertThat(album.artist()).isEqualTo("My Artist");
        assertThat(album.year()).isEqualTo(2026);
        assertThat(album.genre()).isEqualTo("Indie");
        assertThat(album.upcEan()).isEqualTo("123456789012");
        assertThat(album.releaseDate()).contains(LocalDate.of(2026, 4, 28));

        AlbumTrackMetadata m0 = restored.getTrackMetadata(0).orElseThrow();
        assertThat(m0.title()).isEqualTo("Track 1");
        assertThat(m0.artist()).isEqualTo("Solo Artist");
        assertThat(m0.composer()).isEqualTo("Composer A");
        assertThat(m0.isrc()).isEqualTo("US-RC1-26-00042");
        assertThat(m0.cdText()).isPresent();
        CdText cdt = m0.cdText().orElseThrow();
        assertThat(cdt.songwriter()).isEqualTo("Smith");
        assertThat(cdt.arranger()).isEqualTo("Jones");
        assertThat(cdt.message()).isEqualTo("Mastered at X");
        assertThat(cdt.upcEan()).isEqualTo("123456789012");
        assertThat(m0.extra())
                .containsEntry("explicit", "false")
                .containsEntry("language", "en");

        AlbumTrackMetadata m1 = restored.getTrackMetadata(1).orElseThrow();
        assertThat(m1.isrc()).isEqualTo("US-RC1-26-00043");
        assertThat(m1.cdText()).isEmpty();
        assertThat(m1.extra()).isEmpty();
    }

    @Test
    void roundTripWithNoTrackMetadataPreservesAlbumOnly() {
        AlbumSequence seq = new AlbumSequence("A", "B");
        seq.addTrack(AlbumTrackEntry.of("T1", 180.0));

        String xml = AlbumMetadataSerializer.toXml(seq);

        AlbumSequence restored = new AlbumSequence("placeholder", "placeholder");
        restored.addTrack(AlbumTrackEntry.of("placeholder", 180.0));
        AlbumMetadataSerializer.fromXml(restored, xml);

        assertThat(restored.getAlbumTitle()).isEqualTo("A");
        assertThat(restored.getArtist()).isEqualTo("B");
        assertThat(restored.getTrackMetadata(0)).isEmpty();
    }

    @Test
    void removeTrackKeepsRemainingMetadataAligned() {
        AlbumSequence seq = new AlbumSequence("A", "B");
        seq.addTrack(AlbumTrackEntry.of("T1", 100.0));
        seq.addTrack(AlbumTrackEntry.of("T2", 200.0));
        seq.addTrack(AlbumTrackEntry.of("T3", 300.0));
        seq.setTrackMetadata(0, AlbumTrackMetadata.ofTitle("T1").withIsrc("US-RC1-26-00001"));
        seq.setTrackMetadata(1, AlbumTrackMetadata.ofTitle("T2").withIsrc("US-RC1-26-00002"));
        seq.setTrackMetadata(2, AlbumTrackMetadata.ofTitle("T3").withIsrc("US-RC1-26-00003"));

        seq.removeTrack(1);

        assertThat(seq.size()).isEqualTo(2);
        assertThat(seq.getTrackMetadata(0).orElseThrow().isrc()).isEqualTo("US-RC1-26-00001");
        assertThat(seq.getTrackMetadata(1).orElseThrow().isrc()).isEqualTo("US-RC1-26-00003");
    }

    @Test
    void moveTrackReassignsMetadataIndex() {
        AlbumSequence seq = new AlbumSequence("A", "B");
        seq.addTrack(AlbumTrackEntry.of("T1", 100.0));
        seq.addTrack(AlbumTrackEntry.of("T2", 200.0));
        seq.setTrackMetadata(0, AlbumTrackMetadata.ofTitle("T1").withIsrc("US-RC1-26-00001"));

        seq.moveTrack(0, 1);

        assertThat(seq.getTrackMetadata(0)).isEmpty();
        assertThat(seq.getTrackMetadata(1).orElseThrow().isrc()).isEqualTo("US-RC1-26-00001");
    }

    @Test
    void setAlbumTitleAndArtistMirroredIntoAlbumMetadata() {
        AlbumSequence seq = new AlbumSequence("Old", "Old");
        seq.setAlbumTitle("New Title");
        seq.setArtist("New Artist");
        assertThat(seq.getAlbumMetadata().title()).isEqualTo("New Title");
        assertThat(seq.getAlbumMetadata().artist()).isEqualTo("New Artist");
    }
}
