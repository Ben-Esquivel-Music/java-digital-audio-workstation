package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.app.ui.theme.UiScale;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.core.project.DawProject;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Applies live settings changes to the running DAW — UI scale, auto-save
 * interval, transport stop behaviour, and plugin scan paths.
 *
 * <p>The UI scale goes through {@link ThemeManager#setUiScale(UiScale)}
 * (story 335, studio ruling R6): a root font size plus rem-resolved lengths
 * in every styled window, never a scale transform. Setting the same step
 * fires nothing, so an Apply of unrelated rows does not restyle anything.</p>
 *
 * <p>It never writes the open project's tempo (story 335, studio ruling
 * R4): an Apply that carried an edited default tempo hands it to
 * {@code MainController}'s default-tempo decision instead, so an Apply of
 * unrelated rows never reaches the transport's tempo.</p>
 *
 * <p>Extracted from {@code MainController} to keep the main coordinator
 * free of settings propagation logic.</p>
 */
final class LiveSettingsApplier {

    private LiveSettingsApplier() {}

    /**
     * Applies {@code model} to the running DAW.
     */
    static void apply(SettingsModel model, String previousPluginPaths,
                      ThemeManager appearance, ProjectManager projectManager,
                      DawProject project, PluginRegistry pluginRegistry) {
        Objects.requireNonNull(appearance, "appearance must not be null");
        appearance.setUiScale(UiScale.ofFactor(model.getUiScale()));
        CheckpointManager checkpointManager = projectManager.getCheckpointManager();
        AutoSaveConfig currentConfig = checkpointManager.getConfig();
        Duration newInterval = Duration.ofSeconds(model.getAutoSaveIntervalSeconds());
        if (!currentConfig.autoSaveInterval().equals(newInterval)) {
            checkpointManager.reconfigure(new AutoSaveConfig(newInterval,
                    currentConfig.maxCheckpoints(), currentConfig.enabled()));
        }
        // Story 315 — LIVE apply class: the transport reads the volatile
        // flag on each Stop, so pushing it here is all a settings Apply
        // needs (no engine re-arm).
        project.getTransport().setReturnToStartOnStop(model.isReturnToStartOnStop());
        String newPluginPaths = model.getPluginScanPaths();
        if (!newPluginPaths.equals(previousPluginPaths) && !newPluginPaths.isBlank()) {
            List<Path> paths = new ArrayList<>();
            for (String p : newPluginPaths.split(";")) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) { paths.add(Path.of(trimmed)); }
            }
            if (!paths.isEmpty()) { pluginRegistry.scanClapPlugins(paths); }
        }
    }
}
