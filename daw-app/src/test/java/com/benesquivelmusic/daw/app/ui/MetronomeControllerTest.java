package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.recording.ClickSound;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.core.recording.Subdivision;

import javafx.application.Platform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link MetronomeController} that exercises preference loading,
 * toggle logic, and null-argument validation.
 *
 * <p>Tests that create JavaFX controls ({@code Button}, {@code Label},
 * {@code NotificationBar}) require the JavaFX toolkit, which is
 * initialized by the {@link JavaFxToolkitExtension}.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MetronomeControllerTest {

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
        assertThat(catchException(() -> new MetronomeController(
                null, new javafx.scene.control.Button(),
                new NotificationBar(), new javafx.scene.control.Label(), prefs)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("metronome");
    }

    @Test
    void shouldRejectNullButton() {
        assertThat(catchException(() -> new MetronomeController(
                metronome, null,
                new NotificationBar(), new javafx.scene.control.Label(), prefs)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("metronomeButton");
    }

    @Test
    void shouldRejectNullNotificationBar() {
        assertThat(catchException(() -> new MetronomeController(
                metronome, new javafx.scene.control.Button(),
                null, new javafx.scene.control.Label(), prefs)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("notificationBar");
    }

    @Test
    void shouldRejectNullStatusBarLabel() {
        assertThat(catchException(() -> new MetronomeController(
                metronome, new javafx.scene.control.Button(),
                new NotificationBar(), null, prefs)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("statusBarLabel");
    }

    @Test
    void shouldRejectNullPreferences() {
        assertThat(catchException(() -> new MetronomeController(
                metronome, new javafx.scene.control.Button(),
                new NotificationBar(), new javafx.scene.control.Label(), null)))
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
        MetronomeController controller = createController();
        assertThat(controller.getCountInMode()).isEqualTo(CountInMode.OFF);
    }

    // ── Preferences loading ─────────────────────────────────────────────────

    @Test
    void shouldLoadPersistedEnabledState() {
        prefs.putBoolean("metronome.enabled", false);
        MetronomeController controller = createController();
        assertThat(controller.getMetronome().isEnabled()).isFalse();
    }

    @Test
    void shouldLoadPersistedVolume() {
        prefs.putFloat("metronome.volume", 0.5f);
        MetronomeController controller = createController();
        assertThat(controller.getMetronome().getVolume()).isEqualTo(0.5f);
    }

    @Test
    void shouldLoadPersistedClickSound() {
        prefs.put("metronome.clickSound", ClickSound.COWBELL.name());
        MetronomeController controller = createController();
        assertThat(controller.getMetronome().getClickSound()).isEqualTo(ClickSound.COWBELL);
    }

    @Test
    void shouldLoadPersistedSubdivision() {
        prefs.put("metronome.subdivision", Subdivision.EIGHTH.name());
        MetronomeController controller = createController();
        assertThat(controller.getMetronome().getSubdivision()).isEqualTo(Subdivision.EIGHTH);
    }

    @Test
    void shouldLoadPersistedCountInMode() {
        prefs.put("metronome.countIn", CountInMode.TWO_BARS.name());
        MetronomeController controller = createController();
        assertThat(controller.getCountInMode()).isEqualTo(CountInMode.TWO_BARS);
    }

    // ── Invalid preferences fallback ────────────────────────────────────────

    @Test
    void shouldFallBackToDefaultsForInvalidClickSound() {
        prefs.put("metronome.clickSound", "INVALID");
        MetronomeController controller = createController();
        assertThat(controller.getMetronome().getClickSound()).isEqualTo(ClickSound.WOODBLOCK);
    }

    @Test
    void shouldFallBackToDefaultsForInvalidSubdivision() {
        prefs.put("metronome.subdivision", "INVALID");
        MetronomeController controller = createController();
        assertThat(controller.getMetronome().getSubdivision()).isEqualTo(Subdivision.QUARTER);
    }

    @Test
    void shouldFallBackToDefaultsForInvalidCountIn() {
        prefs.put("metronome.countIn", "INVALID");
        MetronomeController controller = createController();
        assertThat(controller.getCountInMode()).isEqualTo(CountInMode.OFF);
    }

    @Test
    void shouldClampOutOfRangeVolume() {
        prefs.putFloat("metronome.volume", 2.0f);
        MetronomeController controller = createController();
        assertThat(controller.getMetronome().getVolume()).isEqualTo(1.0f);
    }

    @Test
    void shouldClampNegativeVolume() {
        prefs.putFloat("metronome.volume", -0.5f);
        MetronomeController controller = createController();
        assertThat(controller.getMetronome().getVolume()).isEqualTo(0.0f);
    }

    // ── Accessor ────────────────────────────────────────────────────────────

    @Test
    void shouldReturnMetronomeInstance() {
        MetronomeController controller = createController();
        assertThat(controller.getMetronome()).isSameAs(metronome);
    }

    // ── Toggle behavior ─────────────────────────────────────────────────────

    @Test
    void toggleShouldDisableEnabledMetronome() {
        MetronomeController controller = createController();
        assertThat(metronome.isEnabled()).isTrue();

        controller.onToggleMetronome();

        assertThat(metronome.isEnabled()).isFalse();
    }

    @Test
    void toggleShouldEnableDisabledMetronome() {
        prefs.putBoolean("metronome.enabled", false);
        MetronomeController controller = createController();
        assertThat(metronome.isEnabled()).isFalse();

        controller.onToggleMetronome();

        assertThat(metronome.isEnabled()).isTrue();
    }

    @Test
    void toggleShouldPersistEnabledState() {
        MetronomeController controller = createController();
        assertThat(metronome.isEnabled()).isTrue();

        controller.onToggleMetronome();

        assertThat(prefs.getBoolean("metronome.enabled", true)).isFalse();
    }

    @Test
    void toggleShouldUpdateButtonStyle() {
        javafx.scene.control.Button button = new javafx.scene.control.Button();
        MetronomeController controller = new MetronomeController(
                metronome, button, new NotificationBar(),
                new javafx.scene.control.Label(), prefs);

        // Initially enabled — button should have active style
        assertThat(button.getStyle()).contains("#b388ff");

        controller.onToggleMetronome();

        // Now disabled — style should be cleared
        assertThat(button.getStyle()).isEmpty();
    }

    @Test
    void toggleShouldUpdateStatusBarLabel() {
        javafx.scene.control.Label statusBar = new javafx.scene.control.Label();
        MetronomeController controller = new MetronomeController(
                metronome, new javafx.scene.control.Button(), new NotificationBar(),
                statusBar, prefs);

        controller.onToggleMetronome();

        assertThat(statusBar.getText()).isEqualTo("Metronome: OFF");
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

    private static Throwable catchException(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
