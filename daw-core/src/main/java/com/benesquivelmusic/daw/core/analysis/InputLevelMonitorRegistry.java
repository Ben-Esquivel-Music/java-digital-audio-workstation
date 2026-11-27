package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.core.track.Track;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime registry of {@link InputLevelMonitor} instances keyed by track id.
 *
 * <p>The engine's control paths call {@link #getOrCreate(String)} for every
 * graph track before publishing the graph or registry. The audio callback
 * uses {@link #get(String)} to tap input without allocating; the mixer UI
 * and arrangement-view track header read the same monitor's snapshot.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <ul>
 *     <li><b>Runtime-only.</b> This registry is never persisted — user story
 *     137 explicitly requires no persistence of meter state.</li>
 *     <li>Monitors are kept for the lifetime of the DAW session. Disarming a
 *     track leaves its monitor in place (cheap; a few words of state) so
 *     that re-arming resumes immediately without a state-creation race.
 *     {@link #remove(String)} is provided for callers that want to eagerly
 *     free state on track deletion.</li>
 *     <li>{@link #resetAll()} clears the sticky clip flag on every monitor;
 *     it is wired to the {@code Alt+click} gesture on any clip LED.</li>
 *     <li>Every render drive the engine builds, at a stream's start and at a
 *     resume, gives each monitor a new per-stream state at the floor and
 *     lifts a device loss's mute when it is newer than the lost drive
 *     ({@link #liftAll(long)}); a monitor created after that build starts
 *     with that build's state ({@link #getOrCreate(String)}).
 *     A device loss mutes every monitor through the lost render drive and
 *     floors the meters whose state belongs to that drive or an older one
 *     ({@link #silenceAll(long)}, on the FX thread); a reconnect that opens a
 *     stream on another device and closes it again at once does the same
 *     through that drive. The mute is the registry's, shared by every monitor
 *     it creates, so a monitor created during the loss is muted too. It is
 *     lifted only by a drive's build, never by an engine-state delivery: a
 *     stream can open with none, for a Play after a failed Settings apply
 *     (story 338, rulings E175, E185, E201 and E202).</li>
 *     <li>When the close of the input devices beside a stream stops waiting
 *     for a block one of them is still delivering, every monitor, present
 *     and created later in that drive, refuses those devices' writes until
 *     the next build, keeping what it showed
 *     ({@link #refuseTheDevicesBesideTheStream()}, ruling E205).</li>
 * </ul>
 *
 * <h2>Thread-safety</h2>
 *
 * <p>Backed by a {@link ConcurrentHashMap}, so that {@link #get(String)},
 * which the render pump and the delivery threads of the input devices
 * beside the stream use, takes no lock. One lock, a private object of the
 * registry, makes a monitor's creation and the installs of a build or a
 * refusal exclude each other: {@link #getOrCreate(String)} holds it while it
 * reads the newest built drive and the refused drive and inserts a monitor
 * constructed with them, and {@link #liftAll(long)} and
 * {@link #refuseTheDevicesBesideTheStream()} hold it while they record their
 * drive and install on every monitor. So a monitor is never inserted with an
 * older state than the registry requires, and their iterations see every
 * monitor. Nothing else takes it: {@link #silenceAll(long)},
 * {@link #resetAll()}, {@link #remove(String)} and {@link #clear()} work on
 * the map and the monitors without it, and an iteration of
 * {@link #silenceAll(long)} can miss a monitor inserted while it runs, which
 * shares the mute all the same. Lock order: the engine's stream lifecycle
 * lock, then its graph lock, then this registry's lock, then a monitor's
 * own lock ({@link InputLevelMonitor}); no path takes them in another order.
 * The engine calls {@link #getOrCreate(String)} under its graph lock, and
 * {@link #liftAll(long)} and {@link #refuseTheDevicesBesideTheStream()}
 * under its stream lifecycle lock and its graph lock; under this registry's
 * lock nothing is called out of the registry but the map's
 * {@code computeIfAbsent}, which constructs a monitor, a construction that
 * takes no lock, and the monitors' installs, which take each monitor's lock
 * in turn; {@link #resetAll()} and {@link #silenceAll(long)} take each
 * monitor's lock in turn, through {@link InputLevelMonitor#reset()} and
 * {@link InputLevelMonitor#silenceThrough(long)}, and hold no other. The
 * lock is held at the longest for one build's or one
 * refusal's installs, which is what a creation on the FX thread can wait
 * for. The {@link InputLevelMonitor} instances it
 * holds have their own thread-safety contract — see that class's javadoc.
 * The newest built drive and the refused drive only rise, under this
 * registry's lock; the mute's two epochs only rise, each by an atomic
 * maximum: the FX thread and the engine's stream lifecycle both mute, the
 * stream lifecycle builds, lifts and refuses.</p>
 */
public final class InputLevelMonitorRegistry {

    private final Map<String, InputLevelMonitor> byTrackId = new ConcurrentHashMap<>();
    private final InputLevelMonitor.LostStreamMute lostStreamMute = new InputLevelMonitor.LostStreamMute();

    /**
     * Taken by {@link #getOrCreate(String)}, {@link #liftAll(long)} and
     * {@link #refuseTheDevicesBesideTheStream()}, and by nothing else; see
     * the class javadoc.
     */
    private final Object drivesLock = new Object();

    /** The newest render drive {@link #liftAll(long)} was told of, {@code 0} before any; under {@link #drivesLock}. */
    private long builtDrive;

    /**
     * The newest drive whose input devices beside its stream the meters refuse
     * ({@link #refuseTheDevicesBesideTheStream()}), {@code -1} before any;
     * under {@link #drivesLock}.
     */
    private long refusedDrive = -1L;

    /**
     * Returns the monitor for {@code trackId}, creating one on first access
     * with the newest built drive's state, which refuses the input devices
     * beside that drive's stream once their close stopped waiting
     * ({@link #refuseTheDevicesBesideTheStream()}). Under the registry's
     * lock, so no build or refusal installs between that read and the
     * monitor's insertion.
     */
    public InputLevelMonitor getOrCreate(String trackId) {
        Objects.requireNonNull(trackId, "trackId must not be null");
        synchronized (drivesLock) {
            long built = builtDrive;
            boolean refusing = refusedDrive == built;
            return byTrackId.computeIfAbsent(trackId, _ -> new InputLevelMonitor(lostStreamMute, built, refusing));
        }
    }

    /**
     * Convenience overload that keys by {@link Track#getId()}.
     */
    public InputLevelMonitor getOrCreate(Track track) {
        Objects.requireNonNull(track, "track must not be null");
        return getOrCreate(track.getId());
    }

    /** Returns the existing monitor for {@code trackId}, or {@code null}. */
    public InputLevelMonitor get(String trackId) {
        if (trackId == null) {
            return null;
        }
        return byTrackId.get(trackId);
    }

    /** Removes and returns the monitor for {@code trackId}, or {@code null}. */
    public InputLevelMonitor remove(String trackId) {
        if (trackId == null) {
            return null;
        }
        return byTrackId.remove(trackId);
    }

    /** Returns the number of monitors currently registered. */
    public int size() {
        return byTrackId.size();
    }

    /**
     * Resets the sticky clip flag on every registered monitor.
     *
     * <p>Wired to the {@code Alt+click} gesture on any clip LED so engineers
     * can acknowledge clips on many tracks at once.</p>
     */
    public void resetAll() {
        for (InputLevelMonitor m : byTrackId.values()) {
            m.reset();
        }
    }

    /**
     * The audio device was lost (story 338, rulings E64, E175 and E202):
     * mutes every monitor, present and future, through render drive
     * {@code lostDrive}, then drops to the floor, keeping its clip latch, the
     * level of each monitor whose state belongs to {@code lostDrive} or an
     * older drive. Until a drive newer than {@code lostDrive} is built
     * ({@link #liftAll(long)}), each snapshot reads the floor with the live
     * clip latch, whatever a block of the lost stream still writes. A loss
     * through a drive the engine has already replaced changes nothing. The
     * FX thread, at the loss; the engine's stream lifecycle, through the
     * drive of a stream a reconnect opened on another device and closed
     * again at once (ruling E185).
     *
     * @param lostDrive the render-drive epoch read as the loss was declared,
     *                  or of the stream closed at once; a negative value
     *                  floors every monitor's level and mutes nothing
     */
    public void silenceAll(long lostDrive) {
        lostStreamMute.muteThrough(lostDrive);
        for (InputLevelMonitor m : byTrackId.values()) {
            m.silenceThrough(lostDrive);
        }
    }

    /**
     * The engine built render drive {@code newDrive}, before starting it:
     * records it as the newest built drive, gives every monitor whose state
     * belongs to an older drive a new state for {@code newDrive}, at the
     * floor with the clip latch and the routing and physical-input flags the
     * old state holds at that moment and a true-peak detector of its own,
     * then lifts a device loss's mute when
     * {@code newDrive} is newer than the lost drive. It does so at every
     * build, reading nothing of the mute, because the loss's FX delivery can
     * come after the build: in either order nothing of a lost stream is shown
     * or enters the new stream's first block (rulings E175 and E185). A
     * block of an older drive still being measured completes into the old
     * state, which nothing shows, and one delivered later is refused (ruling
     * E201). A drive not newer than the lost one lifts nothing. The record
     * and the installs are made under the registry's lock. The engine calls
     * this on its stream lifecycle thread, under its graph lock, after the
     * previous drive has exited and the input devices beside it were closed.
     *
     * @param newDrive the epoch of the render drive just built
     */
    public void liftAll(long newDrive) {
        synchronized (drivesLock) {
            builtDrive = Math.max(builtDrive, newDrive);
            for (InputLevelMonitor m : byTrackId.values()) {
                m.startWithDrive(newDrive, false);
            }
        }
        lostStreamMute.liftFrom(newDrive);
    }

    /**
     * The close of the input devices beside the stream of the newest built
     * drive stopped waiting for a block one of them is still delivering
     * (story 338, ruling E205): from now the meters take nothing more from
     * those devices. Records that drive as the one whose beside devices are
     * refused, so a monitor created later in that drive refuses them too
     * ({@link #getOrCreate(String)}), then gives every monitor a state of
     * that drive, with a true-peak detector of its own, that takes over the
     * level record, the clip latch, the routing and physical-input flags and
     * the frame count as they are at this moment and refuses those devices'
     * writes.
     * A block being measured at this moment completes into the replaced
     * state, which nothing shows; a clear of the latch or a routing edit made
     * afterwards applies; the next build gives every monitor an ordinary
     * state of its drive. The record and the installs are made under the
     * registry's lock. The engine calls this on its stream lifecycle thread,
     * under its graph lock, in the same hold in which the close of the input
     * devices beside the stream, its wait having passed, closes a device's
     * word and finds that device's delivery still in progress, directly after
     * that close.
     */
    public void refuseTheDevicesBesideTheStream() {
        synchronized (drivesLock) {
            long drive = builtDrive;
            refusedDrive = Math.max(refusedDrive, drive);
            for (InputLevelMonitor m : byTrackId.values()) {
                m.startWithDrive(drive, true);
            }
        }
    }

    /** Removes every registered monitor. */
    public void clear() {
        byTrackId.clear();
    }
}
