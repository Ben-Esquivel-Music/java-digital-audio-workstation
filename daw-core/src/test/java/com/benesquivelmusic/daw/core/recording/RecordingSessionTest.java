package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.sdk.event.RecordingListener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.benesquivelmusic.daw.core.recording.RecordingSession.DEFAULT_MAX_SEGMENT_BYTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RecordingSession} — the per-track streaming capture of story 323
 * (book §4.4, §5.3; context D10). Every block fed through
 * {@code recordAudioData} must land on disk as well as in the RAM mirror,
 * counts are exact, and every {@link RecordingSegment} names a real file.
 */
class RecordingSessionTest {

    /** CD quality: 2 channels × 16 bit = 4 bytes per frame. */
    private static final int BYTES_PER_FRAME = 4;

    @TempDir
    Path tempDir;

    private static float[][] block(int frames, float left, float right) {
        float[][] input = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            input[0][i] = left;
            input[1][i] = right;
        }
        return input;
    }

    private static float[][] ramp(int frames, int firstValue) {
        float[][] input = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            input[0][i] = (firstValue + i) / 32767f;
            input[1][i] = -(firstValue + i) / 32767f;
        }
        return input;
    }

    @Test
    void shouldStartSession() {
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);

        session.start();

        assertThat(session.isActive()).isTrue();
        assertThat(session.isPaused()).isFalse();
        assertThat(session.getSessionStartTime()).isNotNull();
        assertThat(session.getSegmentCount()).isEqualTo(1);
        assertThat(session.getCurrentSegment()).isNotNull();
    }

    @Test
    void startCreatesTheFirstStreamingSegmentFileOnDisk() throws IOException {
        Path trackDir = tempDir.resolve("track-a");
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, trackDir);

        session.start();

        RecordingSegment current = session.getCurrentSegment();
        assertThat(current.index()).isZero();
        assertThat(current.filePath()).isEqualTo(trackDir.resolve("segment-000.wav"));
        assertThat(current.streamingPath()).isEqualTo(trackDir.resolve("segment-000.wav.part"));
        assertThat(current.streamingPath()).exists();
        assertThat(current.filePath()).doesNotExist();
        assertThat(current.isInProgress()).isTrue();
        SegmentFile.Description description = SegmentFile.describe(current.streamingPath());
        assertThat(description.sealed()).isFalse();
        assertThat(description.frameCount()).isZero();
        assertThat(description.channels()).isEqualTo(2);
        assertThat(description.bitDepth()).isEqualTo(16);
        assertThat(session.getCurrentWriter()).isNotNull();
        assertThat(session.getCurrentWriter().isStreaming()).isTrue();
    }

    @Test
    void startRejectsAnUnsupportedBitDepthBeforeCreatingAnySegment() {
        AudioFormat eightBit = new AudioFormat(44_100.0, 2, 8, 512);
        Path trackDir = tempDir.resolve("eight");
        RecordingSession session = new RecordingSession(eightBit, trackDir);

        assertThatThrownBy(session::start)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bitDepth");
        assertThat(session.isActive()).isFalse();
        assertThat(session.getSegments()).isEmpty();
        assertThat(trackDir.resolve("segment-000.wav.part")).doesNotExist();
    }

    @Test
    void shouldRejectDoubleStart() {
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        session.start();

        assertThatThrownBy(session::start)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldPauseAndResumeSession() {
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        session.start();

        session.pause();
        assertThat(session.isPaused()).isTrue();

        session.resume();
        assertThat(session.isPaused()).isFalse();
        assertThat(session.isActive()).isTrue();
    }

    @Test
    void shouldStopSession() {
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        session.start();

        session.stop();

        assertThat(session.isActive()).isFalse();
        assertThat(session.isPaused()).isFalse();
    }

    @Test
    void stopSealsWithTheExactFrameCountThatWasFed() throws IOException {
        Path trackDir = tempDir.resolve("exact");
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, trackDir);
        session.start();
        int[] blocks = {512, 37, 512, 1, 300};
        int fed = 0;
        int rampStart = 0;
        for (int frames : blocks) {
            session.recordAudioData(ramp(frames, rampStart), frames);
            fed += frames;
            rampStart += frames;
        }

        session.stop();

        assertThat(session.getSegments()).hasSize(1);
        RecordingSegment sealed = session.getSegments().getFirst();
        assertThat(sealed.isInProgress()).isFalse();
        assertThat(sealed.sampleCount()).as("exact, not a wall-clock estimate").isEqualTo(fed);
        assertThat(sealed.sizeBytes()).isEqualTo((long) fed * BYTES_PER_FRAME);
        assertThat(sealed.filePath()).isEqualTo(trackDir.resolve("segment-000.wav")).exists();
        assertThat(sealed.streamingPath()).doesNotExist();
        assertThat(session.getTotalSamplesRecorded()).isEqualTo(fed);
        assertThat(session.getCapturedSampleCount()).isEqualTo(fed);
        assertThat(session.getCurrentSegment()).isNull();

        SegmentFile.Description description = SegmentFile.describe(sealed.filePath());
        assertThat(description.sealed()).isTrue();
        assertThat(description.frameCount()).isEqualTo(fed);
        float[][] onDisk = SegmentFile.readFrames(sealed.filePath());
        float[][] mirror = session.getCapturedAudio();
        assertThat(onDisk[0]).hasSize(fed);
        for (int i = 0; i < fed; i++) {
            assertThat(onDisk[0][i]).as("frame %d", i).isEqualTo(i / 32768f);
            assertThat(onDisk[1][i]).as("frame %d", i).isEqualTo(-i / 32768f);
            assertThat(mirror[0][i]).isEqualTo(i / 32767f);
        }
    }

    @Test
    void rotationSealsTheFullSegmentAndOpensTheNextStreamingPart() throws IOException {
        // 1200-byte cap at 4 bytes/frame = three 100-frame blocks: the segment
        // rotates as soon as it holds the cap, and never holds more.
        Path trackDir = tempDir.resolve("rotate");
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, trackDir,
                Duration.ofHours(1), 1200L);
        session.start();
        assertThat(session.getSegmentCount()).isEqualTo(1);

        session.recordAudioData(block(100, 0.1f, -0.1f), 100);
        session.recordAudioData(block(100, 0.2f, -0.2f), 100);
        assertThat(session.getSegmentCount()).as("800 bytes < cap").isEqualTo(1);
        session.recordAudioData(block(100, 0.3f, -0.3f), 100); // 1200 >= 1200 → rotate

        assertThat(session.getSegmentCount()).isEqualTo(2);
        RecordingSegment first = session.getSegments().get(0);
        RecordingSegment second = session.getSegments().get(1);
        assertThat(first.isInProgress()).isFalse();
        assertThat(first.sampleCount()).isEqualTo(300);
        assertThat(first.sizeBytes()).isEqualTo(1200);
        assertThat(first.filePath()).isEqualTo(trackDir.resolve("segment-000.wav")).exists();
        assertThat(first.streamingPath()).doesNotExist();
        assertThat(second.isInProgress()).isTrue();
        assertThat(second.index()).isEqualTo(1);
        assertThat(second.filePath()).isEqualTo(trackDir.resolve("segment-001.wav")).doesNotExist();
        assertThat(second.streamingPath()).isEqualTo(trackDir.resolve("segment-001.wav.part")).exists();
        assertThat(session.getCurrentSegment()).isEqualTo(second);
        assertThat(SegmentFile.describe(first.filePath()).frameCount()).isEqualTo(300);
        assertThat(SegmentFile.describe(second.streamingPath()).frameCount()).isZero();

        // The mirror spans both segments; the next block lands in segment-001.
        session.recordAudioData(block(50, 0.4f, -0.4f), 50);
        assertThat(session.getCapturedSampleCount()).isEqualTo(350);
        assertThat(SegmentFile.describe(second.streamingPath()).frameCount()).isEqualTo(50);

        session.stop();

        assertThat(session.getSegments()).allSatisfy(segment -> {
            assertThat(segment.isInProgress()).isFalse();
            assertThat(segment.filePath()).exists();
        });
        assertThat(session.getSegments().get(1).sampleCount()).isEqualTo(50);
        assertThat(session.getTotalSamplesRecorded()).isEqualTo(350);
    }

    @Test
    void rotationByDurationUsesExactFrameCountsNotWallClock() {
        // 10 ms at 44.1 kHz = 441 frames per segment.
        Path trackDir = tempDir.resolve("duration");
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, trackDir,
                Duration.ofMillis(10), DEFAULT_MAX_SEGMENT_BYTES);
        session.start();

        session.recordAudioData(block(440, 0.1f, 0.1f), 440);
        assertThat(session.getSegmentCount()).as("440 frames < 441").isEqualTo(1);
        session.recordAudioData(block(1, 0.1f, 0.1f), 1);
        assertThat(session.getSegmentCount()).as("441 frames >= 10 ms").isEqualTo(2);
        assertThat(session.getSegments().getFirst().sampleCount()).isEqualTo(441);
    }

    @Test
    void aCapOfTwoAndAHalfBlocksYieldsSegmentsOfTwoBlocksNeverThree() throws IOException {
        // 1000-byte cap, 400-byte blocks: a third block would take the
        // segment to 1200 bytes, so the session rotates BEFORE appending it.
        Path trackDir = tempDir.resolve("cap");
        long cap = 1000L;
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, trackDir,
                Duration.ofHours(1), cap);
        session.start();

        for (int i = 0; i < 7; i++) {
            session.recordAudioData(ramp(100, i * 100), 100);
            for (RecordingSegment segment : session.getSegments()) {
                if (!segment.isInProgress()) {
                    assertThat(segment.sizeBytes()).as("sealed segment %d after block %d", segment.index(), i)
                            .isLessThanOrEqualTo(cap);
                }
            }
            assertThat(session.getCurrentWriter().dataBytes()).as("streaming segment after block %d", i)
                    .isLessThanOrEqualTo(cap);
        }
        session.stop();

        assertThat(session.getSegments()).extracting(RecordingSegment::sampleCount)
                .containsExactly(200L, 200L, 200L, 100L);
        assertThat(session.getSegments()).extracting(RecordingSegment::sizeBytes)
                .containsExactly(800L, 800L, 800L, 400L);
        assertThat(session.getTotalSamplesRecorded()).isEqualTo(700);
        // Nothing was lost or reordered by rotating early: the files, read
        // back in order, are the ramp.
        int frame = 0;
        for (RecordingSegment segment : session.getSegments()) {
            assertThat(Files.size(segment.filePath())).isLessThanOrEqualTo(SegmentWriter.DATA_OFFSET + cap);
            float[][] onDisk = SegmentFile.readFrames(segment.filePath());
            for (int i = 0; i < onDisk[0].length; i++, frame++) {
                assertThat(onDisk[0][i]).as("frame %d", frame).isEqualTo(frame / 32768f);
            }
        }
        assertThat(frame).isEqualTo(700);
    }

    @Test
    void aSingleBlockLargerThanTheCapStillLandsInOneSegment() {
        // The early rotation needs something to seal: a block that alone
        // exceeds the cap goes into the empty segment, which then rotates.
        Path trackDir = tempDir.resolve("oversize");
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, trackDir,
                Duration.ofHours(1), 800L);
        session.start();

        session.recordAudioData(block(250, 0.1f, 0.1f), 250); // 1000 bytes > 800

        assertThat(session.getSegments()).hasSize(2);
        assertThat(session.getSegments().getFirst().sampleCount()).isEqualTo(250);
        assertThat(session.getSegments().getFirst().isInProgress()).isFalse();
        assertThat(session.getCurrentSegment().index()).isEqualTo(1);
        assertThat(session.getCurrentWriter().frameCount()).isZero();
    }

    @Test
    void shouldRejectASegmentByteCapBeyondTheWritersLimit() {
        long limit = SegmentWriter.MAX_DATA_BYTES;
        assertThat(limit).isEqualTo(Integer.MAX_VALUE - 44L);

        assertThatThrownBy(() -> new RecordingSession(AudioFormat.CD_QUALITY, tempDir,
                Duration.ofMinutes(10), limit + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAX_DATA_BYTES")
                .hasMessageContaining(Long.toString(limit));
        assertThatThrownBy(() -> new RecordingSession(AudioFormat.CD_QUALITY, tempDir,
                Duration.ofMinutes(10), 5_000_000_000L, Duration.ofSeconds(5), System::nanoTime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAX_DATA_BYTES");

        RecordingSession atTheLimit = new RecordingSession(AudioFormat.CD_QUALITY, tempDir,
                Duration.ofMinutes(10), limit);
        assertThat(atTheLimit.getMaxSegmentBytes()).isEqualTo(limit);
    }

    @Test
    void stopDiscardsAnEmptyTailInsteadOfSealingAZeroFrameFile() {
        Path trackDir = tempDir.resolve("empty-tail");
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, trackDir,
                Duration.ofHours(1), 400L);
        session.start();
        session.recordAudioData(block(100, 0.5f, 0.5f), 100); // exactly the cap → rotate → empty tail
        assertThat(session.getSegmentCount()).isEqualTo(2);
        Path emptyPart = session.getCurrentSegment().streamingPath();
        assertThat(emptyPart).exists();

        session.stop();

        assertThat(session.getSegments()).hasSize(1);
        assertThat(session.getSegments().getFirst().sampleCount()).isEqualTo(100);
        assertThat(emptyPart).doesNotExist();
        assertThat(trackDir.resolve("segment-001.wav")).doesNotExist();
        assertThat(session.getNextSegmentIndex()).as("the freed index is reused").isEqualTo(1);
    }

    @Test
    void sessionWithNoAudioLeavesNoSegmentBehind() {
        Path trackDir = tempDir.resolve("silent");
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, trackDir);
        session.start();

        session.stop();

        assertThat(session.getSegments()).isEmpty();
        assertThat(trackDir.resolve("segment-000.wav")).doesNotExist();
        assertThat(trackDir.resolve("segment-000.wav.part")).doesNotExist();
    }

    @Test
    void everySegmentAlwaysHasItsFileOnDisk() {
        Path trackDir = tempDir.resolve("files");
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, trackDir,
                Duration.ofHours(1), 800L);
        session.start();
        // Right after start(), before any block: the one listed segment is on disk.
        assertThat(session.getSegments()).hasSize(1);
        for (RecordingSegment segment : session.getSegments()) {
            assertThat(segment.isInProgress()).isTrue();
            assertThat(segment.streamingPath()).as("segment %d at start", segment.index()).exists();
        }
        for (int i = 0; i < 7; i++) {
            session.recordAudioData(block(100, 0.1f, 0.1f), 100);
            for (RecordingSegment segment : session.getSegments()) {
                Path expected = segment.isInProgress() ? segment.streamingPath() : segment.filePath();
                assertThat(expected).as("segment %d after block %d", segment.index(), i).exists();
            }
        }
        session.stop();
        assertThat(session.getSegments()).hasSize(4)
                .allSatisfy(segment -> assertThat(segment.filePath()).exists());
    }

    @Test
    void shouldNotRecordWhenInactive() {
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);

        session.recordAudioData(block(1000, 0.5f, 0.5f), 1000);

        assertThat(session.getTotalSamplesRecorded()).isZero();
        assertThat(session.getCapturedSampleCount()).isZero();
        assertThat(session.getCapturedAudio()).isNull();
    }

    @Test
    void shouldNotRecordWhenPaused() {
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        session.start();
        session.pause();

        session.recordAudioData(block(1000, 0.5f, 0.5f), 1000);

        assertThat(session.getTotalSamplesRecorded()).isZero();
        assertThat(session.getCapturedSampleCount()).isZero();
        assertThat(session.getCurrentWriter().frameCount()).isZero();
    }

    @Test
    void shouldRecordAudioDataIntoBufferAndOntoDisk() {
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        session.start();

        session.recordAudioData(block(512, 0.5f, -0.5f), 512);

        assertThat(session.getCapturedSampleCount()).isEqualTo(512);
        assertThat(session.getTotalSamplesRecorded()).isEqualTo(512);
        assertThat(session.getCurrentWriter().frameCount()).isEqualTo(512);
        assertThat(session.getCurrentWriter().dataBytes()).isEqualTo(512L * BYTES_PER_FRAME);

        float[][] captured = session.getCapturedAudio();
        assertThat(captured).isNotNull();
        assertThat(captured).hasNumberOfRows(2);
        assertThat(captured[0]).hasSize(512);
        assertThat(captured[0][0]).isEqualTo(0.5f);
        assertThat(captured[1][0]).isEqualTo(-0.5f);
    }

    @Test
    void shouldAccumulateMultipleAudioDataBlocks() {
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        session.start();

        session.recordAudioData(block(256, 0.1f, 0.2f), 256);
        session.recordAudioData(block(256, 0.3f, 0.4f), 256);

        assertThat(session.getCapturedSampleCount()).isEqualTo(512);
        float[][] captured = session.getCapturedAudio();
        assertThat(captured).isNotNull();
        assertThat(captured[0]).hasSize(512);
        assertThat(captured[0][0]).isEqualTo(0.1f);
        assertThat(captured[0][256]).isEqualTo(0.3f);
        assertThat(captured[1][0]).isEqualTo(0.2f);
        assertThat(captured[1][256]).isEqualTo(0.4f);
    }

    @Test
    void narrowerRoutedBlocksLeaveTheRemainingChannelsSilentOnDiskAndInRam() throws IOException {
        AudioFormat quad = new AudioFormat(48_000.0, 4, 16, 256);
        Path trackDir = tempDir.resolve("quad");
        RecordingSession session = new RecordingSession(quad, trackDir);
        session.start();

        session.recordAudioData(block(64, 0.25f, -0.25f), 64);
        session.stop();

        float[][] mirror = session.getCapturedAudio();
        assertThat(mirror).hasNumberOfRows(4);
        assertThat(mirror[0]).containsOnly(0.25f);
        assertThat(mirror[2]).containsOnly(0f);
        float[][] disk = SegmentFile.readFrames(trackDir.resolve("segment-000.wav"));
        assertThat(disk).hasNumberOfRows(4);
        assertThat(disk[1]).containsOnly(-0.25f); // round(-0.25 * 32767) = -8192 → exactly -0.25
        assertThat(disk[3]).containsOnly(0f);
    }

    @Test
    void aFailedAppendLeavesMirrorAndCountsMatchingTheDisk() {
        Path trackDir = tempDir.resolve("fault");
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, trackDir);
        session.start();
        session.recordAudioData(block(100, 0.1f, 0.1f), 100);
        session.getCurrentWriter().failNextAppend();

        assertThatThrownBy(() -> session.recordAudioData(block(100, 0.2f, 0.2f), 100))
                .isInstanceOf(UncheckedIOException.class);

        assertThat(session.getCapturedSampleCount()).isEqualTo(100);
        assertThat(session.getTotalSamplesRecorded()).isEqualTo(100);
        assertThat(session.getCurrentWriter().frameCount()).isEqualTo(100);
        assertThat(session.isActive()).as("the session itself stays usable; the flush service decides").isTrue();
        session.recordAudioData(block(100, 0.3f, 0.3f), 100);
        assertThat(session.getCapturedAudio()[0][100]).isEqualTo(0.3f);
        session.stop();
        assertThat(session.getSegments().getFirst().sampleCount()).isEqualTo(200);
    }

    @Test
    void shouldNotRecordAudioDataWhenInactive() {
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);

        float[][] input = new float[2][512];
        session.recordAudioData(input, 512);

        assertThat(session.getCapturedSampleCount()).isZero();
        assertThat(session.getCapturedAudio()).isNull();
    }

    @Test
    void shouldNotRecordAudioDataWhenPaused() {
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        session.start();
        session.pause();

        float[][] input = new float[2][512];
        input[0][0] = 0.5f;
        session.recordAudioData(input, 512);

        assertThat(session.getCapturedSampleCount()).isZero();
    }

    @Test
    void shouldReturnNullCapturedAudioWhenNothingRecorded() {
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        session.start();

        assertThat(session.getCapturedAudio()).isNull();
        assertThat(session.getCapturedSampleCount()).isZero();
    }

    @Test
    void shouldGrowBufferBeyondInitialCapacity() {
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        session.start();

        // Record enough data to exceed the initial ~10 second buffer
        float[][] block = new float[2][44100];
        for (int i = 0; i < 12; i++) {
            session.recordAudioData(block, 44100);
        }

        assertThat(session.getCapturedSampleCount()).isEqualTo(44100 * 12);
        float[][] captured = session.getCapturedAudio();
        assertThat(captured).isNotNull();
        assertThat(captured[0]).hasSize(44100 * 12);
        assertThat(session.getCurrentWriter().frameCount()).isEqualTo(44100L * 12);
    }

    @Test
    void shouldNotifyListenersOnStart() {
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        List<String> events = new ArrayList<>();

        session.addListener(new TestRecordingListener(events));
        session.start();

        assertThat(events).contains("started", "segment:0");
    }

    @Test
    void shouldNotifyListenersOnRotation() {
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir,
                Duration.ofHours(1), 400L);
        List<String> events = new ArrayList<>();
        session.addListener(new TestRecordingListener(events));
        session.start();

        session.recordAudioData(block(100, 0.1f, 0.1f), 100);

        assertThat(events).containsExactly("segment:0", "started", "segment:1");
    }

    @Test
    void shouldNotifyListenersOnPauseAndResume() {
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        List<String> events = new ArrayList<>();
        session.addListener(new TestRecordingListener(events));

        session.start();
        session.pause();
        session.resume();

        assertThat(events).contains("paused", "resumed");
    }

    @Test
    void shouldNotifyListenersOnStop() {
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        List<String> events = new ArrayList<>();
        session.addListener(new TestRecordingListener(events));

        session.start();
        session.stop();

        assertThat(events).contains("stopped");
    }

    @Test
    void shouldReturnFormat() {
        RecordingSession session = new RecordingSession(AudioFormat.STUDIO_QUALITY, tempDir);

        assertThat(session.getFormat()).isEqualTo(AudioFormat.STUDIO_QUALITY);
    }

    @Test
    void shouldReturnDefaultSegmentLimits() {
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);

        assertThat(session.getMaxSegmentDuration()).isEqualTo(Duration.ofMinutes(30));
        assertThat(session.getMaxSegmentBytes()).isEqualTo(500L * 1024 * 1024);
        assertThat(session.getForceCadence()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void shouldReturnCustomSegmentLimits() {
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir,
                Duration.ofMinutes(10), 100_000_000L);

        assertThat(session.getMaxSegmentDuration()).isEqualTo(Duration.ofMinutes(10));
        assertThat(session.getMaxSegmentBytes()).isEqualTo(100_000_000L);
    }

    @Test
    void customForceCadenceAndClockReachTheWriter() {
        AtomicLong clock = new AtomicLong(0);
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir.resolve("cadence"),
                Duration.ofMinutes(10), DEFAULT_MAX_SEGMENT_BYTES, Duration.ofSeconds(1), clock::get);
        session.start();

        assertThat(session.getForceCadence()).isEqualTo(Duration.ofSeconds(1));
        assertThat(session.getCurrentWriter().forceCadence()).isEqualTo(Duration.ofSeconds(1));
        session.recordAudioData(block(10, 0.1f, 0.1f), 10);
        assertThat(session.getCurrentWriter().forceCount()).isZero();
        clock.set(1_000_000_000L);
        session.recordAudioData(block(10, 0.1f, 0.1f), 10);
        assertThat(session.getCurrentWriter().forceCount()).isEqualTo(1);
    }

    @Test
    void shouldRejectNonPositiveMaxSegmentBytes() {
        assertThatThrownBy(() -> new RecordingSession(AudioFormat.CD_QUALITY, tempDir,
                Duration.ofMinutes(10), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectZeroMaxSegmentDuration() {
        assertThatThrownBy(() -> new RecordingSession(AudioFormat.CD_QUALITY, tempDir,
                Duration.ZERO, DEFAULT_MAX_SEGMENT_BYTES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSegmentDuration");
    }

    @Test
    void shouldRejectNegativeMaxSegmentDuration() {
        assertThatThrownBy(() -> new RecordingSession(AudioFormat.CD_QUALITY, tempDir,
                Duration.ofMinutes(-1), DEFAULT_MAX_SEGMENT_BYTES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSegmentDuration");
    }

    @Test
    void shouldComputeTotalDuration() {
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        session.start();

        session.recordAudioData(block(44100, 0.1f, 0.1f), 44100);

        assertThat(session.getTotalDuration()).isEqualTo(Duration.ofMillis(1000));
    }

    @Test
    void shouldRemoveListener() {
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        List<String> events = new ArrayList<>();
        TestRecordingListener listener = new TestRecordingListener(events);

        session.addListener(listener);
        session.removeListener(listener);
        session.start();

        assertThat(events).isEmpty();
    }

    @Test
    void shouldReturnUnmodifiableSegments() {
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, tempDir);
        session.start();

        assertThatThrownBy(() -> session.getSegments().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void laterLaneContinuesTheTrackDirectoryNumbering() {
        Path trackDir = tempDir.resolve("lanes");
        RecordingSession lane0 = new RecordingSession(AudioFormat.CD_QUALITY, trackDir,
                Duration.ofHours(1), 800L);
        lane0.start();
        lane0.recordAudioData(block(250, 0.1f, 0.1f), 250); // 1000 bytes ≥ 800 → segment-000 sealed
        lane0.recordAudioData(block(10, 0.1f, 0.1f), 10);
        lane0.stop();
        assertThat(lane0.getSegments()).extracting(RecordingSegment::index).containsExactly(0, 1);

        RecordingSession lane1 = new RecordingSession(AudioFormat.CD_QUALITY, trackDir);
        lane1.setFirstSegmentIndex(lane0.getNextSegmentIndex());
        lane1.start();

        assertThat(lane1.getCurrentSegment().index()).isEqualTo(2);
        assertThat(trackDir.resolve("segment-002.wav.part")).exists();
        assertThatThrownBy(() -> lane1.setFirstSegmentIndex(5)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void abandonForCrashSimulationLeavesThePartAndDeactivates() throws IOException {
        Path trackDir = tempDir.resolve("crash");
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, trackDir);
        session.start();
        session.recordAudioData(block(300, 0.1f, 0.1f), 300);

        session.abandonForCrashSimulation();

        assertThat(session.isActive()).isFalse();
        Path part = trackDir.resolve("segment-000.wav.part");
        assertThat(part).exists();
        assertThat(trackDir.resolve("segment-000.wav")).doesNotExist();
        assertThat(SegmentFile.describe(part).frameCount()).isEqualTo(300);
        assertThat(session.getCurrentWriter().isStreaming()).isFalse();
        session.stop(); // no-op: already inactive
        assertThat(part).exists();
    }

    @Test
    void discardAllFilesRemovesEverythingTheSessionCreated() {
        Path trackDir = tempDir.resolve("rollback");
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, trackDir,
                Duration.ofHours(1), 800L);
        session.start();
        session.recordAudioData(block(250, 0.1f, 0.1f), 250); // seals segment-000, opens 001
        assertThat(trackDir.resolve("segment-000.wav")).exists();
        assertThat(trackDir.resolve("segment-001.wav.part")).exists();

        session.discardAllFiles();

        assertThat(session.isActive()).isFalse();
        assertThat(session.getSegments()).isEmpty();
        assertThat(trackDir).doesNotExist();
        session.discardAllFiles(); // idempotent
    }

    @Test
    void segmentObserverSeesOpenSealAndDiscard() throws IOException {
        Path trackDir = tempDir.resolve("observer");
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, trackDir,
                Duration.ofHours(1), 400L);
        List<String> events = new ArrayList<>();
        session.setSegmentObserver(new RecordingSession.SegmentObserver() {
            @Override
            public void onSegmentOpened(RecordingSegment segment) {
                events.add("open:" + segment.index());
            }

            @Override
            public void onSegmentSealed(RecordingSegment segment) {
                events.add("seal:" + segment.index() + "/" + segment.sampleCount());
                assertThat(segment.filePath()).exists();
            }

            @Override
            public void onSegmentDiscarded(RecordingSegment segment) {
                events.add("discard:" + segment.index());
                assertThat(segment.streamingPath()).doesNotExist();
            }
        });
        session.start();
        session.recordAudioData(block(100, 0.1f, 0.1f), 100); // exactly the cap: seal 0, open 1
        session.stop();                                        // empty tail 1 discarded

        assertThat(events).containsExactly("open:0", "seal:0/100", "open:1", "discard:1");
        try (var files = Files.list(trackDir)) {
            assertThat(files.toList()).containsExactly(trackDir.resolve("segment-000.wav"));
        }
    }

    private static class TestRecordingListener implements RecordingListener {
        private final List<String> events;

        TestRecordingListener(List<String> events) {
            this.events = events;
        }

        @Override
        public void onRecordingStarted() {
            events.add("started");
        }

        @Override
        public void onRecordingPaused() {
            events.add("paused");
        }

        @Override
        public void onRecordingResumed() {
            events.add("resumed");
        }

        @Override
        public void onRecordingStopped() {
            events.add("stopped");
        }

        @Override
        public void onNewSegmentCreated(int segmentIndex) {
            events.add("segment:" + segmentIndex);
        }
    }
}
