package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.ClockStatusIndicator.TakeClockMarks;
import com.benesquivelmusic.daw.app.ui.ClockStatusIndicator.TakeRecording;
import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.TakeManifest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.onFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the window's clock badge reads from the current transport's record
 * coordinator through {@code ClockStatusIndicator.takeMarksOf} (story 338,
 * rulings E108 and E146): an audio take is marked, a MIDI-only take records
 * with no audio manifest, so its unlock notice promises no mark, and with no
 * take, or a take still being prepared, nothing records and nothing is
 * marked.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TakeClockMarksOfTheTransportTest {

    @TempDir
    Path folder;

    private RecordingInFlightFixture recording;

    @AfterEach
    void stopTheTake() throws Exception {
        if (recording != null) {
            recording.close();
        }
    }

    @Test
    void aMidiOnlyTakeRecordsWithoutAudio() throws Exception {
        recording = RecordingInFlightFixture.midiOnly(project());
        TakeClockMarks marks = ClockStatusIndicator.takeMarksOf(recording::controller);
        TakeRecording before = onFx(marks::takeRecording);

        recording.start();
        TakeRecording during = onFx(marks::takeRecording);

        assertThat(before).isEqualTo(TakeRecording.NONE);
        assertThat(during).as("RECORDING, with no audio manifest to mark").isEqualTo(TakeRecording.WITHOUT_AUDIO);
    }

    @Test
    void anAudioTakeIsMarked() throws Exception {
        recording = RecordingInFlightFixture.audio(project());
        TakeClockMarks marks = ClockStatusIndicator.takeMarksOf(recording::controller);

        recording.start();

        assertThat(onFx(marks::takeRecording)).isEqualTo(TakeRecording.AUDIO);
    }

    /**
     * Ruling E146: a stream that starts while Record prepares its take
     * belongs to no recording yet. In the FX turn of the Record press the
     * coordinator is PREPARING: the badge's unlock notice there is the plain
     * one, promising neither that recording continues nor a mark, and a
     * clock change the badge hands the take then is not marked in it once
     * it records.
     */
    @Test
    void aTakeBeingPreparedRecordsNothingAndAClockChangeThenMarksNothing() throws Exception {
        recording = RecordingInFlightFixture.audio(project());
        TakeClockMarks marks = ClockStatusIndicator.takeMarksOf(recording::controller);

        WhilePreparing whilePreparing = onFx(() -> {
            recording.startOnFx();
            marks.lockChanged(false, 0L);
            return new WhilePreparing(recording.controller().recordCoordinator().getState(), marks.takeRecording());
        });
        recording.assertStillRecording();
        Path take = onFx(recording.controller()::activeTakeDirectory).orElseThrow();
        recording.stop();

        assertThat(whilePreparing.state()).as("premise: the take is being prepared").isEqualTo(RecordState.PREPARING);
        assertThat(whilePreparing.recording()).as("nothing records yet: the plain notice")
                .isEqualTo(TakeRecording.NONE);
        assertThat(TakeManifest.read(TakeManifest.manifestPath(take)).clockMarks())
                .as("a clock change before the take recorded is not the take's").isEmpty();
    }

    /** What the record press's FX turn saw: the coordinator's state and what the badge's marks answered. */
    private record WhilePreparing(RecordState state, TakeRecording recording) {
    }

    private DawProject project() {
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        project.setMetadata(project.getMetadata().withPath(folder));
        return project;
    }
}
