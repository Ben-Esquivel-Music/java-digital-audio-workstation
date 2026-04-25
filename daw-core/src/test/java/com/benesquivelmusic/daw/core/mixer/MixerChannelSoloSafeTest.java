package com.benesquivelmusic.daw.core.mixer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the solo-safe (solo-in-place defeat) flag on
 * {@link MixerChannel} and the resulting behaviour in {@link Mixer}.
 */
class MixerChannelSoloSafeTest {

    @Test
    void newTrackChannelDefaultsToNotSoloSafe() {
        MixerChannel channel = new MixerChannel("Vocal");

        assertThat(channel.isSoloSafe()).isFalse();
    }

    @Test
    void defaultReverbReturnIsSoloSafe() {
        Mixer mixer = new Mixer();

        assertThat(mixer.getAuxBus().isSoloSafe()).isTrue();
    }

    @Test
    void newReturnBusIsSoloSafeByDefault() {
        Mixer mixer = new Mixer();

        MixerChannel returnBus = mixer.addReturnBus("Delay Return");

        assertThat(returnBus.isSoloSafe()).isTrue();
    }

    @Test
    void shouldToggleSoloSafe() {
        MixerChannel channel = new MixerChannel("Vocal");

        channel.setSoloSafe(true);
        assertThat(channel.isSoloSafe()).isTrue();

        channel.setSoloSafe(false);
        assertThat(channel.isSoloSafe()).isFalse();
    }

    @Test
    void soloingTrackKeepsSoloSafeReturnAudible() {
        // ── Arrange: vocal + drums tracks routed through a solo-safe reverb
        // return. The vocal sends to the reverb so that we can confirm the
        // soloed vocal still feeds the (solo-safe) return bus rather than
        // silencing it.
        Mixer mixer = new Mixer();
        MixerChannel vocal = new MixerChannel("Vocal");
        MixerChannel drums = new MixerChannel("Drums");
        mixer.addChannel(vocal);
        mixer.addChannel(drums);
        MixerChannel reverbReturn = mixer.getAuxBus();
        assertThat(reverbReturn.isSoloSafe())
                .as("default reverb return must be solo-safe")
                .isTrue();

        Send vocalSend = new Send(reverbReturn, 0.5, SendMode.POST_FADER);
        vocal.addSend(vocalSend);

        // Two channel buffers (vocal carries signal, drums is silent for
        // this test), one return buffer (reverb).
        int frames = 4;
        float[][][] channelBuffers = {
                {{0.5f, 0.5f, 0.5f, 0.5f}, {0.5f, 0.5f, 0.5f, 0.5f}},
                {{0.0f, 0.0f, 0.0f, 0.0f}, {0.0f, 0.0f, 0.0f, 0.0f}}
        };
        float[][] output = new float[2][frames];
        float[][][] returnBuffers = {{new float[frames], new float[frames]}};

        // ── Act: solo only the vocal.
        vocal.setSolo(true);

        mixer.mixDown(channelBuffers, output, returnBuffers, frames);

        // ── Assert: the soloed vocal's send still reaches the solo-safe
        // reverb return — its output buffer carries non-zero signal.
        boolean returnHasSignal = false;
        for (float[] ch : returnBuffers[0]) {
            for (float v : ch) {
                if (v != 0.0f) {
                    returnHasSignal = true;
                    break;
                }
            }
        }
        assertThat(returnHasSignal)
                .as("solo-safe return should still receive the soloed vocal's send")
                .isTrue();
    }

    @Test
    void soloingTrackWithSoloSafeReturnSilencesNonSoloedChannel() {
        // The flag is solo-in-place defeat for the return path; non-soloed,
        // non-solo-safe tracks must still be muted in the main bus.
        Mixer mixer = new Mixer();
        MixerChannel vocal = new MixerChannel("Vocal");
        MixerChannel drums = new MixerChannel("Drums");
        mixer.addChannel(vocal);
        mixer.addChannel(drums);

        int frames = 4;
        float[][][] channelBuffers = {
                {{0.0f, 0.0f, 0.0f, 0.0f}, {0.0f, 0.0f, 0.0f, 0.0f}},
                {{0.5f, 0.5f, 0.5f, 0.5f}, {0.5f, 0.5f, 0.5f, 0.5f}}
        };
        float[][] output = new float[2][frames];

        vocal.setSolo(true);
        mixer.mixDown(channelBuffers, output, frames);

        // Drums (not soloed, not solo-safe) is silenced from the main mix.
        for (float[] ch : output) {
            for (float v : ch) {
                assertThat(v).isEqualTo(0.0f);
            }
        }
    }

