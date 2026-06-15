package com.benesquivelmusic.daw.app.ui.hub;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Skin;

import java.time.Instant;
import java.util.Objects;

/**
 * One project tile in the story-296 Project Hub / Welcome screen — a custom
 * {@link Control} carrying a project's name, last-opened time, on-disk size,
 * session count, schema / backup versions, lock holder and a {@link HealthBadge}.
 *
 * <p>Follows the {@code Control + SkinBase + package-resource user-agent
 * stylesheet} convention of {@link com.benesquivelmusic.daw.app.ui.status.StatusStripCell}
 * and {@link com.benesquivelmusic.daw.app.ui.status.SessionStatusStrip}
 * ({@code javafx-application-design} skill §3 Control/Skin split, §8 themeable
 * via a user-agent stylesheet plus stable style classes).</p>
 *
 * <h2>Headless-safe style-class reconciliation</h2>
 *
 * <p>The {@link #healthProperty() health}, {@link #selectedProperty() selected}
 * and {@link #pinnedProperty() pinned} properties reconcile their style classes
 * <em>on this control itself</em> from {@code invalidated()} (mirroring
 * {@code StatusStripCell.severity}) so the visual state is correct even in a
 * headless, unrealized-skin test. The matching {@code -fx-*} rules live in
 * {@code project-card.css}.</p>
 *
 * <h2>Control/Skin split</h2>
 *
 * <p>{@link ProjectCardSkin} builds and binds the labels. The label accessors
 * below delegate to the realized skin — a test must attach the card to a
 * {@code Scene} and call {@code applyCss()} before reading them.</p>
 *
 * @see ProjectCardSkin
 * @see ProjectScanResult
 */
public final class ProjectCard extends Control {

    /** Stable style class — selectable as {@code .project-card}. */
    public static final String DEFAULT_STYLE_CLASS = "project-card";

    /** Style class added while {@link #selectedProperty()} is {@code true}. */
    public static final String SELECTED_CLASS = "project-card-selected";

    /** Style class added while {@link #pinnedProperty()} is {@code true}. */
    public static final String PINNED_CLASS = "project-card-pinned";

    private final StringProperty name =
            new SimpleStringProperty(this, "name", "");
    private final ObjectProperty<Instant> lastOpened =
            new SimpleObjectProperty<>(this, "lastOpened", null);
    private final LongProperty sizeOnDiskBytes =
            new SimpleLongProperty(this, "sizeOnDiskBytes", -1L);
    private final IntegerProperty sessionCount =
            new SimpleIntegerProperty(this, "sessionCount", -1);
    private final IntegerProperty missingAssetCount =
            new SimpleIntegerProperty(this, "missingAssetCount", 0);
    private final IntegerProperty schemaVersion =
            new SimpleIntegerProperty(this, "schemaVersion", -1);
    private final IntegerProperty backupSchemaVersion =
            new SimpleIntegerProperty(this, "backupSchemaVersion", -1);
    private final StringProperty lockHolderLabel =
            new SimpleStringProperty(this, "lockHolderLabel", "");
    private final BooleanProperty lockStale =
            new SimpleBooleanProperty(this, "lockStale", false);

    /**
     * Pinned state. Its {@code invalidated()} reconciles the
     * {@link #PINNED_CLASS} style class on this control (headless-safe).
     */
    private final BooleanProperty pinned =
            new SimpleBooleanProperty(this, "pinned", false) {
                @Override
                protected void invalidated() {
                    reconcileFlagClass(PINNED_CLASS, get());
                }
            };

    /**
     * Selected state. Its {@code invalidated()} reconciles the
     * {@link #SELECTED_CLASS} style class on this control (headless-safe).
     */
    private final BooleanProperty selected =
            new SimpleBooleanProperty(this, "selected", false) {
                @Override
                protected void invalidated() {
                    reconcileFlagClass(SELECTED_CLASS, get());
                }
            };

    /**
     * Health badge. Its {@code set(...)} coerces {@code null} to
     * {@link HealthBadge#HEALTHY}, and its {@code invalidated()} reconciles the
     * three badge style classes directly on this control (headless-safe —
     * copies {@code StatusStripCell.severity}): remove all three, add exactly
     * the current badge's class.
     */
    private final ObjectProperty<HealthBadge> health =
            new SimpleObjectProperty<>(this, "health", HealthBadge.HEALTHY) {
                @Override
                public void set(HealthBadge newValue) {
                    super.set(newValue == null ? HealthBadge.HEALTHY : newValue);
                }

                @Override
                protected void invalidated() {
                    getStyleClass().removeAll(HealthBadge.allStyleClasses());
                    getStyleClass().add(get().styleClass());
                }
            };

