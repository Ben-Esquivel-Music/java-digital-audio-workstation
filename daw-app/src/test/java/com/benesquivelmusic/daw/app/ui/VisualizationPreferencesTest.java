package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.VisualizationPreferences.DisplayTile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisualizationPreferencesTest {

    private Preferences prefs;
    private VisualizationPreferences vizPrefs;

    @BeforeEach
    void setUp() throws Exception {
        prefs = Preferences.userRoot().node("vizPrefsTest_" + System.nanoTime());
        vizPrefs = new VisualizationPreferences(prefs);
    }

    @Test
    void shouldRejectNullPreferences() {
        assertThatThrownBy(() -> new VisualizationPreferences(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldDefaultToRowVisible() {
        assertThat(vizPrefs.isRowVisible()).isTrue();
    }

    @Test
    void shouldDefaultAllTilesToVisible() {
        for (DisplayTile tile : DisplayTile.values()) {
            assertThat(vizPrefs.isTileVisible(tile)).isTrue();
        }
    }

    @Test
    void shouldPersistRowVisibility() {
        vizPrefs.setRowVisible(false);

        VisualizationPreferences reloaded = new VisualizationPreferences(prefs);
        assertThat(reloaded.isRowVisible()).isFalse();
    }

    @Test
    void shouldPersistTileVisibility() {
        vizPrefs.setTileVisible(DisplayTile.SPECTRUM, false);
        vizPrefs.setTileVisible(DisplayTile.LOUDNESS, false);

        VisualizationPreferences reloaded = new VisualizationPreferences(prefs);
        assertThat(reloaded.isTileVisible(DisplayTile.SPECTRUM)).isFalse();
        assertThat(reloaded.isTileVisible(DisplayTile.LOUDNESS)).isFalse();
        assertThat(reloaded.isTileVisible(DisplayTile.WAVEFORM)).isTrue();
        assertThat(reloaded.isTileVisible(DisplayTile.LEVELS)).isTrue();
        assertThat(reloaded.isTileVisible(DisplayTile.CORRELATION)).isTrue();
    }

    @Test
    void shouldShowAll() {
        vizPrefs.setTileVisible(DisplayTile.SPECTRUM, false);
        vizPrefs.setTileVisible(DisplayTile.LEVELS, false);

        vizPrefs.showAll();

        for (DisplayTile tile : DisplayTile.values()) {
            assertThat(vizPrefs.isTileVisible(tile)).isTrue();
        }
    }

    @Test
    void shouldHideAll() {
        vizPrefs.hideAll();

        for (DisplayTile tile : DisplayTile.values()) {
            assertThat(vizPrefs.isTileVisible(tile)).isFalse();
        }
    }

    @Test
    void shouldResetToDefaults() {
        vizPrefs.setRowVisible(false);
        vizPrefs.setTileVisible(DisplayTile.SPECTRUM, false);
        vizPrefs.setTileVisible(DisplayTile.CORRELATION, false);

        vizPrefs.resetToDefaults();

        assertThat(vizPrefs.isRowVisible()).isTrue();
        for (DisplayTile tile : DisplayTile.values()) {
            assertThat(vizPrefs.isTileVisible(tile)).isTrue();
        }
    }

    @Test
    void shouldHaveFiveDisplayTiles() {
        assertThat(DisplayTile.values()).hasSize(5);
    }

    @Test
    void shouldContainExpectedDisplayTiles() {
        assertThat(DisplayTile.values()).containsExactly(
                DisplayTile.SPECTRUM,
                DisplayTile.LEVELS,
                DisplayTile.WAVEFORM,
                DisplayTile.LOUDNESS,
                DisplayTile.CORRELATION
        );
    }

    @Test
    void shouldToggleRowVisibility() {
        assertThat(vizPrefs.isRowVisible()).isTrue();
        vizPrefs.setRowVisible(false);
        assertThat(vizPrefs.isRowVisible()).isFalse();
        vizPrefs.setRowVisible(true);
        assertThat(vizPrefs.isRowVisible()).isTrue();
    }

    @Test
    void showAllShouldPersist() {
        vizPrefs.hideAll();
        vizPrefs.showAll();

        VisualizationPreferences reloaded = new VisualizationPreferences(prefs);
        for (DisplayTile tile : DisplayTile.values()) {
            assertThat(reloaded.isTileVisible(tile)).isTrue();
        }
    }

    @Test
    void hideAllShouldPersist() {
        vizPrefs.hideAll();

        VisualizationPreferences reloaded = new VisualizationPreferences(prefs);
        for (DisplayTile tile : DisplayTile.values()) {
            assertThat(reloaded.isTileVisible(tile)).isFalse();
        }
    }

    @Test
    void resetToDefaultsShouldPersist() {
        vizPrefs.setRowVisible(false);
        vizPrefs.hideAll();
        vizPrefs.resetToDefaults();

        VisualizationPreferences reloaded = new VisualizationPreferences(prefs);
        assertThat(reloaded.isRowVisible()).isTrue();
        for (DisplayTile tile : DisplayTile.values()) {
            assertThat(reloaded.isTileVisible(tile)).isTrue();
        }
    }
}
