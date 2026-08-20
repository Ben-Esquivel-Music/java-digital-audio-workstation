package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.midi.SoundFontAssignment;
import com.benesquivelmusic.daw.core.midi.fluidsynth.FluidSynthBindings;
import com.benesquivelmusic.daw.core.midi.fluidsynth.FluidSynthRenderer;
import com.benesquivelmusic.daw.core.midi.javasound.JavaSoundRenderer;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.midi.MidiEvent;
import com.benesquivelmusic.daw.sdk.midi.SoundFontRenderer;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages SoundFont-based MIDI rendering for MIDI tracks in the audio engine.
 *
 * <p>For each MIDI track with a {@link SoundFontAssignment}, this class maintains
 * a {@link SoundFontRenderer} instance, loads the assigned SoundFont, selects the
 * correct bank/program, and renders MIDI note events into audio buffers.</p>
 *
 * <p>Renderer creation and SoundFont loading are performed eagerly via
 * {@link #prepareRenderer(Track)} — called from the UI thread when assignments
 * change or during engine start. The audio-thread method
 * {@link #renderMidiTrack(Track, float[][], double, double, double, int, int)}
 * only accesses already-initialized renderers and never performs I/O.</p>
 *
 * <p>MIDI note events are rendered with sample-accurate timing by splitting the
 * segment into sub-chunks around each note-on/note-off boundary and rendering
 * each sub-chunk separately. Which events belong to a segment is decided by the
 * beat window the caller passes to
 * {@link #renderMidiTrack(Track, float[][], double, double, double, int, int)},
 * and the two event kinds take opposite ends of it: note-ONs are admitted on
 * the half-open {@code [startBeat, endBeat)}, note-OFFs on the mirror-image
 * {@code (startBeat, endBeat]} — EXCLUSIVE on the left, INCLUSIVE on the
 * right. That right-inclusivity is deliberate and load-bearing: it is how a
 * note ending exactly at the loop end gets released on the lap it belongs to.
 * Because note-ons and note-offs are point events, the window is computed
 * loop-exactly by the caller rather than being read off the segment's own
 * fractional render cursor, and at a loop wrap it may begin up to one frame
 * before that cursor. See that method for the full contract.</p>
 *
 * <p>When the FluidSynth native library is not available, the renderer logs a
 * warning and falls back to {@link JavaSoundRenderer}. Note that the Java Sound
 * fallback cannot render raw float audio into buffers — MIDI tracks will be
 * silent in this configuration. Use FluidSynth for audible MIDI playback.</p>
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
     * Prepares a renderer for the given MIDI track by initializing the
     * synthesizer, loading the assigned SoundFont, and selecting the preset.
     *
     * <p>This method performs I/O (disk reads for SoundFont files) and native
     * allocations. It must <strong>not</strong> be called from the real-time
     * audio thread. Call it from the UI thread when a SoundFont assignment
     * changes, or during engine startup.</p>
     *
     * <p>If a renderer already exists for the track, this method checks for
     * assignment changes and reloads the SoundFont / selects a new preset
     * as needed.</p>
     *
     * @param track the MIDI track to prepare a renderer for
     */
    void prepareRenderer(Track track) {
        Objects.requireNonNull(track, "track must not be null");
        SoundFontAssignment assignment = track.getSoundFontAssignment();
        if (assignment == null) {
            return;
        }
        ensureRenderer(track, assignment);
    }

    /**
     * Renders a MIDI track's note data into the provided track buffer for the
     * given event window, with sample-accurate timing.
     *
     * <p>For each note in the track's {@link MidiClip} whose event position
     * falls in the beat window bounded by {@code startBeat} and
     * {@code endBeat}, this method computes per-event frame offsets and
     * renders in sub-chunks: render up to the next event offset, send the
     * event, continue rendering the remainder.</p>
     *
     * <h4>The window is ASYMMETRIC: ons half-open, offs right-inclusive</h4>
     * <p>The two event kinds take opposite ends of the same window:</p>
     * <ul>
     *   <li>note-ONs are admitted on the half-open
     *       {@code [startBeat, endBeat)} —
     *       {@code noteStartBeat >= startBeat && noteStartBeat < endBeat};</li>
     *   <li>note-OFFs are admitted on the mirror-image
     *       {@code (startBeat, endBeat]} —
     *       {@code noteEndBeat > startBeat && noteEndBeat <= endBeat}, i.e.
     *       EXCLUSIVE on the left and INCLUSIVE on the right.</li>
     * </ul>
     * <p>The right-inclusivity for offs is deliberate and load-bearing, not a
     * stray {@code <=}: the caller caps this window at the loop end, so it is
     * the only way a note ending exactly AT the loop end is released on the
     * lap it belongs to. It is pinned by {@code AudioEngineMidiPlaybackTest#
     * noteEndingExactlyOnTheLoopEndIsReleasedOncePerLapWhileLooping}, and the
     * matching exclusion of note-ONs at the loop end is pinned by
     * {@code noteExactlyAtTheLoopEndNeverFiresWhileLooping}. Do not
     * "regularize" either bound: the window is half-open for ons only, and
     * describing it as half-open without qualification is wrong for offs.</p>
     *
     * <h4>The window is the caller's, not the segment's own cursor</h4>
     * <p>{@code startBeat}/{@code endBeat} are the segment's loop-exact EVENT
     * WINDOW, computed by the caller — they are not simply the
     * segment's own fractional render cursor and its cursor + duration. MIDI
     * note-ons and note-offs are POINT events, and the caller's loop split is
     * clamped to whole frames, so passing the raw cursor would let a note
     * sitting exactly on the loop start fall between two laps' windows and a
     * note sitting exactly on the (exclusive) loop end fall inside one. The
     * caller therefore caps the window at the loop end and, at a loop wrap,
     * may begin it up to one frame BEFORE the segment's own cursor.</p>
     *
     * <p>{@code startBeat} doubles as the frame-mapping origin for every event
     * in the window, so at such a wrap the mapping origin moves WITH the
     * window rather than staying on the segment's own cursor.
     * {@link #beatToFrame} clamps its result into
     * {@code [0, framesToProcess - 1]}, and on this path only the UPPER clamp
     * is load-bearing. It is what stops a note-off landing on exactly
     * {@code framesToProcess} from being swallowed: the sub-chunk dispatcher
     * seeds its search at {@code framesToProcess} and selects strictly below
     * it, so such an event would never be sent at all. Story 315 review —
     * before that clamp was applied to the note-off end, such note-offs were
     * dropped outright and their notes sustained until the next
     * {@link #allNotesOff()}.</p>
     *
     * <p>The LOWER clamp is unreachable under the CURRENT caller contract
     * only. {@code RenderPipeline.renderSegment} passes the very same
     * {@code eventWindowStartBeat} as both the window start and the
     * frame-mapping origin, and admission is {@code noteStartBeat >=
     * startBeat} for note-ons and {@code noteEndBeat > startBeat} for
     * note-offs — so every admitted event satisfies
     * {@code beat - startBeat >= 0} and {@code Math.max(0, …)} cannot fire
     * today. Widen only the admission bound while keeping the raw render
     * cursor as the origin and it becomes load-bearing at once; see
     * {@link #beatToFrame} for why it must stay. Do not read the two clamps
     * as a matched pair.</p>
     *
     * <p>The asymmetry with the metronome's click walk is worth recording,
     * because there the lower clamp genuinely fires. That walk widens only
     * the WINDOW ({@code gridStartBeat}) and keeps the segment's own
     * {@code segStartBeat} as the sample-offset ORIGIN, so a recovered
     * loop-start grid position sits BEFORE the origin and rounds to a
     * negative offset. The MIDI walk widens both together, which is exactly
     * why its lower clamp cannot.</p>
     *
     * <p>This method only accesses already-initialized renderers — it never
     * performs I/O or native allocations. If no renderer has been prepared
     * for the track (via {@link #prepareRenderer(Track)}), this method
     * returns silently.</p>
     *
     * @param track           the MIDI track to render
     * @param trackBuffer     the output buffer {@code [channel][frame]}
     * @param startBeat       start of this segment's event window — INCLUSIVE
     *                        for note-ons, EXCLUSIVE for note-offs — and the
     *                        frame-mapping origin for its events
     * @param endBeat         end of this segment's event window — EXCLUSIVE
     *                        for note-ons, INCLUSIVE for note-offs, which is
     *                        what releases a note ending exactly at the loop
     *                        end
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

        String trackId = track.getId();
        RendererState state = rendererStates.get(trackId);
        if (state == null) {
            // No renderer prepared — skip silently (will be prepared on the UI thread)
            return;
        }

        // Check for assignment change (just update the flag; actual reload
        // happens on the next prepareRenderer call from the UI thread)
        if (!assignment.equals(state.currentAssignment)) {
            state.needsReload = true;
        }

        MidiClip clip = track.getMidiClip();
        if (clip.isEmpty()) {
            renderSilence(state, framesToProcess);
            return;
        }

        // Render with sample-accurate sub-chunk timing
        renderWithSubChunks(state, clip.getNotes(), startBeat, endBeat,
                samplesPerBeat, trackBuffer, frameOffset, framesToProcess);
    }

    /**
     * Sends all-notes-off to all active renderers. Call this when the
     * transport loops back to prevent stuck notes spanning the loop boundary.
     */
    @RealTimeSafe
    void allNotesOff() {
        for (RendererState state : rendererStates.values()) {
            try {
                state.renderer.allNotesOff();
            } catch (Exception e) {
                // Ignore errors on the RT thread
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
        volatile boolean needsReload;

        RendererState(SoundFontRenderer renderer, SoundFontAssignment assignment) {
            this.renderer = renderer;
            this.currentAssignment = assignment;
        }
    }

    // ── Renderer lifecycle (non-RT thread only) ─────────────────────────────

    /**
     * Ensures a renderer is active for the given track with the correct
     * SoundFont assignment. If the assignment has changed, the SoundFont
     * is reloaded and the new preset is selected.
     *
     * <p>This method performs I/O and must not be called from the audio thread.</p>
     */
    private RendererState ensureRenderer(Track track, SoundFontAssignment assignment) {
        String trackId = track.getId();
        RendererState state = rendererStates.get(trackId);

        if (state != null) {
            // Check for assignment change
            if (!assignment.equals(state.currentAssignment) || state.needsReload) {
                handleAssignmentChange(state, assignment);
                state.needsReload = false;
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
     *
     * <p>Note: the Java Sound fallback ({@link JavaSoundRenderer}) cannot render
     * raw float audio into buffers — its {@code render()} method is a no-op.
     * MIDI tracks will be silent when using the Java Sound fallback. FluidSynth
     * is required for audible MIDI track playback in the audio engine.</p>
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
        LOG.warning("FluidSynth native library not available. Falling back to Java Sound "
                + "renderer which cannot render float audio buffers — MIDI tracks will be "
                + "silent. Install FluidSynth for audible MIDI playback.");
        return new JavaSoundRenderer();
    }

    // ── Sub-chunk rendering for sample-accurate timing ──────────────────────

    /**
     * Renders MIDI notes with sample-accurate timing by splitting the segment
     * into sub-chunks around each note-on/note-off boundary.
     *
     * <p>For each event boundary, the renderer advances to the event's frame
     * offset, sends all events at that frame, then continues rendering the
     * remainder of the segment.</p>
     */
    @RealTimeSafe
    private void renderWithSubChunks(RendererState state, List<MidiNoteData> notes,
                                     double startBeat, double endBeat,
                                     double samplesPerBeat,
                                     float[][] trackBuffer, int frameOffset,
                                     int framesToProcess) {
        int currentFrame = 0;
        // Track which note-on and note-off events have been sent via
        // a simple "minimum frame to consider" that advances past sent events.
        int minEventFrame = 0;

        while (currentFrame < framesToProcess) {
            // Find the smallest event frame offset >= minEventFrame
            int nextEventFrame = findNextEventFrame(notes, startBeat, endBeat,
                    samplesPerBeat, framesToProcess, minEventFrame);

            // If no more events, nextEventFrame == framesToProcess
            boolean hasEvents = nextEventFrame < framesToProcess;

            // Render sub-chunk from currentFrame to nextEventFrame
            currentFrame = renderSubChunk(state, trackBuffer, frameOffset,
                    currentFrame, nextEventFrame);

            if (!hasEvents) {
                break;
            }

            // Send all events at nextEventFrame
            sendEventsAtFrame(state, notes, startBeat, endBeat,
                    samplesPerBeat, framesToProcess, nextEventFrame);

            // Advance past all events at this frame to avoid re-sending them
            minEventFrame = nextEventFrame + 1;
        }

        // Render any remaining frames after the last event
        renderSubChunk(state, trackBuffer, frameOffset, currentFrame, framesToProcess);
    }

    /**
     * Finds the next event frame offset >= minFrame for any note-on or note-off
     * admitted by this segment's ASYMMETRIC event window: note-ONs on the
     * half-open {@code [startBeat, endBeat)}, note-OFFs on the mirror-image
     * {@code (startBeat, endBeat]} — INCLUSIVE on the right, which is how a
     * note ending exactly at the loop end is released. See
     * {@link #renderMidiTrack(Track, float[][], double, double, double, int,
     * int)} for the full contract.
     *
     * @return the frame offset of the next event, or {@code framesToProcess}
     *         if no more events exist
     */
    @RealTimeSafe
    private static int findNextEventFrame(List<MidiNoteData> notes,
                                          double startBeat, double endBeat,
                                          double samplesPerBeat, int framesToProcess,
                                          int minFrame) {
        int nextFrame = framesToProcess;
        for (int i = 0; i < notes.size(); i++) {
            MidiNoteData note = notes.get(i);
            double noteStartBeat = note.startColumn() * BEATS_PER_COLUMN;
            double noteEndBeat = note.endColumn() * BEATS_PER_COLUMN;

            if (noteStartBeat >= startBeat && noteStartBeat < endBeat) {
                int frame = beatToFrame(noteStartBeat, startBeat, samplesPerBeat, framesToProcess - 1);
                if (frame >= minFrame && frame < nextFrame) {
                    nextFrame = frame;
                }
            }
            if (noteEndBeat > startBeat && noteEndBeat <= endBeat) {
                // Story 315 review — clamp to framesToProcess - 1, exactly as
                // the note-on branch above does. The dispatcher seeds
                // nextFrame = framesToProcess and selects only on
                // frame < nextFrame, so a note-off mapped to exactly
                // framesToProcess was NEVER sent, and the next segment's
                // window cannot recover it either — that window admits only
                // noteEndBeat > startBeat, and the following segment starts
                // past the note end. The note therefore sustained until the
                // next allNotesOff() or transport stop. At 44100 Hz with
                // 512-frame blocks and looping disabled, 68 BPM lost the
                // note-off of every 1/16 column from 1 to 8. Note-ons were
                // always safe because they already clamp to
                // framesToProcess - 1.
                int frame = beatToFrame(noteEndBeat, startBeat, samplesPerBeat,
                        framesToProcess - 1);
                if (frame >= minFrame && frame < nextFrame) {
                    nextFrame = frame;
                }
            }
        }
        return nextFrame;
    }

    /**
     * Sends all note-on/note-off events that land exactly at the given frame.
     */
    @RealTimeSafe
    private static void sendEventsAtFrame(RendererState state, List<MidiNoteData> notes,
                                          double startBeat, double endBeat,
                                          double samplesPerBeat, int framesToProcess,
                                          int targetFrame) {
        for (int i = 0; i < notes.size(); i++) {
            MidiNoteData note = notes.get(i);
            double noteStartBeat = note.startColumn() * BEATS_PER_COLUMN;
            double noteEndBeat = note.endColumn() * BEATS_PER_COLUMN;

            if (noteStartBeat >= startBeat && noteStartBeat < endBeat) {
                int frame = beatToFrame(noteStartBeat, startBeat, samplesPerBeat, framesToProcess - 1);
                if (frame == targetFrame) {
                    try {
                        state.renderer.sendEvent(
                                MidiEvent.noteOn(note.channel(), note.noteNumber(), note.velocity()));
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Failed to send MIDI note-on", e);
                    }
                }
            }

            if (noteEndBeat > startBeat && noteEndBeat <= endBeat) {
                // Same clamp as findNextEventFrame — see the note there.
                int frame = beatToFrame(noteEndBeat, startBeat, samplesPerBeat,
                        framesToProcess - 1);
                if (frame == targetFrame) {
                    try {
                        state.renderer.sendEvent(
                                MidiEvent.noteOff(note.channel(), note.noteNumber()));
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Failed to send MIDI note-off", e);
                    }
                }
            }
        }
    }

    /**
     * Renders a sub-chunk of audio from {@code fromFrame} to {@code toFrame}
     * and copies the result into the track buffer.
     *
     * @return the new current frame position (= toFrame)
     */
    @RealTimeSafe
    private int renderSubChunk(RendererState state, float[][] trackBuffer,
                               int frameOffset, int fromFrame, int toFrame) {
        int chunkSize = toFrame - fromFrame;
        if (chunkSize <= 0) {
            return toFrame;
        }

        clearRenderBuffer(chunkSize);
        try {
            state.renderer.render(midiRenderBuffer, chunkSize);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "MIDI render failed", e);
            return toFrame;
        }

        int channels = Math.min(midiRenderBuffer.length, trackBuffer.length);
        for (int ch = 0; ch < channels; ch++) {
            for (int f = 0; f < chunkSize; f++) {
                trackBuffer[ch][frameOffset + fromFrame + f] += midiRenderBuffer[ch][f];
            }
        }
        return toFrame;
    }

    /**
     * Converts a beat position to a frame index within the current segment,
     * clamped to [0, maxFrame].
     *
     * <p>The {@code maxFrame} end is load-bearing on every call: every caller
     * passes {@code framesToProcess - 1} to keep an event off the segment's
     * exclusive end, where the sub-chunk dispatcher would never see it.</p>
     *
     * <p><b>The {@code 0} end is unreachable under the CURRENT caller
     * contract only — it is not dead code, and it must not be deleted.</b>
     * {@code RenderPipeline.renderSegment} today passes the very same
     * {@code eventWindowStartBeat} as both the admission bound and the
     * frame-mapping origin (see
     * {@link #renderMidiTrack(Track, float[][], double, double, double, int,
     * int)}), so every admitted event satisfies {@code beat >= startBeat} and
     * maps to a non-negative offset. Widen only the admission bound while
     * keeping the raw render cursor as the origin — which is exactly the
     * shape {@code RenderPipeline.mixMetronomeClicks} uses, where the widened
     * grid start is decoupled from {@code segStartBeat} — and the clamp
     * becomes load-bearing immediately: it is then the only thing standing
     * between a recovered loop-start event and a negative buffer index.</p>
     *
     * <p>Deleting {@code Math.max(0, …)} leaves the whole daw-core suite
     * green, so no test would catch its removal, and none would catch the
     * out-of-bounds write a later change to the caller would then expose.
     * That is a reason to keep it, not permission to drop it.</p>
     */
    @RealTimeSafe
    private static int beatToFrame(double beat, double startBeat,
                                   double samplesPerBeat, int maxFrame) {
        int frame = (int) Math.round((beat - startBeat) * samplesPerBeat);
        return Math.max(0, Math.min(frame, maxFrame));
    }

    // ── Buffer utilities ────────────────────────────────────────────────────

    private void clearRenderBuffer(int framesToProcess) {
        for (float[] channel : midiRenderBuffer) {
            Arrays.fill(channel, 0, framesToProcess, 0.0f);
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
