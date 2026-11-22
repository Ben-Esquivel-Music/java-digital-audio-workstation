package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.diagnostics.EngineFault;
import com.benesquivelmusic.daw.app.diagnostics.NoticeAction;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.status.EngineHealthStatus;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.app.ui.status.SessionStatusStrip;
import com.benesquivelmusic.daw.app.ui.status.StatusStripCell;
import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.AccessibleAction;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The session status strip's Engine and Xruns cells over an
 * {@link EngineHealthStatus} (story 338): the text, treatment,
 * tooltip, spoken text and action of each, exactly as rulings E18, E22 to
 * E24 and E26 to E28 word them, and the Engine cell never in the overflow.
 * Each test builds its strip on the FX thread and asserts off it.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class EngineHealthCellsTest {

    private static final Optional<DeviceId> INTERFACE = Optional.of(new DeviceId("Mock", "Interface A"));
    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter MINUTES = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final Instant FIRST = Instant.parse("2026-11-16T10:00:01Z");
    private static final Instant LAST = Instant.parse("2026-11-16T10:00:04Z");

    /** What a cell shows, read on the FX thread. */
    private record Shown(boolean visible, boolean managed, String text, StatusStripCell.Severity severity,
                         List<String> styleClasses, String tooltip, String spoken, String help,
                         boolean actionable, boolean focusTraversable, String role) {

        static Shown of(StatusStripCell cell) {
            return new Shown(cell.isVisible(), cell.isManaged(), cell.getText(), cell.getSeverity(),
                    List.copyOf(cell.getStyleClass()), cell.getTooltip() == null ? null : cell.getTooltip().getText(),
                    cell.getAccessibleText(), cell.getAccessibleHelp(), cell.getOnAction() != null,
                    cell.isFocusTraversable(), cell.getAccessibleRole().name());
        }
    }

    @Test
    void theEngineCellSaysNothingWhileTheEngineIsStoppedOrRunning() throws Exception {
        List<Shown> shown = onFx(() -> {
            EngineHealthStatus model = new EngineHealthStatus();
            SessionStatusStrip strip = realized(model, 1300);
            List<Shown> states = new ArrayList<>();
            states.add(Shown.of(strip.engineCell()));
            model.onEngineStateChange(new EngineStateChange(EngineState.RUNNING, INTERFACE));
            states.add(Shown.of(strip.engineCell()));
            return states;
        });

        assertThat(shown).allSatisfy(quiet -> {
            assertThat(quiet.visible()).as("never RUNNING or STOPPED as health (ruling E26)").isFalse();
            assertThat(quiet.managed()).as("and it takes no space").isFalse();
            assertThat(quiet.actionable()).isFalse();
            assertThat(quiet.focusTraversable()).as("out of the focus order while it says nothing").isFalse();
        });
    }

    @Test
    void reconfiguringIsInformational() throws Exception {
        Shown shown = onFx(() -> {
            EngineHealthStatus model = new EngineHealthStatus();
            SessionStatusStrip strip = realized(model, 1300);
            model.onEngineStateChange(new EngineStateChange(EngineState.RECONFIGURING, INTERFACE));
            return Shown.of(strip.engineCell());
        });

        assertThat(shown.visible()).isTrue();
        assertThat(shown.managed()).isTrue();
        assertThat(shown.text()).isEqualTo("Engine: reconfiguring…");
        assertThat(shown.severity()).as("the informational treatment (ruling E27)")
                .isEqualTo(StatusStripCell.Severity.NORMAL);
        assertThat(shown.tooltip()).isEqualTo("Applying audio settings; audio resumes when done.");
        assertThat(shown.spoken()).isEqualTo("Audio engine reconfiguring.");
        assertThat(shown.help()).isEqualTo(shown.tooltip());
    }

    @Test
    void aLostDeviceIsNamedInTheWarningTreatmentAndTheCellReconnects() throws Exception {
        AtomicInteger reconnects = new AtomicInteger();
        Shown[] shown = onFx(() -> {
            EngineHealthStatus model = new EngineHealthStatus();
            SessionStatusStrip strip = realized(model, 1300);
            strip.setOnReconnectAction(reconnects::incrementAndGet);
            model.onEngineStateChange(new EngineStateChange(EngineState.DEVICE_LOST, INTERFACE,
                    EngineStateChange.LossCause.STALLED));
            Shown named = Shown.of(strip.engineCell());
            strip.engineCell().fire();
            model.onEngineStateChange(new EngineStateChange(EngineState.DEVICE_LOST, Optional.empty(),
                    EngineStateChange.LossCause.REMOVED));
            return new Shown[] {named, Shown.of(strip.engineCell())};
        });

        assertThat(shown[0].text()).isEqualTo("Device lost: Interface A");
        assertThat(shown[0].severity()).isEqualTo(StatusStripCell.Severity.WARN);
        assertThat(shown[0].styleClasses()).as("the warning-token treatment, never a raw colour")
                .contains(StatusStripCell.SEVERITY_WARN_CLASS);
        assertThat(shown[0].tooltip()).isEqualTo("Interface A stopped responding or was disconnected. Audio is"
                + " stopped. Click to reconnect, or choose another device in Audio Settings.");
        assertThat(shown[0].spoken()).isEqualTo("Audio device Interface A lost. Press to reconnect.");
        assertThat(shown[0].actionable()).isTrue();
        assertThat(shown[0].focusTraversable()).isTrue();
        assertThat(shown[0].role()).isEqualTo("BUTTON");
        assertThat(reconnects).as("the cell's action is Reconnect while the device is lost (ruling E6)").hasValue(1);
        assertThat(shown[1].text()).isEqualTo("Device lost: audio device");
        assertThat(shown[1].tooltip()).startsWith("The audio device stopped responding or was disconnected.");
        assertThat(shown[1].spoken()).isEqualTo("Audio device lost. Press to reconnect.");
    }

    @Test
    void aLatchedFaultIsCriticalWithItsWordsTimesAndAction() throws Exception {
        AtomicInteger restarts = new AtomicInteger();
        EngineFault fault = new EngineFault(AudioFailureCategory.SINK, 3, FIRST, LAST,
                new NoticeAction("Restart Audio Engine", restarts::incrementAndGet));
        Shown shown = onFx(() -> {
            EngineHealthStatus model = new EngineHealthStatus();
            SessionStatusStrip strip = realized(model, 1300);
            model.bindEngineFault(new SimpleObjectProperty<>(Optional.of(fault)));
            Shown faulted = Shown.of(strip.engineCell());
            strip.engineCell().fire();
            return faulted;
        });

        assertThat(shown.text()).isEqualTo("Engine fault: audio output");
        assertThat(shown.severity()).isEqualTo(StatusStripCell.Severity.CRITICAL);
        assertThat(shown.styleClasses()).contains(StatusStripCell.SEVERITY_CRITICAL_CLASS);
        assertThat(shown.tooltip()).isEqualTo("Audio output failed 3 times between " + local(FIRST, SECONDS)
                + " and " + local(LAST, SECONDS) + ". Click to restart audio engine.");
        assertThat(shown.spoken()).as("the action in lower case, as the tooltip has it (ruling E95)")
                .isEqualTo("Audio engine fault: audio output. Press to restart audio engine.");
        assertThat(restarts).as("otherwise the cell runs the fault's own action (ruling E27)").hasValue(1);
    }

    @Test
    void eachCategoryHasItsWords() throws Exception {
        Map<AudioFailureCategory, String> expected = new LinkedHashMap<>();
        expected.put(AudioFailureCategory.CALLBACK, "device callback");
        expected.put(AudioFailureCategory.RENDER, "audio processing");
        expected.put(AudioFailureCategory.RENDER_HALTED, "audio processing stopped");
        expected.put(AudioFailureCategory.SINK, "audio output");
        expected.put(AudioFailureCategory.PACING, "timing");
        expected.put(AudioFailureCategory.INPUT, "audio input");
        expected.put(AudioFailureCategory.WORKER, "processing threads");
        expected.put(AudioFailureCategory.DEVICE, "audio engine");
        expected.put(AudioFailureCategory.DSP, "audio engine");
        Map<AudioFailureCategory, String> texts = onFx(() -> {
            EngineHealthStatus model = new EngineHealthStatus();
            SessionStatusStrip strip = realized(model, 1300);
            SimpleObjectProperty<Optional<EngineFault>> fact = new SimpleObjectProperty<>(Optional.empty());
            model.bindEngineFault(fact);
            Map<AudioFailureCategory, String> read = new LinkedHashMap<>();
            for (AudioFailureCategory category : expected.keySet()) {
                fact.set(Optional.of(new EngineFault(category, 1, FIRST, FIRST, new NoticeAction("Open Log Folder",
                        () -> { }))));
                read.put(category, strip.engineCell().getText());
            }
            return read;
        });

        expected.forEach((category, words) -> assertThat(texts.get(category)).as("%s (ruling E27)", category)
                .isEqualTo("Engine fault: " + words));
    }

    @Test
    void aLossWinsOverAFaultWhoseSentenceJoinsTheTooltip() throws Exception {
        AtomicInteger reconnects = new AtomicInteger();
        AtomicInteger restarts = new AtomicInteger();
        EngineFault fault = new EngineFault(AudioFailureCategory.RENDER_HALTED, 1, FIRST, FIRST,
                new NoticeAction("Restart Audio Engine", restarts::incrementAndGet));
        Shown shown = onFx(() -> {
            EngineHealthStatus model = new EngineHealthStatus();
            SessionStatusStrip strip = realized(model, 1300);
            strip.setOnReconnectAction(reconnects::incrementAndGet);
            model.bindEngineFault(new SimpleObjectProperty<>(Optional.of(fault)));
            model.onEngineStateChange(new EngineStateChange(EngineState.DEVICE_LOST, INTERFACE,
                    EngineStateChange.LossCause.REMOVED));
            Shown both = Shown.of(strip.engineCell());
            strip.engineCell().fire();
            return both;
        });

        assertThat(shown.text()).isEqualTo("Device lost: Interface A");
        assertThat(shown.severity()).isEqualTo(StatusStripCell.Severity.WARN);
        assertThat(shown.tooltip()).isEqualTo("Interface A stopped responding or was disconnected. Audio is"
                + " stopped. Click to reconnect, or choose another device in Audio Settings. Audio processing stopped"
                + " failed 1 time between " + local(FIRST, SECONDS) + " and " + local(FIRST, SECONDS) + ".");
        assertThat(reconnects).as("Reconnect while lost").hasValue(1);
        assertThat(restarts).as("not the fault's action").hasValue(0);
    }

    @Test
    void theCellFiresOnAPrimaryClickEnterSpaceAndTheAccessibleFireAndOnlyWhileItHasAnAction() throws Exception {
        AtomicInteger fired = new AtomicInteger();
        int[] counts = onFx(() -> {
            StatusStripCell cell = new StatusStripCell("Xruns 0");
            boolean traversableBefore = cell.isFocusTraversable();
            cell.fireEvent(key(KeyCode.ENTER));
            int withoutAction = fired.get();
            cell.setOnAction(_ -> fired.incrementAndGet());
            cell.fireEvent(click(MouseButton.PRIMARY, true));
            int afterClick = fired.get();
            cell.fireEvent(click(MouseButton.PRIMARY, false));
            cell.fireEvent(click(MouseButton.SECONDARY, true));
            int afterADragAndASecondaryClick = fired.get();
            cell.fireEvent(key(KeyCode.ENTER));
            int afterEnter = fired.get();
            cell.fireEvent(key(KeyCode.SPACE));
            int afterSpace = fired.get();
            cell.executeAccessibleAction(AccessibleAction.FIRE);
            int afterFire = fired.get();
            boolean traversableWith = cell.isFocusTraversable();
            String roleWith = cell.getAccessibleRole().name();
            cell.setOnAction(null);
            cell.fire();
            return new int[] {traversableBefore ? 1 : 0, withoutAction, afterClick, afterADragAndASecondaryClick,
                    afterEnter, afterSpace, afterFire, traversableWith ? 1 : 0, "BUTTON".equals(roleWith) ? 1 : 0,
                    fired.get(), cell.isFocusTraversable() ? 1 : 0,
                    "TEXT".equals(cell.getAccessibleRole().name()) ? 1 : 0};
        });

        assertThat(counts[0]).as("a text cell is out of the focus order").isZero();
        assertThat(counts[1]).as("Enter on a cell with no action does nothing").isZero();
        assertThat(counts[2]).as("a primary click fires").isEqualTo(1);
        assertThat(counts[3]).as("a drag or a secondary click does not").isEqualTo(1);
        assertThat(counts[4]).as("Enter fires").isEqualTo(2);
        assertThat(counts[5]).as("Space fires").isEqualTo(3);
        assertThat(counts[6]).as("the accessible FIRE action fires").isEqualTo(4);
        assertThat(counts[7]).as("in the focus order while it has an action").isEqualTo(1);
        assertThat(counts[8]).as("read as a button while it has an action").isEqualTo(1);
        assertThat(counts[9]).as("no action, nothing fired").isEqualTo(4);
        assertThat(counts[10]).as("out of the focus order again").isZero();
        assertThat(counts[11]).as("text again").isEqualTo(1);
    }

    @Test
    void theXrunsCellShowsTheCountQuietlyAtZeroAndOpensTheLog() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        Instant since = Instant.parse("2026-11-16T09:30:00Z");
        Shown shown = onFx(() -> {
            EngineHealthStatus model = new EngineHealthStatus();
            SessionStatusStrip strip = realized(model, 1300);
            strip.setOnXrunLogAction(opened::incrementAndGet);
            model.setXrunFigures(new EngineHealthStatus.XrunFigures(0, 0, 0, since,
                    EngineHealthStatus.UnderrunReporting.REPORTED));
            Shown zero = Shown.of(strip.xrunCell());
            strip.xrunCell().fire();
            return zero;
        });

        assertThat(shown.visible()).as("always shown (ruling E22)").isTrue();
        assertThat(shown.text()).isEqualTo("Xruns 0");
        assertThat(shown.severity()).as("the quiet treatment at zero").isEqualTo(StatusStripCell.Severity.NORMAL);
        assertThat(shown.tooltip()).isEqualTo("0 dropouts since " + local(since, SECONDS)
                + " (0 in the last 30 seconds); 0 late blocks. Click for the xrun log.");
        assertThat(shown.spoken()).isEqualTo("Xruns: 0 since " + local(since, MINUTES)
                + ", 0 in the last 30 seconds. Press to open the xrun log.");
        assertThat(shown.actionable()).isTrue();
        assertThat(opened).as("the cell opens the xrun log (ruling E23)").hasValue(1);
    }

    @Test
    void theXrunsCellHasTheWarningAccentOnlyWhileTheCountGrewInTheLastThirtySeconds() throws Exception {
        Instant since = Instant.parse("2026-11-16T09:30:00Z");
        Shown[] shown = onFx(() -> {
            EngineHealthStatus model = new EngineHealthStatus();
            SessionStatusStrip strip = realized(model, 1300);
            model.setXrunFigures(new EngineHealthStatus.XrunFigures(12, 2, 1, since,
                    EngineHealthStatus.UnderrunReporting.NOT_REPORTED));
            Shown recent = Shown.of(strip.xrunCell());
            model.setXrunFigures(new EngineHealthStatus.XrunFigures(12, 0, 1, since,
                    EngineHealthStatus.UnderrunReporting.NOT_REPORTED));
            return new Shown[] {recent, Shown.of(strip.xrunCell())};
        });

        assertThat(shown[0].text()).isEqualTo("Xruns 12");
        assertThat(shown[0].severity()).isEqualTo(StatusStripCell.Severity.WARN);
        assertThat(shown[0].tooltip()).as("with the sentence of ruling E18 for a driver that reports no underruns")
                .isEqualTo("12 dropouts since " + local(since, SECONDS) + " (2 in the last 30 seconds); 1 late block."
                        + " Click for the xrun log. This audio driver does not report underruns; late blocks are"
                        + " listed in the log.");
        assertThat(shown[1].severity()).as("quiet again once nothing grew for 30 seconds")
                .isEqualTo(StatusStripCell.Severity.NORMAL);
    }

    @Test
    void aTakeWithDropoutsShowsThemAndKeepsThemAsTheLastTakeAfterItEnds() throws Exception {
        Instant since = Instant.parse("2026-11-16T09:30:00Z");
        Shown[] shown = onFx(() -> {
            EngineHealthStatus model = new EngineHealthStatus();
            SessionStatusStrip strip = realized(model, 1300);
            model.setXrunFigures(new EngineHealthStatus.XrunFigures(3, 0, 0, since,
                    EngineHealthStatus.UnderrunReporting.REPORTED));
            model.setTakeDropouts(Optional.of(new TakeDropouts(0, 0, 0, true)));
            Shown clean = Shown.of(strip.xrunCell());
            model.setTakeDropouts(Optional.of(new TakeDropouts(4, 1, 30, true)));
            Shown recording = Shown.of(strip.xrunCell());
            model.setTakeDropouts(Optional.of(new TakeDropouts(4, 1, 30, false)));
            return new Shown[] {clean, recording, Shown.of(strip.xrunCell())};
        });

        assertThat(shown[0].text()).as("a take with no dropouts adds nothing").isEqualTo("Xruns 3");
        assertThat(shown[0].severity()).isEqualTo(StatusStripCell.Severity.NORMAL);
        assertThat(shown[1].text()).isEqualTo("Xruns 3 · Take dropouts 5");
        assertThat(shown[1].severity()).as("the warning accent (ruling E24)").isEqualTo(StatusStripCell.Severity.WARN);
        assertThat(shown[2].text()).as("it never disappears when the take ends").isEqualTo(
                "Xruns 3 · Last take dropouts 5");
        assertThat(shown[2].severity()).isEqualTo(StatusStripCell.Severity.WARN);
    }

    /**
     * Ruling E92: the last take's dropouts keep the warning accent for as
     * long as they are on show, past the 30 seconds of ruling E22, until the
     * next take starts or Clear; a take with K = 0 adds nothing and no accent.
     */
    @Test
    void theLastTakesDropoutsKeepTheAccentAfterThirtyQuietSecondsUntilTheyAreCleared() throws Exception {
        Instant since = Instant.parse("2026-11-16T09:30:00Z");
        Shown[] shown = onFx(() -> {
            EngineHealthStatus model = new EngineHealthStatus();
            SessionStatusStrip strip = realized(model, 1300);
            model.setXrunFigures(new EngineHealthStatus.XrunFigures(3, 3, 0, since,
                    EngineHealthStatus.UnderrunReporting.REPORTED));
            model.setTakeDropouts(Optional.of(new TakeDropouts(2, 0, 0, false)));
            model.setXrunFigures(new EngineHealthStatus.XrunFigures(3, 0, 0, since,
                    EngineHealthStatus.UnderrunReporting.REPORTED));
            Shown thirtySecondsLater = Shown.of(strip.xrunCell());
            model.setTakeDropouts(Optional.empty());
            Shown cleared = Shown.of(strip.xrunCell());
            model.setTakeDropouts(Optional.of(new TakeDropouts(0, 0, 0, false)));
            return new Shown[] {thirtySecondsLater, cleared, Shown.of(strip.xrunCell())};
        });

        assertThat(shown[0].text()).isEqualTo("Xruns 3 · Last take dropouts 2");
        assertThat(shown[0].severity()).as("nothing grew for 30 seconds, the last take's figure is still on show")
                .isEqualTo(StatusStripCell.Severity.WARN);
        assertThat(shown[1].text()).isEqualTo("Xruns 3");
        assertThat(shown[1].severity()).as("Clear acknowledged it").isEqualTo(StatusStripCell.Severity.NORMAL);
        assertThat(shown[2].text()).as("a clean last take adds nothing").isEqualTo("Xruns 3");
        assertThat(shown[2].severity()).isEqualTo(StatusStripCell.Severity.NORMAL);
    }

    @Test
    void theEngineCellNeverEntersTheOverflowAtAnyWidth() throws Exception {
        List<Object[]> layouts = onFx(() -> {
            EngineHealthStatus model = new EngineHealthStatus();
            SessionStatusStrip strip = realized(model, 1300);
            model.onEngineStateChange(new EngineStateChange(EngineState.DEVICE_LOST, INTERFACE,
                    EngineStateChange.LossCause.REMOVED));
            List<Object[]> read = new ArrayList<>();
            for (double width : new double[] {1300, 1160, 850, 600}) {
                strip.resize(width, 60);
                strip.applyCss();
                strip.layout();
                read.add(new Object[] {width, strip.overflowCells().contains(strip.engineCell()),
                        strip.engineCell().isManaged()
                                && strip.engineCell().getParent() == strip.sessionCell().getParent()});
            }
            return read;
        });

        assertThat(layouts).allSatisfy(layout -> {
            assertThat((Boolean) layout[1]).as("at %s px a loss is never behind the overflow (ruling E28)", layout[0])
                    .isFalse();
            assertThat((Boolean) layout[2]).as("at %s px it stays inline, in the session cell's row", layout[0])
                    .isTrue();
        });
    }

    @Test
    void theDefaultDeviceAndAResetThatCouldNotReopenAreWordedAsRuled() throws Exception {
        Shown[] shown = onFx(() -> {
            EngineHealthStatus model = new EngineHealthStatus();
            SessionStatusStrip strip = realized(model, 1300);
            model.onEngineStateChange(new EngineStateChange(EngineState.DEVICE_LOST,
                    Optional.of(DeviceId.defaultFor("Java Sound")), EngineStateChange.LossCause.STALLED));
            Shown placeholder = Shown.of(strip.engineCell());
            model.onEngineStateChange(new EngineStateChange(EngineState.DEVICE_LOST, INTERFACE,
                    EngineStateChange.LossCause.REOPEN_AFTER_RESET_FAILED));
            Shown reset = Shown.of(strip.engineCell());
            model.onEngineStateChange(new EngineStateChange(EngineState.DEVICE_LOST,
                    Optional.of(DeviceId.defaultFor("ASIO")), EngineStateChange.LossCause.REOPEN_AFTER_RESET_FAILED));
            return new Shown[] {placeholder, reset, Shown.of(strip.engineCell())};
        });

        assertThat(shown[0].text()).as("ruling E111: never the placeholder").isEqualTo("Device lost: audio device");
        assertThat(shown[0].tooltip()).startsWith("The audio device stopped responding or was disconnected.");
        assertThat(shown[0].spoken()).isEqualTo("Audio device lost. Press to reconnect.");
        assertThat(shown[1].text()).as("the cell's text is the loss's").isEqualTo("Device lost: Interface A");
        assertThat(shown[1].tooltip()).as("ruling E113, never \"stopped responding or was disconnected\"")
                .isEqualTo("Interface A asked to be reset and could not be reopened. Audio is stopped. Click to"
                        + " reconnect, or choose another device in Audio Settings.");
        assertThat(shown[1].spoken()).isEqualTo("Audio device Interface A lost. Press to reconnect.");
        assertThat(shown[2].tooltip()).isEqualTo("The audio device asked to be reset and could not be reopened."
                + " Audio is stopped. Click to reconnect, or choose another device in Audio Settings.");
    }

    @Test
    void theSpokenXrunsTextCarriesTheTakeFigure() throws Exception {
        Instant since = Instant.parse("2026-11-16T09:30:00Z");
        String[] spoken = onFx(() -> {
            EngineHealthStatus model = new EngineHealthStatus();
            SessionStatusStrip strip = realized(model, 1300);
            model.setXrunFigures(new EngineHealthStatus.XrunFigures(3, 1, 0, since,
                    EngineHealthStatus.UnderrunReporting.REPORTED));
            model.setTakeDropouts(Optional.of(new TakeDropouts(0, 0, 0, true)));
            String clean = strip.xrunCell().getAccessibleText();
            model.setTakeDropouts(Optional.of(new TakeDropouts(1, 0, 0, true)));
            String one = strip.xrunCell().getAccessibleText();
            model.setTakeDropouts(Optional.of(new TakeDropouts(4, 1, 30, false)));
            return new String[] {clean, one, strip.xrunCell().getAccessibleText()};
        });

        String lead = "Xruns: 3 since " + local(since, MINUTES) + ", 1 in the last 30 seconds. ";
        assertThat(spoken[0]).as("K = 0 keeps ruling E23's text").isEqualTo(lead + "Press to open the xrun log.");
        assertThat(spoken[1]).as("ruling E102, singular").isEqualTo(lead + "This take: 1 dropout. Press to open the"
                + " xrun log.");
        assertThat(spoken[2]).as("ruling E102, after the take").isEqualTo(lead + "Last take: 5 dropouts. Press to"
                + " open the xrun log.");
    }

    @Test
    void theUnderrunSentenceIsShownOnlyForADriverThatAnsweredItReportsNone() throws Exception {
        Instant since = Instant.parse("2026-11-16T09:30:00Z");
        String[] tooltips = onFx(() -> {
            EngineHealthStatus model = new EngineHealthStatus();
            SessionStatusStrip strip = realized(model, 1300);
            String initial = strip.xrunCell().getTooltip().getText();
            model.setXrunFigures(new EngineHealthStatus.XrunFigures(0, 0, 0, since,
                    EngineHealthStatus.UnderrunReporting.NOT_ASKED));
            String notAsked = strip.xrunCell().getTooltip().getText();
            model.setXrunFigures(new EngineHealthStatus.XrunFigures(0, 0, 0, since,
                    EngineHealthStatus.UnderrunReporting.NOT_REPORTED));
            return new String[] {initial, notAsked, strip.xrunCell().getTooltip().getText()};
        });

        assertThat(tooltips[0]).as("ruling E116: before any stream").doesNotContain("does not report underruns");
        assertThat(tooltips[1]).as("ruling E116: not asked").doesNotContain("does not report underruns");
        assertThat(tooltips[2]).endsWith("This audio driver does not report underruns; late blocks are listed in"
                + " the log.");
    }

    private static SessionStatusStrip realized(EngineHealthStatus model, double width) {
        SessionStatusStrip strip = new SessionStatusStrip(new ProjectOperationProgress(new FxDispatcher()),
                MotionManager.getDefault(), model);
        VBox root = new VBox(strip);
        new Scene(root, width, 120);
        root.applyCss();
        root.layout();
        return strip;
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false,
                false, false);
    }

    private static MouseEvent click(MouseButton button, boolean stillSincePress) {
        return new MouseEvent(MouseEvent.MOUSE_CLICKED, 1, 1, 1, 1, button, 1,
                false, false, false, false, button == MouseButton.PRIMARY, false,
                button == MouseButton.SECONDARY, false, false, stillSincePress, null);
    }

    private static String local(Instant time, DateTimeFormatter format) {
        return format.format(time.atZone(ZoneId.systemDefault()));
    }

    private static <T> T onFx(Callable<T> action) throws Exception {
        return Story307TestSupport.onFx(action, 30, TimeUnit.SECONDS);
    }
}
