package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.status.ProjectJournalCoordinator;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.persistence.*;
import com.benesquivelmusic.daw.core.persistence.journal.*;
import com.benesquivelmusic.daw.core.session.SessionManager;
import com.benesquivelmusic.daw.sdk.event.MixerEvent;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
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
                assertThat(JournalState.replayBase(context).file().getParent()).isEqualTo(fixture.songA.resolve("checkpoints"));
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

    @Test void switchSealsBeforeOpeningTheNextJournalAndDisposalLeavesItsReaderUnsealed() throws Exception {
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
