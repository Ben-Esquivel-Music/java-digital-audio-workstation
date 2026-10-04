package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.SourceRateMetadata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-armed-track capture state owned by the {@code capture-flush} thread
 * (Recording Reliability book §3.1, §4.3; story 323): the routing snapshot
 * taken at record start, the routed scratch block, the current lane's
 * {@link RecordingSession}, the lane index, every sealed segment across
 * lanes, and the loop-take stack built lap by lap.
 *
 * <p><strong>Threads.</strong> Constructed, with its unstarted lane-0
 * session, on the caller thread by {@code RecordingPipeline.prepare()} (which
 * also reads the {@link Track} once to take the snapshot — the flush thread
 * never touches a {@code Track}). Everything else runs on the
 * {@code capture-flush} thread, which is the only thread that touches the
 * take's files: {@link #setSegmentEvents} and {@link #startLane()} for lane 0
 * (the take's initialisation), {@link #prepareStandby()} and
 * {@link #finalizeLane(boolean, boolean)} — between them they start every
 * later lane — the scratch/session accessors used while
 * routing, {@link #discardAllFiles()} (a start that failed or was aborted)
 * and {@link #abandonWithoutSeal()} (the {@code stopAndAbandon} test seam).
 * The results ({@link #takeGroup()}, {@link #sealedSegments()},
 * {@link #session()}) are volatile or copy-on-write so the pipeline can read
 * them on the caller thread once the flush thread has terminated
 * ({@code CaptureFlushService.isTerminated()}).</p>
 *
 * <p><strong>Pre-opened loop lanes</strong> (book §4.6). In loop-record the
 * flush thread keeps a <em>standby</em> session for lane + 1, started —
 * its {@code .part} created, its header written — away from the loop seam
 * ({@link #prepareStandby()}): once the take's lane-0 sessions have started,
 * and after each block once a lane has been swapped in or has rotated. The
 * wrap then seals the lane, stacks the lap and swaps the standby in; it
 * opens no file. The standby reserves the index the current lane's next
 * segment would take, so the track directory's numbering is the one a lane
 * opened at the seam would have: indices are contiguous per track and rise
 * with the lane. A lane that rotates needs that index for itself, so the
 * standby hands the lane its open, empty file — the rotation opens nothing
 * either — and another standby is prepared, one index further, after the
 * block. A standby whose index
 * the lane did not leave free — the lane ended on an empty segment, which
 * was deleted — is discarded at the seam and the next lane opened there, as
 * is done when no standby could be prepared; if its empty file cannot be
 * deleted the take still goes on, with the next lane opened past that file
 * ({@link #finalizeLane}). The standby holds no frames:
 * discarding it deletes its file and removes its segment entry, and a take
 * that ends — stop, early seal, abort — never leaves one sealed.</p>
 *
 * <p><strong>Peak mirror</strong> (book §4.5). The capture owns one
 * {@link CapturePeakMirror}, fed with the frames the current lane's session
 * recorded and emptied when a lane is swapped in. Snapshots go to the sink
 * installed with {@link #setPeakSink}: on request while the lane records
 * ({@link #publishPeaksIfChanged()}) and once when the lane is finalized.
 * With no sink installed none is built.</p>
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

        /**
         * A rotating lane has just listed, as its own next segment, the
         * open, empty file its standby held — after
         * {@link #onSegmentDiscarded} for the standby's entry and
         * {@link #onSegmentOpened} for the lane's, and before the lane
         * appends its first frame to that file.
         */
        void onStandbyFileAdopted(TrackCapture capture, int lane, RecordingSegment segment);
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
     * Whether the start of one of this capture's lane sessions created the
     * track directory: every lane start, one that failed included, adds its
     * session's {@link RecordingSession#createdOutputDirectory()} if that
     * session's output directory is the track directory (a session factory
     * may point it elsewhere), and once set it stays set, so a later lane
     * whose start found the directory in place does not drop lane 0's claim.
     * Only then does {@link #discardAllFiles()} remove the directory. Flush
     * thread only.
     */
    private boolean createdTrackDirectory;
    /** The lane's decimated peaks; flush thread only. */
    private final CapturePeakMirror peaks = new CapturePeakMirror();
    /** Where peak snapshots go, or {@code null}: none is built then. Flush thread only. */
    private Consumer<CapturePeakSnapshot> peakSink;
    /** A snapshot could not be built or handed over, and that was logged; flush thread only. */
    private boolean peakFailureLogged;
    /**
     * The started, empty session of lane + 1, or {@code null}: none was
     * asked for, the last one was swapped in or discarded, or its start was
     * refused. Flush thread only.
     */
    private RecordingSession standby;
    /**
     * Every standby whose empty file could not be deleted when it was
     * discarded ({@link #stopStandby}): its writer is closed and its segment
     * entry removed, the file is still on disk. Kept so that
     * {@link #discardAllFiles()} can try the delete again. A take that goes
     * on after such a failure at a loop wrap can add another, so this is a
     * list; it holds one session per failed delete. Flush thread only.
     */
    private final List<RecordingSession> undeletedStandbys = new ArrayList<>();
    /**
     * What the discard of a standby at a loop wrap threw, when its empty
     * file could not be deleted and the take went on
     * ({@link #finalizeLane}), until {@link #takeStandbyDiscardFailure()}
     * collects it. Flush thread only.
     */
    private RuntimeException standbyDiscardFailure;
    /** The current lane's rotation took over the standby's file; cleared when the lane lists it. Flush thread only. */
    private boolean adoptionPending;
    /** The segment index {@link #standby} opened at. */
    private int standbyFirstIndex;
    /**
     * Starting a standby at the current lane's next index failed with
     * nothing created: not tried again until that index or the lane changes;
     * the next lane is opened at the seam instead.
     */
    private boolean standbyRefused;

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

    /**
     * Gives {@code target} the observer that forwards its segment events,
     * tagged with {@code laneIndex}, to whatever sink is installed when the
     * event fires, and that frees the standby's index when {@code target}
     * rotates ({@link #releaseStandbyIndexForRotation}).
     */
    private void attachObserver(RecordingSession target, int laneIndex) {
        target.setSegmentObserver(new RecordingSession.SegmentObserver() {
            @Override
            public void onSegmentOpened(RecordingSegment segment) {
                SegmentEvents sink = events;
                if (sink != null) {
                    sink.onSegmentOpened(TrackCapture.this, laneIndex, segment);
                }
                if (adoptionPending && target == session) {
                    adoptionPending = false;
                    if (sink != null) {
                        sink.onStandbyFileAdopted(TrackCapture.this, laneIndex, segment);
                    }
                }
            }

            @Override
            public void onSegmentSealed(RecordingSegment segment) {
                SegmentEvents sink = events;
                if (sink != null) {
                    sink.onSegmentSealed(TrackCapture.this, laneIndex, segment);
                }
                releaseStandbyIndexForRotation(target);
            }

            @Override
            public void onSegmentDiscarded(RecordingSegment segment) {
                SegmentEvents sink = events;
                if (sink != null) {
                    sink.onSegmentDiscarded(TrackCapture.this, laneIndex, segment);
                }
            }
        });
    }

    /**
     * A segment of {@code sealedIn} has just been sealed. If that is the
     * current lane rotating — not the seal that finalizes it — the segment
     * it opens next takes the index the standby holds, so the standby gives
     * its open, empty file to the lane
     * ({@link RecordingSession#surrenderEmptySegment()},
     * {@link RecordingSession#adoptAsNextSegment}): its segment entry is
     * removed, the lane's rotation lists the same file as its own and opens
     * nothing. A standby that is not at that index, or not in the lane's
     * directory, is discarded instead ({@link #stopStandby}) and the
     * rotation opens its file as usual. {@link #prepareStandby()} prepares
     * another standby, one index further, after the block.
     *
     * <p><strong>The manifest across the hand-over.</strong> The entry
     * changes key — from (lane + 1, k) to (lane, k) — in the builder only.
     * When the lane has listed the file,
     * {@link SegmentEvents#onStandbyFileAdopted} tells the flush service,
     * which writes the manifest then, before the lane appends a frame to
     * the file. So with a manifest that can be written, a crash leaves
     * either the manifest from before the rotation — file k listed as lane
     * + 1's, in progress, and holding no frames — or the re-keyed one. Only
     * while a manifest write is failing — that write, or an episode already
     * being retried once per retry interval — can the manifest on disk go
     * on naming file k as lane + 1's (or not at all) while the lane's
     * frames are appended to it, until a retry lands. The frames are not
     * lost; a reader of that manifest sees them under the wrong lane.</p>
     */
    private void releaseStandbyIndexForRotation(RecordingSession sealedIn) {
        if (sealedIn != session || sealedIn == finalizedSession) {
            return;
        }
        standbyRefused = false;
        RecordingSession held = standby;
        if (held == null) {
            return;
        }
        standby = null;
        if (standbyFirstIndex == sealedIn.getNextSegmentIndex()
                && held.getOutputDirectory().equals(sealedIn.getOutputDirectory())) {
            sealedIn.adoptAsNextSegment(held.surrenderEmptySegment());
            adoptionPending = true;
        } else {
            stopStandby(held, lane + 1);
        }
    }

    /**
     * Stops a standby session that holds no frames: its file is deleted and
     * its segment entry removed. If the file cannot be deleted, the entry
     * is removed all the same — the file is no segment of the take, and a
     * manifest that went on listing it in progress would contradict a take
     * whose lanes are all sealed — the session is remembered
     * ({@link #undeletedStandbys}) so that {@link #discardAllFiles()} can try
     * the delete again, and the failure is rethrown for the caller to
     * report: the zero-frame {@code .part} stays in the track directory,
     * named by no manifest entry.
     *
     * @throws UncheckedIOException if the empty file could not be deleted
     */
    private void stopStandby(RecordingSession unused, int standbyLane) {
        try {
            unused.stop();
        } catch (RuntimeException | Error e) {
            dropListedSegments(unused, standbyLane);
            undeletedStandbys.add(unused);
            throw e;
        }
    }

    /**
     * Returns, once, what the discard of a standby at the last loop wrap
     * threw when its empty file could not be deleted and the take went on
     * ({@link #finalizeLane}), or {@code null}. The caller reports it. Flush
     * thread.
     */
    RuntimeException takeStandbyDiscardFailure() {
        RuntimeException failure = standbyDiscardFailure;
        standbyDiscardFailure = null;
        return failure;
    }

    /** Removes the manifest entry of every segment {@code laneSession} still lists. */
    private void dropListedSegments(RecordingSession laneSession, int laneIndex) {
        SegmentEvents sink = events;
        if (sink == null) {
            return;
        }
        for (RecordingSegment segment : laneSession.getSegments()) {
            sink.onSegmentDiscarded(this, laneIndex, segment);
        }
    }

    /** Installs the segment event sink for the current and every later lane's session. */
    void setSegmentEvents(SegmentEvents events) {
        this.events = events;
    }

    /**
     * Installs the sink the lane's peak snapshots go to. Until one is
     * installed no snapshot is built: the mirror is still fed, and nothing
     * is copied out of it. The sink is called on the flush thread; whatever
     * it throws is contained ({@link #handOverPeaks}).
     */
    void setPeakSink(Consumer<CapturePeakSnapshot> peakSink) {
        this.peakSink = Objects.requireNonNull(peakSink, "peakSink must not be null");
    }

    /**
     * Folds into the lane's peak mirror the first {@code frames} frames of
     * the first {@code rows} rows of the routed block — the frames the
     * lane's session has just recorded from it.
     */
    void notePeaks(int rows, int frames) {
        peaks.add(routed, rows, frames);
    }

    /**
     * Hands the sink a snapshot of the lane's peaks if a sink is installed
     * and frames were added since the last one. A hand-over that threw is
     * contained ({@link #handOverPeaks}) and counts as made.
     *
     * @return whether a hand-over was made or attempted
     */
    boolean publishPeaksIfChanged() {
        if (peakSink == null || !peaks.hasChangedSinceSnapshot()) {
            return false;
        }
        handOverPeaks(lane);
        return true;
    }

    /**
     * Builds a snapshot of the mirror for {@code laneIndex} and hands it to
     * the sink, if one is installed. Whatever building the snapshot or the
     * sink throws — an {@link Error} included — is caught, logged once per
     * capture, and returned. The one thing that can still leave this method
     * is a throwable from that logging call itself, which is not guarded.
     * The peaks are a picture of the lane; a picture that could not be
     * taken ends no take and replaces no seal's failure.
     *
     * @return what was thrown, or {@code null}
     */
    private Throwable handOverPeaks(int laneIndex) {
        Consumer<CapturePeakSnapshot> sink = peakSink;
        if (sink == null) {
            return null;
        }
        try {
            sink.accept(peaks.snapshot(trackId, laneIndex, sampleRate));
            return null;
        } catch (Throwable t) {
            if (!peakFailureLogged) {
                peakFailureLogged = true;
                LOG.log(t instanceof Error ? Level.SEVERE : Level.WARNING, "a peak snapshot of track " + trackId
                        + " could not be built or its sink threw; capture continues, and later failures of this"
                        + " track are not logged", t);
            }
            return t;
        }
    }

    /** Returns the lane's peak mirror (flush thread; tests behind a fence). */
    CapturePeakMirror peaks() {
        return peaks;
    }

    /**
     * Starts the standby session of lane + 1 at the index the current
     * lane's next segment would take, if there is none and the current lane
     * is still recording. Its start is recorded as {@link #startLane()}
     * records lane 0's. Does nothing once the lane has been finalized, and
     * nothing again for an index at which a start was refused.
     *
     * <p>A start that fails having created nothing — the session factory
     * threw, the file could not be opened — is logged and remembered: the
     * next lane is then opened at the seam, where the same failure ends the
     * take as a write failure. A start that fails with its segment file
     * created — a listener threw, or anything threw an {@link Error} — is
     * undone here and rethrown: the session's writer is closed, the file it
     * created is deleted (best-effort: a delete that fails is logged and the
     * file left) and its segment entry is removed, and no standby is held.
     * The session lists only files its own {@code CREATE_NEW} created, so
     * nothing that was there before is touched. An {@code Error} from a
     * start that created nothing is rethrown as it is.</p>
     *
     * @throws RuntimeException a failed start that had created its segment file
     * @throws Error            an {@code Error} thrown by the start
     */
    void prepareStandby() {
        RecordingSession current = session;
        if (standby != null || standbyRefused || current == finalizedSession) {
            return;
        }
        int firstIndex = current.getNextSegmentIndex();
        RecordingSession next = null;
        try {
            next = newSession(lane + 1, firstIndex);
            startLaneSession(next);
        } catch (RuntimeException | Error e) {
            if (next != null && !next.getSegments().isEmpty()) {
                // Its file exists and nothing else will ever reach it.
                dropListedSegments(next, lane + 1);
                next.discardAllFiles();
                throw e;
            }
            if (e instanceof Error) {
                throw e;
            }
            standbyRefused = true;
            LOG.log(Level.WARNING, "could not pre-open lane " + (lane + 1) + " of track " + trackId
                    + " at segment index " + firstIndex + "; it is opened at the loop wrap instead", e);
            return;
        }
        standby = next;
        standbyFirstIndex = firstIndex;
    }

    /**
     * Discards the standby, if there is one: stopping a session that holds
     * no frames deletes its {@code .part} and removes its segment entry —
     * nothing is sealed ({@link #stopStandby}).
     *
     * @throws UncheckedIOException if the empty file could not be deleted:
     *                              its entry is removed all the same, the
     *                              file stays in the track directory, and
     *                              {@link #discardAllFiles()} tries the
     *                              delete again
     */
    void discardStandby() {
        RecordingSession unused = standby;
        if (unused == null) {
            return;
        }
        standby = null;
        stopStandby(unused, lane + 1);
    }

    /** Returns whether a standby session is held (flush thread; tests behind a fence). */
    boolean hasStandby() {
        return standby != null;
    }

    /**
     * Starts the current lane's session (it creates its directory if that is
     * missing, and its first {@code .part}) and records whether that start
     * created the track directory — also when the start then failed — so
     * that {@link #discardAllFiles()} removes the directory only if this
     * capture created it.
     */
    void startLane() {
        startLaneSession(session);
    }

    /**
     * Starts {@code laneSession} and, however the start ends, adds its
     * {@link RecordingSession#createdOutputDirectory()} to the capture's
     * claim on the track directory: a start that created the directory and
     * then failed (at its first {@code .part} open, say) still created it.
     * Only a session whose output directory is the track directory counts;
     * one that a session factory pointed elsewhere created nothing there.
     */
    private void startLaneSession(RecordingSession laneSession) {
        try {
            laneSession.start();
        } finally {
            createdTrackDirectory |= laneSession.createdOutputDirectory()
                    && laneSession.getOutputDirectory().equals(trackDirectory);
        }
    }

    /**
     * Seals the current lane: stops its session (exact-count seal), records
     * its sealed segments, optionally appends the lane as a {@link Take}
     * (loop-record), hands the peak sink, if one is installed, the lane's
     * last snapshot — whether or not the seal failed, and without letting
     * anything that hand-over throws out: it is attached as suppressed to
     * the seal's failure when there is one ({@link #handOverPeaks}) — and
     * optionally makes lane + 1 the current lane with the track directory's
     * segment numbering continued and an empty peak mirror. The next lane is
     * the standby ({@link #prepareStandby()}) when one is held at the index
     * the sealed lane left free — no file is opened then, and the standby's
     * force cadence is restarted — and otherwise a session opened here,
     * after a standby at another index has been discarded. If that
     * standby's empty file cannot be deleted, the take goes on as it does
     * when the same delete fails at the stop: the entry is removed, the
     * session is remembered for {@link #discardAllFiles()}
     * ({@link #stopStandby}), the failure is kept for the caller to report
     * ({@link #takeStandbyDiscardFailure()}), and the next lane is opened
     * one index past the file that stayed — not at the index the sealed
     * lane left free, because the file sits at the index after that one,
     * where the lane's next rotation, its standby or its successor would be
     * refused by it. The numbering then skips two indices: the free one and
     * the file's. An {@code Error} from that discard is still thrown. A lane started
     * here is recorded as {@link #startLane()} records lane 0's: whether it
     * created the track directory is added to the capture's claim, which
     * keeps the claim of every earlier lane.
     *
     * <p>The lane's clip carries no audio data: it lists the lane's sealed
     * segment files and declares the rate, width and frame count the lane
     * was captured with ({@link AudioClip#setSourceRateMetadata}); whoever
     * plays the take reads the audio from those files
     * ({@link SegmentFile#readFrames(List)}).</p>
     *
     * <p>If the seal fails the take is still built, at the length the lane
     * captured, listing whatever did seal — nothing at all when the lane's
     * only segment is the one that failed, whose frames stay in its
     * {@code .part} for recovery. The next lane is <em>not</em> made current
     * — a standby stays held, for {@link #discardStandby()} — and the
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
                clip.setSourceRateMetadata(declaredRate(previous, samples));
                takeGroup = takeGroup.withTakeAppended(Take.of(clip));
            }
        }
        // The lane's last peaks, whatever the seal did: the mirror holds
        // exactly the frames the lane's session counted. Nothing the
        // hand-over throws gets out: with a failed seal it rides on that
        // failure, which is the one to report.
        Throwable peakFailure = handOverPeaks(previousLane);
        if (failure != null && peakFailure != null && peakFailure != failure) {
            failure.addSuppressed(peakFailure);
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (openNext) {
            int nextLane = previousLane + 1;
            int firstIndex = previous.getNextSegmentIndex();
            RecordingSession next = standby;
            standby = null;
            if (next != null && standbyFirstIndex != firstIndex) {
                // The lane ended on an empty segment and gave its index
                // back: the standby sits one index too far. Its file goes,
                // and the lane is opened here at the index that is free.
                try {
                    stopStandby(next, nextLane);
                } catch (RuntimeException undeletable) {
                    // The lane sealed; the file that stays holds no frame.
                    // The next lane starts past it, so nothing the take
                    // opens from here on is refused by that file.
                    standbyDiscardFailure = undeletable;
                    firstIndex = Math.max(firstIndex, standbyFirstIndex + 1);
                }
                next = null;
            }
            if (next == null) {
                next = newSession(nextLane, firstIndex);
                startLaneSession(next);
            } else {
                // The cadence counts from now, not from the pre-open.
                next.restartForceCadence();
            }
            peaks.reset();
            standbyRefused = false;
            lane = nextLane;
            session = next;
        }
    }

    /**
     * What a clip built from {@code laneSession} declares about its audio:
     * the sample rate and channel count the session writes into its segment
     * headers, and the frames it recorded.
     *
     * @param laneSession the session that captured the clip's audio
     * @param frames      the frames it recorded; positive
     */
    static SourceRateMetadata declaredRate(RecordingSession laneSession, long frames) {
        AudioFormat captured = laneSession.getFormat();
        return new SourceRateMetadata(SegmentWriter.headerSampleRate(captured.sampleRate()),
                captured.channels(), frames);
    }

    /**
     * Test seam: abandons the current lane's writer, and the standby's if
     * one is held, without sealing
     * ({@link RecordingSession#abandonWithoutSeal()}); a segment that was
     * streaming stays a {@code .part} — the standby's an empty one, still
     * listed.
     */
    void abandonWithoutSeal() {
        session.abandonWithoutSeal();
        if (standby != null) {
            standby.abandonWithoutSeal();
        }
    }

    /**
     * Start-failure rollback: deletes the files this capture created — the
     * standby's file, if one is held, the file of a standby whose delete
     * failed when it was discarded ({@link #stopStandby}), the
     * current lane's files ({@link RecordingSession#discardAllFiles()}) and
     * the {@code .wav} of every sealed segment, never its {@code .part}
     * name, which the seal's rename vacated, so whatever is there now is
     * someone else's — and the track directory if the start of one of its
     * lane sessions created it and it is empty afterwards (best-effort: what
     * an I/O error keeps from being deleted is left and the error logged).
     * A lane session counts only if its output directory is the track
     * directory. Idempotent.
     */
    void discardAllFiles() {
        if (standby != null) {
            standby.discardAllFiles();
            standby = null;
        }
        for (RecordingSession undeleted : undeletedStandbys) {
            // The delete that failed when the standby was discarded, again.
            undeleted.discardAllFiles();
        }
        undeletedStandbys.clear();
        session.discardAllFiles();
        for (SealedSegment sealed : sealedSegments) {
            try {
                Files.deleteIfExists(sealed.segment().filePath());
            } catch (IOException e) {
                LOG.log(Level.WARNING, "could not delete " + sealed.segment().filePath(), e);
            }
        }
        sealedSegments.clear();
        takeGroup = TakeGroup.empty();
        if (createdTrackDirectory) {
            try {
                Files.deleteIfExists(trackDirectory);
            } catch (DirectoryNotEmptyException notEmpty) {
                // Someone else's files, or ones a failed delete left: leave them.
            } catch (IOException e) {
                LOG.log(Level.WARNING, "could not remove " + trackDirectory, e);
            }
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
     * lane whose seal failed at a wrap stays the current lane. A pre-opened
     * standby lane is not a lane of the take and never counts here: it
     * holds no frames, and once it has been discarded its segment is listed
     * nowhere — also when its empty file could not be deleted, which
     * {@link #discardStandby()} reports by throwing. Flush thread.
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
