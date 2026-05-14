package com.benesquivelmusic.daw.app.ui.icons;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the {@link DawgIcon} factory contract from the UI Design
 * Book §3.6 (Iconography). The key invariants checked here are the
 * ones an integrator can rely on:
 *
 * <ul>
 *   <li>The factory resolves a bundled Lucide name into a sized
 *       {@code Region} (16 / 20 / 24 px nominal).</li>
 *   <li>Unknown names fail fast with a useful message.</li>
 *   <li>The region exposes the {@code -fx-icon-color} styleable
 *       property defaulting to {@code -text-hi} so theming works.</li>
 * </ul>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class DawgIconTest {

    @Test
    void shouldResolvePlayIconAtSize16() {
        DawgIcon icon = DawgIcon.of("play", DawgIcon.Size.SIZE_16);

        assertThat(icon.getPrefWidth()).isEqualTo(16.0);
        assertThat(icon.getPrefHeight()).isEqualTo(16.0);
        assertThat(icon.getMinWidth()).isEqualTo(16.0);
        assertThat(icon.getMinHeight()).isEqualTo(16.0);
        assertThat(icon.getMaxWidth()).isEqualTo(16.0);
        assertThat(icon.getMaxHeight()).isEqualTo(16.0);
        assertThat(icon.iconName()).isEqualTo("play");
        assertThat(icon.size()).isEqualTo(DawgIcon.Size.SIZE_16);
        assertThat(icon.getStyleClass()).contains("dawg-icon", "dawg-icon-play");
    }

    @Test
    void shouldResolveAllThreeNominalSizes() {
        assertThat(DawgIcon.of("repeat", DawgIcon.Size.SIZE_16).getPrefWidth()).isEqualTo(16.0);
        assertThat(DawgIcon.of("repeat", DawgIcon.Size.SIZE_20).getPrefWidth()).isEqualTo(20.0);
        assertThat(DawgIcon.of("repeat", DawgIcon.Size.SIZE_24).getPrefWidth()).isEqualTo(24.0);
    }

    @Test
    void shouldRejectUnknownIconName() {
        assertThatThrownBy(() -> DawgIcon.of("this-icon-does-not-exist", DawgIcon.Size.SIZE_16))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("this-icon-does-not-exist");
    }

    @Test
    void shouldRejectBlankIconName() {
        assertThatThrownBy(() -> DawgIcon.of("", DawgIcon.Size.SIZE_16))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullSize() {
        assertThatThrownBy(() -> DawgIcon.of("play", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldExposeStyleableIconColorProperty() {
        DawgIcon icon = DawgIcon.of("play", DawgIcon.Size.SIZE_16);
        // Default is the design-book -text-hi token (#ECEEF2).
        assertThat(icon.getIconColor()).isNotNull();
        // Scene attachment is sufficient for CSS to dispatch later;
        // direct setIconColor must always work. Assign to a local to
        // prevent GC from collecting the scene during the test.
        @SuppressWarnings("unused")
        Scene scene = new Scene(new StackPane(icon));
        icon.setIconColor(javafx.scene.paint.Color.RED);
        assertThat(icon.getIconColor()).isEqualTo(javafx.scene.paint.Color.RED);
    }

    @Test
    void shouldRenderIconBackedByAtLeastOneShape() {
        // A Lucide icon with only one <path> (e.g. play).
        DawgIcon icon = DawgIcon.of("play", DawgIcon.Size.SIZE_16);
        assertThat(icon.getChildrenUnmodifiable()).isNotEmpty();

        // A Lucide icon with mixed <path> + <circle> shapes (info).
        DawgIcon info = DawgIcon.of("info", DawgIcon.Size.SIZE_16);
        assertThat(info.getChildrenUnmodifiable()).isNotEmpty();
    }

    @Test
    void shouldToggleActivePseudoClass() {
        DawgIcon icon = DawgIcon.of("play", DawgIcon.Size.SIZE_16);
        assertThat(icon.isActive()).isFalse();

        icon.setActive(true);
        assertThat(icon.isActive()).isTrue();
        assertThat(icon.getPseudoClassStates().stream()
                .anyMatch(pc -> "active".equals(pc.getPseudoClassName()))).isTrue();

        icon.setActive(false);
        assertThat(icon.isActive()).isFalse();
        assertThat(icon.getPseudoClassStates().stream()
                .anyMatch(pc -> "active".equals(pc.getPseudoClassName()))).isFalse();
    }
}
