package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.SpectrumDisplayWindow;
import com.benesquivelmusic.daw.app.ui.display.TunerDisplayWindow;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.core.plugin.*;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.spatial.binaural.HrtfImportController;
import com.benesquivelmusic.daw.core.spatial.binaural.HrtfProfileLibrary;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
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

    /**
     * Story 294 — direct functional deps replace the callback-up {@code Host}
     * (Control Synchronization Design Book §4.2/§9 "use publish/subscribe, not a
     * callback-up {@code Host} for cross-surface updates"). {@code sampleRate}/
     * {@code bufferSize} are primitive {@link DoubleSupplier}/{@link IntSupplier}
     * reads; the swappable project is a live {@link Supplier} (read live, so a
     * project load is reflected without rebuilding this controller); mastering
     * navigation, the docked telemetry panel (story 287), and dirty are
     * {@link Runnable}s ({@code markProjectDirty} routes through
     * {@code DawProject.markDirty()} now — the §1.2 "one dirty bit"); status and
     * notification are {@link BiConsumer} sinks.
     */
    record Deps(
            DoubleSupplier sampleRate,
            IntSupplier bufferSize,
            Supplier<DawProject> project,
            Runnable markProjectDirty,
            Runnable switchToMasteringView,
            BiConsumer<String, DawIcon> updateStatusBar,
            BiConsumer<NotificationLevel, String> showNotification,
            Runnable showTelemetryPanel) {
    }

    private final Deps deps;
    private final Map<Class<? extends BuiltInDawPlugin>, BuiltInDawPlugin> builtInPluginCache = new HashMap<>();
    private Stage virtualKeyboardStage;
    private SpectrumDisplayWindow builtInSpectrumWindow;
    private TunerDisplayWindow tunerDisplayWindow;
    private Stage busCompressorStage;
    private BusCompressorPluginView busCompressorView;
    private Stage multibandCompressorStage;
    private MultibandCompressorPluginView multibandCompressorView;
    private Stage deEsserStage;
    private DeEsserPluginView deEsserView;
    private Stage truePeakLimiterStage;
    private TruePeakLimiterPluginView truePeakLimiterView;
    private Stage transientShaperStage;
    private TransientShaperPluginView transientShaperView;
    private Stage noiseGateStage;
    private NoiseGatePluginView noiseGateView;
    private Stage convolutionReverbStage;
    private ConvolutionReverbPluginView convolutionReverbView;
    private Stage exciterStage;
    private ExciterPluginView exciterView;
    private Stage binauralMonitorStage;
    private BinauralMonitorPluginView binauralMonitorView;
    private Stage midSideWrapperStage;
    private MidSideWrapperPluginView midSideWrapperView;
    private final HrtfProfileLibrary hrtfProfileLibrary = new HrtfProfileLibrary();

    PluginViewController(Deps deps) {
        this.deps = deps;
    }

    void onManagePlugins(PluginRegistry pluginRegistry) {
        deps.updateStatusBar().accept("Opening plugin manager...", com.benesquivelmusic.daw.app.ui.icons.DawIcon.MENU);
        PluginManagerDialog dialog = new PluginManagerDialog(pluginRegistry);
        dialog.showAndWait();
        deps.updateStatusBar().accept("Plugin manager closed", com.benesquivelmusic.daw.app.ui.icons.DawIcon.SETTINGS);
    }

    void onActivateBuiltInPlugin(Class<? extends BuiltInDawPlugin> pluginClass) {
        try {
            BuiltInDawPlugin plugin = builtInPluginCache.computeIfAbsent(pluginClass, cls -> {
                try {
                    BuiltInDawPlugin instance = cls.getConstructor().newInstance();
                    PluginContext pluginContext = new PluginContext() {
                        @Override public double getSampleRate() { return deps.sampleRate().getAsDouble(); }
                        @Override public int getBufferSize() { return deps.bufferSize().getAsInt(); }
                        @Override public void log(String message) { LOG.info(message); }
                    };
                    instance.initialize(pluginContext);
                    return instance;
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException("Failed to instantiate built-in plugin: " + cls.getName(), e);
                }
            });
            deps.updateStatusBar().accept("Activating " + plugin.getMenuLabel() + "...", null);
            plugin.activate();
            openBuiltInPluginView(plugin);
            deps.updateStatusBar().accept(plugin.getMenuLabel() + " activated", null);
            LOG.fine("Activated built-in plugin: " + plugin.getMenuLabel());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to activate built-in plugin: " + pluginClass.getName(), e);
            deps.updateStatusBar().accept("Failed to activate " + pluginClass.getSimpleName(), null);
            deps.showNotification().accept(NotificationLevel.ERROR,
                    "Failed to activate " + pluginClass.getSimpleName() + ": " + e.getMessage());
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
        if (busCompressorStage != null) {
            busCompressorStage.hide();
        }
        if (multibandCompressorStage != null) {
            multibandCompressorStage.hide();
        }
        if (deEsserStage != null) {
            deEsserStage.hide();
        }
        if (truePeakLimiterStage != null) {
            truePeakLimiterStage.hide();
        }
        if (transientShaperStage != null) {
            transientShaperStage.hide();
        }
        if (noiseGateStage != null) {
            noiseGateStage.hide();
        }
        if (midSideWrapperStage != null) {
            midSideWrapperStage.hide();
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
            case BusCompressorPlugin.PLUGIN_ID -> openBusCompressorWindow((BusCompressorPlugin) plugin);
            case MultibandCompressorPlugin.PLUGIN_ID -> openMultibandCompressorWindow((MultibandCompressorPlugin) plugin);
            case DeEsserPlugin.PLUGIN_ID -> openDeEsserWindow((DeEsserPlugin) plugin);
            case TruePeakLimiterPlugin.PLUGIN_ID -> openTruePeakLimiterWindow((TruePeakLimiterPlugin) plugin);
            case TransientShaperPlugin.PLUGIN_ID -> openTransientShaperWindow((TransientShaperPlugin) plugin);
            case NoiseGatePlugin.PLUGIN_ID -> openNoiseGateWindow((NoiseGatePlugin) plugin);
            case ConvolutionReverbPlugin.PLUGIN_ID -> openConvolutionReverbWindow((ConvolutionReverbPlugin) plugin);
            case ExciterPlugin.PLUGIN_ID -> openExciterWindow((ExciterPlugin) plugin);
            case BinauralMonitorPlugin.PLUGIN_ID -> openBinauralMonitorWindow((BinauralMonitorPlugin) plugin);
            case MidSideWrapperPlugin.PLUGIN_ID -> openMidSideWrapperWindow((MidSideWrapperPlugin) plugin);
            case ParametricEqPlugin.PLUGIN_ID,
                 CompressorPlugin.PLUGIN_ID,
                 ReverbPlugin.PLUGIN_ID -> deps.switchToMasteringView().run();
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
        ThemeManager.getDefault().applyTo(stage.getScene());
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
                builtInSpectrumWindow.getDisplay().dispose();
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
                tunerDisplayWindow.getDisplay().dispose();
                tunerDisplayWindow = null;
            });
        }
        tunerDisplayWindow.setReferencePitchHz(plugin.getReferencePitchHz());
        tunerDisplayWindow.show();
    }

    /**
     * Story 287 — the Sound Wave Telemetry view is no longer a standalone
     * {@code Stage}. It is the shared {@code TelemetryView} owned by
     * {@code MainController} and registered with the {@code DockManager}
     * as {@code PANEL_TELEMETRY} / {@code PANEL_ROOM_3D}. Activating the
     * plugin (done by the caller in {@link #onActivateBuiltInPlugin}
     * before this method runs) keeps the armed-track source subscription
     * live; here we simply route to the host to show / focus the docked
     * panel. The plugin is deactivated on app shutdown via
     * {@link #dispose()} (its {@code dispose()} unsubscribes the provider),
     * not on window-hide — the panel can now be hidden and re-shown
     * without tearing down the plugin.
     */
    private void openSoundWaveTelemetryWindow(SoundWaveTelemetryPlugin plugin) {
        deps.showTelemetryPanel().run();
    }

    private void openBusCompressorWindow(BusCompressorPlugin plugin) {
        if (busCompressorStage != null) {
            busCompressorStage.show();
            busCompressorStage.toFront();
            return;
        }

        busCompressorView = new BusCompressorPluginView(plugin.getProcessor());

        Stage stage = new Stage(StageStyle.UTILITY);
        stage.setTitle("Bus Compressor");
        stage.setScene(new Scene(busCompressorView));
        ThemeManager.getDefault().applyTo(stage.getScene());
        stage.setMinWidth(900);
        stage.setMinHeight(260);
        stage.setOnHidden(_ -> {
            if (busCompressorView != null) {
                busCompressorView.dispose();
                busCompressorView = null;
            }
            plugin.deactivate();
            busCompressorStage = null;
        });
        stage.show();
        stage.toFront();
        busCompressorStage = stage;
    }

    private void openMultibandCompressorWindow(MultibandCompressorPlugin plugin) {
        if (multibandCompressorStage != null) {
            multibandCompressorStage.show();
            multibandCompressorStage.toFront();
            return;
        }

        multibandCompressorView = new MultibandCompressorPluginView(plugin);

        Stage stage = new Stage(StageStyle.UTILITY);
        stage.setTitle("Multiband Compressor");
        stage.setScene(new Scene(multibandCompressorView));
        ThemeManager.getDefault().applyTo(stage.getScene());
        stage.setMinWidth(960);
        stage.setMinHeight(540);
        stage.setOnHidden(_ -> {
            if (multibandCompressorView != null) {
                multibandCompressorView.dispose();
                multibandCompressorView = null;
            }
            plugin.deactivate();
            multibandCompressorStage = null;
        });
        stage.show();
        stage.toFront();
        multibandCompressorStage = stage;
    }

    private void openDeEsserWindow(DeEsserPlugin plugin) {
        if (deEsserStage != null) {
            deEsserStage.show();
            deEsserStage.toFront();
            return;
        }

        deEsserView = new DeEsserPluginView(plugin.getProcessor());

        Stage stage = new Stage(StageStyle.UTILITY);
        stage.setTitle("De-Esser");
        stage.setScene(new Scene(deEsserView));
        ThemeManager.getDefault().applyTo(stage.getScene());
        stage.setMinWidth(720);
        stage.setMinHeight(260);
        stage.setOnHidden(_ -> {
            if (deEsserView != null) {
                deEsserView.dispose();
                deEsserView = null;
            }
            plugin.deactivate();
            deEsserStage = null;
        });
        stage.show();
        stage.toFront();
        deEsserStage = stage;
    }

    private void openTruePeakLimiterWindow(TruePeakLimiterPlugin plugin) {
        if (truePeakLimiterStage != null) {
            truePeakLimiterStage.show();
            truePeakLimiterStage.toFront();
            return;
        }

        truePeakLimiterView = new TruePeakLimiterPluginView(plugin.getProcessor());

        Stage stage = new Stage(StageStyle.UTILITY);
        stage.setTitle("True-Peak Limiter");
        stage.setScene(new Scene(truePeakLimiterView));
        ThemeManager.getDefault().applyTo(stage.getScene());
        stage.setMinWidth(820);
        stage.setMinHeight(280);
        stage.setOnHidden(_ -> {
            if (truePeakLimiterView != null) {
                truePeakLimiterView.dispose();
                truePeakLimiterView = null;
            }
            plugin.deactivate();
            truePeakLimiterStage = null;
        });
        stage.show();
        stage.toFront();
        truePeakLimiterStage = stage;
    }

    private void openTransientShaperWindow(TransientShaperPlugin plugin) {
        if (transientShaperStage != null) {
            transientShaperStage.show();
            transientShaperStage.toFront();
            return;
        }

        transientShaperView = new TransientShaperPluginView(plugin.getProcessor());

        Stage stage = new Stage(StageStyle.UTILITY);
        stage.setTitle("Transient Shaper");
        stage.setScene(new Scene(transientShaperView));
        ThemeManager.getDefault().applyTo(stage.getScene());
        stage.setMinWidth(820);
        stage.setMinHeight(280);
        stage.setOnHidden(_ -> {
            if (transientShaperView != null) {
                transientShaperView.dispose();
                transientShaperView = null;
            }
            plugin.deactivate();
            transientShaperStage = null;
        });
        stage.show();
        stage.toFront();
        transientShaperStage = stage;
    }

    private void openNoiseGateWindow(NoiseGatePlugin plugin) {
        if (noiseGateStage != null) {
            noiseGateStage.show();
            noiseGateStage.toFront();
            return;
        }

        noiseGateView = new NoiseGatePluginView(plugin.getProcessor());

        Stage stage = new Stage(StageStyle.UTILITY);
        stage.setTitle("Noise Gate");
        stage.setScene(new Scene(noiseGateView));
        ThemeManager.getDefault().applyTo(stage.getScene());
        stage.setMinWidth(960);
        stage.setMinHeight(340);
        stage.setOnHidden(_ -> {
            if (noiseGateView != null) {
                noiseGateView.dispose();
                noiseGateView = null;
            }
            plugin.deactivate();
            noiseGateStage = null;
        });
        stage.show();
        stage.toFront();
        noiseGateStage = stage;
    }

    private void openConvolutionReverbWindow(ConvolutionReverbPlugin plugin) {
        if (convolutionReverbStage != null) {
            convolutionReverbStage.show();
            convolutionReverbStage.toFront();
            return;
        }

        convolutionReverbView = new ConvolutionReverbPluginView(plugin.getProcessor());

        Stage stage = new Stage(StageStyle.UTILITY);
        stage.setTitle("Convolution Reverb");
        stage.setScene(new Scene(convolutionReverbView));
        ThemeManager.getDefault().applyTo(stage.getScene());
        stage.setMinWidth(720);
        stage.setMinHeight(360);
        stage.setOnHidden(_ -> {
            if (convolutionReverbView != null) {
                convolutionReverbView.dispose();
                convolutionReverbView = null;
            }
            plugin.deactivate();
            convolutionReverbStage = null;
        });
        stage.show();
        stage.toFront();
        convolutionReverbStage = stage;
    }

    private void openExciterWindow(ExciterPlugin plugin) {
        if (exciterStage != null) {
            exciterStage.show();
            exciterStage.toFront();
            return;
        }

        exciterView = new ExciterPluginView(plugin.getProcessor());

        Stage stage = new Stage(StageStyle.UTILITY);
        stage.setTitle("Harmonic Exciter");
        stage.setScene(new Scene(exciterView));
        ThemeManager.getDefault().applyTo(stage.getScene());
        stage.setMinWidth(640);
        stage.setMinHeight(320);
        stage.setOnHidden(_ -> {
            if (exciterView != null) {
                exciterView.dispose();
                exciterView = null;
            }
            plugin.deactivate();
            exciterStage = null;
        });
        stage.show();
        stage.toFront();
        exciterStage = stage;
    }

    private void openBinauralMonitorWindow(BinauralMonitorPlugin plugin) {
        if (binauralMonitorStage != null) {
            binauralMonitorStage.show();
            binauralMonitorStage.toFront();
            return;
        }

        binauralMonitorView = new BinauralMonitorPluginView(
                plugin, hrtfProfileLibrary, deps.sampleRate().getAsDouble());

        // Persist the user's selection on the active project so it survives
        // save/load (story 174 — per-project active HRTF profile).
        binauralMonitorView.setProfileSelectionListener(entry -> {
            String current = deps.project().get().getActiveHrtfProfileName();
            if (!entry.displayName().equals(current)) {
                deps.project().get().setActiveHrtfProfileName(entry.displayName());
                deps.markProjectDirty().run();
            }
        });
        // Honour any previously saved selection, resolving through the
        // controller so missing profiles trigger the one-shot fallback warning.
        String saved = deps.project().get().getActiveHrtfProfileName();
        if (saved != null) {
            HrtfImportController resolver = new HrtfImportController(
                    hrtfProfileLibrary, deps.sampleRate().getAsDouble());
            HrtfImportController.Resolution resolution = resolver.resolve(saved);
            binauralMonitorView.selectProfileByNameSilently(resolution.displayName());
            resolution.fallbackWarning().ifPresent(warning ->
                    deps.showNotification().accept(NotificationLevel.WARNING, warning));
        }

        Stage stage = new Stage(StageStyle.UTILITY);
        stage.setTitle("Binaural Monitor");
        stage.setScene(new Scene(binauralMonitorView));
        ThemeManager.getDefault().applyTo(stage.getScene());
        stage.setMinWidth(420);
        stage.setMinHeight(220);
        stage.setOnHidden(_ -> {
            binauralMonitorView = null;
            plugin.deactivate();
            binauralMonitorStage = null;
        });
        stage.show();
        stage.toFront();
        binauralMonitorStage = stage;
    }

    private void openMidSideWrapperWindow(MidSideWrapperPlugin plugin) {
        if (midSideWrapperStage != null) {
            midSideWrapperStage.show();
            midSideWrapperStage.toFront();
            return;
        }

        midSideWrapperView = new MidSideWrapperPluginView(plugin);

        Stage stage = new Stage(StageStyle.UTILITY);
        stage.setTitle("Mid/Side Wrapper");
        stage.setScene(new Scene(midSideWrapperView));
        ThemeManager.getDefault().applyTo(stage.getScene());
        stage.setMinWidth(520);
        stage.setMinHeight(380);
        stage.setOnHidden(_ -> {
            midSideWrapperView = null;
            plugin.deactivate();
            midSideWrapperStage = null;
        });
        stage.show();
        stage.toFront();
        midSideWrapperStage = stage;
    }

    /**
     * Opens the standalone "Manage HRTF Profiles…" dialog (Settings entry-point
     * for story 174). Surfaced as a {@code public} method so the menu bar can
     * route a "Settings → HRTF Profiles…" action through the plugin controller.
     */
    public void onManageHrtfProfiles() {
        HrtfProfileBrowserDialog dialog = new HrtfProfileBrowserDialog(
                hrtfProfileLibrary, deps.sampleRate().getAsDouble());
        dialog.showAndWait();
    }
}
