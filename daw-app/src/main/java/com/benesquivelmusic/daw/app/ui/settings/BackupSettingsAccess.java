package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The Backups-category access seam (story 308, Settings View Design
 * Book §3.3 / §5.8 / §7 Stage 4) — the retention policy and disk-usage
 * inputs the absorbed {@code BackupSettingsDialog} used to receive from
 * {@code BackupRetentionController.openDialog}.
 *
 * <p>Implemented over {@code BackupRetentionController}:
 * {@link #currentPolicy()} reads {@code ~/.daw/backup-retention.json}
 * (falling back to {@link BackupRetentionPolicy#DEFAULT});
 * {@link #applyPolicy(BackupRetentionPolicy)} persists and prunes
 * asynchronously on the controller's scheduler thread, exactly like the
 * old dialog's Apply.</p>
 */
public interface BackupSettingsAccess {

    /** @return the currently persisted retention policy */
    BackupRetentionPolicy currentPolicy();

    /**
     * @return the open project's directory (the §5.8 disk-usage pie
     *         source), or empty when no project is open
     */
    Optional<Path> projectDirectory();

    /**
     * Persists and applies an edited policy (asynchronously — the write
     * and the immediate prune run off the FX thread).
     *
     * @param policy the edited policy; not {@code null}
     */
    void applyPolicy(BackupRetentionPolicy policy);
}
