package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.app.ui.settings.RecordingSettingsAccess;
import com.benesquivelmusic.daw.core.mixer.CueBus;
import com.benesquivelmusic.daw.core.mixer.CueBusManager;
import com.benesquivelmusic.daw.core.recording.ClickSound;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.core.recording.MetronomeSettingsStore;
import com.benesquivelmusic.daw.core.recording.MetronomeSideOutputRouter;
import com.benesquivelmusic.daw.core.recording.Subdivision;
import com.benesquivelmusic.daw.sdk.transport.ClickOutput;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

/**
 * Manages the metronome toggle button, right-click configuration context menu,
 * and preferences persistence for metronome settings.
 *
 * <p>Extracted from {@link MainController} following the same pattern as
 * {@link TransportController} — all dependencies are received via constructor
 * injection.</p>
 */
final class MetronomeController {

    private static final Logger LOG = Logger.getLogger(MetronomeController.class.getName());

    private static final String PREF_ENABLED = "metronome.enabled";
    private static final String PREF_VOLUME = "metronome.volume";
    private static final String PREF_CLICK_SOUND = "metronome.clickSound";
    private static final String PREF_SUBDIVISION = "metronome.subdivision";
    private static final String PREF_COUNT_IN = "metronome.countIn";

    private static final String ACTIVE_STYLE =
            "-fx-background-color: #b388ff; -fx-text-fill: #0d0d0d;";

    private final Metronome metronome;
    private final Button metronomeButton;
    private final NotificationBar notificationBar;
    private final Label statusBarLabel;
    private final Preferences prefs;
    private final MetronomeSettingsStore settingsStore;
    /** Story 135 — supplier of the live {@link CueBusManager} so the
     *  Recording settings category can list cue buses without a hard
     *  reference to a specific
     *  {@link com.benesquivelmusic.daw.core.project.DawProject}.
     *  May be {@code null} when no project is wired (tests). */
    private final java.util.function.Supplier<CueBusManager> cueBusManagerSupplier;
    /** Story 135 — supplier of the active side-output router. The router is
     *  owned by the audio engine; routing the click to a cue bus is achieved
     *  by setting a per-bus level on it. May be {@code null}. */
    private final java.util.function.Supplier<MetronomeSideOutputRouter> sideOutputRouterSupplier;

    /**
     * Story 308 — the "Click Routing…" context item deep-links into the
     * Settings surface's Recording category instead of opening the
     * deleted {@code MetronomeSettingsDialog}. Injected by the host;
     * {@code null} (unset — bare test wirings) makes the item a no-op.
     */
    private Runnable clickRoutingSettingsOpener;

    private CountInMode countInMode;

    /**
     * Creates a controller without a global settings store — convenience
     * overload used by older call-sites and simple tests that do not
     * exercise the story-136 {@link ClickOutput} persistence path.
     */
    MetronomeController(Metronome metronome,
                        Button metronomeButton,
                        NotificationBar notificationBar,
                        Label statusBarLabel,
                        Preferences prefs) {
        this(metronome, metronomeButton, notificationBar, statusBarLabel, prefs, null);
    }

    /**
     * Backward-compatible 6-arg constructor — no cue-bus or side-output
     * router wiring. Story-135 cue-routing in the Recording settings
     * category is suppressed when both suppliers are absent.
     */
    MetronomeController(Metronome metronome,
                        Button metronomeButton,
                        NotificationBar notificationBar,
                        Label statusBarLabel,
                        Preferences prefs,
                        MetronomeSettingsStore settingsStore) {
        this(metronome, metronomeButton, notificationBar, statusBarLabel, prefs,
                settingsStore, null, null);
    }

