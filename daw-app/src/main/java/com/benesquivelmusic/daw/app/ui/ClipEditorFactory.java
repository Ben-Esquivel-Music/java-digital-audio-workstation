package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.clip.Clip;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.scene.Node;

import java.util.Objects;

/**
 * Builds clip-detail editor {@link Node}s for the Workshop view's
 * clip-detail slot (story 281, UI Design Book §4 Concept F).
 *
 * <p>The single existing {@code AudioEditorView} / {@code MidiEditorView}
 * Nodes are part of the standard Arrangement / Editor view chrome and
 * cannot be re-parented across two scene-graph homes without disturbing
 * their layout in the original home. Workshop therefore needs a
 * <em>second</em> instance of the appropriate editor for its own clip
 * slot — both editors carry only per-clip state (waveform / piano-roll
 * notes), no singleton native resources, so cheap to construct multiple
 * times.</p>
 *
 * <p>The factory dispatches on the {@link Clip} runtime type:</p>
 *
 * <ul>
 *   <li>{@link AudioClip} → a fresh {@link AudioEditorView} with the
 *       owning track wired in via {@code setSelectedTrack(...)} so the
 *       Trim / Fade In / Fade Out handles enable themselves.</li>
 *   <li>{@link MidiClip} → a fresh {@link MidiEditorView} populated via
 *       {@code loadFromMidiClip(midiClip)} so the piano roll renders the
 *       clip's notes.</li>
 * </ul>
 *
 * <p>This class lives in {@code com.benesquivelmusic.daw.app.ui} to access
 * the package-private editor classes. It is exposed as a {@code public}
 * type so the Workshop wiring controller in {@code …ui.views} can call it.
 * Identity caching by {@code clipId} (stable {@code AudioClip.getId()} for
 * audio clips, or object identity via an {@code IdentityHashMap} for MIDI
 * clips in the {@code WorkshopSelectionHostController}) is the caller's
 * concern — this factory always returns a freshly-built Node so callers can
 * cache as they see fit.</p>
 */
public final class ClipEditorFactory {

    private ClipEditorFactory() {
        // Static utility — no instances.
    }

    /**
     * Builds a clip-detail editor Node for the given clip.
     *
     * @param clip          the clip to render (must not be {@code null})
     * @param owningTrack   the track that owns the clip — used by
     *                      {@code AudioEditorView} to enable its
     *                      handle buttons; may be {@code null} when
     *                      unknown (handles stay disabled)
     * @return a fresh editor node — {@link AudioEditorView} for an
     *         {@link AudioClip}, {@link MidiEditorView} for a
     *         {@link MidiClip}
     * @throws IllegalArgumentException if {@code clip} is neither an
     *         {@code AudioClip} nor a {@code MidiClip} (the only two
     *         {@code Clip} subtypes in this codebase)
     */
    public static Node buildEditor(Clip clip, Track owningTrack) {
        Objects.requireNonNull(clip, "clip must not be null");
        return switch (clip) {
            case AudioClip ignored -> {
                AudioEditorView view = new AudioEditorView();
                if (owningTrack != null) {
                    view.setSelectedTrack(owningTrack);
                }
                yield view;
            }
            case MidiClip midi -> {
                MidiEditorView view = new MidiEditorView();
                view.loadFromMidiClip(midi);
                yield view;
            }
            default -> throw new IllegalArgumentException(
                    "Unknown Clip subtype: " + clip.getClass().getName()
                            + " — only AudioClip and MidiClip are supported");
        };
    }

    /**
     * Convenience overload — equivalent to
     * {@code buildEditor(clip, null)}. Use when the owning track isn't
     * known (the {@code AudioEditorView}'s handle buttons stay disabled).
     *
     * @param clip the clip to render
     * @return a fresh editor node
     */
    public static Node buildEditor(Clip clip) {
        return buildEditor(clip, null);
    }
}
