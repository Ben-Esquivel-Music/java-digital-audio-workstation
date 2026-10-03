package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.track.Track;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.SAMPLE_RATE;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.rampBlock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TrackCapture#discardAllFiles()} — the rollback of a start that
 * failed or was aborted — deletes only what the capture created (PR #978
 * review 5401214925; Recording Reliability book §5.1, §5.2): the
 * {@code .wav} of each sealed segment, never the {@code .part} name its
 * seal's rename vacated; and the track directory only if the start of one
 * of its lane sessions created it, so lane 0's creation still counts once
 * lane 1, whose start found the directory in place, is current, while a
 * lane session that a session factory pointed at another directory claims
 * nothing at the track path.
 */
class TrackCaptureDiscardTest {

    @TempDir
    Path takeDir;

    private Track track;
    private Path trackDir;
    private TrackCapture capture;

    @BeforeEach
    void setUp() {
        track = RampCaptureTestSupport.armedMonoTrack("Audio 1");
        trackDir = takeDir.resolve(track.getId());
        capture = captureWith((t, dir) -> new RecordingSession(MONO_16, dir));
    }

    /** A capture of the fixture's track into {@code trackDir} whose lane sessions {@code sessions} creates. */
    private TrackCapture captureWith(TrackCapture.SessionFactory sessions) {
        return new TrackCapture(track, track.getInputRouting(), -1, 1, BLOCK_FRAMES, 0L, 0.0,
                SAMPLE_RATE, 120.0, trackDir, sessions);
    }

    /** Closes the current lane's streaming segment if a test left it open, so no channel outlives the test. */
    @AfterEach
    void closeTheStreamingSegment() throws IOException {
        SegmentWriter writer = capture.session().getCurrentWriter();
        if (writer != null && writer.isStreaming()) {
            writer.abandon();
        }
    }

    /** Starts lane 0, records one block, then seals lane 0 and opens lane 1, as a loop wrap does. */
    private void recordLaneZeroThenOpenLaneOne() {
        capture.startLane();
        capture.session().recordAudioData(rampBlock(0), BLOCK_FRAMES);
        capture.finalizeLane(true, true);
        assertThat(capture.lane()).as("fixture: lane 1 is current").isEqualTo(1);
        assertThat(trackDir.resolve("segment-000.wav")).as("fixture: lane 0 sealed its segment").exists();
        assertThat(trackDir.resolve("segment-001.wav.part")).as("fixture: lane 1 is streaming").exists();
    }

    @Test
    void aForeignFileAtASealedSegmentsPartNameSurvivesTheDiscard() throws IOException {
        recordLaneZeroThenOpenLaneOne();
        Path vacated = trackDir.resolve("segment-000.wav.part");
        byte[] foreign = "not the capture's".getBytes(StandardCharsets.US_ASCII);
        Files.write(vacated, foreign);

        capture.discardAllFiles();

        assertThat(vacated).as("the seal's rename vacated that name; what is there now is not the capture's")
                .isRegularFile();
        assertThat(Files.readAllBytes(vacated)).isEqualTo(foreign);
        assertThat(trackDir.resolve("segment-000.wav")).as("lane 0's sealed segment is deleted").doesNotExist();
        assertThat(trackDir.resolve("segment-001.wav.part")).as("lane 1's streaming segment is deleted")
                .doesNotExist();
        assertThat(entries(trackDir)).as("the track directory still holds the foreign file, so it stays")
                .containsExactly(vacated);
    }

    @Test
    void aTrackDirectoryTheCaptureFoundInPlaceSurvivesTheDiscard() throws IOException {
        Files.createDirectory(trackDir);
        recordLaneZeroThenOpenLaneOne();

        capture.discardAllFiles();

        assertThat(trackDir).as("no lane's start created it, so it is not the capture's to remove").isDirectory();
        assertThat(entries(trackDir)).as("and the capture's own files are gone").isEmpty();
    }

    @Test
    void aTrackDirectoryLaneZeroCreatedIsRemovedWhileLaneOneIsCurrent() {
        assertThat(trackDir).as("fixture: lane 0's start creates the track directory").doesNotExist();
        recordLaneZeroThenOpenLaneOne();

        capture.discardAllFiles();

        assertThat(trackDir).as("lane 1 found it in place, but lane 0 created it").doesNotExist();
        assertThat(takeDir).as("the take directory is not the capture's").isDirectory();
    }

    @Test
    void aSessionDirectoryElsewhereGivesTheCaptureNoClaimOnTheTrackPath() throws IOException {
        byte[] foreign = "not the capture's".getBytes(StandardCharsets.US_ASCII);
        Files.write(trackDir, foreign);
        Path elsewhere = takeDir.resolve("elsewhere");
        capture = captureWith((_, _) -> {
            RecordingSession session = new RecordingSession(MONO_16, elsewhere);
            session.setChannelOpener(_ -> {
                throw new IOException("injected open failure");
            });
            return session;
        });

        assertThatThrownBy(capture::startLane).isInstanceOf(UncheckedIOException.class);
        assertThat(capture.session().createdOutputDirectory()).as("fixture: the session created ITS directory")
                .isTrue();
        capture.discardAllFiles();

        assertThat(elsewhere).as("the session removed its own directory").doesNotExist();
        assertThat(trackDir).as("no lane start created the track path, so the foreign file there stays")
                .isRegularFile();
        assertThat(Files.readAllBytes(trackDir)).isEqualTo(foreign);
    }

    private static List<Path> entries(Path dir) throws IOException {
        try (Stream<Path> listed = Files.list(dir)) {
            return listed.toList();
        }
    }
}
