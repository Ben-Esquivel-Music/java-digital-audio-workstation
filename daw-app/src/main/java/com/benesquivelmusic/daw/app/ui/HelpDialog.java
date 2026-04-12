package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.audio.NativeLibraryDetector;
import com.benesquivelmusic.daw.core.audio.NativeLibraryStatus;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Modal dialog that displays keyboard shortcuts, a getting-started guide,
 * application information, and native library status.
 *
 * <p>Provides four tabbed sections:</p>
 * <ul>
 *   <li><b>Keyboard Shortcuts</b> — lists key bindings for transport, editing,
 *       navigation, and project actions</li>
 *   <li><b>Getting Started</b> — a brief feature guide for new users</li>
 *   <li><b>About</b> — application version and credits</li>
 *   <li><b>System Capabilities</b> — native library detection status and
 *       resolved paths, powered by {@link NativeLibraryDetector}</li>
 * </ul>
 *
 * <p>Uses the {@link DawIcon} icon pack for all tab and header graphics.</p>
 */
public final class HelpDialog extends Dialog<Void> {

    private static final double HEADER_ICON_SIZE = 18;
    private static final double TAB_ICON_SIZE = 14;

    /**
     * Creates a new help dialog.
     */
    public HelpDialog() {
        setTitle("Help");
        setHeaderText("Help & Documentation");
        setGraphic(IconNode.of(DawIcon.INFO, 24));

        // ── Keyboard Shortcuts tab ───────────────────────────────────────────
        Tab shortcutsTab = new Tab("Keyboard Shortcuts", buildShortcutsPane());
        shortcutsTab.setGraphic(IconNode.of(DawIcon.KEYBOARD, TAB_ICON_SIZE));
        shortcutsTab.setClosable(false);

        // ── Getting Started tab ──────────────────────────────────────────────
        Tab gettingStartedTab = new Tab("Getting Started", buildGettingStartedPane());
        gettingStartedTab.setGraphic(IconNode.of(DawIcon.INFO_CIRCLE, TAB_ICON_SIZE));
        gettingStartedTab.setClosable(false);

        // ── About tab ────────────────────────────────────────────────────────
        Tab aboutTab = new Tab("About", buildAboutPane());
        aboutTab.setGraphic(IconNode.of(DawIcon.HOME, TAB_ICON_SIZE));
        aboutTab.setClosable(false);

        // ── System Capabilities tab ──────────────────────────────────────────
        Tab capabilitiesTab = new Tab("System Capabilities", buildCapabilitiesPane());
        capabilitiesTab.setGraphic(IconNode.of(DawIcon.SETTINGS, TAB_ICON_SIZE));
        capabilitiesTab.setClosable(false);

        // ── Assemble ─────────────────────────────────────────────────────────
        TabPane tabPane = new TabPane(shortcutsTab, gettingStartedTab, aboutTab,
                capabilitiesTab);
        tabPane.setPrefWidth(520);
        tabPane.setPrefHeight(400);

        getDialogPane().setContent(tabPane);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        DarkThemeHelper.applyTo(this);

        setResultConverter(button -> null);
    }

    // ── Tab builders ─────────────────────────────────────────────────────────

