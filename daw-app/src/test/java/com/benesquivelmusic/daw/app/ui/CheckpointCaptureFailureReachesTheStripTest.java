package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.CheckpointStatusListener;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.plugin.MidiEffectPlugin;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.MidiMessage;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.MidiProcessContext;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.time.ZoneId;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.GUARD;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 331, "Concurrent-edit isolation test", app half (Persistence
 * Integrity book §2.3, §4.2; rulings F6, F8, G4): with the autosave wired
 * exactly as {@code MainController} wires it ({@link MainController#wireAutosave}:
 * a real {@link CheckpointManager} on a short cadence, capturing the live
 * project on the FX thread through a dispatcher's un-keyed post), a capture
 * that fails — a {@link ConcurrentModificationException} from a mutation
 * injected during the capture's walk, or a {@link RuntimeException} — is a
 * visible failed checkpoint: it reaches the strip model's checkpoint failure
 * state once <em>that model's</em> dispatcher is pulsed, and the next healthy
 * tick writes a checkpoint and clears it.
 *
 * <p>The story's "{@code recordSaveFailed} reaches the strip" is met by the
 * checkpoint failure state ({@code lastCheckpointSucceeded} false, with its
 * cause): rulings F6 and G4 separate a checkpoint's outcome from the head
 * save's, so a failed checkpoint never shows as "Save failed" — the head state
 * is untouched here. A source scan pins that {@code MainController} wires the
 * autosave through {@code wireAutosave}, with its FX post as the owner, and
 * detaches the listener on teardown.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class CheckpointCaptureFailureReachesTheStripTest {

    private static final AutoSaveConfig SHORT_CADENCE = new AutoSaveConfig(Duration.ofMillis(150), 10, true);

    @TempDir
    Path workspace;

    private final FxDispatcher ownerDispatcher = new FxDispatcher();
    private final FxDispatcher stripDispatcher = new FxDispatcher();
    private final ProjectOperationProgress progress = new ProjectOperationProgress(stripDispatcher);
    private final CheckpointManager checkpoints = new CheckpointManager(SHORT_CADENCE);
    private Runnable removal;

    @AfterEach
    void stopTheCheckpoints() throws Exception {
        checkpoints.stop();
        if (removal != null) {
            removal.run();
        }
    }

    @Test
    void aCaptureBrokenByAnEditDuringItsWalkReachesTheStripAndTheNextHealthyTickClearsIt() throws Exception {
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        Track first = project.createAudioTrack("First");
        project.createAudioTrack("Second");
        BrokenCapture edit = BrokenCapture.editing(project);
        first.getMidiEffectChain().add(edit);
        Path folder = wire(project);

        awaitOnTheStrip(() -> !progress.isLastCheckpointSucceeded(), "the failed checkpoint");

        assertThat(onFx(progress::getCheckpointFailureCause)).isInstanceOf(ConcurrentModificationException.class);
        assertThat(edit.threads).as("the capture ran on the FX thread").isNotEmpty()
                .allSatisfy(thread -> assertThat(thread.getName()).startsWith("JavaFX Application Thread"));
        assertThat(onFx(progress::isLastSaveSucceeded)).as("never shown as a failed save").isTrue();
        assertThat(checkpointFiles(folder)).as("nothing was written").isEmpty();

        edit.disarm();
        awaitOnTheStrip(() -> progress.isLastCheckpointSucceeded() && progress.getLastCheckpointFile() != null,
                "the next healthy tick's checkpoint");

        assertThat(onFx(progress::getCheckpointFailureCause)).isNull();
        assertThat(checkpointFiles(folder)).as("the next tick wrote one").isNotEmpty();
        assertThat(Files.readString(onFx(progress::getLastCheckpointFile), StandardCharsets.UTF_8))
                .contains("First").contains("Second");
    }

    @Test
    void aCaptureThatThrowsReachesTheStripAndTheNextHealthyTickClearsIt() throws Exception {
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        Track only = project.createAudioTrack("Only");
        BrokenCapture broken = BrokenCapture.throwing(new IllegalStateException("the capture broke"));
        only.getMidiEffectChain().add(broken);
        Path folder = wire(project);

        awaitOnTheStrip(() -> !progress.isLastCheckpointSucceeded(), "the failed checkpoint");

        assertThat(onFx(progress::getCheckpointFailureCause)).isInstanceOf(IllegalStateException.class)
                .hasMessage("the capture broke");
        assertThat(checkpointFiles(folder)).isEmpty();

        broken.disarm();
        awaitOnTheStrip(() -> progress.isLastCheckpointSucceeded() && progress.getLastCheckpointFile() != null,
                "the next healthy tick's checkpoint");
        assertThat(checkpointFiles(folder)).isNotEmpty();
    }

    @Test
    void mainControllerWiresTheAutosaveThisWayAndDetachesItOnTeardown() throws Exception {
        Path file = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java");
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(raw.length()).as("non-vacuity: the MainController source was read").isGreaterThan(50_000);
        String code = SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(raw))
                .replaceAll("\\s+", " ");

        assertThat(methodBody(code, "private void createSessionStatusStrip()"))
                .as("the strip's checkpoint status listens to the project manager's checkpoints")
                .contains("checkpointStatus = new com.benesquivelmusic.daw.app.ui.status.CheckpointStatusListener("
                        + " projectOperationProgress, projectManager.getCheckpointManager(), notificationBar,")
                .as("and the autosave captures the live project through the FX post")
                .contains("checkpointStatusRemoval = wireTheWindowsAutosave();")
                .contains("snapshotsController.setCheckpointStatus(checkpointStatus);");
        // Story 333, ruling 4 Q4 (the stage-7 review S2) and the app-fix-2 review S1: the capture gate is not an input
        // gate — a close's input-inert window pauses no scheduled checkpoint — and it shuts for good once DISPOSE
        // begins.
        assertThat(methodBody(code, "Runnable wireTheWindowsAutosave()"))
                .isEqualTo("{ return wireAutosave(projectManager, this::postFx, () -> project, checkpointStatus,"
                        + " this::autosaveCaptureAllowed); }");
        assertThat(methodBody(code, "private boolean autosaveCaptureAllowed()"))
                .isEqualTo("{ return !windowLifetimeEnding && noProjectInstallationPending(); }");
        assertThat(methodBody(code, "java.util.concurrent.CompletionStage<Void> disposeWindowLifetime()"))
                .as("the gate shuts before any of DISPOSE's steps runs")
                .startsWith("{ if (windowLifetimeDisposal != null) { return windowLifetimeDisposal; }"
                        + " windowLifetimeEnding = true;");
        assertThat(code).as("one writer of the flag, never cleared").containsOnlyOnce("windowLifetimeEnding = ");
        assertThat(methodBody(code, "private boolean noProjectInstallationPending()"))
                .as("which reads the installation alone")
                .isEqualTo("{ return projectLifecycleController == null"
                        + " || !projectLifecycleController.isProjectInstallationPending(); }");
        assertThat(code).as("the autosave is wired once, there").containsOnlyOnce("wireAutosave(projectManager,");
        assertThat(methodBody(code, "private void postFx(Runnable work)"))
                .as("the FX post is the dispatcher's un-keyed one")
                .isEqualTo("{ FxDispatcher.runOnFx(fxDispatcher, work); }");
        assertThat(code).as("the teardown detaches it")
                .contains("if (checkpointStatusRemoval != null) { checkpointStatusRemoval.run();");
        assertThat(code).as("no other capture source and no setProjectDataSupplier")
                .doesNotContain("setProjectDataSupplier")
                .containsOnlyOnce("setCaptureSource(");
    }

    /** Wires the autosave as the application does, starts the project's checkpoints, and returns its folder. */
    private Path wire(DawProject project) throws Exception {
        Path folder = Files.createDirectories(workspace.resolve("Song"));
        AtomicReference<CheckpointStatusListener> status = new AtomicReference<>();
        runOnFx(() -> {
            NotificationBar bar = new NotificationBar();
            bar.setAnimated(false);
            status.set(new CheckpointStatusListener(progress, checkpoints, bar, ownerDispatcher,
                    InstantSource.system(), ZoneId.systemDefault()));
        });
        removal = MainController.wireAutosave(new ProjectManager(checkpoints), ownerDispatcher::onFx, () -> project,
                status.get(), () -> true);
        checkpoints.start(folder).get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
        return folder;
    }

    /** Pulses the strip model's own dispatcher until {@code condition} holds; bounded by {@link SaveFlowFixture#GUARD}. */
    private void awaitOnTheStrip(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.nanoTime() + GUARD.toNanos();
        while (true) {
            runOnFx(stripDispatcher::pulse);
            if (onFx(condition::getAsBoolean)) {
                return;
            }
            assertThat(System.nanoTime() - deadline < 0).as("%s within %s", what, GUARD).isTrue();
            Thread.sleep(10);
        }
    }

    private static List<Path> checkpointFiles(Path folder) throws Exception {
        return SaveFlowFixture.checkpointFiles(folder);
    }

    private static String methodBody(String code, String signature) {
        int declaration = code.indexOf(signature);
        assertThat(declaration).as("MainController declares %s", signature).isNotNegative();
        int open = code.indexOf('{', declaration);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces after " + signature);
    }

    /**
     * A MIDI effect whose descriptor read — made by the capture while it walks
     * the tracks — breaks the capture while armed: by adding a track, so the
     * walk's iterator throws {@link ConcurrentModificationException} as an
     * edit interleaved with the walk would make it throw, or by throwing.
     * Records the thread of each read.
     */
    private static final class BrokenCapture implements MidiEffectPlugin {

        private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
                "test.broken-capture", "Broken Capture", "1.0", "Test", PluginType.EFFECT);

        private final DawProject edited;
        private final RuntimeException failure;
        private volatile boolean armed = true;
        final List<Thread> threads = new CopyOnWriteArrayList<>();

        private BrokenCapture(DawProject edited, RuntimeException failure) {
            this.edited = edited;
            this.failure = failure;
        }

        static BrokenCapture editing(DawProject project) {
            return new BrokenCapture(project, null);
        }

        static BrokenCapture throwing(RuntimeException failure) {
            return new BrokenCapture(null, failure);
        }

        void disarm() {
            armed = false;
        }

        @Override
        public PluginDescriptor getDescriptor() {
            if (armed) {
                threads.add(Thread.currentThread());
                if (failure != null) {
                    throw failure;
                }
                edited.createAudioTrack("Added during the walk");
            }
            return DESCRIPTOR;
        }

        @Override
        public MidiMessage[] process(MidiMessage[] in, int sampleOffset, MidiProcessContext ctx) {
            return in;
        }

        @Override public void initialize(PluginContext context) { }
        @Override public void activate() { }
        @Override public void deactivate() { }
        @Override public void dispose() { }
    }
}
