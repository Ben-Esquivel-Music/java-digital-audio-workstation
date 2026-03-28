package com.benesquivelmusic.daw.core.marker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarkerRangeTest {

    @Test
    void shouldCreateMarkerRange() {
        MarkerRange range = new MarkerRange("Verse 1", 0.0, 16.0, MarkerType.SECTION);

        assertThat(range.getName()).isEqualTo("Verse 1");
        assertThat(range.getStartPositionInBeats()).isEqualTo(0.0);
        assertThat(range.getEndPositionInBeats()).isEqualTo(16.0);
        assertThat(range.getType()).isEqualTo(MarkerType.SECTION);
        assertThat(range.getColor()).isEqualTo(MarkerType.SECTION.getDefaultColor());
        assertThat(range.getId()).isNotNull();
    }

    @Test
    void shouldCalculateDuration() {
        MarkerRange range = new MarkerRange("Chorus", 16.0, 32.0, MarkerType.SECTION);
        assertThat(range.getDurationInBeats()).isEqualTo(16.0);
    }

    @Test
    void shouldSetAndGetName() {
        MarkerRange range = new MarkerRange("Verse", 0.0, 16.0, MarkerType.SECTION);
        range.setName("Verse 1");
        assertThat(range.getName()).isEqualTo("Verse 1");
    }

    @Test
    void shouldSetRange() {
        MarkerRange range = new MarkerRange("Chorus", 16.0, 32.0, MarkerType.SECTION);
        range.setRange(8.0, 24.0);
        assertThat(range.getStartPositionInBeats()).isEqualTo(8.0);
        assertThat(range.getEndPositionInBeats()).isEqualTo(24.0);
    }

    @Test
    void shouldSetAndGetColor() {
        MarkerRange range = new MarkerRange("Bridge", 32.0, 48.0, MarkerType.SECTION);
        range.setColor("#FF5733");
        assertThat(range.getColor()).isEqualTo("#FF5733");
    }

    @Test
    void shouldSetAndGetType() {
        MarkerRange range = new MarkerRange("A", 0.0, 16.0, MarkerType.REHEARSAL);
        range.setType(MarkerType.ARRANGEMENT);
        assertThat(range.getType()).isEqualTo(MarkerType.ARRANGEMENT);
    }

    @Test
    void shouldContainPositionWithinRange() {
        MarkerRange range = new MarkerRange("Verse", 4.0, 20.0, MarkerType.SECTION);

        assertThat(range.contains(4.0)).isTrue();   // start (inclusive)
        assertThat(range.contains(12.0)).isTrue();   // middle
        assertThat(range.contains(19.99)).isTrue();   // near end
        assertThat(range.contains(20.0)).isFalse();  // end (exclusive)
        assertThat(range.contains(3.99)).isFalse();  // before start
        assertThat(range.contains(21.0)).isFalse();  // after end
    }

    @Test
    void shouldHaveUniqueIds() {
        MarkerRange range1 = new MarkerRange("Verse", 0.0, 16.0, MarkerType.SECTION);
        MarkerRange range2 = new MarkerRange("Verse", 0.0, 16.0, MarkerType.SECTION);
        assertThat(range1.getId()).isNotEqualTo(range2.getId());
    }

    @Test
    void shouldRejectNullName() {
        assertThatThrownBy(() -> new MarkerRange(null, 0.0, 16.0, MarkerType.SECTION))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullNameInSetter() {
        MarkerRange range = new MarkerRange("Verse", 0.0, 16.0, MarkerType.SECTION);
        assertThatThrownBy(() -> range.setName(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullType() {
        assertThatThrownBy(() -> new MarkerRange("Verse", 0.0, 16.0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullTypeInSetter() {
        MarkerRange range = new MarkerRange("Verse", 0.0, 16.0, MarkerType.SECTION);
        assertThatThrownBy(() -> range.setType(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullColor() {
        MarkerRange range = new MarkerRange("Verse", 0.0, 16.0, MarkerType.SECTION);
        assertThatThrownBy(() -> range.setColor(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNegativeStartPosition() {
        assertThatThrownBy(() -> new MarkerRange("Bad", -1.0, 16.0, MarkerType.SECTION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectEndNotGreaterThanStart() {
        assertThatThrownBy(() -> new MarkerRange("Bad", 16.0, 16.0, MarkerType.SECTION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MarkerRange("Bad", 16.0, 8.0, MarkerType.SECTION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidRangeInSetter() {
        MarkerRange range = new MarkerRange("Verse", 0.0, 16.0, MarkerType.SECTION);
        assertThatThrownBy(() -> range.setRange(-1.0, 16.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> range.setRange(16.0, 16.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> range.setRange(16.0, 8.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldCompareByStartPosition() {
        MarkerRange earlier = new MarkerRange("Verse", 0.0, 16.0, MarkerType.SECTION);
        MarkerRange later = new MarkerRange("Chorus", 16.0, 32.0, MarkerType.SECTION);

        assertThat(earlier.compareTo(later)).isNegative();
        assertThat(later.compareTo(earlier)).isPositive();
    }

    @Test
    void shouldUseIdForEquality() {
        MarkerRange range = new MarkerRange("Verse", 0.0, 16.0, MarkerType.SECTION);
        MarkerRange different = new MarkerRange("Verse", 0.0, 16.0, MarkerType.SECTION);

        assertThat(range).isEqualTo(range);
        assertThat(range).isNotEqualTo(different);
    }

    @Test
    void shouldProvideToString() {
        MarkerRange range = new MarkerRange("Chorus", 16.0, 32.0, MarkerType.SECTION);
        assertThat(range.toString()).contains("Chorus");
        assertThat(range.toString()).contains("16.0");
        assertThat(range.toString()).contains("32.0");
    }
}
