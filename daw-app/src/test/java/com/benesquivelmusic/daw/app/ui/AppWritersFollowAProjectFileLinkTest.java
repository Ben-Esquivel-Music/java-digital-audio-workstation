package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AtomicFileWriter;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointOrder;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.persistence.journal.JournalRecord;
import com.benesquivelmusic.daw.core.persistence.journal.JournalState;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationReport;
import com.benesquivelmusic.daw.core.project.DawProject;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Story 331, ruling M3, the application's own writers of {@code project.daw}
 * — the migration roll-back and the journal replay's recovered file: a
 * {@code project.daw} that is a symbolic link to a {@code .daw} file stays a
 * link, and the file it leads to is replaced, as the project manager's save
 * does; one that leads to anything else is refused with the writer's reason,
 * and nothing is written.
 *
 * <p>The replay half runs through the open recovery gate, as production
 * wires it ({@code MainController} always enables the gate): Recover
 * Session's one recovery dialog is answered "Recover everything", and the
 * replay and its commit run on the gate's worker. A refused commit opens the
 * last clean save read-only, which writes nothing anywhere — no checkpoint
 * generation starts, no journal opens, and every piece of recovery evidence
 * stays byte-identical. A commit through a link opens the recovered project
 * writable, and that session's checkpoint seeding revokes the folder's ghost
 * certification on the checkpoint writer, asynchronously and in no fixed
 * order with the open's installation, so that test waits for the seeding
 * before it reads the publishes.</p>
 *
 * <p>Skipped where this account cannot create symbolic links (on Windows
 * that takes Developer Mode or an elevated shell).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class AppWritersFollowAProjectFileLinkTest {

    private static final String NOT_A_DAW_FILE_LINK =
            "a symbolic link that does not lead to a .daw file; it was not replaced";
    private static final String RECOVER_UNSAVED_WORK = "Recover unsaved work";
    private static final String RECOVER_EVERYTHING = "Recover everything";
    private static final String CHECKPOINT_WRITER = "daw-checkpoint-writer";
    /** The verified replay base {@link #aProjectWithACheckpointAndAJournal} writes. */
    private static final String VERIFIED_BASE = "checkpoint-999-20260101T000000.daw";

    @TempDir
    Path workspace;

    private SaveFlowFixture fixture;
    private Path shared;
    /** The recovery dialogs shown, each answered "Recover everything". */
    private final AtomicInteger recoveryDialogs = new AtomicInteger();
    /** The journals the gate's open hook was asked to open. */
    private final AtomicInteger journalOpens = new AtomicInteger();

    @BeforeEach
    void openSongA() throws Exception {
        assumeSymbolicLinks(workspace);
        fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
        // The controller's own writes go through the fixture's writer, whose mover records each publish.
        runOnFx(() -> fixture.lifecycle().setFileWriter(fixture.writer));
        shared = Files.createDirectories(workspace.resolve("shared"));
    }

    @AfterEach
    void close() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void aRollBackThroughAProjectFileLinkReplacesTheDawFileItLeadsToAndKeepsTheLink() throws Exception {
        Path projectFile = fixture.songA.resolve("project.daw");
        Path song = shared.resolve("Song.daw");
        Files.move(projectFile, song);
        Files.createSymbolicLink(projectFile, Path.of("..", "shared", "Song.daw"));
        Path backup = aMigrationBackupOf(fixture.songA);

        runOnFx(() -> fixture.lifecycle().rollbackMigration(fixture.songA, aMigrationReport(), backup));

        assertThat(Files.isSymbolicLink(projectFile)).as("project.daw is still the link").isTrue();
        assertThat(song).as("the .daw file it leads to holds the backup").hasSameBinaryContentAs(backup);
        assertThat(publishedOutsideCheckpoints()).as("the one publish replaced the .daw file the link leads to")
                .containsExactly(song.toRealPath());
        assertThat(writerTempsIn(fixture.songA)).isEmpty();
        assertThat(writerTempsIn(shared)).isEmpty();
        assertThat(fixture.notifications()).extracting(NotificationEntry::level)
                .doesNotContain(NotificationLevel.ERROR);
    }

    @Test
    void aRollBackThroughALinkThatLeadsToNoDawFileIsRefusedAndWritesNothing() throws Exception {
        Path projectFile = fixture.songA.resolve("project.daw");
        Path notes = Files.writeString(shared.resolve("notes.txt"), "not a project");
        Files.delete(projectFile);
        Files.createSymbolicLink(projectFile, Path.of("..", "shared", "notes.txt"));
        Path backup = aMigrationBackupOf(fixture.songA);
        DawProject songAModel = onFx(fixture.project::get);

        runOnFx(() -> fixture.lifecycle().rollbackMigration(fixture.songA, aMigrationReport(), backup));

        assertThat(fixture.notifications()).singleElement().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
            assertThat(entry.message()).isEqualTo("Roll back failed: "
                    + new FileSystemException(projectFile.toString(), null, NOT_A_DAW_FILE_LINK).getMessage());
        });
        assertThat(Files.isSymbolicLink(projectFile)).as("the link is as it was").isTrue();
        assertThat(Files.readString(notes)).as("the file it leads to is untouched").isEqualTo("not a project");
        assertThat(fixture.publishes.done()).as("nothing was published").isEmpty();
        assertThat(writerTempsIn(fixture.songA)).isEmpty();
        assertThat(writerTempsIn(shared)).isEmpty();
        assertThat(onFx(fixture.project::get)).as("Song A is not reloaded").isSameAs(songAModel);
    }

    @Test
    void aJournalReplayThroughAProjectFileLinkWritesTheDawFileItLeadsToAndKeepsTheLink() throws Exception {
        Path songB = aProjectWithACheckpointAndAJournal("Song B");
        Path projectFile = songB.resolve("project.daw");
        Path song = shared.resolve("Song B.daw");
        Files.move(projectFile, song);
        Files.createSymbolicLink(projectFile, Path.of("..", "shared", "Song B.daw"));
        byte[] before = Files.readAllBytes(song);
        ProjectContext context = ProjectContext.forProject(songB);
        Path base = context.checkpointDirectory().resolve(VERIFIED_BASE);
        byte[] baseBefore = Files.readAllBytes(base);
        long songAGeneration = fixture.checkpoints.runningGeneration().orElseThrow();

        recoverEverythingThroughTheOpenGate(songB);

        assertThat(recoveryDialogs).as("one recovery dialog was shown").hasValue(1);
        assertThat(fixture.projectManager.isReadOnly()).as("the recovered Song B opened writable").isFalse();
        assertThat(journalOpens).as("its journal opened once").hasValue(1);
        fixture.awaitCheckpointsSeeded(songAGeneration);
        List<Path> recoveredBackups = recoveredBackupsIn(songB);
        assertThat(recoveredBackups).as("the pre-recovery file was backed up first").singleElement()
                .satisfies(recoveredBackup -> assertThat(Files.readAllBytes(recoveredBackup)).isEqualTo(before));
        List<SaveFlowFixture.Publish> published = fixture.publishes.done();
        assertThat(published).extracting(SaveFlowFixture.Publish::target)
                .as("the backup, the one publish of the recovered project, then the seeding's revocation of the ghost")
                .containsExactly(recoveredBackups.getFirst(), song.toRealPath(),
                        context.checkpointDirectory().resolve(CheckpointOrder.FILE_NAME));
        assertThat(published).extracting(publish -> publish.thread().getName())
                .as("the recovery's writes on the gate's worker, the ordering fact on the checkpoint writer")
                .containsExactly(ProjectLifecycleController.RECOVERY_SCAN_WORKER,
                        ProjectLifecycleController.RECOVERY_SCAN_WORKER, CHECKPOINT_WRITER);
        assertThat(published).extracting(SaveFlowFixture.Publish::thread).as("nothing was published on the FX thread")
                .doesNotContain(fixture.fxThread());
        assertThat(CheckpointOrder.read(context.checkpointDirectory()))
                .as("the writable session revoked the ghost certification").isEmpty();
        assertThat(Files.readAllBytes(base)).as("the verified replay base is still on disk, as it was")
                .isEqualTo(baseBefore);
        assertThat(Files.isSymbolicLink(projectFile)).as("project.daw is still the link").isTrue();
        assertThat(Files.readString(song)).as("the .daw file it leads to holds the recovered project")
                .contains("Vocals");
        assertThat(writerTempsIn(songB)).isEmpty();
        assertThat(writerTempsIn(context.checkpointDirectory())).isEmpty();
        assertThat(writerTempsIn(shared)).isEmpty();
        assertThat(JournalState.needsRecovery(context))
                .as("only the successful commit consumed the recovery evidence").isFalse();
        List<NotificationEntry> notifications = fixture.notifications();
        assertThat(notifications).extracting(NotificationEntry::level).doesNotContain(NotificationLevel.ERROR);
        assertThat(notifications).filteredOn(entry -> entry.level() == NotificationLevel.WARNING)
                .as("the open announcement: the changes the dialog said cannot be rebuilt are not counted as recovered")
                .extracting(NotificationEntry::message)
                .containsExactly("Recovered the project from its recovery point; the 3 journaled changes after it"
                        + " could not be rebuilt");
    }

    @Test
    void aJournalReplayThroughALinkThatLeadsToNoDawFileBacksNothingUpAndWritesNothing() throws Exception {
        Path songB = aProjectWithACheckpointAndAJournal("Song B");
        Path projectFile = songB.resolve("project.daw");
        Path notes = Files.writeString(shared.resolve("notes.txt"), "not a project");
        Files.delete(projectFile);
        Path leadsTo = Path.of("..", "shared", "notes.txt");
        Files.createSymbolicLink(projectFile, leadsTo);
        ProjectContext context = ProjectContext.forProject(songB);
        Path segment = context.journalDirectory().resolve("segment-000.bin");
        Path base = context.checkpointDirectory().resolve(VERIFIED_BASE);
        Path fact = context.checkpointDirectory().resolve(CheckpointOrder.FILE_NAME);
        byte[] segmentBefore = Files.readAllBytes(segment);
        byte[] baseBefore = Files.readAllBytes(base);
        byte[] factBefore = Files.readAllBytes(fact);
        Set<String> ghosts = CheckpointOrder.read(context.checkpointDirectory());

        recoverEverythingThroughTheOpenGate(songB);

        assertThat(recoveryDialogs).as("one recovery dialog was shown: the fallback never asks again").hasValue(1);
        assertThat(fixture.notifications()).singleElement().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
            assertThat(entry.message()).isEqualTo("Could not write recovered project: "
                    + new FileSystemException(projectFile.toString(), null, NOT_A_DAW_FILE_LINK).getMessage()
                    + "; recovery evidence was preserved; fallback is read-only; opened the last clean save");
        });
        // Nothing can still be on its way: a read-only session runs no checkpoint writer and no journal.
        assertThat(fixture.projectManager.isReadOnly()).as("the last clean save opened read-only").isTrue();
        assertThat(fixture.checkpoints.runningGeneration()).as("no checkpoint generation started").isEmpty();
        assertThat(journalOpens).as("no journal was opened").hasValue(0);
        assertThat(fixture.publishes.done()).as("nothing was published").isEmpty();
        assertThat(Files.readAllBytes(segment)).as("the journal is as it was").isEqualTo(segmentBefore);
        assertThat(Files.readAllBytes(base)).as("the verified replay base is as it was").isEqualTo(baseBefore);
        assertThat(Files.readAllBytes(fact)).as("the ordering fact is as it was").isEqualTo(factBefore);
        assertThat(CheckpointOrder.read(context.checkpointDirectory()))
                .as("the fact still certifies the absent checkpoint: a read-only session revokes nothing")
                .isEqualTo(ghosts);
        assertThat(JournalState.needsRecovery(context)).as("the work is offered again on the next writable open")
                .isTrue();
        assertThat(recoveredBackupsIn(songB)).as("nothing was read through the link into a backup").isEmpty();
        assertThat(writerTempsIn(songB)).isEmpty();
        assertThat(writerTempsIn(context.checkpointDirectory())).isEmpty();
        assertThat(writerTempsIn(shared)).isEmpty();
        assertThat(Files.isSymbolicLink(projectFile)).as("the link is as it was").isTrue();
        assertThat(Files.readSymbolicLink(projectFile)).isEqualTo(leadsTo);
        assertThat(Files.readString(notes)).as("the file it leads to is untouched").isEqualTo("not a project");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Skips the test where this account cannot create symbolic links in {@code folder}. */
    private static void assumeSymbolicLinks(Path folder) throws IOException {
        Path probe = folder.resolve("symbolic-link-check");
        String refusal = "";
        try {
            Files.createSymbolicLink(probe, Path.of("symbolic-link-check-target"));
            Files.delete(probe);
        } catch (UnsupportedOperationException | FileSystemException refused) {
            refusal = String.valueOf(refused);
        }
        assumeTrue(refusal.isEmpty(), "this account cannot create symbolic links here: " + refusal);
    }

    /** A pre-migration backup beside {@code projectDirectory}'s {@code project.daw}, as a first save writes it. */
    private static Path aMigrationBackupOf(Path projectDirectory) throws IOException {
        DawProject original = new DawProject("Song A", AudioFormat.CD_QUALITY);
        original.createAudioTrack("Before the migration");
        return Files.writeString(projectDirectory.resolve("project.daw.v1.20260101-000000.bak"),
                new ProjectSerializer().serialize(original));
    }

    private static MigrationReport aMigrationReport() {
        int current = MigrationRegistry.CURRENT_VERSION;
        return new MigrationReport(1, current,
                List.of(new MigrationReport.AppliedMigration(1, current, "test migration")), Instant.EPOCH);
    }

    /**
     * The files published so far through the fixture's writer that are not
     * checkpoints — which the project the writes lead to opening may write —
     * oldest first.
     */
    private List<Path> publishedOutsideCheckpoints() {
        return fixture.publishes.done(target -> !SaveFlowFixture.isCheckpoint(target)).stream()
                .map(SaveFlowFixture.Publish::target).toList();
    }

    /**
     * Recovers {@code projectDirectory} through the open recovery gate, as
     * production wires it: journaled persistence on, the outgoing journal's
     * close a no-op, and each journal open counted in {@link #journalOpens}.
     * A window listener answers each recovery dialog "Recover everything" and
     * counts it in {@link #recoveryDialogs}; what it fails with is rethrown
     * here, and it is removed before this returns. Returns once
     * {@code projectDirectory} is the current project, its installation has
     * finished and its open was announced.
     */
    private void recoverEverythingThroughTheOpenGate(Path projectDirectory) throws Exception {
        AtomicReference<Throwable> answerFailure = new AtomicReference<>();
        ListChangeListener<Window> answerRecovery = change -> {
            while (change.next()) {
                for (Window window : change.getAddedSubList()) {
                    if (window instanceof Stage stage && RECOVER_UNSAVED_WORK.equals(stage.getTitle())) {
                        recoveryDialogs.incrementAndGet();
                        Platform.runLater(() -> {
                            try {
                                Node root = stage.getScene().getRoot();
                                DialogPane pane = root instanceof DialogPane direct ? direct
                                        : (DialogPane) root.lookup(".dialog-pane");
                                ButtonType choice = pane.getButtonTypes().stream()
                                        .filter(type -> RECOVER_EVERYTHING.equals(type.getText()))
                                        .findFirst().orElseThrow();
                                ((Button) pane.lookupButton(choice)).fire();
                            } catch (Throwable failure) {
                                answerFailure.set(failure);
                                stage.hide();
                            }
                        });
                    }
                }
            }
        };
        try {
            runOnFx(() -> {
                Window.getWindows().addListener(answerRecovery);
                fixture.lifecycle().enableRecoveryGate(() -> true, () -> () -> { },
                        _ -> journalOpens.incrementAndGet());
                fixture.lifecycle().beginRecovery(projectDirectory);
            });
            fixture.awaitOnFx(() -> answerFailure.get() != null
                    || fixture.currentProjectIs(projectDirectory)
                    && !fixture.lifecycle().isProjectInstallationPending()
                    && !fixture.shown.getEntries().isEmpty(),
                    "the recovered " + projectDirectory.getFileName() + " installed and announced");
            if (answerFailure.get() != null) {
                throw new AssertionError("the recovery dialog could not be answered", answerFailure.get());
            }
        } finally {
            runOnFx(() -> Window.getWindows().removeListener(answerRecovery));
        }
    }

    /** The names in {@code folder} that are the atomic writer's temps. */
    private static List<String> writerTempsIn(Path folder) throws IOException {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.map(entry -> entry.getFileName().toString())
                    .filter(name -> AtomicFileWriter.publishedNameOf(name).isPresent()).toList();
        }
    }

    /** The backups a recovery took of {@code projectDirectory}'s {@code project.daw}, by name. */
    private static List<Path> recoveredBackupsIn(Path projectDirectory) throws IOException {
        try (Stream<Path> entries = Files.list(projectDirectory)) {
            return entries.filter(entry -> entry.getFileName().toString().startsWith("project.daw.recovered."))
                    .sorted().toList();
        }
    }

    /**
     * A saved project with a hash-verified checkpoint capture of "Vocals" and
     * three journaled changes after that capture, independent of the current
     * head. Its {@code checkpoints/.checkpoint-order} keeps a ghost
     * certification on purpose, and this checks it: the fact still names
     * the checkpoint the other manager's save wrote and certified, which this
     * deletes, as a copied folder or an interrupted prune leaves it. A
     * writable session revokes the ghost when its checkpoints seed; a
     * read-only one leaves the fact byte-identical.
     */
    private Path aProjectWithACheckpointAndAJournal(String name) throws IOException {
        Path directory = fixture.anotherProjectOnDisk(name);
        DawProject checkpointed = new DawProject(name, AudioFormat.CD_QUALITY);
        checkpointed.createAudioTrack("Vocals");
        ProjectContext context = ProjectContext.forProject(directory);
        Files.createDirectories(context.checkpointDirectory());
        for (Path written : SaveFlowFixture.checkpointFiles(directory)) {
            Files.delete(written);
        }
        Path checkpoint = Files.writeString(context.checkpointDirectory().resolve(VERIFIED_BASE),
                new ProjectSerializer().serialize(checkpointed), StandardCharsets.UTF_8);
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        new JournalRecord(0L, Instant.EPOCH, JournalRecord.CHECKPOINT_TYPE, null,
                JournalState.capture(-1L, checkpoint)).writeTo(java.nio.channels.Channels.newChannel(frames));
        for (int sequence = 0; sequence < 3; sequence++) {
            ByteBuffer frame = JournalRecord.ofMutation(sequence + 1L, Instant.ofEpochMilli(1_000L + sequence),
                    "MixerEvent.MuteChanged", UUID.randomUUID(), null).toBuffer();
            byte[] bytes = new byte[frame.remaining()];
            frame.get(bytes);
            frames.writeBytes(bytes);
        }
        Files.createDirectories(context.journalDirectory());
        Files.write(context.journalDirectory().resolve("segment-000.bin"), frames.toByteArray());
        assertThat(CheckpointOrder.read(context.checkpointDirectory()))
                .as("the ordering fact certifies only checkpoints that are no longer on disk")
                .isNotEmpty().allMatch(certified -> Files.notExists(context.checkpointDirectory().resolve(certified)));
        return directory;
    }
}
