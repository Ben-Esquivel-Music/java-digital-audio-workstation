package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.DarkThemeHelper;
import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.density.DensityManager;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 271 / 278 — the strip width is density-driven and enforced from
 * the Skin (Java), not CSS: 72&nbsp;px Compact, 88&nbsp;px Comfortable
 * (UI Design Book §5.4).
 *
 * <p>Story 278 makes <strong>Comfortable</strong> the single global
 * default density (UI Design Book §3.7) and routes the skin's width
 * through the shared {@link com.benesquivelmusic.daw.app.ui.density.DensityMode#resolveFor}
 * bridge. Its fallback chain for a strip with no density class on either
 * the control or the scene root ends at the live default
 * ({@code DensityManager.getDefault()} → Comfortable), so a class-less
 * strip is now 88&nbsp;px — not the legacy 72&nbsp;px. The explicit
 * control-own-class cases ({@code density-compact} / {@code density-comfortable})
 * are still honoured via the resolver's back-compat fallback and remain
 * 72 / 88&nbsp;px respectively.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MixerChannelStripLayoutTest {

    private static double widthOf(String densityClass) {
        return runOnFxThread(() -> {
            MixerChannelStrip strip = new MixerChannelStrip();
            strip.setChannelName("Drums");
            if (densityClass != null) {
                strip.getStyleClass().add(densityClass);
            }
            StackPane root = new StackPane(strip);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 200, 600);
            DarkThemeHelper.applyTo(scene);
            root.applyCss();
            root.layout();
            return strip.prefWidth(-1);
        });
    }

    @Test
    void compactDensityIs72Px() {
        assertThat(widthOf("density-compact")).isCloseTo(72.0, within(1.0));
    }

    @Test
    void comfortableDensityIs88Px() {
        assertThat(widthOf("density-comfortable")).isCloseTo(88.0, within(1.0));
    }

    @Test
    void defaultDensityIsComfortableWidth() {
        // Story 278 (UI Design Book §3.7): Comfortable is the single
        // global default density. A strip with no density class on the
        // control and none on the scene root resolves through
        // DensityMode.resolveFor's fallback chain to the live default
        // (DensityManager.getDefault() → Comfortable) → 88 px. This
        // supersedes the pre-278 "no class == Compact 72" behaviour: §3.7
        // makes density a single global setting, so there is no longer a
        // mixer-local "default is Compact".
        //
        // The class-less case is the ONLY one that reaches
        // DensityManager.getDefault() — so, unlike the two explicit-class
        // cases above, it must be isolated from the developer's / CI
        // agent's real `appearance.density` preference. A fresh userRoot
        // node has no persisted value, so the isolated manager restores
        // DEFAULT_DENSITY (Comfortable); pinning it via setDefaultForTest
        // makes this assert the *default contract*, not whatever density
        // happens to be persisted on the machine running the test.
        Preferences node = Preferences.userRoot()
                .node("mixerChannelStripLayoutDefault_" + System.nanoTime());
        DensityManager mgr = new DensityManager(node);
        DensityManager.setDefaultForTest(mgr);
        try {
            assertThat(widthOf(null)).isCloseTo(88.0, within(1.0));
        } finally {
            DensityManager.setDefaultForTest(null);
            removeQuietly(node);
        }
    }

    private static void removeQuietly(Preferences node) {
        try {
            node.removeNode();
        } catch (BackingStoreException ignored) {
            // Best-effort cleanup; must not mask test results.
        }
    }
}
