package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.SpectrumDisplayWindow;
import com.benesquivelmusic.daw.app.ui.display.TunerDisplayWindow;
import com.benesquivelmusic.daw.core.plugin.*;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages built-in plugin activation, floating plugin windows, and plugin
 * lifecycle (dispose on shutdown).
 *
 * <p>Extracted from {@code MainController} to separate plugin view management
 * from the main coordinator.</p>
 */
final class PluginViewController {

    private static final Logger LOG = Logger.getLogger(PluginViewController.class.getName());

    interface Host {
        double sampleRate();
        int bufferSize();
        com.benesquivelmusic.daw.core.project.DawProject project();
        void setProjectDirty();
        void switchToMasteringView();
        void updateStatusBar(String text, com.benesquivelmusic.daw.app.ui.icons.DawIcon icon);
        void showNotification(NotificationLevel level, String message);
    }

    private final Host host;
    private final Map<Class<? extends BuiltInDawPlugin>, BuiltInDawPlugin> builtInPluginCache = new HashMap<>();
    private Stage virtualKeyboardStage;
    private SpectrumDisplayWindow builtInSpectrumWindow;
    private TunerDisplayWindow tunerDisplayWindow;
    private Stage telemetryPluginStage;
    private TelemetryView telemetryPluginView;

    PluginViewController(Host host) {
        this.host = host;
    }

    void onManagePlugins(PluginRegistry pluginRegistry) {
        host.updateStatusBar("Opening plugin manager...", com.benesquivelmusic.daw.app.ui.icons.DawIcon.MENU);
        PluginManagerDialog dialog = new PluginManagerDialog(pluginRegistry);
        dialog.showAndWait();
        host.updateStatusBar("Plugin manager closed", com.benesquivelmusic.daw.app.ui.icons.DawIcon.SETTINGS);
    }

    void onActivateBuiltInPlugin(Class<? extends BuiltInDawPlugin> pluginClass) {
        try {
            BuiltInDawPlugin plugin = builtInPluginCache.computeIfAbsent(pluginClass, cls -> {
                try {
                    BuiltInDawPlugin instance = cls.getConstructor().newInstance();
                    PluginContext pluginContext = new PluginContext() {
                        @Override public double getSampleRate() { return host.sampleRate(); }
                        @Override public int getBufferSize() { return host.bufferSize(); }
                        @Override public void log(String message) { LOG.info(message); }
                    };
                    instance.initialize(pluginContext);
                    return instance;
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException("Failed to instantiate built-in plugin: " + cls.getName(), e);
                }
            });
            host.updateStatusBar("Activating " + plugin.getMenuLabel() + "...", null);
            plugin.activate();
            openBuiltInPluginView(plugin);
            host.updateStatusBar(plugin.getMenuLabel() + " activated", null);
            LOG.fine("Activated built-in plugin: " + plugin.getMenuLabel());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to activate built-in plugin: " + pluginClass.getName(), e);
            host.updateStatusBar("Failed to activate " + pluginClass.getSimpleName(), null);
            host.showNotification(NotificationLevel.ERROR,
                    "Failed to activate " + pluginClass.getSimpleName() + ": " + e.getMessage());
        }
    }

    void onProjectChanged(com.benesquivelmusic.daw.core.project.DawProject project) {
        if (telemetryPluginView != null) {
            telemetryPluginView.setProject(project);
        }
    }

    void dispose() {
        if (virtualKeyboardStage != null) {
            virtualKeyboardStage.hide();
        }
        if (builtInSpectrumWindow != null) {
            builtInSpectrumWindow.getStage().hide();
        }
        if (tunerDisplayWindow != null) {
            tunerDisplayWindow.getStage().hide();
        }
        if (telemetryPluginStage != null) {
            telemetryPluginStage.hide();
        }
        try {
            for (BuiltInDawPlugin plugin : builtInPluginCache.values()) {
                try {
                    plugin.dispose();
                } catch (Exception ex) {
                    LOG.log(Level.WARNING,
                            "Failed to dispose built-in plugin: " + plugin.getClass().getName(), ex);
                }
            }
        } finally {
            builtInPluginCache.clear();
        }
    }

