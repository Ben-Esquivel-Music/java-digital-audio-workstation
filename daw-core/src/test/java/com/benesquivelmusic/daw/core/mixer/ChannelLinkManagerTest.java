package com.benesquivelmusic.daw.core.mixer;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates the behaviors required by the issue
 * "Mixer Channel Link (Stereo Pairing of Mono Channels)":
 *
 * <ul>
 *   <li>Moving a fader on a linked channel moves its pair (both
 *       {@link LinkMode#ABSOLUTE} and {@link LinkMode#RELATIVE} modes).</li>
 *   <li>Pan mirror works correctly: left at -0.3 → right at +0.3.</li>
 *   <li>Unlink preserves both channels' values.</li>
 *   <li>Per-attribute toggles ({@code linkFaders}, {@code linkPans},
 *       {@code linkMuteSolo}) gate propagation as expected.</li>
 *   <li>A channel can be in at most one link.</li>
 * </ul>
 */
class ChannelLinkManagerTest {

    private static final double TOL = 1e-9;

    @Test
    void newManagerHasNoLinks() {
        ChannelLinkManager manager = new ChannelLinkManager();

        assertThat(manager.getLinks()).isEmpty();
        assertThat(manager.isLinked(UUID.randomUUID())).isFalse();
        assertThat(manager.partnerOf(UUID.randomUUID())).isNull();
    }

    @Test
    void linkRegistersPairAndExposesPartnerLookup() {
        ChannelLinkManager manager = new ChannelLinkManager();
        UUID left = UUID.randomUUID();
        UUID right = UUID.randomUUID();

        manager.link(ChannelLink.ofPair(left, right));

        assertThat(manager.isLinked(left)).isTrue();
        assertThat(manager.isLinked(right)).isTrue();
        assertThat(manager.partnerOf(left)).isEqualTo(right);
        assertThat(manager.partnerOf(right)).isEqualTo(left);
        assertThat(manager.getLinks()).hasSize(1);
    }

    @Test
    void linkingAlreadyLinkedChannelThrows() {
        ChannelLinkManager manager = new ChannelLinkManager();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        manager.link(ChannelLink.ofPair(a, b));

        assertThatThrownBy(() -> manager.link(ChannelLink.ofPair(a, c)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> manager.link(ChannelLink.ofPair(c, b)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void absoluteVolumeLinkSetsPartnerToSourceValue() {
        ChannelLinkManager manager = new ChannelLinkManager();
        MixerChannel left = new MixerChannel("L");
        MixerChannel right = new MixerChannel("R");
        left.setVolume(0.4);
        right.setVolume(0.9);

        ChannelLink link = new ChannelLink(UUID.randomUUID(), UUID.randomUUID(),
                LinkMode.ABSOLUTE, true, true, true, true, true);

        double oldVol = left.getVolume();
        left.setVolume(0.7);
        manager.applyVolumeChange(link, left, right, oldVol, left.getVolume());

        assertThat(right.getVolume()).isCloseTo(0.7, org.assertj.core.data.Offset.offset(TOL));
    }

    @Test
    void relativeVolumeLinkPreservesOffsetAndShiftsPartnerByDelta() {
        ChannelLinkManager manager = new ChannelLinkManager();
        MixerChannel left = new MixerChannel("L");
        MixerChannel right = new MixerChannel("R");
        left.setVolume(0.5);
        right.setVolume(0.8);   // pre-existing +0.3 offset

        ChannelLink link = new ChannelLink(UUID.randomUUID(), UUID.randomUUID(),
                LinkMode.RELATIVE, true, true, true, true, true);

        double oldVol = left.getVolume();
        left.setVolume(0.6);    // +0.1 delta
        manager.applyVolumeChange(link, left, right, oldVol, left.getVolume());

        // 0.8 + 0.1 = 0.9; offset preserved.
        assertThat(right.getVolume()).isCloseTo(0.9, org.assertj.core.data.Offset.offset(TOL));
    }

    @Test
    void relativeVolumeLinkClampsPartnerWithinFaderRange() {
        ChannelLinkManager manager = new ChannelLinkManager();
        MixerChannel left = new MixerChannel("L");
        MixerChannel right = new MixerChannel("R");
        left.setVolume(0.5);
        right.setVolume(0.95);

        ChannelLink link = new ChannelLink(UUID.randomUUID(), UUID.randomUUID(),
                LinkMode.RELATIVE, true, true, true, true, true);

        double oldVol = left.getVolume();
        left.setVolume(0.8); // +0.3 → would push right to 1.25
        manager.applyVolumeChange(link, left, right, oldVol, left.getVolume());

        assertThat(right.getVolume()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(TOL));
    }

    @Test
    void panChangeMirrorsAroundCentre() {
        ChannelLinkManager manager = new ChannelLinkManager();
        MixerChannel right = new MixerChannel("R");
        right.setPan(0.0);

        ChannelLink link = ChannelLink.ofPair(UUID.randomUUID(), UUID.randomUUID());

        manager.applyPanChange(link, right, -0.3);
        assertThat(right.getPan()).isCloseTo(0.3, org.assertj.core.data.Offset.offset(TOL));

        manager.applyPanChange(link, right, 0.7);
        assertThat(right.getPan()).isCloseTo(-0.7, org.assertj.core.data.Offset.offset(TOL));
    }

    @Test
    void panMirrorIdenticalInBothModes() {
        // Pan is a position, not an offset, so absolute and relative modes must mirror identically.
        ChannelLinkManager manager = new ChannelLinkManager();
        MixerChannel rightAbs = new MixerChannel("R-abs");
        MixerChannel rightRel = new MixerChannel("R-rel");

        ChannelLink absLink = new ChannelLink(UUID.randomUUID(), UUID.randomUUID(),
                LinkMode.ABSOLUTE, true, true, true, true, true);
        ChannelLink relLink = new ChannelLink(UUID.randomUUID(), UUID.randomUUID(),
                LinkMode.RELATIVE, true, true, true, true, true);

        manager.applyPanChange(absLink, rightAbs, -0.5);
        manager.applyPanChange(relLink, rightRel, -0.5);

        assertThat(rightAbs.getPan()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(TOL));
        assertThat(rightRel.getPan()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(TOL));
    }

    @Test
    void muteAndSoloChangesPropagate() {
        ChannelLinkManager manager = new ChannelLinkManager();
        MixerChannel partner = new MixerChannel("R");

        ChannelLink link = ChannelLink.ofPair(UUID.randomUUID(), UUID.randomUUID());

        manager.applyMuteChange(link, partner, true);
        assertThat(partner.isMuted()).isTrue();
        manager.applyMuteChange(link, partner, false);
        assertThat(partner.isMuted()).isFalse();

        manager.applySoloChange(link, partner, true);
        assertThat(partner.isSolo()).isTrue();
        manager.applySoloChange(link, partner, false);
        assertThat(partner.isSolo()).isFalse();
    }

    @Test
    void perAttributeTogglesGatePropagation() {
        ChannelLinkManager manager = new ChannelLinkManager();
        MixerChannel partner = new MixerChannel("R");
        partner.setVolume(0.5);
        partner.setPan(0.0);

        ChannelLink fadersOff = new ChannelLink(UUID.randomUUID(), UUID.randomUUID(),
                LinkMode.ABSOLUTE, false, false, false, false, false);

        // No-op: linkFaders=false.
        MixerChannel src = new MixerChannel("L");
        src.setVolume(0.9);
        manager.applyVolumeChange(fadersOff, src, partner, 0.5, 0.9);
        assertThat(partner.getVolume()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(TOL));

        // No-op: linkPans=false.
        manager.applyPanChange(fadersOff, partner, -0.4);
        assertThat(partner.getPan()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(TOL));

        // No-op: linkMuteSolo=false.
        manager.applyMuteChange(fadersOff, partner, true);
        assertThat(partner.isMuted()).isFalse();
    }

    @Test
    void unlinkRemovesLinkAndPreservesChannelValues() {
        ChannelLinkManager manager = new ChannelLinkManager();
        MixerChannel left = new MixerChannel("L");
        MixerChannel right = new MixerChannel("R");
        UUID leftId = UUID.randomUUID();
        UUID rightId = UUID.randomUUID();
        manager.link(ChannelLink.ofPair(leftId, rightId));

        // Establish some state via propagation, then unlink.
        ChannelLink link = manager.getLink(leftId);
        left.setVolume(0.7);
        manager.applyVolumeChange(link, left, right, 1.0, 0.7);
        manager.applyPanChange(link, right, -0.4);
        left.setPan(-0.4);
        manager.applyMuteChange(link, right, true);
        left.setMuted(true);

        ChannelLink removed = manager.unlink(leftId);
        assertThat(removed).isNotNull();
        assertThat(manager.isLinked(leftId)).isFalse();
        assertThat(manager.isLinked(rightId)).isFalse();

        // Both channels retain their current values — unlinking does not destroy state.
        assertThat(left.getVolume()).isCloseTo(0.7, org.assertj.core.data.Offset.offset(TOL));
        assertThat(right.getVolume()).isCloseTo(0.7, org.assertj.core.data.Offset.offset(TOL));
        assertThat(left.getPan()).isCloseTo(-0.4, org.assertj.core.data.Offset.offset(TOL));
        assertThat(right.getPan()).isCloseTo(0.4, org.assertj.core.data.Offset.offset(TOL));
        assertThat(left.isMuted()).isTrue();
        assertThat(right.isMuted()).isTrue();
    }

    @Test
    void unlinkOnUnlinkedChannelReturnsNull() {
        ChannelLinkManager manager = new ChannelLinkManager();
        assertThat(manager.unlink(UUID.randomUUID())).isNull();
    }

    @Test
    void replaceUpdatesModeAndToggles() {
        ChannelLinkManager manager = new ChannelLinkManager();
        UUID left = UUID.randomUUID();
        UUID right = UUID.randomUUID();
        manager.link(ChannelLink.ofPair(left, right));

        ChannelLink updated = new ChannelLink(left, right,
                LinkMode.ABSOLUTE, true, false, true, false, false);
        manager.replace(updated);

        ChannelLink stored = manager.getLink(left);
        assertThat(stored.mode()).isEqualTo(LinkMode.ABSOLUTE);
        assertThat(stored.linkPans()).isFalse();
        assertThat(stored.linkInserts()).isFalse();
    }

    @Test
    void channelLinkRecordRejectsSameLeftAndRight() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new ChannelLink(id, id, LinkMode.ABSOLUTE,
                true, true, true, true, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
