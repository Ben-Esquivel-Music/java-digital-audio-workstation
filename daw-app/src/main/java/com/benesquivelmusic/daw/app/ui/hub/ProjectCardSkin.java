package com.benesquivelmusic.daw.app.ui.hub;

import javafx.beans.binding.Bindings;
import javafx.scene.control.Label;
import javafx.scene.control.SkinBase;
import javafx.scene.layout.VBox;

import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Skin for {@link ProjectCard} (story-296 Project Hub / Welcome screen,
 * {@code javafx-application-design} skill §3). Builds a small card layout — a
 * name, a one-line meta summary and a health line — and binds each label's
 * text to a {@code StringBinding} over the card's properties.
 *
 * <h2>Binding-only</h2>
 *
 * <p>Every label's text is wired via {@code bind(...)}; this skin never calls
 * {@code Label.setText} after construction. {@link #dispose()} unbinds all
 * labels before delegating to the superclass so the skin can be swapped (e.g.
 * on a theme change) without leaking the bindings ({@code javafx-application-design}
 * §3/§4).</p>
 */
public final class ProjectCardSkin extends SkinBase<ProjectCard> {

    private final Label nameLabel = new Label();
    private final Label metaLabel = new Label();
    private final Label healthLabel = new Label();
    private final VBox content = new VBox();

    /**
     * Clock backing the relative "last opened" label — captured once rather than
     * re-fetched on every binding recompute. (Stage 2 uses the system clock; a
     * later stage can make this injectable for a deterministic relative label.)
     */
    private final InstantSource clock = InstantSource.system();

    /**
     * Builds the card's labels and binds them to the control's properties.
     *
     * @param card the owning {@link ProjectCard}
     */
    public ProjectCardSkin(ProjectCard card) {
        super(card);

        nameLabel.getStyleClass().add("project-card-name");
        metaLabel.getStyleClass().add("project-card-meta");
        healthLabel.getStyleClass().add("project-card-health");
        content.getStyleClass().add("project-card-content");

        // Name — straight mirror of the control's name property.
        nameLabel.textProperty().bind(card.nameProperty());

        // Meta — "<last opened> · <size> · N sess.", omitting the session
        // fragment when the count is unknown (-1), joined by " · ".
        metaLabel.textProperty().bind(Bindings.createStringBinding(
                () -> {
                    List<String> parts = new ArrayList<>(3);
                    parts.add(HubFormat.formatRelativeOpened(
                            card.getLastOpened(), clock));
                    parts.add(HubFormat.formatBytes(card.getSizeOnDiskBytes()));
                    int sessions = card.getSessionCount();
                    if (sessions >= 0) {
                        parts.add(sessions + " sess.");
                    }
                    return String.join(" · ", parts);
                },
                card.lastOpenedProperty(), card.sizeOnDiskBytesProperty(),
                card.sessionCountProperty()));

        // Health — badge label, with a migrated-version detail and a
        // missing-assets count when those facts are known.
        healthLabel.textProperty().bind(Bindings.createStringBinding(
                () -> healthText(card),
                card.healthProperty(), card.backupSchemaVersionProperty(),
                card.schemaVersionProperty(), card.missingAssetCountProperty()));

        content.getChildren().setAll(nameLabel, metaLabel, healthLabel);
        getChildren().add(content);
    }

    /**
     * The §health-line text for {@code card}: {@code "✓ healthy"} when healthy;
     * {@code "⚠ migrated v{backup}→v{schema}"} when migrated (omitting the
     * arrow detail if either version is unknown); {@code "⚠ {n} missing assets"}
     * when assets are missing.
     */
    private static String healthText(ProjectCard card) {
        HealthBadge badge = card.getHealth();
        return switch (badge) {
            case HEALTHY -> HealthBadge.HEALTHY.defaultLabel();
            case MIGRATED -> {
                int backup = card.getBackupSchemaVersion();
                int schema = card.getSchemaVersion();
                if (backup >= 0 && schema >= 0) {
                    yield HealthBadge.MIGRATED.defaultLabel() + " v" + backup + "→v" + schema;
                }
                yield HealthBadge.MIGRATED.defaultLabel();
            }
            case MISSING_ASSETS -> {
                int missing = card.getMissingAssetCount();
                yield "⚠ " + missing + " missing assets";
            }
        };
    }

    // ── Accessors for the control (delegated to from ProjectCard) ───────────────

    Label nameLabel() { return nameLabel; }
    Label metaLabel() { return metaLabel; }
    Label healthLabel() { return healthLabel; }

    @Override
    public void dispose() {
        nameLabel.textProperty().unbind();
        metaLabel.textProperty().unbind();
        healthLabel.textProperty().unbind();
        super.dispose();
    }
}
