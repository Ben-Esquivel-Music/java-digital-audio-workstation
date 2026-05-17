package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * The shared notification pill visual (UI Design Book §5.10, §7.3,
 * story 273).
 *
 * <p>A 32 px tall {@code -surface-1} pill with a 4 px left bar in the
 * level's semantic colour, a tinted severity glyph, the message text,
 * an optional borderless action button, and (only on the transient
 * toast) a trailing dismiss affordance. The accent bar is a drawn
 * {@link Rectangle} child positioned at {@code CENTER_LEFT} with
 * {@code managed = false} — <em>not</em> a CSS border — per §7.3, the
 * same approach as story 270's armed-track edge bar.</p>
 *
 * <p>Both {@link NotificationBar} (the transient toast) and
 * {@code NotificationsSection} (the inspector history log) compose this
 * one node so the two surfaces never drift. The level style class
 * (e.g. {@code notification-warning}) is applied to the root so a single
 * CSS block drives the accent-bar fill in both contexts.</p>
 *
 * <p>The accent {@link Rectangle}'s fill is CSS-driven (the level style
 * class selects the {@code -ntf-*} token). The severity glyph has no CSS
 * tint hook, so it is re-tinted in Java to whatever colour the accent
 * bar resolved to: the bar's resolved fill is the single source of
 * truth, so the glyph follows the level colour and any future theme
 * swap (story 277) without a parallel hard-coded palette.</p>
 */
public final class NotificationPill extends StackPane {

    /** Stable style class — selectable as {@code .notification-pill}. */
    public static final String DEFAULT_STYLE_CLASS = "notification-pill";

    private static final double ACCENT_BAR_WIDTH = 4;
    private static final double GLYPH_SIZE = 16;
    private static final double DISMISS_ICON_SIZE = 14;

