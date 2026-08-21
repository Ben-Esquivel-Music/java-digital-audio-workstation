package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract of {@link JavaxSoundBackend}'s mandatory output line (story 316
 * review): a rung that cannot produce sound must FAIL the open loudly so the
 * engine's {@code StreamingProvision} ladder can fall through — never
 * "succeed" into a silent no-output stream.
 */
class JavaxSoundBackendTest {

    @Test
    void unopenableOutputLineFailsTheOpenAndRollsBack() {
        // 192 interleaved channels is absurd for javax.sound.sampled — no
        // host mixer offers such a line, so the output-line open is refused
        // deterministically on every machine, headless or not.
        JavaxSoundBackend backend = new JavaxSoundBackend();
        AudioFormat absurd = new AudioFormat(48_000.0, 192, 16);

        assertThatThrownBy(() ->
                backend.open(DeviceId.defaultFor(JavaxSoundBackend.NAME), absurd, 512))
                .as("a refused OUTPUT line is a failed rung, not a silent success")
                .isInstanceOf(AudioBackendException.class);

        assertThat(backend.isOpen())
                .as("the failed open must roll back — the ladder sees a closed rung")
                .isFalse();

        backend.close(); // idempotent on a rolled-back backend
        assertThat(backend.isOpen()).isFalse();
    }
}
