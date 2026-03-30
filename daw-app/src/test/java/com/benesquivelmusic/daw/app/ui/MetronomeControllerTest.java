package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.recording.ClickSound;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.core.recording.Subdivision;

import javafx.application.Platform;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the {@link MetronomeController} that exercises preference loading,
 * toggle logic, and null-argument validation.
 *
 * <p>Tests that create JavaFX controls ({@code Button}, {@code Label},
 * {@code NotificationBar}) require the JavaFX toolkit; these are gated
 * behind {@code Assumptions.assumeTrue(toolkitAvailable)} so they are
 * skipped on headless CI runners.</p>
 */
class MetronomeControllerTest {

    private static boolean toolkitAvailable;

    @BeforeAll
    static void initToolkit() throws Exception {
        toolkitAvailable = false;
        CountDownLatch startupLatch = new CountDownLatch(1);
        try {
            Platform.startup(startupLatch::countDown);
            if (!startupLatch.await(5, TimeUnit.SECONDS)) {
                return;
            }
        } catch (IllegalStateException ignored) {
            // Toolkit already initialized
        } catch (UnsupportedOperationException ignored) {
            // Headless CI
            return;
        }
        CountDownLatch verifyLatch = new CountDownLatch(1);
        Thread verifier = new Thread(() -> {
            try {
                Platform.runLater(verifyLatch::countDown);
            } catch (Exception ignored) { }
        });
        verifier.setDaemon(true);
        verifier.start();
        verifier.join(3000);
        toolkitAvailable = verifyLatch.await(3, TimeUnit.SECONDS);
    }

    private Preferences prefs;
    private Metronome metronome;

    @BeforeEach
    void setUp() {
        prefs = Preferences.userRoot().node("metronomeControllerTest_" + System.nanoTime());
        metronome = new Metronome(44100.0, 2);
    }

    // ── Class structure ─────────────────────────────────────────────────────

    @Test
    void shouldLoadClassAndVerifyPackageVisibility() {
        Class<?> clazz = MetronomeController.class;
        assertThat(clazz).isNotNull();
        assertThat(java.lang.reflect.Modifier.isFinal(clazz.getModifiers())).isTrue();
    }

    // ── Constructor validation ──────────────────────────────────────────────

    @Test
    void shouldRejectNullMetronome() {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available");
        assertThatThrownBy(() -> new MetronomeController(
                null, new javafx.scene.control.Button(),
                new NotificationBar(), new javafx.scene.control.Label(), prefs))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("metronome");
    }

    @Test
    void shouldRejectNullButton() {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available");
        assertThatThrownBy(() -> new MetronomeController(
                metronome, null,
                new NotificationBar(), new javafx.scene.control.Label(), prefs))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("metronomeButton");
    }

    @Test
    void shouldRejectNullNotificationBar() {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available");
        assertThatThrownBy(() -> new MetronomeController(
                metronome, new javafx.scene.control.Button(),
                null, new javafx.scene.control.Label(), prefs))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("notificationBar");
    }

    @Test
    void shouldRejectNullStatusBarLabel() {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available");
        assertThatThrownBy(() -> new MetronomeController(
                metronome, new javafx.scene.control.Button(),
                new NotificationBar(), null, prefs))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("statusBarLabel");
    }

    @Test
    void shouldRejectNullPreferences() {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available");
        assertThatThrownBy(() -> new MetronomeController(
                metronome, new javafx.scene.control.Button(),
                new NotificationBar(), new javafx.scene.control.Label(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("prefs");
    }

    // ── Default state ───────────────────────────────────────────────────────

    @Test
    void shouldDefaultToEnabledMetronome() {
        assertThat(metronome.isEnabled()).isTrue();
    }

    @Test
    void shouldDefaultCountInModeToOff() {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available");
        MetronomeController controller = createController();
        assertThat(controller.getCountInMode()).isEqualTo(CountInMode.OFF);
    }

    // ── Preferences loading ─────────────────────────────────────────────────

    @Test
    void shouldLoadPersistedEnabledState() {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available");
        prefs.putBoolean("enabled", false);
        MetronomeController controller = createController();
        assertThat(controller.getMetronome().isEnabled()).isFalse();
    }

    @Test
    void shouldLoadPersistedVolume() {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available");
        prefs.putFloat("volume", 0.5f);
        MetronomeController controller = createController();
        assertThat(controller.getMetronome().getVolume()).isEqualTo(0.5f);
    }

    @Test
    void shouldLoadPersistedClickSound() {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available");
        prefs.put("clickSound", ClickSound.COWBELL.name());
        MetronomeController controller = createController();
        assertThat(controller.getMetronome().getClickSound()).isEqualTo(ClickSound.COWBELL);
    }

    @Test
    void shouldLoadPersistedSubdivision() {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available");
        prefs.put("subdivision", Subdivision.EIGHTH.name());
        MetronomeController controller = createController();
        assertThat(controller.getMetronome().getSubdivision()).isEqualTo(Subdivision.EIGHTH);
    }

    @Test
    void shouldLoadPersistedCountInMode() {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available");
        prefs.put("countIn", CountInMode.TWO_BARS.name());
        MetronomeController controller = createController();
        assertThat(controller.getCountInMode()).isEqualTo(CountInMode.TWO_BARS);
    }

    // ── Invalid preferences fallback ────────────────────────────────────────

    @Test
    void shouldFallBackToDefaultsForInvalidClickSound() {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available");
        prefs.put("clickSound", "INVALID");
        MetronomeController controller = createController();
        assertThat(controller.getMetronome().getClickSound()).isEqualTo(ClickSound.WOODBLOCK);
    }

    @Test
    void shouldFallBackToDefaultsForInvalidSubdivision() {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available");
        prefs.put("subdivision", "INVALID");
        MetronomeController controller = createController();
        assertThat(controller.getMetronome().getSubdivision()).isEqualTo(Subdivision.QUARTER);
    }

    @Test
    void shouldFallBackToDefaultsForInvalidCountIn() {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available");
        prefs.put("countIn", "INVALID");
        MetronomeController controller = createController();
        assertThat(controller.getCountInMode()).isEqualTo(CountInMode.OFF);
    }

    @Test
    void shouldClampOutOfRangeVolume() {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available");
        prefs.putFloat("volume", 2.0f);
        MetronomeController controller = createController();
        assertThat(controller.getMetronome().getVolume()).isEqualTo(1.0f);
    }

    @Test
    void shouldClampNegativeVolume() {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available");
        prefs.putFloat("volume", -0.5f);
        MetronomeController controller = createController();
        assertThat(controller.getMetronome().getVolume()).isEqualTo(0.0f);
    }

    // ── Accessor ────────────────────────────────────────────────────────────

    @Test
    void shouldReturnMetronomeInstance() {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available");
        MetronomeController controller = createController();
        assertThat(controller.getMetronome()).isSameAs(metronome);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private MetronomeController createController() {
        return new MetronomeController(
                metronome,
                new javafx.scene.control.Button(),
                new NotificationBar(),
                new javafx.scene.control.Label(),
                prefs);
    }
}
