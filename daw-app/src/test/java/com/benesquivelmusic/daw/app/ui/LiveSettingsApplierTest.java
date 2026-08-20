package com.benesquivelmusic.daw.app.ui;

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
 * <p>Headless and off-toolkit: the {@code Scene} parameter is
 * {@code null} (the applier's scale branch is scene-guarded), the
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

    @BeforeEach
    void setUp() {
        Preferences prefs = Preferences.userRoot()
                .node("liveSettingsApplierTest_" + System.nanoTime());
        model = new SettingsModel(prefs);
        model.setAutoSaveIntervalSeconds(DEFAULT_CONFIG_INTERVAL_SECONDS);
        project = new DawProject("Test", AudioFormat.CD_QUALITY);
        projectManager = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT));
        pluginRegistry = new PluginRegistry();
    }

    private void apply() {
        LiveSettingsApplier.apply(model, model.getPluginScanPaths(), null,
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

    @Test
    void applyShouldPushTheDefaultTempoToTheLiveTransport() {
        model.setDefaultTempo(140.0);

        apply();

        assertThat(project.getTransport().getTempo())
                .as("the pre-existing tempo push still rides the same Apply")
                .isEqualTo(140.0);
    }
}
