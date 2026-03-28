package com.benesquivelmusic.daw.core.track;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrackGroupTest {

    @Test
    void shouldCreateGroupWithName() {
        TrackGroup group = new TrackGroup("Drums");

        assertThat(group.getName()).isEqualTo("Drums");
        assertThat(group.getId()).isNotBlank();
        assertThat(group.getTracks()).isEmpty();
        assertThat(group.size()).isZero();
    }

    @Test
    void shouldRejectNullName() {
        assertThatThrownBy(() -> new TrackGroup(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldSetName() {
        TrackGroup group = new TrackGroup("Old");
        group.setName("New");
        assertThat(group.getName()).isEqualTo("New");
    }

    @Test
    void shouldRejectNullSetName() {
        TrackGroup group = new TrackGroup("Test");
        assertThatThrownBy(() -> group.setName(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldAddTrack() {
        TrackGroup group = new TrackGroup("Drums");
        Track kick = new Track("Kick", TrackType.AUDIO);

        group.addTrack(kick);

        assertThat(group.getTracks()).containsExactly(kick);
        assertThat(group.size()).isEqualTo(1);
        assertThat(group.contains(kick)).isTrue();
    }

    @Test
    void shouldNotAddDuplicateTrack() {
        TrackGroup group = new TrackGroup("Drums");
        Track kick = new Track("Kick", TrackType.AUDIO);

        group.addTrack(kick);
        group.addTrack(kick);

        assertThat(group.size()).isEqualTo(1);
    }

    @Test
    void shouldRejectNullTrack() {
        TrackGroup group = new TrackGroup("Drums");
        assertThatThrownBy(() -> group.addTrack(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRemoveTrack() {
        TrackGroup group = new TrackGroup("Drums");
        Track kick = new Track("Kick", TrackType.AUDIO);
        group.addTrack(kick);

        boolean removed = group.removeTrack(kick);

        assertThat(removed).isTrue();
        assertThat(group.getTracks()).isEmpty();
    }

    @Test
    void shouldReturnFalseWhenRemovingAbsentTrack() {
        TrackGroup group = new TrackGroup("Drums");
        Track kick = new Track("Kick", TrackType.AUDIO);

        assertThat(group.removeTrack(kick)).isFalse();
    }

    @Test
    void shouldReturnUnmodifiableTrackList() {
        TrackGroup group = new TrackGroup("Drums");
        group.addTrack(new Track("Kick", TrackType.AUDIO));

        assertThatThrownBy(() -> group.getTracks().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldGenerateUniqueIds() {
        TrackGroup a = new TrackGroup("A");
        TrackGroup b = new TrackGroup("B");
        assertThat(a.getId()).isNotEqualTo(b.getId());
    }

    // ── Linked operation tests ──────────────────────────────────────────────

    @Test
    void shouldSetMutedOnAllMembers() {
        TrackGroup group = new TrackGroup("Drums");
        Track kick = new Track("Kick", TrackType.AUDIO);
        Track snare = new Track("Snare", TrackType.AUDIO);
        group.addTrack(kick);
        group.addTrack(snare);

        group.setMuted(true);

        assertThat(kick.isMuted()).isTrue();
        assertThat(snare.isMuted()).isTrue();

        group.setMuted(false);

        assertThat(kick.isMuted()).isFalse();
        assertThat(snare.isMuted()).isFalse();
    }

    @Test
    void shouldSetSoloOnAllMembers() {
        TrackGroup group = new TrackGroup("Drums");
        Track kick = new Track("Kick", TrackType.AUDIO);
        Track snare = new Track("Snare", TrackType.AUDIO);
        group.addTrack(kick);
        group.addTrack(snare);

        group.setSolo(true);

        assertThat(kick.isSolo()).isTrue();
        assertThat(snare.isSolo()).isTrue();
    }

    @Test
    void shouldSetArmedOnAllMembers() {
        TrackGroup group = new TrackGroup("Drums");
        Track kick = new Track("Kick", TrackType.AUDIO);
        Track snare = new Track("Snare", TrackType.AUDIO);
        group.addTrack(kick);
        group.addTrack(snare);

        group.setArmed(true);

        assertThat(kick.isArmed()).isTrue();
        assertThat(snare.isArmed()).isTrue();
    }

    @Test
    void shouldScaleVolumeProportionally() {
        TrackGroup group = new TrackGroup("Drums");
        Track kick = new Track("Kick", TrackType.AUDIO);
        Track snare = new Track("Snare", TrackType.AUDIO);
        kick.setVolume(1.0);
        snare.setVolume(0.5);
        group.addTrack(kick);
        group.addTrack(snare);

        group.scaleVolume(0.5);

        assertThat(kick.getVolume()).isEqualTo(0.5);
        assertThat(snare.getVolume()).isEqualTo(0.25);
    }

    @Test
    void shouldClampVolumeToMaxOnScale() {
        TrackGroup group = new TrackGroup("Drums");
        Track kick = new Track("Kick", TrackType.AUDIO);
        kick.setVolume(0.8);
        group.addTrack(kick);

        group.scaleVolume(2.0);

        assertThat(kick.getVolume()).isEqualTo(1.0);
    }

    @Test
    void shouldRejectNegativeScaleFactor() {
        TrackGroup group = new TrackGroup("Drums");
        assertThatThrownBy(() -> group.scaleVolume(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldHandleEmptyGroupOperations() {
        TrackGroup group = new TrackGroup("Empty");

        // These should not throw even with no members
        group.setMuted(true);
        group.setSolo(true);
        group.setArmed(true);
        group.scaleVolume(0.5);
    }
}
