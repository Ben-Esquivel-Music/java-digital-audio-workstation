package com.benesquivelmusic.daw.app.ui.hub;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.persistence.ProjectLock;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.persistence.ProjectMetadata;
import com.benesquivelmusic.daw.core.persistence.ProjectMetadataReader;
import com.benesquivelmusic.daw.core.persistence.backup.ProjectDiskUsage;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Produces a {@link ProjectScanResult} for a project directory — the quick
 * on-open scan that populates a {@link ProjectCard} in the story-296 Project
 * Hub / Welcome screen.
 *
 * <h2>Off the FX thread ({@code javafx-application-design} §11)</h2>
 *
 * <p>{@link #scanBlocking(Path, InstantSource)} performs all the I/O on the
 * calling thread and never touches JavaFX. {@link #scan(Path, Consumer)} spawns
 * a background <strong>virtual thread</strong> ({@code Thread.ofVirtual()}),
 * runs {@code scanBlocking} there, then marshals the result to the FX-thread
 * consumer through the story-289 {@link FxDispatcher} — exactly the shape of
 * {@code SessionStatusDiskScanner.scanNow()}. {@link #scanInto(Path, ProjectCard)}
 * is the common case: scan, then apply onto a card on the FX thread.</p>
 *
 * <h2>Stage 2 scope</h2>
 *
 * <p>This stage scans name + last-opened (via {@link ProjectMetadataReader}),
 * on-disk size (via {@link ProjectDiskUsage}), the advisory lock (via
 * {@link ProjectLockManager#peekLock(Path)} — no side effect), and the newest
 * pre-migration backup ({@code project.daw.v<n>.<stamp>.bak}). The session
 * count is reported as unknown ({@code -1}) and the missing-asset count as
 * {@code 0}; both are filled in by a later stage.</p>
 */
public final class ProjectHealthScanner {

    private static final String PROJECT_FILE_NAME = "project.daw";

    /**
     * Matches a pre-migration backup sibling written by
     * {@code ProjectManager.writeMigrationBackup}:
     * {@code project.daw.v<digits>.<stamp>.bak} (optionally
     * {@code …-<n>.bak} on a same-second collision). Group 1 is the version;
     * group 2 is the {@code yyyyMMdd-HHmmss[-<n>]} stamp, which is
     * lexicographically time-ordered.
     */
    private static final Pattern BACKUP_PATTERN =
            Pattern.compile(Pattern.quote(PROJECT_FILE_NAME) + "\\.v(\\d+)\\.(.+)\\.bak");

    private final FxDispatcher dispatcher;

    /**
     * Creates a scanner that marshals scan results through {@code dispatcher}.
     *
     * @param dispatcher the FX marshalling seam; must not be {@code null}
     */
    public ProjectHealthScanner(FxDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
    }

    /**
     * Performs a full project scan on the <strong>calling</strong> thread (all
     * I/O; never touches JavaFX). Robust: a failed disk-usage walk yields a
     * {@code null} {@code diskUsage} rather than throwing.
     *
     * @param projectDir the project directory to scan; must not be {@code null}
     * @param clock      the clock used for stale-lock classification; must not be {@code null}
     * @return the scan result (never {@code null})
     */
    public static ProjectScanResult scanBlocking(Path projectDir, InstantSource clock) {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Objects.requireNonNull(clock, "clock must not be null");

        // ── name + lastOpened ──────────────────────────────────────────────
        Optional<ProjectMetadata> metadata = ProjectMetadataReader.read(projectDir);
        String name = metadata
                .map(ProjectMetadata::name)
                .filter(n -> !n.isBlank())
                .orElseGet(() -> HubFormat.baseName(projectDir));
        Instant lastOpened = metadata
                .map(ProjectMetadata::lastModified)
                .orElseGet(() -> lastModifiedInstant(projectDir.resolve(PROJECT_FILE_NAME)));

        // ── disk usage ─────────────────────────────────────────────────────
        ProjectDiskUsage diskUsage;
        try {
            diskUsage = ProjectDiskUsage.compute(projectDir);
        } catch (IOException e) {
            diskUsage = null; // best-effort — a card still renders
        }

        // ── lock (side-effect-free peek) ───────────────────────────────────
        Optional<ProjectLock> lock = ProjectLockManager.peekLock(projectDir);
        boolean lockPresent = lock.isPresent();
        boolean lockStale = lock
                .map(l -> ProjectLockManager.isStale(l, clock.instant()))
                .orElse(false);
        String lockHolderLabel = lock
                .map(l -> l.user() + " @ " + l.hostname())
                .orElse("");

        // ── newest pre-migration backup ────────────────────────────────────
        int backupSchemaVersion = newestBackupSchemaVersion(projectDir);

        int schemaVersion = MigrationRegistry.CURRENT_VERSION;
        HealthBadge health = (backupSchemaVersion >= 0) ? HealthBadge.MIGRATED : HealthBadge.HEALTHY;
        int missingAssetCount = 0;          // Stage 2
        int sessionCount = -1;              // Stage 2 — no sessions/ yet
        boolean recoverable = lockPresent && lockStale;

        return new ProjectScanResult(
                projectDir, name, lastOpened, diskUsage, sessionCount, health,
                missingAssetCount, schemaVersion, backupSchemaVersion,
                lockHolderLabel, lockPresent, lockStale, recoverable);
    }

    /**
     * Spawns one scan on a fresh background <strong>virtual</strong> thread and
     * returns it (so callers — and tests — can join on completion). The scan
     * runs {@link #scanBlocking(Path, InstantSource)} off the FX thread and
     * marshals the result to {@code fxConsumer} via {@link FxDispatcher#onFx(Runnable)}.
     *
     * @param projectDir the project directory to scan; must not be {@code null}
     * @param fxConsumer the FX-thread consumer of the result; must not be {@code null}
     * @return the started virtual scan thread
     */
    public Thread scan(Path projectDir, Consumer<ProjectScanResult> fxConsumer) {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Objects.requireNonNull(fxConsumer, "fxConsumer must not be null");
        Thread t = Thread.ofVirtual().name("daw-project-health-scan").unstarted(() -> {
            ProjectScanResult r = scanBlocking(projectDir, InstantSource.system());
            dispatcher.onFx(() -> fxConsumer.accept(r));
        });
        t.start();
        return t;
    }

    /**
     * Scans {@code projectDir} on a background virtual thread and applies the
     * result onto {@code card} on the FX thread.
     *
     * @param projectDir the project directory to scan; must not be {@code null}
     * @param card       the card to populate; must not be {@code null}
     * @return the started virtual scan thread
     */
    public Thread scanInto(Path projectDir, ProjectCard card) {
        Objects.requireNonNull(card, "card must not be null");
        return scan(projectDir, card::applyScanResult);
    }

    /**
     * Cheap synchronous recover classification — a single small lock-file read
     * (NOT a tree walk). Used by the Welcome view to place a card in the
     * "Recover" group vs the "Continue" group deterministically.
     *
     * @param projectDir the project directory; must not be {@code null}
     * @param clock      the clock for the stale comparison; must not be {@code null}
     * @return {@code true} when a stale lock is present (did not exit cleanly)
     */
    public static boolean isRecoverable(Path projectDir, InstantSource clock) {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        return ProjectLockManager.peekLock(projectDir)
                .map(l -> ProjectLockManager.isStale(l, clock.instant()))
                .orElse(false);
    }

    /**
     * Scans {@code projectDir} for {@code project.daw.v<n>.<stamp>.bak} siblings,
     * returning the version of the <em>newest</em> backup (the one the user most
     * recently migrated from), or {@code -1} when none exist.
     *
     * <p>Newest is determined by the {@code yyyyMMdd-HHmmss[-<n>]} stamp (regex
     * group 2), which is lexicographically time-ordered — <strong>not</strong>
     * by the whole filename: the leading {@code vN} segment would sort
     * {@code v10} before {@code v2} and so report the wrong version when a
     * long-lived project carries backups from more than one schema version.</p>
     */
    private static int newestBackupSchemaVersion(Path projectDir) {
        String newestTime = null;
        int newestCollision = -1;
        int versionOfNewest = -1;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(projectDir)) {
            for (Path entry : stream) {
                Path fileName = entry.getFileName();
                if (fileName == null) {
                    continue;
                }
                Matcher matcher = BACKUP_PATTERN.matcher(fileName.toString());
                if (!matcher.matches()) {
                    continue;
                }
                String stamp = matcher.group(2);
                String time = stamp;
                int collision = 0;
                int suffixIndex = stamp.indexOf('-', 15); // "yyyyMMdd-HHmmss" is 15 chars
                if (suffixIndex > 0) {
                    time = stamp.substring(0, suffixIndex);
                    collision = parseVersion(stamp.substring(suffixIndex + 1));
                }
                if (newestTime == null || time.compareTo(newestTime) > 0
                        || (time.equals(newestTime) && collision > newestCollision)) {
                    newestTime = time;
                    newestCollision = collision;
                    versionOfNewest = parseVersion(matcher.group(1));
                }
            }
        }
        } catch (IOException | RuntimeException e) {
            return -1; // unreadable directory — no backup info
        }
        return versionOfNewest;
    }

    /** Parses a backup's version digits, or {@code -1} for a pathologically long run. */
    private static int parseVersion(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Instant lastModifiedInstant(Path file) {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            FileTime time = Files.getLastModifiedTime(file);
            return time.toInstant();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
