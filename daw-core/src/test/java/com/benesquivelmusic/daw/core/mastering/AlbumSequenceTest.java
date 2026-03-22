package com.benesquivelmusic.daw.core.mastering;

import com.benesquivelmusic.daw.sdk.mastering.AlbumTrackEntry;
import com.benesquivelmusic.daw.sdk.mastering.CrossfadeCurve;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class AlbumSequenceTest {

    @Test
    void shouldCreateEmptySequence() {
        var seq = new AlbumSequence("Test Album", "Test Artist");

        assertThat(seq.getAlbumTitle()).isEqualTo("Test Album");
        assertThat(seq.getArtist()).isEqualTo("Test Artist");
        assertThat(seq.size()).isZero();
        assertThat(seq.getTotalDurationSeconds()).isEqualTo(0.0);
    }

    @Test
    void shouldAddTracks() {
        var seq = new AlbumSequence("Album", "Artist");
        seq.addTrack(AlbumTrackEntry.of("Track 1", 180.0));
        seq.addTrack(AlbumTrackEntry.of("Track 2", 240.0));

        assertThat(seq.size()).isEqualTo(2);
        assertThat(seq.getTracks().get(0).title()).isEqualTo("Track 1");
        assertThat(seq.getTracks().get(1).title()).isEqualTo("Track 2");
    }

    @Test
    void shouldInsertTrackAtIndex() {
        var seq = new AlbumSequence("Album", "Artist");
        seq.addTrack(AlbumTrackEntry.of("Track 1", 180.0));
        seq.addTrack(AlbumTrackEntry.of("Track 3", 200.0));
        seq.insertTrack(1, AlbumTrackEntry.of("Track 2", 240.0));

        assertThat(seq.getTracks().get(1).title()).isEqualTo("Track 2");
    }

    @Test
    void shouldRemoveTrack() {
        var seq = new AlbumSequence("Album", "Artist");
        seq.addTrack(AlbumTrackEntry.of("Track 1", 180.0));
        seq.addTrack(AlbumTrackEntry.of("Track 2", 240.0));

        var removed = seq.removeTrack(0);
        assertThat(removed.title()).isEqualTo("Track 1");
        assertThat(seq.size()).isEqualTo(1);
    }

    @Test
    void shouldMoveTrack() {
        var seq = new AlbumSequence("Album", "Artist");
        seq.addTrack(AlbumTrackEntry.of("A", 100.0));
        seq.addTrack(AlbumTrackEntry.of("B", 200.0));
        seq.addTrack(AlbumTrackEntry.of("C", 300.0));

        seq.moveTrack(2, 0);

        assertThat(seq.getTracks()).extracting(AlbumTrackEntry::title)
                .containsExactly("C", "A", "B");
    }

    @Test
    void shouldRejectInvalidMoveIndices() {
        var seq = new AlbumSequence("Album", "Artist");
        seq.addTrack(AlbumTrackEntry.of("A", 100.0));

        assertThatThrownBy(() -> seq.moveTrack(-1, 0))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> seq.moveTrack(0, 5))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void shouldComputeTotalDurationWithGaps() {
        var seq = new AlbumSequence("Album", "Artist");
        // First track: 180s, default pre-gap (2s) — but first track's pre-gap is excluded
        seq.addTrack(AlbumTrackEntry.of("Track 1", 180.0));
        // Second track: 240s, default pre-gap (2s)
        seq.addTrack(AlbumTrackEntry.of("Track 2", 240.0));

        // Total = 180 + 2 + 240 = 422
        assertThat(seq.getTotalDurationSeconds()).isCloseTo(422.0, offset(0.001));
    }

    @Test
    void shouldComputeTotalDurationWithCrossfades() {
        var seq = new AlbumSequence("Album", "Artist");
        seq.addTrack(AlbumTrackEntry.of("Track 1", 180.0));
        seq.addTrack(AlbumTrackEntry.of("Track 2", 240.0)
                .withPreGapSeconds(0.0)
                .withCrossfade(3.0, CrossfadeCurve.EQUAL_POWER));

        // Total = 180 + 0 (gap) - 3 (crossfade) + 240 = 417
        assertThat(seq.getTotalDurationSeconds()).isCloseTo(417.0, offset(0.001));
    }

    @Test
    void shouldComputeTrackStartTimes() {
        var seq = new AlbumSequence("Album", "Artist");
        seq.addTrack(AlbumTrackEntry.of("Track 1", 180.0).withPreGapSeconds(0.0));
        seq.addTrack(AlbumTrackEntry.of("Track 2", 240.0).withPreGapSeconds(2.0));
        seq.addTrack(AlbumTrackEntry.of("Track 3", 200.0).withPreGapSeconds(3.0));

        List<Double> startTimes = seq.getTrackStartTimes();

        assertThat(startTimes).hasSize(3);
        assertThat(startTimes.get(0)).isCloseTo(0.0, offset(0.001));
        assertThat(startTimes.get(1)).isCloseTo(182.0, offset(0.001)); // 180 + 2 gap
        assertThat(startTimes.get(2)).isCloseTo(425.0, offset(0.001)); // 182 + 240 + 3 gap
    }

    @Test
    void shouldComputeStartTimesWithCrossfades() {
        var seq = new AlbumSequence("Album", "Artist");
        seq.addTrack(AlbumTrackEntry.of("Track 1", 180.0));
        seq.addTrack(new AlbumTrackEntry("Track 2", null, 240.0,
                0.0, 5.0, CrossfadeCurve.S_CURVE));

        List<Double> startTimes = seq.getTrackStartTimes();

        // Track 2 starts at 180 + 0 (gap) - 5 (crossfade) = 175
        assertThat(startTimes.get(0)).isCloseTo(0.0, offset(0.001));
        assertThat(startTimes.get(1)).isCloseTo(175.0, offset(0.001));
    }

    @Test
    void shouldGeneratePqSheet() {
        var seq = new AlbumSequence("Test Album", "Test Artist");
        seq.addTrack(new AlbumTrackEntry("Opening", "USRC11111111", 180.0,
                0.0, 0.0, CrossfadeCurve.LINEAR));
        seq.addTrack(new AlbumTrackEntry("Main Theme", "USRC22222222", 240.0,
                2.0, 0.0, CrossfadeCurve.LINEAR));

        String pqSheet = seq.generatePqSheet();

        assertThat(pqSheet).contains("Test Album");
        assertThat(pqSheet).contains("Test Artist");
        assertThat(pqSheet).contains("Opening");
        assertThat(pqSheet).contains("Main Theme");
        assertThat(pqSheet).contains("USRC11111111");
        assertThat(pqSheet).contains("USRC22222222");
        assertThat(pqSheet).contains("Track");
        assertThat(pqSheet).contains("Start");
        assertThat(pqSheet).contains("Duration");
        assertThat(pqSheet).contains("ISRC");
    }

    @Test
    void shouldHandleCustomGapTimings() {
        var seq = new AlbumSequence("Album", "Artist");
        seq.addTrack(AlbumTrackEntry.of("T1", 100.0).withPreGapSeconds(0.0));
        seq.addTrack(AlbumTrackEntry.of("T2", 100.0).withPreGapSeconds(0.0));
        seq.addTrack(AlbumTrackEntry.of("T3", 100.0).withPreGapSeconds(5.0));
        seq.addTrack(AlbumTrackEntry.of("T4", 100.0).withPreGapSeconds(10.0));

        // Total = 100 + 0 + 100 + 5 + 100 + 10 + 100 = 415
        assertThat(seq.getTotalDurationSeconds()).isCloseTo(415.0, offset(0.001));

        List<Double> starts = seq.getTrackStartTimes();
        assertThat(starts.get(0)).isCloseTo(0.0, offset(0.001));
        assertThat(starts.get(1)).isCloseTo(100.0, offset(0.001));
        assertThat(starts.get(2)).isCloseTo(205.0, offset(0.001));
        assertThat(starts.get(3)).isCloseTo(315.0, offset(0.001));
    }

    @Test
    void shouldUpdateAlbumMetadata() {
        var seq = new AlbumSequence("Old Title", "Old Artist");
        seq.setAlbumTitle("New Title");
        seq.setArtist("New Artist");

        assertThat(seq.getAlbumTitle()).isEqualTo("New Title");
        assertThat(seq.getArtist()).isEqualTo("New Artist");
    }

    @Test
    void shouldRejectNullAlbumTitle() {
        assertThatThrownBy(() -> new AlbumSequence(null, "Artist"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullArtist() {
        assertThatThrownBy(() -> new AlbumSequence("Album", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullTrackEntry() {
        var seq = new AlbumSequence("Album", "Artist");
        assertThatThrownBy(() -> seq.addTrack(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReturnUnmodifiableTrackList() {
        var seq = new AlbumSequence("Album", "Artist");
        seq.addTrack(AlbumTrackEntry.of("Track 1", 180.0));

        assertThatThrownBy(() -> seq.getTracks().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldReplaceTrackAtIndex() {
        var seq = new AlbumSequence("Album", "Artist");
        seq.addTrack(AlbumTrackEntry.of("Old", 180.0));

        seq.setTrack(0, AlbumTrackEntry.of("New", 200.0));

        assertThat(seq.getTracks().get(0).title()).isEqualTo("New");
        assertThat(seq.getTracks().get(0).durationSeconds()).isEqualTo(200.0);
    }

    @Test
    void singleTrackAlbumShouldHaveOnlyTrackDuration() {
        var seq = new AlbumSequence("Album", "Artist");
        seq.addTrack(AlbumTrackEntry.of("Solo Track", 300.0));

        // First track's pre-gap is excluded
        assertThat(seq.getTotalDurationSeconds()).isCloseTo(300.0, offset(0.001));
    }
}
