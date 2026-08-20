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
 * {@link #renderMidiTrack(Track, float[][], double, double, double, double,
 * int, int)} only accesses already-initialized renderers and never performs
 * I/O.</p>
 *
 * <p>MIDI note events are rendered with sample-accurate timing by splitting the
 * segment into sub-chunks around each note-on/note-off boundary and rendering
 * each sub-chunk separately. Which events belong to a segment is decided by the
 * beat window the caller passes to
 * {@link #renderMidiTrack(Track, float[][], double, double, double, double,
 * int, int)}, and the two event kinds take opposite ends of it: note-ONs are
 * admitted on the half-open {@code [windowStartBeat, windowEndBeat)},
 * note-OFFs on the mirror-image {@code (windowStartBeat, windowEndBeat]} —
 * EXCLUSIVE on the left, INCLUSIVE on the right. That right-inclusivity is
 * deliberate and load-bearing: it is how a note ending exactly at the loop end
 * gets released on the lap it belongs to.</p>
 *
 * <p>Because note-ons and note-offs are POINT events, that window is not the
 * segment's raw fractional cursor range: it is the segment's
 * FRAME-OWNERSHIP interval, computed by the caller. At an ordinary
 * continuation edge it is the cursor range shifted back half a frame at both
 * ends, so that ownership matches the nearest-frame rounding the mapping
 * below performs; at a HARD edge — a loop boundary, or a transport
 * discontinuity such as the first segment of a render or a seek — it is
 * beat-exact, because there is no previous segment whose declined events
 * could be carried in. The frame-mapping origin travels SEPARATELY, as the
 * method's own {@code frameOriginBeat} argument. See that method for the full
 * contract.</p>
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
     * falls in the beat window bounded by {@code windowStartBeat} and
     * {@code windowEndBeat}, this method maps that position to a frame offset
     * relative to {@code frameOriginBeat} and renders in sub-chunks: render up
     * to the next event offset, send the event, continue rendering the
     * remainder.</p>
     *
     * <h4>The window is ASYMMETRIC: ons half-open, offs right-inclusive</h4>
     * <p>The two event kinds take opposite ends of the same window:</p>
     * <ul>
     *   <li>note-ONs are admitted on the half-open
     *       {@code [windowStartBeat, windowEndBeat)} —
     *       {@code noteStartBeat >= windowStartBeat
     *       && noteStartBeat < windowEndBeat};</li>
     *   <li>note-OFFs are admitted on the mirror-image
     *       {@code (windowStartBeat, windowEndBeat]} —
     *       {@code noteEndBeat > windowStartBeat
     *       && noteEndBeat <= windowEndBeat}, i.e. EXCLUSIVE on the left and
     *       INCLUSIVE on the right.</li>
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
     * <h4>ADMISSION and MAPPING are separate questions, so they take separate
     * parameters</h4>
     * <p>{@code windowStartBeat}/{@code windowEndBeat} decide WHICH segment
     * owns an event. {@code frameOriginBeat} decides WHICH FRAME of this
     * segment it lands on: every admitted position is mapped with
     * {@code round((beat - frameOriginBeat) × samplesPerBeat)}. The two used
     * to be one and the same value, and that is precisely what could not be
     * made consistent — admission is a test in BEATS, mapping is a rounding
     * to FRAMES, and the two domains disagree over the last half frame of
     * every segment. A position in {@code (N - 0.5, N]} frames
     * ({@code N = framesToProcess}) was admitted here and rounded to
     * {@code N}, outside the renderable {@code [0, N - 1]}, so it had to be
     * dragged back to {@code N - 1} — one sample early, systematically, for
     * every event landing in the last half frame of every block, and for
     * note-ons exactly as much as for note-offs.</p>
     *
     * <p>The caller resolves it by handing down the segment's
     * FRAME-OWNERSHIP interval rather than its cursor range. At an ordinary
     * continuation edge that window is
     * {@code [origin - ½ frame, origin + (N - ½) frames)} — exactly the set
     * of beats whose nearest frame lies in {@code [0, N - 1]}. A straggler
     * 0.06 frame short of the segment's end is then not admitted here at all;
     * the NEXT segment admits it, because its own window reaches half a frame
     * back past its origin, and maps it to FRAME 0 — the frame the rounding
     * said it belonged on all along. The event is CARRIED across the
     * boundary; its quantized frame is preserved, not overridden. Consecutive
     * windows tile the timeline exactly, because a segment does not
     * recompute its left edge from its own cursor: {@code RenderPipeline}
     * HANDS it the previous segment's right edge, so the two are the same
     * double — no gap for an event to fall through, no overlap to double-fire
     * it.</p>
     *
     * <p>Two kinds of edge are NOT continuation edges, and the caller does
     * not shift either of them.</p>
     * <ul>
     *   <li>A LOOP EDGE. The window ends beat-exactly at {@code loopEnd}, so
     *       a note-off sitting exactly there is still admitted by the
     *       pre-wrap segment through the {@code <=} above — there is no next
     *       segment in that lap to carry it into. And a lap start begins
     *       beat-exactly at {@code loopStart}, so nothing in the half frame
     *       BELOW the loop start — outside the half-open loop — leaks in.
     *   </li>
     *   <li>A TRANSPORT DISCONTINUITY: the first segment of a render, or a
     *       seek. No previous segment declined anything there, so there is
     *       nothing to carry, and reaching back would let a seek landing
     *       inside a note re-trigger its note-on. The caller detects this by
     *       remembering the previous window end rather than by testing the
     *       cursor, so the distinction costs no tolerance.</li>
     * </ul>
     *
     * <p>{@code frameOriginBeat} is normally the segment's own render cursor.
     * At a lap start whose whole-frame-quantized wrap left a sub-frame
     * residue the caller moves it back onto {@code loopStart}, so a note
     * sitting exactly on the loop start maps to the lap's FIRST frame instead
     * of saturating against the upper clamp and sounding on the segment's
     * last one. {@code AudioEngineMidiPlaybackTest#
     * noteAtANonZeroLoopStartFiresOnEveryLapAtTheLapsFirstFrame} pins those
     * frames, not merely the count.</p>
     *
     * <h4>What is left for the two clamps in {@link #beatToFrame}</h4>
     * <p>The UPPER clamp is no longer the general boundary rule it was. It
     * fires only (a) on a LOOP-END window, where pulling a note-off admitted
     * beat-exactly at {@code loopEnd} back onto {@code framesToProcess - 1}
     * IS the release-before-the-wrap semantics rather than a re-quantization,
     * and (b) on the exact half-frame tie at an ordinary right edge, which
     * the note-off window's inclusive {@code <=} deliberately keeps in the
     * earlier segment — and {@code framesToProcess - 1} is that segment's
     * last frame. It stays load-bearing in both: the sub-chunk dispatcher
     * seeds its search at {@code framesToProcess} and selects strictly below
     * it, so an event mapped to exactly {@code framesToProcess} would never
     * be sent at all, and before story 315 applied a clamp to the note-off
     * end such note-offs were dropped outright and their notes sustained
     * until the next {@link #allNotesOff()}.</p>
     *
     * <p>The LOWER clamp is reachable only through floating-point residue at
     * the window's own lower edge. An ordinary continuation window starts
     * half a frame BELOW the origin, so an admitted position legitimately
     * maps to an offset in {@code [-0.5, 0)}, all of which
     * {@code Math.round} takes to {@code 0} in exact arithmetic (Java rounds
     * half UP, so {@code -0.5} goes to {@code 0}). What the clamp catches is
     * {@code (beat - frameOriginBeat) × samplesPerBeat} evaluating a hair
     * BELOW {@code -0.5} for a beat the admission test accepted, where
     * {@code Math.round} returns {@code -1}. It is no longer the
     * unreachable-but-kept-on-principle line it was while the window start
     * doubled as the origin. The metronome's click walk reaches the same
     * clamp from the other side, and the contrast is worth recording: that
     * walk widens only the WINDOW ({@code gridStartBeat}) and keeps the
     * segment's own {@code segStartBeat} as the sample-offset ORIGIN, so a
     * recovered loop-start grid position sits a whole frame before the origin
     * and rounds to a genuinely negative offset. Here the origin moves WITH
     * the lap start, so the exposure is a floating-point residue rather than
     * a structural one — but it is exposure all the same.</p>
     *
     * <p>This method only accesses already-initialized renderers — it never
     * performs I/O or native allocations. If no renderer has been prepared
     * for the track (via {@link #prepareRenderer(Track)}), this method
     * returns silently.</p>
     *
     * @param track           the MIDI track to render
     * @param trackBuffer     the output buffer {@code [channel][frame]}
     * @param windowStartBeat start of this segment's event window — INCLUSIVE
     *                        for note-ons, EXCLUSIVE for note-offs. The
     *                        previous segment's window end (half a frame
     *                        below {@code frameOriginBeat}) at an ordinary
     *                        continuation edge; equal to
     *                        {@code frameOriginBeat} at a lap start or a
     *                        transport discontinuity
     * @param windowEndBeat   end of this segment's event window — EXCLUSIVE
     *                        for note-ons, INCLUSIVE for note-offs, which is
     *                        what releases a note ending exactly at the loop
     *                        end
     * @param frameOriginBeat the beat that maps to frame 0 of this segment —
     *                        the segment's own render cursor, or
     *                        {@code loopStart} at a lap start whose wrap left
     *                        a sub-frame residue. Independent of the window
     *                        bounds; do not conflate the two again
     * @param samplesPerBeat  samples per beat at the current tempo
     * @param frameOffset     the frame offset within the track buffer
     * @param framesToProcess the number of frames to render
     */
    @RealTimeSafe
    void renderMidiTrack(Track track, float[][] trackBuffer,
                         double windowStartBeat, double windowEndBeat,
                         double frameOriginBeat, double samplesPerBeat,
                         int frameOffset, int framesToProcess) {
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
        renderWithSubChunks(state, clip.getNotes(), windowStartBeat, windowEndBeat,
                frameOriginBeat, samplesPerBeat, trackBuffer, frameOffset,
                framesToProcess);
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
                                     double windowStartBeat, double windowEndBeat,
                                     double frameOriginBeat, double samplesPerBeat,
                                     float[][] trackBuffer, int frameOffset,
                                     int framesToProcess) {
        int currentFrame = 0;
        // Track which note-on and note-off events have been sent via
        // a simple "minimum frame to consider" that advances past sent events.
        int minEventFrame = 0;

        while (currentFrame < framesToProcess) {
            // Find the smallest event frame offset >= minEventFrame
            int nextEventFrame = findNextEventFrame(notes, windowStartBeat, windowEndBeat,
                    frameOriginBeat, samplesPerBeat, framesToProcess, minEventFrame);

            // If no more events, nextEventFrame == framesToProcess
            boolean hasEvents = nextEventFrame < framesToProcess;

            // Render sub-chunk from currentFrame to nextEventFrame
            currentFrame = renderSubChunk(state, trackBuffer, frameOffset,
                    currentFrame, nextEventFrame);

            if (!hasEvents) {
                break;
            }

            // Send all events at nextEventFrame
            sendEventsAtFrame(state, notes, windowStartBeat, windowEndBeat,
                    frameOriginBeat, samplesPerBeat, framesToProcess, nextEventFrame);

            // Advance past all events at this frame to avoid re-sending them
            minEventFrame = nextEventFrame + 1;
        }

        // Render any remaining frames after the last event
        renderSubChunk(state, trackBuffer, frameOffset, currentFrame, framesToProcess);
    }

    /**
     * Finds the next event frame offset >= minFrame for any note-on or note-off
     * admitted by this segment's ASYMMETRIC event window: note-ONs on the
     * half-open {@code [windowStartBeat, windowEndBeat)}, note-OFFs on the
     * mirror-image {@code (windowStartBeat, windowEndBeat]} — INCLUSIVE on the
     * right, which is how a note ending exactly at the loop end is released.
     * Both kinds are mapped to frames against {@code frameOriginBeat}, which
     * is NOT the window start: the window answers whether this segment OWNS
     * the event, the origin answers which FRAME it lands on. See
     * {@link #renderMidiTrack(Track, float[][], double, double, double, double,
     * int, int)} for the full contract.
     *
     * @return the frame offset of the next event, or {@code framesToProcess}
     *         if no more events exist
     */
    @RealTimeSafe
    private static int findNextEventFrame(List<MidiNoteData> notes,
                                          double windowStartBeat, double windowEndBeat,
                                          double frameOriginBeat, double samplesPerBeat,
                                          int framesToProcess, int minFrame) {
        int nextFrame = framesToProcess;
        for (int i = 0; i < notes.size(); i++) {
            MidiNoteData note = notes.get(i);
            double noteStartBeat = note.startColumn() * BEATS_PER_COLUMN;
            double noteEndBeat = note.endColumn() * BEATS_PER_COLUMN;

            if (noteStartBeat >= windowStartBeat && noteStartBeat < windowEndBeat) {
                // Story 315 review (second round) — map against
                // frameOriginBeat, never against the window start. The two
                // were the same value until this round, and that is what made
                // the maxFrame clamp a general boundary rule: a position in
                // the last half frame of the segment was admitted by the beat
                // test, rounded to framesToProcess, and had to be dragged back
                // to framesToProcess - 1, one sample early. It no longer is
                // one. The caller's window is this segment's FRAME-OWNERSHIP
                // interval and ends half a frame before its exclusive end at
                // an ordinary continuation edge, so such a position is owned
                // by the NEXT segment and lands on its frame 0 — the frame the
                // rounding always said it belonged on. What is left for the
                // clamp is the loop-end window (release before the wrap) and
                // the exact half-frame tie; see renderMidiTrack.
                int frame = beatToFrame(noteStartBeat, frameOriginBeat, samplesPerBeat,
                        framesToProcess - 1);
                if (frame >= minFrame && frame < nextFrame) {
                    nextFrame = frame;
                }
            }
            if (noteEndBeat > windowStartBeat && noteEndBeat <= windowEndBeat) {
                // Same origin and the same clamp as the note-on branch above:
                // the two kinds differ only in which END of the window they
                // take, never in how they are mapped. The exact half-frame tie
                // the note-on branch's half-open "<" hands to the next
                // segment is the one this branch's inclusive "<=" keeps here,
                // and framesToProcess - 1 is where it belongs — the last frame
                // of the segment that owns it. Keeping a clamp on this branch
                // is also what stops the dispatcher swallowing the event
                // outright: it seeds nextFrame = framesToProcess and selects
                // only on frame < nextFrame, so a note-off mapped to exactly
                // framesToProcess is never sent and its note sustains until
                // the next allNotesOff() or transport stop.
                int frame = beatToFrame(noteEndBeat, frameOriginBeat, samplesPerBeat,
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
     *
     * <p>Admission and mapping are split exactly as in
     * {@link #findNextEventFrame} — same window, same origin, same clamp — so
     * the two walks agree on every event's frame by construction.</p>
     */
    @RealTimeSafe
    private static void sendEventsAtFrame(RendererState state, List<MidiNoteData> notes,
                                          double windowStartBeat, double windowEndBeat,
                                          double frameOriginBeat, double samplesPerBeat,
                                          int framesToProcess, int targetFrame) {
        for (int i = 0; i < notes.size(); i++) {
            MidiNoteData note = notes.get(i);
            double noteStartBeat = note.startColumn() * BEATS_PER_COLUMN;
            double noteEndBeat = note.endColumn() * BEATS_PER_COLUMN;

            if (noteStartBeat >= windowStartBeat && noteStartBeat < windowEndBeat) {
                // Window admits, origin maps — see findNextEventFrame.
                int frame = beatToFrame(noteStartBeat, frameOriginBeat, samplesPerBeat,
                        framesToProcess - 1);
                if (frame == targetFrame) {
                    try {
                        state.renderer.sendEvent(
                                MidiEvent.noteOn(note.channel(), note.noteNumber(), note.velocity()));
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Failed to send MIDI note-on", e);
                    }
                }
            }

            if (noteEndBeat > windowStartBeat && noteEndBeat <= windowEndBeat) {
                // Same window/origin split as the note-on branch — see the
                // note in findNextEventFrame.
                int frame = beatToFrame(noteEndBeat, frameOriginBeat, samplesPerBeat,
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
     * Converts a beat position to a frame index within the current segment by
     * nearest-frame rounding against {@code frameOriginBeat}, clamped to
     * [0, maxFrame].
     *
     * <p>Every caller passes {@code framesToProcess - 1} as {@code maxFrame},
     * and that end is still load-bearing — but it is no longer the general
     * boundary rule it was. Since the caller began handing down the segment's
     * FRAME-OWNERSHIP interval instead of its raw cursor range, an ordinary
     * continuation window ends half a frame before the segment's exclusive
     * end, so a position that would round to {@code framesToProcess} is owned
     * by the NEXT segment and lands on its frame 0. {@code maxFrame} now
     * fires in exactly two places, both of them shapes the caller chooses
     * deliberately: a LOOP-END window, where a note-off admitted beat-exactly
     * at {@code loopEnd} has no next segment in the lap to be carried into
     * and is released on the last frame before the wrap; and the exact
     * half-frame tie at an ordinary right edge, which the note-off window's
     * inclusive {@code <=} keeps in this segment. In both, dropping the clamp
     * loses the event outright — the sub-chunk dispatcher seeds its search at
     * {@code framesToProcess} and selects strictly below it.</p>
     *
     * <p><b>The {@code 0} end is a floating-point guard, and it must not be
     * deleted.</b> An ordinary continuation window starts half a frame BELOW
     * the origin, so an admitted position legitimately maps to an offset in
     * {@code [-0.5, 0)} — every value of which {@code Math.round} takes to
     * {@code 0} in exact arithmetic, Java rounding half UP. What the clamp
     * catches is the residue case: {@code (beat - frameOriginBeat) ×
     * samplesPerBeat} evaluating a hair below {@code -0.5} for a beat the
     * admission test accepted, where {@code Math.round} returns {@code -1}
     * and the dispatcher would index outside the buffer. (While the window
     * start doubled as the origin this could not happen at all, and the clamp
     * was documented as unreachable-but-kept-on-principle. It is reachable
     * now.) The same clamp fires structurally rather than marginally in
     * {@code RenderPipeline.mixMetronomeClicks}, which widens only its grid
     * WINDOW and keeps {@code segStartBeat} as the sample-offset origin.</p>
     *
     * <p>Deleting {@code Math.max(0, …)} leaves the whole daw-core suite
     * green, so no test would catch its removal, and none would catch the
     * out-of-bounds write the residue case would eventually expose. That is a
     * reason to keep it, not permission to drop it.</p>
     */
    @RealTimeSafe
    private static int beatToFrame(double beat, double frameOriginBeat,
                                   double samplesPerBeat, int maxFrame) {
        int frame = (int) Math.round((beat - frameOriginBeat) * samplesPerBeat);
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