    /** Creates a blank card ({@link HealthBadge#HEALTHY}, unknown facts). */
    public ProjectCard() {
        getStyleClass().add(DEFAULT_STYLE_CLASS);
        // Seed the health style class so a card constructed but never re-set
        // still carries .project-card-healthy.
        getStyleClass().add(HealthBadge.HEALTHY.styleClass());
        setAccessibleRole(AccessibleRole.BUTTON);
        setFocusTraversable(true);
        // Keep the screen-reader text in step with the name (no bind on a
        // read-only-style accessor — a listener + setter, like the design notes).
        name.addListener((_, _, n) -> setAccessibleText(n == null ? "" : n));
        setAccessibleText(name.get());
    }

    private void reconcileFlagClass(String styleClass, boolean on) {
        // Remove first (guards against a duplicate add), then add when on —
        // mirrors StatusStripCell.severity's add/remove so the state is correct
        // without a realized skin.
        getStyleClass().remove(styleClass);
        if (on) {
            getStyleClass().add(styleClass);
        }
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new ProjectCardSkin(this);
    }

    @Override
    public String getUserAgentStylesheet() {
        return Objects.requireNonNull(
                ProjectCard.class.getResource("project-card.css"),
                "project-card.css not on classpath").toExternalForm();
    }

    /**
     * Applies a {@link ProjectScanResult} to this card's properties — the
     * scan's name, last-opened, size, session count, missing-asset count,
     * schema / backup versions, lock holder + staleness, and health. Leaves
     * {@link #pinnedProperty() pinned} and {@link #selectedProperty() selected}
     * untouched (those are view state, not scan output). Call on the FX thread.
     *
     * @param r the scan result; must not be {@code null}
     */
    public void applyScanResult(ProjectScanResult r) {
        Objects.requireNonNull(r, "scan result must not be null");
        setName(r.name());
        setLastOpened(r.lastOpened());
        setSizeOnDiskBytes(r.sizeOnDiskBytes());
        setSessionCount(r.sessionCount());
        setMissingAssetCount(r.missingAssetCount());
        setSchemaVersion(r.schemaVersion());
        setBackupSchemaVersion(r.backupSchemaVersion());
        setLockHolderLabel(r.lockHolderLabel());
        setLockStale(r.lockStale());
        setHealth(r.health());
    }

    // ── name ─────────────────────────────────────────────────────────────────

    /** @return the project-name property. */
    public final StringProperty nameProperty() { return name; }
    /** @return the project name. */
    public final String getName() { return name.get(); }
    /** @param name the project name. */
    public final void setName(String name) { this.name.set(name); }

    // ── lastOpened ─────────────────────────────────────────────────────────────

    /** @return the last-opened property (value may be {@code null}). */
    public final ObjectProperty<Instant> lastOpenedProperty() { return lastOpened; }
    /** @return the last-opened instant, or {@code null}. */
    public final Instant getLastOpened() { return lastOpened.get(); }
    /** @param lastOpened the last-opened instant, or {@code null}. */
    public final void setLastOpened(Instant lastOpened) { this.lastOpened.set(lastOpened); }

    // ── sizeOnDiskBytes ────────────────────────────────────────────────────────

    /** @return the on-disk-size property ({@code -1} = unknown). */
    public final LongProperty sizeOnDiskBytesProperty() { return sizeOnDiskBytes; }
    /** @return the on-disk size in bytes, or {@code -1} if unknown. */
    public final long getSizeOnDiskBytes() { return sizeOnDiskBytes.get(); }
    /** @param bytes the on-disk size in bytes ({@code -1} = unknown). */
    public final void setSizeOnDiskBytes(long bytes) { this.sizeOnDiskBytes.set(bytes); }

    // ── sessionCount ───────────────────────────────────────────────────────────

    /** @return the session-count property ({@code -1} = unknown). */
    public final IntegerProperty sessionCountProperty() { return sessionCount; }
    /** @return the session count, or {@code -1} if unknown. */
    public final int getSessionCount() { return sessionCount.get(); }
    /** @param count the session count ({@code -1} = unknown). */
    public final void setSessionCount(int count) { this.sessionCount.set(count); }

    // ── missingAssetCount ──────────────────────────────────────────────────────

