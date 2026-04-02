package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.midi.SoundFontAssignment;
import com.benesquivelmusic.daw.core.midi.fluidsynth.FluidSynthBindings;
import com.benesquivelmusic.daw.core.midi.fluidsynth.FluidSynthRenderer;
import com.benesquivelmusic.daw.core.midi.javasound.JavaSoundRenderer;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.midi.MidiEvent;
import com.benesquivelmusic.daw.sdk.midi.SoundFontRenderer;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages SoundFont-based MIDI rendering for MIDI tracks in the audio engine.
 *
 * <p>For each MIDI track with a {@link SoundFontAssignment}, this class maintains
 * a {@link SoundFontRenderer} instance, loads the assigned SoundFont, selects the
 * correct bank/program, and renders MIDI note events into audio buffers.</p>
 *
 * <p>The renderer falls back to a {@link JavaSoundRenderer} when the FluidSynth
 * native library is not available on the system.</p>
 *
 * <p>MIDI note timing uses grid columns from {@link MidiNoteData}, where each
 * column equals {@value #BEATS_PER_COLUMN} beats (1/16 note at 4/4).</p>
 */
final class MidiTrackRenderer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(MidiTrackRenderer.class.getName());

    /**
     * Beats per grid column — 0.25 beats = 1/16 note in 4/4 time.
     * Matches EditorView.BEATS_PER_COLUMN.
     */
    static final double BEATS_PER_COLUMN = 0.25;

    private static final int MIDI_CHANNEL = 0;

    private final double sampleRate;
    private final int bufferSize;
    private final boolean fluidSynthAvailable;

    // Per-track state: track ID → renderer state
    private final Map<String, RendererState> rendererStates = new HashMap<>();

    // Pre-allocated stereo render buffer used during renderMidiTrack
    private float[][] midiRenderBuffer;

    // Factory for creating SoundFont renderers (overridable for testing)
    private final RendererFactory rendererFactory;

    /**
     * Creates a new MIDI track renderer.
     *
     * @param sampleRate the audio sample rate in Hz
     * @param bufferSize the audio buffer size in frames
     */
    MidiTrackRenderer(double sampleRate, int bufferSize) {
        this(sampleRate, bufferSize, null);
    }

    /**
     * Creates a new MIDI track renderer with an optional renderer factory
     * for testing.
     *
     * @param sampleRate      the audio sample rate in Hz
     * @param bufferSize      the audio buffer size in frames
     * @param rendererFactory custom factory for creating renderers, or {@code null}
     *                        to use the default (FluidSynth with Java Sound fallback)
     */
    MidiTrackRenderer(double sampleRate, int bufferSize, RendererFactory rendererFactory) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be positive: " + bufferSize);
        }
        this.sampleRate = sampleRate;
        this.bufferSize = bufferSize;
        this.fluidSynthAvailable = isFluidSynthLibraryAvailable();
        this.midiRenderBuffer = new float[2][bufferSize];
        this.rendererFactory = rendererFactory;
    }

    /**
     * Factory interface for creating {@link SoundFontRenderer} instances.
     * Package-private for testing.
     */
    @FunctionalInterface
    interface RendererFactory {
        SoundFontRenderer create();
    }

    /**
     * Renders a MIDI track's note data into the provided track buffer for the
     * given beat range.
     *
     * <p>For each note in the track's {@link MidiClip} that overlaps the beat
     * range [{@code startBeat}, {@code endBeat}), this method sends note-on and
     * note-off events at the correct frame positions, then renders the synthesizer
     * output into the track buffer.</p>
     *
     * @param track           the MIDI track to render
     * @param trackBuffer     the output buffer {@code [channel][frame]}
     * @param startBeat       the beat position at the start of this segment
     * @param endBeat         the beat position at the end of this segment
     * @param samplesPerBeat  samples per beat at the current tempo
     * @param frameOffset     the frame offset within the track buffer
     * @param framesToProcess the number of frames to render
     */
    @RealTimeSafe
    void renderMidiTrack(Track track, float[][] trackBuffer,
                         double startBeat, double endBeat,
                         double samplesPerBeat, int frameOffset,
                         int framesToProcess) {
        Objects.requireNonNull(track, "track must not be null");

        SoundFontAssignment assignment = track.getSoundFontAssignment();
        if (assignment == null) {
            return;
        }

        RendererState state = ensureRenderer(track, assignment);
        if (state == null) {
            return;
        }

        MidiClip clip = track.getMidiClip();
        if (clip.isEmpty()) {
            renderSilence(state, framesToProcess);
            return;
        }

        // Schedule note-on and note-off events for notes overlapping this segment
        scheduleNoteEvents(state, clip.getNotes(), startBeat, endBeat,
                samplesPerBeat, framesToProcess);

        // Render the synthesizer output into the pre-allocated buffer
        clearRenderBuffer(framesToProcess);
        try {
            state.renderer.render(midiRenderBuffer, framesToProcess);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "MIDI render failed for track " + track.getName(), e);
            return;
        }

        // Copy rendered audio into the track buffer (additive)
        int channels = Math.min(midiRenderBuffer.length, trackBuffer.length);
        for (int ch = 0; ch < channels; ch++) {
            for (int f = 0; f < framesToProcess; f++) {
                trackBuffer[ch][frameOffset + f] += midiRenderBuffer[ch][f];
            }
        }
    }

    /**
     * Returns whether this renderer has an active renderer state for the given track.
     *
     * @param trackId the track ID
     * @return {@code true} if a renderer is active for this track
     */
    boolean hasRenderer(String trackId) {
        return rendererStates.containsKey(trackId);
    }

    /**
     * Disposes the renderer for the given track, releasing its resources.
     *
     * @param trackId the track ID
     */
    void disposeRenderer(String trackId) {
        RendererState state = rendererStates.remove(trackId);
        if (state != null) {
            closeRendererQuietly(state.renderer);
        }
    }

    /**
     * Returns the SoundFontRenderer for the specified track, or {@code null}
     * if no renderer is active for that track.
     *
     * @param trackId the track ID
     * @return the renderer, or {@code null}
     */
    SoundFontRenderer getRenderer(String trackId) {
        RendererState state = rendererStates.get(trackId);
        return state != null ? state.renderer : null;
    }

    @Override
    public void close() {
        for (RendererState state : rendererStates.values()) {
            closeRendererQuietly(state.renderer);
        }
        rendererStates.clear();
    }

    // ── Internal state ──────────────────────────────────────────────────────

    /**
     * Per-track renderer state holding the SoundFont renderer and the
     * currently loaded SoundFont assignment for change detection.
     */
    private static final class RendererState {
        final SoundFontRenderer renderer;
        SoundFontAssignment currentAssignment;

        RendererState(SoundFontRenderer renderer, SoundFontAssignment assignment) {
            this.renderer = renderer;
            this.currentAssignment = assignment;
        }
    }

    // ── Renderer lifecycle ──────────────────────────────────────────────────

    /**
     * Ensures a renderer is active for the given track with the correct
     * SoundFont assignment. If the assignment has changed, the SoundFont
     * is reloaded and the new preset is selected.
     */
    private RendererState ensureRenderer(Track track, SoundFontAssignment assignment) {
        String trackId = track.getId();
        RendererState state = rendererStates.get(trackId);

        if (state != null) {
            // Check for assignment change
            if (!assignment.equals(state.currentAssignment)) {
                handleAssignmentChange(state, assignment);
            }
            return state;
        }

        // Create a new renderer
        SoundFontRenderer renderer = createRenderer();
        if (renderer == null) {
            return null;
        }

        try {
            renderer.initialize(sampleRate, bufferSize);
            renderer.loadSoundFont(assignment.soundFontPath());
            renderer.selectPreset(MIDI_CHANNEL, assignment.bank(), assignment.program());

            state = new RendererState(renderer, assignment);
            rendererStates.put(trackId, state);
            return state;
        } catch (Exception e) {
            LOG.log(Level.WARNING,
                    "Failed to initialize MIDI renderer for track " + track.getName(), e);
            closeRendererQuietly(renderer);
            return null;
        }
    }

    /**
     * Handles a SoundFont assignment change by reloading the SoundFont and
     * selecting the new preset without stopping the engine.
     */
    private void handleAssignmentChange(RendererState state, SoundFontAssignment newAssignment) {
        try {
            state.renderer.allNotesOff();

            // If the SoundFont path changed, reload
            if (!newAssignment.soundFontPath().equals(state.currentAssignment.soundFontPath())) {
                // Unload existing SoundFonts
                for (var sf : state.renderer.getLoadedSoundFonts()) {
                    try {
                        state.renderer.unloadSoundFont(sf.id());
                    } catch (Exception e) {
                        LOG.log(Level.FINE, "Error unloading SoundFont", e);
                    }
                }
                state.renderer.loadSoundFont(newAssignment.soundFontPath());
            }

            state.renderer.selectPreset(MIDI_CHANNEL, newAssignment.bank(), newAssignment.program());
            state.currentAssignment = newAssignment;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to handle SoundFont assignment change", e);
        }
    }

    /**
     * Creates a SoundFontRenderer, preferring FluidSynth and falling back to
     * Java Sound. Uses the injected factory if provided.
     */
    private SoundFontRenderer createRenderer() {
        if (rendererFactory != null) {
            return rendererFactory.create();
        }
        if (fluidSynthAvailable) {
            try {
                return new FluidSynthRenderer(new FluidSynthBindings());
            } catch (Exception e) {
                LOG.log(Level.WARNING,
                        "FluidSynth renderer creation failed; falling back to Java Sound", e);
            }
        }
        return new JavaSoundRenderer();
    }

    // ── Note event scheduling ───────────────────────────────────────────────

    /**
     * Schedules note-on and note-off events for notes overlapping the current
     * beat range. Events are sent to the renderer at the correct frame positions
     * within the segment.
     */
    @RealTimeSafe
    private void scheduleNoteEvents(RendererState state, List<MidiNoteData> notes,
                                    double startBeat, double endBeat,
                                    double samplesPerBeat, int framesToProcess) {
        for (int i = 0; i < notes.size(); i++) {
            MidiNoteData note = notes.get(i);
            double noteStartBeat = note.startColumn() * BEATS_PER_COLUMN;
            double noteEndBeat = note.endColumn() * BEATS_PER_COLUMN;

            // Skip notes that don't overlap this segment at all
            if (noteEndBeat <= startBeat || noteStartBeat >= endBeat) {
                continue;
            }

            // Note-on: only send if the note starts within this segment
            if (noteStartBeat >= startBeat && noteStartBeat < endBeat) {
                state.renderer.sendEvent(
                        MidiEvent.noteOn(note.channel(), note.noteNumber(), note.velocity()));
            }

            // Note-off: only send if the note ends within this segment
            if (noteEndBeat > startBeat && noteEndBeat <= endBeat) {
                state.renderer.sendEvent(
                        MidiEvent.noteOff(note.channel(), note.noteNumber()));
            }
        }
    }

    // ── Buffer utilities ────────────────────────────────────────────────────

    private void clearRenderBuffer(int framesToProcess) {
        for (float[] channel : midiRenderBuffer) {
            java.util.Arrays.fill(channel, 0, framesToProcess, 0.0f);
        }
    }

    /**
     * Renders silence through the synthesizer to keep its internal state
     * advancing (e.g., release tails of previously triggered notes).
     */
    private void renderSilence(RendererState state, int framesToProcess) {
        clearRenderBuffer(framesToProcess);
        try {
            state.renderer.render(midiRenderBuffer, framesToProcess);
        } catch (Exception e) {
            // Ignore render errors for silence
        }
    }

    // ── Utilities ───────────────────────────────────────────────────────────

    private static boolean isFluidSynthLibraryAvailable() {
        try {
            FluidSynthBindings bindings = new FluidSynthBindings();
            return bindings.isAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    private static void closeRendererQuietly(SoundFontRenderer renderer) {
        try {
            renderer.close();
        } catch (Exception e) {
            LOG.log(Level.FINE, "Error closing SoundFont renderer", e);
        }
    }
}
