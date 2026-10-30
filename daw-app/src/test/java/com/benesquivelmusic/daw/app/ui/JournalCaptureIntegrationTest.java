package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.status.ProjectJournalCoordinator;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.persistence.*;
import com.benesquivelmusic.daw.core.persistence.journal.*;
import com.benesquivelmusic.daw.core.session.SessionManager;
import com.benesquivelmusic.daw.sdk.event.MixerEvent;
import com.benesquivelmusic.daw.sdk.event.TransportEvent;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.prefs.Preferences;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(JavaFxToolkitExtension.class)
class JournalCaptureIntegrationTest {
    @TempDir Path workspace;

    private ProjectJournalCoordinator coordinator(SaveFlowFixture fixture, DefaultEventBus bus,
                                                   java.util.function.Supplier<Boolean> enabled) throws Exception {
        return new ProjectJournalCoordinator(fixture.progress, onFx(NotificationBar::new), fixture.dispatcher,
                enabled, () -> bus, fixture.checkpoints, new SessionManager(), InstantSource.system());
    }

    private record Captured(CheckpointManager.Capture point, String xml) { }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sameRevisionCapturesKeepTheirTransportBoundariesDespiteReversedCompletion(boolean reverse) throws Exception {
        DefaultEventBus bus = new DefaultEventBus();
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
             ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            fixture.save(); fixture.settle();
            ProjectJournalCoordinator journal = coordinator(fixture, bus, () -> true);
            journal.openFor(fixture.songA);
            CountDownLatch publishDelayed = new CountDownLatch(1);
            try {
                Captured first = captureAfterSeek(fixture, bus, 10);
                Captured second = captureAfterSeek(fixture, bus, 20);
                assertThat(first.point().revision()).isEqualTo(second.point().revision());
                assertThat(second.point().journalSequence().orElseThrow())
                        .isGreaterThan(first.point().journalSequence().orElseThrow());
                Captured delayed = reverse ? first : second;
                Captured immediate = reverse ? second : first;
                Future<Path> delayedWrite = workers.submit(() -> {
                    assertThat(publishDelayed.await(10, TimeUnit.SECONDS)).isTrue();
                    return fixture.checkpoints.writeCheckpoint(delayed.xml(), delayed.point(), CheckpointOrigin.USER)
                            .get(10, TimeUnit.SECONDS);
                });
                Path immediateFile = fixture.checkpoints.writeCheckpoint(immediate.xml(), immediate.point(), CheckpointOrigin.USER)
                        .get(10, TimeUnit.SECONDS);
                publishDelayed.countDown();
                Path delayedFile = delayedWrite.get(10, TimeUnit.SECONDS);
                runOnFx(() -> bus.publish(new TransportEvent.Seeked(20, 30, Instant.now())));
                journal.drainWithoutSeal(null);
                ProjectContext context = ProjectContext.forProject(fixture.songA);
                List<JournalRecord> markers = JournalState.records(context).stream().filter(JournalRecord::isCheckpointMarker).toList();
                assertThat(markers).hasSize(2);
                assertThat(markers).extracting(JournalState::boundary).containsExactly(
                        immediate.point().journalSequence().orElseThrow(), delayed.point().journalSequence().orElseThrow());
                assertThat(recoveryPoint(context).file()).isEqualTo(reverse ? immediateFile : delayedFile);
                assertThat(new JournalReplayer().recover(context).appliedRecordCount()).isEqualTo(1);
            } finally {
                publishDelayed.countDown();
                journal.drainWithoutSeal(null);
            }
        } finally { bus.close(); }
    }

    /** The recovery point a replay of {@code context}'s journal starts from. */
    private static RecoveryPlan.Available recoveryPoint(ProjectContext context) throws IOException {
        RecoveryPlan.ReplayJournal journal = (RecoveryPlan.ReplayJournal) new RecoveryScanner().replayPlan(context).recovery();
        return (RecoveryPlan.Available) journal.base();
    }

    private Captured captureAfterSeek(SaveFlowFixture fixture, DefaultEventBus bus, long position) throws Exception {
        return onFx(() -> {
            bus.publish(new TransportEvent.Seeked(0, position, Instant.now()));
            CheckpointManager.Capture point = fixture.checkpoints.captureStarted(fixture.project.get().getEditRevision());
            try { return new Captured(point, new ProjectSerializer().capture(fixture.project.get()).toXml()); }
            catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
    }

    @Test
    void anOlderSlowSaveCannotPruneTheNewerExplicitRecoveryBaseAtTheSmallestRetentionCap() throws Exception {
        DefaultEventBus bus = new DefaultEventBus();
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, new AutoSaveConfig(Duration.ofHours(1), 1, true))) {
            fixture.save(); fixture.settle();
            ProjectJournalCoordinator journal = coordinator(fixture, bus, () -> true);
            journal.openFor(fixture.songA);
            SaveFlowFixture.Publishes.Hold head = fixture.publishes.holdNext(path -> path.equals(fixture.songA.resolve("project.daw")));
            try {
                runOnFx(() -> {
                    fixture.project.get().markDirty();
                    bus.publish(new TransportEvent.Seeked(0, 10, Instant.now()));
                });
                fixture.save(); head.awaitHeld();
                runOnFx(() -> {
                    fixture.project.get().createAudioTrack("Newer captured take");
                    fixture.project.get().markDirty();
                    bus.publish(new TransportEvent.Seeked(10, 20, Instant.now()));
                    fixture.snapshots().createCheckpointWithLabel("Newer capture");
                });
                fixture.awaitOnFx(() -> fixture.checkpointEvents.stream().anyMatch(event ->
                        event instanceof CheckpointEvent.Written written && written.origin() == CheckpointOrigin.USER),
                        "newer user checkpoint completed before the slow save");
                runOnFx(() -> bus.publish(new TransportEvent.Seeked(20, 30, Instant.now())));
                head.release(); fixture.settle();
                journal.drainWithoutSeal(null);

                RecoveryResult recovered = new JournalReplayer().recover(ProjectContext.forProject(fixture.songA));
                assertThat(recovered.recoveredProject().getTracks()).extracting(track -> track.getName())
                        .contains("Newer captured take");
                assertThat(recovered.appliedRecordCount()).isEqualTo(1);
                assertThat(fixture.checkpoints.getCheckpointFiles()).as("newest capture plus late returned save checkpoint")
                        .hasSize(2);
            } finally { head.release(); journal.drainWithoutSeal(null); }
        } finally { bus.close(); }
    }

    @Test
    void aFailedSaveCarriesItsOriginalCaptureBoundaryIntoItsSafetyCopy() throws Exception {
        DefaultEventBus bus = new DefaultEventBus();
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT)) {
            fixture.save(); fixture.settle();
            ProjectJournalCoordinator journal = coordinator(fixture, bus, () -> true);
            journal.openFor(fixture.songA);
            Path headFile = fixture.songA.resolve("project.daw");
            SaveFlowFixture.Publishes.Hold head = fixture.publishes.holdNext(headFile::equals);
            fixture.publishes.failNext(headFile::equals, new IOException("head publication refused"));
            try {
                runOnFx(() -> {
                    fixture.project.get().markDirty();
                    bus.publish(new TransportEvent.Seeked(0, 10, Instant.now()));
                });
                fixture.save(); head.awaitHeld();
                runOnFx(() -> bus.publish(new TransportEvent.Seeked(10, 20, Instant.now())));
                head.release(); fixture.settle();
                journal.drainWithoutSeal(null);
                assertThat(fixture.checkpointEvents).anyMatch(event -> event instanceof CheckpointEvent.Written written
                        && written.origin() == CheckpointOrigin.SAFETY_COPY && written.journalSequence().isPresent());
                assertThat(new JournalReplayer().recover(ProjectContext.forProject(fixture.songA)).appliedRecordCount()).isEqualTo(1);
            } finally { head.release(); journal.drainWithoutSeal(null); }
        } finally { bus.close(); }
    }

    @Test
    void anOldGenerationCompletingAfterSameFolderRestartCannotMarkTheNewJournal() throws Exception {
        DefaultEventBus bus = new DefaultEventBus();
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT)) {
            fixture.save(); fixture.settle();
            ProjectJournalCoordinator journal = coordinator(fixture, bus, () -> true);
            journal.openFor(fixture.songA);
            SaveFlowFixture.Publishes.Hold held = fixture.publishes.holdNext(SaveFlowFixture::isCheckpoint);
            try {
                Captured old = captureAfterSeek(fixture, bus, 10);
                CompletableFuture<Path> pending = fixture.checkpoints.writeCheckpoint(old.xml(), old.point(), CheckpointOrigin.USER);
                held.awaitHeld();
                journal.drainWithoutSeal(null);
                fixture.checkpoints.stop();
                JournalState.clear(ProjectContext.forProject(fixture.songA));
                CompletableFuture<Void> restarted = fixture.checkpoints.start(fixture.songA);
                journal.openFor(fixture.songA);
                held.release();
                pending.get(10, TimeUnit.SECONDS);
                restarted.get(10, TimeUnit.SECONDS);
                journal.drainWithoutSeal(null);
                assertThat(JournalState.records(ProjectContext.forProject(fixture.songA))).noneMatch(JournalRecord::isCheckpointMarker);
                assertThat(fixture.checkpoints.captureStarted(old.point().revision()).journalSequence()).isEmpty();
            } finally { held.release(); journal.drainWithoutSeal(null); }
        } finally { bus.close(); }
    }

    @Test void actualSaveCaptureRetainsAnEditPublishedWhileItsCheckpointWriteIsHeld() throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT)) {
            fixture.save(); fixture.settle();
            DefaultEventBus bus = new DefaultEventBus();
            ProjectJournalCoordinator coordinator = coordinator(fixture, bus, () -> true);
            coordinator.openFor(fixture.songA);
            try {
                runOnFx(() -> {
                    fixture.project.get().markDirty();
                    bus.publish(new MixerEvent.GainChanged(UUID.randomUUID(), Instant.now()));
                });
                SaveFlowFixture.Publishes.Hold checkpoint = fixture.publishes.holdNext(SaveFlowFixture::isCheckpoint);
                fixture.save();
                assertThat(checkpoint.awaitHeld().isVirtual()).isTrue();
                runOnFx(() -> {
                    fixture.project.get().markDirty();
                    bus.publish(new MixerEvent.MuteChanged(UUID.randomUUID(), true, Instant.now()));
                });
                checkpoint.release(); fixture.settle();
                coordinator.drainWithoutSeal(null);
                ProjectContext context = ProjectContext.forProject(fixture.songA);
                assertThat(JournalState.records(context).stream().filter(record -> !record.isControl())).hasSize(2);
                assertThat(new JournalReplayer().recover(context).appliedRecordCount()).isEqualTo(1);
                assertThat(JournalState.needsRecovery(context)).isTrue();
            } finally { coordinator.drainWithoutSeal(null); bus.close(); }
        }
    }

    @Test void autosaveDisabledSavesDoNotRotateButCreateCheckpointAdvancesTheVerifiedReplayBase() throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT.withEnabled(false))) {
            fixture.save(); fixture.settle();
            DefaultEventBus bus = new DefaultEventBus();
            ProjectJournalCoordinator coordinator = coordinator(fixture, bus, () -> true);
            coordinator.openFor(fixture.songA);
            try {
                runOnFx(() -> {
                    fixture.project.get().createAudioTrack("Before explicit checkpoint");
                    fixture.project.get().markDirty();
                    bus.publish(new MixerEvent.GainChanged(UUID.randomUUID(), Instant.now()));
                });
                fixture.save(); fixture.settle();
                assertThat(SaveFlowFixture.checkpointFiles(fixture.songA)).isEmpty();
                assertThat(JournalState.records(ProjectContext.forProject(fixture.songA)))
                        .noneMatch(JournalRecord::isCheckpointMarker);
                runOnFx(() -> fixture.snapshots().createCheckpointWithLabel("Explicit safety copy"));
                fixture.awaitOnFx(() -> fixture.checkpointEvents.stream().anyMatch(event -> event instanceof CheckpointEvent.Written),
                        "explicit user checkpoint completed");
                runOnFx(() -> {
                    fixture.project.get().createAudioTrack("After explicit checkpoint");
                    fixture.project.get().markDirty();
                    bus.publish(new MixerEvent.MuteChanged(UUID.randomUUID(), true, Instant.now()));
                });
                fixture.save(); fixture.settle();
                assertThat(SaveFlowFixture.checkpointFiles(fixture.songA)).hasSize(1);
                coordinator.drainWithoutSeal(null);
                ProjectContext context = ProjectContext.forProject(fixture.songA);
                RecoveryResult replay = new JournalReplayer().recover(context);
                assertThat(replay.recoveredProject().getTracks()).extracting(track -> track.getName())
                        .contains("Before explicit checkpoint").doesNotContain("After explicit checkpoint");
                assertThat(replay.appliedRecordCount()).isEqualTo(1);
                assertThat(recoveryPoint(context).file().getParent()).isEqualTo(fixture.songA.resolve("checkpoints"));
            } finally { coordinator.drainWithoutSeal(null); bus.close(); }
        }
    }

    @Test void freshSettingsActivateRealBusJournalingAndOptOutSuppressesIt() throws Exception {
        Preferences preferences = Preferences.userRoot().node("daw-story332-default-" + UUID.randomUUID());
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT)) {
            fixture.save(); fixture.settle();
            SettingsModel settings = new SettingsModel(preferences);
            assertThat(settings.isUseJournaledPersistence()).isTrue();
            DefaultEventBus bus = new DefaultEventBus();
            ProjectJournalCoordinator coordinator = coordinator(fixture, bus, settings::isUseJournaledPersistence);
            try {
                coordinator.openFor(fixture.songA);
                runOnFx(() -> bus.publish(new MixerEvent.GainChanged(UUID.randomUUID(), Instant.now())));
                coordinator.drainWithoutSeal(null);
                assertThat(JournalState.records(ProjectContext.forProject(fixture.songA))).anyMatch(record -> !record.isControl());
                settings.setUseJournaledPersistence(false);
                Path optedOut = Files.createDirectories(workspace.resolve("Opted out"));
                coordinator.openFor(optedOut);
                runOnFx(() -> bus.publish(new MixerEvent.GainChanged(UUID.randomUUID(), Instant.now())));
                assertThat(coordinator.isOpen()).isFalse();
                assertThat(optedOut.resolve("journal")).doesNotExist();
            } finally { coordinator.drainWithoutSeal(null); bus.close(); }
        } finally { preferences.removeNode(); }
    }

    @Test void switchSealsBeforeOpeningTheNextJournalAndAnUnsealedDrainLeavesItsReaderOwingRecovery() throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT)) {
            fixture.save(); fixture.settle();
            DefaultEventBus bus = new DefaultEventBus();
            ProjectJournalCoordinator coordinator = coordinator(fixture, bus, () -> true);
            coordinator.openFor(fixture.songA);
            try {
                Path next = fixture.anotherProjectOnDisk("Next");
                coordinator.detachFromBus();
                coordinator.reopenFor(next, null);
                ProjectContext old = ProjectContext.forProject(fixture.songA);
                assertThat(JournalState.records(old).getLast().isSessionSeal()).isTrue();
                assertThat(JournalState.needsRecovery(old)).isFalse();
                coordinator.drainWithoutSeal(null);
                ProjectContext current = ProjectContext.forProject(next);
                assertThat(JournalState.records(current)).noneMatch(JournalRecord::isSessionSeal);
                assertThat(JournalState.needsRecovery(current)).isTrue();
            } finally { coordinator.drainWithoutSeal(null); bus.close(); }
        }
    }
}
