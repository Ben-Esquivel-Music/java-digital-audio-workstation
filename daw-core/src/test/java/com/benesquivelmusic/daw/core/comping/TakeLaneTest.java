package com.benesquivelmusic.daw.core.comping;

import com.benesquivelmusic.daw.core.audio.AudioClip;

import org.junit.jupiter.api.Test;

import java.util.UUID;

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

    @Test
    void aRestoredIdIsKept() {
        UUID saved = UUID.fromString("3f2c9a4e-6a1b-4c57-9d0e-1b2c3d4e5f60");

        TakeLane lane = new TakeLane(saved, "Take 1");

        assertThat(lane.getId()).isEqualTo("3f2c9a4e-6a1b-4c57-9d0e-1b2c3d4e5f60");
        assertThat(lane.getName()).isEqualTo("Take 1");
        String fresh = new TakeLane("Take 2").getId();
        assertThat(UUID.fromString(fresh)).as("a fresh lane's id is a canonical UUID as well").hasToString(fresh);
        assertThatThrownBy(() -> new TakeLane(null, "Take 1")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TakeLane(saved, null)).isInstanceOf(NullPointerException.class);
    }
}
