package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TakeGroupTest {

    private static AudioClip clip(String name) {
        return new AudioClip(name, 0.0, 1.0, null);
    }

    @Test
    void emptyGroupHasZeroSizeAndActiveIndexZero() {
        TakeGroup group = TakeGroup.empty();

        assertThat(group.isEmpty()).isTrue();
        assertThat(group.size()).isZero();
        assertThat(group.activeIndex()).isZero();
    }

    @Test
    void appendingTakesPreservesInsertionOrder() {
        TakeGroup group = TakeGroup.empty();

        group = group.withTakeAppended(Take.of(clip("t0")));
        group = group.withTakeAppended(Take.of(clip("t1")));
        group = group.withTakeAppended(Take.of(clip("t2")));

        assertThat(group.size()).isEqualTo(3);
        assertThat(group.takes().get(0).clip().getName()).isEqualTo("t0");
        assertThat(group.takes().get(2).clip().getName()).isEqualTo("t2");
        assertThat(group.activeTake().clip().getName()).isEqualTo("t0");
    }

    @Test
    void activeIndexOutOfRangeRejected() {
        AudioClip c = clip("a");
        List<Take> takes = List.of(Take.of(c));

        assertThatThrownBy(() -> new TakeGroup(UUID.randomUUID(), takes, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TakeGroup(UUID.randomUUID(), takes, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withActiveIndexRoutesTheCorrectClip() {
        TakeGroup group = TakeGroup.empty()
                .withTakeAppended(Take.of(clip("t0")))
                .withTakeAppended(Take.of(clip("t1")))
                .withTakeAppended(Take.of(clip("t2")));

        TakeGroup updated = group.withActiveIndex(2);

        assertThat(updated.activeIndex()).isEqualTo(2);
        assertThat(updated.activeClip().getName()).isEqualTo("t2");
        // Underlying record is immutable — original unchanged.
        assertThat(group.activeIndex()).isZero();
    }

    @Test
    void cycleActiveWrapsAroundTheEnd() {
        TakeGroup group = TakeGroup.empty()
                .withTakeAppended(Take.of(clip("t0")))
                .withTakeAppended(Take.of(clip("t1")));

        assertThat(group.cycleActive().activeIndex()).isEqualTo(1);
        assertThat(group.cycleActive().cycleActive().activeIndex()).isZero();
    }

    @Test
    void cycleActiveOnSingletonOrEmptyReturnsSameGroup() {
        TakeGroup empty = TakeGroup.empty();
        assertThat(empty.cycleActive()).isSameAs(empty);

        TakeGroup single = TakeGroup.empty().withTakeAppended(Take.of(clip("only")));
        assertThat(single.cycleActive()).isSameAs(single);
    }

    @Test
    void takeRejectsNulls() {
        AudioClip c = clip("x");
        assertThatThrownBy(() -> new Take(null, c, Instant.now()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Take(UUID.randomUUID(), null, Instant.now()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Take(UUID.randomUUID(), c, null))
                .isInstanceOf(NullPointerException.class);
    }
}