    @Test
    void clearingAllSolosRevertsToPreSoloMix() {
        // Solo + un-solo should leave the mixer producing the same output as
        // never having soloed at all.
        Mixer mixer = new Mixer();
        MixerChannel vocal = new MixerChannel("Vocal");
        MixerChannel drums = new MixerChannel("Drums");
        mixer.addChannel(vocal);
        mixer.addChannel(drums);

        int frames = 4;
        float[][][] channelBuffers = {
                {{0.25f, 0.25f, 0.25f, 0.25f}, {0.25f, 0.25f, 0.25f, 0.25f}},
                {{0.50f, 0.50f, 0.50f, 0.50f}, {0.50f, 0.50f, 0.50f, 0.50f}}
        };
        float[][] before = new float[2][frames];
        mixer.mixDown(deepCopy(channelBuffers), before, frames);

        // Solo, then clear solo on every channel.
        vocal.setSolo(true);
        vocal.setSolo(false);

        float[][] after = new float[2][frames];
        mixer.mixDown(deepCopy(channelBuffers), after, frames);

        for (int ch = 0; ch < before.length; ch++) {
            assertThat(after[ch]).containsExactly(before[ch]);
        }
    }

    @Test
    void resetSoloSafeToDefaultsRestoresExpectedFlags() {
        Mixer mixer = new Mixer();
        MixerChannel track = new MixerChannel("Vocal");
        mixer.addChannel(track);

        // Flip everything from its default to the opposite value.
        mixer.getAuxBus().setSoloSafe(false);
        track.setSoloSafe(true);
        mixer.getMasterChannel().setSoloSafe(true);

        mixer.resetSoloSafeToDefaults();

        assertThat(mixer.getAuxBus().isSoloSafe()).isTrue();
        assertThat(track.isSoloSafe()).isFalse();
        assertThat(mixer.getMasterChannel().isSoloSafe()).isFalse();
    }

    @Test
    void soloingTrackSilencesNonSoloSafeReturn() {
        // Negative case for comment 4: when a return bus is NOT solo-safe,
        // it should be silenced under solo. We solo the vocal and route its
        // send to a non-solo-safe return bus — the send runs (vocal is the
        // soloed channel) but the return must contribute nothing to the
        // main output. Compare against the same setup with solo-safe ON to
        // prove the flag is what's making the difference.
        for (boolean returnIsSafe : new boolean[]{false, true}) {
            Mixer mixer = new Mixer();
            MixerChannel vocal = new MixerChannel("Vocal");
            mixer.addChannel(vocal);
            MixerChannel reverbReturn = mixer.getAuxBus();
            reverbReturn.setSoloSafe(returnIsSafe);

            vocal.addSend(new Send(reverbReturn, 0.5, SendMode.POST_FADER));

            int frames = 4;
            float[][][] channelBuffers = {
                    {{0.5f, 0.5f, 0.5f, 0.5f}, {0.5f, 0.5f, 0.5f, 0.5f}}
            };
            float[][] output = new float[2][frames];
            float[][][] returnBuffers = {{new float[frames], new float[frames]}};

            vocal.setSolo(true);
            mixer.mixDown(channelBuffers, output, returnBuffers, frames);

            float returnSum = 0.0f;
            for (float[] ch : returnBuffers[0]) {
                for (float v : ch) {
                    returnSum += v;
                }
            }

            if (returnIsSafe) {
                assertThat(returnSum)
                        .as("solo-safe return should still receive the send")
                        .isNotZero();
            } else {
                assertThat(returnSum)
                        .as("non-solo-safe return must be silenced under solo")
                        .isZero();
            }
        }
    }

    @Test
    void soloingReturnBusSilencesOtherTracks() {
        // Soloing a return bus should mute every non-soloed, non-solo-safe
        // track channel — symmetrical to soloing a track.
        Mixer mixer = new Mixer();
        MixerChannel drums = new MixerChannel("Drums");
        mixer.addChannel(drums);

        int frames = 4;
        float[][][] channelBuffers = {
                {{0.5f, 0.5f, 0.5f, 0.5f}, {0.5f, 0.5f, 0.5f, 0.5f}}
        };
        float[][] output = new float[2][frames];
        float[][][] returnBuffers = {{new float[frames], new float[frames]}};

        // Solo the (silent) reverb return.
        mixer.getAuxBus().setSolo(true);
        mixer.mixDown(channelBuffers, output, returnBuffers, frames);

        for (float[] ch : output) {
            for (float v : ch) {
                assertThat(v)
                        .as("track channel must be silenced when a return bus is soloed")
                        .isEqualTo(0.0f);
            }
        }
    }

    private static float[][][] deepCopy(float[][][] src) {
        float[][][] copy = new float[src.length][][];
        for (int i = 0; i < src.length; i++) {
            copy[i] = new float[src[i].length][];
            for (int j = 0; j < src[i].length; j++) {
                copy[i][j] = src[i][j].clone();
            }
        }
        return copy;
    }
}
