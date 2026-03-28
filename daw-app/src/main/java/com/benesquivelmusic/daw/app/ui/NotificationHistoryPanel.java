package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A side panel displaying the notification history (warnings and errors).
 *
 * <p>Mirrors the {@link UndoHistoryPanel} layout pattern: a header label,
 * a scrollable list of entries, and a clear button. Each entry shows the
 * timestamp, severity icon, and message text.</p>
 */
public final class NotificationHistoryPanel extends VBox {

    private static final double DEFAULT_WIDTH = 280.0;
    private static final double MIN_WIDTH = 200.0;
    private static final double ICON_SIZE = 14.0;
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final NotificationHistoryService historyService;
    private final ListView<NotificationEntry> historyListView;
    private final ObservableList<NotificationEntry> historyItems;
    private final Consumer<NotificationEntry> serviceListener;

    /**
     * Creates a notification history panel bound to the given service.
     *
     * @param historyService the notification history service to display
     */
    public NotificationHistoryPanel(NotificationHistoryService historyService) {
        this.historyService = Objects.requireNonNull(historyService,
                "historyService must not be null");

        getStyleClass().add("browser-panel");
        setPrefWidth(DEFAULT_WIDTH);
        setMinWidth(MIN_WIDTH);
        setSpacing(6);
        setPadding(new Insets(8));

        // ── Header ──────────────────────────────────────────────────────────
        Label headerLabel = new Label("NOTIFICATION HISTORY");
        headerLabel.getStyleClass().add("panel-header");
        headerLabel.setGraphic(IconNode.of(DawIcon.BELL_RING, ICON_SIZE));

        Button clearButton = new Button("Clear");
        clearButton.getStyleClass().add("notification-history-clear");
        clearButton.setOnAction(_ -> historyService.clear());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox headerRow = new HBox(6, headerLabel, spacer, clearButton);
        headerRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // ── History list ────────────────────────────────────────────────────
        historyItems = FXCollections.observableArrayList();
        historyListView = new ListView<>(historyItems);
        historyListView.setPlaceholder(new Label("No warnings or errors"));
        historyListView.getStyleClass().add("browser-list");
        historyListView.setCellFactory(_ -> new NotificationHistoryCell());
        VBox.setVgrow(historyListView, Priority.ALWAYS);

        getChildren().addAll(headerRow, historyListView);

        // ── Listen for new entries ──────────────────────────────────────────
        serviceListener = _ -> {
            if (Platform.isFxApplicationThread()) {
                refreshHistory();
            } else {
                Platform.runLater(this::refreshHistory);
            }
        };
        historyService.addListener(serviceListener);

        refreshHistory();
    }

    /**
     * Returns the history list view.
     */
    public ListView<NotificationEntry> getHistoryListView() {
        return historyListView;
    }

    /**
     * Detaches this panel from the history service.
     * Call when the panel is no longer needed.
     */
    public void dispose() {
        historyService.removeListener(serviceListener);
    }

    /**
     * Refreshes the displayed list from the history service.
     */
    void refreshHistory() {
        List<NotificationEntry> all = historyService.getEntries();
        historyItems.setAll(all);
        if (!all.isEmpty()) {
            historyListView.scrollTo(all.size() - 1);
        }
    }

    /**
     * A cell that renders a notification entry with its timestamp, level icon,
     * and message.
     */
    private static final class NotificationHistoryCell extends ListCell<NotificationEntry> {

        @Override
        protected void updateItem(NotificationEntry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
            } else {
                String time = TIME_FMT.format(item.timestamp());
                setText("[" + time + "] " + item.message());
                setGraphic(IconNode.of(item.level().icon(), 12));
            }
        }
    }
}
