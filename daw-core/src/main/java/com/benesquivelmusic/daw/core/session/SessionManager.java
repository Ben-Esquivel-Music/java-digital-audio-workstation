package com.benesquivelmusic.daw.core.session;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages the lifecycle of first-class {@link WorkingSession} objects for a
 * project, implementing the §6.6 session-boundary rule and the §7 Stage&nbsp;3
 * graceful fallback from the Project Manager Design Book.
 *
 * <p>When a project is opened, the most recent session is either
 * <em>continued</em> (its last activity is within the configurable session gap,
 * default four hours) or <em>sealed</em> and replaced by a brand-new session.
 * Projects that predate session manifests have their history derived from the
 * existing checkpoint files, grouped by calendar date, so the Session Manager
 * dock always has something to show.</p>
 *
 * <p>All filesystem work is synchronous and lock-free; callers running on
 * virtual threads should invoke these methods off the JavaFX thread.</p>
 */
public final class SessionManager {

    /** Default idle gap after which a re-open starts a new session (§6.6). */
    public static final Duration DEFAULT_SESSION_GAP = Duration.ofHours(4);

    private static final String CHECKPOINT_DIR_NAME = "checkpoints";
    private static final Pattern CHECKPOINT_FILE = Pattern.compile(
            "checkpoint-(\\d+)-(\\d{8}T\\d{6})\\.daw");
    private static final DateTimeFormatter CHECKPOINT_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    private final SessionManifestStore store;
    private final ZoneId zone;
    private final Duration sessionGap;

    /** Creates a manager with the default gap and system time zone. */
    public SessionManager() {
        this(new SessionManifestStore(), ZoneId.systemDefault(), DEFAULT_SESSION_GAP);
    }

