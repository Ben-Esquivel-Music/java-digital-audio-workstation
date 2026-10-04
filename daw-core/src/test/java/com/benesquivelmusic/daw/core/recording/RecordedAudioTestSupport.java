package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a test reads the audio a take recorded: from the segment files, which
 * are the only place it is. Neither the {@link RecordingSession} nor the clip
 * {@link RecordingPipeline#completeStop()} returns holds a sample.
 */
public final class RecordedAudioTestSupport {

    private RecordedAudioTestSupport() {
    }

    /**
     * Decodes the segment files {@code clip} lists, in order, after checking
     * that the clip itself carries no audio and that the frames the files
     * hold are the frames the clip declares.
     *
     * @param clip a clip a recording pipeline built, with at least one segment
     * @return the decoded frames {@code [channel][frame]}
     */
    public static float[][] audioOnDisk(AudioClip clip) {
        assertThat(clip.getAudioData()).as("a recorded clip is built without audio data").isNull();
        assertThat(clip.getSourceSegmentPaths()).as("the clip lists its segment files").isNotEmpty();
        List<Path> files = clip.getSourceSegmentPaths().stream().map(Path::of).toList();
        float[][] audio = read(files);
        assertThat(clip.getSourceRateMetadata()).as("the clip declares what was captured").isNotNull();
        assertThat(clip.getSourceRateMetadata().framesPerChannel())
                .as("the frames the clip declares are the frames its segment files hold")
                .isEqualTo(audio[0].length);
        assertThat(clip.getSourceRateMetadata().channels()).isEqualTo(audio.length);
        return audio;
    }

    /**
     * Decodes every segment {@code session} lists, in order: a sealed one
     * from its {@code .wav}, the one in progress from its {@code .part}.
     * While the session is recording, call this behind the flush fence
     * ({@code awaitFlushed}) or from the thread driving the session.
     *
     * @param session a session that lists at least one segment
     * @return the decoded frames {@code [channel][frame]}
     */
    public static float[][] audioOnDisk(RecordingSession session) {
        List<Path> files = new ArrayList<>();
        for (RecordingSegment segment : session.getSegments()) {
            files.add(segment.isInProgress() ? segment.streamingPath() : segment.filePath());
        }
        assertThat(files).as("the session lists a segment").isNotEmpty();
        return read(files);
    }

    /**
     * What {@code SegmentFile} decodes for a sample captured as {@code value}
     * at 16 bits: the writer stores {@code round(value * 32767)} and the
     * reader divides by {@code 32768}.
     */
    public static float decoded16(float value) {
        return Math.round((double) value * 32767.0) / 32768.0f;
    }

    private static float[][] read(List<Path> files) {
        try {
            return SegmentFile.readFrames(files);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
