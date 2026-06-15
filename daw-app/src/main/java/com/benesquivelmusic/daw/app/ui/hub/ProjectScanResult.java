package com.benesquivelmusic.daw.app.ui.hub;

import com.benesquivelmusic.daw.core.persistence.backup.ProjectDiskUsage;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * The immutable output of one quick, on-open project scan for the story-296
 * Project Hub / Welcome screen (see {@link ProjectHealthScanner}). JavaFX-free
 * so the scan can be produced entirely off the FX thread on a background
 * virtual thread, then applied to a {@link ProjectCard} on the FX thread via
 * {@link ProjectCard#applyScanResult(ProjectScanResult)}.
 *
 * <p>Several fields use {@code -1} or {@code null} as an explicit "unknown"
 * sentinel rather than throwing on a partial scan: a project whose disk-usage
 * walk failed still produces a card (with {@code diskUsage == null}); a Stage-2
 * project that has no {@code sessions/} directory yet reports
 * {@code sessionCount == -1}.</p>
 *
 * @param projectDir          the scanned project directory; never {@code null}
 * @param name                the project display name; coerced to {@code ""} when {@code null}
 * @param lastOpened          when the project was last opened/modified, or {@code null} if unknown
 * @param diskUsage           the on-disk size breakdown, or {@code null} when the scan failed
 * @param sessionCount        number of sessions, or {@code -1} when unknown (Stage 2 has no sessions)
 * @param health              the health badge; coerced to {@link HealthBadge#HEALTHY} when {@code null}
 * @param missingAssetCount   number of missing referenced assets ({@code 0} in Stage 2)
 * @param schemaVersion       the schema version this build considers current (post-migration target), or {@code -1} when unknown
 * @param backupSchemaVersion the schema version of the newest pre-migration backup, or {@code -1} when none
 * @param lockHolderLabel     {@code "user @ host"} when a lock is present, else {@code ""} (coerced from {@code null})
 * @param lockPresent         whether a {@code .project.lock} sidecar exists
 * @param lockStale           whether the present lock is stale (past the heartbeat threshold)
 * @param recoverable         {@code lockPresent && lockStale} — the project did not exit cleanly
 */
public record ProjectScanResult(
        Path projectDir,
        String name,
        Instant lastOpened,
        ProjectDiskUsage diskUsage,
        int sessionCount,
        HealthBadge health,
        int missingAssetCount,
        int schemaVersion,
        int backupSchemaVersion,
        String lockHolderLabel,
        boolean lockPresent,
        boolean lockStale,
        boolean recoverable
) {
    public ProjectScanResult {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        if (name == null) {
            name = "";
        }
        if (health == null) {
            health = HealthBadge.HEALTHY;
        }
        if (lockHolderLabel == null) {
            lockHolderLabel = "";
        }
    }

    /**
     * @return the total on-disk size in bytes, or {@code -1} when the disk-usage
     *         scan failed ({@code diskUsage == null})
     */
    public long sizeOnDiskBytes() {
        return diskUsage == null ? -1L : diskUsage.totalBytes();
    }
}