    /**
     * Creates a manager with explicit collaborators.
     *
     * @param store      the manifest store
     * @param zone       the zone used for date grouping / default names
     * @param sessionGap the idle gap after which a re-open starts a new session
     */
    public SessionManager(SessionManifestStore store, ZoneId zone, Duration sessionGap) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.zone = Objects.requireNonNull(zone, "zone must not be null");
        this.sessionGap = Objects.requireNonNull(sessionGap, "sessionGap must not be null");
        if (sessionGap.isNegative()) {
            throw new IllegalArgumentException("sessionGap must not be negative");
        }
    }

    /** {@return the manifest store backing this manager} */
    public SessionManifestStore store() {
        return store;
    }

    /**
     * Opens a working session for the project, applying the §6.6 boundary rule.
     *
     * <p>If the latest manifest is still active and its start is within the
     * session gap of {@code now}, that session is continued. Otherwise any
     * dangling active session is sealed and a fresh session is created and
     * persisted.</p>
     *
     * @param projectDir the project directory
     * @param now        the instant the project is being opened
     * @return the open result describing the current and (optionally) sealed
     *         prior session
     * @throws IOException if the manifests cannot be read or written
     */
    public SessionOpenResult openSession(Path projectDir, Instant now) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Objects.requireNonNull(now, "now must not be null");

        List<WorkingSession> existing = store.listSessions(projectDir);
        if (existing.isEmpty()) {
            WorkingSession created = WorkingSession.start(now, zone);
            store.write(projectDir, created);
            return new SessionOpenResult(created, false, Optional.empty());
        }

        WorkingSession latest = existing.getFirst();
        Instant lastActivity = latest.endTime() != null ? latest.endTime() : latest.startTime();
        // Continue only a still-open session whose last activity is recent.
        if (latest.isActive() && Duration.between(lastActivity, now).compareTo(sessionGap) <= 0) {
            return new SessionOpenResult(latest, true, Optional.empty());
        }

        Optional<WorkingSession> sealed = Optional.empty();
        if (latest.isActive()) {
            WorkingSession sealedSession = latest.sealed(now);
            store.write(projectDir, sealedSession);
            sealed = Optional.of(sealedSession);
        }
        WorkingSession created = WorkingSession.start(now, zone);
        store.write(projectDir, created);
        return new SessionOpenResult(created, false, sealed);
    }

    /**
     * Seals the given session at {@code endInstant} and persists the closed
     * manifest.
     *
     * @param projectDir the project directory
     * @param session    the session to close
     * @param endInstant the closing instant
     * @return the sealed session
     * @throws IOException if the manifest cannot be written
     */
    public WorkingSession closeSession(Path projectDir, WorkingSession session, Instant endInstant)
            throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(endInstant, "endInstant must not be null");
        WorkingSession sealed = session.sealed(endInstant);
        store.write(projectDir, sealed);
        return sealed;
    }

    /**
     * Returns the project's full session history for the Session Manager dock,
     * newest-first. When the project has no manifests this falls back to
     * synthesising sessions from checkpoint timestamps grouped by date
     * (design book §7 Stage&nbsp;3).
     *
     * @param projectDir the project directory
     * @return the session history, possibly synthesised, newest-first
     * @throws IOException if the project directory cannot be read
     */
    public List<WorkingSession> history(Path projectDir) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        List<WorkingSession> manifests = store.listSessions(projectDir);
        if (!manifests.isEmpty()) {
            return manifests;
        }
        return sessionsFromCheckpoints(projectDir);
    }

    private List<WorkingSession> sessionsFromCheckpoints(Path projectDir) throws IOException {
        Path checkpointDir = projectDir.resolve(CHECKPOINT_DIR_NAME);
        if (!Files.isDirectory(checkpointDir)) {
            return List.of();
        }
        // Preserve insertion order keyed by date; values accumulate per day.
        Map<LocalDate, List<CheckpointRef>> byDate = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(checkpointDir, "checkpoint-*.daw")) {
            for (Path file : stream) {
                Matcher m = CHECKPOINT_FILE.matcher(file.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                Instant ts;
                try {
                    LocalDateTime ldt = LocalDateTime.parse(m.group(2), CHECKPOINT_TS);
                    ts = ldt.atZone(zone).toInstant();
                } catch (RuntimeException e) {
                    continue;
                }
                LocalDate date = LocalDate.ofInstant(ts, zone);
                byDate.computeIfAbsent(date, d -> new ArrayList<>())
                        .add(new CheckpointRef(file.getFileName().toString(), ts));
            }
        }

        List<WorkingSession> sessions = new ArrayList<>(byDate.size());
        for (Map.Entry<LocalDate, List<CheckpointRef>> entry : byDate.entrySet()) {
            List<CheckpointRef> refs = entry.getValue();
            refs.sort(Comparator.comparing(CheckpointRef::timestamp));
            Instant start = refs.getFirst().timestamp();
            Instant end = refs.getLast().timestamp();
            List<String> checkpointIds = refs.stream().map(CheckpointRef::fileName).toList();
            WorkingSession synthesised = new WorkingSession(
                    "checkpoints-" + entry.getKey(),
                    WorkingSession.defaultName(entry.getKey()),
                    start,
                    end,
                    Duration.ZERO,
                    List.of(),
                    checkpointIds,
                    List.of(),
                    "");
            sessions.add(synthesised);
        }
        sessions.sort(Comparator.comparing(WorkingSession::startTime).reversed());
        return List.copyOf(sessions);
    }

    private record CheckpointRef(String fileName, Instant timestamp) {
    }

    /**
     * The outcome of {@link #openSession(Path, Instant)}.
     *
     * @param current   the session the user is now working in
     * @param continued {@code true} if {@code current} is a continuation of a
     *                  prior still-open session rather than a new one
     * @param sealed    the prior session that was sealed during this open, if any
     */
    public record SessionOpenResult(
            WorkingSession current,
            boolean continued,
            Optional<WorkingSession> sealed) {

        /** Canonical constructor enforcing non-null fields. */
        public SessionOpenResult {
            Objects.requireNonNull(current, "current must not be null");
            Objects.requireNonNull(sealed, "sealed must not be null");
        }
    }
}
