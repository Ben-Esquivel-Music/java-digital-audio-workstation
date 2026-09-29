package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.recording.TakeManifest.SealedBy;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentEntry;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentState;
import com.benesquivelmusic.daw.core.recording.TakeManifest.TrackEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 323 fault-injection probe (Recording Reliability book §3.3;
 * Persistence Integrity book §4.1 idiom): {@link TakeManifest#write(Path)}
 * stages {@code take.manifest.tmp} and moves it over {@code take.manifest},
 * so a rewrite that cannot complete leaves the previous manifest byte-for-byte
 * intact. A direct {@code Files.writeString(take.manifest)} passes every other
 * manifest test — no {@code .tmp} left behind, contents replaced, round trip —
 * because those only look at the end state. This test injects a rewrite that
 * cannot stage (the staging name is occupied by a directory, which fails
 * {@code writeString} on every platform) and pins the crash-consistency half
 * of the atomic replace: the failure is reported and the old sidecar survives
 * untouched. Deterministic; no timing.
 */
class Story323TakeManifestAtomicWriteProbeContractTest {

    @TempDir
    Path takeDir;

    @Test
    void aRewriteThatCannotStageItsTemporaryFileLeavesThePreviousManifestIntact() throws IOException {
        TakeManifest first = manifest().build();
        Path manifestPath = first.write(takeDir);
        byte[] before = Files.readAllBytes(manifestPath);
        Path staging = takeDir.resolve(TakeManifest.FILE_NAME + TakeManifest.TMP_SUFFIX);
        assertThat(staging).as("write() leaves no staging file behind").doesNotExist();

        Files.createDirectory(staging);
        TakeManifest second = manifest().overflowBlocks(7).sealed(SealedBy.STOP).build();

        assertThatThrownBy(() -> second.write(takeDir))
                .as("a rewrite that cannot stage its temporary file fails instead of touching the target")
                .isInstanceOf(IOException.class);

        assertThat(Files.readAllBytes(manifestPath))
                .as("the previous manifest is byte-for-byte intact after the failed rewrite")
                .isEqualTo(before);
        assertThat(TakeManifest.read(manifestPath)).isEqualTo(first);
        assertThat(staging).isDirectory();
    }

    @Test
    void theSameRewriteLandsOnceTheStagingNameIsFreeAgain() throws IOException {
        // Non-vacuity of the probe above: the only thing that blocked the
        // rewrite was the occupied staging name.
        TakeManifest first = manifest().build();
        Path manifestPath = first.write(takeDir);
        Path staging = takeDir.resolve(TakeManifest.FILE_NAME + TakeManifest.TMP_SUFFIX);
        Files.createDirectory(staging);
        TakeManifest second = manifest().overflowBlocks(7).sealed(SealedBy.STOP).build();
        assertThatThrownBy(() -> second.write(takeDir)).isInstanceOf(IOException.class);

        Files.delete(staging);
        assertThat(second.write(takeDir)).isEqualTo(manifestPath);

        assertThat(TakeManifest.read(manifestPath)).isEqualTo(second);
        assertThat(staging).doesNotExist();
    }

    private static TakeManifest.Builder manifest() {
        return TakeManifest.builder()
                .take("2026-09-28T10-00-00_take-0001")
                .startedAt(Instant.parse("2026-09-28T10:00:00Z"))
                .sampleRate(48_000.0)
                .bitDepth(16)
                .streamChannels(2)
                .startBeat(0.0)
                .startFrame(0L)
                .forceCadenceMillis(5_000L)
                .ringSlots(8)
                .ringFrames(512)
                .addTrack(new TrackEntry("track-1", 2, 0L))
                .addSegment(new SegmentEntry("track-1", 0, 0, "track-1/segment-000.wav",
                        TakeManifest.FRAMES_STREAMING, SegmentState.STREAMING));
    }
}
