package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link SendTap} per-send tap-point feature on
 * {@link MixerChannel} sends. Verifies the three signal-tap positions
 * defined by the issue:
 *
 * <ul>
 *   <li>{@link SendTap#PRE_INSERTS} — bit-identical to the channel input
 *       even when an insert effect is active.</li>
 *   <li>{@link SendTap#PRE_FADER} — the send level on the return bus does
 *       not change when the channel fader moves (cue/monitor send
 *       semantics).</li>
 *   <li>{@link SendTap#POST_FADER} — bit-identical to the post-fader
 *       channel output at unity send.</li>
 * </ul>
 */
class SendTapTest {

    private static final float EPS = 1e-6f;

    // ── SendTap enum & Send.tap accessor ────────────────────────────────────

    @Test
    void sendTapEnumExposesAllThreeValues() {
        assertThat(SendTap.values()).containsExactly(
                SendTap.PRE_INSERTS, SendTap.PRE_FADER, SendTap.POST_FADER);
    }

    @Test
    void newSendDefaultsToPostFaderTap() {
        Send send = new Send(new MixerChannel("Reverb"));
        assertThat(send.getTap()).isEqualTo(SendTap.POST_FADER);
        assertThat(send.getMode()).isEqualTo(SendMode.POST_FADER);
    }

    @Test
    void sendModeConstructorMapsToTap() {
        MixerChannel target = new MixerChannel("Reverb");
        assertThat(new Send(target, 0.5, SendMode.PRE_FADER).getTap())
                .isEqualTo(SendTap.PRE_FADER);
        assertThat(new Send(target, 0.5, SendMode.POST_FADER).getTap())
                .isEqualTo(SendTap.POST_FADER);
    }

    @Test
    void sendTapConstructorIsAccepted() {
        MixerChannel target = new MixerChannel("Reverb");
        Send send = new Send(target, 0.7, SendTap.PRE_INSERTS);
        assertThat(send.getTap()).isEqualTo(SendTap.PRE_INSERTS);
        assertThat(send.getLevel()).isEqualTo(0.7);
        // Legacy mode view collapses PRE_INSERTS onto PRE_FADER for back-compat
        assertThat(send.getMode()).isEqualTo(SendMode.PRE_FADER);
    }

    @Test
    void setTapUpdatesTheTapField() {
        Send send = new Send(new MixerChannel("R"));
        send.setTap(SendTap.PRE_INSERTS);
        assertThat(send.getTap()).isEqualTo(SendTap.PRE_INSERTS);
        send.setTap(SendTap.POST_FADER);
        assertThat(send.getTap()).isEqualTo(SendTap.POST_FADER);
    }

    @Test
    void setModeKeepsTapSynchronisedForLegacyCallers() {
        Send send = new Send(new MixerChannel("R"));
        send.setMode(SendMode.PRE_FADER);
        assertThat(send.getTap()).isEqualTo(SendTap.PRE_FADER);
        send.setMode(SendMode.POST_FADER);
        assertThat(send.getTap()).isEqualTo(SendTap.POST_FADER);
    }

    // ── Render-pipeline behaviour ───────────────────────────────────────────

    @Test
    void preFaderSendLevelIsIndependentOfChannelFader() {
        // Cue/monitor send: pulling the channel fader down must NOT change
        // what the headphone mix hears.
        Mixer mixer = new Mixer();
        MixerChannel reverb = mixer.getAuxBus();
        MixerChannel ch = new MixerChannel("Vox");
        ch.addSend(new Send(reverb, 0.8, SendTap.PRE_FADER));
        mixer.addChannel(ch);

        float[][][] full = {{{1.0f}}};
        float[][] outFull = {{0.0f}};
        float[][][] returnsFull = {{{0.0f}}};
        ch.setVolume(1.0);
        mixer.mixDown(full, outFull, returnsFull, 1);

        float[][][] half = {{{1.0f}}};
        float[][] outHalf = {{0.0f}};
        float[][][] returnsHalf = {{{0.0f}}};
        ch.setVolume(0.25);
        mixer.mixDown(half, outHalf, returnsHalf, 1);

        // Identical send contribution at both fader positions
        assertThat(returnsHalf[0][0][0]).isEqualTo(returnsFull[0][0][0], org.assertj.core.data.Offset.offset(EPS));
        assertThat(returnsFull[0][0][0]).isEqualTo(0.8f, org.assertj.core.data.Offset.offset(EPS));
    }

    @Test
    void postFaderSendIsBitIdenticalToChannelOutputAtUnity() {
        // With sendLevel = 1.0 and channel volume = 1.0, the post-fader send
        // must equal the dry channel signal sample-for-sample.
        Mixer mixer = new Mixer();
        MixerChannel reverb = mixer.getAuxBus();
        MixerChannel ch = new MixerChannel("Snare");
        ch.addSend(new Send(reverb, 1.0, SendTap.POST_FADER));
        mixer.addChannel(ch);

        float[] input = {0.1f, -0.4f, 0.7f, 1.0f, -1.0f, 0.0f};
        float[][][] channelBuffers = {{input.clone()}};
        float[][] output = {{0, 0, 0, 0, 0, 0}};
        float[][][] returnBuffers = {{{0, 0, 0, 0, 0, 0}}};

        mixer.mixDown(channelBuffers, output, returnBuffers, input.length);

        for (int i = 0; i < input.length; i++) {
            assertThat(returnBuffers[0][0][i]).isEqualTo(input[i]);
        }
    }

    @Test
    void preInsertsSendIsBitIdenticalToInputEvenWithActiveInsert() {
        // PRE_INSERTS taps before the insert chain — even a destructive
        // insert (here a -6 dB gain) must NOT affect the send contribution.
        Mixer mixer = new Mixer();
        mixer.prepareForPlayback(1, 6);
        MixerChannel reverb = mixer.getAuxBus();
        MixerChannel ch = new MixerChannel("Drums");
        ch.addInsert(new InsertSlot("Gain", new TestGain(0.5f)));
        ch.addSend(new Send(reverb, 1.0, SendTap.PRE_INSERTS));
        mixer.addChannel(ch);

        float[] input = {0.1f, -0.4f, 0.7f, 1.0f, -1.0f, 0.0f};
        float[][][] channelBuffers = {{input.clone()}};
        float[][] output = {{0, 0, 0, 0, 0, 0}};
        float[][][] returnBuffers = {{{0, 0, 0, 0, 0, 0}}};

        mixer.mixDown(channelBuffers, output, returnBuffers, input.length);

        for (int i = 0; i < input.length; i++) {
            // Send sees the unprocessed input; the channel itself shows the
            // gain-reduced signal in the main output (which also receives the
            // return bus contribution, hence we check the return bus alone
            // for the bit-identical guarantee).
            assertThat(returnBuffers[0][0][i]).isEqualTo(input[i]);
        }
    }

    @Test
    void preFaderSendIsAffectedByInsertChain() {
        // Sanity check distinguishing PRE_FADER from PRE_INSERTS: PRE_FADER
        // taps AFTER inserts, so the same -6 dB insert should halve the send.
        Mixer mixer = new Mixer();
        mixer.prepareForPlayback(1, 1);
        MixerChannel reverb = mixer.getAuxBus();
        MixerChannel ch = new MixerChannel("Drums");
        ch.addInsert(new InsertSlot("Gain", new TestGain(0.5f)));
        ch.addSend(new Send(reverb, 1.0, SendTap.PRE_FADER));
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f}}};
        float[][] output = {{0.0f}};
        float[][][] returnBuffers = {{{0.0f}}};

        mixer.mixDown(channelBuffers, output, returnBuffers, 1);

        assertThat(returnBuffers[0][0][0]).isEqualTo(0.5f, org.assertj.core.data.Offset.offset(EPS));
    }

    // ── SetSendTapAction ────────────────────────────────────────────────────

    @Test
    void setSendTapActionUpdatesTapAndIsUndoable() {
        MixerChannel ch = new MixerChannel("Ch");
        MixerChannel reverb = new MixerChannel("Reverb");
        ch.addSend(new Send(reverb, 0.5, SendTap.POST_FADER));

        SetSendTapAction action = new SetSendTapAction(ch, reverb, SendTap.PRE_INSERTS);
        action.execute();
        assertThat(ch.getSendForTarget(reverb).getTap()).isEqualTo(SendTap.PRE_INSERTS);

        action.undo();
        assertThat(ch.getSendForTarget(reverb).getTap()).isEqualTo(SendTap.POST_FADER);
    }

    @Test
    void setSendTapActionIsNoOpWhenSendIsAbsent() {
        MixerChannel ch = new MixerChannel("Ch");
        MixerChannel reverb = new MixerChannel("Reverb");
        // No send routed to reverb yet.
        SetSendTapAction action = new SetSendTapAction(ch, reverb, SendTap.PRE_FADER);
        action.execute();
        action.undo();
        assertThat(ch.getSendForTarget(reverb)).isNull();
    }

    @Test
    void preInsertsSendIsCorrectWithParallelScheduler() {
        // Regression: when AudioGraphScheduler runs the parallel insert
        // pre-pass, channelBuffers may already be post-insert by the time
        // the per-channel loop executes. The pre-insert capture must
        // happen BEFORE the scheduler runs so PRE_INSERTS sends still
        // tap a truly dry signal.
        Mixer mixer = new Mixer();
        mixer.prepareForPlayback(1, 4);
        // Two channels so the scheduler's channelCount >= 2 gate triggers.
        MixerChannel ch1 = new MixerChannel("Drums");
        MixerChannel ch2 = new MixerChannel("Bass");
        ch1.addInsert(new InsertSlot("Gain", new TestGain(0.5f)));
        ch2.addInsert(new InsertSlot("Gain", new TestGain(0.5f)));
        MixerChannel reverb = mixer.getAuxBus();
        ch1.addSend(new Send(reverb, 1.0, SendTap.PRE_INSERTS));
        ch2.addSend(new Send(reverb, 1.0, SendTap.PRE_INSERTS));
        mixer.addChannel(ch1);
        mixer.addChannel(ch2);

        try (com.benesquivelmusic.daw.core.audio.AudioWorkerPool pool =
                     new com.benesquivelmusic.daw.core.audio.AudioWorkerPool(2)) {
            mixer.setGraphScheduler(
                    new com.benesquivelmusic.daw.core.audio.AudioGraphScheduler(pool, 2));

            float[] in1 = {0.2f, -0.6f, 0.4f, 1.0f};
            float[] in2 = {0.1f,  0.3f, -0.2f, -0.5f};
            float[][][] channelBuffers = {{in1.clone()}, {in2.clone()}};
            float[][] out = new float[1][4];
            float[][][] returnBuffers = {new float[1][4]};

            mixer.mixDown(channelBuffers, out, returnBuffers, 4);

            // Reverb return must equal the SUM of the two unprocessed inputs
            // — i.e. PRE_INSERTS taps the dry signal even though the
            // scheduler ran inserts in another thread.
            for (int f = 0; f < 4; f++) {
                assertThat(returnBuffers[0][0][f])
                        .as("frame %d", f)
                        .isEqualTo(in1[f] + in2[f]);
            }
        }
    }

    // ── Test fixture ────────────────────────────────────────────────────────

    private record TestGain(float gain) implements AudioProcessor {
        @Override
        public void process(float[][] in, float[][] out, int n) {
            for (int ch = 0; ch < in.length; ch++) {
                for (int i = 0; i < n; i++) {
                    out[ch][i] = in[ch][i] * gain;
                }
            }
        }

        @Override public void reset() {}
        @Override public int getInputChannelCount() { return 1; }
        @Override public int getOutputChannelCount() { return 1; }
    }
}
