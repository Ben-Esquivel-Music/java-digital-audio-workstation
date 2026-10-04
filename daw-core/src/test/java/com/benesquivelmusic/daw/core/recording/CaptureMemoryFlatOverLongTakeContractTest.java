package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.startRecording;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.stopRecording;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.BYTES_PER_FRAME_MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.advanceOneBlock;
import static com.benesquivelmusic.daw.core.recording.RampCaptureTestSupport.rampBlock;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The memory the capture path holds does not depend on how long the take is
 * (Recording Reliability book §4.5): over hours of audio the session keeps
 * the one staging block it allocated at its start, and nothing in the
 * capture objects — the session, its writer, the track capture and its peak
 * mirror, the ring, the flush service — holds an array or a collection that
 * grew with the frames or the blocks that went through.
 *
 * <p>The assertion is structural, not a heap measurement: a reflective walk
 * over the instance fields of the capture objects (every object of the
 * {@code core.recording} package reachable from the roots) records the
 * length and the identity of every array, the capacity of every buffer and
 * the size of every collection and map; the walk after the first block and
 * the walk after the last must agree. A session that kept the take in
 * memory, as the capture path did before, shows up as an array whose length
 * changed.</p>
 *
 * <p>Nothing large reaches the disk: the segments are opened through a
 * channel that passes the 44 header bytes to the real file — so the seal's
 * rename works — and discards every data write. No wait here is unbounded:
 * the fences are bounded by {@link CaptureFlushService#DEFAULT_AWAIT_TIMEOUT},
 * the start and the stop by {@link PipelineLifecycleTestSupport#LIFECYCLE_GUARD}.</p>
 */
@ExtendWith(CaptureFlushThreadLeakGuard.class)
class CaptureMemoryFlatOverLongTakeContractTest {

    private static final String CAPTURE_PACKAGE = RecordingSession.class.getPackageName();

    @TempDir
    Path tempDir;

    @Test
    void threeHoursThroughASessionLeaveItHoldingTheStagingBlockItStartedWith() throws IOException {
        long frames = 3L * 3600 * 48_000;
        long blocks = frames / BLOCK_FRAMES;
        DiscardedData discarded = new DiscardedData();
        // No rotation in this take: the whole three hours stay under the
        // writer's own segment limit, so the session's one writer is the
        // same object first and last.
        RecordingSession session = new RecordingSession(MONO_16, tempDir.resolve("track"),
                Duration.ofDays(1), SegmentWriter.MAX_DATA_BYTES);
        assertThat(frames * BYTES_PER_FRAME_MONO_16).as("fixture: three hours fit one segment")
                .isLessThan(SegmentWriter.MAX_DATA_BYTES);
        session.setChannelOpener(discarded::open);
        session.start();
        float[][] block = rampBlock(0);

        session.recordAudioData(block, BLOCK_FRAMES);
        Footprint afterTheFirstBlock = Footprint.of(session);
        for (long b = 1; b < blocks; b++) {
            session.recordAudioData(block, BLOCK_FRAMES);
        }
        Footprint afterTheLastBlock = Footprint.of(session);

        assertThat(session.getTotalSamplesRecorded()).as("fixture: three hours went through").isEqualTo(frames);
        assertThat(discarded.bytes()).as("fixture: every frame reached the channel")
                .isEqualTo(frames * BYTES_PER_FRAME_MONO_16);
        assertThat(session.getSegmentCount()).as("fixture: no rotation").isEqualTo(1);

        assertThat(afterTheFirstBlock.stagingBlocks(MONO_16.channels()))
                .as("non-vacuity: the walk found the session's staging block").hasSize(1);
        assertThat(afterTheLastBlock.sizes()).as("no array, buffer or collection of the session changed size")
                .isEqualTo(afterTheFirstBlock.sizes());
        afterTheLastBlock.assertSameArraysAs(afterTheFirstBlock, path -> true);
        session.stop();
    }

    @Test
    void anHourThroughThePipelineWithRotationGrowsNothingButOneEntryPerSegmentFile() {
        int minutes = 60;
        int segmentMinutes = 10;
        long frames = (long) minutes * 60 * 48_000;
        long blocks = frames / BLOCK_FRAMES;
        int fenceEvery = 128;
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        Track track = RampCaptureTestSupport.armedMonoTrack("Vocal");
        DiscardedData discarded = new DiscardedData();
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, tempDir, List.of(track));
        pipeline.setSegmentLimits(Duration.ofMinutes(segmentMinutes), RecordingSession.DEFAULT_MAX_SEGMENT_BYTES);
        pipeline.setChannelOpener(discarded::open);
        pipeline.setRingSlots(2 * fenceEvery);
        // The sink keeps nothing of what it is handed but two numbers.
        AtomicLong snapshots = new AtomicLong();
        AtomicLong mostBuckets = new AtomicLong();
        pipeline.setPeakSnapshotSink(snapshot -> {
            snapshots.incrementAndGet();
            mostBuckets.accumulateAndGet(snapshot.bucketCount(), Math::max);
        });
        startRecording(pipeline);
        CaptureFlushService service = pipeline.getCaptureFlushService();
        float[][] input = rampBlock(0);
        float[][] output = new float[1][BLOCK_FRAMES];

        engine.processBlock(input, output, BLOCK_FRAMES);
        advanceOneBlock(transport);
        pipeline.awaitFlushed();
        Footprint afterTheFirstBlock = footprintOfTheCapture(pipeline, service);
        for (long b = 1; b < blocks; b++) {
            engine.processBlock(input, output, BLOCK_FRAMES);
            advanceOneBlock(transport);
            if (b % fenceEvery == 0) {
                pipeline.awaitFlushed();
            }
        }
        pipeline.awaitFlushed();
        Footprint afterTheLastBlock = footprintOfTheCapture(pipeline, service);

        RecordingSession session = pipeline.getSession(track);
        assertThat(pipeline.getOverflowCount()).as("fixture: no block was dropped").isZero();
        assertThat(session.getTotalSamplesRecorded()).as("fixture: the hour went through").isEqualTo(frames);
        assertThat(discarded.bytes()).isEqualTo(frames * BYTES_PER_FRAME_MONO_16);
        int segments = session.getSegmentCount();
        assertThat(segments).as("fixture: the take rotated").isGreaterThanOrEqualTo(minutes / segmentMinutes);

        assertThat(afterTheFirstBlock.stagingBlocks(MONO_16.channels()))
                .as("non-vacuity: the walk found the session's staging block").hasSize(1);
        // The peak mirror is one of the capture objects: its bucket array is
        // in both walks, so the assertions below hold it to the size and the
        // identity it had after the first block, through every merge.
        CapturePeakMirror mirror = service.captures().getFirst().peaks();
        assertThat(afterTheFirstBlock.arraysOfLength(2 * CapturePeakMirror.BUCKETS))
                .as("non-vacuity: the first walk found one bucket array").hasSize(1);
        assertThat(afterTheLastBlock.arraysOfLength(2 * CapturePeakMirror.BUCKETS))
                .as("non-vacuity: the bucket array the last walk found is the mirror's")
                .hasSize(1).first().isSameAs(mirror.backingArray());
        assertThat(mirror.totalFrames()).as("fixture: the mirror was fed the whole hour").isEqualTo(frames);
        assertThat(mirror.framesPerBucket()).as("fixture: the mirror merged its buckets many times over the hour")
                .isGreaterThanOrEqualTo(256L * CapturePeakMirror.INITIAL_FRAMES_PER_BUCKET);
        assertThat(mirror.bucketCount()).isLessThanOrEqualTo(CapturePeakMirror.BUCKETS);
        assertThat(snapshots.get()).as("fixture: snapshots were handed over while the take ran").isPositive();
        assertThat(mostBuckets.get()).as("no snapshot was larger than the mirror")
                .isBetween(1L, (long) CapturePeakSnapshot.MAX_BUCKETS);
        assertThat(afterTheLastBlock.arrayAndBufferSizes())
                .as("every array and buffer of the capture objects has the size it had after the first block")
                .isEqualTo(afterTheFirstBlock.arrayAndBufferSizes());
        // A rotation gives the session a new writer; everything else is the same object.
        afterTheLastBlock.assertSameArraysAs(afterTheFirstBlock, path -> !path.contains(".writer."));
        afterTheLastBlock.collectionSizes().forEach((path, size) -> {
            long before = afterTheFirstBlock.collectionSizes().getOrDefault(path, 0L);
            assertThat(size - before)
                    .as("%s grew by %d over %d blocks; a capture collection gains at most one entry per"
                            + " segment file (%d files)", path, size - before, blocks, segments)
                    .isBetween(0L, (long) segments);
        });

        List<AudioClip> clips = stopRecording(pipeline);
        assertThat(clips).singleElement().satisfies(clip -> {
            assertThat(clip.getAudioData()).as("the clip is built without the take's audio").isNull();
            assertThat(clip.getSourceRateMetadata().framesPerChannel()).isEqualTo(frames);
            assertThat(clip.getSourceSegmentPaths()).as("one path per sealed segment")
                    .hasSize(session.getSegmentCount())
                    .hasSizeGreaterThanOrEqualTo(minutes / segmentMinutes);
        });
    }

    /**
     * Loop-record replaces the lane's session at every wrap and keeps a
     * pre-opened standby beside it: three hundred laps must leave the capture
     * holding no more sessions — no more staging blocks, arrays or buffers —
     * than it held once the first laps had gone round, and collections that
     * gained at most one entry per lap.
     */
    @Test
    void threeHundredLoopLapsLeaveTheCaptureHoldingNoMoreSessionsThanAfterTheFirstLaps() {
        int laps = 300;
        int blocksPerLap = 4;
        int settledLaps = 3;
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        Track track = RampCaptureTestSupport.armedMonoTrack("Vocal");
        DiscardedData discarded = new DiscardedData();
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, tempDir, List.of(track));
        pipeline.setChannelOpener(discarded::open);
        double samplesPerBeat = MONO_16.sampleRate() * 60.0 / transport.getTempo();
        transport.setLoopRegion(0.0, blocksPerLap * BLOCK_FRAMES / samplesPerBeat);
        transport.setLoopEnabled(true);
        pipeline.setLoopRecord(true);
        startRecording(pipeline);
        CaptureFlushService service = pipeline.getCaptureFlushService();
        float[][] input = rampBlock(0);
        float[][] output = new float[1][BLOCK_FRAMES];

        Footprint afterTheFirstLaps = null;
        for (int block = 0; block < laps * blocksPerLap; block++) {
            engine.processBlock(input, output, BLOCK_FRAMES);
            advanceOneBlock(transport);
            pipeline.awaitFlushed();
            if (block == settledLaps * blocksPerLap) {
                // One block into lane 3: the sealed lane's session, the lane's and the standby's.
                afterTheFirstLaps = footprintOfTheCapture(pipeline, service);
            }
        }
        Footprint afterTheLastLap = footprintOfTheCapture(pipeline, service);

        TrackCapture capture = service.captures().getFirst();
        assertThat(pipeline.getOverflowCount()).as("fixture: no block was dropped").isZero();
        assertThat(capture.lane()).as("fixture: every lap but the last wrapped").isEqualTo(laps - 1);
        assertThat(capture.takeGroup().size()).isEqualTo(laps - 1);
        assertThat(capture.hasStandby()).as("fixture: a standby is held").isTrue();

        int stagingBlocks = afterTheFirstLaps.stagingBlocks(MONO_16.channels()).size();
        assertThat(stagingBlocks)
                .as("non-vacuity: the walk found the staging blocks of the sessions the capture holds — the lane's, "
                        + "its standby's and the last sealed lane's")
                .isBetween(2, 3);
        assertThat(afterTheLastLap.stagingBlocks(MONO_16.channels()))
                .as("after %d laps the capture holds the staging blocks of as many sessions as after %d: a lane's "
                        + "session, sealed or surrendered, is not kept", laps, settledLaps)
                .hasSize(stagingBlocks);
        assertThat(afterTheLastLap.arrayCount()).as("the capture objects hold as many arrays as after %d laps",
                settledLaps).isEqualTo(afterTheFirstLaps.arrayCount());
        assertThat(afterTheLastLap.totalArrayAndBufferSize())
                .as("and their arrays and buffers add up to the same size")
                .isEqualTo(afterTheFirstLaps.totalArrayAndBufferSize());
        Footprint settled = afterTheFirstLaps;
        afterTheLastLap.collectionSizes().forEach((path, size) -> {
            long before = settled.collectionSizes().getOrDefault(path, 0L);
            assertThat(size - before)
                    .as("%s grew by %d over %d laps; a capture collection gains at most one entry per lap",
                            path, size - before, laps)
                    .isBetween(0L, (long) laps);
        });

        List<AudioClip> clips = stopRecording(pipeline);
        assertThat(clips).hasSize(1);
        assertThat(pipeline.getTakeGroups().get(track).size()).isEqualTo(laps);
    }

    private static Footprint footprintOfTheCapture(RecordingPipeline pipeline, CaptureFlushService service) {
        List<Object> roots = new ArrayList<>(service.captures());
        roots.add(pipeline.getCaptureRing());
        roots.add(service);
        return Footprint.of(roots.toArray());
    }

    /**
     * The sizes and identities of everything of variable size the given
     * capture objects hold, keyed by the field path that reaches it.
     */
    private static final class Footprint {

        private final Map<String, Long> arraySizes = new LinkedHashMap<>();
        private final Map<String, Long> bufferSizes = new LinkedHashMap<>();
        private final Map<String, Long> collectionSizes = new LinkedHashMap<>();
        private final Map<String, Object> arrays = new LinkedHashMap<>();
        private final Map<Object, String> visited = new IdentityHashMap<>();

        static Footprint of(Object... roots) {
            Footprint footprint = new Footprint();
            for (int i = 0; i < roots.length; i++) {
                footprint.walk(roots[i].getClass().getSimpleName() + "#" + i, roots[i]);
            }
            return footprint;
        }

        Map<String, Long> sizes() {
            Map<String, Long> all = new LinkedHashMap<>(arrayAndBufferSizes());
            all.putAll(collectionSizes);
            return all;
        }

        Map<String, Long> arrayAndBufferSizes() {
            Map<String, Long> all = new LinkedHashMap<>(arraySizes);
            all.putAll(bufferSizes);
            return all;
        }

        Map<String, Long> collectionSizes() {
            return collectionSizes;
        }

        /** How many arrays the walk reached. */
        int arrayCount() {
            return arrays.size();
        }

        /** The lengths of every array and the capacities of every buffer the walk reached, added up. */
        long totalArrayAndBufferSize() {
            return arrayAndBufferSizes().values().stream().mapToLong(Long::longValue).sum();
        }

        /** Paths of the {@code float[channels][STAGING_FRAMES]} blocks the walk reached. */
        List<String> stagingBlocks(int channels) {
            List<String> found = new ArrayList<>();
            arrays.forEach((path, array) -> {
                if (array instanceof float[][] rows && rows.length == channels
                        && java.util.Arrays.stream(rows)
                                .allMatch(row -> row.length == RecordingSession.STAGING_FRAMES)) {
                    found.add(path);
                }
            });
            return found;
        }

        /** The primitive {@code float[]} arrays of exactly {@code length} elements the walk reached. */
        List<Object> arraysOfLength(int length) {
            List<Object> found = new ArrayList<>();
            arrays.values().forEach(array -> {
                if (array instanceof float[] floats && floats.length == length) {
                    found.add(array);
                }
            });
            return found;
        }

        /** Every array {@code earlier} reached at a path {@code compared} accepts is the same object here. */
        void assertSameArraysAs(Footprint earlier, java.util.function.Predicate<String> compared) {
            assertThat(arrays.keySet()).as("the same arrays are reachable").isEqualTo(earlier.arrays.keySet());
            earlier.arrays.forEach((path, array) -> {
                if (compared.test(path)) {
                    assertThat(arrays.get(path)).as("%s is the array it was after the first block", path)
                            .isSameAs(array);
                }
            });
        }

        private void walk(String path, Object value) {
            if (value == null || visited.putIfAbsent(value, path) != null) {
                return;
            }
            Class<?> type = value.getClass();
            if (type.isArray()) {
                int length = Array.getLength(value);
                arraySizes.put(path, (long) length);
                arrays.put(path, value);
                if (!type.getComponentType().isPrimitive()) {
                    for (int i = 0; i < length; i++) {
                        walk(path + "[" + i + "]", Array.get(value, i));
                    }
                }
            } else if (value instanceof ByteBuffer buffer) {
                bufferSizes.put(path, (long) buffer.capacity());
            } else if (value instanceof Collection<?> collection) {
                collectionSizes.put(path, (long) collection.size());
                int i = 0;
                for (Object element : collection) {
                    walk(path + "{" + i++ + "}", element);
                }
            } else if (value instanceof Map<?, ?> map) {
                collectionSizes.put(path, (long) map.size());
            } else if (isCaptureObject(type)) {
                for (Class<?> c = type; c != null && isCaptureObject(c); c = c.getSuperclass()) {
                    for (Field field : c.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers())) {
                            continue;
                        }
                        field.setAccessible(true);
                        try {
                            walk(path + "." + field.getName(), field.get(value));
                        } catch (IllegalAccessException e) {
                            throw new AssertionError("cannot read " + field, e);
                        }
                    }
                }
            }
        }

        /** A class of the recording package that is not a lambda, an enum or a test double. */
        private static boolean isCaptureObject(Class<?> type) {
            return CAPTURE_PACKAGE.equals(type.getPackageName())
                    && !type.isHidden() && !type.isSynthetic() && !type.isEnum()
                    && type != DiscardedData.class && type != DiscardedData.Channel.class;
        }
    }

    /**
     * Opens each segment's real file and hands the writer a channel that
     * passes the header bytes (positions below the data offset) through to
     * it and counts and discards everything else.
     */
    private static final class DiscardedData {

        private final AtomicLong bytes = new AtomicLong();

        FileChannel open(Path path) throws IOException {
            return new Channel(SegmentWriter.CREATE_NEW_CHANNEL.open(path));
        }

        long bytes() {
            return bytes.get();
        }

        private final class Channel extends FileChannel {

            private final FileChannel file;

            Channel(FileChannel file) {
                this.file = file;
            }

            @Override
            public int write(ByteBuffer src, long position) throws IOException {
                if (position < SegmentWriter.DATA_OFFSET) {
                    return file.write(src, position);
                }
                int discardedNow = src.remaining();
                src.position(src.limit());
                bytes.addAndGet(discardedNow);
                return discardedNow;
            }

            @Override
            public int write(ByteBuffer src) throws IOException {
                return file.write(src);
            }

            @Override
            public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
                return file.write(srcs, offset, length);
            }

            @Override
            public int read(ByteBuffer dst) throws IOException {
                return file.read(dst);
            }

            @Override
            public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
                return file.read(dsts, offset, length);
            }

            @Override
            public int read(ByteBuffer dst, long position) throws IOException {
                return file.read(dst, position);
            }

            @Override
            public long position() throws IOException {
                return file.position();
            }

            @Override
            public FileChannel position(long newPosition) throws IOException {
                file.position(newPosition);
                return this;
            }

            @Override
            public long size() throws IOException {
                return file.size();
            }

            @Override
            public FileChannel truncate(long size) throws IOException {
                file.truncate(size);
                return this;
            }

            @Override
            public void force(boolean metaData) throws IOException {
                file.force(metaData);
            }

            @Override
            public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
                return file.transferTo(position, count, target);
            }

            @Override
            public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
                return file.transferFrom(src, position, count);
            }

            @Override
            public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
                return file.map(mode, position, size);
            }

            @Override
            public FileLock lock(long position, long size, boolean shared) throws IOException {
                return file.lock(position, size, shared);
            }

            @Override
            public FileLock tryLock(long position, long size, boolean shared) throws IOException {
                return file.tryLock(position, size, shared);
            }

            @Override
            protected void implCloseChannel() throws IOException {
                file.close();
            }
        }
    }
}
