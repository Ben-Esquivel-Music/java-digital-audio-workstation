package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.core.project.DawProject;
import javafx.scene.Scene;
import javafx.scene.transform.Scale;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies live settings changes to the running DAW — UI scale, auto-save
 * interval, default tempo, and plugin scan paths.
 *
 * <p>Extracted from {@code MainController} to keep the main coordinator
 * free of settings propagation logic.</p>
 */
final class LiveSettingsApplier {

    private LiveSettingsApplier() {}

    static void apply(SettingsModel model, String previousPluginPaths,
                      Scene scene, ProjectManager projectManager,
                      DawProject project, PluginRegistry pluginRegistry) {
        if (scene != null && scene.getRoot() != null) {
            double scale = model.getUiScale();
            scene.getRoot().getTransforms().clear();
            scene.getRoot().getTransforms().add(new Scale(scale, scale));
        }
        CheckpointManager checkpointManager = projectManager.getCheckpointManager();
        AutoSaveConfig currentConfig = checkpointManager.getConfig();
        Duration newInterval = Duration.ofSeconds(model.getAutoSaveIntervalSeconds());
        if (!currentConfig.autoSaveInterval().equals(newInterval)) {
            checkpointManager.reconfigure(new AutoSaveConfig(newInterval,
                    currentConfig.maxCheckpoints(), currentConfig.enabled()));
        }
        project.getTransport().setTempo(model.getDefaultTempo());
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
