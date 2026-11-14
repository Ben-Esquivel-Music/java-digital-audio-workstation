package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.inspector.sections.NotificationsSection;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Skin;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.util.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.GUARD;
import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 336: a long failure
 * message — a re-show with its running total, or a FATAL with a long reason —
 * does not fit the single-line message label of a toast or of a history pill
 * in the inspector, so each pill's label carries one tooltip holding the whole
 * message. The tooltip follows the label's text (javafx-application-design
 * "## 4. Use JavaFX Properties for everything observable"), takes no focus,
 * and the pill's accessible text stays the whole message.
 *
 * <p>The tooltip shows that whole message on the screen (story 336, studio-engineer
 * ruling R3-2): on both pill kinds it wraps, at most 440 px wide, and stays up
 * while the pointer is on the message instead of vanishing after the default
 * five seconds. On the transient toast, shown in a 1280 × 720 window with the
 * application's {@code styles.css}, its 300-character message is laid out by
 * the tooltip's skin within those 440 px, every character of it displayed.</p>
 *
 * <p>A {@link Tooltip} is a popup window with a scene of its own, and the
 * inspector rebuilds up to a hundred history pills on every toast, so a pill
 * builds its tooltip only when the pointer first enters the label (the same
 * section's lazy initialization for controls built in bulk, and "## 13.
 * Performance checklist"): no pill has one before, a rebuilt history creates
 * none, and the first entry of the pointer gives the label its tooltip — the
 * entry comes before the move that starts a tooltip's show delay, so the first
 * hover shows it.</p>
 *
 * <p>Real {@link NotificationBar} (the transient toast), a history
 * {@link NotificationPill} built from a {@link NotificationEntry} as the
 * inspector's Notifications section builds each one, and the real
 * {@link NotificationsSection} over a {@link NotificationHistoryService}. The
 * pointer's entry is the event the scene fires at a node the pointer enters.
 * The window and the tooltip a test shows are hidden after it.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class NotificationPillTooltipTest {

    private static final String LONG_MESSAGE = ("Serious internal error: the application may not work correctly from"
            + " now on. When you can, save a copy with File ▸ Save As… and restart. Reason: "
            + "x".repeat(300)).substring(0, 300);

    /** The widest the message tooltip may be (story 336, studio-engineer ruling R3-2). */
    private static final double MAX_WIDTH = 440.0;

    private static final String STYLES_CSS = "/com/benesquivelmusic/daw/app/ui/styles.css";

    @Test
    void theToastsTooltipIsBuiltOnTheFirstHoverHoldsTheWholeMessageAndFollowsTheNextOne() throws Exception {
        ToastHovers seen = onFx(() -> {
            NotificationBar bar = new NotificationBar();
            bar.setAnimated(false);
            bar.show(NotificationLevel.ERROR, LONG_MESSAGE, "Open Log Folder", () -> { });
            Label label = messageLabel(bar.getPill());
            String before = String.valueOf(label.getTooltip());
            hover(label);
            Tooltip first = label.getTooltip();
            TooltipSettings firstSettings = TooltipSettings.of(first);
            String firstAccessible = bar.getPill().getAccessibleText();
            bar.show(NotificationLevel.WARNING, "a shorter one");
            hover(label);
            Tooltip second = label.getTooltip();
            ToastHovers hovers = new ToastHovers(before, firstSettings, TooltipSettings.of(second), first == second,
                    firstAccessible);
            bar.dismiss();
            return hovers;
        });

        assertThat(LONG_MESSAGE).hasSize(300);
        assertThat(seen.before()).as("no tooltip before the pointer enters").isEqualTo("null");
        assertThat(seen.first().text()).as("the whole 300-character message").isEqualTo(LONG_MESSAGE);
        assertWrapsWithinTheMaximumWidthAndStaysUp(seen.first(), "the toast's");
        assertThat(seen.second().text()).as("the tooltip follows the label to the next toast")
                .isEqualTo("a shorter one");
        assertThat(seen.sameTooltip()).as("one tooltip per pill").isTrue();
        assertThat(seen.accessible()).as("the accessible text stays the whole message").endsWith(": " + LONG_MESSAGE);
    }

    @Test
    void aHistoryPillsTooltipIsBuiltOnTheFirstHoverAndHoldsTheWholeMessage() throws Exception {
        HistoryHover seen = onFx(() -> {
            NotificationPill pill = new NotificationPill(false);
            pill.setEntry(new NotificationEntry(Instant.now(), NotificationLevel.ERROR, LONG_MESSAGE,
                    Optional.of(() -> { }), Optional.of("Open Log Folder")));
            Label label = messageLabel(pill);
            String before = String.valueOf(label.getTooltip());
            hover(label);
            return new HistoryHover(before, TooltipSettings.of(label.getTooltip()), pill.getAccessibleText());
        });

        assertThat(seen.before()).as("no tooltip before the pointer enters").isEqualTo("null");
        assertThat(seen.tooltip().text()).as("the whole 300-character message").isEqualTo(LONG_MESSAGE);
        assertWrapsWithinTheMaximumWidthAndStaysUp(seen.tooltip(), "a history pill's");
        assertThat(seen.accessible()).as("the accessible text stays the whole message").endsWith(": " + LONG_MESSAGE);
    }

    @Test
    void theToastsTooltipLaysTheWholeMessageOutWithinItsMaximumWidth() throws Exception {
        Stage stage = onFx(Stage::new);
        NotificationBar bar = onFx(NotificationBar::new);
        Tooltip[] shown = new Tooltip[1];
        try {
            Scene tooltipScene = onFx(() -> {
                BorderPane rootPane = new BorderPane();
                rootPane.getStyleClass().add("root-pane");
                bar.setAnimated(false);
                rootPane.setTop(bar);
                Scene scene = new Scene(rootPane, 1280, 720);
                scene.getStylesheets().add(
                        NotificationPillTooltipTest.class.getResource(STYLES_CSS).toExternalForm());
                stage.setScene(scene);
                stage.show();
                bar.show(NotificationLevel.ERROR, LONG_MESSAGE, "Open Log Folder", () -> { });
                Label label = messageLabel(bar.getPill());
                hover(label);
                Tooltip tooltip = label.getTooltip();
                shown[0] = tooltip;
                tooltip.show(label, stage.getX() + 16, stage.getY() + 48);
                return tooltip.getScene();
            });
            awaitALayoutPulseOf(tooltipScene);

            TooltipLayout laidOut = onFx(() -> {
                Skin<?> skin = shown[0].getSkin();
                assertThat(skin).as("the shown tooltip has its skin").isNotNull();
                Label skinLabel = (Label) skin.getNode();
                Text displayed = (Text) skinLabel.lookup(".text");
                assertThat(displayed).as("the skin label's displayed text node").isNotNull();
                Text oneLine = new Text(LONG_MESSAGE);
                oneLine.setFont(displayed.getFont());
                return new TooltipLayout(skinLabel.getWidth(), displayed.getText(),
                        displayed.getLayoutBounds().getWidth(), displayed.getLayoutBounds().getHeight(),
                        oneLine.getLayoutBounds().getWidth(), oneLine.getLayoutBounds().getHeight(),
                        displayed.getFont().getSize());
            });

            assertThat(laidOut.fontSize()).as("precondition: the application's .tooltip rule styles it (12 px)")
                    .isEqualTo(12.0);
            assertThat(laidOut.oneLineWidth()).as("non-vacuity: on one line the message is wider than the bound")
                    .isGreaterThan(MAX_WIDTH);
            assertThat(laidOut.labelWidth()).as("the tooltip's label is laid out within 440 px")
                    .isPositive().isLessThanOrEqualTo(MAX_WIDTH);
            assertThat(laidOut.displayedText()).as("its displayed text is the whole message: nothing clipped")
                    .isEqualTo(LONG_MESSAGE);
            assertThat(laidOut.textWidth()).as("laid out within 440 px").isPositive()
                    .isLessThanOrEqualTo(MAX_WIDTH);
            assertThat(laidOut.textHeight()).as("on several lines: wrapped, not cut")
                    .isGreaterThan(2 * laidOut.oneLineHeight());
        } finally {
            runOnFx(() -> {
                if (shown[0] != null) {
                    shown[0].hide();
                }
                bar.dismiss();
                stage.hide();
            });
        }
    }

    @Test
    void rebuildingTheHistoryCreatesNoTooltipsUntilAPillIsHovered() throws Exception {
        List<String> seen = onFx(() -> {
            NotificationHistoryService history = new NotificationHistoryService();
            for (int i = 0; i < 20; i++) {
                history.record(NotificationLevel.ERROR, "an earlier failure " + i + ": " + LONG_MESSAGE,
                        Optional.of(() -> { }), Optional.of("Open Log Folder"));
            }
            NotificationsSection section = new NotificationsSection("Notifications");
            section.setHistoryService(history);
            history.record(NotificationLevel.ERROR, LONG_MESSAGE, Optional.of(() -> { }),
                    Optional.of("Open Log Folder"));
            List<NotificationPill> pills = section.getPills();
            long withTooltips = pills.stream().filter(pill -> messageLabel(pill).getTooltip() != null).count();
            NotificationPill newest = pills.stream().filter(pill -> LONG_MESSAGE.equals(pill.getMessage()))
                    .findFirst().orElseThrow();
            hover(messageLabel(newest));
            long afterOneHover = pills.stream().filter(pill -> messageLabel(pill).getTooltip() != null).count();
            return List.of(String.valueOf(pills.size()), String.valueOf(withTooltips),
                    String.valueOf(afterOneHover), messageLabel(newest).getTooltip().getText());
        });

        assertThat(seen.get(0)).as("non-vacuity: the rebuilt history has a pill per entry").isEqualTo("21");
        assertThat(seen.get(1)).as("rebuilding the history built no tooltip").isEqualTo("0");
        assertThat(seen.get(2)).as("hovering one pill builds its tooltip only").isEqualTo("1");
        assertThat(seen.get(3)).as("which holds the whole message").isEqualTo(LONG_MESSAGE);
    }

    private static void assertWrapsWithinTheMaximumWidthAndStaysUp(TooltipSettings tooltip, String whose) {
        assertThat(tooltip.wrapText()).as("%s tooltip wraps", whose).isTrue();
        assertThat(tooltip.maxWidth()).as("%s tooltip is at most 440 px wide", whose).isEqualTo(MAX_WIDTH);
        assertThat(tooltip.showDuration()).as("%s tooltip stays up while the pointer is on the message", whose)
                .isEqualTo(Duration.INDEFINITE);
    }

    private static Label messageLabel(NotificationPill pill) {
        Label message = (Label) pill.lookup(".notification-message");
        assertThat(message).as("the pill's message label").isNotNull();
        assertThat(message.getText()).as("the label shows the message").isEqualTo(pill.getMessage());
        return message;
    }

    /** The pointer enters {@code label}: the event the scene fires at each node the pointer enters. */
    private static void hover(Label label) {
        Event.fireEvent(label, new MouseEvent(MouseEvent.MOUSE_ENTERED_TARGET, 1, 1, 1, 1, MouseButton.NONE, 0,
                false, false, false, false, false, false, false, true, false, false, null));
    }

    /** Waits, bounded by {@link FailureSpineFixture#GUARD}, for a pulse that has laid {@code scene} out. */
    private static void awaitALayoutPulseOf(Scene scene) throws Exception {
        CountDownLatch laidOut = new CountDownLatch(1);
        Runnable listener = laidOut::countDown;
        runOnFx(() -> {
            scene.addPostLayoutPulseListener(listener);
            Platform.requestNextPulse();
        });
        try {
            assertThat(laidOut.await(GUARD.toMillis(), TimeUnit.MILLISECONDS))
                    .as("a layout pulse of the tooltip's scene within %s", GUARD).isTrue();
        } finally {
            runOnFx(() -> scene.removePostLayoutPulseListener(listener));
        }
    }

    /** What a tooltip is set to, read on the FX thread. */
    private record TooltipSettings(String text, boolean wrapText, double maxWidth, Duration showDuration) {
        private TooltipSettings {
            Objects.requireNonNull(text, "text must not be null");
            Objects.requireNonNull(showDuration, "showDuration must not be null");
        }

        static TooltipSettings of(Tooltip tooltip) {
            return new TooltipSettings(tooltip.getText(), tooltip.isWrapText(), tooltip.getMaxWidth(),
                    tooltip.getShowDuration());
        }
    }

    /** The toast's tooltip at its first hover and at the next toast's. */
    private record ToastHovers(String before, TooltipSettings first, TooltipSettings second, boolean sameTooltip,
                               String accessible) {
        private ToastHovers {
            Objects.requireNonNull(before, "before must not be null");
            Objects.requireNonNull(first, "first must not be null");
            Objects.requireNonNull(second, "second must not be null");
            Objects.requireNonNull(accessible, "accessible must not be null");
        }
    }

    /** A history pill's tooltip at its first hover. */
    private record HistoryHover(String before, TooltipSettings tooltip, String accessible) {
        private HistoryHover {
            Objects.requireNonNull(before, "before must not be null");
            Objects.requireNonNull(tooltip, "tooltip must not be null");
            Objects.requireNonNull(accessible, "accessible must not be null");
        }
    }

    /** How the shown tooltip's skin laid the message out, read on the FX thread. */
    private record TooltipLayout(double labelWidth, String displayedText, double textWidth, double textHeight,
                                 double oneLineWidth, double oneLineHeight, double fontSize) {
        private TooltipLayout {
            Objects.requireNonNull(displayedText, "displayedText must not be null");
        }
    }
}
