package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationReport;
import com.benesquivelmusic.daw.core.persistence.migration.ProjectMigration;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.event.EventTarget;
import javafx.event.EventType;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scrollable project migration history, backed by sibling
 * {@code project.daw.v<n>.<timestamp>.bak} files.
 *
 * <p>The view is intentionally standalone: callers can scan with
 * {@link #scanProjectDirectory(Path)} off the FX thread, then hand the entries
 * to {@link #setEntries(List)}. The convenience
 * {@link #refreshFromProjectDirectory(Path)} does that on a virtual thread and
 * marshals the list update back to the JavaFX application thread.</p>
 */
public final class MigrationHistoryView extends VBox {

    private static final String PROJECT_FILE_NAME = "project.daw";
    private static final String DEFAULT_TRIGGER = "Auto on open";
    private static final double DEFAULT_WIDTH = 520.0;
    private static final double MIN_WIDTH = 360.0;
    private static final double ICON_SIZE = 14.0;

    private static final Pattern BACKUP_PATTERN =
            Pattern.compile(Pattern.quote(PROJECT_FILE_NAME)
                    + "\\.v(\\d+)\\.(\\d{8}-\\d{6})(?:-(\\d+))?\\.bak");

    private static final DateTimeFormatter BACKUP_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    private final ObservableList<MigrationHistoryEntry> items =
            FXCollections.observableArrayList();
    private final ListView<MigrationHistoryEntry> listView = new ListView<>(items);
    /**
     * The FX-thread marshalling seam (story 289), injected on the production
     * path. May be {@code null} in a pure-unit context (the compatibility
     * constructors default it to {@link FxDispatcher#getDefault()});
     * {@link #postFx} tolerates the null.
     */
    private final FxDispatcher fxDispatcher;

    /** Creates an empty migration history view. */
    public MigrationHistoryView() {
        this(FxDispatcher.getDefault());
    }

    /**
     * Creates an empty migration history view with an explicit FX-thread
     * marshalling seam (story 289).
     *
     * @param fxDispatcher the FX-thread marshalling seam, or {@code null} to use
     *                     the {@link FxDispatcher#getDefault() app-scoped default}
     */
    public MigrationHistoryView(FxDispatcher fxDispatcher) {
        // May be null in a pure-unit context; postFx() falls back to the
        // static seam, preserving today's behaviour byte-for-byte.
        this.fxDispatcher = fxDispatcher;

        getStyleClass().addAll("browser-panel", "migration-history-view");
        setAccessibleRoleDescription("Migration history");
        setPrefWidth(DEFAULT_WIDTH);
        setMinWidth(MIN_WIDTH);
        setSpacing(6);
        setPadding(new Insets(8));

        Label header = new Label("MIGRATION HISTORY");
        header.getStyleClass().add("panel-header");
        header.setGraphic(IconNode.of(DawIcon.HISTORY, ICON_SIZE));

        listView.setPlaceholder(new Label("No migration backups found"));
        listView.getStyleClass().addAll("browser-list", "migration-history-list");
        listView.setCellFactory(_ -> new MigrationCell());
        VBox.setVgrow(listView, Priority.ALWAYS);

        getChildren().addAll(header, listView);
    }

    /** Creates a migration history view seeded with the supplied entries. */
    public MigrationHistoryView(List<MigrationHistoryEntry> entries) {
        this(entries, FxDispatcher.getDefault());
    }

    /**
     * Creates a migration history view seeded with the supplied entries and an
     * explicit FX-thread marshalling seam (story 289).
     *
     * @param entries      initial entries to display
     * @param fxDispatcher the FX-thread marshalling seam, or {@code null} to use
     *                     the {@link FxDispatcher#getDefault() app-scoped default}
     */
    public MigrationHistoryView(
            List<MigrationHistoryEntry> entries,
            FxDispatcher fxDispatcher) {
        this(fxDispatcher);
        setEntries(entries);
    }

    /** Returns the underlying list view, primarily for tests and host wiring. */
    public ListView<MigrationHistoryEntry> getListView() {
        return listView;
    }

    /** Returns the currently displayed entries in timeline order. */
    public List<MigrationHistoryEntry> getEntries() {
        return List.copyOf(items);
    }

    /**
     * Replaces the displayed entries. If called from a background thread, the
     * update is posted to the JavaFX application thread.
     */
    public void setEntries(List<MigrationHistoryEntry> entries) {
        List<MigrationHistoryEntry> copy =
                List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
        if (Platform.isFxApplicationThread()) {
            doSetEntries(copy);
        } else {
            postFx(() -> doSetEntries(copy));
        }
    }

    /**
     * Posts {@code work} to the FX thread through the injected
     * {@link FxDispatcher} when present, else the static app-scoped seam.
     */
    private void postFx(Runnable work) {
        FxDispatcher.runOnFx(fxDispatcher, work);
    }

    /**
     * Scans the project directory on a virtual thread and updates this view on
     * the FX thread when complete.
     *
     * @return the started worker thread
     */
    public Thread refreshFromProjectDirectory(Path projectDirectory) {
        Objects.requireNonNull(projectDirectory, "projectDirectory must not be null");
        Thread worker = Thread.ofVirtual()
                .name("daw-migration-history-scan")
                .unstarted(() -> setEntries(scanProjectDirectory(projectDirectory)));
        worker.start();
        return worker;
    }

    /**
     * Finds migration backup files in a project directory, returning entries in
     * timestamp order (oldest first). This method performs file I/O only and
     * never touches JavaFX.
     */
    public static List<MigrationHistoryEntry> scanProjectDirectory(Path projectDirectory) {
        return scanProjectDirectory(projectDirectory, MigrationRegistry.defaultRegistry());
    }

    static List<MigrationHistoryEntry> scanProjectDirectory(
            Path projectDirectory, MigrationRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");
        return scanProjectDirectory(projectDirectory,
                registry.currentVersion(), registry.migrations());
    }

    static List<MigrationHistoryEntry> scanProjectDirectory(
            Path projectDirectory, int currentSchemaVersion) {
        return scanProjectDirectory(projectDirectory, currentSchemaVersion, List.of());
    }

    private static List<MigrationHistoryEntry> scanProjectDirectory(
            Path projectDirectory,
            int currentSchemaVersion,
            List<ProjectMigration> migrations) {
        Objects.requireNonNull(projectDirectory, "projectDirectory must not be null");
        if (currentSchemaVersion < 1) {
            throw new IllegalArgumentException(
                    "currentSchemaVersion must be >= 1, was " + currentSchemaVersion);
        }
        if (!Files.isDirectory(projectDirectory)) {
            return List.of();
        }

        List<ParsedBackup> parsed = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(projectDirectory)) {
            for (Path entry : stream) {
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                parseBackup(entry).ifPresent(parsed::add);
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }

        parsed.sort(Comparator
                .comparing(ParsedBackup::timestamp)
                .thenComparingInt(ParsedBackup::collision)
                .thenComparing(p -> p.path().getFileName().toString()));

        List<MigrationHistoryEntry> entries = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            ParsedBackup backup = parsed.get(i);
            int toVersion = inferToVersion(parsed, i, currentSchemaVersion);
            entries.add(new MigrationHistoryEntry(
                    backup.fromSchemaVersion(),
                    toVersion,
                    backup.timestamp(),
                    DEFAULT_TRIGGER,
                    backup.path(),
                    bulletsFor(backup.fromSchemaVersion(), toVersion, migrations)));
        }
        return List.copyOf(entries);
    }

    private static Optional<ParsedBackup> parseBackup(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return Optional.empty();
        }
        Matcher matcher = BACKUP_PATTERN.matcher(fileName.toString());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            int fromVersion = Integer.parseInt(matcher.group(1));
            LocalDateTime local = LocalDateTime.parse(matcher.group(2), BACKUP_STAMP);
            int collision = matcher.group(3) == null
                    ? 0
                    : Integer.parseInt(matcher.group(3));
            return Optional.of(new ParsedBackup(
                    fromVersion,
                    local.toInstant(ZoneOffset.UTC),
                    collision,
                    path));
        } catch (NumberFormatException | DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static int inferToVersion(
            List<ParsedBackup> backups, int index, int currentSchemaVersion) {
        int fromVersion = backups.get(index).fromSchemaVersion();
        for (int i = index + 1; i < backups.size(); i++) {
            int laterSource = backups.get(i).fromSchemaVersion();
            if (laterSource > fromVersion) {
                return laterSource;
            }
        }
        return Math.max(currentSchemaVersion, fromVersion);
    }

    private static List<String> bulletsFor(
            int fromVersion, int toVersion, List<ProjectMigration> migrations) {
        List<MigrationReport.AppliedMigration> applied =
                appliedMigrationsFor(fromVersion, toVersion, migrations);
        if (!applied.isEmpty()) {
            return applied.stream()
                    .map(MigrationReportDialog::formatAppliedMigration)
                    .toList();
        }
        if (toVersion > fromVersion) {
            return List.of(MigrationReportDialog.formatAppliedMigration(
                    new MigrationReport.AppliedMigration(
                            fromVersion,
                            toVersion,
                            "migration details were not recorded with this backup")));
        }
        return List.of("Target schema was not recorded; this backup preserves schema v"
                + fromVersion + " before migration.");
    }

    private static List<MigrationReport.AppliedMigration> appliedMigrationsFor(
            int fromVersion, int toVersion, List<ProjectMigration> migrations) {
        if (toVersion <= fromVersion || migrations.isEmpty()) {
            return List.of();
        }
        List<ProjectMigration> sorted = migrations.stream()
                .sorted(Comparator.comparingInt(ProjectMigration::fromVersion))
                .toList();
        List<MigrationReport.AppliedMigration> applied = new ArrayList<>();
        int version = fromVersion;
        for (ProjectMigration migration : sorted) {
            if (migration.toVersion() <= version) {
                continue;
            }
            if (migration.fromVersion() > version || migration.toVersion() > toVersion) {
                break;
            }
            applied.add(new MigrationReport.AppliedMigration(
                    migration.fromVersion(),
                    migration.toVersion(),
                    migration.description()));
            version = migration.toVersion();
            if (version >= toVersion) {
                break;
            }
        }
        return List.copyOf(applied);
    }

    private void doSetEntries(List<MigrationHistoryEntry> entries) {
        Path selectedBackup = Optional.ofNullable(
                        listView.getSelectionModel().getSelectedItem())
                .map(MigrationHistoryEntry::backupPath)
                .orElse(null);
        items.setAll(entries);
        if (selectedBackup != null) {
            for (MigrationHistoryEntry entry : entries) {
                if (entry.backupPath().equals(selectedBackup)) {
                    listView.getSelectionModel().select(entry);
                    return;
                }
            }
        }
        if (!entries.isEmpty()) {
            int latest = entries.size() - 1;
            listView.getSelectionModel().select(latest);
            listView.scrollTo(latest);
        }
    }

    void requestDiff(MigrationHistoryEntry entry) {
        fireEvent(new MigrationHistoryEvent(MigrationHistoryEvent.DIFF_REQUESTED, entry));
    }

    void requestRollback(MigrationHistoryEntry entry) {
        fireEvent(new MigrationHistoryEvent(MigrationHistoryEvent.ROLLBACK_REQUESTED, entry));
    }

    /** One discovered migration backup entry. */
    public record MigrationHistoryEntry(
            int fromSchemaVersion,
            int toSchemaVersion,
            Instant timestamp,
            String triggerText,
            Path backupPath,
            List<String> bullets) {

        public MigrationHistoryEntry {
            if (fromSchemaVersion < 1) {
                throw new IllegalArgumentException(
                        "fromSchemaVersion must be >= 1, was " + fromSchemaVersion);
            }
            if (toSchemaVersion < 1) {
                throw new IllegalArgumentException(
                        "toSchemaVersion must be >= 1, was " + toSchemaVersion);
            }
            timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
            triggerText = Objects.requireNonNullElse(triggerText, "").isBlank()
                    ? DEFAULT_TRIGGER
                    : triggerText;
            backupPath = Objects.requireNonNull(backupPath, "backupPath must not be null");
            bullets = List.copyOf(Objects.requireNonNull(bullets, "bullets must not be null"));
        }

        /** @return just the backup file name for compact UI surfaces. */
        public String backupFileName() {
            Path fileName = backupPath.getFileName();
            return fileName == null ? backupPath.toString() : fileName.toString();
        }
    }

    /** Typed bubbling event for migration-history row actions. */
    public static final class MigrationHistoryEvent extends Event {

        private static final long serialVersionUID = 20260621L;

        /** Fired when a row's Diff button is pressed. */
        public static final EventType<MigrationHistoryEvent> DIFF_REQUESTED =
                new EventType<>(Event.ANY, "MIGRATION_HISTORY_DIFF_REQUESTED");

        /** Fired when a row's Roll back button is pressed. */
        public static final EventType<MigrationHistoryEvent> ROLLBACK_REQUESTED =
                new EventType<>(Event.ANY, "MIGRATION_HISTORY_ROLLBACK_REQUESTED");

        private final MigrationHistoryEntry entry;

        public MigrationHistoryEvent(
                EventType<? extends MigrationHistoryEvent> eventType,
                MigrationHistoryEntry entry) {
            super(eventType);
            this.entry = Objects.requireNonNull(entry, "entry must not be null");
        }

        public MigrationHistoryEvent(
                Object source,
                EventTarget target,
                EventType<? extends MigrationHistoryEvent> eventType,
                MigrationHistoryEntry entry) {
            super(source, target, eventType);
            this.entry = Objects.requireNonNull(entry, "entry must not be null");
        }

        /** @return the migration history entry selected for the action. */
        public MigrationHistoryEntry getEntry() {
            return entry;
        }

        /** @return the backup path selected for the action. */
        public Path getBackupPath() {
            return entry.backupPath();
        }
    }

    /** Public re-export of the diff request event type. */
    public static final EventType<MigrationHistoryEvent> DIFF_REQUESTED =
            MigrationHistoryEvent.DIFF_REQUESTED;

    /** Public re-export of the rollback request event type. */
    public static final EventType<MigrationHistoryEvent> ROLLBACK_REQUESTED =
            MigrationHistoryEvent.ROLLBACK_REQUESTED;

    /** Sets the handler invoked when a row requests a diff. */
    public final void setOnDiffRequested(EventHandler<MigrationHistoryEvent> handler) {
        setEventHandler(DIFF_REQUESTED, handler);
    }

    /** Sets the handler invoked when a row requests rollback. */
    public final void setOnRollbackRequested(EventHandler<MigrationHistoryEvent> handler) {
        setEventHandler(ROLLBACK_REQUESTED, handler);
    }

    private record ParsedBackup(
            int fromSchemaVersion,
            Instant timestamp,
            int collision,
            Path path) {
    }

    private final class MigrationCell extends ListCell<MigrationHistoryEntry> {

        private final Label versionLabel = new Label();
        private final Label timeLabel = new Label();
        private final Label triggerLabel = new Label();
        private final Label pathLabel = new Label();
        private final VBox bulletBox = new VBox(2);
        private final Button diffButton = new Button("Diff");
        private final Button rollbackButton = new Button("Roll back");
        private final VBox root;

        private MigrationCell() {
            versionLabel.getStyleClass().add("migration-history-version");
            timeLabel.getStyleClass().add("migration-history-time");
            triggerLabel.getStyleClass().add("migration-history-trigger");

            pathLabel.getStyleClass().add("migration-history-backup-path");
            pathLabel.setWrapText(true);
            bulletBox.getStyleClass().add("migration-history-bullets");

            diffButton.setGraphic(IconNode.of(DawIcon.SEARCH, 12));
            diffButton.setTooltip(new Tooltip("Compare this backup with the current project"));
            diffButton.setMinWidth(76);
            diffButton.setOnAction(_ -> {
                MigrationHistoryEntry entry = getItem();
                if (entry != null) {
                    getListView().getSelectionModel().select(entry);
                    requestDiff(entry);
                }
            });

            rollbackButton.setGraphic(IconNode.of(DawIcon.UNDO, 12));
            rollbackButton.setTooltip(new Tooltip("Roll back to this migration backup"));
            rollbackButton.setMinWidth(104);
            rollbackButton.setOnAction(_ -> {
                MigrationHistoryEntry entry = getItem();
                if (entry != null) {
                    getListView().getSelectionModel().select(entry);
                    requestRollback(entry);
                }
            });

            HBox metaRow = new HBox(8, versionLabel, timeLabel, triggerLabel);
            VBox details = new VBox(3, metaRow, pathLabel);
            HBox.setHgrow(details, Priority.ALWAYS);

            HBox actions = new HBox(6, diffButton, rollbackButton);
            actions.setMinWidth(190);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            HBox topRow = new HBox(10, details, spacer, actions);
            root = new VBox(6, topRow, bulletBox);
            root.getStyleClass().add("migration-history-row");
            root.setPadding(new Insets(8, 4, 8, 4));

            setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        }

        @Override
        protected void updateItem(MigrationHistoryEntry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setAccessibleText(null);
                return;
            }

            versionLabel.setText("v" + item.fromSchemaVersion()
                    + " -> v" + item.toSchemaVersion());
            timeLabel.setText(DISPLAY_TIME.format(item.timestamp()));
            triggerLabel.setText(item.triggerText());
            pathLabel.setText("Backup: " + item.backupPath());
            pathLabel.setTooltip(new Tooltip(item.backupPath().toString()));

            bulletBox.getChildren().setAll(item.bullets().stream()
                    .map(this::bulletLabel)
                    .toList());

            setAccessibleText("Migration backup "
                    + item.backupFileName()
                    + ", schema v" + item.fromSchemaVersion()
                    + " to v" + item.toSchemaVersion());
            setGraphic(root);
        }

        private Label bulletLabel(String text) {
            Label label = new Label("- " + text);
            label.setWrapText(true);
            label.getStyleClass().add("migration-history-bullet");
            return label;
        }
    }
}
