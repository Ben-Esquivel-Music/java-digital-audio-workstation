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
        assertThat(indicator.getStyleClass()).containsExactly("label", "lock-status-indicator");
        assertThat(indicator.getStyle()).as("story 333: no inline style").isEmpty();
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
        assertThat(indicator.getStyleClass()).containsExactly("label", "lock-status-indicator", "lock-held");
        assertThat(indicator.getStyle()).as("story 333: the fill is the stylesheet's, by role").isEmpty();
    }

    @Test
    void readOnlyStatusRendersReadOnlyBadge() throws Exception {
        LockStatusIndicator indicator = onFx(() -> {
            LockStatusIndicator i = new LockStatusIndicator();
            i.show(LockStatus.READ_ONLY, null);
            return i;
        });

        assertThat(indicator.getText()).contains("Read-only");
        assertThat(indicator.getStyleClass()).containsExactly("label", "lock-status-indicator", "lock-read-only");
        assertThat(indicator.getStyle()).isEmpty();
    }

    @Test
    void stolenStatusRendersWarningBadge() throws Exception {
        LockStatusIndicator indicator = onFx(() -> {
            LockStatusIndicator i = new LockStatusIndicator();
            i.show(LockStatus.STOLEN, null);
            return i;
        });

        assertThat(indicator.getText()).contains("Lock stolen");
        assertThat(indicator.getStyleClass()).containsExactly("label", "lock-status-indicator", "lock-stolen");
        assertThat(indicator.getStyle()).isEmpty();
        assertThat(indicator.getTooltip().getText()).as("ruling R7: it names File ▸ Save As…").isEqualTo(
                "Another session has taken over this project's lock. Use File ▸ Save As… to keep your changes in a"
                        + " new project.");
    }

    @Test
    void aStatusChangeLeavesExactlyOneStateClass() throws Exception {
        LockStatusIndicator indicator = onFx(() -> {
            LockStatusIndicator i = new LockStatusIndicator();
            i.show(LockStatus.HELD, null);
            i.show(LockStatus.STOLEN, null);
            i.show(LockStatus.READ_ONLY, null);
            return i;
        });
        assertThat(indicator.getStyleClass()).containsExactly("label", "lock-status-indicator", "lock-read-only");

        onFx(() -> {
            indicator.show(LockStatus.NONE, null);
            return indicator;
        });
        assertThat(indicator.getStyleClass()).containsExactly("label", "lock-status-indicator");
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
