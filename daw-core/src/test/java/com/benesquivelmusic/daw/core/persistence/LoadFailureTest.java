package com.benesquivelmusic.daw.core.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The too-large wording a report row and a relink refusal share (decisions D10, D35). */
class LoadFailureTest {

    private static final long MEBIBYTE = 1024L * 1024L;

    @Test
    void aSourceAboveTheLimitSaysWhatItNeedsAndWhatTheApplicationAllowsInMebibytes() {
        LoadFailure failure = LoadFailure.tooLarge(2_417 * MEBIBYTE, 2_048 * MEBIBYTE);

        assertThat(failure.reason()).isEqualTo(MissingAssetReason.TOO_LARGE);
        assertThat(failure.detail()).isEqualTo("needs about 2417 MiB of memory to load;"
                + " at most 2048 MiB is allowed (half the application's memory limit)");
    }

    @Test
    void runningOutOfMemorySaysHowToTryAgainRatherThanThatTheFileIsTooLarge() {
        LoadFailure failure = LoadFailure.outOfMemory();

        assertThat(failure.reason()).isEqualTo(MissingAssetReason.TOO_LARGE);
        assertThat(failure.detail()).isEqualTo("ran out of memory while loading — try Locate file…"
                + " on the same file after loading finishes");
    }
}
