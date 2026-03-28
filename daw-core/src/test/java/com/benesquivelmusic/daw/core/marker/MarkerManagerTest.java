package com.benesquivelmusic.daw.core.marker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarkerManagerTest {

    private MarkerManager manager;

    @BeforeEach
    void setUp() {
        manager = new MarkerManager();
    }

    // ── Add / remove / list markers ─────────────────────────────────────────

    @Test
    void shouldStartWithNoMarkers() {
        assertThat(manager.getMarkers()).isEmpty();
        assertThat(manager.getMarkerCount()).isZero();
    }

    @Test
    void shouldAddMarker() {
        Marker marker = new Marker("Verse 1", 0.0, MarkerType.SECTION);
        manager.addMarker(marker);

        assertThat(manager.getMarkerCount()).isEqualTo(1);
        assertThat(manager.getMarkers().get(0)).isEqualTo(marker);
    }

    @Test
    void shouldRemoveMarker() {
        Marker marker = new Marker("Verse 1", 0.0, MarkerType.SECTION);
        manager.addMarker(marker);

        assertThat(manager.removeMarker(marker)).isTrue();
        assertThat(manager.getMarkerCount()).isZero();
    }

    @Test
    void shouldReturnFalseWhenRemovingNonexistentMarker() {
        Marker marker = new Marker("Verse 1", 0.0, MarkerType.SECTION);
        assertThat(manager.removeMarker(marker)).isFalse();
    }

    @Test
    void shouldRejectNullMarkerOnAdd() {
        assertThatThrownBy(() -> manager.addMarker(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReturnUnmodifiableMarkerList() {
        assertThatThrownBy(() -> manager.getMarkers().add(
                new Marker("Bad", 0.0, MarkerType.SECTION)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── Sorted order ────────────────────────────────────────────────────────

    @Test
    void shouldMaintainSortedOrder() {
        Marker chorus = new Marker("Chorus", 16.0, MarkerType.SECTION);
        Marker verse = new Marker("Verse", 0.0, MarkerType.SECTION);
        Marker bridge = new Marker("Bridge", 32.0, MarkerType.SECTION);

        manager.addMarker(chorus);
        manager.addMarker(verse);
        manager.addMarker(bridge);

        List<Marker> markers = manager.getMarkers();
        assertThat(markers.get(0).getName()).isEqualTo("Verse");
        assertThat(markers.get(1).getName()).isEqualTo("Chorus");
        assertThat(markers.get(2).getName()).isEqualTo("Bridge");
    }

    @Test
    void shouldResortAfterPositionChange() {
        Marker a = new Marker("A", 0.0, MarkerType.SECTION);
        Marker b = new Marker("B", 16.0, MarkerType.SECTION);
        manager.addMarker(a);
        manager.addMarker(b);

        a.setPositionInBeats(32.0);
        manager.resort();

        assertThat(manager.getMarkers().get(0).getName()).isEqualTo("B");
        assertThat(manager.getMarkers().get(1).getName()).isEqualTo("A");
    }

    // ── Navigation (next/previous) ──────────────────────────────────────────

    @Test
    void shouldFindNextMarker() {
        manager.addMarker(new Marker("Verse", 0.0, MarkerType.SECTION));
        manager.addMarker(new Marker("Chorus", 16.0, MarkerType.SECTION));
        manager.addMarker(new Marker("Bridge", 32.0, MarkerType.SECTION));

        Optional<Marker> next = manager.getNextMarker(10.0);
        assertThat(next).isPresent();
        assertThat(next.get().getName()).isEqualTo("Chorus");
    }

    @Test
    void shouldReturnEmptyWhenNoNextMarker() {
        manager.addMarker(new Marker("Verse", 0.0, MarkerType.SECTION));
        manager.addMarker(new Marker("Chorus", 16.0, MarkerType.SECTION));

        Optional<Marker> next = manager.getNextMarker(20.0);
        assertThat(next).isEmpty();
    }

    @Test
    void shouldFindPreviousMarker() {
        manager.addMarker(new Marker("Verse", 0.0, MarkerType.SECTION));
        manager.addMarker(new Marker("Chorus", 16.0, MarkerType.SECTION));
        manager.addMarker(new Marker("Bridge", 32.0, MarkerType.SECTION));

        Optional<Marker> previous = manager.getPreviousMarker(20.0);
        assertThat(previous).isPresent();
        assertThat(previous.get().getName()).isEqualTo("Chorus");
    }

    @Test
    void shouldReturnEmptyWhenNoPreviousMarker() {
        manager.addMarker(new Marker("Verse", 4.0, MarkerType.SECTION));

        Optional<Marker> previous = manager.getPreviousMarker(2.0);
        assertThat(previous).isEmpty();
    }

    @Test
    void shouldNotReturnMarkerAtExactPositionAsNext() {
        manager.addMarker(new Marker("Chorus", 16.0, MarkerType.SECTION));

        Optional<Marker> next = manager.getNextMarker(16.0);
        assertThat(next).isEmpty();
    }

    @Test
    void shouldNotReturnMarkerAtExactPositionAsPrevious() {
        manager.addMarker(new Marker("Chorus", 16.0, MarkerType.SECTION));

        Optional<Marker> previous = manager.getPreviousMarker(16.0);
        assertThat(previous).isEmpty();
    }

    @Test
    void shouldNavigateWithEmptyMarkerList() {
        assertThat(manager.getNextMarker(0.0)).isEmpty();
        assertThat(manager.getPreviousMarker(10.0)).isEmpty();
    }

    // ── Find by ID ──────────────────────────────────────────────────────────

    @Test
    void shouldFindMarkerById() {
        Marker marker = new Marker("Verse", 0.0, MarkerType.SECTION);
        manager.addMarker(marker);

        Optional<Marker> found = manager.findMarkerById(marker.getId());
        assertThat(found).isPresent();
        assertThat(found.get()).isEqualTo(marker);
    }

    @Test
    void shouldReturnEmptyForUnknownId() {
        assertThat(manager.findMarkerById("nonexistent")).isEmpty();
    }

    @Test
    void shouldRejectNullIdInFindMarker() {
        assertThatThrownBy(() -> manager.findMarkerById(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Filter by type ──────────────────────────────────────────────────────

    @Test
    void shouldFilterMarkersByType() {
        manager.addMarker(new Marker("Verse", 0.0, MarkerType.SECTION));
        manager.addMarker(new Marker("A", 0.0, MarkerType.REHEARSAL));
        manager.addMarker(new Marker("Chorus", 16.0, MarkerType.SECTION));

        List<Marker> sections = manager.getMarkersByType(MarkerType.SECTION);
        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).getName()).isEqualTo("Verse");
        assertThat(sections.get(1).getName()).isEqualTo("Chorus");
    }

    @Test
    void shouldRejectNullTypeInFilter() {
        assertThatThrownBy(() -> manager.getMarkersByType(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Clear markers ───────────────────────────────────────────────────────

    @Test
    void shouldClearAllMarkers() {
        manager.addMarker(new Marker("Verse", 0.0, MarkerType.SECTION));
        manager.addMarker(new Marker("Chorus", 16.0, MarkerType.SECTION));
        manager.clearMarkers();

        assertThat(manager.getMarkerCount()).isZero();
    }

    // ── Marker range operations ─────────────────────────────────────────────

    @Test
    void shouldStartWithNoRanges() {
        assertThat(manager.getMarkerRanges()).isEmpty();
        assertThat(manager.getMarkerRangeCount()).isZero();
    }

    @Test
    void shouldAddMarkerRange() {
        MarkerRange range = new MarkerRange("Verse 1", 0.0, 16.0, MarkerType.SECTION);
        manager.addMarkerRange(range);

        assertThat(manager.getMarkerRangeCount()).isEqualTo(1);
        assertThat(manager.getMarkerRanges().get(0)).isEqualTo(range);
    }

    @Test
    void shouldRemoveMarkerRange() {
        MarkerRange range = new MarkerRange("Verse 1", 0.0, 16.0, MarkerType.SECTION);
        manager.addMarkerRange(range);

        assertThat(manager.removeMarkerRange(range)).isTrue();
        assertThat(manager.getMarkerRangeCount()).isZero();
    }

    @Test
    void shouldMaintainSortedRangeOrder() {
        MarkerRange chorus = new MarkerRange("Chorus", 16.0, 32.0, MarkerType.SECTION);
        MarkerRange verse = new MarkerRange("Verse", 0.0, 16.0, MarkerType.SECTION);

        manager.addMarkerRange(chorus);
        manager.addMarkerRange(verse);

        assertThat(manager.getMarkerRanges().get(0).getName()).isEqualTo("Verse");
        assertThat(manager.getMarkerRanges().get(1).getName()).isEqualTo("Chorus");
    }

    @Test
    void shouldFindRangesAtPosition() {
        manager.addMarkerRange(new MarkerRange("Verse", 0.0, 16.0, MarkerType.SECTION));
        manager.addMarkerRange(new MarkerRange("Chorus", 16.0, 32.0, MarkerType.SECTION));

        List<MarkerRange> atVerse = manager.getRangesAtPosition(8.0);
        assertThat(atVerse).hasSize(1);
        assertThat(atVerse.get(0).getName()).isEqualTo("Verse");

        List<MarkerRange> atChorus = manager.getRangesAtPosition(16.0);
        assertThat(atChorus).hasSize(1);
        assertThat(atChorus.get(0).getName()).isEqualTo("Chorus");
    }

    @Test
    void shouldReturnEmptyForPositionOutsideRanges() {
        manager.addMarkerRange(new MarkerRange("Verse", 0.0, 16.0, MarkerType.SECTION));
        assertThat(manager.getRangesAtPosition(20.0)).isEmpty();
    }

    @Test
    void shouldFindMarkerRangeById() {
        MarkerRange range = new MarkerRange("Verse", 0.0, 16.0, MarkerType.SECTION);
        manager.addMarkerRange(range);

        Optional<MarkerRange> found = manager.findMarkerRangeById(range.getId());
        assertThat(found).isPresent();
        assertThat(found.get()).isEqualTo(range);
    }

    @Test
    void shouldReturnEmptyForUnknownRangeId() {
        assertThat(manager.findMarkerRangeById("nonexistent")).isEmpty();
    }

    @Test
    void shouldRejectNullIdInFindRange() {
        assertThatThrownBy(() -> manager.findMarkerRangeById(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullRange() {
        assertThatThrownBy(() -> manager.addMarkerRange(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldClearAllRanges() {
        manager.addMarkerRange(new MarkerRange("Verse", 0.0, 16.0, MarkerType.SECTION));
        manager.addMarkerRange(new MarkerRange("Chorus", 16.0, 32.0, MarkerType.SECTION));
        manager.clearMarkerRanges();

        assertThat(manager.getMarkerRangeCount()).isZero();
    }

    @Test
    void shouldReturnUnmodifiableRangeList() {
        assertThatThrownBy(() -> manager.getMarkerRanges().add(
                new MarkerRange("Bad", 0.0, 16.0, MarkerType.SECTION)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
