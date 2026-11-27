package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.ClockLockStatus;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's pass-through of the open stream's clock-lock status: the open
 * stream's backend's level while a stream is open, nothing otherwise, read
 * without the lifecycle lock so the window can read it every frame.
 */
class AudioEngineClockLockStatusTest {

    private static final DeviceId DEVICE = new DeviceId(MockAudioBackend.NAME, "Interface A");

    private final MockAudioBackend backend = new MockAudioBackend();
    private final AudioEngine engine = new AudioEngine(new AudioFormat(48_000.0, 2, 24, 256));

    @AfterEach
    void stopTheStream() {
        engine.stopAudioOutput();
        engine.stop();
    }

    @Test
    void theOpenStreamsBackendAnswersAndAClosedStreamHasNoStatus() {
        ClockLockStatus unlocked = new ClockLockStatus(new DeviceId(MockAudioBackend.NAME, "Resolved Driver"), 1, 7L,
                ClockLockStatus.Lock.UNLOCKED, 42L);
        backend.setClockLockStatus(unlocked);
        engine.setStreamingProvision(new StreamingProvision(backend.name(),
                List.of(new BackendStreamRung(backend, DEVICE))));

        boolean beforeTheOpen = engine.openStreamClockLockStatus().isPresent();
        engine.startAudioOutput();
        ClockLockStatus whileOpen = engine.openStreamClockLockStatus().orElseThrow();
        engine.stopAudioOutput();

        assertThat(beforeTheOpen).as("no stream, no status").isFalse();
        assertThat(whileOpen).as("the open stream's backend's own status, its device as the driver side names it")
                .isEqualTo(unlocked);
        assertThat(engine.openStreamClockLockStatus()).as("a closed stream has none").isEmpty();
    }

    /** The FX thread reads it every frame: two volatile reads and the backend's own read, never the lifecycle lock. */
    @Test
    void theReadTakesNoLock() throws IOException {
        String code = Files.readString(engineSource()).replaceAll("\\s+", " ");
        String signature = "public Optional<ClockLockStatus> openStreamClockLockStatus() {";
        int declaration = code.indexOf(signature);

        assertThat(declaration).as("non-vacuity: the method was found").isNotNegative();
        assertThat(code.substring(declaration, code.indexOf('}', declaration) + 1))
                .isEqualTo(signature + " AudioBackend backend = this.openBackend; return backend != null"
                        + " && isStreamOpen() ? backend.clockLockStatus() : Optional.empty(); }");
    }

    private static Path engineSource() {
        Path relative = Paths.get("src/main/java/com/benesquivelmusic/daw/core/audio/AudioEngine.java");
        Path direct = Paths.get("").toAbsolutePath().resolve(relative);
        return Files.isRegularFile(direct) ? direct : Paths.get("daw-core").resolve(relative);
    }
}
