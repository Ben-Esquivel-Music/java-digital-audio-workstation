package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.ClipGainEnvelope;
import com.benesquivelmusic.daw.sdk.audio.CurveShape;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class NormalizeClipGainActionTest {

    @Test
    void normalize_silentClip_leavesEnvelopeUnchanged() {
        AudioClip clip = new AudioClip("v", 0.0, 4.0, null);
        clip.setAudioData(new float[][] {new float[100]});

        new NormalizeClipGainAction(clip, -1.0).execute();

        assertThat(clip.gainEnvelope()).isEmpty();
    }

    @Test
    void normalize_toMinusOneDb_createsStartBreakpointWithCorrectOffset() {
        AudioClip clip = new AudioClip("v", 0.0, 4.0, null);
        // Peak = 0.5 → -6.0206 dBFS. Target -1 → offset = +5.0206 dB.
        clip.setAudioData(new float[][] {{0.1f, -0.5f, 0.3f, -0.2f}});

        new NormalizeClipGainAction(clip, -1.0).execute();

        assertThat(clip.gainEnvelope()).isPresent();
        double db = clip.gainEnvelope().orElseThrow().dbAtFrame(0L);
        double expected = -1.0 - 20.0 * Math.log10(0.5);
        assertThat(db).isCloseTo(expected, offset(1e-9));
    }

    @Test
    void normalize_preservesShapeByOffsettingAllBreakpoints() {
        AudioClip clip = new AudioClip("v", 0.0, 4.0, null);
        clip.setAudioData(new float[][] {{1.0f, -1.0f}}); // peak = 1.0 (0 dBFS)
        ClipGainEnvelope original = new ClipGainEnvelope(List.of(
                new ClipGainEnvelope.BreakpointDb(0L, 0.0, CurveShape.LINEAR),
                new ClipGainEnvelope.BreakpointDb(1000L, -6.0, CurveShape.LINEAR)));
        clip.setGainEnvelope(original);

        // Target -3.0 dB; peak already 0 dB → offset = -3.0, applied to all.
        new NormalizeClipGainAction(clip, -3.0).execute();

        var breakpoints = clip.gainEnvelope().orElseThrow().breakpoints();
        assertThat(breakpoints).hasSize(2);
        assertThat(breakpoints.get(0).dbGain()).isCloseTo(-3.0, offset(1e-9));
        assertThat(breakpoints.get(1).dbGain()).isCloseTo(-9.0, offset(1e-9));
    }

    @Test
    void undo_restoresPriorEnvelope() {
        AudioClip clip = new AudioClip("v", 0.0, 4.0, null);
        clip.setAudioData(new float[][] {{0.5f, -0.5f}});

        UndoManager um = new UndoManager();
        um.execute(new NormalizeClipGainAction(clip, -1.0));
        assertThat(clip.gainEnvelope()).isPresent();

        um.undo();
        assertThat(clip.gainEnvelope()).isEmpty();
    }
}