    private Node buildShortcutsPane() {
        VBox vbox = new VBox(8);
        vbox.setPadding(new Insets(16));

        Label header = new Label("Keyboard Shortcuts");
        header.setGraphic(IconNode.of(DawIcon.KEYBOARD, HEADER_ICON_SIZE));
        header.setStyle("-fx-font-weight: bold;");

        // ── Transport shortcuts ──────────────────────────────────────────────
        Label transportHeader = new Label("Transport");
        transportHeader.setStyle("-fx-font-weight: bold; -fx-text-fill: #b388ff;");

        GridPane transportGrid = createGrid();
        transportGrid.add(new Label("Play / Pause:"), 0, 0);
        transportGrid.add(new Label("Space"), 1, 0);
        transportGrid.add(new Label("Stop:"), 0, 1);
        transportGrid.add(new Label("Escape"), 1, 1);
        transportGrid.add(new Label("Record:"), 0, 2);
        transportGrid.add(new Label("R"), 1, 2);
        transportGrid.add(new Label("Skip to Beginning:"), 0, 3);
        transportGrid.add(new Label("Home"), 1, 3);
        transportGrid.add(new Label("Skip Forward:"), 0, 4);
        transportGrid.add(new Label("End"), 1, 4);
        transportGrid.add(new Label("Toggle Loop:"), 0, 5);
        transportGrid.add(new Label("L"), 1, 5);

        // ── Editing shortcuts ────────────────────────────────────────────────
        Label editingHeader = new Label("Editing");
        editingHeader.setStyle("-fx-font-weight: bold; -fx-text-fill: #b388ff;");

        GridPane editingGrid = createGrid();
        editingGrid.add(new Label("Undo:"), 0, 0);
        editingGrid.add(new Label("Ctrl+Z"), 1, 0);
        editingGrid.add(new Label("Redo:"), 0, 1);
        editingGrid.add(new Label("Ctrl+Shift+Z"), 1, 1);
        editingGrid.add(new Label("Pointer Tool:"), 0, 2);
        editingGrid.add(new Label("V"), 1, 2);
        editingGrid.add(new Label("Pencil Tool:"), 0, 3);
        editingGrid.add(new Label("P"), 1, 3);
        editingGrid.add(new Label("Eraser Tool:"), 0, 4);
        editingGrid.add(new Label("E"), 1, 4);
        editingGrid.add(new Label("Scissors Tool:"), 0, 5);
        editingGrid.add(new Label("C"), 1, 5);
        editingGrid.add(new Label("Glue Tool:"), 0, 6);
        editingGrid.add(new Label("G"), 1, 6);

        // ── Navigation shortcuts ─────────────────────────────────────────────
        Label navigationHeader = new Label("Navigation & Project");
        navigationHeader.setStyle("-fx-font-weight: bold; -fx-text-fill: #b388ff;");

        GridPane navigationGrid = createGrid();
        navigationGrid.add(new Label("Save:"), 0, 0);
        navigationGrid.add(new Label("Ctrl+S"), 1, 0);
        navigationGrid.add(new Label("New Project:"), 0, 1);
        navigationGrid.add(new Label("Ctrl+N"), 1, 1);
        navigationGrid.add(new Label("Open Project:"), 0, 2);
        navigationGrid.add(new Label("Ctrl+O"), 1, 2);
        navigationGrid.add(new Label("Add Audio Track:"), 0, 3);
        navigationGrid.add(new Label("Ctrl+Shift+A"), 1, 3);
        navigationGrid.add(new Label("Add MIDI Track:"), 0, 4);
        navigationGrid.add(new Label("Ctrl+Shift+M"), 1, 4);
        navigationGrid.add(new Label("Arrangement View:"), 0, 5);
        navigationGrid.add(new Label("Ctrl+1"), 1, 5);
        navigationGrid.add(new Label("Mixer View:"), 0, 6);
        navigationGrid.add(new Label("Ctrl+2"), 1, 6);
        navigationGrid.add(new Label("Editor View:"), 0, 7);
        navigationGrid.add(new Label("Ctrl+3"), 1, 7);
        navigationGrid.add(new Label("Mastering View:"), 0, 8);
        navigationGrid.add(new Label("Ctrl+4"), 1, 8);
        navigationGrid.add(new Label("Toggle Browser:"), 0, 9);
        navigationGrid.add(new Label("Ctrl+B"), 1, 9);
        navigationGrid.add(new Label("Toggle Snap:"), 0, 10);
        navigationGrid.add(new Label("Ctrl+Shift+S"), 1, 10);
        navigationGrid.add(new Label("Zoom In:"), 0, 11);
        navigationGrid.add(new Label("Ctrl+="), 1, 11);
        navigationGrid.add(new Label("Zoom Out:"), 0, 12);
        navigationGrid.add(new Label("Ctrl+-"), 1, 12);
        navigationGrid.add(new Label("Zoom to Fit:"), 0, 13);
        navigationGrid.add(new Label("Ctrl+0"), 1, 13);

        vbox.getChildren().addAll(
                header, new Separator(),
                transportHeader, transportGrid,
                editingHeader, editingGrid,
                navigationHeader, navigationGrid);
        return vbox;
    }

