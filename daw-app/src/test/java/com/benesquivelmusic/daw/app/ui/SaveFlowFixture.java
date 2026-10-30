package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.CheckpointStatusListener;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AtomicFileWriter;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointEvent;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.ProjectMetadata;
import com.benesquivelmusic.daw.core.persistence.RecentProjectsStore;
import com.benesquivelmusic.daw.core.persistence.archive.ArchiveOptions;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiver;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.snapshot.SnapshotBrowserService;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The save flow of story 331 as the application wires it, over real
 * persistence: a {@link ProjectLifecycleController} over a real
 * {@link ProjectManager} and {@link CheckpointManager}, whose every
 * {@code project.daw} and checkpoint publish goes through one
 * {@link AtomicFileWriter} whose mover ({@link Publishes}) records the
 * thread of each publish and can hold or fail the next one; the strip's
 * {@link ProjectOperationProgress} over a dispatcher that is never started,
 * so its keyed state changes only in {@link #pulse()}; the checkpoint-status
 * listener wired through {@link MainController#wireAutosave}, as the
 * application wires it — before any project's checkpoints start, so Song A's
 * seeding and first re-arm reach the strip — with a record of every
 * checkpoint event after it ({@link #checkpointEvents}); and a notification
 * bar whose history the tests read.
 * The unsaved-changes prompt is answered on the FX thread by pressing
 * {@link #promptAnswer}'s button, or hidden (a cancel) when it is
 * {@code null}. Every FX action and wait is bounded by {@link #GUARD}.
 */
final class SaveFlowFixture implements AutoCloseable {

    /** The bound of every FX action and of every wait. */
    static final Duration GUARD = Duration.ofSeconds(20);

    private static final String UNSAVED_CHANGES_TITLE = "Unsaved Changes";
    /** The takeover dialog's title (story 333, ruling R7). */
    static final String TAKEOVER_TITLE = "Project Taken Over";
    /** The Save As refusal's title (story 333, ruling Q6); its progress dialog shares it. */
    static final String SAVE_AS_TITLE = "Save Project As";

    final Path workspace;
    final Publishes publishes = new Publishes();
    final AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, publishes);
    final CheckpointManager checkpoints;
    final ProjectManager projectManager;
    /** Never started: the strip model's keyed work runs only inside {@link #pulse()}. */
    final FxDispatcher dispatcher;
    final ProjectOperationProgress progress;
    final NotificationHistoryService shown = new NotificationHistoryService();
    final AtomicReference<DawProject> project = new AtomicReference<>();
    final AtomicReference<UndoManager> undoManager = new AtomicReference<>(new UndoManager());
    /** The slow-save timer tasks the controller armed, when {@link #holdSlowSaveTimers()} captures them. */
    final List<Runnable> slowSaveTimers = new CopyOnWriteArrayList<>();
    final AtomicInteger prompts = new AtomicInteger();
    /** The question of each unsaved-changes prompt shown, in order. */
    final List<String> promptQuestions = new CopyOnWriteArrayList<>();
    /** The header of each unsaved-changes prompt shown, in order. */
    final List<String> promptHeaders = new CopyOnWriteArrayList<>();
    /**
     * Every event the checkpoints reported since before Song A was created,
     * in order. Each is recorded after the strip's listener took it, so a
     * re-arm found here has already been posted towards the strip.
     */
    final List<CheckpointEvent> checkpointEvents = new CopyOnWriteArrayList<>();
    final Path songA;
    /** The text of the prompt button to press; {@code null} hides the prompt, which cancels it. */
    volatile String promptAnswer;
    /**
     * The clock of the controller's door waits (story 333, ruling R8): manual, so a test fires the
     * 10 s "Stop waiting" offer when it chooses, and no other test ever sees it.
     */
    final ManualCloseTimers doorWaitTimers = new ManualCloseTimers();
    /** The text of the takeover dialog's button to press (story 333); {@code null} hides it, its Escape answer. */
    volatile String takeoverAnswer = "Not now";
    /** The header, content and button texts of each takeover dialog shown, in order. */
    final List<String> takeoverHeaders = new CopyOnWriteArrayList<>();
    final List<String> takeoverContents = new CopyOnWriteArrayList<>();
    final List<List<String>> takeoverButtons = new CopyOnWriteArrayList<>();
    /** The default button's text of each takeover dialog shown, in order. */
    final List<String> takeoverDefaults = new CopyOnWriteArrayList<>();
    /** The message of each Save As target refusal shown (ruling Q6); each is answered OK. */
    final List<String> saveAsRefusals = new CopyOnWriteArrayList<>();
    /** Save As's chooser: scripted answers, and what each call opened with. */
    final SaveAsTargets saveAsTargets = new SaveAsTargets();
    /** The clock of Save As's "Continue" offer (ruling S6): manual, so a test fires its 10 s. */
    final ManualCloseTimers saveAsTimers = new ManualCloseTimers();
    /** Every layout the controller applied, in order; {@code null} is the built-in Default (story 282, R10). */
    final List<String> appliedLayouts = new CopyOnWriteArrayList<>();
    /** What each UI rebuild the controller asks for runs (story 333: a test makes it throw). */
    volatile Runnable onProjectUIRebuild = () -> { };
    /**
     * Told each project the controller installs, right after it became the live one — where the application's
     * {@code MainController.replacePluginProject} runs (story 333: a test wires a partial controller's).
     */
    volatile java.util.function.Consumer<DawProject> onProjectSet = _ -> { };
    /** Run on the FX thread while each takeover dialog asks, before it is answered (story 333). */
    volatile Runnable whileTheTakeoverAsks = () -> { };
    /**
     * Where the closer of the open folder runs a journal end: its own virtual
     * worker unless a test holds the end before it begins (story 333: a task
     * submitted and never run is a journal end not begun).
     */
    volatile java.util.concurrent.Executor sealWorker =
            task -> Thread.ofVirtual().name(ProjectFolderCloser.SEAL_WORKER).start(task);
    /**
     * Whether the window's lifetime has begun ending: the exit fixture's DISPOSE stand-in sets it first, as
     * {@code MainController.disposeWindowLifetime} does, and the autosave's capture gate then says no for good
     * ({@code MainController.autosaveCaptureAllowed}; the app-fix-2 review S1).
     */
    volatile boolean windowLifetimeEnding;
    /** The journal coordinator the Save As flow closes through: journaling off unless a test installs one. */
    private com.benesquivelmusic.daw.app.ui.status.ProjectJournalCoordinator saveAsJournal;
    private ProjectFolderCloser folderCloser;
    private SaveAsFlow saveAsFlow;

    private NotificationBar notificationBar;
    private ProjectLifecycleController lifecycle;
    private CheckpointStatusListener status;
    private SnapshotsController snapshots;
    private Runnable statusRemoval;
    private Runnable eventRecording;
    private Thread fxThread;

    private final ListChangeListener<Window> answerPrompts = change -> {
        while (change.next()) {
            for (Window window : change.getAddedSubList()) {
                if (window instanceof Stage stage && UNSAVED_CHANGES_TITLE.equals(stage.getTitle())) {
                    prompts.incrementAndGet();
                    Platform.runLater(() -> answer(stage));
                } else if (window instanceof Stage stage && TAKEOVER_TITLE.equals(stage.getTitle())) {
                    Platform.runLater(() -> answerTakeover(stage));
                } else if (window instanceof Stage stage && SAVE_AS_TITLE.equals(stage.getTitle())) {
                    Platform.runLater(() -> answerSaveAsRefusal(stage));
                }
            }
        }
    };

    /**
     * Creates Song A in {@code workspace} through the project manager — its
     * checkpoints started — and opens it in the controller: a project of one
     * audio track, clean.
     */
    SaveFlowFixture(Path workspace, AutoSaveConfig config) throws Exception {
        this(workspace, config, null);
    }

    /**
     * As {@link #SaveFlowFixture(Path, AutoSaveConfig)}, with
     * {@code recentProjects} as the project manager's recent projects — the
     * list the Welcome window and the Project Hub show — or none when
     * {@code null}.
     */
    SaveFlowFixture(Path workspace, AutoSaveConfig config, RecentProjectsStore recentProjects) throws Exception {
        this(workspace, config, recentProjects, new FxDispatcher());
    }

    SaveFlowFixture(Path workspace, AutoSaveConfig config, RecentProjectsStore recentProjects,
                    FxDispatcher dispatcher) throws Exception {
        this.workspace = workspace;
        this.dispatcher = dispatcher;
        this.progress = new ProjectOperationProgress(dispatcher);
        this.checkpoints = new CheckpointManager(config, writer);
        this.projectManager = new ProjectManager(checkpoints, recentProjects, new ProjectLockManager(),
                new ProjectDeserializer(), writer);
        // The strip's listener is wired before Song A's checkpoints start, as
        // the application wires it before any project opens.
        runOnFx(() -> {
            fxThread = Thread.currentThread();
            Window.getWindows().addListener(answerPrompts);
            notificationBar = new NotificationBar();
            notificationBar.setAnimated(false);
            notificationBar.setAutoDismissMillis(600_000);
            notificationBar.setHistoryService(shown);
            status = new CheckpointStatusListener(progress, checkpoints, notificationBar, dispatcher,
                    InstantSource.system(), ZoneId.systemDefault());
            statusRemoval = MainController.wireAutosave(projectManager, dispatcher::onFx, project::get, status,
                    () -> !windowLifetimeEnding && (lifecycle == null || !lifecycle.isProjectInstallationPending()));
        });
        eventRecording = checkpoints.addEventListener(checkpointEvents::add);
        songA = projectManager.createProject("Song A", workspace).projectPath();
        DawProject song = new DawProject("Song A", AudioFormat.CD_QUALITY);
        song.setMetadata(projectManager.getCurrentProject());
        song.createAudioTrack("Vox");
        song.markClean();
        project.set(song);
        runOnFx(() -> {
            lifecycle = new ProjectLifecycleController(projectManager, new SessionInterchangeController(),
                    notificationBar, progress, new BorderPane(), new VBox(),
                    new ProjectLifecycleController.Deps(project::get, next -> {
                        project.set(next);
                        onProjectSet.accept(next);
                    }, undoManager::get,
                            undoManager::set, () -> { }, () -> { }, () -> onProjectUIRebuild.run(), () -> null,
                            appliedLayouts::add, () -> { }),
                    new ProjectArchiver(), dispatcher);
            lifecycle.setWelcomePresenter(_ -> { });
            lifecycle.setDoorWaitTimers(doorWaitTimers);
            snapshots = new SnapshotsController(new SnapshotBrowserService(), checkpoints, projectManager,
                    new SnapshotsController.Deps(() -> null, project::get,
                            proceed -> lifecycle.whenProjectMayClose(
                                    ProjectLifecycleController.RESTORE_SNAPSHOT_DOOR, proceed),
                            (restored, label, missing, unread) -> lifecycle.installRestoredSnapshot(
                                    restored, label, missing, unread)),
                    dispatcher);
            snapshots.setCheckpointStatus(status);
            snapshots.setProjectActionsAllowed(() -> !lifecycle.isProjectInstallationPending());
            com.benesquivelmusic.daw.app.ui.status.ProjectJournalCoordinator journalOff =
                    new com.benesquivelmusic.daw.app.ui.status.ProjectJournalCoordinator(progress, notificationBar,
                            dispatcher, () -> false, () -> null, checkpoints,
                            new com.benesquivelmusic.daw.core.session.SessionManager(), InstantSource.system());
            installSaveAs(journalOff, new SessionJournalEnding(journalOff,
                    new com.benesquivelmusic.daw.core.session.SessionManager(), () -> null, () -> null, () -> null,
                    this::openFolder, InstantSource.system()));
        });
        publishes.clear();
    }

    /**
     * Builds the Save As flow (story 333) as {@code MainController} builds it
     * — over the controller's own lifecycle part and prompts, closing the
     * original folder through a {@link ProjectFolderCloser} over
     * {@code journal} and {@code ending} — with this fixture's scripted
     * chooser ({@link #saveAsTargets}), manual timers ({@link #saveAsTimers})
     * and the workspace as its workspace root, and installs it on the
     * controller. FX thread.
     */
    void installSaveAs(com.benesquivelmusic.daw.app.ui.status.ProjectJournalCoordinator journal,
                       JournalEnding ending) {
        installSaveAs(journal, ending, InstantSource.system(), ZoneId.systemDefault(), () -> workspace);
    }

    /** As {@link #installSaveAs(com.benesquivelmusic.daw.app.ui.status.ProjectJournalCoordinator, JournalEnding)},
     *  with the takeover pre-fill's clock and zone and the workspace root given. FX thread. */
    void installSaveAs(com.benesquivelmusic.daw.app.ui.status.ProjectJournalCoordinator journal,
                       JournalEnding ending, InstantSource clock, ZoneId zone, Supplier<Path> workspaceRoot) {
        saveAsJournal = journal;
        folderCloser = new ProjectFolderCloser(projectManager, journal, ending, project::get, dispatcher,
                task -> sealWorker.execute(task));
        saveAsFlow = new SaveAsFlow(new SaveAsFlow.Deps(projectManager, lifecycle.saveAsLifecycle(), folderCloser,
                lifecycle.closePrompts(), saveAsTargets, notificationBar, dispatcher, project::get, () -> null,
                saveAsTimers, clock, zone, java.util.Optional::empty, workspaceRoot));
        lifecycle.setSaveAsFlow(saveAsFlow);
    }

    /** The Save As flow installed on the controller; FX thread only. */
    SaveAsFlow saveAsFlow() {
        return saveAsFlow;
    }

    /** The closer of the open folder the Save As flow closes through; FX thread only. */
    ProjectFolderCloser folderCloser() {
        return folderCloser;
    }

    /** The journal coordinator the Save As flow closes through. */
    com.benesquivelmusic.daw.app.ui.status.ProjectJournalCoordinator saveAsJournal() {
        return saveAsJournal;
    }

    /** The project manager's open folder, or {@code null}. */
    Path openFolder() {
        ProjectMetadata open = projectManager.getCurrentProject();
        return open == null ? null : open.projectPath();
    }

    /** The controller under test; FX thread only. */
    ProjectLifecycleController lifecycle() {
        return lifecycle;
    }

    /** The snapshot browser's controller, wired to this fixture's checkpoint status; FX thread only. */
    SnapshotsController snapshots() {
        return snapshots;
    }

    /** The notification bar the controller and the Save As flow show their toasts on; FX thread only. */
    NotificationBar notificationBar() {
        return notificationBar;
    }

    /** The checkpoint-status listener wired through {@link MainController#wireAutosave}. */
    CheckpointStatusListener status() {
        return status;
    }

    /** The FX application thread. */
    Thread fxThread() {
        return fxThread;
    }

    /** Captures the controller's slow-save timer tasks instead of the real 500 ms timer. */
    void holdSlowSaveTimers() throws Exception {
        runOnFx(() -> lifecycle.setSlowSaveTimerScheduler(slowSaveTimers::add));
    }

    /** Runs, on the FX thread, every slow-save timer task captured so far. */
    void fireSlowSaveTimers() throws Exception {
        List<Runnable> due = new ArrayList<>(slowSaveTimers);
        slowSaveTimers.clear();
        runOnFx(() -> due.forEach(Runnable::run));
    }

    /** Ctrl+S on the FX thread; returns once the FX handler did. */
    void save() throws Exception {
        runOnFx(() -> lifecycle.onSaveProject());
    }

    /**
     * Waits until the save writer finished every save queued so far, then
     * for the FX turns their outcomes posted, then runs one pulse of the
     * strip model — so every outcome so far is on the strip.
     */
    void settle() throws Exception {
        SaveOutcomes.awaitIdle(projectManager);
        runOnFx(() -> { });
        pulse();
    }

    /** One pulse of the strip model's dispatcher, on the FX thread: the only place its keyed state changes. */
    void pulse() throws Exception {
        runOnFx(dispatcher::pulse);
    }

    /** The notifications shown so far. */
    List<NotificationEntry> notifications() throws Exception {
        return onFx(() -> List.copyOf(shown.getEntries()));
    }

    /**
     * Whether {@code directory} is the current project. Reads the current
     * project once: an open's worker may retire it concurrently, so a second
     * read could find none.
     */
    boolean currentProjectIs(Path directory) {
        ProjectMetadata current = projectManager.getCurrentProject();
        return current != null && directory.equals(current.projectPath());
    }

    /** The names of the strip operations shown now. */
    List<String> operationNames() throws Exception {
        return onFx(() -> progress.getOperations().stream().map(ProjectOperationProgress.NamedOperation::name)
                .toList());
    }

    /** A second project on disk, made and released by a manager of its own; returns its folder. */
    Path anotherProjectOnDisk(String name) throws IOException {
        ProjectManager other = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT));
        Path directory = other.createProject(name, workspace).projectPath();
        DawProject saved = new DawProject(name, AudioFormat.CD_QUALITY);
        saved.createAudioTrack("Other");
        SaveOutcomes.saved(other, saved);
        other.closeProject().join();
        return directory;
    }

    /** An archive of a one-track project named {@code name}, written in the workspace; returns its file. */
    Path archiveOnDisk(String name) throws IOException {
        DawProject archived = new DawProject(name, AudioFormat.CD_QUALITY);
        archived.createAudioTrack("Archived");
        ProjectArchiver archiver = new ProjectArchiver();
        Path archive = workspace.resolve(name + ProjectArchiver.ARCHIVE_EXTENSION);
        archiver.write(archiver.capture(archived, ArchiveOptions.defaults(), List.of()), archive);
        return archive;
    }

    /** A new folder to restore an archive of {@code name} into, as the restore's chooser hands it. */
    Path restoreDestination(String name) throws IOException {
        return Files.createDirectories(workspace.resolve("restored")).resolve(name + "-20261023-120000");
    }

    /** The checkpoint files in {@code projectDirectory}'s {@code checkpoints/}, by name. */
    static List<Path> checkpointFiles(Path projectDirectory) throws IOException {
        Path folder = projectDirectory.resolve("checkpoints");
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.filter(file -> file.getFileName().toString().startsWith("checkpoint-")).sorted()
                    .toList();
        }
    }

    /** Polls, bounded by {@link #GUARD}, after an FX turn and a pulse each time, until {@code condition} holds. */
    void awaitOnFx(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.nanoTime() + GUARD.toNanos();
        while (true) {
            pulse();
            if (onFx(condition::getAsBoolean)) {
                return;
            }
            assertThat(System.nanoTime() - deadline < 0).as("%s within %s", what, GUARD).isTrue();
            Thread.sleep(5);
        }
    }

    /**
     * Waits, through {@link #awaitOnFx} and so bounded by {@link #GUARD},
     * until a checkpoint generation newer than {@code afterGeneration} runs
     * and has reported its {@link CheckpointEvent.Seeded seeding}. Seeded
     * fires on the checkpoint writer after seeding's ordering-fact
     * compaction and stray-temp cleanup, each of which publishes or deletes
     * synchronously on that writer, so every publish the seeding made is in
     * {@link #publishes} once the event is in {@link #checkpointEvents}.
     * The caller captures {@code afterGeneration} — the outgoing project's
     * running generation — before the action that opens the next project,
     * so the outgoing generation's own Seeded never satisfies the wait, and
     * calls this only once that open's installation finished. A read-only
     * open starts no generation, so its test never waits here.
     *
     * @param afterGeneration the generation that ran before the open
     */
    void awaitCheckpointsSeeded(long afterGeneration) throws Exception {
        awaitOnFx(() -> {
            OptionalLong running = checkpoints.runningGeneration();
            return running.isPresent() && running.getAsLong() > afterGeneration
                    && checkpointEvents.stream().anyMatch(event -> event instanceof CheckpointEvent.Seeded seeded
                            && seeded.generation() == running.getAsLong());
        }, "the seeding of a checkpoint generation after generation " + afterGeneration);
    }

    @Override
    public void close() throws Exception {
        try {
            publishes.releaseAll();
            runOnFx(() -> {
                SaveAsProgressDialog leftOpen = saveAsFlow == null ? null : saveAsFlow.progressDialog();
                if (leftOpen != null) {
                    leftOpen.closeByFlow();
                }
                Window.getWindows().removeListener(answerPrompts);
                if (statusRemoval != null) {
                    statusRemoval.run();
                }
                lifecycle.dispose();
            });
            SaveOutcomes.awaitIdle(projectManager);
        } finally {
            eventRecording.run();
            // Bounded (story 333 review): a release that never ends fails the test instead of hanging it.
            projectManager.closeProject().get(GUARD.toMillis() * 3, TimeUnit.MILLISECONDS);
        }
    }

    // ── the prompt ───────────────────────────────────────────────────────────

    /** Presses the button the test chose on the prompt, or hides it. FX thread, inside its showAndWait. */
    private void answer(Stage prompt) {
        String text = promptAnswer;
        Node found = prompt.getScene().getRoot().lookup(".dialog-pane");
        if (found instanceof DialogPane shown) {
            promptQuestions.add(shown.getContentText());
            promptHeaders.add(shown.getHeaderText());
        }
        if (text != null && found instanceof DialogPane pane) {
            for (ButtonType type : pane.getButtonTypes()) {
                if (type.getText().equals(text) && pane.lookupButton(type) instanceof Button button) {
                    button.fire();
                    return;
                }
            }
        }
        prompt.hide();
    }

    /**
     * Records the takeover dialog — its header, content, buttons in order and
     * default button — then presses {@link #takeoverAnswer}'s button, or hides
     * it (its Escape answer). FX thread, inside its showAndWait.
     */
    private void answerTakeover(Stage dialog) {
        Node found = dialog.getScene().getRoot().lookup(".dialog-pane");
        if (!(found instanceof DialogPane pane)) {
            dialog.hide();
            return;
        }
        takeoverHeaders.add(pane.getHeaderText());
        takeoverContents.add(pane.getContentText());
        List<String> buttons = new ArrayList<>();
        String defaultButton = null;
        for (ButtonType type : pane.getButtonTypes()) {
            buttons.add(type.getText());
            if (pane.lookupButton(type) instanceof Button button && button.isDefaultButton()) {
                defaultButton = type.getText();
            }
        }
        takeoverButtons.add(List.copyOf(buttons));
        takeoverDefaults.add(defaultButton);
        whileTheTakeoverAsks.run();
        String text = takeoverAnswer;
        if (text != null) {
            for (ButtonType type : pane.getButtonTypes()) {
                if (type.getText().equals(text) && pane.lookupButton(type) instanceof Button button) {
                    button.fire();
                    return;
                }
            }
        }
        dialog.hide();
    }

    /**
     * Records a Save As target refusal's message (ruling Q6) and presses its
     * OK; Save As's progress dialog, which has the same title, is left alone.
     * FX thread, inside the refusal's showAndWait.
     */
    private void answerSaveAsRefusal(Stage dialog) {
        Node message = dialog.getScene().getRoot().lookup(".dawg-dialog-message");
        if (!(message instanceof javafx.scene.control.Label label)) {
            return;
        }
        saveAsRefusals.add(label.getText());
        Node found = dialog.getScene().getRoot().lookup(".dialog-pane");
        if (found instanceof DialogPane pane) {
            for (ButtonType type : pane.getButtonTypes()) {
                if (pane.lookupButton(type) instanceof Button button && button.isDefaultButton()) {
                    button.fire();
                    return;
                }
            }
        }
        dialog.hide();
    }

    /**
     * Save As's chooser for the tests (story 333): each call is recorded with
     * what it opened with, and answered by the next scripted answer — a
     * target, or a cancel — or, with none left, cancelled. Called on the FX
     * thread.
     */
    static final class SaveAsTargets implements SaveAsTargetPrompt {

        /** What one call opened with. */
        record Call(Path directory, String name) { }

        private final List<Call> calls = new CopyOnWriteArrayList<>();
        private final java.util.Deque<java.util.function.Function<Call, java.util.Optional<Path>>> answers =
                new java.util.concurrent.ConcurrentLinkedDeque<>();

        /** The next call chooses {@code target}. */
        SaveAsTargets choose(Path target) {
            answers.add(_ -> java.util.Optional.of(target));
            return this;
        }

        /** The next call chooses what {@code answer} gives for it. */
        SaveAsTargets answer(java.util.function.Function<Call, java.util.Optional<Path>> answer) {
            answers.add(answer);
            return this;
        }

        /** The next call is cancelled. */
        SaveAsTargets cancel() {
            answers.add(_ -> java.util.Optional.empty());
            return this;
        }

        /** Every call so far, oldest first. */
        List<Call> calls() {
            return List.copyOf(calls);
        }

        @Override
        public java.util.Optional<Path> choose(Path initialDirectory, String initialName) {
            Call call = new Call(initialDirectory, initialName);
            calls.add(call);
            java.util.function.Function<Call, java.util.Optional<Path>> answer = answers.poll();
            return answer == null ? java.util.Optional.empty() : answer.apply(call);
        }
    }

    // ── FX ───────────────────────────────────────────────────────────────────

    static <T> T onFx(Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        runOnFx(() -> result.set(action.get()));
        return result.get();
    }

    /** Runs {@code action} on the FX thread and rethrows what it threw; bounded at {@link #GUARD}. */
    static void runOnFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .as("the FX action returned within %s", GUARD).isTrue();
        if (thrown.get() instanceof Error error) {
            throw error;
        }
        if (thrown.get() != null) {
            throw new AssertionError("the FX action threw", thrown.get());
        }
    }

    // ── the publishes ────────────────────────────────────────────────────────

    /** Whether a publish is {@code project.daw}. */
    static boolean isHead(Path target) {
        return target.getFileName().toString().equals("project.daw");
    }

    /** Whether a publish is a checkpoint. */
    static boolean isCheckpoint(Path target) {
        Path parent = target.getParent();
        return parent != null && parent.getFileName().toString().equals("checkpoints")
                && com.benesquivelmusic.daw.core.persistence.CheckpointFileName.parse(target.getFileName().toString()).isPresent();
    }

    /** One file published, and the thread that published it. */
    record Publish(Path target, Thread thread) { }

    /**
     * The mover of every {@code project.daw} and checkpoint publish: records
     * each with its thread; holds the next publish a test chose until it
     * releases it; fails the next publish a test chose with the exception it
     * gave, after any hold, without publishing.
     */
    static final class Publishes implements AtomicFileWriter.Mover {

        private final List<Publish> done = new CopyOnWriteArrayList<>();
        private final List<Hold> holds = new CopyOnWriteArrayList<>();
        private final List<Failure> failures = new CopyOnWriteArrayList<>();

        /** A publish held until {@link #release()}. */
        static final class Hold {
            private final Predicate<Path> which;
            private final CountDownLatch entered = new CountDownLatch(1);
            private final CountDownLatch released = new CountDownLatch(1);
            private volatile Thread holder;

            private Hold(Predicate<Path> which) {
                this.which = which;
            }

            /** Waits, bounded, until the held publish reached the mover; returns the thread holding it. */
            Thread awaitHeld() throws InterruptedException {
                assertThat(entered.await(GUARD.toMillis(), TimeUnit.MILLISECONDS))
                        .as("the held publish reached the mover within %s", GUARD).isTrue();
                return holder;
            }

            /** Whether the held publish reached the mover. */
            boolean isHeld() {
                return entered.getCount() == 0 && released.getCount() == 1;
            }

            /** Lets the held publish go on. */
            void release() {
                released.countDown();
            }
        }

        private record Failure(Predicate<Path> which, IOException failure) { }

        /** What runs on the publishing thread right after a publish, with its target. */
        @FunctionalInterface
        interface AfterPublish {
            void run(Path target) throws IOException;
        }

        private record After(Predicate<Path> which, AfterPublish action) { }

        private final List<After> afters = new CopyOnWriteArrayList<>();

        /** Runs {@code action} right after the next publish {@code which} accepts, on its thread (story 333). */
        void afterNext(Predicate<Path> which, AfterPublish action) {
            afters.add(new After(which, action));
        }

        /** Holds the next publish {@code which} accepts. */
        Hold holdNext(Predicate<Path> which) {
            Hold hold = new Hold(which);
            holds.add(hold);
            return hold;
        }

        /** Fails the next publish {@code which} accepts with {@code failure}. */
        void failNext(Predicate<Path> which, IOException failure) {
            failures.add(new Failure(which, failure));
        }

        /** The publishes so far, oldest first. */
        List<Publish> done() {
            return List.copyOf(done);
        }

        /** The publishes so far whose target {@code which} accepts. */
        List<Publish> done(Predicate<Path> which) {
            return done.stream().filter(publish -> which.test(publish.target())).toList();
        }

        void clear() {
            done.clear();
        }

        /** Releases every hold, so nothing outlives a test. */
        void releaseAll() {
            holds.forEach(Hold::release);
        }

        @Override
        public void move(Path temp, Path target, CopyOption... options) throws IOException {
            for (Hold hold : holds) {
                if (hold.which.test(target) && holds.remove(hold)) {
                    hold.holder = Thread.currentThread();
                    hold.entered.countDown();
                    try {
                        if (!hold.released.await(GUARD.toMillis() * 3, TimeUnit.MILLISECONDS)) {
                            throw new IOException("the held publish of " + target + " was never released");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted while held", e);
                    }
                    break;
                }
            }
            for (Failure failure : failures) {
                if (failure.which().test(target) && failures.remove(failure)) {
                    throw failure.failure();
                }
            }
            AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, target, options);
            done.add(new Publish(target, Thread.currentThread()));
            for (After after : afters) {
                if (after.which().test(target) && afters.remove(after)) {
                    after.action().run(target);
                }
            }
        }
    }
}
