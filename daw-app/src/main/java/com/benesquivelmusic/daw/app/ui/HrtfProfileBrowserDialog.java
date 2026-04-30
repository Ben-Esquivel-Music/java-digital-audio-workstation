package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.spatial.binaural.HrtfImportController;
import com.benesquivelmusic.daw.core.spatial.binaural.HrtfProfileLibrary;
import com.benesquivelmusic.daw.sdk.spatial.HrtfProfile;
import com.benesquivelmusic.daw.sdk.spatial.PersonalizedHrtfProfile;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * "Manage HRTF Profiles…" modal dialog (story 174).
 *
 * <p>Lists factory profiles (read-only) and the user's imported personalized
 * profiles together. The user can:</p>
 * <ul>
 *   <li>Trigger a fresh "Import SOFA…" via the embedded button.</li>
 *   <li>Preview the coverage of any selected profile.</li>
 *   <li>Delete an imported profile (factory profiles are read-only).</li>
 * </ul>
 *
 * <p>The dialog returns the display name of the profile selected at close
 * time — typically the profile the user wants the binaural plugin to switch
 * to.</p>
 */
public final class HrtfProfileBrowserDialog extends Dialog<String> {

    private final HrtfImportController controller;
    private final ListView<HrtfProfileLibrary.ProfileEntry> entryList;
    private final Button deleteButton;
    private final Button importButton;
    private final Label statusLabel;
    private final HrtfProfileImportDialog.CoveragePreview coveragePreview;

    /**
     * Creates a browser dialog backed by the given library and session.
     *
     * @param library           the HRTF profile library
     * @param sessionSampleRate the active session sample rate, for any
     *                          re-imports performed from inside this dialog
     */
    public HrtfProfileBrowserDialog(HrtfProfileLibrary library, double sessionSampleRate) {
        this(new HrtfImportController(
                Objects.requireNonNull(library, "library must not be null"),
                sessionSampleRate));
    }

    /** Test seam — accepts a pre-built controller. */
    HrtfProfileBrowserDialog(HrtfImportController controller) {
        this.controller = Objects.requireNonNull(controller, "controller must not be null");

        setTitle("Manage HRTF Profiles");
        setHeaderText("Factory profiles are read-only. Imported profiles can be deleted.");

        entryList = new ListView<>();
        entryList.setCellFactory(_ -> new ProfileEntryCell());
        entryList.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        entryList.setPrefHeight(220);
        entryList.setPrefWidth(260);

        importButton = new Button("Import SOFA…");
        importButton.setOnAction(_ -> onImport());

        deleteButton = new Button("Delete");
        deleteButton.setDisable(true);
        deleteButton.setOnAction(_ -> onDelete());

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #bbb; -fx-font-size: 11px;");
        statusLabel.setWrapText(true);

        coveragePreview = new HrtfProfileImportDialog.CoveragePreview(260, 200);

        entryList.getSelectionModel().selectedItemProperty().addListener(
                (_, _, entry) -> onSelectionChanged(entry));

        HBox buttonRow = new HBox(8, importButton, deleteButton);
        VBox left = new VBox(8, entryList, buttonRow, statusLabel);
        VBox right = new VBox(8, new Label("Coverage preview"), coveragePreview);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox content = new HBox(12, left, right);
        content.setPadding(new Insets(12));
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);

        setResultConverter(_ -> {
            HrtfProfileLibrary.ProfileEntry selected = entryList.getSelectionModel().getSelectedItem();
            return selected != null ? selected.displayName() : null;
        });

