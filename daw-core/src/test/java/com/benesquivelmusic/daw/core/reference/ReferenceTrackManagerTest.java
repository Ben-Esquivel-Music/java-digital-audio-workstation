package com.benesquivelmusic.daw.core.reference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ReferenceTrackManagerTest {

    private ReferenceTrackManager manager;

    @BeforeEach
    void setUp() {
        manager = new ReferenceTrackManager();
    }

    @Test
    void shouldStartEmpty() {
        assertThat(manager.getReferenceTracks()).isEmpty();
        assertThat(manager.getReferenceTrackCount()).isZero();
        assertThat(manager.getActiveReferenceTrack()).isNull();
        assertThat(manager.getActiveIndex()).isEqualTo(-1);
        assertThat(manager.isReferenceActive()).isFalse();
        assertThat(manager.hasReferenceTracks()).isFalse();
    }

    @Test
    void shouldAddReferenceTrack() {
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");

        manager.addReferenceTrack(ref);

        assertThat(manager.getReferenceTrackCount()).isEqualTo(1);
        assertThat(manager.getReferenceTracks()).containsExactly(ref);
        assertThat(manager.hasReferenceTracks()).isTrue();
    }

    @Test
    void shouldRejectNullReferenceTrack() {
        assertThatThrownBy(() -> manager.addReferenceTrack(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldAutoSelectFirstAddedTrack() {
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");

        manager.addReferenceTrack(ref);

        assertThat(manager.getActiveIndex()).isEqualTo(0);
        assertThat(manager.getActiveReferenceTrack()).isSameAs(ref);
    }

    @Test
    void shouldNotChangeActiveIndexOnSubsequentAdds() {
        ReferenceTrack ref1 = new ReferenceTrack("Ref1", "/audio/ref1.wav");
        ReferenceTrack ref2 = new ReferenceTrack("Ref2", "/audio/ref2.wav");

        manager.addReferenceTrack(ref1);
        manager.addReferenceTrack(ref2);

        assertThat(manager.getActiveIndex()).isEqualTo(0);
        assertThat(manager.getActiveReferenceTrack()).isSameAs(ref1);
    }

    @Test
    void shouldRemoveReferenceTrack() {
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        manager.addReferenceTrack(ref);

        assertThat(manager.removeReferenceTrack(ref)).isTrue();
        assertThat(manager.getReferenceTracks()).isEmpty();
        assertThat(manager.getActiveIndex()).isEqualTo(-1);
        assertThat(manager.hasReferenceTracks()).isFalse();
    }

    @Test
    void shouldReturnFalseWhenRemovingNonexistentTrack() {
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        assertThat(manager.removeReferenceTrack(ref)).isFalse();
    }

    @Test
    void shouldDeactivateReferenceWhenLastTrackRemoved() {
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        manager.addReferenceTrack(ref);
        manager.toggleAB();
        assertThat(manager.isReferenceActive()).isTrue();

        manager.removeReferenceTrack(ref);

        assertThat(manager.isReferenceActive()).isFalse();
    }

    @Test
    void shouldAdjustActiveIndexWhenActiveTrackRemoved() {
        ReferenceTrack ref1 = new ReferenceTrack("Ref1", "/audio/ref1.wav");
        ReferenceTrack ref2 = new ReferenceTrack("Ref2", "/audio/ref2.wav");
        manager.addReferenceTrack(ref1);
        manager.addReferenceTrack(ref2);
        manager.setActiveIndex(1);

        manager.removeReferenceTrack(ref2);

        assertThat(manager.getActiveIndex()).isEqualTo(0);
        assertThat(manager.getActiveReferenceTrack()).isSameAs(ref1);
    }

    @Test
    void shouldSetActiveIndex() {
        ReferenceTrack ref1 = new ReferenceTrack("Ref1", "/audio/ref1.wav");
        ReferenceTrack ref2 = new ReferenceTrack("Ref2", "/audio/ref2.wav");
        manager.addReferenceTrack(ref1);
        manager.addReferenceTrack(ref2);

        manager.setActiveIndex(1);

        assertThat(manager.getActiveIndex()).isEqualTo(1);
        assertThat(manager.getActiveReferenceTrack()).isSameAs(ref2);
    }

    @Test
    void shouldRejectActiveIndexOutOfRange() {
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        manager.addReferenceTrack(ref);

        assertThatThrownBy(() -> manager.setActiveIndex(-1))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> manager.setActiveIndex(1))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    // ── A/B toggle tests ────────────────────────────────────────────────────

    @Test
    void shouldToggleAB() {
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        manager.addReferenceTrack(ref);

        manager.toggleAB();
        assertThat(manager.isReferenceActive()).isTrue();

        manager.toggleAB();
        assertThat(manager.isReferenceActive()).isFalse();
    }

    @Test
    void shouldNotToggleToReferenceWhenEmpty() {
        manager.toggleAB();
        assertThat(manager.isReferenceActive()).isFalse();
    }

    @Test
    void shouldSetReferenceActive() {
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        manager.addReferenceTrack(ref);

        manager.setReferenceActive(true);
        assertThat(manager.isReferenceActive()).isTrue();

        manager.setReferenceActive(false);
        assertThat(manager.isReferenceActive()).isFalse();
    }

    @Test
    void shouldNotActivateReferenceWhenEmpty() {
        manager.setReferenceActive(true);
        assertThat(manager.isReferenceActive()).isFalse();
    }

    // ── Level matching tests ────────────────────────────────────────────────

    @Test
    void shouldLevelMatchActiveReference() {
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        ref.setIntegratedLufs(-11.0);
        manager.addReferenceTrack(ref);
        manager.setMixIntegratedLufs(-14.0);

        manager.levelMatchActiveReference();

        // mix = -14, ref = -11, offset should be -14 - (-11) = -3 dB
        assertThat(ref.getGainOffsetDb()).isCloseTo(-3.0, within(0.001));
    }

    @Test
    void shouldNotLevelMatchWhenNoActive() {
        manager.setMixIntegratedLufs(-14.0);
        manager.levelMatchActiveReference(); // should not throw
    }

    @Test
    void shouldNotLevelMatchWhenMixAtFloor() {
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        ref.setIntegratedLufs(-14.0);
        ref.setGainOffsetDb(5.0);
        manager.addReferenceTrack(ref);
        // mix at floor: -120.0

        manager.levelMatchActiveReference();

        assertThat(ref.getGainOffsetDb()).isEqualTo(5.0); // unchanged
    }

    @Test
    void shouldNotLevelMatchWhenRefAtFloor() {
        ReferenceTrack ref = new ReferenceTrack("Ref", "/audio/ref.wav");
        ref.setGainOffsetDb(5.0);
        // integratedLufs defaults to -120.0
        manager.addReferenceTrack(ref);
        manager.setMixIntegratedLufs(-14.0);

        manager.levelMatchActiveReference();

        assertThat(ref.getGainOffsetDb()).isEqualTo(5.0); // unchanged
    }

    @Test
    void shouldLevelMatchAllReferences() {
        ReferenceTrack ref1 = new ReferenceTrack("Ref1", "/audio/ref1.wav");
        ref1.setIntegratedLufs(-11.0);
        ReferenceTrack ref2 = new ReferenceTrack("Ref2", "/audio/ref2.wav");
        ref2.setIntegratedLufs(-16.0);
        manager.addReferenceTrack(ref1);
        manager.addReferenceTrack(ref2);
        manager.setMixIntegratedLufs(-14.0);

        manager.levelMatchAllReferences();

        assertThat(ref1.getGainOffsetDb()).isCloseTo(-3.0, within(0.001));
        assertThat(ref2.getGainOffsetDb()).isCloseTo(2.0, within(0.001));
    }

    @Test
    void shouldStoreMixIntegratedLufs() {
        manager.setMixIntegratedLufs(-14.0);
        assertThat(manager.getMixIntegratedLufs()).isEqualTo(-14.0);
    }

    @Test
    void shouldReturnUnmodifiableReferenceTrackList() {
        assertThatThrownBy(() -> manager.getReferenceTracks().add(
                new ReferenceTrack("Illegal", "/audio/illegal.wav")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldSupportMultipleReferenceTracks() {
        ReferenceTrack ref1 = new ReferenceTrack("Ref1", "/audio/ref1.wav");
        ReferenceTrack ref2 = new ReferenceTrack("Ref2", "/audio/ref2.wav");
        ReferenceTrack ref3 = new ReferenceTrack("Ref3", "/audio/ref3.wav");
        manager.addReferenceTrack(ref1);
        manager.addReferenceTrack(ref2);
        manager.addReferenceTrack(ref3);

        assertThat(manager.getReferenceTrackCount()).isEqualTo(3);
        assertThat(manager.getReferenceTracks()).containsExactly(ref1, ref2, ref3);
    }
}
