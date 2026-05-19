package com.benesquivelmusic.daw.app.ui.density;

import org.junit.jupiter.api.Test;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 278 — the global density survives a "restart": set the active
 * density, then rebuild {@link DensityManager} from the <em>same</em>
 * {@link Preferences} node and assert it deserialises back to the
 * persisted value.
 *
 * <p>Intentionally a pure unit test — {@link DensityManager}'s
 * persistence path is toolkit-free, so no JavaFX is needed (mirrors
 * {@code ThemePersistenceTest}). That is a feature: density persistence
 * is verifiable without the FX harness.</p>
 */
final class DensityPersistenceTest {

    @Test
    void freshNodeYieldsTheDesignBookDefault() throws BackingStoreException {
        Preferences node = Preferences.userRoot()
                .node("densityPersistenceTest_" + System.nanoTime());
        try {
            DensityManager mgr = new DensityManager(node);
            assertThat(mgr.getActiveDensity())
                    .as("a fresh node must default to the design-book default")
                    .isEqualTo(DensityManager.DEFAULT_DENSITY)
                    .isEqualTo(DensityMode.COMFORTABLE);
        } finally {
            node.removeNode();
        }
    }

    @Test
    void activeDensityRoundTripsThroughPreferences() throws BackingStoreException {
        Preferences node = Preferences.userRoot()
                .node("densityPersistenceTestRT_" + System.nanoTime());
        try {
            DensityManager first = new DensityManager(node);
            first.setActiveDensity(DensityMode.TOUCH);

            // Simulate a restart: a brand-new manager over the same node.
            DensityManager restarted = new DensityManager(node);
            assertThat(restarted.getActiveDensity())
                    .as("the persisted density must survive a DensityManager rebuild")
                    .isEqualTo(DensityMode.TOUCH);
        } finally {
            node.removeNode();
        }
    }

    @Test
    void unknownPersistedValueFallsBackToDefault() throws BackingStoreException {
        Preferences node = Preferences.userRoot()
                .node("densityPersistenceTestBad_" + System.nanoTime());
        try {
            node.put(DensityManager.PREF_KEY, "NOT_A_REAL_DENSITY");
            DensityManager mgr = new DensityManager(node);
            assertThat(mgr.getActiveDensity())
                    .as("a corrupt/unknown persisted value must fall back to the default")
                    .isEqualTo(DensityManager.DEFAULT_DENSITY);
        } finally {
            node.removeNode();
        }
    }

    @Test
    void persistenceKeyIsDistinctFromThemeAndStory194Keys() {
        // Density, the token theme (story 277) and the WCAG JSON registry
        // (story 194) are three separate systems and must not share a
        // preferences key (mirrors ThemePersistenceTest's analogous
        // distinctness assertion).
        assertThat(DensityManager.PREF_KEY)
                .isEqualTo("appearance.density")
                .isNotEqualTo("appearance.tokenTheme")  // ThemeManager.PREF_KEY
                .isNotEqualTo("appearance.themeId");     // SettingsModel.KEY_THEME_ID
    }
}
