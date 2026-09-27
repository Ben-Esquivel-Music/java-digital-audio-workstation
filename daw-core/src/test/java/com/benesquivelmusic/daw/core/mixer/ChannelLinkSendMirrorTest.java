package com.benesquivelmusic.daw.core.mixer;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Story 322 — "Link Sends" is wired: {@link ChannelLinkManager#applySendChange}
 * mirrors a send edit to the partner's send for the same return bus, creating
 * the partner's send when absent, and is a no-op when the link's
 * {@code linkSends} flag is off. "Link Inserts" no longer exists on the
 * record (Audio Engine Wiring Design Book §5.6 "Stereo link").
 */
class ChannelLinkSendMirrorTest {

    private static ChannelLink linkWithSends(boolean linkSends) {
        return new ChannelLink(UUID.randomUUID(), UUID.randomUUID(),
                LinkMode.RELATIVE, true, true, true, linkSends);
    }

    @Test
    void mirrorsLevelAndTapOntoThePartnersExistingSendForTheSameBus() {
        ChannelLinkManager manager = new ChannelLinkManager();
        MixerChannel reverb = new MixerChannel("Reverb Return");
        MixerChannel delay = new MixerChannel("Delay Return");
        MixerChannel partner = new MixerChannel("R");
        Send partnerReverb = new Send(reverb, 0.1, SendTap.POST_FADER);
        Send partnerDelay = new Send(delay, 0.2, SendTap.POST_FADER);
        partner.addSend(partnerReverb);
        partner.addSend(partnerDelay);

        manager.applySendChange(linkWithSends(true), partner, reverb, 0.73, SendTap.PRE_INSERTS);

        assertThat(partnerReverb.getLevel()).isEqualTo(0.73);
        assertThat(partnerReverb.getTap()).isEqualTo(SendTap.PRE_INSERTS);
        assertThat(partnerDelay.getLevel()).as("a send to another bus is untouched").isEqualTo(0.2);
        assertThat(partner.getSends()).as("no send was created").hasSize(2);
    }

    @Test
    void createsThePartnersSendWhenItHasNoneForThatBus() {
        ChannelLinkManager manager = new ChannelLinkManager();
        MixerChannel reverb = new MixerChannel("Reverb Return");
        MixerChannel partner = new MixerChannel("R");

        manager.applySendChange(linkWithSends(true), partner, reverb, 0.45, SendTap.PRE_FADER);

        Send created = partner.getSendForTarget(reverb);
        assertThat(created).isNotNull();
        assertThat(created.getLevel()).isEqualTo(0.45);
        assertThat(created.getTap()).isEqualTo(SendTap.PRE_FADER);
        assertThat(partner.getSends()).hasSize(1);
    }

    @Test
    void isANoOpWhenLinkSendsIsOff() {
        ChannelLinkManager manager = new ChannelLinkManager();
        MixerChannel reverb = new MixerChannel("Reverb Return");
        MixerChannel partner = new MixerChannel("R");
        Send partnerReverb = new Send(reverb, 0.1, SendTap.POST_FADER);
        partner.addSend(partnerReverb);
        MixerChannel emptyPartner = new MixerChannel("R2");

        manager.applySendChange(linkWithSends(false), partner, reverb, 0.9, SendTap.PRE_INSERTS);
        manager.applySendChange(linkWithSends(false), emptyPartner, reverb, 0.9, SendTap.PRE_INSERTS);

        assertThat(partnerReverb.getLevel()).isEqualTo(0.1);
        assertThat(partnerReverb.getTap()).isEqualTo(SendTap.POST_FADER);
        assertThat(emptyPartner.getSends()).isEmpty();
    }

    @Test
    void ofPairLinksSendsAndTheRecordHasNoLinkInsertsComponent() {
        ChannelLink pair = ChannelLink.ofPair(UUID.randomUUID(), UUID.randomUUID());

        assertThat(pair.linkSends()).isTrue();
        assertThat(pair.withLinkSends(false).linkSends()).isFalse();
        assertThat(ChannelLink.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("leftChannelId", "rightChannelId", "mode",
                        "linkFaders", "linkPans", "linkMuteSolo", "linkSends");
    }

    @Test
    void rejectsNullArguments() {
        ChannelLinkManager manager = new ChannelLinkManager();
        ChannelLink link = linkWithSends(true);
        MixerChannel partner = new MixerChannel("R");
        MixerChannel target = new MixerChannel("Reverb Return");

        assertThatNullPointerException().isThrownBy(
                () -> manager.applySendChange(null, partner, target, 0.5, SendTap.POST_FADER));
        assertThatNullPointerException().isThrownBy(
                () -> manager.applySendChange(link, null, target, 0.5, SendTap.POST_FADER));
        assertThatNullPointerException().isThrownBy(
                () -> manager.applySendChange(link, partner, null, 0.5, SendTap.POST_FADER));
        assertThatNullPointerException().isThrownBy(
                () -> manager.applySendChange(link, partner, target, 0.5, null));
    }
}
