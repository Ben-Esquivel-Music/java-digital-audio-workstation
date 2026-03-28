package com.benesquivelmusic.daw.core.track;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrackColorTest {

    @Test
    void shouldHaveSixteenPaletteColors() {
        assertThat(TrackColor.PALETTE_SIZE).isEqualTo(16);
        assertThat(TrackColor.palette()).hasSize(16);
    }

    @Test
    void shouldReturnPaletteColorByIndex() {
        assertThat(TrackColor.fromPaletteIndex(0)).isEqualTo(TrackColor.RED);
        assertThat(TrackColor.fromPaletteIndex(1)).isEqualTo(TrackColor.ORANGE);
        assertThat(TrackColor.fromPaletteIndex(15)).isEqualTo(TrackColor.SLATE);
    }

    @Test
    void shouldWrapPaletteIndexAround() {
        assertThat(TrackColor.fromPaletteIndex(16)).isEqualTo(TrackColor.RED);
        assertThat(TrackColor.fromPaletteIndex(17)).isEqualTo(TrackColor.ORANGE);
        assertThat(TrackColor.fromPaletteIndex(32)).isEqualTo(TrackColor.RED);
    }

    @Test
    void shouldCreateCustomColor() {
        TrackColor custom = TrackColor.custom("#FF5733", "Coral");

        assertThat(custom.getHexColor()).isEqualTo("#FF5733");
        assertThat(custom.getDisplayName()).isEqualTo("Coral");
        assertThat(custom.isPaletteColor()).isFalse();
        assertThat(custom.getPaletteIndex()).isEqualTo(-1);
    }

    @Test
    void shouldNormalizeCustomColorToUpperCase() {
        TrackColor custom = TrackColor.custom("#ff5733", "Coral");
        assertThat(custom.getHexColor()).isEqualTo("#FF5733");
    }

    @Test
    void shouldRejectInvalidHexColor() {
        assertThatThrownBy(() -> TrackColor.custom("invalid", "Bad"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrackColor.custom("#GGG", "Bad"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrackColor.custom("", "Bad"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullArguments() {
        assertThatThrownBy(() -> TrackColor.custom(null, "Name"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TrackColor.custom("#FF5733", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldFindPaletteColorFromHex() {
        TrackColor found = TrackColor.fromHex("#E74C3C");
        assertThat(found).isEqualTo(TrackColor.RED);
        assertThat(found.isPaletteColor()).isTrue();
    }

    @Test
    void shouldFindPaletteColorFromHexCaseInsensitive() {
        TrackColor found = TrackColor.fromHex("#e74c3c");
        assertThat(found).isEqualTo(TrackColor.RED);
    }

    @Test
    void shouldCreateCustomColorFromHexWhenNotInPalette() {
        TrackColor found = TrackColor.fromHex("#112233");
        assertThat(found.isPaletteColor()).isFalse();
        assertThat(found.getHexColor()).isEqualTo("#112233");
        assertThat(found.getDisplayName()).isEqualTo("Custom");
    }

    @Test
    void shouldRejectInvalidHexInFromHex() {
        assertThatThrownBy(() -> TrackColor.fromHex("bad"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullInFromHex() {
        assertThatThrownBy(() -> TrackColor.fromHex(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldEqualByHexColor() {
        TrackColor a = TrackColor.custom("#AABBCC", "A");
        TrackColor b = TrackColor.custom("#AABBCC", "B");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void shouldNotEqualDifferentHexColors() {
        TrackColor a = TrackColor.custom("#AABBCC", "A");
        TrackColor b = TrackColor.custom("#112233", "B");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void shouldHaveMeaningfulToString() {
        assertThat(TrackColor.RED.toString()).contains("Red").contains("#E74C3C");
    }

    @Test
    void shouldReturnDefensiveCopyOfPalette() {
        TrackColor[] palette = TrackColor.palette();
        TrackColor original = palette[0];
        palette[0] = null;

        assertThat(TrackColor.palette()[0]).isEqualTo(original);
    }

    @Test
    void allPaletteColorsShouldBePaletteColors() {
        TrackColor[] palette = TrackColor.palette();
        for (int i = 0; i < palette.length; i++) {
            assertThat(palette[i].isPaletteColor()).isTrue();
            assertThat(palette[i].getPaletteIndex()).isEqualTo(i);
        }
    }

    @Test
    void allPaletteColorsShouldHaveValidHex() {
        for (TrackColor color : TrackColor.palette()) {
            assertThat(color.getHexColor()).startsWith("#");
            assertThat(color.getHexColor()).hasSize(7);
        }
    }

    @Test
    void allPaletteColorsShouldHaveNonBlankDisplayName() {
        for (TrackColor color : TrackColor.palette()) {
            assertThat(color.getDisplayName()).isNotBlank();
        }
    }
}