    private void openBuiltInPluginView(BuiltInDawPlugin plugin) {
        String pluginId = plugin.getDescriptor().id();
        switch (pluginId) {
            case VirtualKeyboardPlugin.PLUGIN_ID -> openVirtualKeyboardWindow((VirtualKeyboardPlugin) plugin);
            case SpectrumAnalyzerPlugin.PLUGIN_ID -> openSpectrumAnalyzerWindow((SpectrumAnalyzerPlugin) plugin);
            case TunerPlugin.PLUGIN_ID -> openTunerWindow((TunerPlugin) plugin);
            case SoundWaveTelemetryPlugin.PLUGIN_ID -> openSoundWaveTelemetryWindow((SoundWaveTelemetryPlugin) plugin);
            case ParametricEqPlugin.PLUGIN_ID,
                 CompressorPlugin.PLUGIN_ID,
                 ReverbPlugin.PLUGIN_ID -> host.switchToMasteringView();
            default -> LOG.fine("No associated built-in view mapping for plugin id: " + pluginId);
        }
    }

    private void openVirtualKeyboardWindow(VirtualKeyboardPlugin plugin) {
        if (virtualKeyboardStage != null) {
            virtualKeyboardStage.show();
            virtualKeyboardStage.toFront();
            return;
        }

        KeyboardProcessorView keyboardView = new KeyboardProcessorView(plugin.getProcessor());

        Stage stage = new Stage(StageStyle.UTILITY);
        stage.setTitle("Virtual Keyboard");
        stage.setScene(new Scene(keyboardView));
        DarkThemeHelper.applyTo(stage.getScene());
        stage.setMinWidth(720);
        stage.setMinHeight(280);
        stage.setOnHidden(_ -> {
            keyboardView.dispose();
            plugin.deactivate();
            virtualKeyboardStage = null;
        });
        stage.show();
        stage.toFront();
        virtualKeyboardStage = stage;
    }

    private void openSpectrumAnalyzerWindow(SpectrumAnalyzerPlugin plugin) {
        if (builtInSpectrumWindow == null) {
            builtInSpectrumWindow = new SpectrumDisplayWindow();
            builtInSpectrumWindow.setOnFftSizeChanged(fftSize -> {
                var analyzer = plugin.getAnalyzer();
                if (analyzer != null) {
                    plugin.reconfigure(fftSize, analyzer.getWindowType());
                }
            });
            builtInSpectrumWindow.setOnWindowTypeChanged(windowType -> {
                var analyzer = plugin.getAnalyzer();
                if (analyzer != null) {
                    plugin.reconfigure(analyzer.getFftSize(), windowType);
                }
            });
            builtInSpectrumWindow.getStage().setOnHidden(_ -> {
                plugin.deactivate();
                builtInSpectrumWindow = null;
            });
        }
        builtInSpectrumWindow.show();
    }

    private void openTunerWindow(TunerPlugin plugin) {
        if (tunerDisplayWindow == null) {
            tunerDisplayWindow = new TunerDisplayWindow();
            tunerDisplayWindow.setOnReferencePitchChanged(plugin::setReferencePitchHz);
            tunerDisplayWindow.getStage().setOnHidden(_ -> {
                plugin.deactivate();
                tunerDisplayWindow = null;
            });
        }
        tunerDisplayWindow.setReferencePitchHz(plugin.getReferencePitchHz());
        tunerDisplayWindow.show();
    }

    private void openSoundWaveTelemetryWindow(SoundWaveTelemetryPlugin plugin) {
        if (telemetryPluginStage != null) {
            telemetryPluginStage.show();
            telemetryPluginStage.toFront();
            return;
        }

        telemetryPluginView = new TelemetryView();
        telemetryPluginView.setProject(host.project());
        telemetryPluginView.setOnDirtyChanged(host::setProjectDirty);

        Stage stage = new Stage(StageStyle.UTILITY);
        stage.setTitle("Sound Wave Telemetry");
        stage.setScene(new Scene(telemetryPluginView));
        DarkThemeHelper.applyTo(stage.getScene());
        stage.setMinWidth(800);
        stage.setMinHeight(600);
        stage.setOnShown(_ -> telemetryPluginView.startAnimation());
        stage.setOnHidden(_ -> {
            telemetryPluginView.stopAnimation();
            plugin.deactivate();
            telemetryPluginStage = null;
            telemetryPluginView = null;
        });
        stage.show();
        stage.toFront();
        telemetryPluginStage = stage;
    }
}
