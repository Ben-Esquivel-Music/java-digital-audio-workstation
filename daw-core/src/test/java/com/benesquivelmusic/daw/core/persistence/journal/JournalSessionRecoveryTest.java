package com.benesquivelmusic.daw.core.persistence.journal;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.persistence.backup.BackupRetentionPolicyStore;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.sdk.event.DispatchMode;
import com.benesquivelmusic.daw.sdk.event.MixerEvent;
import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class JournalSessionRecoveryTest {
    @TempDir Path folder;
    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");

    private ProjectContext context(String name) throws IOException {
        Files.writeString(folder.resolve("project.daw"), xml(name));
        return ProjectContext.forProject(folder, InstantSource.fixed(NOW));
    }

    private static String xml(String name) throws IOException {
        return new ProjectSerializer().serialize(new DawProject(name, AudioFormat.CD_QUALITY));
    }

    private Path checkpoint(String name) throws IOException {
        Path directory = Files.createDirectories(folder.resolve("checkpoints"));
        return Files.writeString(directory.resolve("checkpoint-001-20261025T080000.daw"), xml(name));
    }

    @Test void capturePointPreservesRecordsPublishedWhileCheckpointWritesAndDispatchesThemOnce() throws Exception {
        ProjectContext context = context("Start");
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null)) {
            writer.start();
            writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW);
            long captured = writer.captureSequence();
            writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, NOW.plusSeconds(1));
            writer.onCheckpointSucceeded(captured, checkpoint("Captured"), NOW.plusSeconds(2));
            writer.forceFlush();
            assertThat(writer.queuedSinceCheckpoint()).isEqualTo(1);
        }
        RecoveryResult replay = new JournalReplayer().recover(context);
        assertThat(replay.recoveredProject().getName()).isEqualTo("Captured");
        assertThat(replay.appliedRecordCount()).isEqualTo(1);
        assertThat(JournalState.records(context).stream().filter(record -> !record.isControl())).hasSize(2);
    }

    @Test void sealWithoutTrailingMutationsIsCleanButSealOverUncheckpointedEditsStillRecovers() throws Exception {
        ProjectContext context = context("Head");
        JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null);
        writer.start();
        writer.onCheckpointSucceeded(writer.captureSequence(), checkpoint("Head"), NOW);
        writer.sealAndClose(NOW.plusSeconds(1));
        assertThat(JournalState.needsRecovery(context)).isFalse();
        JournalState.clear(context);
        writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null);
        writer.start();
        writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, NOW);
        writer.sealAndClose(NOW.plusSeconds(1));
        assertThat(JournalState.needsRecovery(context)).isTrue();
    }

    @Test void disposalDrainDoesNotSealADirtyCheckpointWhoseStateIsAbsentFromHead() throws Exception {
        ProjectContext context = context("Old saved head");
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null)) {
            writer.start();
            writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW);
            writer.onCheckpointSucceeded(writer.captureSequence(), checkpoint("Dirty checkpoint"), NOW);
            writer.forceFlush();
        }
        assertThat(JournalState.needsRecovery(context)).isTrue();
        assertThat(new JournalReplayer().recover(context).recoveredProject().getName()).isEqualTo("Dirty checkpoint");
        assertThat(Files.readString(context.projectFile())).contains("Old saved head");
    }

    @Test void aNewWriterCannotAppendToUnresolvedPriorSessionEvidence() throws Exception {
        ProjectContext context = context("Head");
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null)) {
            writer.start();
            writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, NOW);
        }
        byte[] prior = Files.readAllBytes(context.journalDirectory().resolve("segment-000.bin"));
        assertThatThrownBy(() -> new JournalWriter(context, JournalConfig.DEFAULT, true, null).start()).isInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(context.journalDirectory().resolve("segment-000.bin"))).isEqualTo(prior);
    }

    @Test void autosaveOffUsesImmutableSessionStartHeadUntilAnExplicitCheckpointSucceeds() throws Exception {
        ProjectContext context = context("Recovery-written head");
        checkpoint("Unrelated stale checkpoint");
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null)) {
            writer.start();
            writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW);
            Files.writeString(context.projectFile(), xml("Manual save without checkpoint"));
            writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, NOW);
        }
        RecoveryResult result = new JournalReplayer().recover(context);
        assertThat(result.recoveredProject().getName()).isEqualTo("Recovery-written head");
        assertThat(result.appliedRecordCount()).isEqualTo(2);
        assertThat(new RecoveryScanner().scan(context).orElseThrow().segmentFileNames()).containsExactly("segment-000.bin");
    }

    @Test void failedFsyncCannotCreateACleanSeal() throws Exception {
        ProjectContext context = context("Head");
        AtomicBoolean failing = new AtomicBoolean();
        JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null,
                channel -> { if (failing.get()) throw new IOException("disk unavailable"); channel.force(true); });
        writer.start();
        writer.forceFlush();
        failing.set(true);
        writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, NOW);
        assertThatThrownBy(() -> writer.sealAndClose(NOW)).isInstanceOf(IOException.class);
        assertThat(JournalState.records(context)).noneMatch(JournalRecord::isSessionSeal);
        assertThat(JournalState.needsRecovery(context)).isTrue();
    }

    @Test void tornBytesAfterASealAreRecoveryEvidence() throws Exception {
        ProjectContext context = context("Head");
        JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null);
        writer.start();
        writer.sealAndClose(NOW);
        Files.write(context.journalDirectory().resolve("segment-000.bin"), new byte[] {1, 2, 3}, StandardOpenOption.APPEND);
        assertThat(JournalState.needsRecovery(context)).isTrue();
    }

    @Test void aSealWhoseOwnFsyncFailsIsRolledBackWithoutChangingPriorDurableRecords() throws Exception {
        ProjectContext context = context("Head");
        AtomicBoolean failSeal = new AtomicBoolean();
        JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null,
                channel -> { if (failSeal.get()) throw new IOException("seal fsync failed"); channel.force(true); });
        writer.start(); writer.forceFlush();
        Path segment = context.journalDirectory().resolve("segment-000.bin");
        byte[] durable = Files.readAllBytes(segment);
        failSeal.set(true);
        assertThatThrownBy(() -> writer.sealAndClose(NOW)).isInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(segment)).isEqualTo(durable);
        assertThat(JournalState.needsRecovery(context)).isTrue();
    }

    @Test void changedCheckpointBytesFallBackToSessionStartAndACorruptSessionStartFailsSafely() throws Exception {
        ProjectContext context = context("Session start");
        Path checkpoint = checkpoint("Captured");
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null)) {
            writer.start();
            writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW);
            writer.onCheckpointSucceeded(writer.captureSequence(), checkpoint, NOW);
            writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, NOW);
        }
        Files.writeString(checkpoint, xml("Unrelated replacement"));
        RecoveryResult replay = new JournalReplayer().recover(context);
        assertThat(replay.recoveredProject().getName()).isEqualTo("Session start");
        assertThat(replay.appliedRecordCount()).isEqualTo(2);
        Files.writeString(context.journalDirectory().resolve(JournalState.BASE_FILE), xml("Changed baseline"));
        assertThatThrownBy(() -> new JournalReplayer().recover(context)).isInstanceOf(JournalRecoveryException.class);
        assertThat(JournalState.records(context)).isNotEmpty();
    }

    @Test void publicationFenceCannotBeOvertakenByAStalledOrOverflowingAsyncSubscriber() throws Exception {
        ProjectContext context = context("Head");
        DefaultEventBus bus = DefaultEventBus.builder().bufferCapacity(1).uiExecutor(_ -> { }).build();
        bus.on(MixerEvent.class, DispatchMode.ON_UI_THREAD, _ -> { });
        try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null);
             JournalEventRecorder recorder = new JournalEventRecorder(bus, writer, InstantSource.fixed(NOW)).start()) {
            writer.start();
            for (int i = 0; i < 50; i++) bus.publish(new MixerEvent.GainChanged(UUID.randomUUID(), NOW));
            long captured = writer.captureSequence();
            for (int i = 0; i < 25; i++) bus.publish(new MixerEvent.GainChanged(UUID.randomUUID(), NOW));
            writer.onCheckpointSucceeded(captured, checkpoint("Captured"), NOW);
            recorder.close();
            writer.forceFlush();
        } finally { bus.close(); }
        assertThat(new JournalReplayer().recover(context).appliedRecordCount()).isEqualTo(25);
        assertThat(JournalState.records(context).stream().filter(record -> !record.isControl())).hasSize(75);
    }

    @ParameterizedTest @ValueSource(strings = {"ar-SA", "th-TH-u-nu-thai"})
    void persistentNamesAndPolicyNumbersAreAsciiUnderEveryFormatLocale(String tag) throws Exception {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag(tag));
            ProjectContext context = context("Head");
            try (JournalWriter writer = new JournalWriter(context, JournalConfig.DEFAULT, true, null)) {
                writer.start();
                writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW);
            }
            assertThat(new RecoveryScanner().scan(context).orElseThrow().segmentFileNames()).containsExactly("segment-000.bin");
            String json = BackupRetentionPolicyStore.toJson(BackupRetentionPolicy.DEFAULT);
            assertThat(json.chars().filter(Character::isDigit).allMatch(value -> value >= '0' && value <= '9')).isTrue();
        } finally { Locale.setDefault(before); }
    }
}
