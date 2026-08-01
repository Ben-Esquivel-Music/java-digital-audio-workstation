package com.benesquivelmusic.daw.app.ui.settings;

import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The §5.9 import/export "Settings profile" sheet — story 309, reachable
 * from General ▸ Reset &amp; profiles.
 *
 * <p>An <em>in-surface overlay</em>, never a nested modal (rejection #1):
 * a {@code StackPane} scrim over the settings shell following the
 * {@code PerformanceStageView} overlay precedent — a dedicated
 * {@code -surface-overlay} backdrop carries the dimming so the panel
 * above stays fully opaque; clicking the scrim (outside the panel)
 * dismisses. The owning {@code SettingsDialog} mounts/unmounts this node
 * in its content stack.</p>
 *
 * <p>Per-scope checkboxes select which {@link SettingsProfileIO.ProfileScope
 * profile scopes} to include (§5.9's checkbox list — Application /
 * Project defaults / This project / Audio device / Key Bindings). Export
 * snapshots the selected descriptors' current values ON the FX thread and
 * hands the immutable snapshot to {@link SettingsProfileIO}; import reads
 * the file off-thread and applies the validated values through the
 * dialog-supplied callback — which seeds the SHELL rows as pending edits
 * and drives the existing {@code applySettings()} path, so a bulk import
 * re-themes and re-arms exactly as manual edits do (the Control-Sync
 * cross-reference; no parallel apply path). Rejects render with the
 * severity tokens ({@code -warn} for validator rejects, {@code -danger}
 * for unknown/unparseable entries).</p>
 *
 * <p>The {@link FileChooser} is skipped when the sheet has no
 * scene/window (headless safety — the {@code SettingRowSkin} PATH-browse
 * precedent); tests drive {@link #exportTo(Path)} /
 * {@link #importFrom(Path)} directly.</p>
 */
public final class SettingsProfileSheet extends StackPane {

    /** Stable style class — selectable as {@code .settings-profile-sheet-overlay}. */
    public static final String DEFAULT_STYLE_CLASS = "settings-profile-sheet-overlay";

    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);

    private final SettingsCatalogue catalogue;
    private final Function<String, Object> currentValues;
    private final Consumer<Map<String, Object>> applyImported;
    private final Runnable onClose;
    private final SettingsProfileIO io;

    private final Map<SettingsProfileIO.ProfileScope, CheckBox> scopeChecks =
            new EnumMap<>(SettingsProfileIO.ProfileScope.class);
    private final Label progressLabel = new Label();
    private final Label statusLabel = new Label();
    private final VBox rejectsBox = new VBox();

    /**
     * Builds the sheet.
     *
     * @param catalogue     the story-305 catalogue; not null
     * @param currentValues current-value access per descriptor id (the
     *                      dialog's persisted read path); not null
     * @param applyImported dialog-side bulk apply: seeds shell rows as
     *                      pending edits then runs {@code applySettings()};
     *                      not null
     * @param onClose       unmounts the sheet from the dialog's content
     *                      stack; not null
     */
    public SettingsProfileSheet(SettingsCatalogue catalogue,
                                Function<String, Object> currentValues,
                                Consumer<Map<String, Object>> applyImported,
                                Runnable onClose) {
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue must not be null");
        this.currentValues = Objects.requireNonNull(currentValues,
                "currentValues must not be null");
        this.applyImported = Objects.requireNonNull(applyImported,
                "applyImported must not be null");
        this.onClose = Objects.requireNonNull(onClose, "onClose must not be null");
        this.io = new SettingsProfileIO(catalogue,
                this::onImported, this::onExported, this::onRunningChanged, this::onFailed);
        getStyleClass().add(DEFAULT_STYLE_CLASS);

        // PerformanceStageView precedent: the translucent backdrop is a
        // dedicated Region so the panel stays fully opaque.
        Region backdrop = new Region();
        backdrop.getStyleClass().add("settings-profile-sheet-backdrop");

        VBox panel = buildPanel();
        StackPane scrim = new StackPane(backdrop, panel);
        scrim.setOnMouseClicked(event -> {
            if (event.getTarget() == scrim || event.getTarget() == backdrop) {
                dismiss();
            }
        });
        getChildren().add(scrim);
    }

    private VBox buildPanel() {
        Label title = new Label(msg("settings.profile.title"));
        title.getStyleClass().add("settings-profile-sheet-title");

        Label scopesLabel = new Label(msg("settings.profile.scopes"));
        scopesLabel.getStyleClass().add("settings-profile-status");
        VBox scopes = new VBox(scopesLabel);
        scopes.getStyleClass().add("settings-profile-scopes");
        for (SettingsProfileIO.ProfileScope scope
                : SettingsProfileIO.ProfileScope.values()) {
            CheckBox check = new CheckBox(
                    msg("settings.profile.scope." + scope.tag()));
            check.setId("profile-scope-" + scope.tag());
            check.setSelected(true);
            scopeChecks.put(scope, check);
            scopes.getChildren().add(check);
        }

        Button exportButton = new Button(msg("settings.profile.export"));
        exportButton.getStyleClass().addAll("dawg-button", "size-default");
        exportButton.setId("settings-profile-export");
        exportButton.setOnAction(_ -> chooseAndExport());

        Button importButton = new Button(msg("settings.profile.import"));
        importButton.getStyleClass().addAll("dawg-button", "size-default");
        importButton.setId("settings-profile-import");
        importButton.setOnAction(_ -> chooseAndImport());

        Button closeButton = new Button(msg("settings.profile.close"));
        closeButton.getStyleClass().addAll("dawg-button", "size-default", "secondary");
        closeButton.setId("settings-profile-close");
        closeButton.setOnAction(_ -> dismiss());

        HBox actions = new HBox(exportButton, importButton, closeButton);
        actions.getStyleClass().add("settings-profile-actions");

        progressLabel.getStyleClass().add("settings-profile-progress");
        progressLabel.setText(msg("settings.profile.progress"));
        progressLabel.setManaged(false);
        progressLabel.setVisible(false);

        statusLabel.getStyleClass().add("settings-profile-status");
        statusLabel.setManaged(false);
        statusLabel.setVisible(false);
        statusLabel.setWrapText(true);

        rejectsBox.getStyleClass().add("settings-profile-rejects");

        VBox panel = new VBox(title, scopes, actions, progressLabel, statusLabel, rejectsBox);
        panel.getStyleClass().add("settings-profile-sheet");
        panel.setMaxWidth(Region.USE_PREF_SIZE);
        panel.setMaxHeight(Region.USE_PREF_SIZE);
        return panel;
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    /** @return the scopes whose checkboxes are currently ticked */
    Set<SettingsProfileIO.ProfileScope> selectedScopes() {
        EnumSet<SettingsProfileIO.ProfileScope> selected =
                EnumSet.noneOf(SettingsProfileIO.ProfileScope.class);
        scopeChecks.forEach((scope, check) -> {
            if (check.isSelected()) {
                selected.add(scope);
            }
        });
        return selected;
    }

    private void chooseAndExport() {
        if (getScene() == null || getScene().getWindow() == null) {
            return; // headless safety — no chooser without a window
        }
        FileChooser chooser = profileChooser();
        File file = chooser.showSaveDialog(getScene().getWindow());
        if (file != null) {
            exportTo(file.toPath());
        }
    }

    private void chooseAndImport() {
        if (getScene() == null || getScene().getWindow() == null) {
            return; // headless safety — no chooser without a window
        }
        FileChooser chooser = profileChooser();
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file != null) {
            importFrom(file.toPath());
        }
    }

    private static FileChooser profileChooser() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(msg("settings.profile.title"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                msg("settings.profile.fileType"), "*.dawgprofile", "*.txt"));
        return chooser;
    }

    /**
     * Exports the selected scopes to {@code file}. The values snapshot is
     * captured HERE on the FX thread; the worker only serialises + writes.
     */
    void exportTo(Path file) {
        clearReport();
        Set<SettingsProfileIO.ProfileScope> scopes = selectedScopes();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (SettingDescriptor<?> descriptor : catalogue.descriptors()) {
            if (scopes.contains(SettingsProfileIO.ProfileScope.of(descriptor))) {
                snapshot.put(descriptor.id(), currentValues.apply(descriptor.id()));
            }
        }
        io.startExport(file, scopes, snapshot);
    }

    /**
     * Imports the selected scopes from {@code file} (off-thread read).
     * Public within the unexported {@code ui.settings} package (the
     * module exports nothing, so this stays module-internal): the
     * FileChooser path routes here, and the {@code ..app.ui} dialog-level
     * tests drive the composed import-apply loop through it directly.
     *
     * @param file the profile file to read; not null
     */
    public void importFrom(Path file) {
        clearReport();
        io.startImport(file, selectedScopes());
    }

    // ── SettingsProfileIO callbacks (FX thread) ──────────────────────────────

    private void onRunningChanged(boolean running) {
        progressLabel.setManaged(running);
        progressLabel.setVisible(running);
    }

    private void onExported(Path file) {
        showStatus(MessageFormat.format(
                msg("settings.profile.exported"), file.getFileName()));
    }

    private void onImported(SettingsProfileIO.ImportOutcome outcome) {
        // Bulk apply FIRST (seed rows + applySettings — the existing
        // path), then report what was and was not applied.
        applyImported.accept(outcome.applied());
        showStatus(MessageFormat.format(
                msg("settings.profile.imported"), outcome.applied().size()));
        renderRejects(outcome);
    }

    private void onFailed(Throwable failure) {
        String message = failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName() : failure.getMessage();
        Label line = new Label(MessageFormat.format(
                msg("settings.profile.failed"), message));
        line.getStyleClass().add("settings-profile-reject-danger");
        line.setWrapText(true);
        rejectsBox.getChildren().setAll(line);
    }

    private void renderRejects(SettingsProfileIO.ImportOutcome outcome) {
        rejectsBox.getChildren().clear();
        if (outcome.rejects().isEmpty()) {
            return;
        }
        Label header = new Label(msg("settings.profile.rejects.header"));
        header.getStyleClass().add("settings-profile-rejects-header");
        rejectsBox.getChildren().add(header);
        for (SettingsProfileIO.Reject reject : outcome.rejects()) {
            Label line = new Label(MessageFormat.format(
                    msg("settings.profile.reject." + reasonKey(reject.kind())),
                    reject.key(), reject.value()));
            // Severity tokens (§2.6): a validator reject is a -warn (the
            // value was understood but out of range); an unknown or
            // unparseable entry is a -danger (the profile is damaged).
            line.getStyleClass().add(
                    reject.kind() == SettingsProfileIO.RejectKind.REJECTED_BY_VALIDATOR
                            ? "settings-profile-reject-warn"
                            : "settings-profile-reject-danger");
            line.setWrapText(true);
            rejectsBox.getChildren().add(line);
        }
    }

    private static String reasonKey(SettingsProfileIO.RejectKind kind) {
        return switch (kind) {
            case MALFORMED_LINE -> "malformed";
            case UNKNOWN_SETTING -> "unknown";
            case UNPARSEABLE_VALUE -> "unparseable";
            case REJECTED_BY_VALIDATOR -> "invalid";
        };
    }

    private void showStatus(String text) {
        statusLabel.setText(text);
        statusLabel.setManaged(true);
        statusLabel.setVisible(true);
    }

    private void clearReport() {
        statusLabel.setManaged(false);
        statusLabel.setVisible(false);
        statusLabel.setText("");
        rejectsBox.getChildren().clear();
    }

    private void dismiss() {
        // §2.7 — closing the sheet cancels its in-flight I/O regardless
        // of how the owner unmounts; disposing again there is a no-op.
        dispose();
        onClose.run();
    }

    /**
     * Tears down in-flight I/O (§2.7 — closing the surface cancels the
     * work; a late result is discarded, never applied). Called by
     * {@link #dismiss()} and by the owning dialog when the sheet unmounts
     * or the dialog hides — idempotent, so both may run.
     */
    public void dispose() {
        io.close();
    }

    private static String msg(String key) {
        try {
            return MESSAGES.getString(key);
        } catch (MissingResourceException missing) {
            return key;
        }
    }
}
