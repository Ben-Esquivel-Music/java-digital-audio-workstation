package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.recording.SegmentFile;
import com.benesquivelmusic.daw.core.recording.SegmentWriter;
import com.benesquivelmusic.daw.core.recording.TakeDirectories;
import com.benesquivelmusic.daw.core.recording.TakeManifest;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentEntry;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentState;
import com.benesquivelmusic.daw.core.recording.TakeManifest.TrackEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.onFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 327's crash grammar at the app level (Recording Reliability book
 * §4.7): a take the JVM died in — its manifest still {@code streaming}, its
 * segment still a {@code .part} with a torn trailing byte — planted under
 * the open project's {@code audio/takes}, is reconstructed by the record
 * coordinator's own project-rooted registry through
 * {@link IncompleteTakeStore#registerRecoveredTakes}: registered with reason
 * CRASH_RECOVERY, its segment sealed and playable with the frames the file
 * length gives, its manifest {@code aborted}/{@code crash-recovery}; and a
 * second pass finds nothing. Story 332 invokes this on project open; this
 * test only exercises what it will call.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class CrashedTakeRecoveryRegistersARescuedTakeTest {

    private static final double SAMPLE_RATE = 44_100.0;
    private static final int BIT_DEPTH = 16;
    private static final int MONO = 1;
    private static final int FRAMES = 1_000;
    /** One 16-bit quantisation step; a sample written and read back lands within a step and its rounding. */
    private static final float SIXTEEN_BIT_STEP = 1.0f / 32_768;
    private static final float QUANTISATION_BOUND = 1.5f * SIXTEEN_BIT_STEP;

    @TempDir
    Path projectDirectory;

    private DeviceLossRecordingFixture fixture;

    @BeforeEach
    void openTheProject() throws Exception {
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory);
    }

    @AfterEach
    void closeTheProject() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void aCrashedTakeIsSealedRegisteredAsCrashRecoveryAndPlayable() throws Exception {
        Path audioDirectory = ProjectManager.audioDirectory(projectDirectory);
        Path takeDirectory = plantACrashedTake(audioDirectory);
        String relativePath = fixture.guitar().getId() + "/segment-000.wav";
        Path wav = takeDirectory.resolve(relativePath);
        Path part = SegmentWriter.partPathFor(wav);
        assertThat(part).as("fixture: the crash left a .part").isRegularFile();
        IncompleteTakeStore store = onFx(() -> fixture.coordinator().rescuedTakeStore()).orElseThrow();
        assertThat(store.projectDirectory()).isEqualTo(projectDirectory.toAbsolutePath().normalize());

        List<IncompleteTakeStore.RescuedTake> registered = store.registerRecoveredTakes(audioDirectory);

        assertThat(registered)
                .extracting(IncompleteTakeStore.RescuedTake::reason, IncompleteTakeStore.RescuedTake::takeDirectory,
                        IncompleteTakeStore.RescuedTake::deviceName, IncompleteTakeStore.RescuedTake::clipCount,
                        IncompleteTakeStore.RescuedTake::sealedSegmentCount)
                .containsExactly(tuple(TakeManifest.SealedBy.CRASH_RECOVERY, takeDirectory.toAbsolutePath().normalize(),
                        Optional.empty(), 1, 1));
        assertThat(store.rescuedTakes()).isEqualTo(registered);
        assertThat(store.latest()).contains(registered.getFirst());

        TakeManifest manifest = TakeManifest.read(TakeManifest.manifestPath(takeDirectory));
        assertThat(manifest.sealStatus()).isEqualTo(TakeManifest.SealStatus.ABORTED);
        assertThat(manifest.sealedBy()).contains(TakeManifest.SealedBy.CRASH_RECOVERY);
        assertThat(manifest.sealedAt()).isPresent();
        assertThat(manifest.segments())
                .containsExactly(new SegmentEntry(fixture.guitar().getId(), 0, 0, relativePath, FRAMES, SegmentState.SEALED));
        assertThat(part).as("the .part was renamed").doesNotExist();
        assertThat(wav).isRegularFile();
        float[][] audio = SegmentFile.readFrames(wav);
        assertThat(audio).hasDimensions(MONO, FRAMES);
        for (int i = 0; i < FRAMES; i++) {
            assertThat(audio[0][i]).as("frame %d", i).isCloseTo(sample(i), within(QUANTISATION_BOUND));
        }

        byte[] manifestBytes = Files.readAllBytes(TakeManifest.manifestPath(takeDirectory));
        assertThat(store.registerRecoveredTakes(audioDirectory)).as("a second pass finds nothing").isEmpty();
        assertThat(store.rescuedTakes()).hasSize(1);
        assertThat(Files.readAllBytes(TakeManifest.manifestPath(takeDirectory))).isEqualTo(manifestBytes);
    }

    /**
     * A streaming manifest listing the guitar's first segment, and that
     * segment's {@code .part} with {@link #FRAMES} frames written through a
     * writer that is then abandoned — no size patch, no rename — plus one
     * torn byte, as a crash leaves them. Returns the take directory.
     */
    private Path plantACrashedTake(Path audioDirectory) throws IOException {
        Path takeDirectory = TakeDirectories.allocate(audioDirectory, Instant.parse("2026-08-03T14:22:05Z"));
        String trackId = fixture.guitar().getId();
        String relativePath = trackId + "/segment-000.wav";
        TakeManifest.builder()
                .take(takeDirectory.getFileName().toString())
                .startedAt(Instant.parse("2026-08-03T14:22:05Z"))
                .sampleRate(SAMPLE_RATE)
                .bitDepth(BIT_DEPTH)
                .streamChannels(2)
                .startBeat(4.0)
                .startFrame(88_200L)
                .forceCadenceMillis(5_000L)
                .ringSlots(32)
                .ringFrames(512)
                .addTrack(new TrackEntry(trackId, MONO, 0))
                .addSegment(new SegmentEntry(trackId, 0, 0, relativePath, TakeManifest.FRAMES_STREAMING,
                        SegmentState.STREAMING))
                .build()
                .write(takeDirectory);
        Path part = SegmentWriter.partPathFor(takeDirectory.resolve(relativePath));
        float[][] audio = new float[MONO][FRAMES];
        for (int i = 0; i < FRAMES; i++) {
            audio[0][i] = sample(i);
        }
        try (SegmentWriter writer = SegmentWriter.open(part, SAMPLE_RATE, MONO, BIT_DEPTH, Duration.ofSeconds(5),
                System::nanoTime)) {
            writer.append(audio, MONO, FRAMES);
        }
        Files.write(part, new byte[] {0x7f}, StandardOpenOption.APPEND);
        return takeDirectory;
    }

    /** A ramp through the 16-bit range that no two frames share. */
    private static float sample(int frame) {
        return (frame - FRAMES / 2) / 512.0f;
    }
}
