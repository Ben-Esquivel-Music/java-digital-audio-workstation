package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointEvent;
import com.benesquivelmusic.daw.core.persistence.CheckpointOrigin;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.persistence.journal.JournalState;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, the studio engineer's ruling R3 (and N7): Discard and close
 * keeps the discarded work in the closing checkpoint and says so in one INFO
 * log line — no toast; a closing checkpoint that fails does not stop the
 * exit: a WARNING is logged, the seal is still written and the window
 * hides; and under a lock found taken over by the lock check after a
 * Discard, no closing checkpoint can be written, which the INFO line says
 * instead.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ClosingCheckpointFailureDoesNotStopTheExitTest {

    @TempDir
    Path workspace;

    private ExitFixture fixture;
    private final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    private final Handler recording = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            logged.add(logRecord);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void songAEditedAndToBeDiscarded() throws Exception {
        Logger.getLogger(ExitCoordinator.class.getName()).addHandler(recording);
        Logger.getLogger(ProjectFolderCloser.class.getName()).addHandler(recording);
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.save.save();
        fixture.save.settle();
        fixture.edit("Bass");
        fixture.save.promptAnswer = "Discard and close";
    }

    @AfterEach
    void close() throws Exception {
        try {
            fixture.close();
        } finally {
            Logger.getLogger(ExitCoordinator.class.getName()).removeHandler(recording);
            Logger.getLogger(ProjectFolderCloser.class.getName()).removeHandler(recording);
        }
    }

    @Test
    void theDiscardedWorkIsKeptInTheClosingCheckpointWhichOneInfoLineNames() throws Exception {
        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        CheckpointEvent.Written closing = fixture.save.checkpointEvents.stream()
                .filter(event -> event instanceof CheckpointEvent.Written written
                        && written.origin() == CheckpointOrigin.CLOSING)
                .map(CheckpointEvent.Written.class::cast).findFirst().orElseThrow();
        assertThat(messages(Level.INFO)).contains("Discarded changes of “Song A” were kept in checkpoint "
                + closing.file().getFileName() + ".");
        assertThat(fixture.save.notifications()).as("a log line, not a toast").isEmpty();
        assertThat(JournalState.needsRecovery(ProjectContext.forProject(fixture.songA)))
                .as("the next open honours the Discard and opens plainly").isFalse();
    }

    @Test
    void aClosingCheckpointThatFailsIsLoggedAndTheSealIsStillWrittenAndTheWindowHides() throws Exception {
        fixture.save.publishes.failNext(SaveFlowFixture::isCheckpoint, new IOException("the volume went away"));

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(fixture.stepNames()).containsSubsequence("CLOSING_CHECKPOINT FAILED", "SEAL DONE",
                "RELEASE DONE", "HIDE DONE");
        assertThat(messages(Level.WARNING)).contains("The closing checkpoint could not be written; the close goes on");
        assertThat(JournalState.records(ProjectContext.forProject(fixture.songA)).getLast().isSessionSeal())
                .as("the seal is still written").isTrue();
        assertThat(messages(Level.INFO)).noneMatch(message -> message.startsWith("Discarded changes"));
    }

    @Test
    void aLockFoundTakenOverAfterADiscardWritesNoClosingCheckpointAndTheInfoLineSaysWhy() throws Exception {
        new ProjectLockManager(InstantSource.system(), "alice", "Studio-PC", 42L).forceAcquire(fixture.songA);

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(messages(Level.INFO)).contains(
                "Discarded changes of “Song A” were not kept: another session holds its lock.");
        assertThat(fixture.save.checkpointEvents).noneMatch(event -> event instanceof CheckpointEvent.Written written
                && written.origin() == CheckpointOrigin.CLOSING);
        assertThat(fixture.save.takeoverHeaders).as("no dialog: the Discard wrote nothing").isEmpty();
    }

    private List<String> messages(Level level) {
        return logged.stream().filter(entry -> entry.getLevel() == level).map(LogRecord::getMessage).toList();
    }
}