        DarkThemeHelper.applyTo(this);
        refresh();
    }

    /** Reloads the profile list from the library. */
    public void refresh() {
        ObservableList<HrtfProfileLibrary.ProfileEntry> items =
                FXCollections.observableArrayList(controller.chooserEntries());
        entryList.setItems(items);
        coveragePreview.clear();
    }

    private void onSelectionChanged(HrtfProfileLibrary.ProfileEntry entry) {
        if (entry == null) {
            deleteButton.setDisable(true);
            statusLabel.setText("");
            coveragePreview.clear();
            return;
        }
        deleteButton.setDisable(entry.kind() != HrtfProfileLibrary.Kind.PERSONALIZED);

        if (entry.kind() == HrtfProfileLibrary.Kind.GENERIC) {
            HrtfProfile p = entry.generic();
            statusLabel.setText(String.format(
                    "Factory profile — head circumference ≈ %.1f cm.", p.headCircumferenceCm()));
            coveragePreview.clear();
        } else {
            try {
                Optional<PersonalizedHrtfProfile> loaded =
                        controller.library().loadImportedProfile(entry.personalizedName());
                if (loaded.isPresent()) {
                    PersonalizedHrtfProfile profile = loaded.get();
                    statusLabel.setText(String.format(
                            "Personalized — %d measurements at %.0f Hz.",
                            profile.measurementCount(), profile.sampleRate()));
                    coveragePreview.render(profile);
                } else {
                    statusLabel.setText("Personalized profile is no longer on disk.");
                    coveragePreview.clear();
                }
            } catch (IOException ex) {
                statusLabel.setText("Failed to load profile: " + ex.getMessage());
                coveragePreview.clear();
            }
        }
    }

    private void onImport() {
        HrtfProfileImportDialog dialog =
                new HrtfProfileImportDialog(controller.library(), controller.sessionSampleRate());
        Optional<String> result = dialog.showAndWait();
        if (result.isPresent()) {
            refresh();
            // Auto-select the freshly imported profile.
            for (HrtfProfileLibrary.ProfileEntry entry : entryList.getItems()) {
                if (entry.kind() == HrtfProfileLibrary.Kind.PERSONALIZED
                        && result.get().equals(entry.personalizedName())) {
                    entryList.getSelectionModel().select(entry);
                    break;
                }
            }
        }
    }

    private void onDelete() {
        HrtfProfileLibrary.ProfileEntry entry = entryList.getSelectionModel().getSelectedItem();
        if (entry == null || entry.kind() != HrtfProfileLibrary.Kind.PERSONALIZED) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete personalized HRTF profile \"" + entry.personalizedName() + "\"?",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        Optional<ButtonType> response = confirm.showAndWait();
        if (response.isPresent() && response.get() == ButtonType.OK) {
            try {
                controller.library().deleteImportedProfile(entry.personalizedName());
            } catch (IOException ex) {
                statusLabel.setText("Failed to delete: " + ex.getMessage());
                return;
            }
            refresh();
        }
    }

    /**
     * Programmatically remove an imported profile by name — visible to keep
     * tests independent of an Alert confirmation dialog.
     */
    boolean deleteWithoutConfirm(String name) {
        try {
            boolean deleted = controller.library().deleteImportedProfile(name);
            refresh();
            return deleted;
        } catch (IOException ex) {
            statusLabel.setText("Failed to delete: " + ex.getMessage());
            return false;
        }
    }

    /** Test accessor. */
    ListView<HrtfProfileLibrary.ProfileEntry> getEntryList() { return entryList; }

    /** Test accessor. */
    Button getDeleteButton() { return deleteButton; }

    /** Test accessor. */
    Button getImportButton() { return importButton; }

    /** Test accessor. */
    Label getStatusLabel() { return statusLabel; }

    /** Renders profile entries with a kind prefix so factory vs imported is obvious. */
    private static final class ProfileEntryCell extends ListCell<HrtfProfileLibrary.ProfileEntry> {
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
    }

    /**
     * Static helper for unit tests: returns the chooser entries grouped as
     * the dialog renders them — factory first, then personalized.
     */
    static List<HrtfProfileLibrary.ProfileEntry> chooserEntriesFor(HrtfProfileLibrary library) {
        return library.chooserEntries();
    }
}
