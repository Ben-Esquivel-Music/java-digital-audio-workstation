package com.benesquivelmusic.daw.core.mixer;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CueBusManagerTest {

    private static float[][] stereoSignal(float left, float right, int frames) {
        float[][] buf = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            buf[0][i] = left;
            buf[1][i] = right;
        }
        return buf;
    }

    @Test
    void shouldCreateAndListCueBusses() {
        CueBusManager mgr = new CueBusManager();
        CueBus a = mgr.createCueBus("Singer", 1);
        CueBus b = mgr.createCueBus("Drummer", 2);

        assertThat(mgr.getCueBusses()).containsExactly(a, b);
        assertThat(mgr.getById(a.id())).isSameAs(a);
    }

    @Test
    void shouldRejectDuplicateHardwareOutput() {
        CueBusManager mgr = new CueBusManager();
        mgr.createCueBus("Singer", 1);

        assertThatThrownBy(() -> mgr.createCueBus("Drummer", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hardware output 1");
    }

    @Test
    void independentCueMixesProduceDistinctOutput() {
        CueBusManager mgr = new CueBusManager();
        UUID vocals = UUID.randomUUID();
        UUID kick = UUID.randomUUID();

        // Singer wants their vocal loud, minimal kick.
        CueBus singer = mgr.createCueBus("Singer", 1)
                .withSend(new CueSend(vocals, 0.9, 0.0, true))
                .withSend(new CueSend(kick,   0.1, 0.0, true));
        mgr.replace(singer);

        // Drummer wants kick loud, minimal vocal.
        CueBus drummer = mgr.createCueBus("Drummer", 2)
                .withSend(new CueSend(vocals, 0.1, 0.0, true))
                .withSend(new CueSend(kick,   0.9, 0.0, true));
        mgr.replace(drummer);

        int frames = 8;
        Map<UUID, float[][]> pre = new HashMap<>();
        pre.put(vocals, stereoSignal(1.0f, 1.0f, frames));
        pre.put(kick,   stereoSignal(0.5f, 0.5f, frames));
        Map<UUID, float[][]> post = new HashMap<>();

        float[][] singerOut = new float[2][frames];
        float[][] drummerOut = new float[2][frames];
        mgr.renderCueBus(mgr.getById(singer.id()), pre, post, singerOut, frames);
        mgr.renderCueBus(mgr.getById(drummer.id()), pre, post, drummerOut, frames);

        // Center-panned equal-power 0.707 scaling applied per channel.
        // Singer: 1.0*0.9*0.707 + 0.5*0.1*0.707 ≈ 0.6718
        // Drummer: 1.0*0.1*0.707 + 0.5*0.9*0.707 ≈ 0.3889
        assertThat(singerOut[0][0]).isCloseTo(0.6718f, org.assertj.core.data.Offset.offset(0.001f));
        assertThat(drummerOut[0][0]).isCloseTo(0.3889f, org.assertj.core.data.Offset.offset(0.001f));
        // The two cue mixes are distinct for the same source audio.
        assertThat(singerOut[0][0]).isNotEqualTo(drummerOut[0][0]);
    }

    @Test
    void preFaderSendsIgnoreMainFaderMoves() {
        CueBusManager mgr = new CueBusManager();
        UUID track = UUID.randomUUID();
        CueBus bus = mgr.createCueBus("Performer", 1)
                .withSend(new CueSend(track, 1.0, 0.0, true));
        mgr.replace(bus);

        int frames = 4;
        // Pre-fader tap: engineer has full-scale dry signal here.
        Map<UUID, float[][]> pre = new HashMap<>();
        pre.put(track, stereoSignal(1.0f, 1.0f, frames));
        // Post-fader tap: engineer has pulled main fader to silence.
        Map<UUID, float[][]> post = new HashMap<>();
        post.put(track, stereoSignal(0.0f, 0.0f, frames));

        float[][] out = new float[2][frames];
        mgr.renderCueBus(mgr.getById(bus.id()), pre, post, out, frames);

        // Pre-fader send ignored the main-fader-to-silence move.
        assertThat(out[0][0]).isCloseTo(0.707f, org.assertj.core.data.Offset.offset(0.001f));

        // Flip to post-fader and the same render now tracks the main fader.
        CueBus postBus = mgr.getById(bus.id()).withSend(new CueSend(track, 1.0, 0.0, false));
        mgr.replace(postBus);
        float[][] out2 = new float[2][frames];
        mgr.renderCueBus(mgr.getById(bus.id()), pre, post, out2, frames);
        assertThat(out2[0][0]).isEqualTo(0.0f);
    }

    @Test
    void copyMainMixSeedsCueBusFromCurrentLevels() {
        CueBusManager mgr = new CueBusManager();
        CueBus bus = mgr.createCueBus("Singer", 1);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        Map<UUID, CueBusManager.MainMixLevel> mainMix = new HashMap<>();
        mainMix.put(a, new CueBusManager.MainMixLevel(0.8, -0.3));
        mainMix.put(b, new CueBusManager.MainMixLevel(0.4, 0.5));
        mgr.copyMainMix(bus.id(), mainMix);

        CueBus seeded = mgr.getById(bus.id());
        assertThat(seeded.sends()).hasSize(2);
        CueSend sendA = seeded.findSend(a);
        assertThat(sendA.gain()).isEqualTo(0.8);
        assertThat(sendA.pan()).isEqualTo(-0.3);
        // Default to pre-fader — the standard choice during tracking.
        assertThat(sendA.preFader()).isTrue();
    }

    @Test
    void createAndDeleteActionsAreUndoable() {
        CueBusManager mgr = new CueBusManager();
        CreateCueBusAction create = new CreateCueBusAction(mgr, "Singer", 1);
        create.execute();
        CueBus bus = create.getCueBus();
        assertThat(mgr.getCueBusses()).containsExactly(bus);

        create.undo();
        assertThat(mgr.getCueBusses()).isEmpty();

        create.execute(); // redo
        assertThat(mgr.getById(bus.id())).isNotNull();

        DeleteCueBusAction delete = new DeleteCueBusAction(mgr, bus.id());
        delete.execute();
        assertThat(mgr.getCueBusses()).isEmpty();
        delete.undo();
        assertThat(mgr.getById(bus.id())).isNotNull();
    }

    @Test
    void setCueSendActionCapturesPreviousSendForUndo() {
        CueBusManager mgr = new CueBusManager();
        CueBus bus = mgr.createCueBus("Singer", 1);
        UUID track = UUID.randomUUID();
        mgr.replace(bus.withSend(new CueSend(track, 0.3, 0.0, true)));

        SetCueSendAction action = new SetCueSendAction(
                mgr, bus.id(), new CueSend(track, 0.9, 0.2, false));
        action.execute();
        CueSend current = mgr.getById(bus.id()).findSend(track);
        assertThat(current.gain()).isEqualTo(0.9);

        action.undo();
        CueSend restored = mgr.getById(bus.id()).findSend(track);
        assertThat(restored.gain()).isEqualTo(0.3);
        assertThat(restored.preFader()).isTrue();
    }

    @Test
    void setCueSendActionRemovesSendOnUndoWhenAdding() {
        CueBusManager mgr = new CueBusManager();
        CueBus bus = mgr.createCueBus("Singer", 1);
        UUID track = UUID.randomUUID();

        SetCueSendAction action = new SetCueSendAction(
                mgr, bus.id(), new CueSend(track, 0.5, 0.0, true));
        action.execute();
        assertThat(mgr.getById(bus.id()).findSend(track)).isNotNull();

        action.undo();
        assertThat(mgr.getById(bus.id()).findSend(track)).isNull();
    }
}
