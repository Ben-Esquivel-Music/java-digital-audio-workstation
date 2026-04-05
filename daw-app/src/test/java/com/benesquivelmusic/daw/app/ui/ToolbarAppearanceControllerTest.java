package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link ToolbarAppearanceController} helper class that can be
 * exercised without a live JavaFX scene or toolkit.
 *
 * <p>Verifies that the controller class is loadable, its inner record types
 * are accessible, and its package-visible constants carry the expected
 * values.</p>
 */
class ToolbarAppearanceControllerTest {

    @Test
    void shouldLoadToolbarAppearanceControllerClass() {
        Class<?> clazz = ToolbarAppearanceController.class;
        assertThat(clazz).isNotNull();
        assertThat(java.lang.reflect.Modifier.isFinal(clazz.getModifiers())).isTrue();
    }

    @Test
    void shouldExposeTransportButtonsRecord() {
        Class<?> recordClass = ToolbarAppearanceController.TransportButtons.class;
        assertThat(recordClass).isNotNull();
        assertThat(recordClass.isRecord()).isTrue();
    }

    @Test
    void shouldExposeToolbarButtonsRecord() {
        Class<?> recordClass = ToolbarAppearanceController.ToolbarButtons.class;
        assertThat(recordClass).isNotNull();
        assertThat(recordClass.isRecord()).isTrue();
    }

    @Test
    void shouldExposeAppearanceLabelsRecord() {
        Class<?> recordClass = ToolbarAppearanceController.AppearanceLabels.class;
        assertThat(recordClass).isNotNull();
        assertThat(recordClass.isRecord()).isTrue();
    }

    @Test
    void shouldExposeOverflowGroupsRecord() {
        Class<?> recordClass = ToolbarAppearanceController.OverflowGroups.class;
        assertThat(recordClass).isNotNull();
        assertThat(recordClass.isRecord()).isTrue();
    }

    @Test
    void constantsShouldHaveExpectedValues() {
        assertThat(ToolbarAppearanceController.TRANSPORT_ICON_SIZE).isEqualTo(14);
        assertThat(ToolbarAppearanceController.TOOLBAR_ICON_SIZE).isEqualTo(14);
        assertThat(ToolbarAppearanceController.PANEL_ICON_SIZE).isEqualTo(16);
        assertThat(ToolbarAppearanceController.TOOLBAR_OVERFLOW_THRESHOLD).isEqualTo(1280.0);
        assertThat(ToolbarAppearanceController.TOOLTIP_SHOW_DELAY.toMillis()).isEqualTo(300.0);
    }
}
