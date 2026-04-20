package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.recording.ClickSound;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.core.recording.MetronomeSettingsStore;
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
     */
    MetronomeController(Metronome metronome,
                        Button metronomeButton,
                        NotificationBar notificationBar,
                        Label statusBarLabel,
                        Preferences prefs,
                        MetronomeSettingsStore settingsStore) {
        this.metronome = Objects.requireNonNull(metronome, "metronome must not be null");
        this.metronomeButton = Objects.requireNonNull(metronomeButton, "metronomeButton must not be null");
        this.notificationBar = Objects.requireNonNull(notificationBar, "notificationBar must not be null");
        this.statusBarLabel = Objects.requireNonNull(statusBarLabel, "statusBarLabel must not be null");
        this.prefs = Objects.requireNonNull(prefs, "prefs must not be null");
        this.settingsStore = settingsStore;
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
        updateButtonStyle();
        String message = newState ? "Metronome: ON" : "Metronome: OFF";
        statusBarLabel.setText(message);
        statusBarLabel.setGraphic(IconNode.of(DawIcon.METRONOME, 12));
        notificationBar.show(NotificationLevel.INFO, message);
        prefs.putBoolean(PREF_ENABLED, newState);
        LOG.fine(message);
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
        ContextMenu contextMenu = buildContextMenu();
        metronomeButton.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
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
        routingItem.setOnAction(_ -> openClickRoutingDialog());

        menu.getItems().addAll(clickSoundMenu, volumeItem, subdivisionMenu,
                countInMenu, new SeparatorMenuItem(), routingItem);
        return menu;
    }

    /**
     * Opens the {@link MetronomeSettingsDialog} pre-populated with the
     * metronome's current {@link ClickOutput}. On Apply, the new routing is
     * written back to the metronome and persisted to the global store so
     * future sessions inherit it.
     */
    private void openClickRoutingDialog() {
        MetronomeSettingsDialog dialog = new MetronomeSettingsDialog(
                metronome.getClickOutput());
        dialog.showAndWait().ifPresent(updated -> {
            metronome.setClickOutput(updated);
            saveGlobalSettings();
            LOG.fine("Metronome click routing updated: " + updated);
        });
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
