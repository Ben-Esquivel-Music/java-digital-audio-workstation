package com.benesquivelmusic.daw.core.comping;

import com.benesquivelmusic.daw.core.audio.AudioClip;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TakeLaneTest {

    @Test
    void shouldCreateWithName() {
        TakeLane lane = new TakeLane("Take 1");

        assertThat(lane.getName()).isEqualTo("Take 1");
        assertThat(lane.getId()).isNotBlank();
        assertThat(lane.getClips()).isEmpty();
        assertThat(lane.isSoloed()).isFalse();
        assertThat(lane.isMuted()).isFalse();
    }

    @Test
    void shouldRejectNullName() {
        assertThatThrownBy(() -> new TakeLane(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldSetName() {
        TakeLane lane = new TakeLane("Take 1");
        lane.setName("Take 1 (best)");
        assertThat(lane.getName()).isEqualTo("Take 1 (best)");
    }

    @Test
    void shouldRejectNullNameOnSet() {
        TakeLane lane = new TakeLane("Take 1");
        assertThatThrownBy(() -> lane.setName(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldAddClip() {
        TakeLane lane = new TakeLane("Take 1");
        AudioClip clip = new AudioClip("Clip A", 0.0, 4.0, null);

        lane.addClip(clip);

        assertThat(lane.getClips()).containsExactly(clip);
    }

    @Test
    void shouldRemoveClip() {
        TakeLane lane = new TakeLane("Take 1");
        AudioClip clip = new AudioClip("Clip A", 0.0, 4.0, null);
        lane.addClip(clip);

        boolean removed = lane.removeClip(clip);

        assertThat(removed).isTrue();
        assertThat(lane.getClips()).isEmpty();
    }

    @Test
    void shouldReturnFalseWhenRemovingAbsentClip() {
        TakeLane lane = new TakeLane("Take 1");
        AudioClip clip = new AudioClip("Clip A", 0.0, 4.0, null);

        assertThat(lane.removeClip(clip)).isFalse();
    }

    @Test
    void shouldRejectNullClip() {
        TakeLane lane = new TakeLane("Take 1");
        assertThatThrownBy(() -> lane.addClip(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReturnUnmodifiableClipsList() {
        TakeLane lane = new TakeLane("Take 1");
        lane.addClip(new AudioClip("Clip A", 0.0, 4.0, null));

        assertThatThrownBy(() -> lane.getClips().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldToggleSolo() {
        TakeLane lane = new TakeLane("Take 1");
        lane.setSoloed(true);
        assertThat(lane.isSoloed()).isTrue();
        lane.setSoloed(false);
        assertThat(lane.isSoloed()).isFalse();
    }

    @Test
    void shouldToggleMute() {
        TakeLane lane = new TakeLane("Take 1");
        lane.setMuted(true);
        assertThat(lane.isMuted()).isTrue();
        lane.setMuted(false);
        assertThat(lane.isMuted()).isFalse();
    }

    @Test
    void shouldGenerateUniqueIds() {
        TakeLane a = new TakeLane("Take 1");
        TakeLane b = new TakeLane("Take 2");

        assertThat(a.getId()).isNotEqualTo(b.getId());
    }
}
