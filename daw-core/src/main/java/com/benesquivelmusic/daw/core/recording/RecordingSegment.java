package com.benesquivelmusic.daw.core.recording;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Represents a single segment of a long-running recording.
 *
 * <p>Long recordings are automatically split into segments of configurable
 * duration to manage file sizes and reduce the risk of data loss. Each segment
 * is saved as a separate audio file on disk.</p>
 *
 * @param index       the zero-based segment index within the recording session
 * @param filePath    the path to the audio file for this segment
 * @param startTime   the wall-clock time when recording of this segment began
 * @param endTime     the wall-clock time when recording of this segment ended (null if still recording)
 * @param sampleCount the number of audio samples captured in this segment
 * @param sizeBytes   the approximate size in bytes of the segment file
 */
public record RecordingSegment(
        int index,
        Path filePath,
        Instant startTime,
        Instant endTime,
        long sampleCount,
        long sizeBytes
) {
    public RecordingSegment {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative: " + index);
        }
        Objects.requireNonNull(filePath, "filePath must not be null");
        Objects.requireNonNull(startTime, "startTime must not be null");
        if (sampleCount < 0) {
            throw new IllegalArgumentException("sampleCount must not be negative: " + sampleCount);
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative: " + sizeBytes);
        }
    }

    /**
     * Creates a new in-progress segment with zero samples.
     *
     * @param index    the segment index
     * @param filePath the file path for the segment
     * @return a new segment
     */
    public static RecordingSegment startNew(int index, Path filePath) {
        return new RecordingSegment(index, filePath, Instant.now(), null, 0, 0);
    }

    /**
     * Returns a copy with the segment marked as completed.
     *
     * @param sampleCount total samples recorded
     * @param sizeBytes   file size in bytes
     * @return the finalized segment
     */
    public RecordingSegment complete(long sampleCount, long sizeBytes) {
        return new RecordingSegment(index, filePath, startTime, Instant.now(), sampleCount, sizeBytes);
    }

    /** Returns whether this segment is still being recorded. */
    public boolean isInProgress() {
        return endTime == null;
    }
}
