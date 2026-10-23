package com.benesquivelmusic.daw.core.persistence.journal;

import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.UnreadProjectItem;
import com.benesquivelmusic.daw.core.project.DawProject;

import java.util.List;
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
 * @param unreadItems        the saved items the read of the checkpoint could
 *                           not honour as written
 *                           ({@link ProjectDeserializer#getUnreadItems()}). A
 *                           project file re-serialized from
 *                           {@code recoveredProject} no longer holds what
 *                           this read dropped or adjusted, but still holds
 *                           the unrecognised-MIDI-effect placeholders, written
 *                           back as saved, which a reopen of it reports
 *                           again; a caller announces this list instead of
 *                           the reopen's, never both, or it counts those
 *                           placeholders twice
 */
public record RecoveryResult(
        DawProject recoveredProject,
        RecoverySummary summary,
        int appliedRecordCount,
        List<UnreadProjectItem> unreadItems) {

    /**
     * Canonical constructor. {@code recoveredProject}, {@code summary} and
     * {@code unreadItems} are required (the list is copied);
     * {@code appliedRecordCount} must be non-negative.
     */
    public RecoveryResult {
        Objects.requireNonNull(recoveredProject, "recoveredProject must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
        if (appliedRecordCount < 0) {
            throw new IllegalArgumentException(
                    "appliedRecordCount must be >= 0: " + appliedRecordCount);
        }
        unreadItems = List.copyOf(Objects.requireNonNull(unreadItems, "unreadItems must not be null"));
    }
}
