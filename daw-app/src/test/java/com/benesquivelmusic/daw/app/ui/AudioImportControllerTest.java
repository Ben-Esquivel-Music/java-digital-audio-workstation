package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.export.WavExporter;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;

import javafx.application.Platform;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 315 regression — {@link AudioImportController#importAudioFile} takes an
 * explicit placement beat instead of round-tripping it through the transport.
 *
 * <p>Story 315 made {@link Transport#setPositionInBeats(double)} deferred while
 * the transport is rolling (the seek is queued and applied at the next
 * audio-block boundary), so the old drag-drop dance of seek-to-drop-beat /
 * import-at-playhead / seek-back read the <em>stale pre-drop</em> position and
 * placed the clip at the playhead instead of the drop beat.</p>
 *
 * <p>A real {@code DragEvent}/{@code Dragboard} cannot be built headless
 * (see {@code DropZoneFiresDockEventTest}), so the test drives the extracted
 * core — the same {@code importAudioFile(file, targetTrack, dropBeat)} call
 * the installed {@code DRAG_DROPPED} handler makes after computing the drop
 * beat. A real {@link Transport} in {@link TransportState#PLAYING} state with
 * the RT-clock claim held gives the deferred-seek behaviour — deferral requires
 * PLAYING <em>and</em> the claim (story 315); an unclaimed transport applies
 * seeks inline even while rolling, which would let the old
 * seek/import/restore behaviour pass unnoticed.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class AudioImportControllerTest {

    @TempDir
    Path tempDir;

    private static <T> T callOnFxThread(java.util.concurrent.Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(10, TimeUnit.SECONDS))
                .as("FX work must complete within 10s").isTrue();
        if (thrown.get() instanceof RuntimeException re) {
            throw re;
        }
        if (thrown.get() instanceof Error e) {
            throw e;
        }
        if (thrown.get() != null) {
            throw new IllegalStateException(thrown.get());
        }
        return result.get();
    }

    private AudioImportController createController(DawProject project, MixerView mixerView) {
        // Drop-on-existing-track never creates a track, so the track-strip /
        // track-creation / stage suppliers are never invoked (lazy Suppliers).
        return new AudioImportController(new AudioImportController.Deps(
                () -> project,
                UndoManager::new,
                () -> null,
                () -> null,
                () -> mixerView,
                VBox::new,
                () -> null,
                () -> { },
                () -> { },
                () -> { },
                (message, icon) -> { },
                (level, message) -> { }));
    }

    private Path createTestWav(String fileName) throws IOException {
        int sampleRate = 44100;
        int numSamples = sampleRate / 2; // 0.5 seconds
        float[][] audio = new float[2][numSamples];
        for (int i = 0; i < numSamples; i++) {
            float value = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate));
            audio[0][i] = value;
            audio[1][i] = value;
        }
        Path wavFile = tempDir.resolve(fileName);
        WavExporter.write(audio, sampleRate, 16, DitherType.NONE, AudioMetadata.EMPTY, wavFile);
        return wavFile;
    }

    @Test
    void dropWhileTransportIsPlayingPlacesClipAtDropBeatAndLeavesTransportUntouched() throws Exception {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track targetTrack = project.createAudioTrack("Audio 1");
        Path wavFile = createTestWav("dropped.wav");

        Transport transport = project.getTransport();
        transport.setPositionInBeats(2.0); // committed while STOPPED
        transport.setRealTimeClockActive(true);
        transport.play();                  // rolling + claimed → seeks are deferred

        MixerView mixerView = callOnFxThread(() -> new MixerView(project));
        AudioImportController controller = createController(project, mixerView);

        double dropBeat = 8.0;
        boolean success = callOnFxThread(
                () -> controller.importAudioFile(wavFile, targetTrack, dropBeat));

        assertThat(success).isTrue();
        assertThat(targetTrack.getClips()).hasSize(1);
        assertThat(targetTrack.getClips().get(0).getStartBeat())
                .as("clip lands at the drop beat, not the rolling playhead")
                .isEqualTo(dropBeat);
        assertThat(transport.getState()).isEqualTo(TransportState.PLAYING);
        assertThat(transport.getPositionInBeats())
                .as("the drop must not move the committed transport position")
                .isEqualTo(2.0);

        // The drop must not have queued a deferred seek either: the next block
        // boundary advances normally instead of applying a queued seek verbatim.
        transport.advancePosition(0.25);
        assertThat(transport.getPositionInBeats())
                .as("no seek was queued by the drop — the block advance lands normally")
                .isEqualTo(2.25);

        transport.stop();
    }

    @Test
    void placementBeatIsHonouredWhileStopped() throws Exception {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track targetTrack = project.createAudioTrack("Audio 1");
        Path wavFile = createTestWav("imported.wav");

        Transport transport = project.getTransport();
        transport.setPositionInBeats(3.0);

        MixerView mixerView = callOnFxThread(() -> new MixerView(project));
        AudioImportController controller = createController(project, mixerView);

        boolean success = callOnFxThread(
                () -> controller.importAudioFile(wavFile, targetTrack, 5.0));

        assertThat(success).isTrue();
        assertThat(targetTrack.getClips()).hasSize(1);
        assertThat(targetTrack.getClips().get(0).getStartBeat()).isEqualTo(5.0);
        assertThat(transport.getPositionInBeats())
                .as("an import never moves the transport")
                .isEqualTo(3.0);
    }
}
