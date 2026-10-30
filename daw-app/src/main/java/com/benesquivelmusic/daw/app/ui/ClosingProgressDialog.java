package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dialogs.DawgDialog;
import com.benesquivelmusic.daw.app.ui.dialogs.DialogDismissibility;

import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import java.util.Objects;
import java.util.Optional;

/**
 * The close protocol's modal progress surface (story 333; the studio
 * engineer's rulings R1, R8, Q4 and ruling 2 OQ-3; Failure Surfacing book
 * §2.4, §5.2): titled {@code Closing “<project>”}, one static step line —
 * {@code Finishing take…}, {@code Saving project…}, {@code Writing
 * checkpoint…}, {@code Closing journal…}, {@code Finishing disk writes…}
 * (the release's wait for every writer, the studio engineer's stage-6 S6),
 * {@code Finishing <door>…}, {@code Stopping audio…} — a note in the
 * warning colour after ten seconds (among them {@code close.note.release}
 * and the two {@code close.note.takenOver} notes of ruling 4 Q1), and the
 * content's own buttons: the keep button ({@code Don't quit} or
 * {@code Keep project open}), which has the focus while it can take it, and
 * the offer ({@code Quit anyway} or {@code Close anyway}) — neither ever the
 * default button, and the offer never given the focus by itself, so neither
 * Enter nor Space quits anyway (ruling R8; the studio engineer's stage-6
 * S2): while the keep button is disabled or hidden the step line, which is
 * focus-traversable, holds the focus. Application-modal and owned by the
 * main window; shown, never waited on.
 *
 * <p>Its body is a plain pane inside the {@link DawgDialog} (javafx skill
 * §3, "Custom controls: the Control + Skin pattern"), styled by role
 * classes over the theme's tokens — no inline style — and nothing in it
 * animates (§10, "Animations and transitions"). It follows the
 * {@code FirstRunWizardHost} idiom: its result converter answers nothing,
 * its buttons belong to the content and run their single-slot actions, and
 * a hidden cancel button ({@link DialogDismissibility#installHiddenCancel})
 * keeps Escape live. Escape, the hidden cancel, the window's close box and a
 * close nobody asked for run the keep action only while the keep button is
 * shown and enabled — so while the keep button is disabled after the
 * journal's detach, or hidden once the release began, they do nothing (a
 * temporary veto, FS book §5.2) — and are consumed
 * unless that action closed the surface: a close nobody asked for whose keep
 * ended the close is let through, so the surface never stays showing with
 * no action left (the stage-6 review N7). The protocol closes it itself
 * ({@link #closeByProtocol()}), which runs neither action.</p>
 */
final class ClosingProgressDialog extends DawgDialog<Void> implements ClosingProgress {

    private final Label step = new Label();
    private final Label note = new Label();
    private final Button keepButton = new Button();
    private final Button offerButton = new Button();
    private boolean closingByProtocol;
    private boolean keepEnabled = true;
    private boolean keepShown = true;
    private Runnable onKeep;
    private Runnable onOffer;
    private Window guardedWindow;

