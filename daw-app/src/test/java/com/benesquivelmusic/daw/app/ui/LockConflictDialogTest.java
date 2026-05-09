package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.LockConflictResolution;
import com.benesquivelmusic.daw.core.persistence.ProjectLock;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LockConflictDialog} that exercise the dialog's
 * non-UI surface area (content formatting and the null-lock fallback).
 *
 * <p>The interactive {@code resolve(...)} path is not exercised here
 * because it requires a JavaFX toolkit and a user click. The
 * {@code DEFAULT_RESOLUTION} contract is exercised directly so the
 * behaviour is regression-locked.</p>
 */
class LockConflictDialogTest {

    @Test
    void nullLockResolvesToCancel() {
        LockConflictDialog dialog = new LockConflictDialog();

        LockConflictResolution result = dialog.resolve(null, false);

        assertThat(result).isEqualTo(LockConflictResolution.CANCEL);
    }

    @Test
    void contentIncludesHolderIdentityAndAge() {
        Instant openedAt = Instant.parse("2026-05-01T10:00:00Z");
        Instant lastSeen = Instant.parse("2026-05-01T10:01:00Z");
        Instant now = Instant.parse("2026-05-01T10:01:30Z");
        ProjectLock lock = new ProjectLock(
                "lock-id-1", "alice", "studio-mac", 4242, openedAt, lastSeen);
        LockConflictDialog dialog = new LockConflictDialog(() -> now);

        String content = dialog.buildContent(lock, false);

        assertThat(content)
                .contains("alice@studio-mac")
                .contains("pid 4242")
                .contains("30s ago")
                .contains("Open read-only");
    }

    @Test
    void staleContentMentionsCrashHint() {
        Instant openedAt = Instant.parse("2026-05-01T08:00:00Z");
        Instant lastSeen = Instant.parse("2026-05-01T08:05:00Z");
        Instant now = Instant.parse("2026-05-01T10:00:00Z");
        ProjectLock lock = new ProjectLock(
                "lock-id-2", "bob", "nas-host", 1, openedAt, lastSeen);
        LockConflictDialog dialog = new LockConflictDialog(() -> now);

        String content = dialog.buildContent(lock, true);

        assertThat(content)
                .contains("bob@nas-host")
                .contains("not been refreshed");
    }
}