    /** @return the missing-asset-count property. */
    public final IntegerProperty missingAssetCountProperty() { return missingAssetCount; }
    /** @return the missing-asset count. */
    public final int getMissingAssetCount() { return missingAssetCount.get(); }
    /** @param count the missing-asset count. */
    public final void setMissingAssetCount(int count) { this.missingAssetCount.set(count); }

    // ── schemaVersion ──────────────────────────────────────────────────────────

    /** @return the schema-version property ({@code -1} = unknown). */
    public final IntegerProperty schemaVersionProperty() { return schemaVersion; }
    /** @return the schema version, or {@code -1} if unknown. */
    public final int getSchemaVersion() { return schemaVersion.get(); }
    /** @param version the schema version ({@code -1} = unknown). */
    public final void setSchemaVersion(int version) { this.schemaVersion.set(version); }

    // ── backupSchemaVersion ────────────────────────────────────────────────────

    /** @return the backup-schema-version property ({@code -1} = none). */
    public final IntegerProperty backupSchemaVersionProperty() { return backupSchemaVersion; }
    /** @return the backup schema version, or {@code -1} if none. */
    public final int getBackupSchemaVersion() { return backupSchemaVersion.get(); }
    /** @param version the backup schema version ({@code -1} = none). */
    public final void setBackupSchemaVersion(int version) { this.backupSchemaVersion.set(version); }

    // ── lockHolderLabel ────────────────────────────────────────────────────────

    /** @return the lock-holder-label property ({@code ""} = no lock). */
    public final StringProperty lockHolderLabelProperty() { return lockHolderLabel; }
    /** @return the lock-holder label, or {@code ""} if no lock. */
    public final String getLockHolderLabel() { return lockHolderLabel.get(); }
    /** @param label the lock-holder label ({@code ""} = no lock). */
    public final void setLockHolderLabel(String label) { this.lockHolderLabel.set(label); }

    // ── lockStale ──────────────────────────────────────────────────────────────

    /** @return the lock-stale property. */
    public final BooleanProperty lockStaleProperty() { return lockStale; }
    /** @return whether the lock is stale. */
    public final boolean isLockStale() { return lockStale.get(); }
    /** @param stale whether the lock is stale. */
    public final void setLockStale(boolean stale) { this.lockStale.set(stale); }

    // ── pinned ─────────────────────────────────────────────────────────────────

    /**
     * @return the pinned property; setting it reconciles the
     *         {@link #PINNED_CLASS} style class on this control
     */
    public final BooleanProperty pinnedProperty() { return pinned; }
    /** @return whether this card is pinned. */
    public final boolean isPinned() { return pinned.get(); }
    /** @param pinned whether this card is pinned. */
    public final void setPinned(boolean pinned) { this.pinned.set(pinned); }

    // ── selected ───────────────────────────────────────────────────────────────

    /**
     * @return the selected property; setting it reconciles the
     *         {@link #SELECTED_CLASS} style class on this control
     */
    public final BooleanProperty selectedProperty() { return selected; }
    /** @return whether this card is selected. */
    public final boolean isSelected() { return selected.get(); }
    /** @param selected whether this card is selected. */
    public final void setSelected(boolean selected) { this.selected.set(selected); }

    // ── health ─────────────────────────────────────────────────────────────────

    /**
     * @return the health property. Setting it (or invalidating it) reconciles
     *         the {@link HealthBadge#allStyleClasses()} style classes on this
     *         control, leaving exactly the current badge's class.
     */
    public final ObjectProperty<HealthBadge> healthProperty() { return health; }
    /** @return the current health badge (never {@code null}). */
    public final HealthBadge getHealth() { return health.get(); }
    /** @param health the health badge; a {@code null} is coerced to {@link HealthBadge#HEALTHY}. */
    public final void setHealth(HealthBadge health) { this.health.set(health); }

    // ── Skin-delegated label accessors ───────────────────────────────────────────

    private ProjectCardSkin skin() {
        Skin<?> skin = getSkin();
        if (!(skin instanceof ProjectCardSkin pcs)) {
            throw new IllegalStateException(
                    "ProjectCard skin is not realized yet — attach the card to a "
                            + "Scene and call applyCss() before reading its labels.");
        }
        return pcs;
    }

    /** @return the name label (delegates to the realized skin). */
    public Label nameLabel() { return skin().nameLabel(); }

    /** @return the meta label (the "14:22 today · 1.8 GB · 12 sess." line). */
    public Label metaLabel() { return skin().metaLabel(); }

    /** @return the health label. */
    public Label healthLabel() { return skin().healthLabel(); }
}