    /**
     * Creates the surface, owned by {@code owner} when there is one.
     *
     * @param owner the main window
     */
    ClosingProgressDialog(Optional<Window> owner) {
        Objects.requireNonNull(owner, "owner must not be null");
        initModality(Modality.APPLICATION_MODAL);
        owner.ifPresent(this::initOwner);

        step.getStyleClass().add("closing-progress-step");
        step.setWrapText(true);
        // First in the traversal order: every move of the focus the toolkit makes by itself — the keep button
        // disabled or hidden — lands here, never on Quit anyway, and a screen reader reads the step.
        step.setFocusTraversable(true);
        note.getStyleClass().add("closing-progress-note");
        note.setWrapText(true);
        offerButton.getStyleClass().addAll("dawg-button", "size-default", "secondary");
        offerButton.setDefaultButton(false);
        offerButton.setFocusTraversable(true);
        offerButton.setOnAction(_ -> offerPressed());
        keepButton.getStyleClass().addAll("dawg-button", "size-default", "secondary");
        keepButton.setDefaultButton(false);
        keepButton.setOnAction(_ -> keepRequested());
        HBox actions = new HBox(offerButton, keepButton);
        actions.getStyleClass().add("closing-progress-actions");
        VBox body = new VBox(step, note, actions);
        body.getStyleClass().add("closing-progress-body");
        getDialogPane().setContent(body);
        showNote("", Offer.NONE);

        Button hiddenCancel = DialogDismissibility.installHiddenCancel(this);
        hiddenCancel.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            keepRequested();
        });
        setResultConverter(_ -> null);
        // Escape reaches the dialog's close request (its stage closes the dialog on ESCAPE), as does a close()
        // nobody asked for: the keep button's action while it is shown and enabled, then vetoed — unless that
        // action closed the surface itself, whose own close() returned at once inside this close.
        setOnCloseRequest(event -> {
            if (closingByProtocol) {
                return;
            }
            keepRequested();
            if (!closingByProtocol) {
                event.consume();
            }
        });
        setOnShown(_ -> {
            guardTheWindowsCloseBox();
            holdTheFocusOffTheOffer();
        });
    }

    @Override
    public void open(String title, String stepLine, String keepLabel) {
        setTitle(Objects.requireNonNull(title, "title must not be null"));
        setHeaderText(title);
        step(stepLine);
        keepLabel(keepLabel);
        if (!isShowing() && !closingByProtocol) {
            show();
        }
    }

    @Override
    public void step(String stepLine) {
        step.setText(Objects.requireNonNull(stepLine, "stepLine must not be null"));
    }

    /**
     * {@inheritDoc} A note that newly asks the user for a decision — its
     * text changed to a non-empty one — brings a minimised main window back
     * to the front: a window owned by a minimised one is hidden with it, so
     * the ten-second decision would be invisible — the close prompts
     * restore their owner the same way (the studio engineer's stage-4 S5
     * and stage-7 N4). The same note again, as each refresh of the surface
     * shows it, restores nothing, so a window the user minimised again
     * stays minimised until the note asks something new (the studio
     * engineer's app-fix-2 review N2; the app-fix-2 review N6). An empty
     * note — DISPOSE clears it — restores nothing, so a short "Stopping
     * audio…" never brings the window back.
     */
    @Override
    public void note(String noteText, Offer offer) {
        Objects.requireNonNull(noteText, "noteText must not be null");
        Objects.requireNonNull(offer, "offer must not be null");
        // A decision newly asked: the note's text changed to a non-empty one. The same note again — a refresh
        // of the surface — restores nothing, so a window the user minimised again stays minimised.
        boolean newDecision = !noteText.isEmpty() && !noteText.equals(noteText());
        showNote(noteText, offer);
        if (newDecision && isShowing() && getOwner() instanceof Stage owner) {
            if (owner.isIconified()) {
                owner.setIconified(false);
            }
            owner.toFront();
        }
    }

    @Override
    public void keepLabel(String label) {
        keepButton.setText(Objects.requireNonNull(label, "label must not be null"));
    }

    @Override
    public void keepEnabled(boolean enabled) {
        keepEnabled = enabled;
        keepButton.setDisable(!enabled);
        holdTheFocusOffTheOffer();
    }

    @Override
    public void keepShown(boolean shown) {
        keepShown = shown;
        keepButton.setVisible(shown);
        keepButton.setManaged(shown);
        holdTheFocusOffTheOffer();
    }

    @Override
    public void onKeep(Runnable action) {
        this.onKeep = Objects.requireNonNull(action, "action must not be null");
    }

    @Override
    public void onOffer(Runnable action) {
        this.onOffer = Objects.requireNonNull(action, "action must not be null");
    }

    @Override
    public void closeByProtocol() {
        closingByProtocol = true;
        onKeep = null;
        onOffer = null;
        if (isShowing()) {
            close();
        }
    }

    @Override
    public boolean isOpen() {
        return isShowing();
    }

    @Override
    public boolean owns(Window window) {
        return window != null && getDialogPane().getScene() != null
                && window == getDialogPane().getScene().getWindow();
    }

    /** The step line shown. */
    String stepText() {
        return step.getText();
    }

    /** The note shown, empty while none is. */
    String noteText() {
        return note.isVisible() ? note.getText() : "";
    }

    /** The content's keep button. */
    Button keepButton() {
        return keepButton;
    }

    /** The content's offer button, {@code Quit anyway} or {@code Close anyway}. */
    Button offerButton() {
        return offerButton;
    }

    private void showNote(String noteText, Offer offer) {
        boolean noted = !noteText.isEmpty();
        note.setText(noteText);
        note.setVisible(noted);
        note.setManaged(noted);
        boolean offered = offer != Offer.NONE;
        offerButton.setText(switch (offer) {
            case NONE -> "";
            case QUIT_ANYWAY -> ProjectCloseText.text("close.quitAnyway");
            case CLOSE_ANYWAY -> ProjectCloseText.text("close.closeAnyway");
        });
        offerButton.setVisible(offered);
        offerButton.setManaged(offered);
    }

    /**
     * The window's close box reaches the dialog's own stage first, which
     * hides itself on a close request nobody consumed — whatever the
     * dialog's close request answered — so the stage consumes every close
     * request and treats it as the keep button (the FirstRunWizardHost
     * idiom). The filter lives on the dialog's own stage, so it ends with it.
     */
    private void guardTheWindowsCloseBox() {
        Window window = getDialogPane().getScene() == null ? null : getDialogPane().getScene().getWindow();
        if (window == null || window == guardedWindow) {
            return;
        }
        guardedWindow = window;
        window.addEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, event -> {
            if (closingByProtocol) {
                return;
            }
            event.consume();
            keepRequested();
        });
    }

    /**
     * Enter and Space never press Quit anyway by themselves (ruling R8; the
     * studio engineer's stage-6 S2): while the keep button cannot hold the
     * focus — disabled after the detach, hidden once the release began — the
     * step line holds it, and the keep button takes it back once it can. A
     * Tab to Quit anyway is the user's own, and stays. FX thread.
     */
    private void holdTheFocusOffTheOffer() {
        Scene scene = getDialogPane().getScene();
        Node owner = scene == null ? null : scene.getFocusOwner();
        if (keepShown && keepEnabled) {
            if (owner == null || owner == step) {
                keepButton.requestFocus();
            }
        } else if (owner == null || owner == keepButton) {
            step.requestFocus();
        }
    }

    /** The keep button, Escape or the close box: the keep action, while the keep button is shown and enabled. */
    private void keepRequested() {
        if (closingByProtocol || !keepEnabled || !keepShown) {
            return;
        }
        Runnable keep = onKeep;
        if (keep != null) {
            keep.run();
        }
    }

    private void offerPressed() {
        if (closingByProtocol || !offerButton.isVisible()) {
            return;
        }
        Runnable offer = onOffer;
        if (offer != null) {
            offer.run();
        }
    }
}
