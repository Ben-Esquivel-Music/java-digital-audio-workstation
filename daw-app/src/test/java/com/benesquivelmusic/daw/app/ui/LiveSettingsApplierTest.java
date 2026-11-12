package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.app.ui.theme.UiScale;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.core.project.DawProject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 315 — {@link LiveSettingsApplier} pushes the story-305
 * {@code transport.returnToStartOnStop} preference to the LIVE
 * transport on Apply (its {@code LIVE} apply class: the transport reads
 * the volatile flag on each Stop, so the push is all an Apply needs —
 * no engine re-arm).
 *
 * <p>Headless and off-toolkit: the theme manager is an isolated one whose
 * scale already matches the model's 100% (setting the same step fires
 * nothing, so no scene is restyled), the
 * autosave interval matches {@link AutoSaveConfig#DEFAULT} so no
 * reconfigure fires, and the plugin paths stay blank so no scan runs —
 * the test isolates exactly the transport pushes.</p>
 */
class LiveSettingsApplierTest {

    /** Seconds in {@link AutoSaveConfig#DEFAULT}'s five-minute interval. */
    private static final int DEFAULT_CONFIG_INTERVAL_SECONDS = 300;

    private SettingsModel model;
    private DawProject project;
    private ProjectManager projectManager;
    private PluginRegistry pluginRegistry;
    private ThemeManager appearance;

    @BeforeEach
    void setUp() {
        Preferences prefs = Preferences.userRoot()
                .node("liveSettingsApplierTest_" + System.nanoTime());
        model = new SettingsModel(prefs);
        appearance = new ThemeManager(prefs.node("theme"));
        model.setAutoSaveIntervalSeconds(DEFAULT_CONFIG_INTERVAL_SECONDS);
        project = new DawProject("Test", AudioFormat.CD_QUALITY);
        projectManager = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT));
        pluginRegistry = new PluginRegistry();
    }

    private void apply() {
        LiveSettingsApplier.apply(model, model.getPluginScanPaths(), appearance,
                projectManager, project, pluginRegistry);
    }

    @Test
    void applyShouldPushReturnToStartOnStopToTheLiveTransport() {
        model.setReturnToStartOnStop(false);

        apply();

        assertThat(project.getTransport().isReturnToStartOnStop())
                .as("a settings Apply reaches the live transport immediately")
                .isFalse();
    }

    @Test
    void applyShouldReenableReturnToStartOnStop() {
        project.getTransport().setReturnToStartOnStop(false);
        model.setReturnToStartOnStop(true);

        apply();

        assertThat(project.getTransport().isReturnToStartOnStop())
                .as("re-enabling the preference reaches the live transport too")
                .isTrue();
    }

    /**
     * Story 335 (studio ruling R4) — the applier never writes the open
     * project's tempo: an edited default tempo reaches it only through
     * {@code MainController}'s default-tempo decision. (The old test pinned
     * the unconditional push this story removes.)
     */
    @Test
    void applyNeverWritesTheOpenProjectsTempo() {
        project.getTransport().setTempo(133.0);
        model.setDefaultTempo(140.0);

        apply();

        assertThat(project.getTransport().getTempo())
                .as("a settings Apply leaves the open project's tempo alone")
                .isEqualTo(133.0);
    }

    /**
     * Story 335 (studio ruling R6) — the applied UI scale reaches the theme
     * manager as an exact step (its scale sheet, not a transform), and an
     * Apply whose scale is unchanged leaves the manager's step alone.
     */
    @Test
    void applyPushesTheUiScaleStepToTheThemeManager() {
        model.setUiScale(1.75);

        apply();

        assertThat(appearance.getUiScale()).isEqualTo(UiScale.P175);

        apply();

        assertThat(appearance.getUiScale()).as("an unrelated Apply keeps the step").isEqualTo(UiScale.P175);
    }
}
