package com.benesquivelmusic.daw.app.ui.help;

import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Non-modal right-side help panel that renders the markdown for the
 * currently-selected topic, lets the user search across topics, and
 * exposes a breadcrumb trail back to the index.
 *
 * <p>The overlay is its own {@link Stage} configured as
 * {@link StageStyle#UTILITY} so it stays above the main window without
 * stealing focus. Reuse a single instance per main window — calling
 * {@link #showTopic(String)} on an open overlay simply navigates to the
 * new topic.</p>
 */
public final class HelpOverlay {

    private final HelpRegistry registry;
    private final HelpMarkdownRenderer renderer = new HelpMarkdownRenderer();
    private final Stage stage = new Stage(StageStyle.UTILITY);

    private final ReadOnlyStringWrapper currentSlug = new ReadOnlyStringWrapper();
    private final List<String> history = new ArrayList<>();

    private final TextField searchField = new TextField();
    private final ListView<HelpTopic> resultsList = new ListView<>();
    private final HBox breadcrumb = new HBox(4);
    private final Label titleLabel = new Label();
    private final ScrollPane bodyScroll = new ScrollPane();

    public HelpOverlay(HelpRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        renderer.setLinkHandler(this::showTopic);

        stage.setTitle("Help");
        stage.initModality(Modality.NONE);
        stage.setAlwaysOnTop(false);
        stage.setWidth(420);
        stage.setHeight(640);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(8));
        root.getStyleClass().add("help-overlay");

        // ── Top: search + breadcrumb ────────────────────────────────────────
        searchField.setPromptText("Search help…");
        searchField.textProperty().addListener((obs, o, n) -> refreshResults(n));

        breadcrumb.getStyleClass().add("help-breadcrumb");

        VBox top = new VBox(6, searchField, breadcrumb);
        top.setPadding(new Insets(0, 0, 6, 0));
        root.setTop(top);

        // ── Center: title + markdown body ───────────────────────────────────
        titleLabel.getStyleClass().add("help-title");
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        bodyScroll.setFitToWidth(true);
        bodyScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        VBox center = new VBox(6, titleLabel, bodyScroll);
        VBox.setVgrow(bodyScroll, Priority.ALWAYS);
        root.setCenter(center);

        // ── Bottom: search results, hidden until search is non-empty ────────
        resultsList.setPrefHeight(140);
        resultsList.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(HelpTopic item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.title());
            }
        });
        resultsList.setOnMouseClicked(e -> {
            HelpTopic selected = resultsList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                showTopic(selected.slug());
            }
        });
        resultsList.setVisible(false);
        resultsList.setManaged(false);
        root.setBottom(resultsList);

        Scene scene = new Scene(root);
        stage.setScene(scene);
    }

    /** Read-only property tracking the slug currently being displayed. */
    public ReadOnlyStringProperty currentSlugProperty() {
        return currentSlug.getReadOnlyProperty();
    }

    /** Returns the slug currently displayed (may be {@code null} before first show). */
    public String currentSlug() {
        return currentSlug.get();
    }

    /**
     * Position the overlay flush against the right edge of {@code owner}.
     * No-op when {@code owner} is {@code null} or has no scene.
     */
    public void anchorTo(Window owner) {
        if (owner == null) {
            return;
        }
        if (stage.getOwner() == null && !stage.isShowing()) {
            stage.initOwner(owner);
        }
        double x = owner.getX() + owner.getWidth() - stage.getWidth();
        double y = owner.getY();
        stage.setX(x);
        stage.setY(y);
        stage.setHeight(Math.max(360, owner.getHeight()));
    }

    /**
     * Shows {@code slug} (or the index, if unknown) and brings the overlay
     * to the front without grabbing focus from the main window.
     */
    public void showTopic(String slug) {
        HelpTopic topic = registry.resolve(slug);
        String resolved = topic.slug();
        currentSlug.set(resolved);
        if (history.isEmpty() || !history.get(history.size() - 1).equals(resolved)) {
            history.add(resolved);
        }
        titleLabel.setText(topic.title());
        Region body = renderer.render(topic.body());
        bodyScroll.setContent(body);
        rebuildBreadcrumb();
        if (!stage.isShowing()) {
            stage.show();
        } else {
            stage.toFront();
        }
    }

    /** Clears the history and shows the index. */
    public void showIndex() {
        history.clear();
        showTopic(HelpRegistry.INDEX_SLUG);
    }

    /** Hides the overlay (keeping its state for the next {@link #showTopic}). */
    public void hide() {
        stage.hide();
    }

    /** Whether the overlay window is currently visible. */
    public boolean isShowing() {
        return stage.isShowing();
    }

    /** Exposes the underlying stage for advanced positioning. */
    public Stage getStage() {
        return stage;
    }

    private void rebuildBreadcrumb() {
        breadcrumb.getChildren().clear();
        // Always provide a path back to the index.
        Button homeBtn = breadcrumbButton("Index", HelpRegistry.INDEX_SLUG);
        breadcrumb.getChildren().add(homeBtn);
        for (int i = 0; i < history.size(); i++) {
            String slug = history.get(i);
            if (slug.equals(HelpRegistry.INDEX_SLUG)) {
                continue;
            }
            Label sep = new Label("›");
            sep.getStyleClass().add("help-breadcrumb-sep");
            HelpTopic t = registry.resolve(slug);
            Button btn = breadcrumbButton(t.title(), slug);
            if (i == history.size() - 1) {
                btn.setDisable(true);
                btn.getStyleClass().add("help-breadcrumb-current");
            }
            breadcrumb.getChildren().addAll(sep, btn);
        }
    }

    private Button breadcrumbButton(String label, String slug) {
        Button btn = new Button(label);
        btn.getStyleClass().addAll("help-breadcrumb-link", "button-link");
        btn.setOnAction(e -> {
            // Trim history forward of this slug, then re-show.
            int idx = history.lastIndexOf(slug);
            if (idx >= 0 && idx < history.size() - 1) {
                history.subList(idx + 1, history.size()).clear();
            }
            showTopic(slug);
        });
        return btn;
    }

    private void refreshResults(String query) {
        if (query == null || query.isBlank()) {
            resultsList.setVisible(false);
            resultsList.setManaged(false);
            resultsList.setItems(FXCollections.emptyObservableList());
            return;
        }
        ObservableList<HelpTopic> items = FXCollections.observableArrayList(registry.search(query));
        resultsList.setItems(items);
        resultsList.setVisible(true);
        resultsList.setManaged(true);
    }

    // Test hooks ─────────────────────────────────────────────────────────────

    /** Visible for testing — returns the live results list contents. */
    ObservableList<HelpTopic> testResults() {
        return resultsList.getItems();
    }

    /** Visible for testing — drives the search field. */
    void testSearch(String query) {
        searchField.setText(query);
    }

    /** Visible for testing — current breadcrumb labels. */
    List<String> testBreadcrumbLabels() {
        List<String> labels = new ArrayList<>();
        for (var node : breadcrumb.getChildren()) {
            if (node instanceof Button b) {
                labels.add(b.getText());
            }
        }
        return labels;
    }
}
