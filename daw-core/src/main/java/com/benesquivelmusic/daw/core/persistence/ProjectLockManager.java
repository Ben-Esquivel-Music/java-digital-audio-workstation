package com.benesquivelmusic.daw.core.persistence;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages a per-project advisory lock implemented as a plain sidecar file
 * ({@code .project.lock}) containing JSON metadata about the holding session.
 *
 * <p>Unlike {@link java.nio.channels.FileLock OS-level advisory locks}, this
 * mechanism only relies on read / write / atomic rename semantics, which work
 * reliably on local filesystems, SMB, and NFS — the use cases that matter for
 * shared NAS-based DAW workflows.</p>
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li>{@link #tryAcquire(Path)} writes the lock file if and only if it does
 *       not already exist (using {@link StandardOpenOption#CREATE_NEW}). The
 *       returned {@link AcquisitionResult} either contains the new lock, or
 *       the existing holder's lock and a {@code stale} flag.</li>
 *   <li>{@link #forceAcquire(Path)} unconditionally takes over the lock and
 *       writes a takeover entry to the {@link ProjectLog}.</li>
 *   <li>{@link #openReadOnly(Path)} acquires nothing but records read-only
 *       state for {@link #status()}.</li>
 *   <li>{@link #startHeartbeat(java.util.concurrent.ScheduledExecutorService)
 *       startHeartbeat} (or {@link #startHeartbeat()} for the default
 *       executor) periodically rewrites the lock file with an updated
 *       {@code lastSeenAt} timestamp every {@link #HEARTBEAT_INTERVAL}.</li>
 *   <li>{@link #verifyOurs()} re-reads the lock file and throws
 *       {@link LockStolenException} if it has been replaced or deleted —
 *       call this immediately before every save.</li>
 *   <li>{@link #release()} stops the heartbeat and deletes the lock file
 *       only if it still belongs to us.</li>
 * </ul>
 *
 * <p>A lock is considered stale if its {@code lastSeenAt} is more than
 * {@link #STALE_THRESHOLD} old according to the injected {@link InstantSource}.
 * Tests can advance a fake clock to exercise stale recovery.</p>
 */
public final class ProjectLockManager implements AutoCloseable {

    /** Sidecar lock file name written next to {@code project.daw}. */
    public static final String LOCK_FILE_NAME = ".project.lock";

    /** Heartbeat refresh interval (30 seconds, per spec). */
    public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    /** A lock whose {@code lastSeenAt} is older than this is considered stale (10 minutes, per spec). */
    public static final Duration STALE_THRESHOLD = Duration.ofMinutes(10);

    private final InstantSource clock;
    private final String user;
    private final String hostname;
    private final long pid;
    private final AtomicFileWriter fileWriter;
    private final LockFileReader lockFileReader;

    private final AtomicReference<ProjectLock> heldLock = new AtomicReference<>();
    private final AtomicReference<Path> projectDirectory = new AtomicReference<>();
    private final AtomicReference<LockStatus> status = new AtomicReference<>(LockStatus.NONE);

    private ScheduledExecutorService ownedExecutor;
    private ScheduledFuture<?> heartbeatTask;

    /**
     * Guarded by this manager's monitor: set, never cleared, by
     * {@link #quiesceHeartbeat()} — from then on {@link #heartbeat()} writes
     * nothing (story 333).
     */
    private boolean quiesced;

    /** Creates a manager that uses the system clock and detected OS identity. */
    public ProjectLockManager() {
        this(InstantSource.system(), defaultUser(), defaultHostname(), defaultPid());
    }

    /**
     * Creates a manager with explicit identity and clock injection — primarily
     * for unit tests that need two "sessions" against the same directory and
     * for advancing a fake clock to exercise stale-lock detection.
     */
    public ProjectLockManager(InstantSource clock, String user, String hostname, long pid) {
        this(clock, user, hostname, pid, new AtomicFileWriter());
    }

    /**
     * As {@link #ProjectLockManager(InstantSource, String, String, long)},
     * with the writer that replaces the lock file on a takeover and on each
     * heartbeat — for tests that fail or observe those writes (story 331).
     * The acquisition's {@code CREATE_NEW} write does not go through it.
     */
    ProjectLockManager(InstantSource clock, String user, String hostname, long pid,
                       AtomicFileWriter fileWriter) {
        this(clock, user, hostname, pid, fileWriter, Files::readString);
    }

    /**
     * As above, with the reader of every lock-file read this manager makes
     * — for tests that record the thread of each read, such as
     * {@link #verifyOurs()}'s re-read before a save (story 331).
     */
    ProjectLockManager(InstantSource clock, String user, String hostname, long pid,
                       AtomicFileWriter fileWriter, LockFileReader lockFileReader) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.user = Objects.requireNonNull(user, "user must not be null");
        this.hostname = Objects.requireNonNull(hostname, "hostname must not be null");
        this.pid = pid;
        this.fileWriter = Objects.requireNonNull(fileWriter, "fileWriter must not be null");
        this.lockFileReader = Objects.requireNonNull(lockFileReader, "lockFileReader must not be null");
    }

    /** Reads the lock file's text (test seam); production reads it with {@link Files#readString(Path)}. */
    @FunctionalInterface
    interface LockFileReader {

        /** @throws IOException if {@code lockFile} cannot be read */
        String read(Path lockFile) throws IOException;
    }

    /** Result of a {@link #tryAcquire(Path) tryAcquire} call. */
    public record AcquisitionResult(
            ProjectLock acquiredLock,
            ProjectLock existingLock,
            boolean stale
    ) {
        /** Convenience for {@code acquiredLock != null}. */
        public boolean wasAcquired() {
            return acquiredLock != null;
        }
    }

    /** Independent target lease with the same injected identity, clock and I/O seams. */
    public ProjectLockManager fork() {
        return new ProjectLockManager(clock, user, hostname, pid, fileWriter, lockFileReader);
    }

    /**
     * Attempts to acquire the lock for {@code projectDir}. If the lock is
     * already held by another session, returns an {@link AcquisitionResult}
     * with the existing holder and a {@code stale} flag — no lock is written
     * in that case.
     */
    public synchronized AcquisitionResult tryAcquire(Path projectDir) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Path lockFile = projectDir.resolve(LOCK_FILE_NAME);
        Instant now = clock.instant();

        if (Files.exists(lockFile)) {
            ProjectLock existing = readLock(lockFile, lockFileReader);
            boolean stale = existing != null && isStale(existing, now);
            return new AcquisitionResult(null, existing, stale);
        }

        ProjectLock lock = ProjectLock.create(user, hostname, pid, now);
        try {
            writeLockAtomically(lockFile, lock, true);
        } catch (java.nio.file.FileAlreadyExistsException race) {
            // Another session created the lock between our exists() check and our write.
            ProjectLock existing = readLock(lockFile, lockFileReader);
            boolean stale = existing != null && isStale(existing, now);
            return new AcquisitionResult(null, existing, stale);
        }
        bind(projectDir, lock, LockStatus.HELD);
        return new AcquisitionResult(lock, null, false);
    }

    /**
     * Unconditionally writes a fresh lock for {@code projectDir}, replacing
     * any existing one. Records the takeover in the project audit log.
     */
    public synchronized ProjectLock forceAcquire(Path projectDir) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Path lockFile = projectDir.resolve(LOCK_FILE_NAME);
        Instant now = clock.instant();

        ProjectLock previous = Files.exists(lockFile) ? readLock(lockFile, lockFileReader) : null;
        ProjectLock lock = ProjectLock.create(user, hostname, pid, now);
        writeLockAtomically(lockFile, lock, false);
        bind(projectDir, lock, LockStatus.HELD);

        ProjectLog log = new ProjectLog(projectDir);
        if (previous != null) {
            boolean wasStale = isStale(previous, now);
            log.append(now, "lock takeover by " + user + "@" + hostname + " (pid " + pid + ")"
                    + " from " + previous.user() + "@" + previous.hostname()
                    + " (pid " + previous.pid() + ", lockId " + previous.lockId() + ")"
                    + (wasStale ? " — previous lock was stale" : ""));
        } else {
            log.append(now, "lock acquired by " + user + "@" + hostname + " (pid " + pid + ")");
        }
        return lock;
    }

    /**
     * Records that the project at {@code projectDir} was opened read-only.
     * No lock file is written.
     */
    public synchronized void openReadOnly(Path projectDir) {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        projectDirectory.set(projectDir);
        heldLock.set(null);
        status.set(LockStatus.READ_ONLY);
        stopHeartbeat();
    }

    /**
     * Re-reads the lock file and verifies it is still ours. If it has been
     * replaced or deleted, marks the status as {@link LockStatus#STOLEN STOLEN}
     * and throws {@link LockStolenException}.
     *
     * <p>{@link ProjectManager} calls this from every save path before writing
     * the project file. It passes when nothing is held — a read-only open, or
     * no project open; {@link #verifyHeld()} refuses then.</p>
     */
    public void verifyOurs() throws LockStolenException, IOException {
        ProjectLock ours = heldLock.get();
        Path dir = projectDirectory.get();
        if (ours == null || dir == null) {
            return; // nothing held — read-only or no project open
        }
        verify(ours, dir);
    }

    /**
     * Verifies that this session holds the lock and that it is still ours:
     * {@link #verifyOurs()}, except that holding nothing — no project open, a
     * read-only open, a released lock — fails too. {@link ProjectManager}
     * installs it as the checkpoints' write guard (story 331), so a session
     * that does not own the project folder never writes, prunes or cleans up
     * its checkpoints.
     *
     * @throws LockStolenException if the lock was taken over or its file deleted
     * @throws IOException         if this session holds no lock, or the lock file cannot be read
     */
    public void verifyHeld() throws LockStolenException, IOException {
        ProjectLock ours = heldLock.get();
        Path dir = projectDirectory.get();
        if (ours == null || dir == null) {
            throw new IOException("This session holds no project lock (" + status.get()
                    + "); it does not write the project folder");
        }
        verify(ours, dir);
    }

    /** A filesystem operation that must finish before its project lease is retired. */
    @FunctionalInterface
    public interface LeaseOperation<T> {
        T run() throws IOException;
    }

    /**
     * Runs an operation under the captured lease, excluding release and rebinding
     * until publication finishes. A queued operation cannot use a later lease,
     * even when this manager has reopened the same project directory.
     */
    public synchronized <T> T withHeldLease(ProjectLock expected, LeaseOperation<T> operation)
            throws IOException {
        Objects.requireNonNull(expected, "expected must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        ProjectLock current = heldLock.get();
        if (current == null || !current.lockId().equals(expected.lockId())) {
            throw new IOException("The captured project lease has been retired");
        }
        verifyHeld();
        return operation.run();
    }

    private void verify(ProjectLock ours, Path dir) throws LockStolenException, IOException {
        Path lockFile = dir.resolve(LOCK_FILE_NAME);
        if (!Files.exists(lockFile)) {
            status.set(LockStatus.STOLEN);
            throw new LockStolenException(
                    "Lock file has been deleted; another session may be writing", null);
        }
        ProjectLock onDisk = readLock(lockFile, lockFileReader);
        if (onDisk == null || !onDisk.lockId().equals(ours.lockId())) {
            status.set(LockStatus.STOLEN);
            throw new LockStolenException(
                    "Lock has been taken over by " + (onDisk == null ? "unknown" :
                            onDisk.user() + "@" + onDisk.hostname()), onDisk);
        }
    }

    /**
     * Refreshes the lock file's {@code lastSeenAt} to the current clock value.
     * Returns {@code true} if the heartbeat succeeded; {@code false} if the
     * lock has been stolen or the file is gone (status is updated accordingly),
     * and — writing nothing — once {@link #quiesceHeartbeat()} quiesced it.
     * Synchronized with {@link #release()}: a release waits for a heartbeat
     * inside its write, so the lock file is never written again after the
     * release deleted it (story 333's lock hand-off).
     */
    public synchronized boolean heartbeat() {
        if (quiesced) {
            return false;
        }
        ProjectLock ours = heldLock.get();
        Path dir = projectDirectory.get();
        if (ours == null || dir == null) {
            return false;
        }
        Path lockFile = dir.resolve(LOCK_FILE_NAME);
        try {
            if (!Files.exists(lockFile)) {
                status.set(LockStatus.STOLEN);
                return false;
            }
            ProjectLock onDisk = readLock(lockFile, lockFileReader);
            if (onDisk == null || !onDisk.lockId().equals(ours.lockId())) {
                status.set(LockStatus.STOLEN);
                return false;
            }
            ProjectLock refreshed = ours.withHeartbeat(clock.instant());
            writeLockAtomically(lockFile, refreshed, false);
            heldLock.set(refreshed);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Starts a periodic heartbeat using a manager-owned single-thread daemon
     * scheduler, fired every {@link #HEARTBEAT_INTERVAL}.
     */
    public synchronized void startHeartbeat() {
        if (ownedExecutor == null) {
            ownedExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "project-lock-heartbeat");
                t.setDaemon(true);
                return t;
            });
        }
        startHeartbeat(ownedExecutor);
    }

    /** Starts a periodic heartbeat using the supplied scheduler. */
    public synchronized void startHeartbeat(ScheduledExecutorService scheduler) {
        Objects.requireNonNull(scheduler, "scheduler must not be null");
        stopHeartbeat();
        long ms = HEARTBEAT_INTERVAL.toMillis();
        heartbeatTask = scheduler.scheduleAtFixedRate(this::heartbeat, ms, ms, TimeUnit.MILLISECONDS);
    }

    private synchronized void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }

    /**
     * Stops this lease's heartbeat for good (story 333): cancels the
     * scheduled task and — by taking this manager's monitor — waits for a
     * heartbeat already inside its write. A heartbeat the scheduler had
     * already started, which enters {@link #heartbeat()} only after this
     * call, writes nothing: {@code cancel(false)} alone is no proof that it
     * never runs. Idempotent.
     */
    synchronized void quiesceHeartbeat() {
        quiesced = true;
        stopHeartbeat();
    }

    /**
     * Hands this session's own lock of {@code projectDir} over to
     * {@code successor} while this lease is still being retired (story 333;
     * {@link ProjectManager} reopening a folder whose release still waits for
     * the work owed in it): quiesces this lease's heartbeat, then has
     * {@code successor} {@linkplain #forceAcquire(Path) take the lock over},
     * all under this manager's monitor — so neither this lease's
     * {@link #release()} (which reads the lock file, then deletes it if it
     * is still ours) nor a late heartbeat (which reads it, then rewrites it)
     * interleaves with the successor's write. Afterwards the file holds the
     * successor's lock: this lease's release reads a foreign lock and
     * deletes nothing, and work bound to this lease fails its
     * {@link #verifyHeld()}. The work this lease still owes in the folder —
     * a held checkpoint write or prune, say — is not ended by the hand-over:
     * the successor's lock must outlive it too, so {@link ProjectManager}
     * releases the successor only once this lease's release has run.
     *
     * <p>Only a lock file that still holds this lease's own lock is handed
     * over — the precondition {@link #release()} uses, read under the same
     * monitor. A lease already released by the time the hand-over runs has
     * none: its release deleted the lock file if it was still ours, and the
     * file may since hold another session's lock (corefix review N12). Nor
     * has a lease whose lock another session took over meanwhile, its file
     * now that session's (the app fix pass 1 review, N1). A forced
     * acquisition would overwrite either; the hand-over hands nothing over,
     * and the caller acquires as for any folder, through the conflict
     * path.</p>
     *
     * @param successor  the lease that takes the lock over
     * @param projectDir the folder whose lock is handed over
     * @return the successor's lock, or empty when the folder no longer holds this lease's lock and nothing was
     *         handed over
     * @throws IOException if the lock file could not be read, or the successor could not write the lock
     */
    synchronized Optional<ProjectLock> handOverTo(ProjectLockManager successor, Path projectDir) throws IOException {
        Objects.requireNonNull(successor, "successor must not be null");
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        quiesceHeartbeat();
        ProjectLock ours = heldLock.get();
        Path lockFile = projectDir.resolve(LOCK_FILE_NAME);
        ProjectLock onDisk = ours == null || !Files.exists(lockFile) ? null : readLock(lockFile, lockFileReader);
        if (onDisk == null || !onDisk.lockId().equals(ours.lockId())) {
            return Optional.empty();
        }
        return Optional.of(successor.forceAcquire(projectDir));
    }

    /**
     * Releases the lock if it still belongs to us, deleting the sidecar file.
     * Always stops the heartbeat. Safe to call when nothing is held. It waits
     * for a {@link #heartbeat()} inside its write and for a
     * {@link #withHeldLease} operation (one monitor), so neither runs in the
     * folder after the lock file is gone.
     */
    public synchronized void release() throws IOException {
        stopHeartbeat();
        ProjectLock ours = heldLock.get();
        Path dir = projectDirectory.get();
        if (ours != null && dir != null) {
            Path lockFile = dir.resolve(LOCK_FILE_NAME);
            if (Files.exists(lockFile)) {
                ProjectLock onDisk = readLock(lockFile, lockFileReader);
                if (onDisk != null && onDisk.lockId().equals(ours.lockId())) {
                    Files.deleteIfExists(lockFile);
                }
            }
        }
        heldLock.set(null);
        projectDirectory.set(null);
        status.set(LockStatus.NONE);
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            release();
        } finally {
            synchronized (this) {
                if (ownedExecutor != null) {
                    ownedExecutor.shutdownNow();
                    ownedExecutor = null;
                }
            }
        }
    }

    /** Returns the lock currently held by this session, if any. */
    public Optional<ProjectLock> currentLock() {
        return Optional.ofNullable(heldLock.get());
    }

    /**
     * The folder this lease is bound to — the one whose lock it holds, or
     * that it opened read-only — until it is released (story 333: a retiring
     * lease is told apart from a copy of its folder by it).
     *
     * @return the folder, if any
     */
    Optional<Path> boundDirectory() {
        return Optional.ofNullable(projectDirectory.get());
    }

    /** Returns the current high-level status (NONE / HELD / READ_ONLY / STOLEN). */
    public LockStatus status() {
        return status.get();
    }

    /** Returns whether {@code lock}'s {@code lastSeenAt} is older than {@link #STALE_THRESHOLD} relative to {@code now}. */
    public static boolean isStale(ProjectLock lock, Instant now) {
        Objects.requireNonNull(lock, "lock must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return Duration.between(lock.lastSeenAt(), now).compareTo(STALE_THRESHOLD) > 0;
    }

    /**
     * Reads the {@code .project.lock} sidecar in {@code projectDir} WITHOUT
     * acquiring, writing, or modifying anything (unlike {@link #tryAcquire}).
     * Returns the parsed holder lock, or empty when no lock file is present or
     * it cannot be parsed. The story-296 Welcome/Hub recover-scan uses this +
     * {@link #isStale(ProjectLock, Instant)} to classify "did not exit cleanly"
     * without the side effect of {@code tryAcquire}.
     *
     * @param projectDir the project directory whose lock sidecar to peek
     * @return the on-disk holder lock, or empty when absent / unparseable
     */
    public static Optional<ProjectLock> peekLock(Path projectDir) {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        try {
            return Optional.ofNullable(readLock(projectDir.resolve(LOCK_FILE_NAME)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Reads and parses a lock file, returning {@code null} if it is absent —
     * gone by the time it is read included — or cannot be parsed.
     */
    static ProjectLock readLock(Path lockFile) throws IOException {
        return readLock(lockFile, Files::readString);
    }

    private static ProjectLock readLock(Path lockFile, LockFileReader reader) throws IOException {
        if (!Files.exists(lockFile)) {
            return null;
        }
        String json;
        try {
            json = reader.read(lockFile);
        } catch (java.nio.file.NoSuchFileException goneSinceTheCheck) {
            // Deleted between the check and the read — a release that ran in between (story 333): absent.
            return null;
        }
        try {
            return parseJson(json);
        } catch (RuntimeException e) {
            return null; // corrupted / partial write — treat as absent
        }
    }

    private void bind(Path projectDir, ProjectLock lock, LockStatus newStatus) {
        projectDirectory.set(projectDir);
        heldLock.set(lock);
        status.set(newStatus);
    }

    /**
     * Writes the lock file: a create-or-fail write when acquiring, else an
     * atomic replace through the {@link AtomicFileWriter} (a temp sibling,
     * forced, then moved into place; story 331). The replace asks only for
     * the directory's permission, as the rename before it did: a stale lock
     * another user's crashed session left in a group-shared project folder
     * is not writable to this user, and is still taken over.
     */
    private void writeLockAtomically(Path lockFile, ProjectLock lock, boolean createNew) throws IOException {
        String json = toJson(lock);
        if (createNew) {
            // Direct CREATE_NEW write — gives us the cross-FS atomic "create-or-fail" we need.
            Files.writeString(lockFile, json,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            return;
        }
        fileWriter.writeString(lockFile, json, AtomicFileWriter.ReplacePermission.DIRECTORY);
    }

    // ---- minimal JSON serialization (no external dependency) -----------------

    static String toJson(ProjectLock lock) {
        return "{"
                + "\"lockId\":" + jsonString(lock.lockId()) + ","
                + "\"user\":" + jsonString(lock.user()) + ","
                + "\"hostname\":" + jsonString(lock.hostname()) + ","
                + "\"pid\":" + lock.pid() + ","
                + "\"openedAt\":" + jsonString(lock.openedAt().toString()) + ","
                + "\"lastSeenAt\":" + jsonString(lock.lastSeenAt().toString())
                + "}";
    }

    static ProjectLock parseJson(String json) {
        String lockId = extractString(json, "lockId");
        String user = extractString(json, "user");
        String hostname = extractString(json, "hostname");
        long pid = Long.parseLong(extractRaw(json, "pid"));
        Instant openedAt = Instant.parse(extractString(json, "openedAt"));
        Instant lastSeenAt = Instant.parse(extractString(json, "lastSeenAt"));
        return new ProjectLock(lockId, user, hostname, pid, openedAt, lastSeenAt);
    }

    private static String extractString(String json, String key) {
        String raw = extractRaw(json, key);
        if (raw.length() < 2 || raw.charAt(0) != '"' || raw.charAt(raw.length() - 1) != '"') {
            throw new IllegalArgumentException("Expected quoted string for key " + key);
        }
        return unescape(raw.substring(1, raw.length() - 1));
    }

    private static String extractRaw(String json, String key) {
        String marker = "\"" + key + "\"";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            throw new IllegalArgumentException("Missing key: " + key);
        }
        int colon = json.indexOf(':', idx + marker.length());
        if (colon < 0) {
            throw new IllegalArgumentException("Malformed JSON near key: " + key);
        }
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) {
            throw new IllegalArgumentException("Truncated JSON at key: " + key);
        }
        int end;
        if (json.charAt(i) == '"') {
            end = i + 1;
            while (end < json.length()) {
                char c = json.charAt(end);
                if (c == '\\' && end + 1 < json.length()) {
                    end += 2;
                    continue;
                }
                if (c == '"') { end++; break; }
                end++;
            }
        } else {
            end = i;
            while (end < json.length() && ",}".indexOf(json.charAt(end)) < 0) end++;
        }
        return json.substring(i, end).trim();
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case '\\' -> sb.append('\\');
                    case '"'  -> sb.append('"');
                    case 'n'  -> sb.append('\n');
                    case 'r'  -> sb.append('\r');
                    case 't'  -> sb.append('\t');
                    case 'u'  -> {
                        if (i + 4 < s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default   -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---- defaults for OS identity --------------------------------------------

    static String defaultUser() {
        String u = System.getProperty("user.name");
        return (u == null || u.isBlank()) ? "unknown" : u;
    }

    static String defaultHostname() {
        try {
            String h = InetAddress.getLocalHost().getHostName();
            if (h != null && !h.isBlank()) {
                return h;
            }
        } catch (UnknownHostException ignored) {
            // fall through
        }
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) return env;
        env = System.getenv("COMPUTERNAME");
        if (env != null && !env.isBlank()) return env;
        return "unknown-host";
    }

    static long defaultPid() {
        try {
            return ProcessHandle.current().pid();
        } catch (UnsupportedOperationException e) {
            return -1L;
        }
    }
}
