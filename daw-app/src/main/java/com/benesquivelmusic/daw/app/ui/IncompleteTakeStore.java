package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.recording.TakeDirectories;
import com.benesquivelmusic.daw.core.recording.TakeManifest;
import com.benesquivelmusic.daw.core.recording.TakeRecovery;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The registry of the open project's rescued takes (Recording Reliability
 * book §2.9, story 327): the takes sealed by a failure rather than by the
 * user's Stop, kept so the user gets the "review recovered take" flow.
 *
 * <p>There is no second copy of the audio here. A rescued take is the
 * ordinary take on disk — its segments under
 * {@code <project>/audio/takes/<take>/}, sealed by the same seal a Stop
 * applies, its manifest reading {@code aborted} with the reason — and this
 * registry only remembers that it exists and why. It is fed by exactly two
 * paths, both seal paths:</p>
 * <ul>
 *   <li>the live rescue: {@link RecordCoordinator} registers the take its
 *       device-loss rescue sealed, once the take's clips are published
 *       ({@link TakeManifest.SealedBy#DEVICE_LOSS});</li>
 *   <li>the recovery scan: {@link #registerRecoveredTakes(Path)} runs the
 *       book §4.7 grammar ({@link TakeRecovery#scan}) over the project's
 *       {@code audio/takes} and registers every take it sealed
 *       ({@link TakeManifest.SealedBy#CRASH_RECOVERY}). Story 332 is to
 *       call it on every project-open path — no production caller exists
 *       yet; this class only provides it.</li>
 * </ul>
 *
 * <p>In memory, one per project ({@link RecordCoordinator} owns it, rooted
 * at the project's directory); persisting takes in the project file is
 * story 334's. A registered take must lie under the project's
 * {@code audio/takes} — never the OS temporary directory (book §9 item 4),
 * never another project — which {@link RescuedTake} refuses otherwise.</p>
 *
 * <p>Thread-safe: registration and reads may come from any thread; in the
 * app the live rescue registers on the FX thread and the scan on the thread
 * that opens the project.</p>
 */
public final class IncompleteTakeStore {

    private final Path projectDirectory;
    private final Path takesDirectory;
    private final List<RescuedTake> rescuedTakes = new CopyOnWriteArrayList<>();

    /**
     * Creates the registry of the project at {@code projectDirectory}.
     *
     * @param projectDirectory the project's directory; must not be null
     */
    public IncompleteTakeStore(Path projectDirectory) {
        this.projectDirectory = Objects.requireNonNull(projectDirectory, "projectDirectory must not be null")
                .toAbsolutePath().normalize();
        this.takesDirectory = TakeDirectories.takesDirectory(ProjectManager.audioDirectory(this.projectDirectory))
                .toAbsolutePath().normalize();
    }

    /**
     * One take sealed by a failure, as the registry remembers it.
     *
     * @param takeDirectory      the take's directory, absolute, under the
     *                           project's {@code audio/takes}
     * @param reason             what sealed it: {@link TakeManifest.SealedBy#DEVICE_LOSS}
     *                           for the live rescue, {@link TakeManifest.SealedBy#CRASH_RECOVERY}
     *                           for the recovery scan; no other reason is a rescue
     * @param deviceName         the lost device's name, for a device-loss
     *                           rescue; empty for a crash recovery, which
     *                           does not know it
     * @param registeredAt       when it was registered
     * @param clipCount          the clips the take was published with
     *                           (crash recovery: the distinct tracks of its
     *                           playable segments)
     * @param sealedSegmentCount the segments that were sealed, and so are playable
     */
    public record RescuedTake(Path takeDirectory, TakeManifest.SealedBy reason, Optional<String> deviceName,
                              Instant registeredAt, int clipCount, int sealedSegmentCount) {
        public RescuedTake {
            Objects.requireNonNull(takeDirectory, "takeDirectory must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(deviceName, "deviceName must not be null");
            Objects.requireNonNull(registeredAt, "registeredAt must not be null");
            if (reason != TakeManifest.SealedBy.DEVICE_LOSS && reason != TakeManifest.SealedBy.CRASH_RECOVERY) {
                throw new IllegalArgumentException("a rescued take is sealed by DEVICE_LOSS or CRASH_RECOVERY, not " + reason);
            }
            if (!takeDirectory.isAbsolute()) {
                throw new IllegalArgumentException("takeDirectory must be absolute: " + takeDirectory);
            }
            if (clipCount < 0) {
                throw new IllegalArgumentException("clipCount must not be negative: " + clipCount);
            }
            if (sealedSegmentCount < 0) {
                throw new IllegalArgumentException("sealedSegmentCount must not be negative: " + sealedSegmentCount);
            }
            takeDirectory = takeDirectory.normalize();
        }
    }

    /** Returns the project directory this registry is rooted at (absolute, normalized). */
    public Path projectDirectory() {
        return projectDirectory;
    }

    /**
     * Registers {@code take}. Any thread.
     *
     * @param take the rescued take
     * @throws IllegalArgumentException if the take's directory is not a
     *                                  subdirectory of this project's
     *                                  {@code audio/takes} — a take in the OS
     *                                  temporary directory or in another
     *                                  project is never a take of this one
     */
    public void register(RescuedTake take) {
        Objects.requireNonNull(take, "take must not be null");
        requireUnderTakes(take.takeDirectory());
        rescuedTakes.add(take);
    }

    /**
     * Returns the rescued takes registered so far, oldest first; an
     * unmodifiable snapshot that later registrations do not change. Any thread.
     */
    public List<RescuedTake> rescuedTakes() {
        return List.copyOf(rescuedTakes);
    }

    /** Returns the take registered last, or empty when none has been. Any thread. */
    public Optional<RescuedTake> latest() {
        List<RescuedTake> snapshot = rescuedTakes();
        return snapshot.isEmpty() ? Optional.empty() : Optional.of(snapshot.getLast());
    }

    /** Returns whether no take has been registered. Any thread. */
    public boolean isEmpty() {
        return rescuedTakes.isEmpty();
    }

    /**
     * Runs the crash-recovery grammar (book §4.7, {@link TakeRecovery#scan})
     * over {@code <audioDirectory>/takes} and registers every take it
     * recovered with reason {@link TakeManifest.SealedBy#CRASH_RECOVERY}:
     * its clip count is the number of distinct tracks among its playable
     * segments and its sealed-segment count their number. A take the scan
     * found nothing to seal in — every remaining file unrecoverable — is
     * still registered, with zero of each, so the user is told it is there.
     * Storage I/O: never on the FX thread; in the app the project-open
     * recovery pass is to run it (story 332 — no production caller yet).
     *
     * @param audioDirectory the project's {@code audio} directory; its
     *                       {@code takes} subdirectory may not exist yet
     * @return the takes registered by this call, in directory-name order
     * @throws IOException              if the takes directory cannot be listed
     * @throws IllegalArgumentException if {@code audioDirectory} is not this
     *                                  project's {@code audio} directory
     */
    public List<RescuedTake> registerRecoveredTakes(Path audioDirectory) throws IOException {
        Objects.requireNonNull(audioDirectory, "audioDirectory must not be null");
        Path takes = TakeDirectories.takesDirectory(audioDirectory).toAbsolutePath().normalize();
        if (!takes.equals(takesDirectory)) {
            throw new IllegalArgumentException(audioDirectory + " is not the audio directory of the project at "
                    + projectDirectory);
        }
        List<RescuedTake> registered = new ArrayList<>();
        for (TakeRecovery.RecoveredTake recovered : TakeRecovery.scan(takes)) {
            List<TakeManifest.SegmentEntry> playable = recovered.playableSegments();
            Set<String> tracks = new HashSet<>();
            for (TakeManifest.SegmentEntry segment : playable) {
                tracks.add(segment.trackId());
            }
            RescuedTake take = new RescuedTake(recovered.takeDirectory().toAbsolutePath(),
                    TakeManifest.SealedBy.CRASH_RECOVERY, Optional.empty(), Instant.now(),
                    tracks.size(), playable.size());
            register(take);
            registered.add(take);
        }
        return List.copyOf(registered);
    }

    private void requireUnderTakes(Path takeDirectory) {
        Path normalized = takeDirectory.toAbsolutePath().normalize();
        if (normalized.getParent() == null || !normalized.getParent().equals(takesDirectory)) {
            throw new IllegalArgumentException("a rescued take of the project at " + projectDirectory
                    + " lies directly under " + takesDirectory + ", not at " + takeDirectory);
        }
    }
}
