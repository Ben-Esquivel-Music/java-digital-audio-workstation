package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.LockStatus;
import com.benesquivelmusic.daw.core.persistence.ProjectLock;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link LockStatusIndicator} — Story 187 title-bar lock badge.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class LockStatusIndicatorTest {

    @Test
    void noneStatusRendersEmptyLabel() throws Exception {
        LockStatusIndicator indicator = onFx(() -> {
            LockStatusIndicator i = new LockStatusIndicator();
            i.show(LockStatus.NONE, null);
            return i;
        });

        assertThat(indicator.getText()).isEmpty();
    }

    @Test
    void heldStatusRendersLockedBadge() throws Exception {
        ProjectLock lock = new ProjectLock("id", "alice", "studio-mac", 1234,
                Instant.parse("2026-05-01T10:00:00Z"),
                Instant.parse("2026-05-01T10:00:00Z"));
        LockStatusIndicator indicator = onFx(() -> {
            LockStatusIndicator i = new LockStatusIndicator();
            i.show(LockStatus.HELD, lock);
            return i;
        });

        assertThat(indicator.getText()).contains("Locked");
        assertThat(indicator.getTooltip().getText()).contains("alice").contains("studio-mac");
    }

    @Test
    void readOnlyStatusRendersReadOnlyBadge() throws Exception {
        LockStatusIndicator indicator = onFx(() -> {
            LockStatusIndicator i = new LockStatusIndicator();
            i.show(LockStatus.READ_ONLY, null);
            return i;
        });

        assertThat(indicator.getText()).contains("Read-only");
    }

    @Test
    void stolenStatusRendersWarningBadge() throws Exception {
        LockStatusIndicator indicator = onFx(() -> {
            LockStatusIndicator i = new LockStatusIndicator();
            i.show(LockStatus.STOLEN, null);
            return i;
        });

        assertThat(indicator.getText()).contains("Lock stolen");
    }

    @Test
    void refreshFromLockManagerReflectsHeldStatus(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path projectDir = Files.createDirectory(tmp.resolve("p"));
        ProjectLockManager mgr = new ProjectLockManager();
        try {
            ProjectLockManager.AcquisitionResult ar = mgr.tryAcquire(projectDir);
            assertThat(ar.wasAcquired()).isTrue();
            LockStatusIndicator indicator = onFx(() -> {
                LockStatusIndicator i = new LockStatusIndicator();
                i.refresh(mgr);
                return i;
            });
            assertThat(indicator.getText()).contains("Locked");
        } finally {
            mgr.release();
        }
    }

    private static <T> T onFx(java.util.function.Supplier<T> supplier) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(supplier.get());
            } catch (Throwable t) {
                err.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX task timed out");
        }
        if (err.get() != null) {
            if (err.get() instanceof Exception ex) throw ex;
            throw new RuntimeException(err.get());
        }
        return ref.get();
    }
}
