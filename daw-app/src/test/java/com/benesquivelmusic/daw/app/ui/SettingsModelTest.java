package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.*;

class SettingsModelTest {

    private Preferences prefs;
    private SettingsModel model;

    @BeforeEach
    void setUp() {
        prefs = Preferences.userRoot().node("settingsModelTest_" + System.nanoTime());
        model = new SettingsModel(prefs);
    }

    // ── Constructor ──────────────────────────────────────────────────────────

    @Test
    void shouldRejectNullPreferences() {
        assertThatThrownBy(() -> new SettingsModel(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Audio defaults ───────────────────────────────────────────────────────

    @Test
    void shouldDefaultToStudioSampleRate() {
        assertThat(model.getSampleRate()).isEqualTo(96_000.0);
    }

    @Test
    void shouldDefaultTo24BitDepth() {
        assertThat(model.getBitDepth()).isEqualTo(24);
    }

    @Test
    void shouldDefaultTo256BufferSize() {
        assertThat(model.getBufferSize()).isEqualTo(256);
    }

    // ── Project defaults ─────────────────────────────────────────────────────

    @Test
    void shouldDefaultTo120SecondAutoSave() {
        assertThat(model.getAutoSaveIntervalSeconds()).isEqualTo(120);
    }

    @Test
    void shouldDefaultTo120Bpm() {
        assertThat(model.getDefaultTempo()).isCloseTo(120.0, within(0.01));
    }

    // ── Appearance defaults ──────────────────────────────────────────────────

    @Test
    void shouldDefaultToUiScale1() {
        assertThat(model.getUiScale()).isCloseTo(1.0, within(0.01));
    }

    // ── Plugin defaults ──────────────────────────────────────────────────────

    @Test
    void shouldDefaultToEmptyPluginScanPaths() {
        assertThat(model.getPluginScanPaths()).isEmpty();
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    @Test
    void shouldPersistSampleRate() {
        model.setSampleRate(48_000.0);

        SettingsModel reloaded = new SettingsModel(prefs);
        assertThat(reloaded.getSampleRate()).isEqualTo(48_000.0);
    }

    @Test
    void shouldPersistBitDepth() {
        model.setBitDepth(16);

        SettingsModel reloaded = new SettingsModel(prefs);
        assertThat(reloaded.getBitDepth()).isEqualTo(16);
    }

    @Test
    void shouldPersistBufferSize() {
        model.setBufferSize(512);

        SettingsModel reloaded = new SettingsModel(prefs);
        assertThat(reloaded.getBufferSize()).isEqualTo(512);
    }

    @Test
    void shouldPersistAutoSaveInterval() {
        model.setAutoSaveIntervalSeconds(60);

        SettingsModel reloaded = new SettingsModel(prefs);
        assertThat(reloaded.getAutoSaveIntervalSeconds()).isEqualTo(60);
    }

    @Test
    void shouldPersistDefaultTempo() {
        model.setDefaultTempo(140.0);

        SettingsModel reloaded = new SettingsModel(prefs);
        assertThat(reloaded.getDefaultTempo()).isCloseTo(140.0, within(0.01));
    }

    @Test
    void shouldPersistUiScale() {
        model.setUiScale(1.5);

        SettingsModel reloaded = new SettingsModel(prefs);
        assertThat(reloaded.getUiScale()).isCloseTo(1.5, within(0.01));
    }

    @Test
    void shouldPersistPluginScanPaths() {
        model.setPluginScanPaths("/usr/lib/plugins;/home/user/vst");

        SettingsModel reloaded = new SettingsModel(prefs);
        assertThat(reloaded.getPluginScanPaths()).isEqualTo("/usr/lib/plugins;/home/user/vst");
    }

    // ── Validation ───────────────────────────────────────────────────────────

    @Test
    void shouldRejectZeroSampleRate() {
        assertThatThrownBy(() -> model.setSampleRate(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeSampleRate() {
        assertThatThrownBy(() -> model.setSampleRate(-44100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectZeroBitDepth() {
        assertThatThrownBy(() -> model.setBitDepth(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeBitDepth() {
        assertThatThrownBy(() -> model.setBitDepth(-16))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectZeroBufferSize() {
        assertThatThrownBy(() -> model.setBufferSize(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeBufferSize() {
        assertThatThrownBy(() -> model.setBufferSize(-256))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectZeroAutoSaveInterval() {
        assertThatThrownBy(() -> model.setAutoSaveIntervalSeconds(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeAutoSaveInterval() {
        assertThatThrownBy(() -> model.setAutoSaveIntervalSeconds(-10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectTempoBelowMinimum() {
        assertThatThrownBy(() -> model.setDefaultTempo(19.9))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectTempoAboveMaximum() {
        assertThatThrownBy(() -> model.setDefaultTempo(1000.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectUiScaleBelowMinimum() {
        assertThatThrownBy(() -> model.setUiScale(0.4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectUiScaleAboveMaximum() {
        assertThatThrownBy(() -> model.setUiScale(3.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullPluginScanPaths() {
        assertThatThrownBy(() -> model.setPluginScanPaths(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Reset ────────────────────────────────────────────────────────────────

    @Test
    void shouldResetToDefaults() {
        model.setSampleRate(44_100.0);
        model.setBitDepth(16);
        model.setBufferSize(1024);
        model.setAutoSaveIntervalSeconds(30);
        model.setDefaultTempo(200.0);
        model.setUiScale(2.0);
        model.setPluginScanPaths("/some/path");

        model.resetToDefaults();

        assertThat(model.getSampleRate()).isEqualTo(96_000.0);
        assertThat(model.getBitDepth()).isEqualTo(24);
        assertThat(model.getBufferSize()).isEqualTo(256);
        assertThat(model.getAutoSaveIntervalSeconds()).isEqualTo(120);
        assertThat(model.getDefaultTempo()).isCloseTo(120.0, within(0.01));
        assertThat(model.getUiScale()).isCloseTo(1.0, within(0.01));
        assertThat(model.getPluginScanPaths()).isEmpty();
    }

    @Test
    void resetToDefaultsShouldPersist() {
        model.setSampleRate(44_100.0);
        model.setBitDepth(16);
        model.setBufferSize(1024);

        model.resetToDefaults();

        SettingsModel reloaded = new SettingsModel(prefs);
        assertThat(reloaded.getSampleRate()).isEqualTo(96_000.0);
        assertThat(reloaded.getBitDepth()).isEqualTo(24);
        assertThat(reloaded.getBufferSize()).isEqualTo(256);
    }

    // ── Boundary values ──────────────────────────────────────────────────────

    @Test
    void shouldAcceptMinimumTempo() {
        model.setDefaultTempo(20.0);
        assertThat(model.getDefaultTempo()).isCloseTo(20.0, within(0.01));
    }

    @Test
    void shouldAcceptMaximumTempo() {
        model.setDefaultTempo(999.0);
        assertThat(model.getDefaultTempo()).isCloseTo(999.0, within(0.01));
    }

    @Test
    void shouldAcceptMinimumUiScale() {
        model.setUiScale(0.5);
        assertThat(model.getUiScale()).isCloseTo(0.5, within(0.01));
    }

    @Test
    void shouldAcceptMaximumUiScale() {
        model.setUiScale(3.0);
        assertThat(model.getUiScale()).isCloseTo(3.0, within(0.01));
    }

    // ── Key Bindings ─────────────────────────────────────────────────────────

    @Test
    void shouldReturnNonNullKeyBindingManager() {
        assertThat(model.getKeyBindingManager()).isNotNull();
    }

    @Test
    void shouldReturnSameKeyBindingManagerInstance() {
        KeyBindingManager first = model.getKeyBindingManager();
        KeyBindingManager second = model.getKeyBindingManager();
        assertThat(first).isSameAs(second);
    }

    @Test
    void keyBindingManagerShouldHaveDefaultBindings() {
        KeyBindingManager manager = model.getKeyBindingManager();
        assertThat(manager.getBinding(DawAction.PLAY_STOP)).isPresent();
        assertThat(manager.getBinding(DawAction.SAVE)).isPresent();
        assertThat(manager.getBinding(DawAction.UNDO)).isPresent();
    }
}
