package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationReport;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.persistence.journal.JournalConfig;
import com.benesquivelmusic.daw.core.persistence.journal.JournalState;
import com.benesquivelmusic.daw.core.persistence.journal.JournalWriter;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.sdk.session.SessionData;
import com.benesquivelmusic.daw.sdk.session.SessionImportResult;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(JavaFxToolkitExtension.class)
class ProjectReplacementJournalSafetyTest {
    @TempDir Path workspace;

    @Test void newProjectKeepsItsGuardUntilFullXmlIsPublishedAndTheJournalIsReady() throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT.withEnabled(false))) {
            Publishes.Hold head = fixture.publishes.holdNext(target -> isHead(target) && Files.exists(target));
            CountDownLatch journalStarted = new CountDownLatch(1);
            CountDownLatch releaseJournal = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            runOnFx(() -> {
                fixture.lifecycle().enableRecoveryGate(() -> true, () -> {
                    assertThat(fixture.lifecycle().isProjectInstallationPending()).isTrue();
                    return () -> { };
                }, folder -> {
                    try (JournalWriter writer = new JournalWriter(ProjectContext.forProject(folder), JournalConfig.DEFAULT, true, null)) {
                        writer.start();
                        String base = Files.readString(folder.resolve("journal").resolve(JournalState.BASE_FILE));
                        assertThat(new ProjectDeserializer().deserialize(base).getName()).isEqualTo("Untitled Project");
                        journalStarted.countDown();
                        await(releaseJournal);
                    } catch (Throwable thrown) { failure.set(thrown); journalStarted.countDown(); }
                });
                fixture.lifecycle().setNewProjectLocationPrompt(() -> workspace.resolve("created"));
                fixture.lifecycle().onNewProject();
            });
            try {
                head.awaitHeld();
                assertThat(onFx(() -> fixture.lifecycle().isProjectInstallationPending())).isTrue();
                assertThat(journalStarted.getCount()).isEqualTo(1);
                assertThat(fixture.projectManager.getCurrentProject().projectPath().resolve("journal")).doesNotExist();
                head.release();
                assertThat(journalStarted.await(15, TimeUnit.SECONDS)).isTrue();
                if (failure.get() != null) throw new AssertionError(failure.get());
                assertThat(onFx(() -> fixture.lifecycle().isProjectInstallationPending())).isTrue();
            } finally { head.release(); releaseJournal.countDown(); }
            fixture.awaitOnFx(() -> !fixture.lifecycle().isProjectInstallationPending(), "new journal ready");
            assertThat(fixture.notifications()).extracting(NotificationEntry::message).contains("New project created");
        }
    }

    @Test void failedInitialXmlDoesNotFreezeTheMetadataOnlyHeadAsAReplayBase() throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT.withEnabled(false))) {
            AtomicInteger opens = new AtomicInteger();
            fixture.publishes.failNext(target -> isHead(target) && Files.exists(target), new IOException("initial XML publication failed"));
            runOnFx(() -> {
                fixture.lifecycle().enableRecoveryGate(() -> true, () -> () -> { }, _ -> opens.incrementAndGet());
                fixture.lifecycle().setNewProjectLocationPrompt(() -> workspace.resolve("failed"));
                fixture.lifecycle().onNewProject();
            });
            fixture.awaitOnFx(() -> !fixture.lifecycle().isProjectInstallationPending(), "failed initial save cleaned up");
            assertThat(opens).hasValue(0);
            assertThat(fixture.projectManager.getCurrentProject().projectPath().resolve("journal")).doesNotExist();
            assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                    .noneMatch(message -> message.equals("New project created"))
                    .anyMatch(message -> message.contains("initial XML publication failed"));
        }
    }

    @Test void failedNewJournalStartupCleansUpTheGuardWithoutClaimingSuccess() throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT.withEnabled(false))) {
            runOnFx(() -> {
                fixture.lifecycle().enableRecoveryGate(() -> true, () -> () -> { }, _ -> { throw new IllegalStateException("journal unavailable"); });
                fixture.lifecycle().setNewProjectLocationPrompt(() -> workspace.resolve("failed journal"));
                fixture.lifecycle().onNewProject();
            });
            fixture.awaitOnFx(() -> !fixture.lifecycle().isProjectInstallationPending(), "failed journal cleaned up");
            assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                    .noneMatch(message -> message.equals("New project created"))
                    .anyMatch(message -> message.contains("journal unavailable"));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void aTakeStartingDuringSlowSealRefusesReplacementAndPreservesTheOutgoingLease(boolean recording) throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT.withEnabled(false))) {
            DawProject original = fixture.project.get();
            Path evidence = Files.writeString(Files.createDirectories(fixture.songA.resolve("journal")).resolve("segment-000.bin"), "accepted edits");
            CountDownLatch sealing = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean take = new AtomicBoolean();
            runOnFx(() -> {
                fixture.lifecycle().setRecordingInFlightCheck(() -> recording && take.get());
                fixture.lifecycle().setTakeBeingWrittenCheck(() -> !recording && take.get());
                fixture.lifecycle().enableRecoveryGate(() -> true, () -> {
                    assertThat(fixture.lifecycle().isProjectInstallationPending()).isTrue();
                    return () -> { sealing.countDown(); await(release); };
                }, _ -> { throw new AssertionError("refused replacement started a journal"); });
                fixture.lifecycle().setNewProjectLocationPrompt(() -> workspace.resolve("refused"));
                fixture.lifecycle().onNewProject();
            });
            try {
                assertThat(sealing.await(15, TimeUnit.SECONDS)).isTrue();
                assertThat(onFx(() -> fixture.lifecycle().isProjectInstallationPending())).isTrue();
                fixture.projectManager.getLockManager().verifyHeld();
                take.set(true);
            } finally { release.countDown(); }
            fixture.awaitOnFx(() -> !fixture.lifecycle().isProjectInstallationPending(), "late take refused");
            assertThat(fixture.project.get()).isSameAs(original);
            assertThat(fixture.projectManager.getCurrentProject().projectPath()).isEqualTo(fixture.songA);
            fixture.projectManager.getLockManager().verifyHeld();
            assertThat(Files.readString(evidence)).isEqualTo("accepted edits");
            assertThat(take).isTrue();
            assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                    .anyMatch(message -> message.contains("Journaled persistence is paused"));
        }
    }

    @Test void sealFailureKeepsTheOutgoingProjectLeaseAndEvidenceAndReleasesTheUiGuard() throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT.withEnabled(false))) {
            DawProject original = fixture.project.get();
            Path evidence = Files.writeString(Files.createDirectories(fixture.songA.resolve("journal")).resolve("segment-000.bin"), "accepted edits");
            runOnFx(() -> {
                fixture.lifecycle().enableRecoveryGate(() -> true, () -> () -> { throw new IllegalStateException("fsync failed"); }, _ -> { });
                fixture.lifecycle().setNewProjectLocationPrompt(() -> workspace.resolve("failed close"));
                fixture.lifecycle().onNewProject();
            });
            fixture.awaitOnFx(() -> !fixture.lifecycle().isProjectInstallationPending(), "close failure cleaned up");
            assertThat(fixture.project.get()).isSameAs(original);
            fixture.projectManager.getLockManager().verifyHeld();
            assertThat(Files.readString(evidence)).isEqualTo("accepted edits");
            assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                    .anyMatch(message -> message.contains("fsync failed") && message.contains("Journaled persistence is paused"));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void importAndNoBackupRollbackKeepTheOutgoingLeaseUntilSealFinishes(boolean importing) throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT.withEnabled(false))) {
            DawProject original = fixture.project.get();
            CountDownLatch sealing = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ListChangeListener<Window> closeSummary = change -> {
                while (change.next()) for (Window window : change.getAddedSubList()) {
                    if (window instanceof Stage stage && "Import Summary".equals(stage.getTitle())) {
                        Platform.runLater(() -> {
                            DialogPane pane = (DialogPane) stage.getScene().getRoot();
                            ((Button) pane.lookupButton(pane.getButtonTypes().getFirst())).fire();
                        });
                    }
                }
            };
            runOnFx(() -> {
                Window.getWindows().addListener(closeSummary);
                fixture.lifecycle().enableRecoveryGate(() -> true, () -> () -> {
                    sealing.countDown(); await(release);
                    assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).exists();
                }, _ -> { });
                if (importing) {
                    SessionImportResult result = new SessionImportResult(new SessionData("Imported", 120, 4, 4, 48_000, List.of()), List.of());
                    invokeImport(fixture.lifecycle(), workspace.resolve("import.dawproject"), result);
                } else {
                    fixture.lifecycle().rollbackMigration(fixture.songA, new MigrationReport(0, 1, List.of(), Instant.now()));
                }
            });
            try {
                assertThat(sealing.await(15, TimeUnit.SECONDS)).isTrue();
                assertThat(fixture.project.get()).isSameAs(original);
                fixture.projectManager.getLockManager().verifyHeld();
                assertThat(onFx(() -> fixture.lifecycle().isProjectInstallationPending())).isTrue();
                release.countDown();
                fixture.awaitOnFx(() -> !fixture.lifecycle().isProjectInstallationPending(), "replacement finished after seal");
                assertThat(fixture.project.get()).isNotSameAs(original);
                assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).doesNotExist();
            } finally {
                release.countDown();
                runOnFx(() -> Window.getWindows().removeListener(closeSummary));
            }
        }
    }

    private static void invokeImport(ProjectLifecycleController lifecycle, Path source, SessionImportResult result) {
        try {
            Method method = ProjectLifecycleController.class.getDeclaredMethod("installImportedSession", Path.class, SessionImportResult.class);
            method.setAccessible(true);
            method.invoke(lifecycle, source, result);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
