package com.benesquivelmusic.daw.app.diagnostics;

import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;
import com.benesquivelmusic.daw.core.audio.health.AudioFailureSample;
import com.benesquivelmusic.daw.core.audio.health.LaneRole;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The samples the audio health drain keeps until a report uses them (story
 * 337, ruling E79): at most {@value UnreportedSamples#MAX_PER_CATEGORY} per
 * category, the oldest dropped, and a one-shot report's used samples removed
 * by identity, so every other sample stays for the next delta.
 */
class UnreportedSamplesTest {

    private final UnreportedSamples unreported = new UnreportedSamples();

    @Test
    void theOldestSampleOfACategoryIsDroppedBeyondTheCap() {
        for (int detail = 0; detail <= UnreportedSamples.MAX_PER_CATEGORY; detail++) {
            unreported.add(sample(AudioFailureCategory.WORKER, detail));
        }
        unreported.add(sample(AudioFailureCategory.RENDER, 7));

        assertThat(unreported.of(AudioFailureCategory.WORKER)).extracting(AudioFailureSample::detail)
                .as("the newest %d, oldest first", UnreportedSamples.MAX_PER_CATEGORY)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, UnreportedSamples.MAX_PER_CATEGORY)
                        .boxed().toList());
        assertThat(unreported.of(AudioFailureCategory.RENDER)).extracting(AudioFailureSample::detail)
                .as("each category has a cap of its own").containsExactly(7);
    }

    @Test
    void removingUsedSamplesKeepsTheRest() {
        AudioFailureSample used = sample(AudioFailureCategory.AUTOMATION, 3);
        AudioFailureSample equalToTheUsedOne = sample(AudioFailureCategory.AUTOMATION, 3);
        AudioFailureSample other = sample(AudioFailureCategory.AUTOMATION, 4);
        AudioFailureSample config = sample(AudioFailureCategory.CONFIG, 18);
        unreported.add(used);
        unreported.add(equalToTheUsedOne);
        unreported.add(other);
        unreported.add(config);

        unreported.remove(AudioFailureCategory.AUTOMATION, List.of(used));

        List<AudioFailureSample> kept = unreported.of(AudioFailureCategory.AUTOMATION);
        assertThat(kept).hasSize(2);
        assertThat(kept.get(0)).as("removed by identity, not by value").isSameAs(equalToTheUsedOne);
        assertThat(kept.get(1)).isSameAs(other);
        assertThat(unreported.of(AudioFailureCategory.CONFIG)).as("another category is untouched")
                .containsExactly(config);
    }

    private static AudioFailureSample sample(AudioFailureCategory category, int detail) {
        return new AudioFailureSample(category, Optional.empty(), Optional.empty(), detail, 0L,
                LaneRole.RENDER_DRIVE);
    }
}
