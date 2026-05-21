package com.benesquivelmusic.daw.app.ui.motion;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 279 — on first launch (no persisted preference) {@link MotionManager}
 * seeds its initial Reduce Motion flag from the OS-level accessibility
 * hint via the injectable {@link OsMotionHint} seam.
 *
 * <p>Pure unit test — the {@code MotionManager(Preferences, OsMotionHint)}
 * constructor lets the test inject a deterministic mock hint instead of
 * probing the real platform, so no JavaFX and no native call is needed.</p>
 */
final class OsHintDetectionTest {

    @Test
    void hintTrueSeedsReduceMotionOn() throws BackingStoreException {
        Preferences node = Preferences.userRoot()
                .node("osHintTestTrue_" + System.nanoTime());
        try {
            MotionManager mgr = new MotionManager(node, () -> Optional.of(true));
            assertThat(mgr.isReduceMotion())
                    .as("an OS hint of true on a fresh node must seed Reduce Motion on")
                    .isTrue();
        } finally {
            node.removeNode();
        }
    }

    @Test
    void hintFalseSeedsReduceMotionOff() throws BackingStoreException {
        Preferences node = Preferences.userRoot()
                .node("osHintTestFalse_" + System.nanoTime());
        try {
            MotionManager mgr = new MotionManager(node, () -> Optional.of(false));
            assertThat(mgr.isReduceMotion())
                    .as("an OS hint of false on a fresh node must seed Reduce Motion off")
                    .isFalse();
        } finally {
            node.removeNode();
        }
    }

    @Test
    void undetectedHintFallsBackToTheDefault() throws BackingStoreException {
        Preferences node = Preferences.userRoot()
                .node("osHintTestEmpty_" + System.nanoTime());
        try {
            // Optional.empty() = OS preference could not be determined
            // (e.g. macOS, or a detection failure) → fall back to default.
            MotionManager mgr = new MotionManager(node, Optional::empty);
            assertThat(mgr.isReduceMotion())
                    .as("an undetected OS hint must fall back to the design default")
                    .isEqualTo(MotionManager.DEFAULT_REDUCE_MOTION)
                    .isFalse();
        } finally {
            node.removeNode();
        }
    }

    @Test
    void persistedValuePresentOverridesTheOsHint() throws BackingStoreException {
        Preferences node = Preferences.userRoot()
                .node("osHintTestOverride_" + System.nanoTime());
        try {
            // A value is already persisted (the user has saved a choice):
            // the stored boolean must win over the OS hint, even when the
            // hint disagrees.
            node.putBoolean(MotionManager.PREF_KEY, false);
            MotionManager mgr = new MotionManager(node, () -> Optional.of(true));
            assertThat(mgr.isReduceMotion())
                    .as("a present persisted value must override the OS hint")
                    .isFalse();
        } finally {
            node.removeNode();
        }
    }
}
