package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dialogs.DawgDialog;
import com.benesquivelmusic.daw.core.persistence.AssetReference;
import com.benesquivelmusic.daw.core.persistence.MissingAsset;
import com.benesquivelmusic.daw.core.persistence.MissingAssetsReport;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The missing-assets report of an open project (story 329; Persistence
 * Integrity book §4.3, §6.2; decisions D6 and D15): one row per asset the
 * open could not load as saved — why (the D6 label), where it was (the
 * path, ellipsised at the front, the full path in the tooltip), and the
 * clips that use it — and a link to the archive consolidation flow (story
 * 189), the recovery of last resort.
 *
 * <p>Non-modal and owned by the main window: the user keeps working while
 * it is open. {@link OpenProjectAudio} shows it once per open and keeps it as
 * the generation's {@link OpenProjectAudio.ReportView}: every {@code Review…}
 * re-presents this one instance, each report change {@linkplain #update
 * updates} it, and retiring the open {@linkplain #dismiss() closes} it. The
 * footer's Close ({@link ButtonBar.ButtonData#CANCEL_CLOSE}) only hides it.</p>
 *
 * <p>Relink (decisions D16, D17; {@link AssetRelinkFlow}): each row's
 * {@code missing-assets-row-actions} box ({@link #rowActions}) holds
 * {@code Locate file…} and {@code Skip}, disabled while the row's work is in
 * flight, and a status line says what the last attempt found (a refusal and
 * why, or how many candidates a folder search tied); above the rows,
 * {@code Locate folder…} searches one folder for every row, and while it
 * runs says which folder and offers {@code Cancel search}. It mirrors
 * {@code ArchiveSummaryDialog.DecisionRow} without its defects: no action
 * waits behind a combo box, every text is styled by a scoped rule with
 * tokens, and every row and control has accessible text. Every update
 * rebuilds the rows; keyboard focus that was on a row's control stays among
 * the rows rather than falling back to the top of the dialog.</p>
 */
final class MissingAssetsDialog extends DawgDialog<Void> implements OpenProjectAudio.ReportView {

    /** How many holders a row names before it says how many more there are. */
    static final int NAMED_HOLDERS = 6;

    /** The scroll pane holding the rows grows to this height, then scrolls (4 px grid). */
    private static final double ROWS_MAX_HEIGHT = 320;

    /** What the report's controls ask of its owner, and where each row's relink stands. */
    interface Actions {

        /** Runs the archive consolidation flow (story 189) for the open project. */
        void archiveProject();

        /**
         * Locate file: relinks {@code row}'s asset to a file the user picks.
         *
         * @param row the row
         */
        void locateFile(MissingAsset row);

        /**
         * Skip: hides every row of {@code row}'s asset
         * ({@link MissingAssetsReport#without}) for this session.
         *
         * @param row the row
         */
        void skip(MissingAsset row);

        /** Locate folder: searches a folder the user picks for every row. */
        void locateFolder();

        /** Cancels the running folder search. */
        void cancelFolderSearch();

        /**
         * Returns where the relink of {@code asset} stands.
         *
         * @param asset a row's asset
         * @return the row's status
         */
        AssetRelinkFlow.RowStatus statusOf(AssetReference asset);

        /**
         * Returns the folder the running search walks.
         *
         * @return the folder, or empty when no search runs
         */
        Optional<Path> searchedFolder();
    }

    private final Actions actions;
    private final Label summary = new Label();
    private final Button locateFolder = new Button(ProjectAudioText.text("missingAssets.locateFolder"));
    private final Label searching = new Label();
    private final Button cancelSearch = new Button(ProjectAudioText.text("missingAssets.cancelSearch"));
    private final VBox rows = new VBox();
    private MissingAssetsReport report;

    /**
     * Creates the report for {@code report}, owned by {@code owner}. Not shown
     * until {@link #present()}.
     *
     * @param owner   the main window, or {@code null} when there is none
     * @param report  the report to show
     * @param actions the owner's actions
     */
    MissingAssetsDialog(Window owner, MissingAssetsReport report, Actions actions) {
        this.actions = Objects.requireNonNull(actions, "actions must not be null");
        this.report = Objects.requireNonNull(report, "report must not be null");
        setTitle(ProjectAudioText.text("missingAssets.title"));
        setHeaderText(ProjectAudioText.text("missingAssets.title"));
        initModality(Modality.NONE);
        if (owner != null) {
            initOwner(owner);
        }
        sized(Size.LARGE);
        getDialogPane().getStyleClass().add("missing-assets-dialog");

        summary.getStyleClass().add("dawg-dialog-message");
        summary.setWrapText(true);
        locateFolder.getStyleClass().addAll("dawg-button", "size-default", "missing-assets-locate-folder");
        locateFolder.setAccessibleText(ProjectAudioText.text("missingAssets.locateFolder.accessible"));
        locateFolder.setOnAction(_ -> this.actions.locateFolder());
        searching.getStyleClass().add("missing-assets-searching");
        searching.setMinWidth(0);
        searching.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
        HBox.setHgrow(searching, Priority.ALWAYS);
        cancelSearch.getStyleClass().addAll("dawg-button", "size-default", "secondary",
                "missing-assets-cancel-search");
        cancelSearch.setAccessibleText(ProjectAudioText.text("missingAssets.cancelSearch.accessible"));
        cancelSearch.setOnAction(_ -> this.actions.cancelFolderSearch());
        HBox folderBar = new HBox(8, locateFolder, searching, cancelSearch);
        folderBar.setAlignment(Pos.CENTER_LEFT);
        folderBar.getStyleClass().add("missing-assets-folder-bar");
        rows.getStyleClass().add("missing-assets-rows");
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setMaxHeight(ROWS_MAX_HEIGHT);
        scroll.getStyleClass().add("missing-assets-scroll");
        VBox assets = new VBox(12, summary, folderBar, scroll);
        addSection(ProjectAudioText.text("missingAssets.section.assets"), assets);

        Hyperlink archive = new Hyperlink(ProjectAudioText.text("missingAssets.archive"));
        archive.getStyleClass().add("missing-assets-archive");
        archive.setAccessibleText(ProjectAudioText.text("missingAssets.archive.accessible"));
        archive.setOnAction(_ -> this.actions.archiveProject());
        addSection(ProjectAudioText.text("missingAssets.section.recovery"), archive);

        secondary(ProjectAudioText.text("missingAssets.close"), null);
        Node close = closeButton();
        if (close != null) {
            close.setAccessibleText(ProjectAudioText.text("missingAssets.close.accessible"));
        }
        showRows();
    }

    // ── ReportView ───────────────────────────────────────────────────────────

    @Override
    public void present() {
        if (!isShowing()) {
            show();
            return;
        }
        Window window = getDialogPane().getScene() == null ? null : getDialogPane().getScene().getWindow();
        if (window instanceof Stage stage) {
            stage.toFront();
        }
        if (window != null) {
            window.requestFocus();
        }
    }

    @Override
    public void update(MissingAssetsReport report) {
        this.report = Objects.requireNonNull(report, "report must not be null");
        showRows();
    }

    @Override
    public void dismiss() {
        close();
    }

    // ── content ──────────────────────────────────────────────────────────────

    /**
     * Returns the report this dialog shows.
     *
     * @return the report
     */
    MissingAssetsReport report() {
        return report;
    }

    /**
     * Returns the rows' nodes, in report order (one per row; tests read them).
     *
     * @return the row nodes
     */
    List<Node> rowNodes() {
        return List.copyOf(rows.getChildren());
    }

    /**
     * The row's own actions, shown in its {@code missing-assets-row-actions}
     * box: {@code Locate file…} and {@code Skip}, both disabled while the
     * row's work is in flight. Skip's accessible text says what skipping
     * leaves ({@link #skipAccessibleText}).
     *
     * @param row the row
     * @return the row's action controls, in order
     */
    List<Node> rowActions(MissingAsset row) {
        boolean working = actions.statusOf(row.reference()) instanceof AssetRelinkFlow.RowStatus.Working;
        String where = locationText(row.reference());
        Button locate = new Button(ProjectAudioText.text("missingAssets.locateFile"));
        locate.getStyleClass().addAll("dawg-button", "size-compact", "missing-assets-locate-file");
        locate.setAccessibleText(ProjectAudioText.text("missingAssets.locateFile.accessible", where));
        locate.setDisable(working);
        locate.setOnAction(_ -> actions.locateFile(row));
        Button skip = new Button(ProjectAudioText.text("missingAssets.skip"));
        skip.getStyleClass().addAll("dawg-button", "size-compact", "secondary", "missing-assets-skip");
        skip.setAccessibleText(skipAccessibleText(row, where));
        skip.setDisable(working);
        skip.setOnAction(_ -> actions.skip(row));
        return List.of(locate, skip);
    }

    /**
     * What Skip on {@code row} leaves (decision D43). Skip
     * hides every row of the asset ({@link MissingAssetsReport#without}) —
     * one asset can have two rows, such as a shorter file whose ADM clips
     * partly select nothing — so the text counts over all of them: the
     * clips are the union of their holders, the silent clips the union of
     * their silent holders. None silent: the clips keep playing as loaded;
     * all silent: they stay silent; otherwise it says how many stay silent
     * and that the others keep playing as loaded.
     *
     * @param row   the row
     * @param where the asset's location text
     * @return Skip's accessible text
     */
    private String skipAccessibleText(MissingAsset row, String where) {
        List<MissingAsset> hidden = report.rowsFor(row.reference());
        Set<String> clips = new HashSet<>();
        Set<String> silent = new HashSet<>();
        for (MissingAsset each : hidden) {
            for (MissingAsset.Holder holder : each.holders()) {
                clips.add(holder.clipId());
            }
            for (MissingAsset.Holder holder : each.silentHolders()) {
                silent.add(holder.clipId());
            }
        }
        if (silent.isEmpty()) {
            return ProjectAudioText.text("missingAssets.skip.accessible.playing", where);
        }
        if (silent.containsAll(clips)) {
            return ProjectAudioText.text("missingAssets.skip.accessible", where);
        }
        return ProjectAudioText.count("missingAssets.skip.accessible.some", silent.size(), where);
    }

    /**
     * What the row's status line says, or empty when there is nothing to
     * say (decision D17's labels).
     *
     * @param status the row's relink status
     * @return the status text
     */
    static Optional<String> statusText(AssetRelinkFlow.RowStatus status) {
        return switch (status) {
            case AssetRelinkFlow.RowStatus.Idle _ -> Optional.empty();
            case AssetRelinkFlow.RowStatus.Working _ ->
                    Optional.of(ProjectAudioText.text("missingAssets.status.working"));
            case AssetRelinkFlow.RowStatus.Ambiguous tie -> Optional.of(ProjectAudioText.text(
                    "missingAssets.status.ambiguous", Integer.toString(tie.candidates())));
            case AssetRelinkFlow.RowStatus.Refused refused -> Optional.of(switch (refused.reason()) {
                case NOT_FOUND, UNRESOLVABLE -> ProjectAudioText.text("missingAssets.status.refused.notFound",
                        refused.detail());
                case UNREADABLE -> ProjectAudioText.text("missingAssets.status.refused.unreadable", refused.detail());
                case TOO_LARGE -> ProjectAudioText.text("missingAssets.status.refused.tooLarge", refused.detail());
                case CHANGED_ON_DISK, ADM_OBJECT_UNKNOWN -> ProjectAudioText.text("missingAssets.status.refused",
                        refused.detail());
            });
        };
    }

    private void showRows() {
        summary.setText(report.isEmpty()
                ? ProjectAudioText.text("missingAssets.empty")
                : OpenAnnouncement.summary(report));
        Optional<Path> searched = actions.searchedFolder();
        locateFolder.setDisable(searched.isPresent() || report.isEmpty());
        searching.setText(searched.map(folder -> ProjectAudioText.text("missingAssets.searching", folder.toString()))
                .orElse(""));
        searching.setAccessibleText(searching.getText());
        searching.setVisible(searched.isPresent());
        cancelSearch.setVisible(searched.isPresent());
        cancelSearch.setManaged(searched.isPresent());
        Optional<FocusedControl> focused = focusedRowControl();
        List<Node> nodes = new ArrayList<>(report.assets().size());
        for (MissingAsset row : report.assets()) {
            nodes.add(rowNode(row));
        }
        rows.getChildren().setAll(nodes);
        focused.ifPresent(this::restoreFocus);
    }

    /**
     * Where keyboard focus is among the rows' controls: the row's index and
     * the control's index in its {@code missing-assets-row-actions} box.
     *
     * @param row     the row's index
     * @param control the control's index in the row's actions
     */
    private record FocusedControl(int row, int control) {
    }

    /** The row control that has keyboard focus, when focus is inside the rows. */
    private Optional<FocusedControl> focusedRowControl() {
        Scene scene = getDialogPane().getScene();
        Node owner = scene == null ? null : scene.getFocusOwner();
        Node control = null;
        for (Node node = owner; node != null; node = node.getParent()) {
            if (node.getParent() != null && node.getParent().getStyleClass().contains("missing-assets-row-actions")) {
                control = node;
            }
            if (node.getParent() == rows) {
                Parent actionBox = control == null ? null : control.getParent();
                return Optional.of(new FocusedControl(rows.getChildren().indexOf(node),
                        actionBox == null ? 0 : actionBox.getChildrenUnmodifiable().indexOf(control)));
            }
        }
        return Optional.empty();
    }

    /**
     * Gives keyboard focus back after the rows were rebuilt (r1 S2): to the
     * same control of the row now at the same index (the last row when
     * there are fewer), else to the next enabled control of that row or a
     * later one, else to {@code Locate folder…}, else to Close — never to
     * the top of the dialog, where a removed control's focus would fall.
     */
    private void restoreFocus(FocusedControl focused) {
        List<Node> rowNodes = rows.getChildren();
        int start = Math.min(focused.row(), rowNodes.size() - 1);
        int from = focused.control();
        for (int index = Math.max(start, 0); index < rowNodes.size(); index++) {
            List<Node> controls = rowControls(rowNodes.get(index));
            for (int at = index == start ? from : 0; at < controls.size(); at++) {
                Node control = controls.get(at);
                if (!control.isDisabled() && control.isFocusTraversable()) {
                    control.requestFocus();
                    return;
                }
            }
        }
        Node close = closeButton();
        if (!locateFolder.isDisabled()) {
            locateFolder.requestFocus();
        } else if (close != null) {
            close.requestFocus();
        }
    }

    /** The controls of a row's {@code missing-assets-row-actions} box, in order. */
    private static List<Node> rowControls(Node row) {
        Node actionBox = row.lookup(".missing-assets-row-actions");
        return actionBox instanceof Parent parent ? parent.getChildrenUnmodifiable() : List.of();
    }

    private Node rowNode(MissingAsset row) {
        String label = reasonText(row);
        String where = locationText(row.reference());
        Label reason = new Label(label);
        reason.getStyleClass().add("missing-assets-reason");
        reason.setMinWidth(Label.USE_PREF_SIZE);
        Label path = new Label(where);
        path.getStyleClass().add("missing-assets-path");
        path.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
        path.setMinWidth(0);
        path.setMaxWidth(Double.MAX_VALUE);
        path.setTooltip(new Tooltip(String.join("\n", row.reference().references())));
        path.setAccessibleText(String.join(", ", row.reference().references()));
        HBox.setHgrow(path, Priority.ALWAYS);
        HBox head = new HBox(8, reason, path);
        head.getStyleClass().add("missing-assets-row-head");

        VBox node = new VBox(4, head);
        if (showsDetail(row)) {
            Label detail = new Label(row.detail());
            detail.getStyleClass().add("missing-assets-detail");
            detail.setWrapText(true);
            node.getChildren().add(detail);
        }
        Label usedBy = new Label(ProjectAudioText.count("missingAssets.usedBy", row.clipCount(), holdersText(row)));
        usedBy.getStyleClass().add("missing-assets-used-by");
        usedBy.setWrapText(true);
        node.getChildren().add(usedBy);
        AssetRelinkFlow.RowStatus status = actions.statusOf(row.reference());
        statusText(status).ifPresent(text -> {
            Label line = new Label(text);
            line.getStyleClass().add(status instanceof AssetRelinkFlow.RowStatus.Working
                    ? "missing-assets-status" : "missing-assets-status-attention");
            line.setWrapText(true);
            line.setAccessibleText(text);
            node.getChildren().add(line);
        });
        List<Node> rowActions = rowActions(row);
        if (!rowActions.isEmpty()) {
            HBox actionBox = new HBox(8);
            actionBox.getStyleClass().add("missing-assets-row-actions");
            actionBox.getChildren().setAll(rowActions);
            node.getChildren().add(actionBox);
        }
        node.getStyleClass().add("missing-assets-row");
        node.setAccessibleText(ProjectAudioText.text("missingAssets.row.accessible", label, where,
                row.clipCount(), Integer.toString(row.clipCount())));
        return node;
    }

    /** The row's label (decisions D6, D24, D35); the reason's detail goes on its own line. */
    static String reasonText(MissingAsset row) {
        return switch (row.reason()) {
            case NOT_FOUND -> ProjectAudioText.text("missingAssets.reason.notFound");
            case UNRESOLVABLE -> ProjectAudioText.text("missingAssets.reason.unresolvable");
            case UNREADABLE -> ProjectAudioText.text("missingAssets.reason.unreadable");
            case CHANGED_ON_DISK -> ProjectAudioText.text("missingAssets.reason.changedOnDisk");
            case ADM_OBJECT_UNKNOWN -> ProjectAudioText.text("missingAssets.reason.admObjectUnknown");
            case TOO_LARGE -> ProjectAudioText.text("missingAssets.reason.tooLarge");
        };
    }

    /**
     * Where the asset was: the file, or for a recorded take the folder of its
     * segments and how many there are.
     */
    static String locationText(AssetReference reference) {
        return switch (reference) {
            case AssetReference.SingleFile file -> file.reference();
            case AssetReference.Take take -> ProjectAudioText.text("missingAssets.take",
                    folderOf(take.segments().getFirst()), take.segments().size(),
                    Integer.toString(take.segments().size()));
        };
    }

    private static String folderOf(String segment) {
        try {
            Path parent = Path.of(segment).getParent();
            return parent == null ? segment : parent.toString();
        } catch (InvalidPathException notAPath) {
            return segment;
        }
    }

    /**
     * Whether the row's detail adds to its label: not for a file that is
     * simply not there ("Missing" says it). An unreadable file's reason is
     * on the wrapped detail line like every other reason's (decision D35),
     * so a long reason never squeezes the path out of the row's head.
     */
    private static boolean showsDetail(MissingAsset row) {
        if (row.detail().isBlank()) {
            return false;
        }
        return switch (row.reason()) {
            case NOT_FOUND -> row.reference() instanceof AssetReference.Take;
            case UNRESOLVABLE, UNREADABLE, CHANGED_ON_DISK, ADM_OBJECT_UNKNOWN, TOO_LARGE -> true;
        };
    }

    /** The holders, "track — clip", the first {@link #NAMED_HOLDERS} by name. */
    private static String holdersText(MissingAsset row) {
        List<String> named = new ArrayList<>();
        for (MissingAsset.Holder holder : row.holders()) {
            if (named.size() == NAMED_HOLDERS) {
                break;
            }
            named.add(ProjectAudioText.text("missingAssets.holder", holder.trackName(), holder.clipName()));
        }
        String names = String.join(", ", named);
        int more = row.clipCount() - named.size();
        return more > 0 ? names + " " + ProjectAudioText.count("missingAssets.moreHolders", more) : names;
    }

    private Node closeButton() {
        for (ButtonType type : getDialogPane().getButtonTypes()) {
            if (type.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE) {
                return getDialogPane().lookupButton(type);
            }
        }
        return null;
    }
}
