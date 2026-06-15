package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.hub.HealthBadge;
import com.benesquivelmusic.daw.app.ui.hub.ProjectCard;
import com.benesquivelmusic.daw.app.ui.hub.ProjectScanResult;
import com.benesquivelmusic.daw.core.persistence.backup.ProjectDiskUsage;

import javafx.application.Platform;
import javafx.scene.Scene;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link ProjectCard}'s skin reflects its properties (the
 * binding-only meta/health labels) and that the {@link HealthBadge} style-class
 * reconciliation is headless-safe (asserted on {@code getStyleClass()} without
 * a realized skin).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ProjectCardStateBindingTest {

    @Test
    void skinReflectsNameSizeAndSessionProperties() throws Exception {
        runOnFxThread(() -> {
            ProjectCard card = new ProjectCard();
            realize(card);

            card.setName("Midnight Mix");
            // HubFormat.formatBytes (mirroring SessionStatusStripSkin) renders
            // the GB band as whole units, so 2 GiB → "2 GB".
            card.setSizeOnDiskBytes(2L * 1024 * 1024 * 1024); // 2 GB
            card.setLastOpened(Instant.now());
            card.setSessionCount(12);

            assertThat(card.nameLabel().getText()).isEqualTo("Midnight Mix");
            // Meta line: "<relative> · 2 GB · 12 sess."
            assertThat(card.metaLabel().getText()).contains("2 GB");
            assertThat(card.metaLabel().getText()).contains("12 sess.");
        });
    }

    @Test
    void metaLineUsesOneDecimalAtTerabyteScale() throws Exception {
        runOnFxThread(() -> {
            ProjectCard card = new ProjectCard();
            realize(card);

            // 1.5 TiB exercises the only one-decimal band of formatBytes.
            card.setSizeOnDiskBytes((long) (1.5 * 1024 * 1024 * 1024 * 1024));

            assertThat(card.metaLabel().getText()).contains("1.5 TB");
        });
    }

    @Test
    void metaLineOmitsSessionFragmentWhenUnknown() throws Exception {
        runOnFxThread(() -> {
            ProjectCard card = new ProjectCard();
            realize(card);

            card.setSizeOnDiskBytes(-1L);          // unknown size → "—"
            card.setSessionCount(-1);              // unknown → no "sess." fragment

            assertThat(card.metaLabel().getText()).doesNotContain("sess.");
            assertThat(card.metaLabel().getText()).contains("—");
        });
    }

    @Test
    void healthLabelShowsMigratedVersionDetail() throws Exception {
        runOnFxThread(() -> {
            ProjectCard card = new ProjectCard();
            realize(card);

            card.setHealth(HealthBadge.MIGRATED);
            card.setBackupSchemaVersion(3);
            card.setSchemaVersion(5);

            assertThat(card.healthLabel().getText()).isEqualTo("⚠ migrated v3→v5");
        });
    }

    @Test
    void healthLabelShowsMissingAssetCount() throws Exception {
        runOnFxThread(() -> {
            ProjectCard card = new ProjectCard();
            realize(card);

            card.setHealth(HealthBadge.MISSING_ASSETS);
            card.setMissingAssetCount(4);

            assertThat(card.healthLabel().getText()).isEqualTo("⚠ 4 missing assets");
        });
    }

    @Test
    void healthBadgeReconcilesStyleClassesHeadlessSafe() throws Exception {
        // No Scene / skin needed — the style-class flip happens on the control
        // itself in healthProperty().invalidated().
        runOnFxThread(() -> {
            ProjectCard card = new ProjectCard();

            card.setHealth(HealthBadge.MIGRATED);
            assertThat(card.getStyleClass())
                    .contains("project-card-migrated")
                    .doesNotContain("project-card-healthy", "project-card-missing");

            card.setHealth(HealthBadge.MISSING_ASSETS);
            assertThat(card.getStyleClass())
                    .contains("project-card-missing")
                    .doesNotContain("project-card-healthy", "project-card-migrated");

            card.setHealth(HealthBadge.HEALTHY);
            assertThat(card.getStyleClass())
                    .contains("project-card-healthy")
                    .doesNotContain("project-card-migrated", "project-card-missing");

            // null coerces to HEALTHY.
            card.setHealth(null);
            assertThat(card.getHealth()).isEqualTo(HealthBadge.HEALTHY);
            assertThat(card.getStyleClass()).contains("project-card-healthy");
        });
    }

    @Test
    void selectedAndPinnedReconcileStyleClassesHeadlessSafe() throws Exception {
        runOnFxThread(() -> {
            ProjectCard card = new ProjectCard();

            assertThat(card.getStyleClass()).doesNotContain("project-card-selected");
            card.setSelected(true);
            assertThat(card.getStyleClass()).contains("project-card-selected");
            card.setSelected(false);
            assertThat(card.getStyleClass()).doesNotContain("project-card-selected");

            card.setPinned(true);
            assertThat(card.getStyleClass()).contains("project-card-pinned");
            card.setPinned(false);
            assertThat(card.getStyleClass()).doesNotContain("project-card-pinned");
        });
    }

    @Test
    void applyScanResultSetsDocumentedProperties() throws Exception {
        runOnFxThread(() -> {
            ProjectCard card = new ProjectCard();
            realize(card);
            // Pinned/selected are view state and must be left untouched by apply.
            card.setPinned(true);
            card.setSelected(true);

            Instant opened = Instant.parse("2026-05-01T09:00:00Z");
            ProjectDiskUsage usage = new ProjectDiskUsage(100L, 200L, 700L); // 1000 bytes total
            ProjectScanResult scan = new ProjectScanResult(
                    Path.of("ignored"), "Scanned Project", opened, usage,
                    7, HealthBadge.MIGRATED, 2, 5, 4,
                    "alice @ studio-mac", true, true, true);

            card.applyScanResult(scan);

            assertThat(card.getName()).isEqualTo("Scanned Project");
            assertThat(card.getLastOpened()).isEqualTo(opened);
            assertThat(card.getSizeOnDiskBytes()).isEqualTo(1000L);
            assertThat(card.getSessionCount()).isEqualTo(7);
            assertThat(card.getMissingAssetCount()).isEqualTo(2);
            assertThat(card.getSchemaVersion()).isEqualTo(5);
            assertThat(card.getBackupSchemaVersion()).isEqualTo(4);
            assertThat(card.getLockHolderLabel()).isEqualTo("alice @ studio-mac");
            assertThat(card.isLockStale()).isTrue();
            assertThat(card.getHealth()).isEqualTo(HealthBadge.MIGRATED);
            // Untouched view state.
            assertThat(card.isPinned()).isTrue();
            assertThat(card.isSelected()).isTrue();
        });
    }

    /** Attaches the card to a Scene and realizes its skin so the labels exist. */
    private static void realize(ProjectCard card) {
        Scene scene = new Scene(card);
        card.applyCss();
        card.layout();
        // Touch the scene so static analysis doesn't flag it unused; the
        // attachment is the load-bearing effect (the skin realizes on applyCss).
        assertThat(scene.getRoot()).isSameAs(card);
    }

    /**
     * Runs {@code action} on the FX thread and rethrows any assertion error it
     * raises (per the headless test discipline — FX would otherwise swallow it).
     */
    private static void runOnFxThread(Runnable action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timed out waiting for JavaFX action to complete");
        }
        if (error.get() != null) {
            throw new RuntimeException(error.get());
        }
    }
}
