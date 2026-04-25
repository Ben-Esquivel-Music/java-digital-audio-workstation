package com.benesquivelmusic.daw.core.mixer.spatial;

import com.benesquivelmusic.daw.sdk.spatial.ImmersiveFormat;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the core invariants of the bed bus + bed-channel routing model.
 *
 * <p>Covers the two assertions called out explicitly in the issue:
 * <ul>
 *   <li>routing a mono source 0 dB to L only produces audio on channel 0
 *       of the bed bus;</li>
 *   <li>format changes preserve existing channel routings where channel
 *       names match and zero out where they do not.</li>
 * </ul>
 */
class BedBusManagerTest {

    private static final int N = 8;

    @Test
    void monoRoutedToLeftOnlyAppearsOnChannelZero() {
        BedBusManager manager = new BedBusManager(ImmersiveFormat.FORMAT_7_1_4);
        UUID trackId = UUID.randomUUID();
        manager.setRouting(BedRoutingPresets.lcr(trackId, ImmersiveFormat.FORMAT_7_1_4)
                // Override LCR to just unity on L (silence C and R).
                .withChannelGain(ImmersiveFormat.FORMAT_7_1_4.layout().indexOf(SpeakerLabel.C),
                        BedChannelRouting.SILENT_DB)
                .withChannelGain(ImmersiveFormat.FORMAT_7_1_4.layout().indexOf(SpeakerLabel.R),
                        BedChannelRouting.SILENT_DB));

        float[] mono = new float[N];
        for (int i = 0; i < N; i++) mono[i] = 0.5f;
        float[][] busOut = new float[ImmersiveFormat.FORMAT_7_1_4.channelCount()][N];

        manager.mixMonoSource(trackId, mono, busOut);

        // Channel 0 == L: receives the full mono signal at unity (0 dB).
        for (int i = 0; i < N; i++) {
            assertThat(busOut[0][i]).isEqualTo(0.5f);
        }
        // Every other channel: silent.
        for (int ch = 1; ch < busOut.length; ch++) {
            for (int i = 0; i < N; i++) {
                assertThat(busOut[ch][i])
                        .as("channel %d sample %d should be silent", ch, i)
                        .isEqualTo(0.0f);
            }
        }
    }

    @Test
    void formatChangePreservesSharedChannelsAndZeroesNewOnes() {
        BedBusManager manager = new BedBusManager(ImmersiveFormat.FORMAT_5_1);
        UUID trackId = UUID.randomUUID();
        // Custom routing: L = -3 dB, C = 0 dB, RS = -6 dB.
        BedChannelRouting initial = BedChannelRouting.silent(trackId, ImmersiveFormat.FORMAT_5_1);
        int lIdx = ImmersiveFormat.FORMAT_5_1.layout().indexOf(SpeakerLabel.L);
        int cIdx = ImmersiveFormat.FORMAT_5_1.layout().indexOf(SpeakerLabel.C);
        int rsIdx = ImmersiveFormat.FORMAT_5_1.layout().indexOf(SpeakerLabel.RS);
        initial = initial.withChannelGain(lIdx, -3.0)
                         .withChannelGain(cIdx, 0.0)
                         .withChannelGain(rsIdx, -6.0);
        manager.setRouting(initial);

        // Switch to 7.1.4: L, C, RS exist in both; LRS, RRS, LTF, RTF, LTR, RTR
        // are new and must be silent. (5.1 has 6 channels; 7.1.4 has 12.)
        manager.setFormat(ImmersiveFormat.FORMAT_7_1_4);

        BedChannelRouting after = manager.getRouting(trackId).orElseThrow();
        assertThat(after.format()).isEqualTo(ImmersiveFormat.FORMAT_7_1_4);

        int newL = ImmersiveFormat.FORMAT_7_1_4.layout().indexOf(SpeakerLabel.L);
        int newC = ImmersiveFormat.FORMAT_7_1_4.layout().indexOf(SpeakerLabel.C);
        int newRs = ImmersiveFormat.FORMAT_7_1_4.layout().indexOf(SpeakerLabel.RS);
        assertThat(after.gainDb(newL)).isEqualTo(-3.0);
        assertThat(after.gainDb(newC)).isEqualTo(0.0);
        assertThat(after.gainDb(newRs)).isEqualTo(-6.0);

        // The rear surrounds and height channels did not exist in 5.1; they must
        // be silent in the new routing.
        for (SpeakerLabel newOnly : new SpeakerLabel[]{
                SpeakerLabel.LRS, SpeakerLabel.RRS,
                SpeakerLabel.LTF, SpeakerLabel.RTF,
                SpeakerLabel.LTR, SpeakerLabel.RTR}) {
            int idx = ImmersiveFormat.FORMAT_7_1_4.layout().indexOf(newOnly);
            assertThat(after.gainDb(idx))
                    .as("new channel %s must be silent after upmix", newOnly)
                    .isEqualTo(BedChannelRouting.SILENT_DB);
        }
    }

