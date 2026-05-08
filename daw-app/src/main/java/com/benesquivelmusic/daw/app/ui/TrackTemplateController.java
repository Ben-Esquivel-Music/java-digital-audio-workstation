package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.template.AddTrackFromTemplateAction;
import com.benesquivelmusic.daw.core.template.ApplyChannelStripPresetAction;
import com.benesquivelmusic.daw.core.template.ChannelStripPreset;
import com.benesquivelmusic.daw.core.template.TrackTemplate;
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
     * Callback host the controller queries to obtain the live project, undo
     * manager, parent window, and to surface notifications to the user.
     *
     * <p>Defined as an interface so {@link MainController} can wire the
     * controller into existing collaborators without coupling this class to
     * any particular field layout.</p>
     */
    public interface Host {
        DawProject project();
        UndoManager undoManager();
        /** Returns the window to use as the parent for modal dialogs, or {@code null}. */
        Window window();
        void showNotification(NotificationLevel level, String message);
        /** Refresh the mixer view after an apply / instantiate that changes the strip. */
        void refreshMixer();
    }

    private final Host host;
    private final Supplier<TrackTemplateStore> storeSupplier;

    /**
     * Creates a controller backed by the user's default template store
     * (under {@code ~/.daw}).
     *
     * @param host the host providing project, undo manager, and window
     */
    public TrackTemplateController(Host host) {
        this(host, TrackTemplateStore::new);
    }

    /**
     * Creates a controller backed by a custom {@link TrackTemplateStore}
     * supplier — used by tests to point persistence at a temp directory.
     *
     * @param host          the host providing project, undo manager, and window
     * @param storeSupplier supplier of the disk-backed store
     */
    public TrackTemplateController(Host host, Supplier<TrackTemplateStore> storeSupplier) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.storeSupplier = Objects.requireNonNull(storeSupplier, "storeSupplier must not be null");
    }

    /** Returns the underlying store (loaded lazily). */
    public TrackTemplateStore store() {
        return storeSupplier.get();
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
                    name.get(), track, host.project());
            store().saveTemplate(template);
            host.showNotification(NotificationLevel.SUCCESS,
                    "Saved track template: " + template.templateName());
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to save track template", e);
            host.showNotification(NotificationLevel.ERROR,
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
                "Save the insert chain and sends on \u201C" + channel.getName()
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
            host.showNotification(NotificationLevel.SUCCESS,
                    "Saved channel strip preset: " + preset.presetName());
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to save channel strip preset", e);
            host.showNotification(NotificationLevel.ERROR,
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
        TrackTemplateBrowser browser = openBrowser(TrackTemplateBrowser.InitialTab.TEMPLATES);
        TrackTemplate template = browser.getSelectedTemplate();
        if (template == null) {
            return;
        }
        try {
            AddTrackFromTemplateAction action = new AddTrackFromTemplateAction(
                    host.project(), template);
            host.undoManager().execute(action);
            host.refreshMixer();
            host.showNotification(NotificationLevel.SUCCESS,
                    "Added track from template: " + template.templateName());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to instantiate template", e);
            host.showNotification(NotificationLevel.ERROR,
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
        TrackTemplateBrowser browser = openBrowser(TrackTemplateBrowser.InitialTab.PRESETS);
        ChannelStripPreset preset = browser.getSelectedPreset();
        if (preset == null) {
            return;
        }
        if (channel.getInsertCount() > 0 || !channel.getSends().isEmpty()) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Replace the existing inserts and sends on \u201C"
                            + channel.getName() + "\u201D with the preset \u201C"
                            + preset.presetName() + "\u201D?",
                    ButtonType.OK, ButtonType.CANCEL);
            confirm.setHeaderText("Apply Channel Strip Preset");
            if (host.window() != null) {
                confirm.initOwner(host.window());
            }
            Optional<ButtonType> response = confirm.showAndWait();
            if (response.isEmpty() || response.get() != ButtonType.OK) {
                return;
            }
        }
        try {
            Mixer mixer = host.project().getMixer();
            AudioFormat format = host.project().getFormat();
            ApplyChannelStripPresetAction action = new ApplyChannelStripPresetAction(
                    channel, preset, mixer, format);
            host.undoManager().execute(action);
            host.refreshMixer();
            host.showNotification(NotificationLevel.SUCCESS,
                    "Applied preset: " + preset.presetName()
                            + " \u2192 " + channel.getName());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to apply channel strip preset", e);
            host.showNotification(NotificationLevel.ERROR,
                    "Failed to apply preset: " + e.getMessage());
        }
    }

    /**
     * Opens the manage-templates browser (both tabs visible, Import / Export
     * available). Returns once the user closes the dialog; nothing else
     * happens — selection is informational.
     */
    public void openManager() {
        openBrowser(TrackTemplateBrowser.InitialTab.TEMPLATES);
    }

    // ── Loading helpers (also used by the browser) ──────────────────────────

    /** Returns the union of factory + user templates, never {@code null}. */
    public List<TrackTemplate> loadAllTemplates() {
        try {
            return store().allTemplates();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to load templates", e);
            host.showNotification(NotificationLevel.WARNING,
                    "Failed to load templates: " + e.getMessage());
            return List.of();
        }
    }

    /** Returns the union of factory + user presets, never {@code null}. */
    public List<ChannelStripPreset> loadAllPresets() {
        try {
            return store().allPresets();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to load presets", e);
            host.showNotification(NotificationLevel.WARNING,
                    "Failed to load presets: " + e.getMessage());
            return List.of();
        }
    }

    // ── internals ───────────────────────────────────────────────────────────

    private TrackTemplateBrowser openBrowser(TrackTemplateBrowser.InitialTab initialTab) {
        TrackTemplateBrowser browser = new TrackTemplateBrowser(this, initialTab);
        if (host.window() != null) {
            browser.initOwner(host.window());
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
        if (host.window() != null) {
            dialog.initOwner(host.window());
        }
        return dialog.showAndWait()
                .map(String::trim)
                .filter(s -> !s.isBlank());
    }
}