    private Node buildGettingStartedPane() {
        VBox vbox = new VBox(8);
        vbox.setPadding(new Insets(16));

        Label header = new Label("Getting Started");
        header.setGraphic(IconNode.of(DawIcon.INFO_CIRCLE, HEADER_ICON_SIZE));
        header.setStyle("-fx-font-weight: bold;");

        Label intro = new Label(
                "Welcome to the Digital Audio Workstation! "
                + "Here are some tips to get started:");
        intro.setWrapText(true);

        Label tip1 = new Label("\u2022 Use the sidebar to switch between "
                + "Arrangement, Mixer, Editor, and Mastering views.");
        tip1.setWrapText(true);

        Label tip2 = new Label("\u2022 Add audio or MIDI tracks using the "
                + "toolbar buttons or Ctrl+Shift+A / Ctrl+Shift+M.");
        tip2.setWrapText(true);

        Label tip3 = new Label("\u2022 Arm a track for recording, then press "
                + "R or click the Record button to start recording.");
        tip3.setWrapText(true);

        Label tip4 = new Label("\u2022 Open the Browser panel (Ctrl+B) to "
                + "browse samples, presets, and project files.");
        tip4.setWrapText(true);

        Label tip5 = new Label("\u2022 Use the edit tools (Pointer, Pencil, "
                + "Eraser, Scissors, Glue) for clip editing in the Editor view.");
        tip5.setWrapText(true);

        Label tip6 = new Label("\u2022 Toggle snap-to-grid with Ctrl+Shift+S "
                + "and right-click the Snap button to change grid resolution.");
        tip6.setWrapText(true);

        Label tip7 = new Label("\u2022 Open Settings (Ctrl+,) to configure "
                + "audio, project defaults, appearance, and plugins.");
        tip7.setWrapText(true);

        vbox.getChildren().addAll(header, new Separator(),
                intro, tip1, tip2, tip3, tip4, tip5, tip6, tip7);
        return vbox;
    }

    private Node buildAboutPane() {
        VBox vbox = new VBox(8);
        vbox.setPadding(new Insets(16));

        Label header = new Label("About");
        header.setGraphic(IconNode.of(DawIcon.HOME, HEADER_ICON_SIZE));
        header.setStyle("-fx-font-weight: bold;");

        Label appName = new Label("Digital Audio Workstation");
        appName.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Label version = new Label("Version 0.1.0-SNAPSHOT");
        version.setStyle("-fx-text-fill: #808080;");

        Label description = new Label(
                "A state-of-the-art Digital Audio Workstation built with "
                + "JavaFX and Java 26. Features multi-track recording, MIDI "
                + "editing, audio processing, and real-time visualization.");
        description.setWrapText(true);

        Label license = new Label("Licensed under the MIT License.");
        license.setStyle("-fx-text-fill: #808080; -fx-font-size: 10px;");

        vbox.getChildren().addAll(header, new Separator(),
                appName, version, description, license);
        return vbox;
    }

    private Node buildCapabilitiesPane() {
        VBox vbox = new VBox(8);
        vbox.setPadding(new Insets(16));

        Label header = new Label("Native Library Status");
        header.setGraphic(IconNode.of(DawIcon.SETTINGS, HEADER_ICON_SIZE));
        header.setStyle("-fx-font-weight: bold;");

        Label description = new Label(
                "Shows the availability and resolved path of each native "
                + "library required by the audio engine.");
        description.setWrapText(true);
        description.setStyle("-fx-text-fill: #808080; -fx-font-size: 11px;");

        GridPane grid = createGrid();
        grid.setHgap(16);
        grid.setVgap(6);

        // Column headers
        Label nameHeader = new Label("Library");
        nameHeader.setStyle("-fx-font-weight: bold; -fx-text-fill: #b388ff;");
        Label statusHeader = new Label("Status");
        statusHeader.setStyle("-fx-font-weight: bold; -fx-text-fill: #b388ff;");
        Label pathHeader = new Label("Path / Required For");
        pathHeader.setStyle("-fx-font-weight: bold; -fx-text-fill: #b388ff;");
        grid.add(nameHeader, 0, 0);
        grid.add(statusHeader, 1, 0);
        grid.add(pathHeader, 2, 0);

        List<NativeLibraryStatus> statuses = NativeLibraryDetector.detectAll();
        int row = 1;
        for (NativeLibraryStatus status : statuses) {
            grid.add(new Label(status.libraryName()), 0, row);

            Label statusLabel = new Label(status.available() ? "\u2705 Available" : "\u274c Missing");
            statusLabel.setStyle(status.available()
                    ? "-fx-text-fill: #4caf50;"
                    : "-fx-text-fill: #f44336;");
            grid.add(statusLabel, 1, row);

            String detail = status.available()
                    ? status.detectedPath()
                    : status.requiredFor();
            Label detailLabel = new Label(detail);
            detailLabel.setWrapText(true);
            detailLabel.setMaxWidth(280);
            detailLabel.setStyle("-fx-text-fill: #b0b0b0; -fx-font-size: 10px;");
            grid.add(detailLabel, 2, row);
            row++;
        }

        vbox.getChildren().addAll(header, new Separator(), description, grid);
        return vbox;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static GridPane createGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(4);
        grid.setPadding(new Insets(4, 0, 8, 8));
        return grid;
    }
}
