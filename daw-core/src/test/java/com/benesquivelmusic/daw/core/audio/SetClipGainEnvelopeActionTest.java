package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.ClipGainEnvelope;
import com.benesquivelmusic.daw.sdk.audio.CurveShape;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class SetClipGainEnvelopeActionTest {

    @Test
    void execute_installsEnvelope() {
        AudioClip clip = new AudioClip("v", 0.0, 4.0, null);
        assertThat(clip.gainEnvelope()).isEmpty();

        ClipGainEnvelope env = new ClipGainEnvelope(List.of(
                new ClipGainEnvelope.BreakpointDb(0L, 0.0, CurveShape.LINEAR),
                new ClipGainEnvelope.BreakpointDb(1000L, -12.0, CurveShape.LINEAR)));

        UndoManager um = new UndoManager();
        um.execute(new SetClipGainEnvelopeAction(clip, env));

        assertThat(clip.gainEnvelope()).contains(env);
    }

    @Test
    void undo_restoresPriorAbsentEnvelope() {
        AudioClip clip = new AudioClip("v", 0.0, 4.0, null);
        ClipGainEnvelope env = ClipGainEnvelope.constant(-3.0);

        UndoManager um = new UndoManager();
        um.execute(new SetClipGainEnvelopeAction(clip, env));
        assertThat(clip.gainEnvelope()).contains(env);

        um.undo();
        assertThat(clip.gainEnvelope()).isEmpty();
    }

    @Test
    void undo_restoresPriorEnvelope() {
        AudioClip clip = new AudioClip("v", 0.0, 4.0, null);
        ClipGainEnvelope first = ClipGainEnvelope.constant(-3.0);
        ClipGainEnvelope second = new ClipGainEnvelope(List.of(
                new ClipGainEnvelope.BreakpointDb(0L, 0.0, CurveShape.S_CURVE),
                new ClipGainEnvelope.BreakpointDb(500L, -6.0, CurveShape.LINEAR)));

        UndoManager um = new UndoManager();
        um.execute(new SetClipGainEnvelopeAction(clip, first));
        um.execute(new SetClipGainEnvelopeAction(clip, second));
        assertThat(clip.gainEnvelope()).contains(second);

        um.undo();
        assertThat(clip.gainEnvelope()).contains(first);
        assertThat(clip.gainEnvelope().orElseThrow().dbAtFrame(0L))
                .isCloseTo(-3.0, offset(1e-12));
    }

    @Test
    void execute_null_clearsEnvelope() {
        AudioClip clip = new AudioClip("v", 0.0, 4.0, null);
        clip.setGainEnvelope(ClipGainEnvelope.constant(-12.0));

        UndoManager um = new UndoManager();
        um.execute(new SetClipGainEnvelopeAction(clip, null));
        assertThat(clip.gainEnvelope()).isEmpty();

        um.undo();
        assertThat(clip.gainEnvelope()).isPresent();
    }
}
