package com.benesquivelmusic.daw.app.ui.motion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Story 279 — exercises the <em>real</em> {@link PlatformMotionHint} probe,
 * the FFM downcall that {@code OsHintDetectionTest}'s mocked seam never
 * touches.
 *
 * <p>Two layers of coverage:</p>
 * <ul>
 *   <li>A Windows-only test that drives the genuine
 *       {@code user32!SystemParametersInfoW} downcall and asserts a
 *       <em>present</em> result — the call must succeed on the project's
 *       primary platform. The boolean value itself is intentionally
 *       <em>not</em> asserted: it depends on the developer's / CI agent's
 *       actual OS accessibility setting, which the test must not assume.</li>
 *   <li>A platform-agnostic test that pins the {@link OsMotionHint}
 *       never-throws contract on whatever OS the suite runs on.</li>
 * </ul>
 */
final class PlatformMotionHintTest {

    /**
     * On Windows, {@code SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION,
     * …)} is a stable Win32 call that succeeds on every supported Windows
     * version, so the probe must return a present {@link Optional}. The
     * contained boolean mirrors the machine's real "show animations"
     * setting and is deliberately left unasserted.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsProbeReturnsAPresentResult() {
        Optional<Boolean> hint = new PlatformMotionHint().reduceMotionPreferred();
        assertThat(hint)
                .as("the Windows SystemParametersInfo downcall must succeed "
                        + "and yield a determinate reduce-motion preference")
                .isPresent();
    }

    /**
     * The {@link OsMotionHint} contract forbids throwing: every probe
     * failure must collapse to {@link Optional#empty()}. This holds on any
     * platform — including the ones where the probe legitimately returns
     * empty (macOS, a non-GNOME Linux) — so it is not OS-gated.
     */
    @Test
    void probeNeverThrowsAndNeverReturnsNull() {
        PlatformMotionHint hint = new PlatformMotionHint();
        assertThatCode(hint::reduceMotionPreferred)
                .as("OsMotionHint implementations must never throw — a probe "
                        + "failure is reported as Optional.empty()")
                .doesNotThrowAnyException();
        assertThat(hint.reduceMotionPreferred())
                .as("the probe must return a non-null Optional")
                .isNotNull();
    }
}
