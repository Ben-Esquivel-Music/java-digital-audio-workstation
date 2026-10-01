package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.track.Track;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-armed-track capture state owned by the {@code capture-flush} thread
 * (Recording Reliability book §3.1, §4.3; story 323): the routing snapshot
 * taken at record start, the routed scratch block, the current lane's
 * {@link RecordingSession}, the lane index, every sealed segment across
 * lanes, and the loop-take stack built lap by lap.
 *
 * <p><strong>Threads.</strong> Constructed on the caller thread by
 * {@code RecordingPipeline.start()} (which also reads the {@link Track} once
 * to take the snapshot — the flush thread never touches a {@code Track}).
 * {@link #startLane()} runs on the caller thread for lane 0 (inside
 * {@code CaptureFlushService.start()}, before the flush thread is started);
 * {@link #finalizeLane(boolean, boolean)} — which starts every later lane —
 * and the scratch/session accessors used while routing are flush-thread
 * only. {@link #setSegmentEvents}, {@link #discardAllFiles()} and
 * {@link #abandonWithoutSeal()} run on the caller thread, before the
 * flush thread is started or once it has terminated
 * ({@code CaptureFlushService.isTerminated()}; a join that runs out first
 * leaves them uncalled). The results ({@link #takeGroup()},
 * {@link #sealedSegments()}, {@link #session()}) are volatile or
 * copy-on-write so the pipeline can read them on the caller thread once the
 * flush thread has terminated.</p>
 */
final class TrackCapture {

    private static final Logger LOG = Logger.getLogger(TrackCapture.class.getName());

    /** Creates the per-lane session for a track (the pipeline's default and its test seam). */
    interface SessionFactory {
        RecordingSession create(Track track, Path trackDirectory);
    }

    /** Segment lifecycle events tagged with the capture and lane (manifest bookkeeping). */
    interface SegmentEvents {
        void onSegmentOpened(TrackCapture capture, int lane, RecordingSegment segment);

        void onSegmentSealed(TrackCapture capture, int lane, RecordingSegment segment);

        void onSegmentDiscarded(TrackCapture capture, int lane, RecordingSegment segment);
    }

    /**
     * A sealed segment and the loop lane it was captured in.
     *
     * @param lane    lane index (0 for a plain take)
     * @param segment the sealed segment (never in progress)
     */
    record SealedSegment(int lane, RecordingSegment segment) {
        SealedSegment {
            if (lane < 0) {
                throw new IllegalArgumentException("lane must not be negative: " + lane);
            }
            Objects.requireNonNull(segment, "segment must not be null");
            if (segment.isInProgress()) {
                throw new IllegalArgumentException("a SealedSegment must be sealed: " + segment);
            }
        }
    }

    private final Track track;
    private final String trackId;
    private final String trackName;
    private final InputRouting routing;
    private final int instrumentSource;
    private final float[][] routed;
    private final long compensationFrames;
    private final double compensatedStartBeat;
    private final double sampleRate;
    private final double tempoBpm;
    private final Path trackDirectory;
    private final SessionFactory sessionFactory;
    private final List<SealedSegment> sealedSegments = new CopyOnWriteArrayList<>();

    private volatile RecordingSession session;
    private volatile int lane;
    private volatile TakeGroup takeGroup = TakeGroup.empty();
    private SegmentEvents events;
    /** The session whose lane bookkeeping is done; flush thread only. */
    private RecordingSession finalizedSession;

    /**
     * Takes the snapshot and creates (but does not start) the lane-0 session.
     *
     * @param track                the armed track (read once, here)
     * @param routing              the track's input routing at record start
     * @param instrumentSource     ring source index of the track's graph-instrument
     *                             buffer, or {@code -1} for a physically routed track
     * @param routedChannels       rows of the routed scratch block
     * @param slotFrames           frames per ring slot (the scratch block length)
     * @param compensationFrames   driver round-trip compensation for this track
     * @param compensatedStartBeat the clip start beat after compensation
     * @param sampleRate           stream sample rate
     * @param tempoBpm             tempo at record start (lap clip durations)
     * @param trackDirectory       {@code <take>/<trackId>}
     * @param sessionFactory       creates each lane's session
     */
    TrackCapture(Track track, InputRouting routing, int instrumentSource, int routedChannels,
                 int slotFrames, long compensationFrames, double compensatedStartBeat,
                 double sampleRate, double tempoBpm, Path trackDirectory,
                 SessionFactory sessionFactory) {
        this.track = Objects.requireNonNull(track, "track must not be null");
        this.trackId = track.getId();
        this.trackName = track.getName();
        this.routing = Objects.requireNonNull(routing, "routing must not be null");
        if (instrumentSource == 0) {
            throw new IllegalArgumentException("source 0 is the device block; instrument sources start at 1");
        }
        this.instrumentSource = Math.max(-1, instrumentSource);
        if (routedChannels < 0 || slotFrames <= 0) {
            throw new IllegalArgumentException("routedChannels/slotFrames out of range: "
                    + routedChannels + "/" + slotFrames);
        }
        this.routed = new float[routedChannels][slotFrames];
        if (compensationFrames < 0) {
            throw new IllegalArgumentException("compensationFrames must not be negative: " + compensationFrames);
        }
        this.compensationFrames = compensationFrames;
        this.compensatedStartBeat = compensatedStartBeat;
        this.sampleRate = sampleRate;
        this.tempoBpm = tempoBpm;
        this.trackDirectory = Objects.requireNonNull(trackDirectory, "trackDirectory must not be null");
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory must not be null");
        this.session = newSession(0, 0);
    }

    private RecordingSession newSession(int laneIndex, int firstSegmentIndex) {
        RecordingSession created = Objects.requireNonNull(
                sessionFactory.create(track, trackDirectory), "session factory returned null");
        created.setFirstSegmentIndex(firstSegmentIndex);
        created.setCompensationFrames(compensationFrames);
        attachObserver(created, laneIndex);
        return created;
    }

    private void attachObserver(RecordingSession target, int laneIndex) {
        SegmentEvents sink = events;
        if (sink == null) {
            target.setSegmentObserver(null);
            return;
        }
        target.setSegmentObserver(new RecordingSession.SegmentObserver() {
            @Override
            public void onSegmentOpened(RecordingSegment segment) {
                sink.onSegmentOpened(TrackCapture.this, laneIndex, segment);
            }

            @Override
            public void onSegmentSealed(RecordingSegment segment) {
                sink.onSegmentSealed(TrackCapture.this, laneIndex, segment);
            }

            @Override
            public void onSegmentDiscarded(RecordingSegment segment) {
                sink.onSegmentDiscarded(TrackCapture.this, laneIndex, segment);
            }
        });
    }

    /** Installs the segment event sink on the current and every later lane's session. */
    void setSegmentEvents(SegmentEvents events) {
        this.events = events;
        attachObserver(session, lane);
    }

    /** Starts the current lane's session (creates its directory and first {@code .part}). */
    void startLane() {
        session.start();
    }

    /**
     * Seals the current lane: stops its session (exact-count seal), records
     * its sealed segments, optionally appends the lane as a {@link Take}
     * (loop-record) and optionally opens the next lane with the track
     * directory's segment numbering continued.
     *
     * <p>If the seal fails the take is still built from the RAM mirror and
     * whatever did seal, the next lane is <em>not</em> opened, and the
     * failure is rethrown after the bookkeeping so the caller can abort the
     * take with an honest manifest.</p>
     *
     * <p>Idempotent per lane: a lane is finalized once. A second call while
     * the same session is still current — the early seal that answers a
     * failure thrown by the first call — does nothing, so a lap is stacked
     * once and a sealed segment is listed once.</p>
     *
     * @param buildTake whether to append this lane to {@link #takeGroup()}
     *                  (skipped when the lane captured no frames)
     * @param openNext  whether to open lane + 1
     * @throws UncheckedIOException if the seal or the next lane's open fails
     * @throws RuntimeException     whatever else stopping the lane's session
     *                              threw (a listener), after the bookkeeping
     * @throws Error                an {@code Error} stopping the lane's session
     *                              threw, likewise after the bookkeeping
     */
    void finalizeLane(boolean buildTake, boolean openNext) {
        RecordingSession previous = session;
        if (previous == finalizedSession) {
            return;
        }
        // Marked before anything below can throw: whatever happens next,
        // this lane's segments and take are recorded by THIS call only.
        finalizedSession = previous;
        int previousLane = lane;
        Throwable failure = null;
        try {
            previous.stop();
        } catch (RuntimeException | Error e) {
            // A failed seal, or a listener that threw — an Error included:
            // either way the bookkeeping below must still run, because no
            // later call will.
            failure = e;
        }
        List<String> lanePaths = new ArrayList<>();
        for (RecordingSegment segment : previous.getSegments()) {
            if (!segment.isInProgress()) {
                sealedSegments.add(new SealedSegment(previousLane, segment));
                lanePaths.add(segment.filePath().toAbsolutePath().toString());
            }
        }
        if (buildTake) {
            long samples = previous.getTotalSamplesRecorded();
            if (samples > 0) {
                double durationSeconds = samples / sampleRate;
                double durationBeats = durationSeconds * (tempoBpm / 60.0);
                if (durationBeats <= 0) {
                    durationBeats = 0.01;
                }
                AudioClip clip = new AudioClip("Take — " + trackName, compensatedStartBeat,
                        durationBeats, lanePaths.isEmpty() ? null : lanePaths.getFirst());
                clip.setSourceSegmentPaths(lanePaths);
                float[][] capturedAudio = previous.getCapturedAudio();
                if (capturedAudio != null) {
                    clip.setAudioData(capturedAudio);
                }
                takeGroup = takeGroup.withTakeAppended(Take.of(clip));
            }
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (openNext) {
            int nextLane = previousLane + 1;
            RecordingSession next = newSession(nextLane, previous.getNextSegmentIndex());
            next.start();
            lane = nextLane;
            session = next;
        }
    }

    /**
     * Test seam: abandons the current lane's writer without sealing
     * ({@link RecordingSession#abandonWithoutSeal()}); a segment that was
     * streaming stays a {@code .part}.
     */
    void abandonWithoutSeal() {
        session.abandonWithoutSeal();
    }

    /**
     * Start-failure rollback: deletes every file this capture created —
     * the current lane's files and every sealed segment — and the track
     * directory if it is empty afterwards. Idempotent.
     */
    void discardAllFiles() {
        session.discardAllFiles();
        for (SealedSegment sealed : sealedSegments) {
            try {
                Files.deleteIfExists(sealed.segment().filePath());
                Files.deleteIfExists(sealed.segment().streamingPath());
            } catch (IOException e) {
                LOG.log(Level.WARNING, "could not delete " + sealed.segment().filePath(), e);
            }
        }
        sealedSegments.clear();
        takeGroup = TakeGroup.empty();
        try {
            Files.deleteIfExists(trackDirectory);
        } catch (DirectoryNotEmptyException notEmpty) {
            // Not ours to remove.
        } catch (IOException e) {
            LOG.log(Level.WARNING, "could not remove " + trackDirectory, e);
        }
    }

    /** Returns the armed track (caller-thread use only: {@code addClip}, {@code putTakeGroup}). */
    Track track() {
        return track;
    }

    String trackId() {
        return trackId;
    }

    String trackName() {
        return trackName;
    }

    /** Returns the routing snapshot taken at record start. */
    InputRouting routing() {
        return routing;
    }

    /** Returns the ring source index of the graph-instrument buffer, or {@code -1} for physical routing. */
    int instrumentSource() {
        return instrumentSource;
    }

    /** Returns whether this track records its graph instrument rather than a device input. */
    boolean isInstrument() {
        return instrumentSource >= 0;
    }

    /** Returns the routed scratch block {@code [routedChannels][slotFrames]} (flush thread only). */
    float[][] routed() {
        return routed;
    }

    long compensationFrames() {
        return compensationFrames;
    }

    double compensatedStartBeat() {
        return compensatedStartBeat;
    }

    Path trackDirectory() {
        return trackDirectory;
    }

    /** Returns the current lane's session (the last lane's, once finalized). */
    RecordingSession session() {
        return session;
    }

    /** Returns the current lane index. */
    int lane() {
        return lane;
    }

    /**
     * Returns whether the current lane's session still lists a segment that
     * did not seal: a seal that fails — at a rotation, or when the lane is
     * finalized — leaves its segment listed in progress, the {@code .part}
     * file in place for recovery. Only the current lane can hold one: a seal
     * that fails at a rotation or at a loop wrap ends the take early, and a
     * lane whose seal failed at a wrap stays the current lane. Flush thread.
     */
    boolean hasUnsealedSegment() {
        for (RecordingSegment segment : session.getSegments()) {
            if (segment.isInProgress()) {
                return true;
            }
        }
        return false;
    }

    /** Returns every sealed segment across lanes, in seal order (= lane, then index). */
    List<SealedSegment> sealedSegments() {
        return List.copyOf(sealedSegments);
    }

    /** Returns the absolute paths of every sealed segment across lanes, in manifest order. */
    List<String> sealedSegmentPaths() {
        List<String> paths = new ArrayList<>(sealedSegments.size());
        for (SealedSegment sealed : sealedSegments) {
            paths.add(sealed.segment().filePath().toAbsolutePath().toString());
        }
        return List.copyOf(paths);
    }

    /** Returns the loop-take stack built so far (empty outside loop-record). */
    TakeGroup takeGroup() {
        return takeGroup;
    }

    @Override
    public String toString() {
        return "TrackCapture[" + trackId + ", lane=" + lane + ", routing=" + routing
                + ", instrumentSource=" + instrumentSource + ", sealed=" + sealedSegments.size() + "]";
    }
}
