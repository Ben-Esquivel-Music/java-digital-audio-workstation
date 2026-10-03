package com.benesquivelmusic.daw.core.recording;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Represents a single segment of a long-running recording — metadata for a
 * file that exists on disk (Recording Reliability book §3.4, §5.3; story 323).
 *
 * <p>Long recordings are automatically split into segments of configurable
 * duration to manage file sizes and reduce the risk of data loss. Each segment
 * is streamed to its own WAV file by a {@link SegmentWriter}: while in
 * progress the bytes live at {@link #streamingPath()} ({@code segment-NNN.wav.part});
 * once sealed they live at {@link #filePath()} ({@code segment-NNN.wav}), which is
 * the segment's identity from the moment it is opened. A {@code RecordingSegment}
 * never describes a file that does not exist (book §9.3).</p>
 *
 * <p>Counts are exact: {@code sampleCount} is the number of frames the
 * writer appended and {@code sizeBytes} is the exact size of the WAV data
 * chunk (frames × channels × bytes per sample) — never a wall-clock
 * estimate. Both are {@code 0} while the segment is in progress.</p>
 *
 * @param index       the segment index within the track directory; it matches
 *                    the file name, and a session opened mid-take (a later
 *                    loop lane) starts above zero
 * @param filePath    the sealed {@code .wav} identity of this segment
 * @param startTime   the wall-clock time when recording of this segment began
 * @param endTime     the wall-clock time when recording of this segment ended (null if still recording)
 * @param sampleCount the exact number of audio frames sealed into this segment
 * @param sizeBytes   the exact size in bytes of the sealed data chunk
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
     * @param sampleCount exact frames sealed into the segment
     * @param sizeBytes   exact data-chunk size in bytes
     * @return the finalized segment
     */
    public RecordingSegment complete(long sampleCount, long sizeBytes) {
        return new RecordingSegment(index, filePath, startTime, Instant.now(), sampleCount, sizeBytes);
    }

    /** Returns whether this segment is still being recorded. */
    public boolean isInProgress() {
        return endTime == null;
    }

    /**
     * Returns the streaming {@code .part} path the bytes live at while the
     * segment is in progress: {@link #filePath()} plus
     * {@link SegmentWriter#PART_SUFFIX}.
     */
    public Path streamingPath() {
        return SegmentWriter.partPathFor(filePath);
    }
}
