package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import com.benesquivelmusic.daw.sdk.event.DawEvent;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.TransportEvent;
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;

import javafx.event.ActionEvent;
import javafx.scene.AccessibleAction;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transport's own edits read the window's edit gate (PR #989 review
 * 5711460812; ruling R-5.1 B): the pre/post-roll toggles and spinners, the
 * tempo editor's commit, the live settings' tempo write and the rescued
 * take's Review seek. While the gate says no, the act — a toggle's FIRE; a
 * spinner's SET_TEXT, committed by its commitValue; the tempo editor's
 * SET_TEXT, after the double-click that opens it, committed by its action
 * event; the live settings' apply, called directly with the gate's answer as
 * its last argument, as MainController.applyLiveSettings passes
 * projectActionsAllowed(); and the Review, run as its notification pill runs
 * it — writes nothing, writes no status text, adds no history entry,
 * publishes nothing, and the control shows the stored value again; once it
 * says yes, the same act edits. The return-to-start preference is the
 * application's, not the project's, and is applied either way (ruling-2 F6).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TransportEditsAreRefusedWhileProjectActionsAreNotAllowedTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 256);

    private final AtomicBoolean allowed = new AtomicBoolean(false);
    private final List<DawEvent> published = new CopyOnWriteArrayList<>();
    private EventBus previousBus;
    private DefaultEventBus bus;
    private EventBus.Subscription recording;
    private DawProject project;
    private UndoManager undo;
    private AudioEngine engine;
    private TransportController transport;
    private Label statusBar;

    @BeforeEach
    void aTransportBehindTheGate() throws Exception {
        previousBus = EventBusPublisher.getDefault();
        bus = new DefaultEventBus();
        recording = bus.onPublication(DawEvent.class, published::add);
        EventBusPublisher.setDefault(bus);
        project = new DawProject("Song", FORMAT);
        project.getTransport().setPreRollPostRoll(new PreRollPostRoll(2, 0, false));
        undo = new UndoManager();
        runOnFx(() -> {
            engine = new AudioEngine(project.getFormat());
            statusBar = new Label("before");
            transport = new TransportController(project, engine, undo, new NotificationBar(), new Label(),
                    statusBar, new Label(), new Button(), new Button(), () -> false,
                    () -> GridResolution.QUARTER, () -> CountInMode.OFF, track -> { }, () -> true,
                    () -> RoundTripLatency.UNKNOWN);
            transport.setProjectActionsAllowed(allowed::get);
            transport.createPreRollPostRollControls();
            project.markClean();
        });
    }

    @AfterEach
    void retire() throws Exception {
        try {
            runOnFx(() -> {
                transport.retire();
                engine.shutdown();
            });
        } finally {
            recording.close();
            EventBusPublisher.setDefault(previousBus);
            bus.close();
        }
    }

    /** One handler: what the act is, and how its refusal and its admission show. */
    record Row(String name, Act act) {
        Row {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(act, "act must not be null");
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @FunctionalInterface
    interface Act {
        void refusedThenAdmitted(TransportEditsAreRefusedWhileProjectActionsAreNotAllowedTest test) throws Exception;
    }

    static Stream<Row> rows() {
        return Stream.of(
                new Row("post-roll toggle FIRE", TransportEditsAreRefusedWhileProjectActionsAreNotAllowedTest::postRollToggle),
                new Row("post-roll spinner SET_TEXT and commit", TransportEditsAreRefusedWhileProjectActionsAreNotAllowedTest::postRollSpinner),
                new Row("tempo editor commit", TransportEditsAreRefusedWhileProjectActionsAreNotAllowedTest::tempoEditor),
                new Row("live settings apply", TransportEditsAreRefusedWhileProjectActionsAreNotAllowedTest::liveSettings),
                new Row("theRescuedTakesReviewIsRefusedWhileTheGateSaysNo",
                        TransportEditsAreRefusedWhileProjectActionsAreNotAllowedTest::rescuedTakeReview));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void aTransportEditIsRefusedWhileTheGateSaysNoAndAdmittedOnceItSaysYes(Row row) throws Exception {
        row.act().refusedThenAdmitted(this);
    }

    // ── the rows ─────────────────────────────────────────────────────────────

    private void postRollToggle() throws Exception {
        PreRollPostRoll stored = project.getTransport().getPreRollPostRoll();
        runOnFx(() -> transport.postRollToggleForTest().executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(project.getTransport().getPreRollPostRoll()).as("refused: nothing written").isEqualTo(stored);
        assertThat(onFx(() -> transport.postRollToggleForTest().isSelected()))
                .as("the toggle the press flipped shows the model again").isFalse();
        assertThat(onFx(statusBar::getText)).as("no status text for a refused click").isEqualTo("before");
        assertNothingElseHappened();

        allowed.set(true);
        runOnFx(() -> transport.postRollToggleForTest().executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(project.getTransport().getPreRollPostRoll()).isEqualTo(new PreRollPostRoll(0, 2, true));
        assertThat(onFx(() -> transport.postRollToggleForTest().isSelected())).isTrue();
        assertThat(onFx(statusBar::getText)).isEqualTo("Post-roll: ON");
    }

    private void postRollSpinner() throws Exception {
        PreRollPostRoll stored = project.getTransport().getPreRollPostRoll();
        Spinner<Integer> spinner = transport.postRollSpinnerForTest();
        runOnFx(() -> {
            spinner.getEditor().executeAccessibleAction(AccessibleAction.SET_TEXT, "3");
            spinner.commitValue();
        });
        assertThat(project.getTransport().getPreRollPostRoll()).as("refused: nothing written").isEqualTo(stored);
        assertThat(onFx(spinner::getValue)).as("the spinner shows the stored bars").isEqualTo(stored.postBars());
        assertThat(onFx(() -> spinner.getEditor().getText())).isEqualTo(String.valueOf(stored.postBars()));
        assertNothingElseHappened();

        allowed.set(true);
        runOnFx(() -> {
            spinner.getEditor().executeAccessibleAction(AccessibleAction.SET_TEXT, "3");
            spinner.commitValue();
        });
        assertThat(project.getTransport().getPreRollPostRoll()).isEqualTo(new PreRollPostRoll(2, 3, false));
        assertThat(onFx(spinner::getValue)).isEqualTo(3);
    }

    private void tempoEditor() throws Exception {
        double stored = project.getTransport().getTempo();
        Label tempoLabel = new Label("120.0");
        HBox box = onFx(() -> new HBox(new Label("BPM"), tempoLabel));
        List<String> statuses = new CopyOnWriteArrayList<>();
        NotificationBar notifications = onFx(NotificationBar::new);
        TempoEditController editor = new TempoEditController(tempoLabel, () -> project, () -> undo, () -> { },
                (text, _) -> statuses.add(text), notifications, allowed::get);
        runOnFx(editor::install);

        runOnFx(() -> commitTempo(box, tempoLabel, "140"));
        assertThat(project.getTransport().getTempo()).as("refused: the tempo is not written").isEqualTo(stored);
        assertThat(onFx(() -> box.getChildren().get(1))).as("the label takes the editor's place again")
                .isSameAs(tempoLabel);
        assertThat(statuses).as("nothing reported").isEmpty();
        assertNothingElseHappened();

        allowed.set(true);
        runOnFx(() -> commitTempo(box, tempoLabel, "140"));
        assertThat(project.getTransport().getTempo()).isEqualTo(140.0);
        assertThat(undo.undoSize()).isEqualTo(1);
        assertThat(onFx(() -> box.getChildren().get(1))).isSameAs(tempoLabel);
    }

    /** Double-clicks the tempo label, types {@code text} into the editor that replaced it, and presses Enter. */
    private static void commitTempo(HBox box, Label tempoLabel, String text) {
        tempoLabel.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 1, 1, 1, 1, MouseButton.PRIMARY, 2,
                false, false, false, false, false, false, false, false, false, false, null));
        Node shown = box.getChildren().get(1);
        assertThat(shown).as("the double-click opened the editor").isInstanceOf(TextField.class);
        TextField field = (TextField) shown;
        field.executeAccessibleAction(AccessibleAction.SET_TEXT, text);
        field.fireEvent(new ActionEvent());
    }

    private void liveSettings() throws Exception {
        Preferences preferences = Preferences.userRoot().node("transportEditsRefused_" + System.nanoTime());
        try {
            SettingsModel model = new SettingsModel(preferences);
            model.setAutoSaveIntervalSeconds(300);
            model.setDefaultTempo(150.0);
            model.setReturnToStartOnStop(!project.getTransport().isReturnToStartOnStop());
            ProjectManager projectManager = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT));
            PluginRegistry plugins = new PluginRegistry();
            double stored = project.getTransport().getTempo();

            LiveSettingsApplier.apply(model, model.getPluginScanPaths(), null, projectManager, project, plugins,
                    allowed.get());
            assertThat(project.getTransport().getTempo()).as("refused: the project's tempo is not written")
                    .isEqualTo(stored);
            assertThat(project.getTransport().isReturnToStartOnStop())
                    .as("the application's preference is applied anyway").isEqualTo(model.isReturnToStartOnStop());
            assertNothingElseHappened();

            allowed.set(true);
            LiveSettingsApplier.apply(model, model.getPluginScanPaths(), null, projectManager, project, plugins,
                    allowed.get());
            assertThat(project.getTransport().getTempo()).isEqualTo(150.0);
        } finally {
            preferences.removeNode();
        }
    }

    /**
     * The rescued take's Review, run as its notification pill runs it — the
     * pill's button calls the action and nothing else — on a STOPPED
     * transport: refused, it moves nothing and writes no status; admitted,
     * the same action seeks to the take's anchor and shows the review status.
     */
    private void rescuedTakeReview() throws Exception {
        double anchor = 16.0;
        java.nio.file.Path takeDirectory = java.nio.file.Path.of("takes", "rescued-take");
        double recordedSeconds = 4.0;
        Runnable review = onFx(() -> transport.recordCoordinator()
                .rescuedTakeReview(anchor, takeDirectory, recordedSeconds));
        double stored = project.getTransport().getPositionInBeats();
        assertThat(stored).as("fixture: the playhead is elsewhere").isNotEqualTo(anchor);

        runOnFx(review);
        assertThat(project.getTransport().getPositionInBeats()).as("refused: the playhead stays").isEqualTo(stored);
        assertThat(onFx(statusBar::getText)).as("and no status text is written").isEqualTo("before");
        assertNothingElseHappened();

        allowed.set(true);
        runOnFx(review);
        assertThat(project.getTransport().getPositionInBeats()).isEqualTo(anchor);
        assertThat(onFx(statusBar::getText))
                .isEqualTo(RecordCoordinator.rescuedTakeReviewStatus(takeDirectory, recordedSeconds));
    }

    /** No history entry, no dirty mark, and nothing but the transport's own events published. */
    private void assertNothingElseHappened() {
        assertThat(undo.undoSize()).as("no history entry").isZero();
        assertThat(project.isDirty()).as("not dirty").isFalse();
        assertThat(published).as("nothing published").allMatch(TransportEvent.class::isInstance);
    }
}
