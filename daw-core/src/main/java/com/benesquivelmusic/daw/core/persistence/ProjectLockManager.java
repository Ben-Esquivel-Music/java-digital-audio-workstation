package com.benesquivelmusic.daw.core.persistence;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    private final AtomicReference<ProjectLock> heldLock = new AtomicReference<>();
    private final AtomicReference<Path> projectDirectory = new AtomicReference<>();
    private final AtomicReference<LockStatus> status = new AtomicReference<>(LockStatus.NONE);

    private ScheduledExecutorService ownedExecutor;
    private ScheduledFuture<?> heartbeatTask;

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
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.user = Objects.requireNonNull(user, "user must not be null");
        this.hostname = Objects.requireNonNull(hostname, "hostname must not be null");
        this.pid = pid;
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

    /**
     * Attempts to acquire the lock for {@code projectDir}. If the lock is
     * already held by another session, returns an {@link AcquisitionResult}
     * with the existing holder and a {@code stale} flag — no lock is written
     * in that case.
     */
    public AcquisitionResult tryAcquire(Path projectDir) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Path lockFile = projectDir.resolve(LOCK_FILE_NAME);
        Instant now = clock.instant();

        if (Files.exists(lockFile)) {
            ProjectLock existing = readLock(lockFile);
            boolean stale = existing != null && isStale(existing, now);
            return new AcquisitionResult(null, existing, stale);
        }

        ProjectLock lock = ProjectLock.create(user, hostname, pid, now);
        try {
            writeLockAtomically(lockFile, lock, true);
        } catch (java.nio.file.FileAlreadyExistsException race) {
            // Another session created the lock between our exists() check and our write.
            ProjectLock existing = readLock(lockFile);
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
    public ProjectLock forceAcquire(Path projectDir) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Path lockFile = projectDir.resolve(LOCK_FILE_NAME);
        Instant now = clock.instant();

        ProjectLock previous = Files.exists(lockFile) ? readLock(lockFile) : null;
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
    public void openReadOnly(Path projectDir) {
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
     * the project file.</p>
     */
    public void verifyOurs() throws LockStolenException, IOException {
        ProjectLock ours = heldLock.get();
        Path dir = projectDirectory.get();
        if (ours == null || dir == null) {
            return; // nothing held — read-only or no project open
        }
        Path lockFile = dir.resolve(LOCK_FILE_NAME);
        if (!Files.exists(lockFile)) {
            status.set(LockStatus.STOLEN);
            throw new LockStolenException(
                    "Lock file has been deleted; another session may be writing", null);
        }
        ProjectLock onDisk = readLock(lockFile);
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
     * lock has been stolen or the file is gone (status is updated accordingly).
     */
    public boolean heartbeat() {
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
            ProjectLock onDisk = readLock(lockFile);
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
     * Releases the lock if it still belongs to us, deleting the sidecar file.
     * Always stops the heartbeat. Safe to call when nothing is held.
     */
    public synchronized void release() throws IOException {
        stopHeartbeat();
        ProjectLock ours = heldLock.get();
        Path dir = projectDirectory.get();
        if (ours != null && dir != null) {
            Path lockFile = dir.resolve(LOCK_FILE_NAME);
            if (Files.exists(lockFile)) {
                ProjectLock onDisk = readLock(lockFile);
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
    public void close() throws IOException {
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

    /** Reads and parses a lock file, returning {@code null} if it cannot be parsed. */
    static ProjectLock readLock(Path lockFile) throws IOException {
        if (!Files.exists(lockFile)) {
            return null;
        }
        String json = Files.readString(lockFile);
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

    /** Atomic write: write to a temp file alongside, then rename into place. */
    private static void writeLockAtomically(Path lockFile, ProjectLock lock, boolean createNew) throws IOException {
        String json = toJson(lock);
        if (createNew) {
            // Direct CREATE_NEW write — gives us the cross-FS atomic "create-or-fail" we need.
            Files.writeString(lockFile, json,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            return;
        }
        Path tmp = lockFile.resolveSibling(lockFile.getFileName().toString() + ".tmp");
        Files.writeString(tmp, json,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(tmp, lockFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            // Some network filesystems don't support atomic moves; fall back to plain replace.
            Files.move(tmp, lockFile, StandardCopyOption.REPLACE_EXISTING);
        }
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
