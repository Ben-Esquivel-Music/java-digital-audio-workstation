package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link IncompleteTakeStore} — the buffer that captures
 * in-flight recording samples and flushes them to a timestamped WAV file
 * under {@code .daw/incomplete-takes/} when the audio device disappears.
 */
class IncompleteTakeStoreTest {

    private static final DeviceId DEVICE = new DeviceId("Mock", "Test/Device:1");

    @Test
    void shouldStartEmpty(@TempDir Path root) {
        IncompleteTakeStore store = new IncompleteTakeStore(root);
        assertThat(store.bufferedByteCount()).isZero();
        assertThat(store.flushIfNotEmpty(DEVICE, AudioFormat.CD_QUALITY)).isEmpty();
    }

    @Test
    void shouldAppendCapturedFrames(@TempDir Path root) {
        IncompleteTakeStore store = new IncompleteTakeStore(root);
        store.appendCapturedFrames(new float[][]{{0.5f, -0.5f}, {0.1f, -0.1f}}, 2);
        // 2 frames * 2 channels * 2 bytes = 8 bytes
        assertThat(store.bufferedByteCount()).isEqualTo(8);
    }

    @Test
    void shouldClampOutOfRangeSamples(@TempDir Path root) {
        IncompleteTakeStore store = new IncompleteTakeStore(root);
        store.appendCapturedFrames(new float[][]{{2.0f}, {-2.0f}}, 1);
        assertThat(store.bufferedByteCount()).isEqualTo(4);
    }

    @Test
    void shouldFlushBufferToWavFileAndResetBuffer(@TempDir Path root) throws IOException {
        IncompleteTakeStore store = new IncompleteTakeStore(root);
        store.appendCapturedFrames(new float[][]{{0.25f, 0.5f}, {0.25f, 0.5f}}, 2);
        Optional<Path> file = store.flushIfNotEmpty(DEVICE, AudioFormat.CD_QUALITY);
        assertThat(file).isPresent();
        Path wav = file.get();
        assertThat(wav).exists();
        // Filename should be sanitized — ':' / '/' replaced.
        assertThat(wav.getFileName().toString()).doesNotContain("/").doesNotContain(":");
        // Standard WAV header begins with "RIFF" and contains "WAVE".
        byte[] bytes = Files.readAllBytes(wav);
        assertThat(new String(bytes, 0, 4)).isEqualTo("RIFF");
        assertThat(new String(bytes, 8, 4)).isEqualTo("WAVE");
        assertThat(store.bufferedByteCount()).isZero();
    }

    @Test
    void shouldRejectNegativeFrameCount(@TempDir Path root) {
        IncompleteTakeStore store = new IncompleteTakeStore(root);
        assertThatThrownBy(() -> store.appendCapturedFrames(new float[][]{{0f}}, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldNoopOnZeroFrameAppend(@TempDir Path root) {
        IncompleteTakeStore store = new IncompleteTakeStore(root);
        store.appendCapturedFrames(new float[][]{{0.5f}}, 0);
        assertThat(store.bufferedByteCount()).isZero();
    }
}
