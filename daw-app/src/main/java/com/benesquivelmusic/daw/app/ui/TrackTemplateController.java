package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.template.AddTrackFromTemplateAction;
import com.benesquivelmusic.daw.core.template.ApplyChannelStripPresetAction;
import com.benesquivelmusic.daw.core.template.ChannelStripPreset;
import com.benesquivelmusic.daw.core.template.TrackTemplate;
import com.benesquivelmusic.daw.core.template.TrackTemplateFactory;
import com.benesquivelmusic.daw.core.template.TrackTemplateService;
import com.benesquivelmusic.daw.core.template.TrackTemplateStore;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextInputDialog;
import javafx.stage.Window;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coordinates the UI workflows for {@link TrackTemplate}s and
 * {@link ChannelStripPreset}s.
 *
 * <p>Implements the user-facing story <em>"Track Templates and Channel
 * Strip Presets"</em> by bridging the headless services in
 * {@link com.benesquivelmusic.daw.core.template} with JavaFX dialogs and
 * the project's undo manager:</p>
 *
 * <ul>
 *   <li>Save the current track as a template — prompts for a name and
 *       writes via {@link TrackTemplateStore#saveTemplate(TrackTemplate)}.</li>
 *   <li>Add a track from a template — opens a {@link TrackTemplateBrowser}
 *       on the templates tab and runs an {@link AddTrackFromTemplateAction}
 *       through the {@link UndoManager} so the result is undoable.</li>
 *   <li>Save the channel strip as a preset — prompts for a name and writes
 *       via {@link TrackTemplateStore#savePreset(ChannelStripPreset)}.</li>
 *   <li>Apply a channel-strip preset to a mixer channel — opens a browser,
 *       lets the user pick a preset, then runs an
 *       {@link ApplyChannelStripPresetAction} through the undo manager.</li>
 *   <li>Manage templates and presets — opens the unified browser with
 *       Import / Export controls for round-tripping XML files.</li>
 * </ul>
 *
 * <p>All persistence goes through {@link TrackTemplateStore}; factory
 * defaults live in {@code TrackTemplateFactory} and are returned by
 * {@link TrackTemplateStore#allTemplates()} / {@link TrackTemplateStore#allPresets()}
 * so first-launch users always have something to apply.</p>
 */
public final class TrackTemplateController {

    private static final Logger LOG = Logger.getLogger(TrackTemplateController.class.getName());

    /**
     * Story 294 — direct functional deps replace the callback-up {@code Host}
     * (Control Synchronization Design Book §4.2/§9 "use publish/subscribe, not a
     * callback-up {@code Host} for cross-surface updates"). The swappable
     * project / undo manager / parent window are live {@link Supplier}s (read
     * live, so a project load is reflected without rebuilding this controller);
     * notification and mixer-refresh are {@link BiConsumer}/{@link Runnable}
     * sinks. The window supplier may yield {@code null} (no modal parent yet).
     */
    public record Deps(
            Supplier<DawProject> project,
            Supplier<UndoManager> undoManager,
            Supplier<Window> window,
            BiConsumer<NotificationLevel, String> showNotification,
            Runnable refreshMixer) {
        public Deps {
            Objects.requireNonNull(project, "project must not be null");
            Objects.requireNonNull(undoManager, "undoManager must not be null");
            Objects.requireNonNull(window, "window must not be null");
            Objects.requireNonNull(showNotification, "showNotification must not be null");
            Objects.requireNonNull(refreshMixer, "refreshMixer must not be null");
        }
    }

    private final Deps deps;
    private final Supplier<TrackTemplateStore> storeSupplier;
    private volatile TrackTemplateStore cachedStore;

    /**
     * Creates a controller backed by the user's default template store
     * (under {@code ~/.daw}).
     *
     * @param deps the functional deps providing project, undo manager, and window
     */
    public TrackTemplateController(Deps deps) {
        this(deps, TrackTemplateStore::new);
    }

    /**
     * Creates a controller backed by a custom {@link TrackTemplateStore}
     * supplier — used by tests to point persistence at a temp directory.
     *
     * @param deps          the functional deps providing project, undo manager, and window
     * @param storeSupplier supplier of the disk-backed store
     */
    public TrackTemplateController(Deps deps, Supplier<TrackTemplateStore> storeSupplier) {
        this.deps = Objects.requireNonNull(deps, "deps must not be null");
        this.storeSupplier = Objects.requireNonNull(storeSupplier, "storeSupplier must not be null");
    }

    /**
     * Returns the underlying store, creating it on first access and
     * caching the result so subsequent calls return the same instance.
     */
    public TrackTemplateStore store() {
        TrackTemplateStore s = cachedStore;
        if (s == null) {
            s = storeSupplier.get();
            cachedStore = s;
        }
        return s;
    }

    // ── Save flows ──────────────────────────────────────────────────────────

    /**
     * Prompts for a template name and saves the given track as a template.
     *
     * @param track the track to capture
     */
    public void saveTrackAsTemplate(Track track) {
        Objects.requireNonNull(track, "track must not be null");
        Optional<String> name = promptForName(
                "Save Track as Template",
                "Save \u201C" + track.getName() + "\u201D as a reusable track template.",
                "Template name:",
                track.getName());
        if (name.isEmpty()) {
            return;
        }
        try {
            TrackTemplate template = TrackTemplateService.captureTrack(
                    name.get(), track, deps.project().get());
            store().saveTemplate(template);
            deps.showNotification().accept(NotificationLevel.SUCCESS,
                    "Saved track template: " + template.templateName());
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to save track template", e);
            deps.showNotification().accept(NotificationLevel.ERROR,
                    "Failed to save track template: " + e.getMessage());
        }
    }

    /**
     * Prompts for a preset name and saves the given mixer channel's strip
     * as a {@link ChannelStripPreset}.
     *
     * @param channel the channel whose insert chain and sends are captured
     */
    public void saveChannelStripAsPreset(MixerChannel channel) {
        Objects.requireNonNull(channel, "channel must not be null");
        Optional<String> name = promptForName(
                "Save Channel Strip",
                "Save the insert chain, sends, volume, and pan on \u201C" + channel.getName()
                        + "\u201D as a reusable channel-strip preset.",
                "Preset name:",
                channel.getName() + " Strip");
        if (name.isEmpty()) {
            return;
        }
        try {
            ChannelStripPreset preset = TrackTemplateService.captureChannelStrip(
                    name.get(), channel);
            store().savePreset(preset);
            deps.showNotification().accept(NotificationLevel.SUCCESS,
                    "Saved channel strip preset: " + preset.presetName());
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to save channel strip preset", e);
            deps.showNotification().accept(NotificationLevel.ERROR,
                    "Failed to save channel strip preset: " + e.getMessage());
        }
    }

    // ── Apply / instantiate flows ───────────────────────────────────────────

    /**
     * Opens the template browser focused on the Track Templates tab. If the
     * user picks a template and clicks <em>Insert</em>, an undoable
     * {@link AddTrackFromTemplateAction} is run through the undo manager.
     */
    public void addTrackFromTemplate() {
        TrackTemplateBrowser browser = openBrowser(TrackTemplateBrowser.InitialTab.TEMPLATES, true);
        TrackTemplate template = browser.getSelectedTemplate();
        if (template == null) {
            return;
        }
        try {
            AddTrackFromTemplateAction action = new AddTrackFromTemplateAction(
                    deps.project().get(), template);
            deps.undoManager().get().execute(action);
            deps.refreshMixer().run();
            deps.showNotification().accept(NotificationLevel.SUCCESS,
                    "Added track from template: " + template.templateName());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to instantiate template", e);
            deps.showNotification().accept(NotificationLevel.ERROR,
                    "Failed to add track from template: " + e.getMessage());
        }
    }

    /**
     * Opens the preset browser. If the user picks a preset and clicks
     * <em>Insert</em>, an undoable {@link ApplyChannelStripPresetAction} is
     * applied to the given channel after a confirmation dialog.
     *
     * @param channel the target mixer channel
     */
    public void applyChannelStripPreset(MixerChannel channel) {
        Objects.requireNonNull(channel, "channel must not be null");
        TrackTemplateBrowser browser = openBrowser(TrackTemplateBrowser.InitialTab.PRESETS, true);
        ChannelStripPreset preset = browser.getSelectedPreset();
        if (preset == null) {
            return;
        }
        if (channel.getInsertCount() > 0 || !channel.getSends().isEmpty()) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Replace the existing inserts, sends, volume, and pan on \u201C"
                            + channel.getName() + "\u201D with the preset \u201C"
                            + preset.presetName() + "\u201D?",
                    ButtonType.OK, ButtonType.CANCEL);
            confirm.setHeaderText("Apply Channel Strip Preset");
            if (deps.window().get() != null) {
                confirm.initOwner(deps.window().get());
            }
            Optional<ButtonType> response = confirm.showAndWait();
            if (response.isEmpty() || response.get() != ButtonType.OK) {
                return;
            }
        }
        try {
            Mixer mixer = deps.project().get().getMixer();
            AudioFormat format = deps.project().get().getFormat();
            ApplyChannelStripPresetAction action = new ApplyChannelStripPresetAction(
                    channel, preset, mixer, format);
            deps.undoManager().get().execute(action);
            deps.refreshMixer().run();
            deps.showNotification().accept(NotificationLevel.SUCCESS,
                    "Applied preset: " + preset.presetName()
                            + " \u2192 " + channel.getName());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to apply channel strip preset", e);
            deps.showNotification().accept(NotificationLevel.ERROR,
                    "Failed to apply preset: " + e.getMessage());
        }
    }

    /**
     * Opens the manage-templates browser (both tabs visible, Import / Export
     * available). Returns once the user closes the dialog; nothing else
     * happens — selection is informational.
     */
    public void openManager() {
        openBrowser(TrackTemplateBrowser.InitialTab.TEMPLATES, false);
    }

    // ── Loading helpers (also used by the browser) ──────────────────────────

    /** Returns the union of factory + user templates, never {@code null}. */
    public List<TrackTemplate> loadAllTemplates() {
        try {
            return store().allTemplates();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to load user templates; falling back to factory defaults", e);
            deps.showNotification().accept(NotificationLevel.WARNING,
                    "Failed to load user templates: " + e.getMessage());
            return List.copyOf(TrackTemplateFactory.factoryTemplates());
        }
    }

    /** Returns the union of factory + user presets, never {@code null}. */
    public List<ChannelStripPreset> loadAllPresets() {
        try {
            return store().allPresets();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to load user presets; falling back to factory defaults", e);
            deps.showNotification().accept(NotificationLevel.WARNING,
                    "Failed to load user presets: " + e.getMessage());
            return List.copyOf(TrackTemplateFactory.factoryPresets());
        }
    }

    // ── internals ───────────────────────────────────────────────────────────

    private TrackTemplateBrowser openBrowser(TrackTemplateBrowser.InitialTab initialTab,
                                             boolean restrictToTab) {
        TrackTemplateBrowser browser = new TrackTemplateBrowser(this, initialTab, restrictToTab);
        if (deps.window().get() != null) {
            browser.initOwner(deps.window().get());
        }
        browser.showAndWait();
        return browser;
    }

    private Optional<String> promptForName(String title, String header,
                                           String label, String initial) {
        TextInputDialog dialog = new TextInputDialog(initial == null ? "" : initial);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText(label);
        if (deps.window().get() != null) {
            dialog.initOwner(deps.window().get());
        }
        return dialog.showAndWait()
                .map(String::trim)
                .filter(s -> !s.isBlank());
    }
}
