package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.spatial.AtmosAbView;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.reference.ReferenceTrack;
import com.benesquivelmusic.daw.core.reference.ReferenceTrackManager;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;
import com.benesquivelmusic.daw.sdk.edit.RippleMode;
import com.benesquivelmusic.daw.sdk.event.DawEvent;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.MixerEvent;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.AccessibleAction;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The toolbar's and the editors' edits read the window's edit gate (PR #989
 * review 5711460812; rulings R-1.2, R-5.1 D, ruling-2 F5, ruling-3 G3,
 * ruling-4 H2): the ripple mode, Generate Telemetry, the clip editor's trim
 * and its notification pill's Undo, the freeze and unfreeze entries — read
 * again after a freeze's render check, which can ask a question — the
 * Immersive A/B reference toggle and auto-trim, adding an audio or a MIDI
 * track before and after its input dialog, and an audio import. While the
 * gate says no, the act changes nothing, adds no history entry, publishes
 * nothing and opens no window; once it says yes, the same act edits.
 *
 * <p>A row that counts the windows an act shows records each added window
 * and posts its hide, never hiding inside the add notification (ruling-4
 * H2); the row removes its listener first and then hides every recorded
 * window still showing. That needs implicit exit off, which
 * {@link JavaFxToolkitExtension} sets.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ToolbarAndEditorEditsAreRefusedWhileProjectActionsAreNotAllowedTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 256);
    private static final long STATUS_SECONDS = 15;

    @TempDir
    Path workspace;

    private final AtomicBoolean allowed = new AtomicBoolean(false);
    private final List<DawEvent> published = new CopyOnWriteArrayList<>();
    private EventBus previousBus;
    private DefaultEventBus bus;
    private EventBus.Subscription recording;

    @BeforeEach
    void recordThePublications() {
        previousBus = EventBusPublisher.getDefault();
        bus = new DefaultEventBus();
        recording = bus.onPublication(DawEvent.class, published::add);
        EventBusPublisher.setDefault(bus);
    }

    @AfterEach
    void restoreTheBus() {
        recording.close();
        EventBusPublisher.setDefault(previousBus);
        bus.close();
    }

    /** One toolbar or editor handler: how its refusal and its admission show. */
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
        void refusedThenAdmitted(ToolbarAndEditorEditsAreRefusedWhileProjectActionsAreNotAllowedTest test)
                throws Exception;
    }

    static Stream<Row> rows() {
        return Stream.of(
                new Row("ripple cycle (no window opens for ALL_TRACKS)",
                        ToolbarAndEditorEditsAreRefusedWhileProjectActionsAreNotAllowedTest::rippleCycle),
                new Row("telemetry generate",
                        ToolbarAndEditorEditsAreRefusedWhileProjectActionsAreNotAllowedTest::telemetryGenerate),
                new Row("editor trim and the pill's Undo through the history door",
                        ToolbarAndEditorEditsAreRefusedWhileProjectActionsAreNotAllowedTest::editorTrim),
                new Row("freeze entries", ToolbarAndEditorEditsAreRefusedWhileProjectActionsAreNotAllowedTest::freezeEntries),
                new Row("freezeIsRefusedWhenTheGateClosesWhileItsRenderCheckAsks",
                        test -> test.freezeAfterTheRenderCheck(false)),
                new Row("batchFreezeIsRefusedWhenTheGateClosesWhileItsRenderCheckAsks",
                        test -> test.freezeAfterTheRenderCheck(true)),
                new Row("immersiveAbReferenceToggleAndAutoTrimAreRefused",
                        ToolbarAndEditorEditsAreRefusedWhileProjectActionsAreNotAllowedTest::immersiveAb),
                new Row("addAudioTrackIsRefusedBeforeItsInputPortDialog",
                        ToolbarAndEditorEditsAreRefusedWhileProjectActionsAreNotAllowedTest::addAudioTrackBeforeItsDialog),
                new Row("addAudioTrackIsRefusedWhenTheGateClosesWhileItsInputPortDialogIsOpen",
                        ToolbarAndEditorEditsAreRefusedWhileProjectActionsAreNotAllowedTest::addAudioTrackAfterItsDialog),
                new Row("addMidiTrackIsRefusedBeforeItsDialog",
                        ToolbarAndEditorEditsAreRefusedWhileProjectActionsAreNotAllowedTest::addMidiTrackBeforeItsDialog),
                new Row("importAudioFileIsRefused",
                        ToolbarAndEditorEditsAreRefusedWhileProjectActionsAreNotAllowedTest::importAudioFile));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void anEditIsRefusedWhileTheGateSaysNoAndAdmittedOnceItSaysYes(Row row) throws Exception {
        row.act().refusedThenAdmitted(this);
    }

    // ── the rows ─────────────────────────────────────────────────────────────

    private void rippleCycle() throws Exception {
        Preferences preferences = Preferences.userRoot().node("rippleGate_" + System.nanoTime());
        try {
            DawProject project = new DawProject("Ripple", FORMAT);
            AtomicInteger dirty = new AtomicInteger();
            Button cycle = onFx(Button::new);
            RippleModeController ripple = onFx(() -> {
                NotificationBar bar = new NotificationBar();
                bar.setAnimated(false);
                return new RippleModeController(() -> project, dirty::incrementAndGet, bar,
                        new ToolbarStateStore(preferences), cycle, new Label(), allowed::get);
            });
            runOnFx(() -> cycle.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(project.getRippleMode()).as("refused: the mode is unchanged").isEqualTo(RippleMode.OFF);
            assertThat(onFx(cycle::getText)).as("the button shows the project's mode")
                    .isEqualTo(RippleMode.OFF.displayName());
            assertThat(windowsShownBy(() -> ripple.setMode(RippleMode.ALL_TRACKS)))
                    .as("ALL_TRACKS is refused before its confirmation: no window opens").isZero();
            assertThat(project.getRippleMode()).isEqualTo(RippleMode.OFF);
            assertThat(dirty).as("nothing dirtied").hasValue(0);

            allowed.set(true);
            runOnFx(() -> cycle.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(project.getRippleMode()).isEqualTo(RippleMode.PER_TRACK);
            assertThat(dirty).hasValue(1);
        } finally {
            preferences.removeNode();
        }
    }

    private void telemetryGenerate() throws Exception {
        DawProject project = new DawProject("Room", FORMAT);
        AtomicInteger dirtied = new AtomicInteger();
        TelemetryView view = onFx(() -> {
            TelemetryView telemetry = new TelemetryView();
            telemetry.setProject(project);
            telemetry.setOnDirtyChanged(dirtied::incrementAndGet);
            telemetry.setProjectActionsAllowed(allowed::get);
            TelemetrySetupPanel panel = telemetry.getSetupPanel();
            panel.getSourceNameField().setText("Guitar");
            panel.getSourceXField().setText("2.0");
            panel.getSourceYField().setText("3.0");
            panel.getSourceZField().setText("1.0");
            panel.getAddSourceButton().fire();
            panel.getMicNameField().setText("Overhead");
            panel.getMicXField().setText("3.0");
            panel.getMicYField().setText("4.0");
            panel.getMicZField().setText("1.5");
            panel.getAddMicButton().fire();
            return telemetry;
        });
        boolean dirtyBefore = project.isDirty();
        runOnFx(() -> view.getGenerateButton().executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(project.getRoomConfiguration()).as("refused: the room is not saved").isNull();
        assertThat(project.isDirty()).as("nothing dirtied").isEqualTo(dirtyBefore);
        assertThat(dirtied).hasValue(0);
        assertThat(onFx(view::isDisplayingTelemetry)).as("the view stays in setup").isFalse();

        allowed.set(true);
        runOnFx(() -> view.getGenerateButton().executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(project.getRoomConfiguration()).isNotNull();
        assertThat(dirtied).hasValue(1);
    }

    private void editorTrim() throws Exception {
        DawProject project = new DawProject("Trim", FORMAT);
        UndoManager undo = new UndoManager();
        Track track = project.createAudioTrack("Vox");
        AudioClip clip = new AudioClip("c", 0.0, 4.0, null);
        track.addClip(clip);
        List<Runnable> pills = new CopyOnWriteArrayList<>();
        AtomicInteger historyDoor = new AtomicInteger();
        AtomicInteger dirty = new AtomicInteger();
        EditorView editor = onFx(EditorView::new);
        runOnFx(() -> editor.setTrack(track));
        ClipEditController controller = new ClipEditController(new ClipEditController.Deps(
                () -> project, () -> undo, new ClipboardManager(), new SelectionModel(),
                () -> { }, () -> { }, dirty::incrementAndGet, (text, icon) -> { },
                (level, message, undoCallback) -> pills.add(undoCallback), (level, message) -> { },
                () -> editor, project::getRippleMode, () -> 1.0,
                historyDoor::incrementAndGet, allowed::get));
        runOnFx(controller::onEditorTrim);
        assertThat(clip.getDurationBeats()).as("refused: the clip is untouched").isEqualTo(4.0);
        assertThat(undo.undoSize()).as("no history entry").isZero();
        assertThat(pills).as("no pill").isEmpty();
        assertThat(dirty).hasValue(0);

        allowed.set(true);
        runOnFx(controller::onEditorTrim);
        assertThat(clip.getDurationBeats()).isLessThan(4.0);
        assertThat(undo.undoSize()).isEqualTo(1);
        assertThat(pills).hasSize(1);
        runOnFx(() -> pills.getFirst().run());
        assertThat(historyDoor).as("the pill's Undo goes through the gated history door").hasValue(1);
        assertThat(undo.undoSize()).as("never the raw history").isEqualTo(1);
    }

    private void freezeEntries() throws Exception {
        FreezeRig rig = new FreezeRig();
        Track vox = rig.audioTrack("Vox");
        Track bass = rig.audioTrack("Bass");
        assertThat(windowsShownBy(() -> {
            rig.controller.freezeTrack(vox);
            rig.controller.unfreezeTrack(vox);
            rig.controller.freezeTracks(List.of(vox, bass));
            rig.controller.unfreezeTracks(List.of(vox, bass));
        })).as("refused at entry: no progress window").isZero();
        runOnFx(() -> { });
        assertThat(rig.statuses).as("refused before any status, even 'Track is not frozen'").isEmpty();
        assertThat(vox.isFrozen() || bass.isFrozen()).isFalse();
        assertThat(rig.undo.undoSize()).isZero();

        allowed.set(true);
        runOnFx(() -> rig.controller.freezeTrack(vox));
        assertThat(rig.awaitStatus()).isEqualTo("Frozen: Vox");
        assertThat(rig.undo.undoSize()).isEqualTo(1);
    }

    private void freezeAfterTheRenderCheck(boolean batch) throws Exception {
        FreezeRig rig = new FreezeRig();
        Track vox = rig.audioTrack("Vox");
        Track bass = rig.audioTrack("Bass");
        AtomicInteger checks = new AtomicInteger();
        AtomicBoolean closeWhileItAsks = new AtomicBoolean(true);
        rig.controller.setRenderGuard(tracks -> {
            checks.incrementAndGet();
            if (closeWhileItAsks.get()) {
                allowed.set(false); // the silent-clips confirmation answered after the close was raised
            }
            return true;
        });
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger(TrackFreezeController.class.getName());
        Level level = logger.getLevel();
        Handler capture = recordingHandler(records);
        logger.addHandler(capture);
        logger.setLevel(Level.ALL);
        try {
            allowed.set(true);
            int windows = windowsShownBy(() -> {
                if (batch) {
                    rig.controller.freezeTracks(List.of(vox, bass));
                } else {
                    rig.controller.freezeTrack(vox);
                }
            });
            runOnFx(() -> { });
            assertThat(checks).as("the render check asked once").hasValue(1);
            assertThat(windows).as("refused after the check: no progress window").isZero();
            assertThat(vox.isFrozen() || bass.isFrozen()).as("nothing frozen").isFalse();
            assertThat(rig.undo.undoSize()).isZero();
            assertThat(rig.statuses).as("no status").isEmpty();
            assertThat(records).as("no log record: nothing was started, so nothing is discarded").isEmpty();

            allowed.set(true);
            closeWhileItAsks.set(false);
            int admitted = windowsShownBy(() -> {
                if (batch) {
                    rig.controller.freezeTracks(List.of(vox, bass));
                } else {
                    rig.controller.freezeTrack(vox);
                }
            });
            assertThat(admitted).as("admitted: the progress window shows").isEqualTo(1);
            assertThat(rig.awaitStatus()).isEqualTo(batch ? "Frozen 2 track(s)" : "Frozen: Vox");
            assertThat(rig.undo.undoSize()).isEqualTo(1);
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(level);
        }
    }

    private void immersiveAb() throws Exception {
        DawProject project = new DawProject("Atmos", FORMAT);
        ReferenceTrackManager manager = project.getReferenceTrackManager();
        ReferenceTrack reference = new ReferenceTrack("Ref", "ref.wav");
        manager.addReferenceTrack(reference);
        manager.setActiveIndex(0);
        double[] trimsBefore = reference.getPerChannelTrimDb() == null
                ? null : reference.getPerChannelTrimDb().clone();
        AtmosAbView view = onFx(() -> {
            AtmosAbView ab = new AtmosAbView(manager, allowed::get);
            float[][] mix = {filled(4_800, 0.5f), filled(4_800, 0.5f)};
            float[][] ref = {filled(4_800, 0.25f), filled(4_800, 0.25f)};
            ab.updateBuffers(mix, ref, 48_000);
            return ab;
        });
        CheckBox toggle = onFx(() -> nodes(view).filter(CheckBox.class::isInstance).map(CheckBox.class::cast)
                .findFirst().orElseThrow());
        Button autoTrim = onFx(() -> nodes(view).filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> "Auto-Trim".equals(button.getText())).findFirst().orElseThrow());
        String status = onFx(() -> view.statusProperty().get());
        runOnFx(() -> {
            toggle.executeAccessibleAction(AccessibleAction.FIRE);
            autoTrim.executeAccessibleAction(AccessibleAction.FIRE);
        });
        assertThat(manager.isReferenceActive()).as("refused: the mix plays").isFalse();
        assertThat(onFx(toggle::isSelected)).as("the toggle shows the manager").isFalse();
        assertThat(reference.getPerChannelTrimDb()).as("refused: no trim is stored").isEqualTo(trimsBefore);
        assertThat(onFx(() -> view.statusProperty().get())).as("and the status is unchanged").isEqualTo(status);

        allowed.set(true);
        runOnFx(() -> {
            toggle.executeAccessibleAction(AccessibleAction.FIRE);
            autoTrim.executeAccessibleAction(AccessibleAction.FIRE);
        });
        assertThat(manager.isReferenceActive()).isTrue();
        assertThat(reference.getPerChannelTrimDb()).isNotEqualTo(trimsBefore);
    }

    private void addAudioTrackBeforeItsDialog() throws Exception {
        CreationRig rig = new CreationRig();
        try {
            runOnFx(rig.controller::onAddAudioTrack);
            assertThat(rig.chooserCalls).as("refused before its dialog: the chooser is never asked").hasValue(0);
            rig.assertNothingAdded();
        } finally {
            rig.close();
        }
    }

    private void addAudioTrackAfterItsDialog() throws Exception {
        CreationRig rig = new CreationRig();
        try {
            allowed.set(true);
            rig.closeWhileItAsks.set(true);
            runOnFx(rig.controller::onAddAudioTrack);
            assertThat(rig.chooserCalls).as("the dialog asked").hasValue(1);
            rig.assertNothingAdded();
            assertThat(published).as("no channel was added").noneMatch(MixerEvent.ChannelAdded.class::isInstance);
            assertThat(rig.sinkCalls).as("no status, notification or dirty mark").hasValue(0);

            allowed.set(true);
            rig.closeWhileItAsks.set(false);
            runOnFx(rig.controller::onAddAudioTrack);
            assertThat(onFx(() -> rig.fixture.project.getTracks().getLast().getName())).isEqualTo("Audio 1");
            assertThat(rig.fixture.undoManager.undoSize()).isEqualTo(1);
        } finally {
            rig.close();
        }
    }

    private void addMidiTrackBeforeItsDialog() throws Exception {
        CreationRig rig = new CreationRig();
        try {
            assertThat(windowsShownBy(rig.controller::onAddMidiTrack))
                    .as("refused before its dialog: no window opens").isZero();
            assertThat(rig.controller.getMidiTrackCounter()).isZero();
            rig.assertNothingAdded();
        } finally {
            rig.close();
        }
    }

    private void importAudioFile() throws Exception {
        DawProject project = new DawProject("Import", FORMAT);
        Track track = project.createAudioTrack("Vox");
        UndoManager undo = new UndoManager();
        AtomicInteger dirty = new AtomicInteger();
        MixerView mixer = onFx(() -> new MixerView(project, null));
        Path file = OpenProjectFixture.wav(workspace.resolve("tone.wav"), 48_000, 0.5, 440.0);
        AudioImportController controller = new AudioImportController(new AudioImportController.Deps(
                () -> project, () -> undo, () -> null, () -> null, () -> mixer, javafx.scene.layout.VBox::new,
                () -> null, () -> { }, () -> { }, dirty::incrementAndGet, (message, icon) -> { },
                (level, message) -> { }, allowed::get));
        try {
            assertThat(onFx(() -> controller.importAudioFile(file, track, 0.0))).as("refused").isFalse();
            assertThat(track.getClips()).as("no clip").isEmpty();
            assertThat(undo.undoSize()).as("no history entry").isZero();
            assertThat(dirty).as("not dirtied").hasValue(0);

            allowed.set(true);
            assertThat(onFx(() -> controller.importAudioFile(file, track, 0.0))).isTrue();
            assertThat(track.getClips()).hasSize(1);
        } finally {
            runOnFx(mixer::dispose);
        }
    }

    // ── rigs ─────────────────────────────────────────────────────────────────

    /** TrackFreezeControllerTest's session shape behind the gate: live suppliers, no owner, a status queue. */
    private final class FreezeRig {
        final DawProject project = new DawProject("Freeze", AudioFormat.STUDIO_QUALITY);
        final UndoManager undo = new UndoManager();
        final BlockingQueue<String> statuses = new LinkedBlockingQueue<>();
        final TrackFreezeController controller = new TrackFreezeController(
                () -> project, () -> undo, null, _ -> { }, () -> { }, (message, _) -> statuses.add(message));

        FreezeRig() {
            controller.setProjectActionsAllowed(allowed::get);
        }

        /** An unfrozen audio track with half a second of audio, added to the project. */
        Track audioTrack(String name) {
            Track track = new Track(name, TrackType.AUDIO);
            AudioClip clip = new AudioClip("c", 0.0, 1.0, null);
            int frames = (int) Math.round(60.0 / 120.0 * project.getFormat().sampleRate());
            clip.setAudioData(new float[project.getFormat().channels()][frames]);
            track.addClip(clip);
            project.addTrack(track);
            return track;
        }

        String awaitStatus() throws Exception {
            String status = statuses.poll(STATUS_SECONDS, TimeUnit.SECONDS);
            assertThat(status).as("a status was reported within %d s", STATUS_SECONDS).isNotNull();
            runOnFx(() -> { });
            return status;
        }
    }

    /** A TrackCreationController over ArrangementStripFixture behind the gate, with a scripted input-port dialog. */
    private final class CreationRig {
        final ArrangementStripFixture fixture;
        final TrackCreationController controller;
        final AtomicInteger chooserCalls = new AtomicInteger();
        final AtomicInteger sinkCalls = new AtomicInteger();
        final AtomicBoolean closeWhileItAsks = new AtomicBoolean();
        final int tracksBefore;

        CreationRig() throws Exception {
            fixture = ArrangementStripFixture.onFx(() -> new ArrangementStripFixture(
                    new DawProject("Create", FORMAT), false));
            MockAudioBackend backend = new MockAudioBackend();
            runOnFx(() -> fixture.audioEngine.setStreamingProvision(new StreamingProvision(backend.name(),
                    List.of(new BackendStreamRung(backend, DeviceId.defaultFor(backend.name()))))));
            AudioDeviceInfo device = new AudioDeviceInfo(0, "Interface", "Mock", 8, 2, 48_000.0,
                    List.of(SampleRate.HZ_48000), 0.0, 0.0);
            controller = new TrackCreationController(
                    new TrackCreationController.Deps(
                            () -> fixture.project, () -> fixture.undoManager, () -> fixture.controller,
                            () -> fixture.mixerView, () -> fixture.trackListPanel, () -> { },
                            sinkCalls::incrementAndGet, (text, icon) -> sinkCalls.incrementAndGet(),
                            (level, message) -> sinkCalls.incrementAndGet(),
                            fixture.sessionInputSelection, allowed::get),
                    new com.benesquivelmusic.daw.core.audio.AudioDeviceManager(fixture.audioEngine),
                    (devices, preselected) -> {
                        chooserCalls.incrementAndGet();
                        if (closeWhileItAsks.get()) {
                            allowed.set(false); // the close raised while the modal dialog asked
                        }
                        return Optional.of(device);
                    });
            tracksBefore = fixture.project.getTracks().size();
            published.clear();
        }

        void assertNothingAdded() {
            assertThat(fixture.project.getTracks()).as("no track is added").hasSize(tracksBefore);
            assertThat(controller.getAudioTrackCounter()).as("the counter is untouched").isZero();
            assertThat(fixture.undoManager.undoSize()).as("no history entry").isZero();
        }

        void close() throws Exception {
            ArrangementStripFixture.onFx(fixture::close);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static float[] filled(int length, float value) {
        float[] samples = new float[length];
        Arrays.fill(samples, value);
        return samples;
    }

    private static Handler recordingHandler(List<LogRecord> records) {
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                records.add(record);
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        handler.setLevel(Level.ALL);
        return handler;
    }

    /**
     * Runs {@code act} on the FX thread and counts the windows it shows: the
     * listener records each added window and posts its hide (ruling-4 H2);
     * it is removed first, then every recorded window still showing is
     * hidden.
     */
    private static int windowsShownBy(Runnable act) throws Exception {
        List<Window> shown = new ArrayList<>();
        ListChangeListener<Window> counter = change -> {
            while (change.next()) {
                for (Window window : change.getAddedSubList()) {
                    shown.add(window);
                    Platform.runLater(window::hide);
                }
            }
        };
        runOnFx(() -> {
            Window.getWindows().addListener(counter);
            try {
                act.run();
            } finally {
                Window.getWindows().removeListener(counter);
                for (Window window : shown) {
                    if (window.isShowing()) {
                        window.hide();
                    }
                }
            }
        });
        return shown.size();
    }

    private static Stream<Node> nodes(Node node) {
        return node instanceof Parent parent
                ? Stream.concat(Stream.of(node), parent.getChildrenUnmodifiable().stream()
                        .flatMap(ToolbarAndEditorEditsAreRefusedWhileProjectActionsAreNotAllowedTest::nodes))
                : Stream.of(node);
    }
}