    /**
     * Creates a fully-wired controller.
     *
     * @param metronome       the metronome instance this controller owns
     * @param metronomeButton the toolbar toggle button
     * @param notificationBar transient toast/notification surface
     * @param statusBarLabel  status-bar label used for latched status text
     * @param prefs           backing {@link Preferences} for non-routing state
     *                        (enabled, volume, click sound, subdivision, count-in)
     * @param settingsStore   optional global-default store that persists
     *                        {@link ClickOutput} across sessions to
     *                        {@code ~/.daw/metronome-settings.json}; {@code null}
     *                        skips global persistence (used by tests)
     * @param cueBusManagerSupplier  story-135 supplier of the live cue-bus
     *                               manager; {@code null} collapses the
     *                               Recording category's "Send click to"
     *                               options to "Main mix only"
     * @param sideOutputRouterSupplier  story-135 supplier of the active
     *                                  side-output router used to apply the
     *                                  cue-bus selection; {@code null} skips
     *                                  cue routing entirely
     */
    MetronomeController(Metronome metronome,
                        Button metronomeButton,
                        NotificationBar notificationBar,
                        Label statusBarLabel,
                        Preferences prefs,
                        MetronomeSettingsStore settingsStore,
                        java.util.function.Supplier<CueBusManager> cueBusManagerSupplier,
                        java.util.function.Supplier<MetronomeSideOutputRouter> sideOutputRouterSupplier) {
        this.metronome = Objects.requireNonNull(metronome, "metronome must not be null");
        this.metronomeButton = Objects.requireNonNull(metronomeButton, "metronomeButton must not be null");
        this.notificationBar = Objects.requireNonNull(notificationBar, "notificationBar must not be null");
        this.statusBarLabel = Objects.requireNonNull(statusBarLabel, "statusBarLabel must not be null");
        this.prefs = Objects.requireNonNull(prefs, "prefs must not be null");
        this.settingsStore = settingsStore;
        this.cueBusManagerSupplier = cueBusManagerSupplier;
        this.sideOutputRouterSupplier = sideOutputRouterSupplier;
        this.countInMode = CountInMode.OFF;
        loadPreferences();
        loadGlobalSettings();
        updateButtonStyle();
        installContextMenu();
    }

    /**
     * Toggles the metronome enabled state, updates the button style,
     * shows a notification, and persists the change.
     */
    void onToggleMetronome() {
        boolean newState = !metronome.isEnabled();
        metronome.setEnabled(newState);
        syncEnabledVisuals();
        prefs.putBoolean(PREF_ENABLED, newState);
    }

    /**
     * The one enabled-state visual sync (button style + status text +
     * toast) — shared by the toolbar toggle and the story-308
     * settings-driven apply so the two paths cannot drift.
     */
    private void syncEnabledVisuals() {
        updateButtonStyle();
        String message = metronome.isEnabled() ? "Metronome: ON" : "Metronome: OFF";
        statusBarLabel.setText(message);
        statusBarLabel.setGraphic(IconNode.of(DawIcon.METRONOME, 12));
        notificationBar.show(NotificationLevel.INFO, message);
        LOG.fine(message);
    }

    /**
     * Story 308 — injects the deep link the "Click Routing…" context
     * item runs (opens Settings focused on the Recording category).
     *
     * @param opener the deep-link action; {@code null} leaves the item
     *               a documented no-op (test wirings without a host)
     */
    void setClickRoutingSettingsOpener(Runnable opener) {
        this.clickRoutingSettingsOpener = opener;
    }

    /**
     * Story 308 — the Recording access seam the Settings surface reads
     * and applies through. Reads come from the live metronome /
     * controller state / router; {@link RecordingSettingsAccess#apply}
     * performs exactly the old dialog apply plus the context-menu
     * enabled/count-in writes.
     */
    RecordingSettingsAccess recordingSettingsAccess() {
        return new RecordingSettingsAccess() {
            @Override
            public Snapshot current() {
                return new Snapshot(metronome.isEnabled(), countInMode,
                        metronome.getClickOutput(), currentCueBusId());
            }

            @Override
            public java.util.List<CueBus> cueBuses() {
                CueBusManager manager = cueBusManagerSupplier == null
                        ? null : cueBusManagerSupplier.get();
                return manager == null ? java.util.List.of() : manager.getCueBuses();
            }

            @Override
            public void apply(Snapshot snapshot) {
                applyRecordingSettings(snapshot);
            }
        };
    }

