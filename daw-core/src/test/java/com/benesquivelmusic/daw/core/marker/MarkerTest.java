package com.benesquivelmusic.daw.core.marker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarkerTest {

    @Test
    void shouldCreateMarkerWithDefaults() {
        Marker marker = new Marker("Verse 1", 0.0, MarkerType.SECTION);

        assertThat(marker.getName()).isEqualTo("Verse 1");
        assertThat(marker.getPositionInBeats()).isEqualTo(0.0);
        assertThat(marker.getType()).isEqualTo(MarkerType.SECTION);
        assertThat(marker.getColor()).isEqualTo(MarkerType.SECTION.getDefaultColor());
        assertThat(marker.getId()).isNotNull();
    }

    @Test
    void shouldAssignDefaultColorFromType() {
        Marker sectionMarker = new Marker("Chorus", 16.0, MarkerType.SECTION);
        assertThat(sectionMarker.getColor()).isEqualTo("#3498DB");

        Marker rehearsalMarker = new Marker("A", 0.0, MarkerType.REHEARSAL);
        assertThat(rehearsalMarker.getColor()).isEqualTo("#E67E22");

        Marker arrangementMarker = new Marker("Intro", 0.0, MarkerType.ARRANGEMENT);
        assertThat(arrangementMarker.getColor()).isEqualTo("#2ECC71");
    }

    @Test
    void shouldSetAndGetName() {
        Marker marker = new Marker("Verse 1", 0.0, MarkerType.SECTION);
        marker.setName("Verse 2");
        assertThat(marker.getName()).isEqualTo("Verse 2");
    }

    @Test
    void shouldSetAndGetPosition() {
        Marker marker = new Marker("Chorus", 16.0, MarkerType.SECTION);
        marker.setPositionInBeats(32.0);
        assertThat(marker.getPositionInBeats()).isEqualTo(32.0);
    }

    @Test
    void shouldSetAndGetColor() {
        Marker marker = new Marker("Bridge", 48.0, MarkerType.SECTION);
        marker.setColor("#FF5733");
        assertThat(marker.getColor()).isEqualTo("#FF5733");
    }

    @Test
    void shouldSetAndGetType() {
        Marker marker = new Marker("A", 0.0, MarkerType.REHEARSAL);
        marker.setType(MarkerType.SECTION);
        assertThat(marker.getType()).isEqualTo(MarkerType.SECTION);
    }

    @Test
    void shouldHaveUniqueIds() {
        Marker marker1 = new Marker("Verse 1", 0.0, MarkerType.SECTION);
        Marker marker2 = new Marker("Verse 1", 0.0, MarkerType.SECTION);
        assertThat(marker1.getId()).isNotEqualTo(marker2.getId());
    }

    @Test
    void shouldRejectNullName() {
        assertThatThrownBy(() -> new Marker(null, 0.0, MarkerType.SECTION))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullNameInSetter() {
        Marker marker = new Marker("Verse", 0.0, MarkerType.SECTION);
        assertThatThrownBy(() -> marker.setName(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullType() {
        assertThatThrownBy(() -> new Marker("Verse", 0.0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullTypeInSetter() {
        Marker marker = new Marker("Verse", 0.0, MarkerType.SECTION);
        assertThatThrownBy(() -> marker.setType(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullColor() {
        Marker marker = new Marker("Verse", 0.0, MarkerType.SECTION);
        assertThatThrownBy(() -> marker.setColor(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNegativePosition() {
        assertThatThrownBy(() -> new Marker("Bad", -1.0, MarkerType.SECTION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativePositionInSetter() {
        Marker marker = new Marker("Verse", 0.0, MarkerType.SECTION);
        assertThatThrownBy(() -> marker.setPositionInBeats(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldCompareByPosition() {
        Marker earlier = new Marker("Verse", 0.0, MarkerType.SECTION);
        Marker later = new Marker("Chorus", 16.0, MarkerType.SECTION);

        assertThat(earlier.compareTo(later)).isNegative();
        assertThat(later.compareTo(earlier)).isPositive();
    }

    @Test
    void shouldCompareEqualPositions() {
        Marker a = new Marker("A", 8.0, MarkerType.SECTION);
        Marker b = new Marker("B", 8.0, MarkerType.SECTION);
        assertThat(a.compareTo(b)).isZero();
    }

    @Test
    void shouldUseIdForEquality() {
        Marker marker = new Marker("Verse", 0.0, MarkerType.SECTION);
        Marker different = new Marker("Verse", 0.0, MarkerType.SECTION);

        assertThat(marker).isEqualTo(marker);
        assertThat(marker).isNotEqualTo(different);
    }

    @Test
    void shouldProvideToString() {
        Marker marker = new Marker("Chorus", 16.0, MarkerType.SECTION);
        assertThat(marker.toString()).contains("Chorus");
        assertThat(marker.toString()).contains("16.0");
        assertThat(marker.toString()).contains("SECTION");
    }

    @Test
    void shouldAcceptZeroPosition() {
        Marker marker = new Marker("Start", 0.0, MarkerType.SECTION);
        assertThat(marker.getPositionInBeats()).isEqualTo(0.0);
    }
}
