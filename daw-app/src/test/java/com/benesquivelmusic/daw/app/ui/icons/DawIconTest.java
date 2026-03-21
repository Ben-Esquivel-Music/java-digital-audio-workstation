package com.benesquivelmusic.daw.app.ui.icons;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DawIconTest {

    @ParameterizedTest
    @EnumSource(DawIcon.class)
    void everyIconShouldHaveCorrespondingSvgResource(DawIcon icon) {
        String resourcePath = icon.resourcePath();
        try (InputStream stream = IconNode.class.getResourceAsStream(resourcePath)) {
            assertThat(stream)
                    .as("SVG resource for %s at %s", icon.name(), resourcePath)
                    .isNotNull();
        } catch (Exception e) {
            throw new AssertionError("Failed to check resource for " + icon.name(), e);
        }
    }

    @Test
    void allCategoriesShouldBeRepresented() {
        for (IconCategory category : IconCategory.values()) {
            boolean found = false;
            for (DawIcon icon : DawIcon.values()) {
                if (icon.category() == category) {
                    found = true;
                    break;
                }
            }
            assertThat(found)
                    .as("Category %s should have at least one icon", category.name())
                    .isTrue();
        }
    }

    @Test
    void resourcePathShouldFollowConvention() {
        assertThat(DawIcon.PLAY.resourcePath())
                .isEqualTo("/com/benesquivelmusic/daw/app/icons/playback/play.svg");
        assertThat(DawIcon.WAVEFORM.resourcePath())
                .isEqualTo("/com/benesquivelmusic/daw/app/icons/daw/waveform.svg");
        assertThat(DawIcon.AUX_CABLE.resourcePath())
                .isEqualTo("/com/benesquivelmusic/daw/app/icons/connectivity/aux-cable.svg");
    }

    @Test
    void shouldHave210Icons() {
        assertThat(DawIcon.values()).hasSize(210);
    }
}
