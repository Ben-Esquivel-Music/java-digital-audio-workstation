package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.IncompleteTakeStore.RescuedTake;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.recording.TakeDirectories;
import com.benesquivelmusic.daw.core.recording.TakeManifest;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SealedBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link IncompleteTakeStore} as the rescued-take registry of Recording
 * Reliability book §2.9 (story 327): rooted at the project, fed only by the
 * seal paths, holding no audio. The guard that a registered take lies under
 * the project's {@code audio/takes} is compared against the configured
 * project directory — on Windows a {@code @TempDir} lives under the OS
 * temporary directory itself, so comparing against {@code java.io.tmpdir}
 * would prove nothing.
 */
class IncompleteTakeStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:15:30Z");

    @TempDir
    Path projectDirectory;

    @Test
    void aNewRegistryIsEmptyAndRootedAtTheProject() {
        IncompleteTakeStore store = new IncompleteTakeStore(projectDirectory);

        assertThat(store.projectDirectory()).isEqualTo(projectDirectory.toAbsolutePath().normalize());
        assertThat(store.isEmpty()).isTrue();
        assertThat(store.rescuedTakes()).isEmpty();
        assertThat(store.latest()).isEmpty();
    }

    @Test
    void registeredTakesAreReturnedInRegistrationOrderAndTheLastOneIsTheLatest() {
        IncompleteTakeStore store = new IncompleteTakeStore(projectDirectory);
        RescuedTake first = rescued(store, "20261009T101530Z_take-0001", SealedBy.DEVICE_LOSS, 2, 2);
        RescuedTake second = rescued(store, "20261009T101530Z_take-0002", SealedBy.CRASH_RECOVERY, 1, 3);

        store.register(first);
        store.register(second);

        assertThat(store.isEmpty()).isFalse();
        assertThat(store.rescuedTakes()).containsExactly(first, second);
        assertThat(store.latest()).contains(second);
    }

    @Test
    void theSnapshotDoesNotChangeWithALaterRegistration() {
        IncompleteTakeStore store = new IncompleteTakeStore(projectDirectory);
        RescuedTake first = rescued(store, "20261009T101530Z_take-0001", SealedBy.DEVICE_LOSS, 1, 1);
        store.register(first);
        List<RescuedTake> snapshot = store.rescuedTakes();

        store.register(rescued(store, "20261009T101530Z_take-0002", SealedBy.DEVICE_LOSS, 1, 1));

        assertThat(snapshot).containsExactly(first);
        assertThatThrownBy(() -> snapshot.add(first)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aTakeOutsideTheProjectsTakesDirectoryIsRefused(@TempDir Path elsewhere) {
        IncompleteTakeStore store = new IncompleteTakeStore(projectDirectory);
        Path takes = takesDirectory(store);

        // Another directory entirely — the OS temporary directory's sibling
        // the @TempDir gave us, which is exactly where a rescue must never go.
        assertThatThrownBy(() -> store.register(take(elsewhere.resolve("take-0001"), SealedBy.DEVICE_LOSS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(takes.toString());
        // Under the project, but not under audio/takes.
        assertThatThrownBy(() -> store.register(take(projectDirectory.resolve("take-0001"), SealedBy.DEVICE_LOSS)))
                .isInstanceOf(IllegalArgumentException.class);
        // Under audio/takes, but one level too deep: a track directory is not a take.
        assertThatThrownBy(() -> store.register(take(takes.resolve("take-0001").resolve("guitar"), SealedBy.DEVICE_LOSS)))
                .isInstanceOf(IllegalArgumentException.class);
        // audio/takes itself is not a take.
        assertThatThrownBy(() -> store.register(take(takes, SealedBy.DEVICE_LOSS)))
                .isInstanceOf(IllegalArgumentException.class);
        // A path that only resolves there through '..' is normalized first and accepted.
        store.register(take(takes.resolve("other").resolve("..").resolve("take-0001"), SealedBy.DEVICE_LOSS));

        assertThat(store.rescuedTakes()).singleElement()
                .extracting(RescuedTake::takeDirectory).isEqualTo(takes.resolve("take-0001"));
    }

    @Test
    void aRescuedTakeIsSealedByDeviceLossOrCrashRecoveryOnly() {
        IncompleteTakeStore store = new IncompleteTakeStore(projectDirectory);
        Path take = takesDirectory(store).resolve("take-0001");

        for (SealedBy reason : List.of(SealedBy.STOP, SealedBy.DISK_EXHAUSTION, SealedBy.WRITE_FAILURE,
                SealedBy.START_FAILURE)) {
            assertThatThrownBy(() -> take(take, reason))
                    .as(reason.name())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(reason.name());
        }
        assertThatThrownBy(() -> new RescuedTake(Path.of("relative"), SealedBy.DEVICE_LOSS, Optional.empty(), NOW, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RescuedTake(take, SealedBy.DEVICE_LOSS, Optional.empty(), NOW, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RescuedTake(take, SealedBy.DEVICE_LOSS, Optional.empty(), NOW, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RescuedTake(take, SealedBy.DEVICE_LOSS, null, NOW, 0, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.register(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void recoveringWithNoTakesDirectoryRegistersNothing() throws IOException {
        IncompleteTakeStore store = new IncompleteTakeStore(projectDirectory);

        assertThat(store.registerRecoveredTakes(ProjectManager.audioDirectory(projectDirectory))).isEmpty();
        assertThat(store.isEmpty()).isTrue();
    }

    @Test
    void recoveringRegistersNothingForATakeThatWasSealedByItsStop() throws IOException {
        IncompleteTakeStore store = new IncompleteTakeStore(projectDirectory);
        Path take = takesDirectory(store).resolve("20261009T101530Z_take-0001");
        Files.createDirectories(take);
        TakeManifest.builder()
                .take(take.getFileName().toString()).startedAt(NOW)
                .sampleRate(48_000.0).bitDepth(24).streamChannels(2).startBeat(0.0).startFrame(0)
                .forceCadenceMillis(5_000).ringSlots(8).ringFrames(256)
                .sealed(SealedBy.STOP).sealedAt(NOW)
                .build().write(take);

        assertThat(store.registerRecoveredTakes(ProjectManager.audioDirectory(projectDirectory))).isEmpty();
        assertThat(store.isEmpty()).isTrue();
    }

    @Test
    void recoveringAnotherProjectsAudioDirectoryIsRefused(@TempDir Path otherProject) {
        IncompleteTakeStore store = new IncompleteTakeStore(projectDirectory);

        assertThatThrownBy(() -> store.registerRecoveredTakes(ProjectManager.audioDirectory(otherProject)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.isEmpty()).isTrue();
    }

    private static Path takesDirectory(IncompleteTakeStore store) {
        return TakeDirectories.takesDirectory(ProjectManager.audioDirectory(store.projectDirectory()));
    }

    private static RescuedTake rescued(IncompleteTakeStore store, String takeName, SealedBy reason,
                                       int clipCount, int sealedSegmentCount) {
        return new RescuedTake(takesDirectory(store).resolve(takeName), reason,
                reason == SealedBy.DEVICE_LOSS ? Optional.of("USB interface") : Optional.empty(),
                NOW, clipCount, sealedSegmentCount);
    }

    private static RescuedTake take(Path takeDirectory, SealedBy reason) {
        return new RescuedTake(takeDirectory.toAbsolutePath(), reason, Optional.empty(), NOW, 0, 0);
    }
}
