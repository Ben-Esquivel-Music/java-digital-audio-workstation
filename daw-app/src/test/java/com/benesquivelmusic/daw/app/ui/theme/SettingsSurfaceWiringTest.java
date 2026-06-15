package com.benesquivelmusic.daw.app.ui.theme;

import com.benesquivelmusic.daw.app.ui.density.DensityManager;
import com.benesquivelmusic.daw.app.ui.density.DensityMode;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.sdk.event.UiEvent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 294 — proves the <strong>settings surface</strong> migration of the
 * Control Synchronization wiring (Design Book §6.7): the three appearance
 * managers are now <em>subscribers</em> to {@link UiEvent.SettingsApplied} on
 * the shared {@code EventBus}, so applying appearance settings propagates
 * <em>through the event layer</em> — not through a {@code SettingsDialog} field
 * poking the managers directly.
 *
 * <p>The headline assertion: publishing a {@code SettingsApplied} carrying a
 * <em>new</em> theme id onto a controllable {@link DefaultEventBus} that a
 * {@link ThemeManager} is subscribed to flips
 * {@link ThemeManager#getActiveTheme()} to the new theme — with no dialog and
 * no controller in the picture. Density and Motion are covered by sibling
 * assertions on the one event (they ride the same record and the same publish).</p>
 *
 * <p>Per the "JavaFX headless test pitfalls" / "@ExtendWith JPMS test failure"
 * memories this test deliberately avoids {@code @ExtendWith(JavaFxToolkitExtension)}.
 * It uses a {@link DefaultEventBus} with an inline UI executor
 * ({@code Runnable::run}) so the {@code ON_UI_THREAD} subscriptions fire on the
 * publish thread without a JavaFX toolkit — the managers' apply paths are pure
 * property writes (their scene/dialog-pane re-apply is gated on a registered
 * scene, of which there are none here, and is dropped when no toolkit is
 * running). Each manager is constructed on an isolated {@link Preferences} node
 * so the test never touches the real user store and starts from a known
 * default.</p>
 *
 * <p>Delivery is awaited by <em>polling the manager's own observable state</em>
 * (the {@code waitUntil} idiom from {@code WorkshopSelectionHostS3InvalidationTest}),
 * never a separate latch subscription: the bus gives each subscription its own
 * serial worker thread, so a second "delivered" subscription could trip its
 * latch before the manager's subscription finishes applying. Polling the actual
 * post-condition is race-free.</p>
 */
final class SettingsSurfaceWiringTest {

    @Test
    void publishingSettingsAppliedWithANewThemeIdFlipsTheSubscribedThemeManager()
            throws Exception {
        Preferences node = isolatedNode("themeWiring");
        ThemeManager themeManager = new ThemeManager(node);
        try {
            // Sanity: a freshly-constructed manager on a clean node sits at the
            // design-book default. The event below names a *different* theme so
            // a passing assertion can only be explained by event propagation.
            assertThat(themeManager.getActiveTheme())
                    .as("fresh ThemeManager starts at the default theme")
                    .isEqualTo(ThemeManager.DEFAULT_THEME);
            ThemeManager.Theme newTheme = ThemeManager.Theme.STUDIO_SLATE;
            assertThat(newTheme).isNotEqualTo(ThemeManager.DEFAULT_THEME);

            DefaultEventBus bus = controllableBus();
            try {
                themeManager.subscribeToSettings(bus);

                bus.publish(new UiEvent.SettingsApplied(
                        Instant.now(), newTheme.name(),
                        DensityMode.COMFORTABLE.name(), false));

                // The crux: the manager changed BECAUSE the event reached it via
                // the shared bus — no SettingsDialog field, no controller.
                waitUntil(() -> themeManager.getActiveTheme() == newTheme);
                assertThat(themeManager.getActiveTheme())
                        .as("ThemeManager applied the new theme from the bus event")
                        .isEqualTo(newTheme);
            } finally {
                bus.close();
            }
        } finally {
            node.removeNode();
        }
    }

    @Test
    void subscribeToSettingsIsIdempotentAndStillAppliesAfterRewiring()
            throws Exception {
        Preferences node = isolatedNode("themeRewire");
        ThemeManager themeManager = new ThemeManager(node);
        try {
            DefaultEventBus firstBus = controllableBus();
            DefaultEventBus secondBus = controllableBus();
            try {
                themeManager.subscribeToSettings(firstBus);
                // Re-wire onto a second bus: the prior subscription must be
                // closed so the manager applies exactly once, from the live bus.
                themeManager.subscribeToSettings(secondBus);

                secondBus.publish(new UiEvent.SettingsApplied(
                        Instant.now(), ThemeManager.Theme.ATELIER.name(),
                        DensityMode.COMFORTABLE.name(), false));

                waitUntil(() ->
                        themeManager.getActiveTheme() == ThemeManager.Theme.ATELIER);
                assertThat(themeManager.getActiveTheme())
                        .as("re-wired manager applies from the new bus")
                        .isEqualTo(ThemeManager.Theme.ATELIER);
            } finally {
                firstBus.close();
                secondBus.close();
            }
        } finally {
            node.removeNode();
        }
    }

    @Test
    void densityAndMotionManagersApplyTheirSliceOfTheSameEvent() throws Exception {
        Preferences densityNode = isolatedNode("densityWiring");
        Preferences motionNode = isolatedNode("motionWiring");
        DensityManager densityManager = new DensityManager(densityNode);
        // The single-arg MotionManager ctor probes the OS hint; the isolated
        // node has no persisted key, so seed an explicit "false" first so the
        // start state is deterministic regardless of the CI agent's OS setting.
        motionNode.putBoolean(MotionManager.PREF_KEY, false);
        MotionManager motionManager = new MotionManager(motionNode);
        try {
            assertThat(densityManager.getActiveDensity())
                    .isEqualTo(DensityManager.DEFAULT_DENSITY);
            assertThat(motionManager.isReduceMotion()).isFalse();

            DensityMode newDensity = DensityMode.TOUCH;
            assertThat(newDensity).isNotEqualTo(DensityManager.DEFAULT_DENSITY);

            DefaultEventBus bus = controllableBus();
            try {
                densityManager.subscribeToSettings(bus);
                motionManager.subscribeToSettings(bus);

                bus.publish(new UiEvent.SettingsApplied(
                        Instant.now(), ThemeManager.DEFAULT_THEME.name(),
                        newDensity.name(), true));

                waitUntil(() -> densityManager.getActiveDensity() == newDensity
                        && motionManager.isReduceMotion());
                assertThat(densityManager.getActiveDensity())
                        .as("DensityManager applied the new density from the bus event")
                        .isEqualTo(newDensity);
                assertThat(motionManager.isReduceMotion())
                        .as("MotionManager applied the Reduce Motion flag from the bus event")
                        .isTrue();
            } finally {
                bus.close();
            }
        } finally {
            densityNode.removeNode();
            motionNode.removeNode();
        }
    }

    @Test
    void anUnknownThemeIdIsIgnoredRatherThanThrowingOnTheBusThread()
            throws Exception {
        Preferences node = isolatedNode("themeUnknown");
        ThemeManager themeManager = new ThemeManager(node);
        try {
            DefaultEventBus bus = controllableBus();
            try {
                themeManager.subscribeToSettings(bus);

                // Round-trip a VALID event first as a delivery barrier we can
                // observe, then assert the unknown one left state untouched.
                // (Publishing the unknown one alone has no observable effect to
                // wait on, so we sandwich it: STUDIO_SLATE applies, then the bad
                // event is ignored, then ATELIER applies — proving the bad event
                // neither changed the theme nor killed the subscription.)
                bus.publish(new UiEvent.SettingsApplied(
                        Instant.now(), ThemeManager.Theme.STUDIO_SLATE.name(),
                        DensityMode.COMFORTABLE.name(), false));
                waitUntil(() ->
                        themeManager.getActiveTheme() == ThemeManager.Theme.STUDIO_SLATE);

                bus.publish(new UiEvent.SettingsApplied(
                        Instant.now(), "NOT_A_REAL_THEME",
                        DensityMode.COMFORTABLE.name(), false));

                bus.publish(new UiEvent.SettingsApplied(
                        Instant.now(), ThemeManager.Theme.ATELIER.name(),
                        DensityMode.COMFORTABLE.name(), false));
                waitUntil(() ->
                        themeManager.getActiveTheme() == ThemeManager.Theme.ATELIER);

                // ATELIER won (the subscription survived the bad event); had the
                // bad event been applied as a theme, valueOf would have thrown
                // and STUDIO_SLATE would have lingered — it did not.
                assertThat(themeManager.getActiveTheme())
                        .as("unknown themeId is ignored; later valid events still apply")
                        .isEqualTo(ThemeManager.Theme.ATELIER);
            } finally {
                bus.close();
            }
        } finally {
            node.removeNode();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * A bus whose {@code ON_UI_THREAD} subscribers run inline on the publish
     * thread — no JavaFX toolkit required (the managers' apply paths are pure
     * property writes here). Mirrors
     * {@code WorkshopSelectionHostS3InvalidationTest}.
     */
    private static DefaultEventBus controllableBus() {
        return DefaultEventBus.builder().uiExecutor(Runnable::run).build();
    }

    /**
     * Polls {@code condition} (the manager's own observable post-state) up to
     * five seconds. Returns as soon as it holds; if it never does, the caller's
     * subsequent assertion fails with a clearer message than a bare timeout
     * (mirrors {@code WorkshopSelectionHostS3InvalidationTest#waitUntil}).
     */
    private static void waitUntil(BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
    }

    /** A throwaway, isolated preferences node so the real user store is untouched. */
    private static Preferences isolatedNode(String label) {
        return Preferences.userRoot()
                .node("daw_test_settingsWiring_" + label + "_" + System.nanoTime());
    }
}
