package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.audio.ClipGainEnvelope;

import java.util.Objects;
import java.util.Optional;

/**
 * An undoable action that replaces (or clears) the {@link ClipGainEnvelope}
 * on an {@link AudioClip}.
 *
 * <p>Executing snapshots the clip's previous envelope and installs the new
 * one. Undoing restores the prior envelope reference (including the
 * absent / scalar-clip-gain state).</p>
 */
public final class SetClipGainEnvelopeAction implements UndoableAction {

    private final AudioClip clip;
    private final ClipGainEnvelope newEnvelope;

    // Snapshot for undo
    private ClipGainEnvelope originalEnvelope;
    private boolean originalEnvelopeCaptured;

    /**
     * Creates a new set-clip-gain-envelope action.
     *
     * @param clip        the clip to modify
     * @param newEnvelope the new envelope, or {@code null} to clear it
     *                    (reverting to the scalar {@code clipGain})
     */
    public SetClipGainEnvelopeAction(AudioClip clip, ClipGainEnvelope newEnvelope) {
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        this.newEnvelope = newEnvelope;
    }

    @Override
    public String description() {
        return "Set Clip Gain Envelope";
    }

    @Override
    public void execute() {
        Optional<ClipGainEnvelope> prior = clip.gainEnvelope();
        this.originalEnvelope = prior.orElse(null);
        this.originalEnvelopeCaptured = true;
        clip.setGainEnvelope(newEnvelope);
    }

    @Override
    public void undo() {
        if (!originalEnvelopeCaptured) {
            throw new IllegalStateException("undo() called before execute()");
        }
        clip.setGainEnvelope(originalEnvelope);
    }
}
