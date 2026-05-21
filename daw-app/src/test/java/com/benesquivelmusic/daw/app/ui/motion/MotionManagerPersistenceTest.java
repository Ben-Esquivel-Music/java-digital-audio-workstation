package com.benesquivelmusic.daw.app.ui.motion;

import com.benesquivelmusic.daw.app.ui.density.DensityManager;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 279 — the global Reduce Motion flag survives a "restart": set the
 * flag, then rebuild {@link MotionManager} from the <em>same</em>
 * {@link Preferences} node and assert it deserialises back to the
 * persisted value.
 *
 * <p>Intentionally a pure unit test — {@link MotionManager}'s persistence
 * path is toolkit-free, so no JavaFX is needed (mirrors
 * {@code DensityPersistenceTest}). That is a feature: Reduce Motion
 * persistence is verifiable without the FX harness.</p>
 */
final class MotionManagerPersistenceTest {

    /** An OS hint that always reports "undetected" — isolates the test from the real OS. */
    private static final OsMotionHint NO_HINT = Optional::empty;

    @Test
    void freshNodeDefaultsToMotionAllowed() throws BackingStoreException {
        Preferences node = Preferences.userRoot()
                .node("motionPersistenceTest_" + System.nanoTime());
        try {
            MotionManager mgr = new MotionManager(node, NO_HINT);
            assertThat(mgr.isReduceMotion())
                    .as("a fresh node with no OS hint must default to motion allowed")
                    .isEqualTo(MotionManager.DEFAULT_REDUCE_MOTION)
                    .isFalse();
        } finally {
            node.removeNode();
        }
    }

    @Test
    void reduceMotionRoundTripsThroughPreferences() throws BackingStoreException {
        Preferences node = Preferences.userRoot()
                .node("motionPersistenceTestRT_" + System.nanoTime());
        try {
            MotionManager first = new MotionManager(node, NO_HINT);
            first.setReduceMotion(true);

            // Simulate a restart: a brand-new manager over the same node.
            MotionManager restarted = new MotionManager(node, NO_HINT);
            assertThat(restarted.isReduceMotion())
                    .as("the persisted Reduce Motion flag must survive a rebuild")
                    .isTrue();
        } finally {
            node.removeNode();
        }
    }

    @Test
    void persistedFalseAlsoSurvivesRestart() throws BackingStoreException {
        // A persisted `false` must round-trip too — the restore() path
        // distinguishes "key present, value false" from "key absent".
        Preferences node = Preferences.userRoot()
                .node("motionPersistenceTestFalse_" + System.nanoTime());
        try {
            MotionManager first = new MotionManager(node, NO_HINT);
            first.setReduceMotion(true);
            first.setReduceMotion(false);

            // Even with an OS hint that says "reduce motion", the
            // persisted explicit `false` wins after a restart.
            MotionManager restarted =
                    new MotionManager(node, () -> Optional.of(true));
            assertThat(restarted.isReduceMotion())
                    .as("a persisted explicit false must override the OS hint")
                    .isFalse();
        } finally {
            node.removeNode();
        }
    }

    @Test
    void persistenceKeyIsDistinctFromThemeAndDensityKeys() {
        // Reduce Motion, the token theme (story 277), the density profile
        // (story 278) and the WCAG JSON registry (story 194) are four
        // separate systems and must not share a preferences key (mirrors
        // DensityPersistenceTest's analogous distinctness assertion).
        assertThat(MotionManager.PREF_KEY)
                .isEqualTo("appearance.reduceMotion")
                .isNotEqualTo(ThemeManager.PREF_KEY)       // "appearance.tokenTheme"
                .isNotEqualTo(DensityManager.PREF_KEY)     // "appearance.density"
                .isNotEqualTo("appearance.themeId");        // SettingsModel.KEY_THEME_ID
    }
}
