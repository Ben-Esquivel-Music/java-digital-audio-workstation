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
 * interval, default tempo, transport stop behaviour, and plugin scan
 * paths.
 *
 * <p>Extracted from {@code MainController} to keep the main coordinator
 * free of settings propagation logic.</p>
 */
final class LiveSettingsApplier {

    private LiveSettingsApplier() {}

    /**
     * Applies {@code model} to the running DAW.
     *
     * @param tempoWriteAllowed whether the default tempo may be written to the
     *                          open project's transport — a value the project
     *                          file stores, so it is written only while the
     *                          window's edit gate says yes; the other settings
     *                          are the application's and are always applied
     *                          (PR #989 review 5711460812)
     */
    static void apply(SettingsModel model, String previousPluginPaths,
                      Scene scene, ProjectManager projectManager,
                      DawProject project, PluginRegistry pluginRegistry,
                      boolean tempoWriteAllowed) {
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
        if (tempoWriteAllowed) {
            project.getTransport().setTempo(model.getDefaultTempo());
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
