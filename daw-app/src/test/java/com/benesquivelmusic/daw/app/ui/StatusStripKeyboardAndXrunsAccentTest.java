package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.status.EngineHealthStatus;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.app.ui.status.SessionStatusStrip;
import com.benesquivelmusic.daw.app.ui.status.StatusStripCell;

import javafx.event.Event;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The status strip's overflow from the keyboard (ruling E23) and the Xruns
 * cell while it carries the warning accent (ruling E103): the {@code ⋯}
 * button takes focus and opens the popover with Space, the popover's first
 * cell with an action has the focus and fires on Enter and Space, and Escape
 * closes it; Enter opens it too, and the button says what it does (ruling
 * E132); however the popover closes, the focus is never left on a node that
 * is not showing; an accented Xruns cell stays on the strip at every width,
 * the cells after it go one band earlier, and it collapses again once the
 * accent leaves.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class StatusStripKeyboardAndXrunsAccentTest {

    private static final Instant SINCE = Instant.parse("2026-11-16T09:30:00Z");

    @Test
    void theOverflowOpensFromTheKeyboardItsCellFiresOnEnterAndEscapeClosesIt() throws Exception {
        AtomicInteger logsOpened = new AtomicInteger();
        Object[] seen = onFx(() -> {
            EngineHealthStatus model = new EngineHealthStatus();
            SessionStatusStrip strip = new SessionStatusStrip(new ProjectOperationProgress(new FxDispatcher()),
                    MotionManager.getDefault(), model);
            strip.setOnXrunLogAction(logsOpened::incrementAndGet);
            Stage stage = new Stage();
            try {
                stage.setScene(new Scene(new VBox(strip), 1160, 120));
                stage.show();
                strip.applyCss();
                strip.layout();
                List<StatusStripCell> collapsed = strip.overflowCells();
                Node button = strip.overflowButton();
                boolean traversable = button.isFocusTraversable();
                button.requestFocus();
                press(button, KeyCode.SPACE);
                boolean opened = strip.isOverflowPopoverShowing();
                Node focused = strip.xrunCell().getScene().getFocusOwner();
                press(focused, KeyCode.ENTER);
                Event.fireEvent(focused, key(KeyEvent.KEY_PRESSED, KeyCode.ESCAPE));
                boolean closed = !strip.isOverflowPopoverShowing();
                return new Object[] {collapsed.contains(strip.xrunCell()), traversable, opened,
                        focused == strip.xrunCell(), closed, button.isFocused()};
            } finally {
                stage.hide();
            }
        });

        assertThat((Boolean) seen[0]).as("premise: the quiet Xruns cell is in the popover at 1160 px").isTrue();
        assertThat((Boolean) seen[1]).as("the ⋯ button is focus-traversable").isTrue();
        assertThat((Boolean) seen[2]).as("Space on the button opens the popover").isTrue();
        assertThat((Boolean) seen[3]).as("the popover's cell with an action has the focus").isTrue();
        assertThat(logsOpened).as("Enter fires it").hasValue(1);
        assertThat((Boolean) seen[4]).as("Escape closes the popover").isTrue();
        assertThat((Boolean) seen[5]).as("and gives the focus back to the button").isTrue();
    }

    @Test
    void anAccentedXrunsCellStaysOnTheStripAtEveryWidthAndOtherCellsGoFirst() throws Exception {
        Object[] seen = onFx(() -> {
            EngineHealthStatus model = new EngineHealthStatus();
            SessionStatusStrip strip = realized(model);
            model.setXrunFigures(accented());
            resizeTo(strip, 1160);
            List<StatusStripCell> at1160 = strip.overflowCells();
            resizeTo(strip, 850);
            List<StatusStripCell> at850 = strip.overflowCells();
            return new Object[] {strip, at1160, at850, strip.xrunCell().isManaged(), strip.xrunCell().getText(),
                    strip.xrunCell().getAccessibleText()};
        });
        SessionStatusStrip strip = (SessionStatusStrip) seen[0];

        assertThat(cells(seen[1])).as("at 1160 px Workspace goes, and Schema in the Xruns cell's place")
                .containsExactly(strip.workspaceCell(), strip.schemaCell());
        assertThat(cells(seen[2])).as("at 850 px every other collapsible goes first (ruling E103)")
                .containsExactly(strip.workspaceCell(), strip.schemaCell(), strip.lockCell(), strip.diskCell(),
                        strip.journalCell());
        assertThat((Boolean) seen[3]).as("the accented cell is on the strip").isTrue();
        assertThat((String) seen[5]).as("its spoken text stays whole").startsWith("Xruns: 4 since ");
    }

    @Test
    void theAccentBringsACollapsedXrunsCellBackAndItsLeavingLetsItCollapseAgain() throws Exception {
        Object[] seen = onFx(() -> {
            EngineHealthStatus model = new EngineHealthStatus();
            SessionStatusStrip strip = realized(model);
            resizeTo(strip, 1160);
            boolean quietCollapsed = strip.overflowCells().contains(strip.xrunCell());
            model.setTakeDropouts(Optional.of(new TakeDropouts(2, 0, 0, true)));
            boolean accentedCollapsed = strip.overflowCells().contains(strip.xrunCell());
            model.setTakeDropouts(Optional.empty());
            boolean quietAgainCollapsed = strip.overflowCells().contains(strip.xrunCell());
            return new Object[] {quietCollapsed, accentedCollapsed, quietAgainCollapsed};
        });

        assertThat((Boolean) seen[0]).as("quiet, it collapses as before").isTrue();
        assertThat((Boolean) seen[1]).as("a take's dropouts bring it back onto the strip").isFalse();
        assertThat((Boolean) seen[2]).as("quiet again, it may collapse").isTrue();
    }

    /**
     * Ruling E103, "other cells go into the overflow first": while the accent
     * keeps the Xruns cell on the strip, each later cell collapses one band
     * earlier; without it the bands are as they were. One width in each band,
     * the strip sized by its Scene.
     */
    @Test
    void whileTheAccentKeepsTheXrunsCellEachLaterCellCollapsesOneBandEarlier() throws Exception {
        Object[] seen = onFx(() -> {
            EngineHealthStatus quietModel = new EngineHealthStatus();
            SessionStatusStrip quiet = stripOf(quietModel);
            EngineHealthStatus accentedModel = new EngineHealthStatus();
            accentedModel.setXrunFigures(accented());
            SessionStatusStrip accentedStrip = stripOf(accentedModel);
            List<Object> rows = new java.util.ArrayList<>();
            for (int width : new int[] {1185, 1150, 1100, 1050, 990, 900}) {
                rows.add(width);
                rows.add(widthAndOverflow(quiet, width));
                rows.add(widthAndOverflow(accentedStrip, width));
            }
            return new Object[] {quiet, accentedStrip, rows};
        });
        SessionStatusStrip quiet = (SessionStatusStrip) seen[0];
        SessionStatusStrip accentedStrip = (SessionStatusStrip) seen[1];
        List<?> rows = (List<?>) seen[2];

        for (int row = 0; row < rows.size(); row += 3) {
            int width = (Integer) rows.get(row);
            List<?> quietRow = (List<?>) rows.get(row + 1);
            List<?> accentedRow = (List<?>) rows.get(row + 2);
            assertThat(quietRow.getFirst()).as("premise: the quiet strip is %d px wide", width).isEqualTo((double) width);
            assertThat(accentedRow.getFirst()).as("premise: the accented strip is %d px wide", width)
                    .isEqualTo((double) width);
            int band = row / 3;
            List<StatusStripCell> quietOrder = List.of(quiet.workspaceCell(), quiet.xrunCell(), quiet.schemaCell(),
                    quiet.lockCell(), quiet.diskCell(), quiet.journalCell());
            List<StatusStripCell> accentedOrder = List.of(accentedStrip.workspaceCell(), accentedStrip.schemaCell(),
                    accentedStrip.lockCell(), accentedStrip.diskCell(), accentedStrip.journalCell());
            assertThat(quietRow.get(1)).as("quiet at %d px: the bands as they were", width)
                    .isEqualTo(quietOrder.subList(0, band + 1));
            assertThat(accentedRow.get(1)).as("accented at %d px: each later cell one band earlier", width)
                    .isEqualTo(accentedOrder.subList(0, Math.min(band + 1, accentedOrder.size())));
        }
    }

    /**
     * The popover's focused cell acts on Enter and on Space: at 1100 px with
     * an accented Xruns cell the Schema cell is the popover's first cell with
     * an action.
     */
    @Test
    void thePopoversFocusedSchemaCellFiresOnEnterAndOnSpace() throws Exception {
        AtomicInteger schemaActions = new AtomicInteger();
        Object[] seen = onFx(() -> {
            EngineHealthStatus model = new EngineHealthStatus();
            model.setXrunFigures(accented());
            model.setTakeDropouts(Optional.of(new TakeDropouts(37, 0, 0, false)));
            SessionStatusStrip strip = new SessionStatusStrip(new ProjectOperationProgress(new FxDispatcher()),
                    MotionManager.getDefault(), model);
            strip.setOnSchemaAction(schemaActions::incrementAndGet);
            Stage stage = shownAt(strip, 1100);
            try {
                double width = strip.getWidth();
                Node button = strip.overflowButton();
                button.requestFocus();
                press(button, KeyCode.SPACE);
                Node focused = strip.schemaCell().getScene().getFocusOwner();
                press(focused, KeyCode.ENTER);
                int afterEnter = schemaActions.get();
                press(focused, KeyCode.SPACE);
                return new Object[] {width, focused == strip.schemaCell(), afterEnter, schemaActions.get()};
            } finally {
                stage.hide();
            }
        });

        assertThat((Double) seen[0]).as("premise: the strip is 1100 px wide").isEqualTo(1100.0);
        assertThat((Boolean) seen[1]).as("the popover's focus is on the Schema cell").isTrue();
        assertThat((Integer) seen[2]).as("Enter fires it").isEqualTo(1);
        assertThat((Integer) seen[3]).as("Space fires it").isEqualTo(2);
    }

    /** Ruling E132: the button's tooltip and spoken text, exactly; Enter opens the popover as Space does. */
    @Test
    void theOverflowButtonSaysWhatItDoesAndEnterOpensIt() throws Exception {
        Object[] seen = onFx(() -> {
            SessionStatusStrip strip = new SessionStatusStrip(new ProjectOperationProgress(new FxDispatcher()),
                    MotionManager.getDefault(), new EngineHealthStatus());
            Stage stage = shownAt(strip, 1100);
            try {
                Node button = strip.overflowButton();
                button.requestFocus();
                press(button, KeyCode.ENTER);
                return new Object[] {strip.overflowButton().getTooltip().getText(),
                        strip.overflowButton().getAccessibleText(), strip.isOverflowPopoverShowing()};
            } finally {
                stage.hide();
            }
        });

        assertThat((String) seen[0]).isEqualTo("More status");
        assertThat((String) seen[1]).isEqualTo("More status. Press to show the status cells that do not fit.");
        assertThat((Boolean) seen[2]).as("Enter opens the popover").isTrue();
    }

    /**
     * The strip widening to two rows while the popover is open hides the
     * button: the focus goes to the strip's first focus-traversable cell
     * that shows, never left on the hidden button.
     */
    @Test
    void wideningWhileThePopoverIsOpenMovesTheFocusToAShowingCell() throws Exception {
        Object[] seen = onFx(() -> {
            SessionStatusStrip strip = new SessionStatusStrip(new ProjectOperationProgress(new FxDispatcher()),
                    MotionManager.getDefault(), new EngineHealthStatus());
            Stage stage = shownAt(strip, 1100);
            try {
                Node button = strip.overflowButton();
                button.requestFocus();
                press(button, KeyCode.SPACE);
                boolean opened = strip.isOverflowPopoverShowing();
                Parent root = stage.getScene().getRoot();
                root.resize(1300, 120);
                root.layout();
                return new Object[] {opened, strip.getWidth(), strip.isOverflowPopoverShowing(),
                        stage.getScene().getFocusOwner(), button.isVisible(), strip.schemaCell()};
            } finally {
                stage.hide();
            }
        });

        assertThat((Boolean) seen[0]).as("premise: the popover was open").isTrue();
        assertThat((Double) seen[1]).as("premise: the strip widened to 1300 px").isEqualTo(1300.0);
        assertThat((Boolean) seen[2]).as("two rows: the popover closed").isFalse();
        assertThat((Boolean) seen[4]).as("premise: the button is hidden").isFalse();
        assertThat(seen[3]).as("the focus is on the first focus-traversable cell that shows").isSameAs(seen[5]);
    }

    /**
     * The accent arriving while the Xruns cell holds the open popover's focus
     * brings the cell back onto the strip: the focus follows it there and the
     * popover closes, never left apart from the button.
     */
    @Test
    void theAccentArrivingWhileTheXrunsCellIsFocusedInThePopoverTakesTheFocusAlong() throws Exception {
        Object[] seen = onFx(() -> {
            EngineHealthStatus model = new EngineHealthStatus();
            SessionStatusStrip strip = new SessionStatusStrip(new ProjectOperationProgress(new FxDispatcher()),
                    MotionManager.getDefault(), model);
            strip.setOnXrunLogAction(() -> { });
            Stage stage = shownAt(strip, 1160);
            try {
                Node button = strip.overflowButton();
                button.requestFocus();
                press(button, KeyCode.SPACE);
                boolean xrunFocusedInPopover = strip.overflowCells().contains(strip.xrunCell())
                        && strip.xrunCell().getScene().getFocusOwner() == strip.xrunCell();
                model.setTakeDropouts(Optional.of(new TakeDropouts(2, 0, 0, true)));
                return new Object[] {xrunFocusedInPopover, strip.overflowCells().contains(strip.xrunCell()),
                        stage.getScene().getFocusOwner(), strip.isOverflowPopoverShowing(), strip.xrunCell()};
            } finally {
                stage.hide();
            }
        });

        assertThat((Boolean) seen[0]).as("premise: the Xruns cell held the open popover's focus").isTrue();
        assertThat((Boolean) seen[1]).as("the accent brought it onto the strip").isFalse();
        assertThat(seen[2]).as("the focus followed it").isSameAs(seen[4]);
        assertThat((Boolean) seen[3]).as("the popover closed rather than stay apart from the button").isFalse();
    }

    private static SessionStatusStrip stripOf(EngineHealthStatus model) {
        return new SessionStatusStrip(new ProjectOperationProgress(new FxDispatcher()), MotionManager.getDefault(),
                model);
    }

    /** The strip's width and its overflow cells once its own Scene is {@code width} px wide. */
    private static List<Object> widthAndOverflow(SessionStatusStrip strip, int width) {
        VBox root = new VBox();
        new Scene(root, width, 120);
        root.getChildren().setAll(strip);
        root.applyCss();
        root.layout();
        return List.of(strip.getWidth(), List.copyOf(strip.overflowCells()));
    }

    /** A shown Stage whose Scene is {@code width} px wide, holding the strip. */
    private static Stage shownAt(SessionStatusStrip strip, int width) {
        Stage stage = new Stage();
        VBox root = new VBox(strip);
        stage.setScene(new Scene(root, width, 120));
        stage.show();
        root.applyCss();
        root.layout();
        return stage;
    }

    private static EngineHealthStatus.XrunFigures accented() {
        return new EngineHealthStatus.XrunFigures(4, 2, 0, SINCE, EngineHealthStatus.UnderrunReporting.REPORTED);
    }

    @SuppressWarnings("unchecked")
    private static List<StatusStripCell> cells(Object list) {
        return (List<StatusStripCell>) list;
    }

    private static SessionStatusStrip realized(EngineHealthStatus model) {
        SessionStatusStrip strip = new SessionStatusStrip(new ProjectOperationProgress(new FxDispatcher()),
                MotionManager.getDefault(), model);
        new Scene(new Group(strip), 1400, 80);
        strip.applyCss();
        strip.layout();
        return strip;
    }

    private static void resizeTo(SessionStatusStrip strip, double width) {
        strip.resize(width, 60.0);
        strip.applyCss();
        strip.layout();
    }

    private static void press(Node target, KeyCode code) {
        Event.fireEvent(target, key(KeyEvent.KEY_PRESSED, code));
        Event.fireEvent(target, key(KeyEvent.KEY_RELEASED, code));
    }

    private static KeyEvent key(javafx.event.EventType<KeyEvent> type, KeyCode code) {
        return new KeyEvent(type, "", "", code, false, false, false, false);
    }

    private static <T> T onFx(Callable<T> action) throws Exception {
        return Story307TestSupport.onFx(action, 30, TimeUnit.SECONDS);
    }
}
