package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.undo.UndoHistoryListener;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;

/**
 * A side panel that displays the undo/redo history as a scrollable list.
 *
 * <p>Each entry shows the description of an {@link UndoableAction}. The current
 * position in the history is highlighted. Clicking an item will undo or redo
 * all actions necessary to reach that point in the history via
 * {@link UndoManager#goToHistoryIndex(int)}.</p>
 *
 * <p>The panel automatically refreshes whenever the history changes by
 * registering an {@link UndoHistoryListener} with the {@link UndoManager}.</p>
 */
public final class UndoHistoryPanel extends VBox {

    private static final double DEFAULT_WIDTH = 250.0;
    private static final double MIN_WIDTH = 180.0;
    private static final double ICON_SIZE = 14.0;

    private final UndoManager undoManager;
    private final ListView<String> historyListView;
    private final ObservableList<String> historyItems;
    private final UndoHistoryListener listener;
    private boolean updating;

    /**
     * Creates a new undo history panel bound to the given {@link UndoManager}.
     *
     * @param undoManager the undo manager whose history to display
     */
    public UndoHistoryPanel(UndoManager undoManager) {
        this.undoManager = Objects.requireNonNull(undoManager, "undoManager must not be null");

        getStyleClass().add("browser-panel");
        setPrefWidth(DEFAULT_WIDTH);
        setMinWidth(MIN_WIDTH);
        setSpacing(6);
        setPadding(new Insets(8));

        // ── Header ──────────────────────────────────────────────────────────
        Label headerLabel = new Label("UNDO HISTORY");
        headerLabel.getStyleClass().add("panel-header");
        headerLabel.setGraphic(IconNode.of(DawIcon.HISTORY, ICON_SIZE));

        // ── History list ────────────────────────────────────────────────────
        historyItems = FXCollections.observableArrayList();
        historyListView = new ListView<>(historyItems);
        historyListView.setPlaceholder(new Label("No actions yet"));
        historyListView.getStyleClass().add("browser-list");
        historyListView.setCellFactory(listView -> new HistoryCell());
        VBox.setVgrow(historyListView, Priority.ALWAYS);

        historyListView.setOnMouseClicked(event -> {
            int selectedIndex = historyListView.getSelectionModel().getSelectedIndex();
            if (selectedIndex >= 0 && !updating) {
                undoManager.goToHistoryIndex(selectedIndex);
            }
        });

        getChildren().addAll(headerLabel, historyListView);

        // ── Listen for history changes ──────────────────────────────────────
        listener = manager -> {
            if (Platform.isFxApplicationThread()) {
                refreshHistory();
            } else {
                Platform.runLater(this::refreshHistory);
            }
        };
        undoManager.addHistoryListener(listener);

        refreshHistory();
    }

    /**
     * Returns the history list view.
     *
     * @return the list view
     */
    public ListView<String> getHistoryListView() {
        return historyListView;
    }

    /**
     * Detaches this panel from the {@link UndoManager} by removing its
     * history listener. Call this when the panel is no longer needed.
     */
    public void dispose() {
        undoManager.removeHistoryListener(listener);
    }

    /**
     * Re-reads the undo/redo history from the manager and updates the list.
     */
    void refreshHistory() {
        updating = true;
        try {
            List<UndoableAction> history = undoManager.getHistory();
            historyItems.clear();
            for (UndoableAction action : history) {
                historyItems.add(action.description());
            }
            int currentIndex = undoManager.getCurrentHistoryIndex();
            if (currentIndex >= 0 && currentIndex < historyItems.size()) {
                historyListView.getSelectionModel().select(currentIndex);
                historyListView.scrollTo(currentIndex);
            } else {
                historyListView.getSelectionModel().clearSelection();
            }
        } finally {
            updating = false;
        }
    }

    /**
     * A custom cell that highlights entries based on whether they have been
     * executed (at or before the current index) or are in the redo portion.
     */
    private final class HistoryCell extends ListCell<String> {

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setOpacity(1.0);
            } else {
                setText(item);
                int currentIdx = undoManager.getCurrentHistoryIndex();
                if (getIndex() <= currentIdx) {
                    setOpacity(1.0);
                    setGraphic(IconNode.of(DawIcon.UNDO, 12));
                } else {
                    setOpacity(0.5);
                    setGraphic(IconNode.of(DawIcon.REDO, 12));
                }
            }
        }
    }
}
