package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dialogs.DawgDialog;

import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, the close protocol's progress surface ({@link ClosingProgressDialog};
 * the studio engineer's rulings R8, Q4, OQ-3; Failure Surfacing book §2.4,
 * §5.2; the FirstRunWizardHost idiom): a top-level {@link DawgDialog} whose
 * body is a plain pane with a static step line — no animation, no inline
 * style — and the content's own buttons. Escape and the window's close box
 * run the keep action exactly once while the keep button is shown and
 * enabled, and nothing while it is disabled or hidden — a close nobody asked
 * for is vetoed — and the protocol's own close runs neither action. Neither
 * button is ever the default; the keep button has the focus. The offer reads
 * Quit anyway or Close anyway.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ClosingProgressDialogTest {

    @Test
    void escapeAndTheCloseBoxRunTheKeepActionOnceWhileItIsShownAndEnabled() throws Exception {
        AtomicInteger keeps = new AtomicInteger();
        ClosingProgressDialog dialog = shown(keeps, new AtomicInteger());

        runOnFx(() -> escape(dialog));
        assertThat(keeps).hasValue(1);
        runOnFx(() -> windowOf(dialog).fireEvent(new WindowEvent(windowOf(dialog), WindowEvent.WINDOW_CLOSE_REQUEST)));
        assertThat(keeps).hasValue(2);
        assertThat(onFx(dialog::isOpen)).as("the keep action decides; the surface is never dismissed by itself")
                .isTrue();
        runOnFx(dialog::closeByProtocol);
    }

    @Test
    void nothingRunsWhileTheKeepButtonIsDisabledOrHiddenAndAStrayCloseIsVetoed() throws Exception {
        AtomicInteger keeps = new AtomicInteger();
        ClosingProgressDialog dialog = shown(keeps, new AtomicInteger());

        runOnFx(() -> dialog.keepEnabled(false));
        runOnFx(() -> escape(dialog));
        runOnFx(() -> windowOf(dialog).fireEvent(new WindowEvent(windowOf(dialog), WindowEvent.WINDOW_CLOSE_REQUEST)));
        runOnFx(() -> dialog.keepButton().fire());
        runOnFx(() -> dialog.keepEnabled(true));
        runOnFx(() -> dialog.keepShown(false));
        runOnFx(() -> escape(dialog));
        runOnFx(dialog::close);

        assertThat(keeps).as("a temporary veto: the detach's ten seconds, and after the release").hasValue(0);
        assertThat(onFx(dialog::isOpen)).isTrue();
        runOnFx(dialog::closeByProtocol);
    }

    @Test
    void theProtocolsCloseRunsNeitherAction() throws Exception {
        AtomicInteger keeps = new AtomicInteger();
        AtomicInteger offers = new AtomicInteger();
        ClosingProgressDialog dialog = shown(keeps, offers);

        runOnFx(dialog::closeByProtocol);

        assertThat(onFx(dialog::isOpen)).isFalse();
        assertThat(keeps).hasValue(0);
        assertThat(offers).hasValue(0);
    }

    @Test
    void neitherButtonIsTheDefaultTheKeepButtonHasTheFocusAndTheOfferNamesItself() throws Exception {
        AtomicInteger offers = new AtomicInteger();
        ClosingProgressDialog dialog = shown(new AtomicInteger(), offers);
        assertThat(onFx(() -> dialog.offerButton().isVisible())).isFalse();

        runOnFx(() -> dialog.note("the note", ClosingProgress.Offer.QUIT_ANYWAY));
        assertThat(onFx(dialog::noteText)).isEqualTo("the note");
        assertThat(onFx(() -> dialog.offerButton().getText())).isEqualTo("Quit anyway");
        assertThat(onFx(() -> dialog.offerButton().isDefaultButton())).isFalse();
        assertThat(onFx(() -> dialog.keepButton().isDefaultButton())).isFalse();
        assertThat(onFx(() -> dialog.getDialogPane().getScene().getFocusOwner())).isSameAs(onFx(dialog::keepButton));
        runOnFx(() -> dialog.offerButton().fire());
        assertThat(offers).hasValue(1);

        runOnFx(() -> dialog.note("", ClosingProgress.Offer.CLOSE_ANYWAY));
        assertThat(onFx(() -> dialog.offerButton().getText())).isEqualTo("Close anyway");
        assertThat(onFx(dialog::noteText)).isEmpty();
        runOnFx(() -> dialog.note("", ClosingProgress.Offer.NONE));
        assertThat(onFx(() -> dialog.offerButton().isVisible())).isFalse();
        assertThat(onFx(() -> dialog.getDialogPane().lookup(".closing-progress-note"))).isNotNull();
        assertThat(onFx(() -> dialog.owns(windowOf(dialog)))).isTrue();
        runOnFx(dialog::closeByProtocol);
    }

    /**
     * The stage-6 review N7: a close nobody asked for, while the keep button
     * is enabled, runs the keep action — which, as the coordinator's does,
     * closes the surface by the protocol, whose own close() returns at once
     * inside this close — and is then let through: the surface never stays
     * showing with no action left.
     */
    @Test
    void aStrayCloseWhoseKeepEndedTheCloseClosesTheSurface() throws Exception {
        AtomicInteger keeps = new AtomicInteger();
        ClosingProgressDialog dialog = onFx(() -> {
            ClosingProgressDialog created = new ClosingProgressDialog(Optional.empty());
            created.onKeep(() -> {
                keeps.incrementAndGet();
                created.closeByProtocol();
            });
            created.open("Closing “Song A”", "Saving project…", "Don't quit");
            return created;
        });

        runOnFx(dialog::close);

        assertThat(keeps).hasValue(1);
        assertThat(onFx(dialog::isOpen)).as("not stranded showing with both actions cleared").isFalse();
    }

    /**
     * The studio engineer's stage-6 S2: while the keep button cannot hold the
     * focus — disabled, or hidden — the toolkit never moves it onto Quit
     * anyway, so Enter and Space press nothing; the keep button takes it back
     * once it can. Also when the surface is first shown with the keep button
     * already disabled.
     */
    @Test
    void theFocusNeverLandsOnQuitAnywayAndComesBackToTheKeepButton() throws Exception {
        for (String unusable : List.of("disabled", "hidden")) {
            AtomicInteger offers = new AtomicInteger();
            AtomicInteger keeps = new AtomicInteger();
            ClosingProgressDialog dialog = shown(keeps, offers);
            runOnFx(() -> dialog.note("the note", ClosingProgress.Offer.QUIT_ANYWAY));
            runOnFx(() -> {
                if (unusable.equals("disabled")) {
                    dialog.keepEnabled(false);
                } else {
                    dialog.keepShown(false);
                }
            });
            awaitPulses(3);

            assertThat(onFx(() -> dialog.getDialogPane().getScene().getFocusOwner())).as("keep %s", unusable)
                    .isNotSameAs(onFx(dialog::offerButton));
            pressOnTheFocusOwner(dialog, KeyCode.ENTER);
            pressOnTheFocusOwner(dialog, KeyCode.SPACE);
            assertThat(offers).as("Enter and Space ran no Quit anyway (keep %s)", unusable).hasValue(0);

            runOnFx(() -> {
                dialog.keepShown(true);
                dialog.keepEnabled(true);
            });
            assertThat(onFx(() -> dialog.getDialogPane().getScene().getFocusOwner()))
                    .as("the keep button takes the focus back (keep %s)", unusable).isSameAs(onFx(dialog::keepButton));
            runOnFx(dialog::closeByProtocol);
        }

        AtomicInteger offers = new AtomicInteger();
        ClosingProgressDialog dialog = onFx(() -> {
            ClosingProgressDialog created = new ClosingProgressDialog(Optional.empty());
            created.onKeep(() -> { });
            created.onOffer(offers::incrementAndGet);
            created.keepEnabled(false);
            created.note("the note", ClosingProgress.Offer.QUIT_ANYWAY);
            created.open("Closing “Song A”", "Writing checkpoint…", "Don't quit");
            return created;
        });
        awaitPulses(3);
        assertThat(onFx(() -> dialog.getDialogPane().getScene().getFocusOwner()))
                .as("first shown with the keep button disabled").isNotSameAs(onFx(dialog::offerButton));
        pressOnTheFocusOwner(dialog, KeyCode.ENTER);
        assertThat(offers).hasValue(0);
        runOnFx(dialog::closeByProtocol);
    }

    @Test
    void itIsATopLevelDawgDialogWithNoInlineStyleAndNoEndlessAnimation() throws Exception {
        assertThat(DawgDialog.class).isAssignableFrom(ClosingProgressDialog.class);
        assertThat(ClosingProgressDialog.class.getEnclosingClass()).as("EveryDialogConformsTest scans top-level"
                + " classes").isNull();
        String code = SourceScanSupport.stripComments(Files.readString(SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/ClosingProgressDialog.java"),
                StandardCharsets.UTF_8));
        assertThat(code).doesNotContain("setStyle(").doesNotContain("ProgressIndicator")
                .doesNotContain("Timeline").doesNotContain("Transition");
    }

    private static ClosingProgressDialog shown(AtomicInteger keeps, AtomicInteger offers) throws Exception {
        return onFx(() -> {
            ClosingProgressDialog dialog = new ClosingProgressDialog(Optional.empty());
            dialog.onKeep(keeps::incrementAndGet);
            dialog.onOffer(offers::incrementAndGet);
            dialog.open("Closing “Song A”", "Saving project…", "Don't quit");
            return dialog;
        });
    }

    private static Window windowOf(ClosingProgressDialog dialog) {
        return dialog.getDialogPane().getScene().getWindow();
    }

    /** Presses and releases {@code key} on the focus owner, or the pane when there is none. */
    private static void pressOnTheFocusOwner(ClosingProgressDialog dialog, KeyCode key) throws Exception {
        runOnFx(() -> {
            Node focused = dialog.getDialogPane().getScene().getFocusOwner();
            Node target = focused != null ? focused : dialog.getDialogPane();
            target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key, false, false, false, false));
            target.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, "", "", key, false, false, false, false));
        });
    }

    /**
     * The studio engineer's stage-7 N4: a note that asks the user for a
     * decision brings a minimised main window back — its owned surface would
     * be hidden with it — and an empty note, DISPOSE's, does not.
     */
    @Test
    void aNoteThatAsksForADecisionBringsAMinimisedOwnerBackAndAnEmptyNoteDoesNot() throws Exception {
        javafx.stage.Stage owner = onFx(() -> {
            javafx.stage.Stage stage = new javafx.stage.Stage();
            stage.setScene(new javafx.scene.Scene(new javafx.scene.layout.StackPane(), 200, 120));
            stage.show();
            return stage;
        });
        try {
            ClosingProgressDialog dialog = onFx(() -> {
                ClosingProgressDialog surface = new ClosingProgressDialog(Optional.of(owner));
                surface.open("Closing “Song A”", "Finishing disk writes…", "Don't quit");
                return surface;
            });
            runOnFx(() -> owner.setIconified(true));
            org.junit.jupiter.api.Assumptions.assumeTrue(onFx(owner::isIconified),
                    "the headless toolkit keeps a stage minimised");

            runOnFx(() -> dialog.note("", ClosingProgress.Offer.NONE));
            assertThat(onFx(owner::isIconified)).as("an empty note restores nothing").isTrue();
            runOnFx(() -> dialog.note("A decision is asked now.", ClosingProgress.Offer.QUIT_ANYWAY));
            assertThat(onFx(owner::isIconified)).as("a decision brings the main window back").isFalse();
            runOnFx(dialog::closeByProtocol);
        } finally {
            runOnFx(owner::hide);
        }
    }

    /**
     * The studio engineer's app-fix-2 review N2, in a test of its own so a
     * toolkit that does not keep a stage minimised skips only this half (the
     * app-fix-3 review N5): the same note again — a refresh — leaves a
     * minimised main window minimised; a note that asks something new brings
     * it back.
     */
    @Test
    void aRefreshOfTheSameNoteLeavesAMinimisedOwnerMinimisedAndANewDecisionBringsItBack() throws Exception {
        javafx.stage.Stage owner = onFx(() -> {
            javafx.stage.Stage stage = new javafx.stage.Stage();
            stage.setScene(new javafx.scene.Scene(new javafx.scene.layout.StackPane(), 200, 120));
            stage.show();
            return stage;
        });
        try {
            ClosingProgressDialog dialog = onFx(() -> {
                ClosingProgressDialog surface = new ClosingProgressDialog(Optional.of(owner));
                surface.open("Closing “Song A”", "Finishing disk writes…", "Don't quit");
                surface.note("A decision is asked now.", ClosingProgress.Offer.QUIT_ANYWAY);
                return surface;
            });
            runOnFx(() -> owner.setIconified(true));
            org.junit.jupiter.api.Assumptions.assumeTrue(onFx(owner::isIconified),
                    "the headless toolkit keeps a stage minimised");

            runOnFx(() -> dialog.note("A decision is asked now.", ClosingProgress.Offer.QUIT_ANYWAY));
            assertThat(onFx(owner::isIconified)).as("a refresh of the same note leaves the window minimised")
                    .isTrue();
            runOnFx(() -> dialog.note("Another decision is asked now.", ClosingProgress.Offer.QUIT_ANYWAY));
            assertThat(onFx(owner::isIconified)).as("a note that asks something new brings it back").isFalse();
            runOnFx(dialog::closeByProtocol);
        } finally {
            runOnFx(owner::hide);
        }
    }

    /**
     * The studio engineer's ruling on app fix pass 2's Java items, §2 (the
     * app-fix-2 review N10; javafx skill §14, keyboard navigability): the
     * step line, which holds the parked focus, shows the house 1 px focus
     * ring when focused — the theme's {@code -focus-ring} token, as a probe
     * resolves it — and the ring moves nothing: the resting border is
     * transparent and of the same width.
     */
    @Test
    void theParkedFocusOnTheStepLineShowsTheFocusRingAndMovesNothing() throws Exception {
        Object[] resolved = onFx(() -> {
            FocusableLabel step = new FocusableLabel("Finishing disk writes…");
            step.getStyleClass().add("closing-progress-step");
            javafx.scene.layout.Region probe = new javafx.scene.layout.Region();
            probe.setStyle("-fx-border-color: -focus-ring; -fx-border-width: 1;");
            javafx.scene.layout.HBox root = new javafx.scene.layout.HBox(step, probe);
            root.getStyleClass().add("root-pane");
            javafx.scene.Scene scene = new javafx.scene.Scene(root, 400, 60);
            com.benesquivelmusic.daw.app.ui.theme.ThemeManager.getDefault().applyTo(scene);
            root.applyCss();
            javafx.geometry.Insets resting = step.getInsets();
            javafx.scene.paint.Paint restingPaint = topStroke(step);
            step.focused(true);
            root.applyCss();
            return new Object[] {resting, restingPaint, step.getInsets(), topStroke(step), topStroke(probe)};
        });

        assertThat(resolved[1]).as("at rest the border is transparent").isEqualTo(javafx.scene.paint.Color.TRANSPARENT);
        assertThat(resolved[3]).as("focused, the house focus ring").isEqualTo(resolved[4])
                .isNotEqualTo(javafx.scene.paint.Color.TRANSPARENT);
        assertThat(resolved[2]).as("the ring moves nothing").isEqualTo(resolved[0]);
    }

    /** A label whose {@code :focused} pseudo-class a test sets, as the focus parked on it would. */
    private static final class FocusableLabel extends javafx.scene.control.Label {
        private static final javafx.css.PseudoClass FOCUSED = javafx.css.PseudoClass.getPseudoClass("focused");

        FocusableLabel(String text) {
            super(text);
        }

        void focused(boolean on) {
            pseudoClassStateChanged(FOCUSED, on);
        }
    }

    /** The top stroke's paint of {@code region}'s border, or {@code null} when it has none. */
    private static javafx.scene.paint.Paint topStroke(javafx.scene.layout.Region region) {
        javafx.scene.layout.Border border = region.getBorder();
        return border == null || border.getStrokes().isEmpty() ? null : border.getStrokes().getFirst().getTopStroke();
    }

    /** Waits, bounded, until the toolkit ran {@code pulses} more pulses (an animation timer counts them). */
    private static void awaitPulses(int pulses) throws Exception {
        java.util.concurrent.CountDownLatch counted = new java.util.concurrent.CountDownLatch(pulses);
        javafx.animation.AnimationTimer timer = onFx(() -> {
            javafx.animation.AnimationTimer counting = new javafx.animation.AnimationTimer() {
                @Override
                public void handle(long now) {
                    counted.countDown();
                }
            };
            counting.start();
            return counting;
        });
        try {
            assertThat(counted.await(SaveFlowFixture.GUARD.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
                    .as("%d pulses within %s", pulses, SaveFlowFixture.GUARD).isTrue();
        } finally {
            runOnFx(timer::stop);
        }
    }

    private static void escape(ClosingProgressDialog dialog) {
        Node target = dialog.getDialogPane();
        Window window = windowOf(dialog);
        window.fireEvent(new KeyEvent(target, target, KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false,
                false, false));
    }
}
