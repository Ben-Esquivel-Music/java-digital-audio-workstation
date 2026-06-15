package com.benesquivelmusic.daw.app.ui.hub;

import java.util.List;

/**
 * The health classification shown on a {@link ProjectCard} in the story-296
 * Project Hub / Welcome screen. Each badge carries the exact style class the
 * card adds to itself (so the {@code .project-card.project-card-…} CSS targets
 * its health label) and a default human label.
 *
 * <p>Standalone enum (not nested in {@link ProjectCard}) so the JavaFX-free
 * {@link ProjectScanResult} record can carry a {@code HealthBadge} without
 * depending on the JavaFX {@code Control}.</p>
 */
public enum HealthBadge {

    /** Loads cleanly at the current schema, all assets present. */
    HEALTHY("project-card-healthy", "✓ healthy"),
    /** Was migrated from an older schema (a pre-migration backup exists). */
    MIGRATED("project-card-migrated", "⚠ migrated"),
    /** One or more referenced assets are missing on disk. */
    MISSING_ASSETS("project-card-missing", "⚠ missing assets");

    private final String styleClass;
    private final String label;

    HealthBadge(String styleClass, String label) {
        this.styleClass = styleClass;
        this.label = label;
    }

    /** @return the single style class the card adds to itself for this badge. */
    public String styleClass() {
        return styleClass;
    }

    /**
     * @return the base label for this badge; callers may append a detail such
     *         as {@code "vN→vM"} (migrated) or a count (missing assets)
     */
    public String defaultLabel() {
        return label;
    }

    /**
     * @return all three badge style classes, for the card's remove-then-add
     *         style-class reconciliation (so flipping the badge never leaves a
     *         stale class on the control)
     */
    public static List<String> allStyleClasses() {
        return List.of(
                HEALTHY.styleClass,
                MIGRATED.styleClass,
                MISSING_ASSETS.styleClass);
    }
}
