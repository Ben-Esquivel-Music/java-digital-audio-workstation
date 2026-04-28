package com.benesquivelmusic.daw.sdk.mastering.album;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlbumTrackMetadataTest {

    @Test
    void ofTitleShouldDefaultToEmptyMetadata() {
        AlbumTrackMetadata m = AlbumTrackMetadata.ofTitle("Track 1");

        assertThat(m.title()).isEqualTo("Track 1");
        assertThat(m.artist()).isNull();
        assertThat(m.composer()).isNull();
        assertThat(m.isrc()).isNull();
        assertThat(m.cdText()).isEmpty();
        assertThat(m.extra()).isEmpty();
    }

    @Test
    void shouldRejectNullTitle() {
        assertThatThrownBy(() -> new AlbumTrackMetadata(
                null, null, null, null, Optional.empty(), Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectEmptyTitle() {
        assertThatThrownBy(() -> new AlbumTrackMetadata(
                "", null, null, null, Optional.empty(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectBlankTitle() {
        assertThatThrownBy(() -> new AlbumTrackMetadata(
                "   ", null, null, null, Optional.empty(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void shouldRejectInvalidIsrc() {
        assertThatThrownBy(() -> new AlbumTrackMetadata(
                "Track 1", null, null, "BAD-ISRC", Optional.empty(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid ISRC");
    }

    @Test
    void shouldAcceptValidIsrc() {
        AlbumTrackMetadata m = new AlbumTrackMetadata(
                "Track 1", null, null, "US-RC1-26-00042", Optional.empty(), Map.of());

        assertThat(m.isrc()).isEqualTo("US-RC1-26-00042");
    }

    @Test
    void constructorShouldNormalizeTightIsrc() {
        AlbumTrackMetadata m = new AlbumTrackMetadata(
                "Track 1", null, null, "USRC12600042", Optional.empty(), Map.of());

        assertThat(m.isrc()).isEqualTo("US-RC1-26-00042");
    }

    @Test
    void withIsrcShouldNormalize() {
        AlbumTrackMetadata m = AlbumTrackMetadata.ofTitle("Track 1")
                .withIsrc("usrc12600042");

        assertThat(m.isrc()).isEqualTo("US-RC1-26-00042");
    }

    @Test
    void withIsrcNullShouldClear() {
        AlbumTrackMetadata m = AlbumTrackMetadata.ofTitle("Track 1")
                .withIsrc("US-RC1-26-00042")
                .withIsrc(null);

        assertThat(m.isrc()).isNull();
    }

    @Test
    void extraMapShouldBeImmutable() {
        Map<String, String> mutable = new LinkedHashMap<>();
        mutable.put("explicit", "false");
        AlbumTrackMetadata m = AlbumTrackMetadata.ofTitle("Track 1").withExtra(mutable);

        // Mutating the source should not leak into the record.
        mutable.put("language", "en");

        assertThat(m.extra()).containsExactly(Map.entry("explicit", "false"));
        assertThatThrownBy(() -> m.extra().put("k", "v"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void withExtraTagShouldAddOrReplace() {
        AlbumTrackMetadata m = AlbumTrackMetadata.ofTitle("T1")
                .withExtraTag("explicit", "false")
                .withExtraTag("language", "en")
                .withExtraTag("explicit", "true"); // replace

        assertThat(m.extra()).containsEntry("explicit", "true");
        assertThat(m.extra()).containsEntry("language", "en");
    }

    @Test
    void withCdTextShouldStoreFields() {
        CdText cdt = new CdText("Smith", null, "Mastered at Studio X", null);
        AlbumTrackMetadata m = AlbumTrackMetadata.ofTitle("T1").withCdText(Optional.of(cdt));

        assertThat(m.cdText()).contains(cdt);
        assertThat(m.cdText().orElseThrow().songwriter()).isEqualTo("Smith");
    }
}
