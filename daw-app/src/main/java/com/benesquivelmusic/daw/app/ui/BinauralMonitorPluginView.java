package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.plugin.BinauralMonitorPlugin;
import com.benesquivelmusic.daw.core.spatial.binaural.HrtfImportController;
import com.benesquivelmusic.daw.core.spatial.binaural.HrtfProfileLibrary;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Callback;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * JavaFX view for the built-in {@link BinauralMonitorPlugin} (story 174).
 *
 * <p>Surfaces:</p>
 * <ul>
 *   <li>A wet-level slider mirroring the plugin's only tuneable parameter.</li>
 *   <li>A grouped HRTF-profile combo (factory profiles followed by the user's
 *       personalized imports) so the active profile can be switched without
 *       leaving the plugin window.</li>
 *   <li>"Import SOFA…" and "Manage Profiles…" buttons that open the
 *       {@link HrtfProfileImportDialog} and {@link HrtfProfileBrowserDialog}
 *       respectively.</li>
 * </ul>
 *
 * <p>The view does not itself wire the chosen profile into the binaural
 * processor — that is the host's responsibility (and is shared with the
 * project-level "active HRTF profile" persistence on {@code DawProject}).
 * A change listener can be registered via
 * {@link #setProfileSelectionListener(Consumer)} so the host can react.</p>
 */
public final class BinauralMonitorPluginView extends VBox {

    private final BinauralMonitorPlugin plugin;
    private final HrtfImportController controller;

    private final Slider wetSlider;
    private final ComboBox<HrtfProfileLibrary.ProfileEntry> profileCombo;
    private final Button importButton;
    private final Button manageButton;
    private Consumer<HrtfProfileLibrary.ProfileEntry> profileSelectionListener;

    /**
     * Creates a binaural monitor view bound to the given plugin and library.
     *
     * @param plugin            the binaural monitor plugin instance
     * @param library           profile library (factory + personalized profiles)
     * @param sessionSampleRate active session sample rate, in Hz, used for
     *                          SOFA imports launched from this view
     */
    public BinauralMonitorPluginView(BinauralMonitorPlugin plugin,
                                     HrtfProfileLibrary library,
                                     double sessionSampleRate) {
        this(Objects.requireNonNull(plugin, "plugin must not be null"),
                new HrtfImportController(
                        Objects.requireNonNull(library, "library must not be null"),
                        sessionSampleRate));
    }

    /** Test seam — accepts a pre-built controller. */
    BinauralMonitorPluginView(BinauralMonitorPlugin plugin, HrtfImportController controller) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.controller = Objects.requireNonNull(controller, "controller must not be null");

        setSpacing(10);
        setPadding(new Insets(12));
        setAlignment(Pos.TOP_LEFT);
        setStyle("-fx-background-color: #2b2b2b;");

        Label title = new Label("Binaural Monitor");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #eee;");

        // ── HRTF profile combo ────────────────────────────────────────────
        Label profileLabel = new Label("HRTF Profile");
        profileLabel.setStyle("-fx-text-fill: #ccc; -fx-font-size: 11px;");
        profileCombo = new ComboBox<>();
        profileCombo.setCellFactory(makeCellFactory());
        profileCombo.setButtonCell(makeCellFactory().call(null));
        profileCombo.setPrefWidth(220);
        refreshProfiles();
        profileCombo.valueProperty().addListener((_, _, entry) -> {
            if (entry != null && profileSelectionListener != null) {
                profileSelectionListener.accept(entry);
            }
        });

        importButton = new Button("Import SOFA…");
        importButton.setOnAction(_ -> onImport());

        manageButton = new Button("Manage…");
        manageButton.setOnAction(_ -> onManage());

        HBox profileRow = new HBox(8, profileCombo, importButton, manageButton);
        profileRow.setAlignment(Pos.CENTER_LEFT);

        // ── Wet-level slider (parameter 0 of the plugin) ──────────────────
        Label wetLabel = new Label("Wet Level");
        wetLabel.setStyle("-fx-text-fill: #ccc; -fx-font-size: 11px;");
        wetSlider = new Slider(0.0, 1.0, 0.5);
        wetSlider.setPrefWidth(220);
        // No public setter on the plugin — kept for visual feedback only;
        // the host should pick this up via PluginParameter wiring.

        getChildren().addAll(title, profileLabel, profileRow, wetLabel, wetSlider);
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Registers a listener invoked whenever the user picks a different
     * profile from the combo. Used by the host to update the active
     * binaural processor and persist the selection on {@code DawProject}.
     *
     * @param listener listener; pass {@code null} to clear
     */
    public void setProfileSelectionListener(Consumer<HrtfProfileLibrary.ProfileEntry> listener) {
        this.profileSelectionListener = listener;
    }

    /**
     * Programmatically selects the profile whose display name matches
     * {@code name}. Used both to honour the project-level active-profile
     * preference at load time and to react to fallbacks reported by
     * {@link HrtfImportController#resolve(String)}.
     *
     * @param name the desired profile display name; {@code null} clears
     * @return {@code true} if a matching entry was found
     */
    public boolean selectProfileByName(String name) {
        if (name == null) {
            profileCombo.getSelectionModel().clearSelection();
            return false;
        }
        for (HrtfProfileLibrary.ProfileEntry entry : profileCombo.getItems()) {
            if (entry.displayName().equals(name)) {
                profileCombo.getSelectionModel().select(entry);
                return true;
            }
        }
        return false;
    }

    /** Reloads the chooser items from the library. */
    public void refreshProfiles() {
        List<HrtfProfileLibrary.ProfileEntry> entries = controller.chooserEntries();
        ObservableList<HrtfProfileLibrary.ProfileEntry> obs = FXCollections.observableArrayList(entries);
        HrtfProfileLibrary.ProfileEntry previous = profileCombo.getValue();
        profileCombo.setItems(obs);
        if (previous != null) {
            for (HrtfProfileLibrary.ProfileEntry e : obs) {
                if (e.displayName().equals(previous.displayName())) {
                    profileCombo.getSelectionModel().select(e);
                    return;
                }
            }
        }
        if (!obs.isEmpty() && profileCombo.getValue() == null) {
            profileCombo.getSelectionModel().selectFirst();
        }
    }

    // ── Internal handlers ─────────────────────────────────────────────────

    private void onImport() {
        HrtfProfileImportDialog dialog =
                new HrtfProfileImportDialog(controller.library(), controller.sessionSampleRate());
        Optional<String> imported = dialog.showAndWait();
        refreshProfiles();
        imported.ifPresent(this::selectProfileByName);
    }

    private void onManage() {
        HrtfProfileBrowserDialog dialog =
                new HrtfProfileBrowserDialog(controller.library(), controller.sessionSampleRate());
        Optional<String> selected = dialog.showAndWait();
        refreshProfiles();
        selected.ifPresent(this::selectProfileByName);
    }

    private static Callback<javafx.scene.control.ListView<HrtfProfileLibrary.ProfileEntry>,
            ListCell<HrtfProfileLibrary.ProfileEntry>> makeCellFactory() {
        return _ -> new ListCell<>() {
            @Override
            protected void updateItem(HrtfProfileLibrary.ProfileEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                String prefix = item.kind() == HrtfProfileLibrary.Kind.GENERIC ? "[Factory] " : "[User] ";
                setText(prefix + item.displayName());
            }
        };
    }

    // ── Test accessors ────────────────────────────────────────────────────

    public BinauralMonitorPlugin getPlugin() { return plugin; }
    ComboBox<HrtfProfileLibrary.ProfileEntry> getProfileCombo() { return profileCombo; }
    Button getImportButton() { return importButton; }
    Button getManageButton() { return manageButton; }
    Slider getWetSlider() { return wetSlider; }
}