    /**
     * The click's currently selected cue bus, reconstructed from the
     * router's level map ("first non-zero level wins" — the router is
     * the authoritative model; no stored id exists anywhere).
     */
    private java.util.Optional<java.util.UUID> currentCueBusId() {
        MetronomeSideOutputRouter router = sideOutputRouterSupplier == null
                ? null : sideOutputRouterSupplier.get();
        if (router == null) {
            return java.util.Optional.empty();
        }
        for (java.util.Map.Entry<java.util.UUID, Double> entry :
                router.cueBusLevels().entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0.0) {
                return java.util.Optional.of(entry.getKey());
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Applies a Settings-surface snapshot through exactly the absorbed
     * dialog's authorities: metronome setters, prefs for enabled /
     * count-in, single-select router cue level (clear first so a
     * previously selected bus is silenced), then the global JSON save.
     */
    private void applyRecordingSettings(RecordingSettingsAccess.Snapshot snapshot) {
        boolean enabledChanged = metronome.isEnabled() != snapshot.enabled();
        metronome.setEnabled(snapshot.enabled());
        prefs.putBoolean(PREF_ENABLED, snapshot.enabled());
        if (enabledChanged) {
            syncEnabledVisuals(); // the onToggleMetronome visual path
        }
        countInMode = snapshot.countIn();
        prefs.put(PREF_COUNT_IN, snapshot.countIn().name());
        metronome.setClickOutput(snapshot.clickOutput());
        MetronomeSideOutputRouter router = sideOutputRouterSupplier == null
                ? null : sideOutputRouterSupplier.get();
        if (router != null) {
            router.clearCueBusLevels();
            snapshot.cueBusId().ifPresent(id -> router.setCueBusLevel(id, 1.0));
        }
        saveGlobalSettings();
        LOG.fine("Metronome settings applied: " + snapshot.clickOutput()
                + " cueBusId=" + snapshot.cueBusId().orElse(null));
    }

    /**
     * Returns the current count-in mode.
     *
     * @return the count-in mode
     */
    CountInMode getCountInMode() {
        return countInMode;
    }

    /**
     * Returns the backing metronome instance.
     *
     * @return the metronome
     */
    Metronome getMetronome() {
        return metronome;
    }

    private void updateButtonStyle() {
        metronomeButton.setStyle(metronome.isEnabled() ? ACTIVE_STYLE : "");
    }

    private void loadPreferences() {
        boolean enabled = prefs.getBoolean(PREF_ENABLED, true);
        metronome.setEnabled(enabled);

        float volume = prefs.getFloat(PREF_VOLUME, 1.0f);
        metronome.setVolume(Math.max(0.0f, Math.min(1.0f, volume)));

        String clickSoundName = prefs.get(PREF_CLICK_SOUND, ClickSound.WOODBLOCK.name());
        try {
            metronome.setClickSound(ClickSound.valueOf(clickSoundName));
        } catch (IllegalArgumentException e) {
            metronome.setClickSound(ClickSound.WOODBLOCK);
        }

        String subdivisionName = prefs.get(PREF_SUBDIVISION, Subdivision.QUARTER.name());
        try {
            metronome.setSubdivision(Subdivision.valueOf(subdivisionName));
        } catch (IllegalArgumentException e) {
            metronome.setSubdivision(Subdivision.QUARTER);
        }

        String countInName = prefs.get(PREF_COUNT_IN, CountInMode.OFF.name());
        try {
            countInMode = CountInMode.valueOf(countInName);
        } catch (IllegalArgumentException e) {
            countInMode = CountInMode.OFF;
        }
    }

    /**
     * Loads the global metronome defaults — including {@link ClickOutput}
     * routing — from {@code ~/.daw/metronome-settings.json} and applies them
     * to the metronome. Per-project settings loaded later (via
     * {@link com.benesquivelmusic.daw.core.persistence.ProjectDeserializer})
     * take precedence and are expected to overwrite these defaults.
     *
     * <p>Silently ignores a missing or corrupt file — the caller falls back
     * to {@link Metronome}'s own code-level defaults.</p>
     */
    private void loadGlobalSettings() {
        if (settingsStore == null) {
            return;
        }
        settingsStore.load().ifPresent(settings -> {
            metronome.setClickOutput(settings.clickOutput());
        });
    }

    /**
     * Persists the current metronome state — enabled, volume, click sound,
     * subdivision, and {@link ClickOutput} routing — to the global settings
     * store, if one is wired.
     */
    private void saveGlobalSettings() {
        if (settingsStore == null) {
            return;
        }
        try {
            settingsStore.save(new MetronomeSettingsStore.Settings(
                    metronome.isEnabled(),
                    metronome.getVolume(),
                    metronome.getClickSound(),
                    metronome.getSubdivision(),
                    metronome.getClickOutput()));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to save metronome settings to "
                    + settingsStore.file(), e);
        }
    }

    private void installContextMenu() {
        metronomeButton.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
                // Story 308 — rebuilt on every show so the radio/check
                // state reflects the live model even after the Settings
                // surface (not this menu) changed count-in or enabled.
                ContextMenu contextMenu = buildContextMenu();
                contextMenu.show(metronomeButton, event.getScreenX(), event.getScreenY());
                event.consume();
            }
        });
    }

    private ContextMenu buildContextMenu() {
        ContextMenu menu = new ContextMenu();

        // ── Click Sound submenu ─────────────────────────────────────────────
        Menu clickSoundMenu = new Menu("Click Sound");
        ToggleGroup clickSoundGroup = new ToggleGroup();
        for (ClickSound sound : ClickSound.values()) {
            RadioMenuItem item = new RadioMenuItem(formatEnumName(sound.name()));
            item.setToggleGroup(clickSoundGroup);
            item.setSelected(metronome.getClickSound() == sound);
            item.setOnAction(_ -> {
                metronome.setClickSound(sound);
                prefs.put(PREF_CLICK_SOUND, sound.name());
                LOG.fine("Metronome click sound: " + sound.name());
            });
            clickSoundMenu.getItems().add(item);
        }

        // ── Volume slider ───────────────────────────────────────────────────
        Slider volumeSlider = new Slider(0, 100, metronome.getVolume() * 100);
        volumeSlider.setShowTickLabels(true);
        volumeSlider.setShowTickMarks(true);
        volumeSlider.setMajorTickUnit(25);
        volumeSlider.setPrefWidth(150);
        Label volumeLabel = new Label(String.format("Volume: %.0f%%", metronome.getVolume() * 100));
        volumeLabel.getStyleClass().add("numeric-value");
        volumeSlider.valueProperty().addListener((_, _, newVal) -> {
            float vol = newVal.floatValue() / 100.0f;
            metronome.setVolume(vol);
            prefs.putFloat(PREF_VOLUME, vol);
            volumeLabel.setText(String.format("Volume: %.0f%%", newVal.doubleValue()));
        });
        HBox volumeBox = new HBox(8, volumeLabel, volumeSlider);
        CustomMenuItem volumeItem = new CustomMenuItem(volumeBox, false);

        // ── Subdivision submenu ─────────────────────────────────────────────
        Menu subdivisionMenu = new Menu("Subdivision");
        ToggleGroup subdivisionGroup = new ToggleGroup();
        for (Subdivision sub : Subdivision.values()) {
            RadioMenuItem item = new RadioMenuItem(formatEnumName(sub.name()));
            item.setToggleGroup(subdivisionGroup);
            item.setSelected(metronome.getSubdivision() == sub);
            item.setOnAction(_ -> {
                metronome.setSubdivision(sub);
                prefs.put(PREF_SUBDIVISION, sub.name());
                LOG.fine("Metronome subdivision: " + sub.name());
            });
            subdivisionMenu.getItems().add(item);
        }

        // ── Count-in submenu ────────────────────────────────────────────────
        Menu countInMenu = new Menu("Count-In");
        ToggleGroup countInGroup = new ToggleGroup();
        for (CountInMode mode : CountInMode.values()) {
            RadioMenuItem item = new RadioMenuItem(formatCountInName(mode));
            item.setToggleGroup(countInGroup);
            item.setSelected(countInMode == mode);
            item.setOnAction(_ -> {
                countInMode = mode;
                prefs.put(PREF_COUNT_IN, mode.name());
                LOG.fine("Metronome count-in: " + mode.name());
            });
            countInMenu.getItems().add(item);
        }

        // ── Click routing (story 136) ───────────────────────────────────────
        MenuItem routingItem = new MenuItem("Click Routing\u2026");
        routingItem.setGraphic(IconNode.of(DawIcon.METRONOME, 12));
        // Story 308 — the dialog is absorbed; the item deep-links into
        // Settings ▸ Recording through the injected opener (§7 Stage 4).
        routingItem.setOnAction(_ -> {
            Runnable opener = clickRoutingSettingsOpener;
            if (opener != null) {
                opener.run();
            }
        });

        menu.getItems().addAll(clickSoundMenu, volumeItem, subdivisionMenu,
                countInMenu, new SeparatorMenuItem(), routingItem);
        return menu;
    }

    private static String formatEnumName(String name) {
        String lower = name.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String formatCountInName(CountInMode mode) {
        return switch (mode) {
            case OFF -> "Off";
            case ONE_BAR -> "One Bar";
            case TWO_BARS -> "Two Bars";
            case FOUR_BARS -> "Four Bars";
        };
    }
}
