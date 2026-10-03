package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RecordingSession}'s claim on its output directory, and what its
 * rollback ({@link RecordingSession#discardAllFiles()}) may delete, with
 * inputs the session's other tests do not use: a parent chain missing two
 * levels deep, which the start creates and the rollback leaves; a second
 * start, refused by a foreign file that took the name of the directory the
 * first start created, which therefore owns nothing there; and a foreign
 * file at the {@code .part} name that a seal of the session itself vacated.
 */
class RecordingSessionOutputDirectoryClaimTest {

    @TempDir
    Path tempDir;

    private final List<RecordingSession> sessions = new ArrayList<>();
    private final byte[] foreign = "not the session's".getBytes(StandardCharsets.US_ASCII);

    private RecordingSession tracked(RecordingSession session) {
        sessions.add(session);
        return session;
    }

    /** Closes the streaming segment each session left open, without sealing it. */
    @AfterEach
    void closeEveryStreamingSegment() throws IOException {
        for (RecordingSession session : sessions) {
            SegmentWriter writer = session.getCurrentWriter();
            if (writer != null && writer.isStreaming()) {
                writer.abandon();
            }
        }
        sessions.clear();
    }

    @Test
    void aStartCreatesAMissingParentChainAndTheRollbackRemovesOnlyTheLeafItCreated() throws IOException {
        Path grandparent = tempDir.resolve("missing-1");
        Path parent = grandparent.resolve("missing-2");
        Path leaf = parent.resolve("track");
        RecordingSession session = tracked(new RecordingSession(AudioFormat.CD_QUALITY, leaf));

        session.start();
        assertThat(session.createdOutputDirectory()).as("the start created the leaf").isTrue();
        assertThat(leaf.resolve("segment-000.wav.part")).exists();
        session.discardAllFiles();

        assertThat(leaf).as("the leaf this start created is removed").doesNotExist();
        assertThat(parent).as("missing parents are created first; the session never removes those").isDirectory();
        assertThat(entries(parent)).isEmpty();
        assertThat(entries(grandparent)).containsExactly(parent);
    }

    @Test
    void aRestartRefusedByAForeignFileLeavesItThoughAnEarlierStartCreatedTheDirectory() throws IOException {
        Path dir = tempDir.resolve("replaced");
        RecordingSession session = tracked(new RecordingSession(AudioFormat.CD_QUALITY, dir));
        session.start();
        session.stop(); // zero frames: the empty tail is deleted, the directory is empty
        Files.delete(dir);
        Files.write(dir, foreign); // someone else's file now has the directory's name

        assertThatThrownBy(session::start)
                .isInstanceOf(UncheckedIOException.class)
                .hasRootCauseInstanceOf(FileAlreadyExistsException.class);
        session.discardAllFiles();

        assertThat(dir).as("the refused restart owns nothing at that name").isRegularFile();
        assertThat(Files.readAllBytes(dir)).isEqualTo(foreign);
    }

    @Test
    void discardAllFilesLeavesAForeignFileAtThePartNameASealOfThisSessionVacated() throws IOException {
        Path dir = tempDir.resolve("rotated");
        RecordingSession session = tracked(new RecordingSession(AudioFormat.CD_QUALITY, dir,
                Duration.ofHours(1), 800L));
        session.start();
        session.recordAudioData(block(250), 250); // 1000 bytes >= 800: seals segment-000, opens 001
        Path sealedWav = dir.resolve("segment-000.wav");
        Path vacated = dir.resolve("segment-000.wav.part");
        assertThat(sealedWav).as("fixture: segment-000 sealed").exists();
        assertThat(vacated).as("fixture: the seal's rename vacated the .part name").doesNotExist();
        Files.write(vacated, foreign);

        session.discardAllFiles();

        assertThat(vacated).as("what is at the vacated name now is not the session's").isRegularFile();
        assertThat(Files.readAllBytes(vacated)).isEqualTo(foreign);
        assertThat(sealedWav).as("the session's sealed segment is deleted").doesNotExist();
        assertThat(dir.resolve("segment-001.wav.part")).as("and its streaming one").doesNotExist();
        assertThat(entries(dir)).containsExactly(vacated);
    }

    /** A CD-quality (two-channel) block of {@code frames} frames at a constant level. */
    private static float[][] block(int frames) {
        float[][] input = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            input[0][i] = 0.1f;
            input[1][i] = 0.1f;
        }
        return input;
    }

    private static List<Path> entries(Path dir) throws IOException {
        try (Stream<Path> listed = Files.list(dir)) {
            return listed.toList();
        }
    }
}
