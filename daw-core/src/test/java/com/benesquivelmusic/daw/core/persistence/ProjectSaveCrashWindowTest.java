package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.RecordingMover;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.HEAD;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.failed;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.tempsIn;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A save that fails in the head write's crash window — the temp written and
 * forced, the move not yet made — or whose atomic move and its
 * {@code REPLACE_EXISTING} fallback both fail, leaves the previous
 * {@code project.daw} byte for byte, still opening, with no temp beside it,
 * and reports the exception that ended the write as thrown (story 331,
 * "Crash-shaped atomicity test"; Persistence Integrity book §4.1). Both are
 * injected at the {@link AtomicFileWriter} mover seam.
 */
class ProjectSaveCrashWindowTest {

    @TempDir
    Path tempDir;

    private final List<ProjectManager> managers = new ArrayList<>();

    @AfterEach
    void releaseTheManagers() {
        // Each release reads and deletes a lock file: let it end before the temporary folder is deleted.
        managers.forEach(manager -> SaveTestSupport.await(manager.closeProject()));
    }

    @Test
    void aSaveThatFailsBetweenTheForcedTempAndTheMoveLeavesThePreviousHeadWhichStillOpens() throws IOException {
        RecordingMover mover = new RecordingMover();
        ProjectManager manager = manager(new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, mover));
        Path directory = manager.createProject("Song", tempDir).projectPath();
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vox");
        saved(manager, project);
        byte[] previous = Files.readAllBytes(directory.resolve("project.daw"));
        project.createAudioTrack("Drums");
        project.markDirty();
        IOException crash = new IOException("injected failure between the forced temp and the move");
        mover.failWith(HEAD, crash);

        SaveOutcome.Failed outcome = failed(manager, project);

        assertThat(outcome.cause()).as("the exception that ended the write, as thrown").isSameAs(crash);
        assertThat(new String(mover.failedTempBytes(), StandardCharsets.UTF_8))
                .as("non-vacuity: the temp was complete when the move was refused").contains("name=\"Drums\"");
        assertThat(Files.readAllBytes(directory.resolve("project.daw"))).as("the previous head, byte for byte")
                .isEqualTo(previous);
        assertThat(tempsIn(directory)).as("no temp beside it").isEmpty();
        manager.closeProject().join();

        ProjectManager reopened = manager(new AtomicFileWriter());
        reopened.openProject(directory);
        assertThat(reopened.getCurrentDawProject().getTracks()).as("the previous head still opens")
                .extracting(Track::getName)
                .containsExactly("Vox");
    }

    @Test
    void aSaveWhoseAtomicMoveAndFallbackBothFailLeavesTheHeadAndNoTemp() throws IOException {
        IOException fallbackFailure = new IOException("injected failure of the plain-replace fallback");
        AtomicBoolean failing = new AtomicBoolean();
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, target, options) -> {
                    if (!failing.get() || !HEAD.test(target)) {
                        AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, target, options);
                    } else if (List.of(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                        throw new AtomicMoveNotSupportedException(temp.toString(), target.toString(),
                                "no atomic move");
                    } else {
                        throw fallbackFailure;
                    }
                });
        ProjectManager manager = manager(writer);
        Path directory = manager.createProject("Song", tempDir).projectPath();
        byte[] previous = Files.readAllBytes(directory.resolve("project.daw"));
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vox");
        failing.set(true);

        SaveOutcome.Failed outcome = failed(manager, project);

        assertThat(outcome.cause()).as("the fallback's exception, as thrown").isSameAs(fallbackFailure);
        assertThat(fallbackFailure.getSuppressed()).as("with the refused atomic move attached")
                .hasAtLeastOneElementOfType(AtomicMoveNotSupportedException.class);
        assertThat(Files.readAllBytes(directory.resolve("project.daw"))).as("project.daw, byte for byte")
                .isEqualTo(previous);
        assertThat(tempsIn(directory)).as("no temp beside it").isEmpty();
    }

    private ProjectManager manager(AtomicFileWriter writer) {
        ProjectManager manager = SaveTestSupport.manager(
                new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true), writer),
                new ProjectLockManager(), writer, ProjectManager.CHECKPOINT_WAIT);
        managers.add(manager);
        return manager;
    }
}
