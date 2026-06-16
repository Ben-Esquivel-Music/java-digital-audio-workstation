package com.benesquivelmusic.daw.core.persistence.journal;

import com.benesquivelmusic.daw.core.project.DawProject;

import java.util.Objects;

/**
 * Story 298 — the outcome of a successful structured crash-recovery.
 *
 * <p>The {@code recoveredProject} is a <em>fresh</em> {@link DawProject}
 * deserialised from the newest checkpoint and then advanced by replaying the
 * journal records — it is never the caller's live project, so a failed
 * recovery (which throws {@link JournalRecoveryException} instead of returning
 * this) can never leave the live project half-applied.</p>
 *
 * @param recoveredProject   the rebuilt project (checkpoint + replayed journal)
 * @param summary            the summary describing what was recovered
 * @param appliedRecordCount the number of journal records applied during replay
 */
public record RecoveryResult(
        DawProject recoveredProject,
        RecoverySummary summary,
        int appliedRecordCount) {

    /**
     * Canonical constructor. {@code recoveredProject} and {@code summary} are
     * required; {@code appliedRecordCount} must be non-negative.
     */
    public RecoveryResult {
        Objects.requireNonNull(recoveredProject, "recoveredProject must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
        if (appliedRecordCount < 0) {
            throw new IllegalArgumentException(
                    "appliedRecordCount must be >= 0: " + appliedRecordCount);
        }
    }
}
