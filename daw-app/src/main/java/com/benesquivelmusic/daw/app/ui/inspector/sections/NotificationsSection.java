package com.benesquivelmusic.daw.app.ui.inspector.sections;

import com.benesquivelmusic.daw.app.ui.NotificationEntry;
import com.benesquivelmusic.daw.app.ui.NotificationHistoryService;
import com.benesquivelmusic.daw.app.ui.NotificationPill;
import com.benesquivelmusic.daw.app.ui.inspector.InspectorDrawer;
import com.benesquivelmusic.daw.app.ui.inspector.InspectorSection;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * "NOTIFICATIONS" inspector section (UI Design Book §5.6, §7.8,
 * story 273).
 *
 * <p>Folds the former standalone notification-history surface into the
 * inspector drawer. Shows the most recent ~100 notifications
 * (newest-first), grouped by day with a small uppercase muted day
 * header, each rendered with the shared {@link NotificationPill} so the
 * history and the transient toast never visually drift. Each pill with
 * an action lets the user re-trigger the original action. A borderless
 * "Clear" button empties the log (parity with the removed panel).</p>
 *
 * <p>Plain {@link InspectorSection} (a {@code VBox}) subclass — not a
 * {@code Control + Skin} pair — following {@link NotesSection}'s
 * precedent and Skill §3 (no Control/Skin where it adds no value).</p>
 */
public final class NotificationsSection extends InspectorSection {

    public static final String DEFAULT_STYLE_CLASS = "inspector-notifications-section";

    private static final DateTimeFormatter DAY_FMT =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.ROOT);

    private final VBox pillsBox = new VBox(4);
    private final ScrollPane scroll = new ScrollPane(pillsBox);
    private final Label emptyLabel = new Label(InspectorDrawer.msg("notification.empty"));
    private final Consumer<NotificationEntry> serviceListener;

    private NotificationHistoryService historyService;

    public NotificationsSection(String title) {
        super(title == null ? InspectorDrawer.msg("inspector.section.notifications") : title);
        getStyleClass().add(DEFAULT_STYLE_CLASS);

        emptyLabel.getStyleClass().add("inspector-field-label");

        pillsBox.getStyleClass().add("notification-history-list");
        pillsBox.setFillWidth(true);

        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("notification-history-scroll");
        scroll.setPrefViewportHeight(200);
        scroll.setFocusTraversable(true);

        Button clearButton = new Button(InspectorDrawer.msg("notification.clear"));
        clearButton.getStyleClass().add("notification-history-clear");
        clearButton.setOnAction(_ -> {
            if (historyService != null) {
                historyService.clear();
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(4, spacer, clearButton);
        toolbar.setAlignment(Pos.CENTER_RIGHT);
        toolbar.getStyleClass().add("notification-history-toolbar");

        VBox body = new VBox(4, toolbar, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        setBody(body);

        // Rebuild on any change (entry or null/clear), marshalled to the
        // FX thread — same pattern as the removed standalone panel.
        serviceListener = _ -> {
            if (Platform.isFxApplicationThread()) {
                rebuild();
            } else {
                FxDispatcher.runOnFx(this::rebuild);
            }
        };

        rebuild();
    }

    /**
     * Re-points this section to {@code service}, detaching from any
     * previous one. This is how {@link InspectorDrawer} unifies the
     * notification stream without breaking FXML's no-arg construction.
     *
     * @param service the shared notification log, or {@code null} to unbind
     */
    public void setHistoryService(NotificationHistoryService service) {
        if (this.historyService == service) {
            return;
        }
        if (this.historyService != null) {
            this.historyService.removeListener(serviceListener);
        }
        this.historyService = service;
        if (service != null) {
            service.addListener(serviceListener);
        }
        rebuild();
    }

    /** @return the bound notification log, or {@code null} if unbound. */
    public NotificationHistoryService getHistoryService() {
        return historyService;
    }

    /**
     * @return the rendered pills in display order (newest-first) — for tests.
     */
    public List<NotificationPill> getPills() {
        List<NotificationPill> pills = new ArrayList<>();
        for (var node : pillsBox.getChildren()) {
            if (node instanceof NotificationPill p) {
                pills.add(p);
            }
        }
        return pills;
    }

    /** @return the scrollable pills container — for tests / theming. */
    public VBox getPillsBox() {
        return pillsBox;
    }

    /** Rebuilds the newest-first, day-grouped pill list from the log. */
    void rebuild() {
        pillsBox.getChildren().clear();
        List<NotificationEntry> entries =
                historyService == null ? List.of() : historyService.getEntries();
        if (entries.isEmpty()) {
            pillsBox.getChildren().add(emptyLabel);
            return;
        }

        LocalDate currentDay = null;
        // Iterate newest-first (the service stores oldest-first).
        for (int i = entries.size() - 1; i >= 0; i--) {
            NotificationEntry entry = entries.get(i);
            LocalDate day = entry.timestamp().atZone(ZoneId.systemDefault()).toLocalDate();
            if (!Objects.equals(day, currentDay)) {
                currentDay = day;
                Label dayHeader = new Label(DAY_FMT.format(day).toUpperCase(Locale.ROOT));
                dayHeader.getStyleClass().add("notification-history-day");
                pillsBox.getChildren().add(dayHeader);
            }
            NotificationPill pill = new NotificationPill(false);
            pill.setEntry(entry);
            pillsBox.getChildren().add(pill);
        }
    }
}
