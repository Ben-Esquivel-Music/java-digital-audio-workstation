package com.benesquivelmusic.daw.app.ui.theme;

import org.junit.jupiter.api.Test;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 277 — the token theme survives a "restart": set the active
 * theme, then rebuild {@link ThemeManager} from the <em>same</em>
 * {@link Preferences} node and assert it deserialises back to the
 * persisted value.
 *
 * <p>This is intentionally a pure unit test — {@link ThemeManager}'s
 * persistence path is toolkit-free, so no JavaFX is needed. That is a
 * feature: theme persistence is verifiable without the FX harness.</p>
 */
final class ThemePersistenceTest {

    @Test
    void activeThemeRoundTripsThroughPreferences() throws BackingStoreException {
        Preferences node = Preferences.userRoot()
                .node("themePersistenceTest_" + System.nanoTime());
        try {
            ThemeManager first = new ThemeManager(node);
            // Fresh node → design-book default.
            assertThat(first.getActiveTheme())
                    .isEqualTo(ThemeManager.DEFAULT_THEME)
                    .isEqualTo(ThemeManager.Theme.ONYX_REFINED);

            first.setActiveTheme(ThemeManager.Theme.STUDIO_SLATE);

            // Simulate a restart: a brand-new manager over the same node.
            ThemeManager restarted = new ThemeManager(node);
            assertThat(restarted.getActiveTheme())
                    .as("the persisted theme must survive a ThemeManager rebuild")
                    .isEqualTo(ThemeManager.Theme.STUDIO_SLATE);
        } finally {
            node.removeNode();
        }
    }

    @Test
    void unknownPersistedValueFallsBackToDefault() throws BackingStoreException {
        Preferences node = Preferences.userRoot()
                .node("themePersistenceTestBad_" + System.nanoTime());
        try {
            node.put(ThemeManager.PREF_KEY, "NOT_A_REAL_THEME");
            ThemeManager mgr = new ThemeManager(node);
            assertThat(mgr.getActiveTheme())
                    .as("a corrupt/unknown persisted value must fall back to the default")
                    .isEqualTo(ThemeManager.DEFAULT_THEME);
        } finally {
            node.removeNode();
        }
    }

    @Test
    void persistenceKeyIsDistinctFromStory194ThemeIdKey() {
        // The two theme systems must not share a preferences key
        // (ThemeManager = token CSS palette, SettingsModel.KEY_THEME_ID
        // = story 194 WCAG JSON registry).
        assertThat(ThemeManager.PREF_KEY)
                .isEqualTo("appearance.tokenTheme")
                .isNotEqualTo("appearance.themeId");
    }
}