    /** Resource bundle for chrome strings (Skill §14) — Locale.ROOT. */
    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);

    private final Rectangle accentBar = new Rectangle();
    private final Label iconHolder = new Label();
    private final Label messageLabel = new Label();
    private final Button actionButton = new Button();
    private final Region trailingSlot = new Region();
    private final HBox contentRow;

    private NotificationLevel currentLevel;
    private Runnable actionHandler;

    /**
     * @param showDismiss whether to render a trailing dismiss affordance
     *                    (the transient toast shows it; history pills do
     *                    not — they have no auto-dismiss)
     */
    public NotificationPill(boolean showDismiss) {
        getStyleClass().add(DEFAULT_STYLE_CLASS);
        setAccessibleRole(AccessibleRole.NODE);

        accentBar.getStyleClass().add("notification-accent-bar");
        accentBar.setWidth(ACCENT_BAR_WIDTH);
        accentBar.setManaged(false);
        // Height tracks the pill height so the bar always spans the row.
        accentBar.heightProperty().bind(heightProperty());
        // Glyph colour is locked to the bar's resolved CSS fill — single
        // source of truth, so a theme swap (story 277) recolours both.
        accentBar.fillProperty().addListener((_, _, fill) -> retintGlyph(fill));

        messageLabel.getStyleClass().add("notification-message");

        actionButton.getStyleClass().add("notification-action");
        actionButton.setVisible(false);
        actionButton.setManaged(false);
        actionButton.setOnAction(_ -> {
            if (actionHandler != null) {
                actionHandler.run();
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        contentRow = new HBox(8, iconHolder, messageLabel, spacer, actionButton);
        contentRow.setAlignment(Pos.CENTER_LEFT);
        // Left padding clears the 4 px accent bar; right padding from CSS.
        contentRow.setPadding(new Insets(0, 0, 0, 12));

        getChildren().addAll(contentRow, accentBar);
        StackPane.setAlignment(accentBar, Pos.CENTER_LEFT);

        if (showDismiss) {
            contentRow.getChildren().add(trailingSlot);
        }
    }

    /**
     * Populates this pill from a history {@link NotificationEntry}. The
     * entry's action (if present) is wired to the action button so the
     * history surface can re-trigger it.
     *
     * <p><strong>Limitation (story 273 AC — "where the action is still
     * valid"):</strong> the stored {@link Runnable} is re-run verbatim;
     * there is no validity model, so a history action whose target no
     * longer exists (e.g. a "Configure input" for a since-deleted track)
     * executes against nothing. Tracked as a follow-up — deliberately not
     * expanded here to keep story 273 scoped.</p>
     */
    public void setEntry(NotificationEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        apply(entry.level(), entry.message(),
                entry.actionLabel().orElse(null), entry.action().orElse(null));
    }

    /**
     * Populates this pill directly (used by the transient toast).
     *
     * @param level       severity level
     * @param message     message text
     * @param actionLabel optional action label; {@code null} hides the button
     * @param action      optional action; {@code null} hides the button
     */
    public void apply(NotificationLevel level,
                      String message,
                      String actionLabel,
                      Runnable action) {
        clearLevelStyle();
        currentLevel = level;
        getStyleClass().add(level.styleClass());

        iconHolder.setGraphic(IconNode.of(level.icon(), GLYPH_SIZE));
        // Resolve the accent bar's CSS fill *now* and tint deterministically.
        // applyCss() is a no-op off-scene (the fillProperty listener still
        // covers the off-scene→scene and theme-swap transitions), but when
        // on-scene it forces the new level token to resolve so a re-show of
        // the *same* level — where the fill never changes and the listener
        // never fires — does not leave the freshly-built glyph on the SVG's
        // hard-coded colour. (Review S2.)
        applyCss();
        retintGlyph(accentBar.getFill());
        messageLabel.setText(message);

        if (action != null && actionLabel != null && !actionLabel.isBlank()) {
            actionHandler = action;
            actionButton.setText(actionLabel);
            actionButton.setVisible(true);
            actionButton.setManaged(true);
        } else {
            actionHandler = null;
            actionButton.setText("");
            actionButton.setVisible(false);
            actionButton.setManaged(false);
        }

        setAccessibleText(localizedLevelName(level) + ": " + message);
    }

    /** Installs a trailing dismiss affordance into the reserved slot. */
    void installDismiss(Runnable onDismiss) {
        Node closeIcon = IconNode.of(DawIcon.CLOSE, DISMISS_ICON_SIZE);
        Button dismissButton = new Button();
        dismissButton.getStyleClass().add("notification-dismiss");
        dismissButton.setGraphic(closeIcon);
        dismissButton.setTooltip(new Tooltip(msg("notification.dismiss")));
        dismissButton.setAccessibleText(msg("notification.dismiss"));
        // The shared close SVG ships a hard-coded stroke; lock the glyph to
        // the button's CSS-resolved -fx-text-fill (-ntf-text-mute, brightening
        // to -ntf-text-hi on :hover) so the dismiss affordance is neutral
        // chrome — never the SVG's colour — and follows a theme swap
        // (story 277). Same resolved-CSS discipline as the severity glyph;
        // never a hard-coded token mirror. (Review S1.)
        dismissButton.textFillProperty().addListener(
                (_, _, fill) -> tintIcon(closeIcon, fill));
        tintIcon(closeIcon, dismissButton.getTextFill());
        dismissButton.setOnAction(_ -> {
            if (onDismiss != null) {
                onDismiss.run();
            }
        });
        int slotIndex = contentRow.getChildren().indexOf(trailingSlot);
        if (slotIndex >= 0) {
            contentRow.getChildren().set(slotIndex, dismissButton);
        } else {
            contentRow.getChildren().add(dismissButton);
        }
    }

    /** @return the currently displayed level, or {@code null} if unset. */
    public NotificationLevel getCurrentLevel() {
        return currentLevel;
    }

    /** @return the current message text. */
    public String getMessage() {
        return messageLabel.getText();
    }

    /** @return the accent {@link Rectangle} — exposed for styling tests. */
    public Rectangle getAccentBar() {
        return accentBar;
    }

    /** @return the severity glyph node, or {@code null} — for styling tests. */
    public Node getSeverityGlyph() {
        return iconHolder.getGraphic();
    }

    /** @return the action button — exposed for tests / wiring. */
    public Button getActionButton() {
        return actionButton;
    }

    /** Removes all level style classes (resets the accent-bar fill to default). */
    public void clearLevelStyle() {
        for (NotificationLevel level : NotificationLevel.values()) {
            getStyleClass().remove(level.styleClass());
        }
        currentLevel = null;
    }

    /**
     * Re-tints the severity glyph to the accent bar's resolved CSS fill
     * so the two never diverge across levels or a theme swap (story 277).
     * No-op until both a glyph and a resolved {@link Color} fill exist.
     */
    private void retintGlyph(Paint barFill) {
        tintIcon(iconHolder.getGraphic(), barFill);
    }

    /**
     * Tints {@code icon} to {@code paint} when both an icon and a
     * resolved {@link Color} exist; a no-op otherwise. Locks both the
     * severity glyph (to the accent bar's resolved fill) and the dismiss
     * glyph (to the button's resolved {@code -fx-text-fill}) to a
     * CSS-resolved colour — never a hard-coded token mirror.
     */
    private static void tintIcon(Node icon, Paint paint) {
        if (icon != null && paint instanceof Color color) {
            tint(icon, color);
        }
    }

    /**
     * Tints every {@link Shape} in the icon group to {@code color},
     * preserving which shapes were stroked vs. filled. IconNode has no
     * built-in tint API so we walk the returned group.
     */
    private static void tint(Node iconNode, Color color) {
        if (iconNode instanceof Group group) {
            for (Node child : group.getChildren()) {
                tint(child, color);
            }
        } else if (iconNode instanceof Shape shape) {
            if (shape.getStroke() != null) {
                shape.setStroke(color);
            }
            if (shape.getFill() != null && !Color.TRANSPARENT.equals(shape.getFill())) {
                shape.setFill(color);
            }
        }
    }

    private static String localizedLevelName(NotificationLevel level) {
        return switch (level) {
            case SUCCESS -> msg("notification.level.success");
            case INFO    -> msg("notification.level.info");
            case WARNING -> msg("notification.level.warning");
            case ERROR   -> msg("notification.level.error");
        };
    }

    /**
     * Resolves a chrome string from the shared {@link ResourceBundle},
     * falling back to the raw key if absent (mirrors
     * {@code InspectorDrawer#msg}).
     */
    static String msg(String key) {
        try {
            return MESSAGES.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }
}
