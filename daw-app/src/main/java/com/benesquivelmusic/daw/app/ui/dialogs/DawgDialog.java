package com.benesquivelmusic.daw.app.ui.dialogs;

import com.benesquivelmusic.daw.app.ui.DarkThemeHelper;
import com.benesquivelmusic.daw.app.ui.icons.DawgIcon;

import javafx.beans.InvalidationListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;

/**
 * Shared dialog skeleton implementing the UI Design Book §5.9
 * ("Dialog / modal") chrome contract (story 276).
 *
 * <p>{@code DawgDialog} is a thin composite wrapper around JavaFX's
 * {@link Dialog} — <em>not</em> a Control/Skin (Skill §3: a one-off
 * composite that wraps {@code Dialog<R>} stays a plain wrapper). It
 * normalises the chrome so individual dialogs cannot drift again:</p>
 *
 * <ul>
 *   <li><strong>Flat {@code -surface-1} header</strong> carrying the
 *       title as an H1 (18 px / 600, no decoration — §3.2 / §3.4
 *       elevation 0). The gradient header is gone.</li>
 *   <li><strong>One-column body</strong> with 24 px padding (§3.3 xl)
 *       and §3.3 spacing; sections are introduced by tokenized
 *       label-small headers (§3.2: {@code -text-mute}, 10 px, 600,
 *       caller passes an already-uppercased string — mirrors the
 *       inspector convention, not a forked one).</li>
 *   <li><strong>Footer</strong> with the secondary (text) button on the
 *       left and the accent-filled primary button on the right
 *       ({@code .dawg-button.size-default.primary}).</li>
 *   <li>The header close glyph is <em>secondary</em>: it is dropped when
 *       a Cancel/secondary button is present (Cancel + Esc are the
 *       primary dismiss paths, §5.9) and kept only for informational
 *       dialogs that have no Cancel.</li>
 * </ul>
 *
 * <p>Existing dialogs adopt the chrome by changing
 * {@code extends Dialog<R>} to {@code extends DawgDialog<R>}; their
 * <em>contents</em> (forms, tabs, fields) are preserved untouched. The
 * fluent {@link #sized}/{@link #addSection}/{@link #primary}/{@link
 * #secondary} API is available for new dialogs and simple
 * confirmations.</p>
 *
 * <h2>JavaFX-specific quirk</h2>
 *
 * <p>JavaFX's {@link javafx.scene.control.Alert Alert} paints its own
 * header gradient and graphic that bypass the {@code .dialog-pane}
 * author stylesheet. The static {@link #confirm}/{@link #info}/{@link
 * #warn}/{@link #error} factories therefore build a real
 * {@code DawgDialog} (never an {@code Alert}) so transient
 * confirmation / information / error dialogs get the same flat §5.9
 * chrome as every other dialog.</p>
 *
 * @param <R> the dialog result type
 * @see LegacyDialog
 */
public class DawgDialog<R> extends Dialog<R> {

