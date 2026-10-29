package com.benesquivelmusic.daw.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AtomicFileWriter#publishDirectory} and
 * {@link AtomicFileWriter#stagingSiblingOf} (story 333, ruling R5; ENG-S11:
 * one directory publish for the product — archive restore and Save As): a
 * fully written staging directory becomes the target in one rename through
 * the writer's mover, an existing target is refused before anything moves,
 * and a transient refusal is retried as every publish retries it.
 *
 * <p>The three race-window tests pin, on every OS, that whatever appears at
 * the target between the writer's check and the move — an empty folder, a
 * folder with files, a file — is never replaced. (The JDK move's own check
 * then {@code rename(2)} on POSIX leaves an instant no seam reaches; the
 * writer's Javadoc states it.) The {@code ATOMIC_MOVE} flag would
 * break that differently per OS: on Linux {@code rename(2)} replaces the
 * empty folder, on Windows {@code MOVEFILE_REPLACE_EXISTING} replaces the
 * file. A fault run of this class therefore proves only the OS it ran on;
 * run it on Linux (CI) and on Windows.</p>
 */
class PublishDirectoryTest {

    @TempDir
    Path tmp;

    /** A mover that records every move and can be told to act before it delegates. */
    private static final class ScriptedMover implements AtomicFileWriter.Mover {

        record Move(Path source, Path target, List<CopyOption> options) {
        }

        final List<Move> moves = new CopyOnWriteArrayList<>();
        volatile MoveStep beforeMove = (source, target, options) -> { };

        @FunctionalInterface
        interface MoveStep {
            void run(Path source, Path target, CopyOption... options) throws IOException;
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            moves.add(new Move(source, target, List.of(options)));
            beforeMove.run(source, target, options);
            AtomicFileWriter.FILE_SYSTEM_MOVER.move(source, target, options);
        }
    }

    private static Path stagingWithOneFile(Path parent, String name) throws IOException {
        Path staging = AtomicFileWriter.stagingSiblingOf(parent.resolve(name), "saving-as");
        Files.createDirectories(staging.resolve("audio"));
        Files.writeString(staging.resolve("project.daw"), "<daw-project/>");
        Files.writeString(staging.resolve("audio").resolve("take.wav"), "RIFF");
        return staging;
    }

    @Test
    void aStagingDirectoryBecomesTheTargetInOneRenameThatReplacesNothing() throws IOException {
        ScriptedMover mover = new ScriptedMover();
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, mover);
        Path target = tmp.resolve("Song B");
        Path staging = stagingWithOneFile(tmp, "Song B");

        Path published = writer.publishDirectory(staging, target);

        assertThat(published).isEqualTo(target);
        assertThat(staging).doesNotExist();
        assertThat(target.resolve("project.daw")).hasContent("<daw-project/>");
        assertThat(target.resolve("audio").resolve("take.wav")).hasContent("RIFF");
        assertThat(mover.moves)
                .as("one move of the whole staging directory, with no option: never ATOMIC_MOVE, which replaces")
                .containsExactly(new ScriptedMover.Move(staging, target, List.of()));
    }

    @Test
    void anExistingEmptyFolderIsRefusedBeforeAnythingMoves() throws IOException {
        ScriptedMover mover = new ScriptedMover();
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, mover);
        Path target = Files.createDirectory(tmp.resolve("Song B"));
        Path staging = stagingWithOneFile(tmp, "Song B");

        assertThatThrownBy(() -> writer.publishDirectory(staging, target))
                .isInstanceOf(FileAlreadyExistsException.class)
                .hasMessageContaining(target.toString());

        assertThat(mover.moves).as("the check comes before the rename: nothing moved").isEmpty();
        assertThat(target).isEmptyDirectory();
        assertThat(staging.resolve("project.daw")).as("the staging directory is left for its caller").exists();
    }

    @Test
    void anExistingFileIsRefusedBeforeAnythingMoves() throws IOException {
        ScriptedMover mover = new ScriptedMover();
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, mover);
        Path target = Files.writeString(tmp.resolve("Song B"), "a file");
        Path staging = stagingWithOneFile(tmp, "Song B");

        assertThatThrownBy(() -> writer.publishDirectory(staging, target))
                .isInstanceOf(FileAlreadyExistsException.class);

        assertThat(mover.moves).isEmpty();
        assertThat(target).hasContent("a file");
    }

    @Test
    void aTransientAccessDenialIsRetried() throws IOException {
        ScriptedMover mover = new ScriptedMover();
        AtomicInteger refusals = new AtomicInteger();
        mover.beforeMove = (source, target, options) -> {
            if (refusals.getAndIncrement() == 0) {
                throw new AccessDeniedException(target.toString(), null, "held by a scanner");
            }
        };
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, mover);
        Path target = tmp.resolve("Song B");
        Path staging = stagingWithOneFile(tmp, "Song B");

        writer.publishDirectory(staging, target);

        assertThat(mover.moves).as("the refused rename, then the retry").hasSize(2)
                .allSatisfy(move -> assertThat(move.options()).isEmpty());
        assertThat(target.resolve("audio").resolve("take.wav")).exists();
    }

    /**
     * The studio engineer's ruling S4 on stage 3: Windows refuses to rename
     * a folder while a program holds a file inside it open, so a directory
     * publish waits longer than a file's 130 ms — here five refusals, about
     * 880 ms, where a file's publish gives up after three — and says so once
     * it waits past a file's budget.
     */
    @Test
    void aFolderHeldByAnotherProgramIsWaitedForLongerThanAFileAndTheWaitIsLogged() throws IOException {
        ScriptedMover mover = new ScriptedMover();
        AtomicInteger refusals = new AtomicInteger();
        mover.beforeMove = (source, target, options) -> {
            if (refusals.getAndIncrement() < 5) {
                throw new AccessDeniedException(target.toString(), null, "a file inside is held open");
            }
        };
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, mover);
        Path target = tmp.resolve("Song B");
        Path staging = stagingWithOneFile(tmp, "Song B");
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        List<LogRecord> infos = new CopyOnWriteArrayList<>();
        Handler handler = recordingTo(warnings, infos);
        Logger log = Logger.getLogger(AtomicFileWriter.class.getName());
        log.addHandler(handler);
        try {
            writer.publishDirectory(staging, target);
        } finally {
            log.removeHandler(handler);
        }

        assertThat(mover.moves).as("five refused renames, then the one that published").hasSize(6);
        assertThat(target.resolve("audio").resolve("take.wav")).exists();
        assertThat(AtomicFileWriter.DIRECTORY_PUBLISH_RETRIES).isGreaterThan(AtomicFileWriter.TRANSIENT_DENIAL_RETRIES);
        assertThat(warnings).as("once, when the wait passed a file's 130 ms").singleElement()
                .satisfies(warning -> assertThat(warning.getMessage())
                        .isEqualTo(target + " is held by another program; waiting to publish it"));
        assertThat(infos).as("ruling 3 Q3: once published, how long it waited in all (10+30+90+250+500)")
                .singleElement().satisfies(info -> assertThat(info.getMessage())
                        .isEqualTo(target + " was published after 880 ms of waiting for another program"));
    }

    @Test
    void aFolderPublishedWithinAFilesBudgetLogsNeitherTheWarningNorTheWait() throws IOException {
        ScriptedMover mover = new ScriptedMover();
        AtomicInteger refusals = new AtomicInteger();
        mover.beforeMove = (source, target, options) -> {
            if (refusals.getAndIncrement() < 3) {
                throw new AccessDeniedException(target.toString(), null, "a file inside is held open");
            }
        };
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, mover);
        Path target = tmp.resolve("Song B");
        Path staging = stagingWithOneFile(tmp, "Song B");
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        List<LogRecord> infos = new CopyOnWriteArrayList<>();
        Handler handler = recordingTo(warnings, infos);
        Logger log = Logger.getLogger(AtomicFileWriter.class.getName());
        log.addHandler(handler);
        try {
            writer.publishDirectory(staging, target);
        } finally {
            log.removeHandler(handler);
        }

        assertThat(mover.moves).as("three refused renames, then the one that published").hasSize(4);
        assertThat(warnings).isEmpty();
        assertThat(infos).isEmpty();
    }

    /** A log handler that keeps the writer's WARNING and INFO records apart. */
    private static Handler recordingTo(List<LogRecord> warnings, List<LogRecord> infos) {
        return new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                if (logRecord.getLevel() == Level.WARNING) {
                    warnings.add(logRecord);
                } else if (logRecord.getLevel() == Level.INFO) {
                    infos.add(logRecord);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }

    @Test
    void aTargetThatExistsByTheTimeOfTheRenameIsNeverRetried() throws IOException {
        ScriptedMover mover = new ScriptedMover();
        mover.beforeMove = (source, target, options) -> {
            throw new FileAlreadyExistsException(target.toString());
        };
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, mover);
        Path target = tmp.resolve("Song B");
        Path staging = stagingWithOneFile(tmp, "Song B");

        assertThatThrownBy(() -> writer.publishDirectory(staging, target))
                .isInstanceOf(FileAlreadyExistsException.class);

        assertThat(mover.moves).as("a lasting refusal: one attempt").hasSize(1);
        assertThat(staging.resolve("project.daw")).exists();
    }

    @Test
    void anEmptyFolderCreatedBetweenTheCheckAndTheRenameIsNeverReplaced() throws IOException {
        ScriptedMover mover = new ScriptedMover();
        mover.beforeMove = (source, target, options) -> {
            if (!Files.exists(target)) {
                Files.createDirectory(target);
            }
        };
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, mover);
        Path target = tmp.resolve("Song B");
        Path staging = stagingWithOneFile(tmp, "Song B");

        assertThatThrownBy(() -> writer.publishDirectory(staging, target))
                .isInstanceOf(FileSystemException.class);

        assertThat(target).as("the folder another writer made stays as it was").isEmptyDirectory();
        assertThat(staging.resolve("project.daw")).exists();
    }

    @Test
    void aNonEmptyFolderCreatedBetweenTheCheckAndTheRenameIsNeverReplaced() throws IOException {
        ScriptedMover mover = new ScriptedMover();
        mover.beforeMove = (source, target, options) -> {
            if (!Files.exists(target)) {
                Files.createDirectory(target);
                Files.writeString(target.resolve("theirs.txt"), "theirs");
            }
        };
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, mover);
        Path target = tmp.resolve("Song B");
        Path staging = stagingWithOneFile(tmp, "Song B");

        assertThatThrownBy(() -> writer.publishDirectory(staging, target))
                .isInstanceOf(FileSystemException.class);

        assertThat(target.resolve("theirs.txt")).hasContent("theirs");
        assertThat(target.resolve("project.daw")).doesNotExist();
        assertThat(staging.resolve("project.daw")).exists();
    }

    @Test
    void aFileCreatedBetweenTheCheckAndTheRenameIsNeverReplaced() throws IOException {
        ScriptedMover mover = new ScriptedMover();
        mover.beforeMove = (source, target, options) -> {
            if (!Files.exists(target)) {
                Files.writeString(target, "theirs");
            }
        };
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, mover);
        Path target = tmp.resolve("Song B");
        Path staging = stagingWithOneFile(tmp, "Song B");

        assertThatThrownBy(() -> writer.publishDirectory(staging, target))
                .isInstanceOf(FileSystemException.class);

        assertThat(target).isRegularFile().hasContent("theirs");
        assertThat(staging.resolve("project.daw")).exists();
    }

    @Test
    void theStagingSiblingIsAHiddenFixedNamePerTargetAndPurpose() {
        Path target = tmp.resolve("Song B");

        assertThat(AtomicFileWriter.stagingSiblingOf(target, "saving-as"))
                .isEqualTo(tmp.resolve(".Song B.saving-as.tmp"));
        assertThat(AtomicFileWriter.stagingSiblingOf(target, "restoring"))
                .as("the archive restore's staging name, unchanged")
                .isEqualTo(tmp.resolve(".Song B.restoring.tmp"));
        assertThat(AtomicFileWriter.publishedNameOf(".Song B.saving-as.tmp"))
                .as("never read as one of the writer's file temps")
                .isEmpty();
    }

    @Test
    void aStagingPurposeIsLowercaseWordsAndNeverANonce() {
        Path target = tmp.resolve("Song B");

        assertThatThrownBy(() -> AtomicFileWriter.stagingSiblingOf(target, "Saving"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AtomicFileWriter.stagingSiblingOf(target, "a/b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AtomicFileWriter.stagingSiblingOf(target, "abcdefabcdefabcd"))
                .as("sixteen hex digits would read as a file temp's nonce")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AtomicFileWriter.stagingSiblingOf(tmp.getRoot(), "saving-as"))
                .as("a root names no folder to stage beside")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
