package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The Backups-category access seam (story 308, Settings View Design
 * Book §3.3 / §5.8 / §7 Stage 4) — the checkpoint retention policy and
 * disk-usage inputs of Settings ▸ Backups.
 *
 * <p>Implemented by {@code BackupRetentionController.settingsAccess}
 * (story 335): {@link #currentPolicy()} is the policy the checkpoint
 * manager enforces; {@link #storedPolicyUnreadable()} reports the hold
 * on an unreadable stored policy; {@link #applyPolicy(BackupRetentionPolicy)} delivers
 * an edited policy to the checkpoint manager now and saves it to
 * {@code ~/.daw/backup-retention.json} off the FX thread. Applying deletes
 * nothing: the policy applies after the open project's next checkpoint.</p>
 */
public interface BackupSettingsAccess {

    /**
     * @return the policy the checkpoint manager enforces, or
     *         {@link BackupRetentionPolicy#DEFAULT} while the stored policy
     *         could not be read (nothing is deleted until one is applied)
     */
    BackupRetentionPolicy currentPolicy();

    /**
     * Whether the stored policy could not be read and no policy was applied
     * since — "the hold" (story 335, studio ruling-3 Q1). While it lasts, a
     * policy that keeps every checkpoint is in effect, so nothing is
     * deleted, and {@link #currentPolicy()} returns
     * {@link BackupRetentionPolicy#DEFAULT}: the rows show a proposal, not
     * the policy in effect. Settings therefore arms the six Backups rows
     * once their pane is displayed (or a Restore All Defaults is confirmed),
     * so an Apply or OK delivers the values they show through
     * {@link #applyPolicy}, which ends the hold.
     *
     * @return whether the hold is active
     */
    boolean storedPolicyUnreadable();

    /**
     * @return the open project's directory (the §5.8 disk-usage pie
     *         source), or empty when no project is open
     */
    Optional<Path> projectDirectory();

    /**
     * Delivers an edited policy to the checkpoint manager now and saves it
     * off the FX thread; it deletes nothing.
     *
     * @param policy the edited policy; not {@code null}
     */
    void applyPolicy(BackupRetentionPolicy policy);
}