    @Test
    void formatChangeFromWideToNarrowDropsMissingChannels() {
        // 7.1.4 -> 5.1: LRS, RRS and all height channels disappear; the
        // surviving channels (L, R, C, LFE, LS, RS) keep their gains.
        BedBusManager manager = new BedBusManager(ImmersiveFormat.FORMAT_7_1_4);
        UUID trackId = UUID.randomUUID();
        BedChannelRouting routing = BedRoutingPresets.surroundDropThrough(trackId,
                ImmersiveFormat.FORMAT_7_1_4);
        manager.setRouting(routing);

        manager.setFormat(ImmersiveFormat.FORMAT_5_1);

        BedChannelRouting after = manager.getRouting(trackId).orElseThrow();
        assertThat(after.format()).isEqualTo(ImmersiveFormat.FORMAT_5_1);
        // Surround drop-through is unity on L, R, C, LS, RS — all of which
        // exist in 5.1 and must therefore have survived.
        for (SpeakerLabel kept : new SpeakerLabel[]{
                SpeakerLabel.L, SpeakerLabel.R, SpeakerLabel.C,
                SpeakerLabel.LS, SpeakerLabel.RS}) {
            int idx = ImmersiveFormat.FORMAT_5_1.layout().indexOf(kept);
            assertThat(after.gainDb(idx)).isEqualTo(0.0);
        }
    }

    @Test
    void undoRestoresPreviousRouting() {
        BedBusManager manager = new BedBusManager(ImmersiveFormat.FORMAT_7_1_4);
        UUID trackId = UUID.randomUUID();

        BedChannelRouting first = BedRoutingPresets.stereoToLR(trackId,
                ImmersiveFormat.FORMAT_7_1_4);
        manager.setRouting(first);

        BedChannelRouting second = BedRoutingPresets.lcr(trackId,
                ImmersiveFormat.FORMAT_7_1_4);
        SetBedRoutingAction action = new SetBedRoutingAction(manager, trackId, second);
        action.execute();
        assertThat(manager.getRouting(trackId)).contains(second);

        action.undo();
        assertThat(manager.getRouting(trackId)).contains(first);
    }

    @Test
    void undoRemovesRoutingThatWasFirstAdded() {
        BedBusManager manager = new BedBusManager(ImmersiveFormat.FORMAT_7_1_4);
        UUID trackId = UUID.randomUUID();
        BedChannelRouting routing = BedRoutingPresets.lcr(trackId,
                ImmersiveFormat.FORMAT_7_1_4);

        SetBedRoutingAction action = new SetBedRoutingAction(manager, trackId, routing);
        action.execute();
        assertThat(manager.getRouting(trackId)).isPresent();

        action.undo();
        assertThat(manager.getRouting(trackId)).isEmpty();
    }
}
