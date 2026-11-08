package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.TrackControlWiring;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import com.benesquivelmusic.daw.sdk.event.DawEvent;
import com.benesquivelmusic.daw.sdk.event.EventBus;

import javafx.css.PseudoClass;
import javafx.event.ActionEvent;
import javafx.scene.AccessibleAction;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arrangement lane's own handlers read the window's edit gate (PR #989
 * review 5711460812; rulings R-1.2 and R-5.1 D, ruling-3 G3 and G4a): the
 * phase, fold and remove buttons, the inline rename's commit, the track
 * menu's edits and its exports, and the input pick. While the gate says no,
 * the act — an assistive technology's action on the real control, but for a
 * menu item, {@code MenuItem.fire()}, the call an assistive technology's
 * FIRE on a showing menu reaches; the inline rename, opened by a
 * double-click and committed by its editor's action event after an
 * assistive technology's {@code SET_TEXT}; an export after its chooser, its
 * write called with the file, as the chooser's answer calls it; and the
 * input pick, a double-click on the I/O label whose port dialog is the
 * test's chooser — changes nothing, writes no status or notification, adds
 * no history entry, marks nothing dirty, publishes nothing, and the lane
 * shows the model; once it says yes, the same act edits. An export refused
 * after the user named its file says so at INFO, because nothing in the
 * window can show it was not written.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ArrangementEditsAreRefusedWhileProjectActionsAreNotAllowedTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 256);
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");
    private static final Logger LANE_LOG = Logger.getLogger(TrackStripController.class.getName());

    @TempDir
    Path workspace;

    private final List<DawEvent> published = new CopyOnWriteArrayList<>();
    private EventBus previousBus;
    private DefaultEventBus bus;
    private EventBus.Subscription recording;
    private final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override public void publish(LogRecord record) { logged.add(record); }
        @Override public void flush() { }
        @Override public void close() { }
    };
    private Level previousLevel;
    private Rig rig;

    @BeforeEach
    void aLaneBehindTheGate() throws Exception {
        previousBus = EventBusPublisher.getDefault();
        bus = new DefaultEventBus();
        recording = bus.onPublication(DawEvent.class, published::add);
        EventBusPublisher.setDefault(bus);
        previousLevel = LANE_LOG.getLevel();
        capture.setLevel(Level.ALL);
        LANE_LOG.addHandler(capture);
        LANE_LOG.setLevel(Level.ALL);
        rig = onFx(Rig::new);
    }

    @AfterEach
    void close() throws Exception {
        try {
            runOnFx(rig::close);
        } finally {
            LANE_LOG.removeHandler(capture);
            LANE_LOG.setLevel(previousLevel);
            recording.close();
            EventBusPublisher.setDefault(previousBus);
            bus.close();
        }
    }

    /** One lane handler: how its refusal and its admission show. */
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
        void refusedThenAdmitted(ArrangementEditsAreRefusedWhileProjectActionsAreNotAllowedTest test) throws Exception;
    }

    static Stream<Row> rows() {
        return Stream.of(
                new Row("fold", ArrangementEditsAreRefusedWhileProjectActionsAreNotAllowedTest::fold),
                new Row("remove", ArrangementEditsAreRefusedWhileProjectActionsAreNotAllowedTest::remove),
                new Row("phase", ArrangementEditsAreRefusedWhileProjectActionsAreNotAllowedTest::phase),
                new Row("rename commit", ArrangementEditsAreRefusedWhileProjectActionsAreNotAllowedTest::renameCommit),
                new Row("Copy Track", ArrangementEditsAreRefusedWhileProjectActionsAreNotAllowedTest::copyTrack),
                new Row("exportAsWavIsRefusedBeforeItsRenderCheckAndChooser",
                        ArrangementEditsAreRefusedWhileProjectActionsAreNotAllowedTest::wavExportBeforeItsRenderCheck),
                new Row("exportAsWavIsRefusedAfterItsChooserReturns",
                        ArrangementEditsAreRefusedWhileProjectActionsAreNotAllowedTest::wavExportAfterItsChooser),
                new Row("exportAsMidiIsRefusedAfterItsChooserReturns",
                        ArrangementEditsAreRefusedWhileProjectActionsAreNotAllowedTest::midiExportAfterItsChooser));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void aLaneEditIsRefusedWhileTheGateSaysNoAndAdmittedOnceItSaysYes(Row row) throws Exception {
        row.act().refusedThenAdmitted(this);
    }

    // ── the rows ─────────────────────────────────────────────────────────────

    private void fold() throws Exception {
        Button fold = onFx(() -> (Button) nodes(rig.lane).filter(Button.class::isInstance)
                .filter(node -> ((Button) node).getTooltip() != null
                        && "Fold Automation Lane".equals(((Button) node).getTooltip().getText()))
                .findFirst().orElseThrow());
        String glyph = onFx(fold::getText);
        runOnFx(() -> fold.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> rig.vox.getFoldState().automationFolded())).as("refused: the saved fold state")
                .isFalse();
        assertThat(onFx(fold::getText)).as("the glyph shows the model").isEqualTo(glyph);
        assertNothingElseHappened();

        rig.allowed.set(true);
        runOnFx(() -> fold.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> rig.vox.getFoldState().automationFolded())).isTrue();
    }

    private void remove() throws Exception {
        Button remove = onFx(() -> (Button) rig.lane.lookup(".track-remove-button"));
        runOnFx(() -> remove.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> rig.project.getTracks())).as("refused: the track stays").contains(rig.vox);
        assertThat(onFx(() -> rig.lane.getParent())).as("and so does its lane").isSameAs(rig.trackList);
        assertNothingElseHappened();

        rig.allowed.set(true);
        runOnFx(() -> remove.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> rig.project.getTracks())).doesNotContain(rig.vox);
        assertThat(rig.undo.undoSize()).isEqualTo(1);
    }

    private void phase() throws Exception {
        Button phase = onFx(() -> (Button) rig.lane.lookup(".track-phase-button"));
        runOnFx(() -> phase.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> rig.vox.isPhaseInverted())).as("refused: a direct Track write").isFalse();
        assertThat(onFx(() -> phase.getPseudoClassStates().contains(ACTIVE))).as("the button shows the model")
                .isFalse();
        assertNothingElseHappened();

        rig.allowed.set(true);
        runOnFx(() -> phase.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> rig.vox.isPhaseInverted())).isTrue();
        assertThat(onFx(() -> phase.getPseudoClassStates().contains(ACTIVE))).isTrue();
    }

    private void renameCommit() throws Exception {
        Label name = onFx(() -> TrackStripController.controlsOf(rig.lane).nameLabel());
        int index = onFx(() -> rig.lane.getChildren().indexOf(name));
        runOnFx(() -> commitRename(name, index, "Lead"));
        assertThat(onFx(() -> rig.vox.getName())).as("refused: the name is unchanged").isEqualTo("Vox");
        assertThat(onFx(() -> rig.lane.getChildren().get(index))).as("the label takes the editor's place again")
                .isSameAs(name);
        assertThat(onFx(name::getText)).as("and shows the track's name").isEqualTo("Vox");
        assertNothingElseHappened();

        rig.allowed.set(true);
        runOnFx(() -> commitRename(name, index, "Lead"));
        assertThat(onFx(() -> rig.vox.getName())).isEqualTo("Lead");
        assertThat(rig.undo.undoSize()).isEqualTo(1);
    }

    /** Double-clicks the lane's name label, types {@code text} into the editor that replaced it, and presses Enter. */
    private void commitRename(Label name, int index, String text) {
        name.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 1, 1, 1, 1, MouseButton.PRIMARY, 2,
                false, false, false, false, false, false, false, false, false, false, null));
        Node shown = rig.lane.getChildren().get(index);
        assertThat(shown).as("the double-click opened the inline editor").isInstanceOf(TextField.class);
        TextField editor = (TextField) shown;
        editor.executeAccessibleAction(AccessibleAction.SET_TEXT, text);
        editor.fireEvent(new ActionEvent());
    }

    private void copyTrack() throws Exception {
        MenuItem copy = onFx(() -> item(rig.menu(), "Copy Track"));
        int tracks = onFx(() -> rig.project.getTracks().size());
        runOnFx(copy::fire);
        assertThat(onFx(() -> rig.project.getTracks().size())).as("refused: no copy").isEqualTo(tracks);
        assertNothingElseHappened();

        rig.allowed.set(true);
        runOnFx(copy::fire);
        assertThat(onFx(() -> rig.project.getTracks().size())).isEqualTo(tracks + 1);
        assertThat(rig.undo.undoSize()).isEqualTo(1);
    }

    private void wavExportBeforeItsRenderCheck() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        runOnFx(() -> rig.controller.setRenderGuard(tracks -> {
            checks.incrementAndGet();
            return false; // never reaches the native chooser in a test
        }));
        MenuItem export = onFx(() -> item(rig.menu(), "Export as WAV"));
        assertThat(onFx(export::isDisable)).as("fixture: the track has audio to export").isFalse();
        int windows = onFx(() -> Window.getWindows().size());
        runOnFx(export::fire);
        assertThat(checks).as("refused before the render check, whose dialog never opens").hasValue(0);
        assertThat(onFx(() -> Window.getWindows().size())).as("no window shows").isEqualTo(windows);
        assertNothingElseHappened();

        rig.allowed.set(true);
        runOnFx(export::fire);
        assertThat(checks).as("admitted: the export asks the render check").hasValue(1);
    }

    private void wavExportAfterItsChooser() throws Exception {
        File target = workspace.resolve("vox.wav").toFile();
        assertThat(onFx(() -> rig.controller.exportTrackAsWav(rig.vox, target))).as("refused").isFalse();
        assertThat(target).doesNotExist();
        assertThat(infoLines()).containsExactly("The WAV export of “Vox” to “" + target.getAbsolutePath()
                + "” was not written: the project could not be edited.");
        assertNothingElseHappened();

        rig.allowed.set(true);
        assertThat(onFx(() -> rig.controller.exportTrackAsWav(rig.vox, target))).as("admitted").isTrue();
    }

    private void midiExportAfterItsChooser() throws Exception {
        File target = workspace.resolve("keys.mid").toFile();
        List<MidiNote> notes = List.of(new MidiNote(60, 0, 4, 100));
        assertThat(onFx(() -> rig.controller.exportTrackAsMidi(rig.keys, notes, target))).as("refused").isFalse();
        assertThat(target).doesNotExist();
        assertThat(infoLines()).containsExactly("The MIDI export of “Keys” to “" + target.getAbsolutePath()
                + "” was not written: the project could not be edited.");
        assertNothingElseHappened();

        rig.allowed.set(true);
        assertThat(onFx(() -> rig.controller.exportTrackAsMidi(rig.keys, notes, target))).as("admitted").isTrue();
        assertThat(target).exists();
    }

    /**
     * Ruling-3 G3: a pick the gate refuses — the gate closing while the
     * input-port dialog asked — leaves the track's input, the I/O label's
     * tooltip and the status as they were, and marks nothing dirty; the same
     * pick admitted names the device in the tooltip.
     */
    @Test
    void aRefusedInputPickLeavesTheTooltipOnTheTracksInput() throws Exception {
        MockAudioBackend backend = new MockAudioBackend();
        runOnFx(() -> rig.engine.setStreamingProvision(new StreamingProvision(backend.name(),
                List.of(new BackendStreamRung(backend, DeviceId.defaultFor(backend.name()))))));
        AtomicBoolean closeTheGateWhileItAsks = new AtomicBoolean(true);
        List<AudioDeviceInfo> picked = new CopyOnWriteArrayList<>();
        rig.allowed.set(true);
        runOnFx(() -> rig.controller.setInputPortChooserForTest((devices, preselected) -> {
            if (closeTheGateWhileItAsks.get()) {
                rig.allowed.set(false);
            }
            picked.add(devices.getFirst());
            return Optional.of(devices.getFirst());
        }));
        Label io = onFx(() -> TrackStripController.controlsOf(rig.lane).ioLabel());
        String tooltip = onFx(() -> io.getTooltip().getText());

        runOnFx(() -> doubleClick(io));
        assertThat(picked).as("the dialog was asked").hasSize(1);
        assertThat(onFx(() -> rig.vox.getInputDevice())).as("refused: the track's input is unchanged").isEmpty();
        assertThat(onFx(() -> io.getTooltip().getText())).as("and the tooltip names it still").isEqualTo(tooltip);
        assertNothingElseHappened();

        closeTheGateWhileItAsks.set(false);
        rig.allowed.set(true);
        runOnFx(() -> doubleClick(io));
        assertThat(onFx(() -> rig.vox.getInputDevice())).isPresent();
        assertThat(onFx(() -> io.getTooltip().getText())).isEqualTo("Input: " + picked.getLast().qualifiedName());
        assertThat(rig.dirtyMarks).hasValue(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** No status, notification, history entry, dirty mark or publication. */
    private void assertNothingElseHappened() throws Exception {
        assertThat(onFx(rig.status::getText)).as("no status text").isEqualTo("before");
        assertThat(onFx(() -> rig.notifications.getEntries())).as("no notification").isEmpty();
        assertThat(rig.undo.undoSize()).as("no history entry").isZero();
        assertThat(rig.dirtyMarks).as("nothing marked dirty").hasValue(0);
        assertThat(published).as("nothing published").isEmpty();
    }

    private List<String> infoLines() {
        return logged.stream().filter(record -> record.getLevel() == Level.INFO)
                .map(LogRecord::getMessage).toList();
    }

    private static MenuItem item(ContextMenu menu, String text) {
        return menu.getItems().stream().filter(item -> text.equals(item.getText())).findFirst().orElseThrow();
    }

    private static void doubleClick(Node node) {
        node.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 2,
                false, false, false, false, true, false, false, false, false, true, null));
    }

    private static Stream<Node> nodes(Node node) {
        return node instanceof Parent parent
                ? Stream.concat(Stream.of(node), parent.getChildrenUnmodifiable().stream()
                        .flatMap(ArrangementEditsAreRefusedWhileProjectActionsAreNotAllowedTest::nodes))
                : Stream.of(node);
    }

    /** A lane of Vox (with a clip) and the MIDI track Keys, behind a gate the row controls. FX thread. */
    private static final class Rig {
        final AtomicBoolean allowed = new AtomicBoolean(false);
        final DawProject project = new DawProject("Lanes", FORMAT);
        final Track vox = project.createAudioTrack("Vox");
        final Track keys = project.createMidiTrack("Keys");
        final UndoManager undo = new UndoManager();
        final FxDispatcher dispatcher = new FxDispatcher();
        final AudioEngine engine = new AudioEngine(project.getFormat());
        final TrackControlWiring wiring = TrackControlWiring.standalone(project, dispatcher, null);
        final MixerView mixerView = new MixerView(project, undo, dispatcher);
        final NotificationHistoryService notifications = new NotificationHistoryService();
        final NotificationBar notificationBar = new NotificationBar();
        final Label status = new Label("before");
        final VBox trackList = new VBox(new Label("TRACKS"));
        final AtomicInteger dirtyMarks = new AtomicInteger();
        final TrackStripController controller;
        final HBox lane;

        Rig() {
            vox.addClip(new AudioClip("Take 1", 0.0, 4.0, null));
            notificationBar.setAnimated(false);
            notificationBar.setHistoryService(notifications);
            controller = new TrackStripController(project, undo, engine, mixerView, notificationBar, status,
                    trackList, new BorderPane(), new ClipboardManager(), new SelectionModel(),
                    () -> { }, () -> { }, () -> { }, () -> { }, () -> { }, () -> { }, dirtyMarks::incrementAndGet,
                    () -> false, ZoomLevel::new, () -> null, () -> wiring, () -> "", allowed::get);
            controller.setArrangementCanvas(new ArrangementCanvas());
            lane = controller.addTrackToUI(vox);
        }

        ContextMenu menu() {
            return controller.buildTrackContextMenu(vox, TrackStripController.controlsOf(lane).nameLabel(), lane);
        }

        void close() {
            controller.dispose();
            mixerView.dispose();
            wiring.dispose();
            dispatcher.dispose();
            engine.shutdown();
        }
    }
}