    /** Resource bundle for chrome strings (Skill §14) — {@link Locale#ROOT}. */
    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);

    /** §3.3 xl — 24 px padding around the dialog body. */
    private static final double BODY_PADDING = 24;
    /** §3.3 md — vertical rhythm between sections / section header and body. */
    private static final double BODY_SPACING = 12;
    /** §5.9 — the header close glyph is a 16 px secondary icon. */
    private static final DawgIcon.Size CLOSE_GLYPH_SIZE = DawgIcon.Size.SIZE_16;

    /** Centred-modal widths from UI Design Book §5.9. */
    public enum Size {
        /** 480 px — short confirmations. */
        SMALL(480),
        /** 640 px — most settings dialogs (default). */
        MEDIUM(640),
        /** 800 px — content-heavy dialogs (plugin manager, exports). */
        LARGE(800);

        private final double px;

        Size(double px) {
            this.px = px;
        }

        /** The modal width in pixels. */
        public double pixels() {
            return px;
        }
    }

    /** Single-column body container; sections are appended in order. */
    private final VBox body = new VBox(BODY_SPACING);

    private ButtonType primaryType;
    private ButtonType secondaryType;
    private Runnable primaryAction;
    private Runnable secondaryAction;

    /**
     * The §5.9 secondary close glyph, while installed. Held by reference
     * so retraction never stomps a domain header graphic a subclass set
     * via {@link #setGraphic(Node)} itself ({@code null} when no glyph of
     * ours is currently the header graphic).
     */
    private DawgIcon closeGlyph;

    /**
     * Creates an empty {@code DawgDialog} with the §5.9 chrome applied.
     * Subclasses set the title via {@link #setTitle(String)} /
     * {@link #setHeaderText(String)}, populate
     * {@link DialogPane#setContent(Node)} (or use {@link #addSection}),
     * and wire {@link ButtonType}s as before — the chrome is already in
     * place.
     */
    public DawgDialog() {
        body.getStyleClass().add("dawg-dialog-body");
        body.setPadding(new Insets(BODY_PADDING));
        applyChrome();
        // Subclasses populate getButtonTypes() in their constructor body,
        // which runs AFTER this super-constructor. Re-evaluate the §5.9
        // close-glyph rule whenever the footer changes so a Cancel/Close
        // added later still retracts the (now redundant) header glyph.
        getDialogPane().getButtonTypes().addListener(
                (InvalidationListener) o -> installCloseGlyphIfAppropriate());
    }

    // ── Fluent API ───────────────────────────────────────────────────────────

    /**
     * Sets the modal width to one of the §5.9 sizes (480 / 640 / 800).
     *
     * @param size the modal width band; must not be {@code null}
     * @return this dialog, for chaining
     */
    public DawgDialog<R> sized(Size size) {
        Objects.requireNonNull(size, "size must not be null");
        getDialogPane().setPrefWidth(size.pixels());
        return this;
    }

    /**
     * Appends a section to the one-column body: a tokenized label-small
     * header (§3.2 — {@code -text-mute}, 10 px, 600) followed by the
     * section body. The header text is upper-cased to realise the §3.2
     * "uppercase" rule the same way the inspector does (JavaFX CSS has
     * no {@code -fx-text-transform}; tracking is not expressible and is
     * dropped). The body {@link VBox} is installed as the dialog-pane
     * content the first time this is called.
     *
     * @param header the section header (rendered upper-cased); must not be {@code null}
     * @param sectionBody the section body node; must not be {@code null}
     * @return this dialog, for chaining
     */
    public DawgDialog<R> addSection(String header, Node sectionBody) {
        Objects.requireNonNull(header, "header must not be null");
        Objects.requireNonNull(sectionBody, "sectionBody must not be null");
        Label headerLabel = new Label(sectionHeaderText(header));
        headerLabel.getStyleClass().add("dawg-dialog-section-header");
        body.getChildren().addAll(headerLabel, sectionBody);
        if (getDialogPane().getContent() != body) {
            getDialogPane().setContent(body);
        }
        return this;
    }

    /**
     * Declares the primary (accent-filled) footer action. The button
     * carries {@code dawg-button size-default primary} and is placed on
     * the footer's right per §5.9. The {@code action} runs from the
     * dialog's result converter when the primary button is pressed.
     *
     * @param label the button label; must not be {@code null}
     * @param action the action to run on press, or {@code null} for none
     * @return this dialog, for chaining
     */
    public DawgDialog<R> primary(String label, Runnable action) {
        Objects.requireNonNull(label, "label must not be null");
        this.primaryType = new ButtonType(label, ButtonBar.ButtonData.OK_DONE);
        this.primaryAction = action;
        rebuildButtons();
        return this;
    }

    /**
     * Declares the secondary (neutral text, no border) footer action,
     * placed on the footer's left per §5.9. When a secondary button is
     * present the header close glyph is dropped (Cancel + Esc are the
     * primary dismiss paths, §5.9).
     *
     * @param label the button label; must not be {@code null}
     * @param action the action to run on press, or {@code null} for none
     * @return this dialog, for chaining
     */
    public DawgDialog<R> secondary(String label, Runnable action) {
        Objects.requireNonNull(label, "label must not be null");
        this.secondaryType = new ButtonType(label, ButtonBar.ButtonData.CANCEL_CLOSE);
        this.secondaryAction = action;
        rebuildButtons();
        return this;
    }

    // ── Chrome ───────────────────────────────────────────────────────────────

    /**
     * Applies the §5.9 chrome: the dark theme stylesheet + the
     * {@code dawg-dialog} / header / footer style classes, and a
     * secondary close glyph for informational dialogs (dropped once a
     * secondary button is added). Idempotent.
     */
    private void applyChrome() {
        DarkThemeHelper.applyTo(this);
        DialogPane pane = getDialogPane();
        addStyleClassOnce(pane, "dawg-dialog");
        installCloseGlyphIfAppropriate();
    }

    /**
     * Applies the §5.9 header close-glyph rule.
     *
     * <p>The glyph is a {@link DawgIcon} ({@code x}) whose tint derives
     * from the resolved CSS {@code -fx-icon-color} of {@code .dawg-dialog}
     * (project rule: tint glyphs from resolved CSS, never a hard-coded hex
     * mirror — {@code DawgIcon} reads {@code -fx-icon-color} via its
     * {@code CssMetaData}, so placing it under the styled dialog-pane is
     * sufficient and deterministic). It is <em>functional</em>: clicking
     * it dismisses the dialog (§5.9 — the close glyph is a secondary
     * dismiss path, with Cancel and Esc primary).</p>
     *
     * <p>It is shown only for informational dialogs that have <em>no
     * footer dismiss button</em>. As soon as the fluent {@code secondary()}
     * is used or the dialog pane gains any Cancel/Close button, the footer
     * becomes the dismiss path and the header glyph is retracted (§5.9 —
     * "Cancel and Esc are the primary dismiss paths"). Retraction only
     * clears the header graphic when it is still <em>our</em> glyph, so a
     * domain header graphic a subclass set via {@link #setGraphic(Node)}
     * is never stomped; conversely the glyph is never installed over a
     * graphic the subclass already set. Idempotent.</p>
     */
    private void installCloseGlyphIfAppropriate() {
        boolean hasFooterDismissPath = secondaryType != null || paneHasCancelButton();
        if (hasFooterDismissPath) {
            if (closeGlyph != null) {
                if (getGraphic() == closeGlyph) {
                    setGraphic(null);
                }
                closeGlyph = null;
            }
            return;
        }
        if (closeGlyph != null || getGraphic() != null) {
            return;
        }
        DawgIcon glyph = DawgIcon.of("x", CLOSE_GLYPH_SIZE);
        glyph.getStyleClass().add("dawg-dialog-close");
        glyph.setAccessibleText(msg("dialog.close"));
        glyph.setOnMouseClicked(e -> close());
        setGraphic(glyph);
        closeGlyph = glyph;
    }

    /**
     * @return {@code true} if the dialog pane carries any
     *         Cancel/Close-type button ({@link
     *         javafx.scene.control.ButtonBar.ButtonData#isCancelButton()})
     *         — i.e. the footer already offers a dismiss path, making the
     *         header glyph redundant per §5.9.
     */
    private boolean paneHasCancelButton() {
        for (ButtonType bt : getDialogPane().getButtonTypes()) {
            if (bt.getButtonData() != null && bt.getButtonData().isCancelButton()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rebuilds the footer button types in §5.9 order (secondary LEFT via
     * {@link ButtonBar.ButtonData#CANCEL_CLOSE}, primary RIGHT via
     * {@link ButtonBar.ButtonData#OK_DONE}), tags the rendered buttons
     * with the unified {@code dawg-button} classes, and installs a
     * result converter that runs the matching {@link Runnable}.
     */
    private void rebuildButtons() {
        DialogPane pane = getDialogPane();
        pane.getButtonTypes().clear();
        if (secondaryType != null) {
            pane.getButtonTypes().add(secondaryType);
        }
        if (primaryType != null) {
            pane.getButtonTypes().add(primaryType);
        }
        styleFooterButton(secondaryType, false);
        styleFooterButton(primaryType, true);
        installCloseGlyphIfAppropriate();

        setResultConverter(bt -> {
            if (bt == primaryType && primaryAction != null) {
                primaryAction.run();
            } else if (bt == secondaryType && secondaryAction != null) {
                secondaryAction.run();
            }
            return null;
        });
    }

    /**
     * Tags the rendered footer {@link Button} for {@code buttonType}
     * with the unified button classes. The primary button carries
     * {@code dawg-button size-default primary} (accent fill via
     * {@code .dawg-button.primary}); the secondary carries
     * {@code dawg-button size-default secondary} (neutral, borderless
     * text button per §5.9).
     */
    private void styleFooterButton(ButtonType buttonType, boolean isPrimary) {
        if (buttonType == null) {
            return;
        }
        Node node = getDialogPane().lookupButton(buttonType);
        if (node instanceof Button button) {
            button.getStyleClass().removeAll(
                    "primary", "secondary", "dawg-button", "size-default");
            button.getStyleClass().addAll("dawg-button", "size-default");
            button.getStyleClass().add(isPrimary ? "primary" : "secondary");
            button.setDefaultButton(isPrimary);
        }
    }

    // ── Static factories — Alert-style transient dialogs ─────────────────────

    /**
     * Builds a confirmation dialog with the §5.9 chrome (NOT a JavaFX
     * {@link javafx.scene.control.Alert Alert}, whose header gradient and
     * graphic bypass the author stylesheet). The result is the pressed
     * {@link ButtonType} so callers can branch on confirm vs. cancel.
     *
     * @param title the localized window/header title
     * @param message the localized confirmation message
     * @param confirmLabel the localized primary (confirm) button label
     * @return a styled confirmation dialog
     */
    public static DawgDialog<ButtonType> confirm(String title, String message,
                                                 String confirmLabel) {
        DawgDialog<ButtonType> dialog = messageDialog(title, message);
        ButtonType confirm = new ButtonType(confirmLabel, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(msg("dialog.cancel"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.secondaryType = cancel;
        dialog.primaryType = confirm;
        dialog.getDialogPane().getButtonTypes().setAll(cancel, confirm);
        dialog.styleFooterButton(cancel, false);
        dialog.styleFooterButton(confirm, true);
        dialog.installCloseGlyphIfAppropriate();
        dialog.setResultConverter(bt -> bt);
        return dialog;
    }

    /**
     * Builds an informational dialog with the §5.9 chrome. Keeps the
     * secondary close glyph (no Cancel — informational dialogs dismiss
     * via the close glyph or {@code OK}).
     *
     * @param title the localized window/header title
     * @param message the localized message
     * @return a styled informational dialog
     */
    public static DawgDialog<ButtonType> info(String title, String message) {
        return acknowledgeDialog(title, message);
    }

    /**
     * Builds a warning dialog with the §5.9 chrome. The chrome is
     * identical to {@link #info}; the semantic level is conveyed by the
     * caller-supplied content (§7.2 — one accent, no per-dialog hue).
     *
     * @param title the localized window/header title
     * @param message the localized warning message
     * @return a styled warning dialog
     */
    public static DawgDialog<ButtonType> warn(String title, String message) {
        return acknowledgeDialog(title, message);
    }

    /**
     * Builds an error dialog with the §5.9 chrome. Replaces ad-hoc
     * {@code new Alert(AlertType.ERROR)} usage so error dialogs get the
     * same flat header / accent button treatment.
     *
     * @param title the localized window/header title
     * @param message the localized error message
     * @return a styled error dialog
     */
    public static DawgDialog<ButtonType> error(String title, String message) {
        return acknowledgeDialog(title, message);
    }

    /**
     * Single-button acknowledge dialog ({@code OK}) that keeps the
     * secondary header close glyph (no Cancel present).
     */
    private static DawgDialog<ButtonType> acknowledgeDialog(String title, String message) {
        DawgDialog<ButtonType> dialog = messageDialog(title, message);
        ButtonType ok = new ButtonType(msg("dialog.ok"), ButtonBar.ButtonData.OK_DONE);
        dialog.primaryType = ok;
        dialog.getDialogPane().getButtonTypes().setAll(ok);
        dialog.styleFooterButton(ok, true);
        dialog.installCloseGlyphIfAppropriate();
        dialog.setResultConverter(bt -> bt);
        return dialog;
    }

    /** Shared skeleton for the static factories: title + wrapped message body. */
    private static DawgDialog<ButtonType> messageDialog(String title, String message) {
        DawgDialog<ButtonType> dialog = new DawgDialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(title);
        Label content = new Label(message);
        content.setWrapText(true);
        content.getStyleClass().add("dawg-dialog-message");
        VBox box = new VBox(BODY_SPACING, content);
        box.setPadding(new Insets(BODY_PADDING));
        box.setAlignment(Pos.TOP_LEFT);
        box.getStyleClass().add("dawg-dialog-body");
        dialog.getDialogPane().setContent(box);
        return dialog;
    }

    // ── Pure helpers (toolkit-free) ──────────────────────────────────────────

    /**
     * Resolves a chrome string from the shared {@link ResourceBundle},
     * falling back to the raw key if absent (mirrors
     * {@code BrowserPanel#msg(String)} / {@code NotificationPill#msg}).
     * Package-private so it is unit-testable without a toolkit.
     *
     * @param key the message key
     * @return the localized string, or {@code key} if not found
     */
    static String msg(String key) {
        try {
            return MESSAGES.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }

    /**
     * Realises the §3.2 "uppercase" label-small rule the same way the
     * inspector does — by upper-casing the caller string ({@link
     * Locale#ROOT}). JavaFX CSS has no {@code -fx-text-transform}; the
     * +12 % tracking is not expressible and is intentionally dropped.
     * Package-private + pure so it is unit-testable off-thread.
     *
     * @param header the caller-supplied section header
     * @return the header upper-cased, or {@code ""} if {@code null}
     */
    static String sectionHeaderText(String header) {
        return header == null ? "" : header.toUpperCase(Locale.ROOT);
    }

    private static void addStyleClassOnce(Node node, String styleClass) {
        if (!node.getStyleClass().contains(styleClass)) {
            node.getStyleClass().add(styleClass);
        }
    }
}
