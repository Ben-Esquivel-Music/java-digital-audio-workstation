package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.recording.InputRoutingGuard;
import com.benesquivelmusic.daw.app.ui.vm.TrackControlBinder;
import com.benesquivelmusic.daw.app.ui.vm.TrackControlWiring;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.css.PseudoClass;
import javafx.scene.AccessibleAction;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Work an intent started before the window's edit gate said no, that lands
 * while it says no, is discarded when it lands (ruling R-1.5; PR #989 review
 * 5711460812): an insert rack's external plug-in load is not inserted — its
 * slot is disposed, the rack is enabled again and no editor opens — and a
 * user's arm is not completed — the track stays unarmed and its button shows
 * it — each with its ruled INFO line. Deferral is rejected, so nothing is
 * queued for later. The input-routing guard's own corrective re-arm is not an
 * intent (the origin rule, ruling-2 F3): it completes while the gate says no
 * and logs nothing, after a routing change and when the guard is built over
 * an armed track as a project's installation builds it.
 *
 * <p>So are a plug-in the menu activates, which is not inserted and is
 * disposed; a freeze whose worker started before the gate closed, which does
 * not run and closes its progress window; and the missing-assets relink: a
 * located file's check and a folder search that land are not applied, their
 * rows idle again, and Locate file is refused, silently, before its
 * chooser; and so is the report's Archive link, which is refused again,
 * with its INFO line, when the gate says no once its chooser returns.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class WorkStartedBeforeTheCloseIsDiscardedWhenItLandsTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 256);
    private static final long GUARD_SECONDS = 20;
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");

    private final AtomicBoolean allowed = new AtomicBoolean(true);
    private final List<LogRecord> infos = new CopyOnWriteArrayList<>();
    /** Counted down at the next INFO record. */
    private final AtomicReference<CountDownLatch> nextInfo = new AtomicReference<>(new CountDownLatch(1));
    private final Handler capture = new Handler() {
        @Override public void publish(LogRecord record) {
            if (record.getLevel() == Level.INFO) {
                infos.add(record);
                nextInfo.get().countDown();
            }
        }
        @Override public void flush() { }
        @Override public void close() { }
    };
    private final List<Logger> captured = List.of(Logger.getLogger(InsertEffectRack.class.getName()),
            Logger.getLogger(InputRoutingGuard.class.getName()),
            Logger.getLogger(PluginViewController.class.getName()),
            Logger.getLogger(TrackFreezeController.class.getName()),
            Logger.getLogger(AssetRelinkFlow.class.getName()));

    @TempDir
    Path workspace;

    @BeforeEach
    void captureTheLandingsInfoLines() {
        capture.setLevel(Level.ALL);
        captured.forEach(logger -> logger.addHandler(capture));
    }

    @AfterEach
    void releaseTheLoggers() {
        captured.forEach(logger -> logger.removeHandler(capture));
    }

    /** One landing: how it is discarded, or, for a corrective write, how it still completes. */
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
        void run(WorkStartedBeforeTheCloseIsDiscardedWhenItLandsTest test) throws Exception;
    }

    static Stream<Row> rows() {
        return Stream.of(
                new Row("aRackPluginLoadThatLandsIsNotInserted",
                        WorkStartedBeforeTheCloseIsDiscardedWhenItLandsTest::aRackPluginLoadThatLands),
                new Row("anArmThatLandsIsNotCompleted",
                        WorkStartedBeforeTheCloseIsDiscardedWhenItLandsTest::anArmThatLands),
                new Row("aRoutingChangeOnAnArmedTrackReArmsItWhileInert",
                        WorkStartedBeforeTheCloseIsDiscardedWhenItLandsTest::aRoutingChangeOnAnArmedTrack),
                new Row("aGuardBuiltOverAnArmedTrackWhileTheGateSaysNoKeepsItArmed",
                        WorkStartedBeforeTheCloseIsDiscardedWhenItLandsTest::aGuardBuiltOverAnArmedTrack),
                new Row("aMenuPluginActivationThatLandsIsNotInserted",
                        WorkStartedBeforeTheCloseIsDiscardedWhenItLandsTest::aMenuPluginActivationThatLands),
                new Row("aFreezeThatLandsDoesNotRun",
                        WorkStartedBeforeTheCloseIsDiscardedWhenItLandsTest::aFreezeThatLands),
                new Row("aRelinkThatLandsIsNotApplied",
                        WorkStartedBeforeTheCloseIsDiscardedWhenItLandsTest::aRelinkThatLands),
                new Row("aFolderSearchThatLandsRelinksNothing",
                        WorkStartedBeforeTheCloseIsDiscardedWhenItLandsTest::aFolderSearchThatLands),
                new Row("anArchiveFromTheReportIsRefusedBeforeItsChooser",
                        WorkStartedBeforeTheCloseIsDiscardedWhenItLandsTest::anArchiveBeforeItsChooser),
                new Row("anArchiveIsRefusedWhenTheGateClosesWhileItsChooserIsOpen",
                        WorkStartedBeforeTheCloseIsDiscardedWhenItLandsTest::anArchiveWhoseChooserLands),
                new Row("aLocateFileIsRefusedBeforeItsChooser",
                        WorkStartedBeforeTheCloseIsDiscardedWhenItLandsTest::aLocateFileBeforeItsChooser));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void workThatLandsWhileTheGateSaysNo(Row row) throws Exception {
        row.act().run(this);
    }

    // ── the rows ─────────────────────────────────────────────────────────────

    private void aRackPluginLoadThatLands() throws Exception {
        DawProject project = new DawProject("Rack", FORMAT);
        MixerChannel channel = project.getMixerChannelForTrack(project.createAudioTrack("Vox"));
        UndoManager undo = new UndoManager();
        FxDispatcher dispatcher = new FxDispatcher();
        AtomicInteger editors = new AtomicInteger();
        InsertEffectRack rack = onFx(() -> {
            InsertEffectRack built = new InsertEffectRack(channel, 2, FORMAT.sampleRate(), FORMAT.bufferSize(),
                    undo, dispatcher, allowed::get);
            built.setOnOpenEditor((_, _) -> editors.incrementAndGet());
            return built;
        });
        try {
            InsertSlot loaded = InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, 2, FORMAT.sampleRate());
            CountDownLatch disposed = new CountDownLatch(1);
            loaded.setDisposal(disposed::countDown);
            // The load started while the project could be edited: it greyed the rack, as the load's first step does.
            runOnFx(() -> rack.setDisable(true));
            allowed.set(false);

            runOnFx(() -> rack.completeExternalInsert(0, loaded));

            assertThat(disposed.await(GUARD_SECONDS, TimeUnit.SECONDS)).as("the loaded slot was disposed").isTrue();
            assertThat(channel.getInsertSlots()).as("not inserted").isEmpty();
            assertThat(onFx(rack::isDisabled)).as("the rack is enabled again").isFalse();
            assertThat(editors).as("no editor opened").hasValue(0);
            assertThat(undo.undoSize()).as("no history entry").isZero();
            assertThat(messages()).containsExactly("The plug-in “" + loaded.getName()
                    + "” finished loading while the project could not be edited; it was not inserted.");

            allowed.set(true);
            InsertSlot another = InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, 2, FORMAT.sampleRate());
            runOnFx(() -> rack.completeExternalInsert(0, another));
            assertThat(channel.getInsertSlots()).as("admitted: inserted").containsExactly(another);
            assertThat(editors).as("and its editor opened").hasValue(1);
        } finally {
            runOnFx(() -> {
                rack.dispose();
                dispatcher.dispose();
            });
        }
    }

    private void anArmThatLands() throws Exception {
        try (ArmRig rig = onFx(() -> new ArmRig(allowed, false))) {
            Button arm = onFx(rig::boundArmButton);
            rig.backend.hold();
            runOnFx(() -> arm.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(rig.backend.entered.await(GUARD_SECONDS, TimeUnit.SECONDS))
                    .as("the arm's validation is in flight").isTrue();
            allowed.set(false);
            CountDownLatch refused = new CountDownLatch(1);
            nextInfo.set(refused);
            rig.backend.release();
            assertThat(refused.await(GUARD_SECONDS, TimeUnit.SECONDS)).as("the landing was refused").isTrue();

            assertThat(onFx(rig.vox::isArmed)).as("the track stays unarmed").isFalse();
            assertThat(onFx(() -> arm.getPseudoClassStates().contains(ACTIVE))).as("and its button shows it")
                    .isFalse();
            assertThat(messages()).containsExactly(
                    "Arming “Vox” was not completed: the project could not be edited.");
            assertThat(rig.errors).isEmpty();

            allowed.set(true);
            CountDownLatch armed = rig.awaitArmed();
            runOnFx(() -> arm.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(armed.await(GUARD_SECONDS, TimeUnit.SECONDS)).as("admitted: the same arm completes").isTrue();
            assertThat(onFx(() -> arm.getPseudoClassStates().contains(ACTIVE))).isTrue();
        }
    }

    private void aRoutingChangeOnAnArmedTrack() throws Exception {
        try (ArmRig rig = onFx(() -> new ArmRig(allowed, false))) {
            CountDownLatch armed = rig.awaitArmed();
            runOnFx(() -> rig.wiring.commandSink().accept(
                    new com.benesquivelmusic.daw.app.ui.vm.command.ToggleArmCommand(rig.vox, true)));
            assertThat(armed.await(GUARD_SECONDS, TimeUnit.SECONDS)).as("fixture: Vox is armed").isTrue();

            allowed.set(false);
            CountDownLatch disarmed = new CountDownLatch(1);
            CountDownLatch reArmed = new CountDownLatch(1);
            runOnFx(() -> {
                rig.vox.addChangeListener(kind -> {
                    if (kind == Track.ChangeKind.ARM) {
                        if (!rig.vox.isArmed()) disarmed.countDown();
                        else if (disarmed.getCount() == 0) reArmed.countDown();
                    }
                });
                rig.vox.setInputRouting(new InputRouting(2, 2));
            });
            assertThat(reArmed.await(GUARD_SECONDS, TimeUnit.SECONDS))
                    .as("the guard's corrective re-arm completes while the gate says no").isTrue();
            assertThat(onFx(rig.vox::isArmed)).isTrue();
            assertThat(messages()).as("a corrective write logs nothing").isEmpty();
        }
    }

    private void aGuardBuiltOverAnArmedTrack() throws Exception {
        allowed.set(false);
        try (ArmRig rig = onFx(() -> new ArmRig(allowed, true))) {
            assertThat(rig.armedAgain.await(GUARD_SECONDS, TimeUnit.SECONDS))
                    .as("the guard built over an armed track re-arms it while the gate says no").isTrue();
            assertThat(onFx(rig.vox::isArmed)).isTrue();
            assertThat(messages()).as("a corrective write logs nothing").isEmpty();
        }
    }

    private void aMenuPluginActivationThatLands() throws Exception {
        MixerChannel channel = new MixerChannel("Voice");
        AtomicBoolean closeWhileItLoads = new AtomicBoolean(true);
        AtomicReference<InsertSlot> loaded = new AtomicReference<>();
        CountDownLatch disposed = new CountDownLatch(1);
        PluginViewController controller = onFx(() -> new PluginViewController(new PluginViewController.Deps(
                () -> 48_000, () -> 512, () -> null, () -> { }, (message, icon) -> { }, (level, message) -> { },
                (segments, node) -> { }, () -> null, () -> { }, allowed::get)));
        try {
            runOnFx(() -> {
                controller.setActivationWorker(Runnable::run);
                controller.setRouting(new PluginViewController.Routing(() -> channel, () -> null, type -> {
                    InsertSlot slot = InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, 2, 48_000);
                    if (closeWhileItLoads.get()) {
                        slot.setDisposal(disposed::countDown);
                        loaded.set(slot);
                        allowed.set(false); // the close raised while the plug-in loaded
                    }
                    return slot;
                }, ignored -> { }), null);
                controller.onActivateBuiltInPlugin(com.benesquivelmusic.daw.core.plugin.CompressorPlugin.class);
            });
            assertThat(disposed.await(GUARD_SECONDS, TimeUnit.SECONDS)).as("the loaded slot was disposed").isTrue();
            assertThat(channel.getInsertSlots()).as("not inserted").isEmpty();
            assertThat(onFx(controller::activeEditorSessionForTest)).as("no editor opened").isNull();
            assertThat(messages()).containsExactly("The plug-in “" + loaded.get().getName()
                    + "” finished loading while the project could not be edited; it was not inserted.");

            allowed.set(true);
            closeWhileItLoads.set(false);
            runOnFx(() -> controller.onActivateBuiltInPlugin(
                    com.benesquivelmusic.daw.core.plugin.CompressorPlugin.class));
            assertThat(channel.getInsertSlots()).as("admitted: inserted").hasSize(1);
        } finally {
            runOnFx(controller::dispose);
        }
    }

    private void aFreezeThatLands() throws Exception {
        DawProject project = new DawProject("Freeze", AudioFormat.STUDIO_QUALITY);
        UndoManager undo = new UndoManager();
        BlockingQueue<String> statuses = new LinkedBlockingQueue<>();
        TrackFreezeController controller = new TrackFreezeController(
                () -> project, () -> undo, null, _ -> { }, () -> { }, (message, _) -> statuses.add(message));
        controller.setProjectActionsAllowed(allowed::get);
        Track vox = new Track("Vox", TrackType.AUDIO);
        AudioClip clip = new AudioClip("c", 0.0, 1.0, null);
        clip.setAudioData(new float[project.getFormat().channels()][(int) project.getFormat().sampleRate() / 2]);
        vox.addClip(clip);
        project.addTrack(vox);
        // The progress window is recorded, never hidden by the row: its close is what the landing does.
        List<Window> shown = new CopyOnWriteArrayList<>();
        ListChangeListener<Window> record = change -> {
            while (change.next()) {
                shown.addAll(change.getAddedSubList());
            }
        };
        CountDownLatch refused = new CountDownLatch(1);
        nextInfo.set(refused);
        try {
            runOnFx(() -> {
                Window.getWindows().addListener(record);
                controller.freezeTrack(vox);
                // The gate closes after the freeze started, so the freeze passes its second read and its
                // worker reaches the landing (ruling-4 H3).
                allowed.set(false);
            });
            assertThat(refused.await(GUARD_SECONDS, TimeUnit.SECONDS)).as("the landing was refused").isTrue();
            runOnFx(() -> { });
            assertThat(messages()).containsExactly(
                    "The freeze of “Vox” did not run: the project could not be edited.");
            assertThat(vox.isFrozen()).as("not frozen").isFalse();
            assertThat(undo.undoSize()).as("no FreezeTrackAction in the history").isZero();
            assertThat(statuses).as("no status").isEmpty();
            assertThat(shown).as("the progress window showed").hasSize(1);
            assertThat(onFx(() -> shown.getFirst().isShowing())).as("and closed as a cancelled freeze's does")
                    .isFalse();
        } finally {
            runOnFx(() -> {
                Window.getWindows().removeListener(record);
                for (Window window : shown) {
                    if (window.isShowing()) {
                        window.hide();
                    }
                }
            });
        }

        allowed.set(true);
        runOnFx(() -> controller.freezeTrack(vox));
        assertThat(statuses.poll(GUARD_SECONDS, TimeUnit.SECONDS)).as("admitted: it runs").isEqualTo("Frozen: Vox");
        assertThat(undo.undoSize()).isEqualTo(1);
    }

    private void aRelinkThatLands() throws Exception {
        try (RelinkRig rig = new RelinkRig(workspace.resolve("relink"))) {
            MissingAssetsDialog dialog = rig.openWithAMissingTone();
            runOnFx(() -> rig.locateFileButton(dialog).fire());
            allowed.set(false); // the close raised while the located file is checked
            rig.runWorkersThenPulse();

            assertThat(messages()).containsExactly(
                    "The relink of “tone.wav” was not applied: the project could not be edited.");
            rig.assertNotRelinked(dialog);
        }
    }

    private void aFolderSearchThatLands() throws Exception {
        try (RelinkRig rig = new RelinkRig(workspace.resolve("search"))) {
            MissingAssetsDialog dialog = rig.openWithAMissingTone();
            runOnFx(() -> rig.locateFolderButton(dialog).fire());
            allowed.set(false); // the close raised while the folder is searched
            rig.runWorkersThenPulse();

            assertThat(messages()).containsExactly("The search of “" + rig.folder.get()
                    + "” relinked nothing: the project could not be edited.");
            rig.assertNotRelinked(dialog);
        }
    }

    private void aLocateFileBeforeItsChooser() throws Exception {
        try (RelinkRig rig = new RelinkRig(workspace.resolve("before"))) {
            MissingAssetsDialog dialog = rig.openWithAMissingTone();
            allowed.set(false);
            runOnFx(() -> rig.locateFileButton(dialog).fire());
            assertThat(rig.fileChooserCalls).as("refused before its chooser: the locator is never asked").hasValue(0);
            assertThat(messages()).as("a synchronous refusal is silent").isEmpty();
            rig.assertNotRelinked(dialog);
        }
    }

    /**
     * The missing-assets report's Archive link, which an assistive
     * technology's action fires while the gate says no, is refused before its
     * chooser, silently: the chooser is never asked, nothing is archived and
     * the project stays clean. Once the gate says yes, the same action on the
     * same link asks for the archive's file.
     */
    private void anArchiveBeforeItsChooser() throws Exception {
        try (RelinkRig rig = new RelinkRig(workspace.resolve("archive-before"))) {
            MissingAssetsDialog dialog = rig.openWithAMissingTone();
            AtomicInteger chooserCalls = new AtomicInteger();
            runOnFx(() -> rig.fixture.lifecycle().setArchiveTargetChooser(_ -> {
                chooserCalls.incrementAndGet();
                return Optional.empty();
            }));
            Hyperlink archive = onFx(() -> rig.archiveLink(dialog));
            try (LifecycleInfo lines = new LifecycleInfo()) {
                allowed.set(false);
                runOnFx(() -> archive.executeAccessibleAction(AccessibleAction.FIRE));
                assertThat(chooserCalls).as("refused before its chooser: the chooser is never asked").hasValue(0);
                assertThat(lines.messages()).as("a synchronous refusal is silent").isEmpty();
                assertThat(rig.fixture.operationNames()).as("nothing is archived").doesNotContain("Archiving project…");
                assertThat(onFx(() -> rig.fixture.project.get().isDirty())).as("the project is still clean").isFalse();

                allowed.set(true);
                runOnFx(() -> archive.executeAccessibleAction(AccessibleAction.FIRE));
                assertThat(chooserCalls).as("positive control: the same link asks for the archive's file")
                        .hasValue(1);
            }
        }
    }

    /**
     * The gate says no while the archive's chooser is open, and the chooser
     * names the file without the archive's extension, as a platform's
     * chooser may: the archive is refused when the chooser returns, before
     * its plan dialog — no window shows and no archive is written at either
     * path — with the ruled INFO line, which names the file the archive would
     * have written, its extension added. With the gate open, a chooser that
     * names the same file goes on to the plan dialog, which the test backs
     * out of.
     */
    private void anArchiveWhoseChooserLands() throws Exception {
        Path root = workspace.resolve("archive-chooser");
        try (RelinkRig rig = new RelinkRig(root)) {
            MissingAssetsDialog dialog = rig.openWithAMissingTone();
            Path named = root.resolve("Relink archive");
            Path target = root.resolve("Relink archive.dawz");
            runOnFx(() -> rig.fixture.lifecycle().setArchiveTargetChooser(_ -> {
                allowed.set(false); // the close raised while the chooser was open
                return Optional.of(named);
            }));
            Hyperlink archive = onFx(() -> rig.archiveLink(dialog));
            String project = onFx(() -> rig.fixture.project.get().getName());
            try (LifecycleInfo lines = new LifecycleInfo()) {
                List<Window> refused = windowsShownBy(() -> archive.executeAccessibleAction(AccessibleAction.FIRE));
                assertThat(refused).as("refused after its chooser: no plan dialog shows").isEmpty();
                assertThat(lines.messages()).containsExactly("The archive of “" + project + "” to “" + target
                        + "” was not written: the project could not be edited.");
                assertThat(target).as("nothing is written").doesNotExist();
                assertThat(named).as("nor at the path the chooser named").doesNotExist();
            }

            runOnFx(() -> rig.fixture.lifecycle().setArchiveTargetChooser(_ -> Optional.of(named)));
            allowed.set(true);
            List<Window> admitted = windowsShownBy(() -> archive.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(admitted).as("positive control: the same link goes on to the plan dialog").hasSize(1);
            assertThat(target).as("which the test backed out of").doesNotExist();
            assertThat(named).doesNotExist();
        }
    }

    /**
     * Runs {@code act} on the FX thread and returns every window it showed,
     * each recorded as it is added and hidden on a later turn, never inside
     * its own add notification; the listener is removed, then any recorded
     * window still showing hidden, before this returns (ruling-4 H2).
     */
    private static List<Window> windowsShownBy(Runnable act) throws Exception {
        List<Window> shown = new CopyOnWriteArrayList<>();
        ListChangeListener<Window> counting = change -> {
            while (change.next()) {
                for (Window window : change.getAddedSubList()) {
                    shown.add(window);
                    Platform.runLater(window::hide);
                }
            }
        };
        runOnFx(() -> {
            Window.getWindows().addListener(counting);
            try {
                act.run();
            } finally {
                Window.getWindows().removeListener(counting);
                for (Window window : shown) {
                    if (window.isShowing()) {
                        window.hide();
                    }
                }
            }
        });
        return List.copyOf(shown);
    }

    /** The lifecycle controller's INFO lines while it is open, where an archive refused late logs. */
    private static final class LifecycleInfo implements AutoCloseable {
        private final Logger logger = Logger.getLogger(ProjectLifecycleController.class.getName());
        private final List<String> lines = new CopyOnWriteArrayList<>();
        private final Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel() == Level.INFO) {
                    lines.add(record.getMessage());
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };

        LifecycleInfo() {
            handler.setLevel(Level.ALL);
            logger.addHandler(handler);
        }

        List<String> messages() {
            return List.copyOf(lines);
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
        }
    }

    private List<String> messages() {
        return infos.stream().map(LogRecord::getMessage).toList();
    }

    // ── the relink rig ───────────────────────────────────────────────────────

    /**
     * RelinkMissingAssetTest's rig behind the row's gate: a reopened project
     * whose one source moved, the real report dialog (built, not shown), a
     * scripted file and folder chooser, and held relink workers whose results
     * run only on the fixture's pulse.
     */
    private final class RelinkRig implements AutoCloseable {
        final OpenProjectFixture fixture;
        final HeldRelinkWorkers workers = HeldRelinkWorkers.held();
        final List<MissingAssetsDialog> dialogs = new CopyOnWriteArrayList<>();
        final AtomicInteger fileChooserCalls = new AtomicInteger();
        final AtomicReference<Path> pick = new AtomicReference<>();
        final AtomicReference<Path> folder = new AtomicReference<>();
        private final Path root;

        RelinkRig(Path root) throws Exception {
            this.root = root;
            fixture = new OpenProjectFixture(root);
            runOnFx(() -> {
                ProjectLifecycleController lifecycle = fixture.lifecycle();
                lifecycle.setProjectActionsAllowed(allowed::get);
                lifecycle.setMissingAssetsPresenter((generation, report) -> {
                    MissingAssetsDialog dialog = lifecycle.missingAssetsDialog(generation, report);
                    dialogs.add(dialog);
                    return dialog;
                });
                AssetRelinkFlow relink = lifecycle.assetRelink();
                relink.setWorkers(workers);
                relink.setFileLocator((row, initialDirectory) -> {
                    fileChooserCalls.incrementAndGet();
                    return Optional.ofNullable(pick.get());
                });
                relink.setFolderLocator(_ -> Optional.ofNullable(folder.get()));
                relink.setConfirmation(_ -> true);
            });
        }

        /** Saves a project over tone.wav, moves the file out of reach, reopens it and returns the report. */
        MissingAssetsDialog openWithAMissingTone() throws Exception {
            Path source = OpenProjectFixture.wav(root.resolve("library").resolve("tone.wav"), 48_000, 1.0, 220.0);
            OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Relink", source);
            Path moved = root.resolve("moved").resolve("tone.wav");
            Files.createDirectories(moved.getParent());
            Files.move(source, moved);
            pick.set(moved);
            folder.set(moved.getParent());
            assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();
            assertThat(dialogs).as("the report was presented once").hasSize(1);
            assertThat(onFx(() -> fixture.project.get().isDirty())).as("fixture: opened clean").isFalse();
            return dialogs.getFirst();
        }

        Button locateFileButton(MissingAssetsDialog dialog) {
            return (Button) dialog.rowNodes().getFirst().lookup(".missing-assets-locate-file");
        }

        Button locateFolderButton(MissingAssetsDialog dialog) {
            return (Button) dialog.getDialogPane().lookup(".missing-assets-locate-folder");
        }

        Hyperlink archiveLink(MissingAssetsDialog dialog) {
            return (Hyperlink) dialog.getDialogPane().lookup(".missing-assets-archive");
        }

        void runWorkersThenPulse() throws Exception {
            workers.release();
            workers.awaitEveryJobEnded();
            fixture.pulse();
            runOnFx(() -> { });
        }

        /** The clip is still missing, the project is clean and the row is idle and still listed. */
        void assertNotRelinked(MissingAssetsDialog dialog) throws Exception {
            AudioClip clip = fixture.liveClips().getFirst();
            assertThat(clip.getAudioData()).as("the clip is still missing").isNull();
            assertThat(onFx(() -> fixture.project.get().isDirty())).as("not dirtied").isFalse();
            assertThat(onFx(dialog::rowNodes)).as("the row is still listed").hasSize(1);
            assertThat(onFx(() -> locateFileButton(dialog).isDisabled())).as("and idle").isFalse();
        }

        @Override
        public void close() throws Exception {
            workers.release();
            workers.awaitEveryJobEnded();
            fixture.close();
        }
    }

    // ── the arm rig ──────────────────────────────────────────────────────────

    /**
     * Vox over a backend whose validation listing can be held, with the
     * production wiring and its input-routing guard behind the row's gate.
     * Built on the FX thread; closed on it.
     */
    private static final class ArmRig implements AutoCloseable {
        final DawProject project = new DawProject("Arm", FORMAT);
        final Track vox = project.createAudioTrack("Vox");
        final HeldBackend backend = new HeldBackend();
        final AudioEngine engine = new AudioEngine(FORMAT);
        final FxDispatcher dispatcher = new FxDispatcher();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final CountDownLatch armedAgain = new CountDownLatch(1);
        final TrackControlWiring wiring;
        private TrackControlBinder binder;

        ArmRig(AtomicBoolean allowed, boolean armedBeforeTheGuard) {
            vox.setInputRouting(new InputRouting(0, 2));
            engine.setStreamingProvision(new StreamingProvision(backend.name(),
                    List.of(new BackendStreamRung(backend, DeviceId.defaultFor(backend.name())))));
            if (armedBeforeTheGuard) {
                vox.setArmed(true);
                AtomicBoolean disarmedOnce = new AtomicBoolean();
                vox.addChangeListener(kind -> {
                    if (kind == Track.ChangeKind.ARM) {
                        if (!vox.isArmed()) disarmedOnce.set(true);
                        else if (disarmedOnce.get()) armedAgain.countDown();
                    }
                });
            }
            wiring = TrackControlWiring.standalone(project, dispatcher, null, engine, errors::add, allowed::get);
        }

        /** A lane-style arm button bound through the wiring's gated sink. FX thread. */
        Button boundArmButton() {
            MixerChannel channel = project.getMixerChannelForTrack(vox);
            Button button = new Button("R");
            binder = new TrackControlBinder(vox, wiring.registry().trackVm(UUID.fromString(vox.getId())), channel,
                    wiring.registry().channelVm(channel.getId()), wiring.commandSink());
            binder.bindArm(button);
            return button;
        }

        /** Counts down once Vox is armed. FX thread. */
        CountDownLatch awaitArmed() throws Exception {
            CountDownLatch armed = new CountDownLatch(1);
            runOnFx(() -> vox.addChangeListener(kind -> {
                if (kind == Track.ChangeKind.ARM && vox.isArmed()) armed.countDown();
            }));
            return armed;
        }

        @Override
        public void close() throws Exception {
            backend.release();
            runOnFx(() -> {
                if (binder != null) binder.dispose();
                wiring.dispose();
                dispatcher.dispose();
            });
            engine.shutdown();
        }
    }

    /** One 8-input device whose off-FX listing — the arm validation's — can be held. */
    private static final class HeldBackend implements AudioBackend {
        private final MockAudioBackend delegate = new MockAudioBackend();
        private final AtomicReference<CountDownLatch> gate = new AtomicReference<>();
        final CountDownLatch entered = new CountDownLatch(1);

        void hold() {
            gate.set(new CountDownLatch(1));
        }

        void release() {
            CountDownLatch held = gate.getAndSet(null);
            if (held != null) held.countDown();
        }

        @Override public String name() { return "Held"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean supportsStreaming() { return true; }

        @Override public List<AudioDeviceInfo> listDevices() {
            CountDownLatch held = Platform.isFxApplicationThread() ? null : gate.get();
            if (held != null) {
                entered.countDown();
                try {
                    if (!held.await(GUARD_SECONDS, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return List.of(new AudioDeviceInfo(0, "Interface", "Held", 8, 2, 48_000, List.of(), 0, 0));
        }

        @Override public void open(DeviceId device, com.benesquivelmusic.daw.sdk.audio.AudioFormat format, int frames) {
            delegate.open(device, format, frames);
        }
        @Override public boolean isOpen() { return delegate.isOpen(); }
        @Override public Flow.Publisher<AudioBlock> inputBlocks() { return delegate.inputBlocks(); }
        @Override public void sink(AudioBlock block) { delegate.sink(block); }
        @Override public void close() { delegate.close(); }
    }
}
