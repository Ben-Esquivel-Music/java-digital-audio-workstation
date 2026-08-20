package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.midi.SoundFontAssignment;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.midi.MidiEvent;
import com.benesquivelmusic.daw.sdk.midi.SoundFontInfo;
import com.benesquivelmusic.daw.sdk.midi.SoundFontRenderer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Tests for MIDI track playback via SoundFont synthesis in the AudioEngine.
 *
 * <p>Uses a stub {@link SoundFontRenderer} to verify that the engine correctly
 * schedules note-on/note-off events and renders MIDI audio into track buffers
 * without requiring native FluidSynth libraries.</p>
 */
class AudioEngineMidiPlaybackTest {

    private static final double SAMPLE_RATE = 44_100.0;
    private static final int CHANNELS = 2;
    private static final int BUFFER_SIZE = 8;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, CHANNELS, 16, BUFFER_SIZE);

    private static final double TEMPO = 120.0;
    private static final double SAMPLES_PER_BEAT = SAMPLE_RATE * 60.0 / TEMPO;

    // ── Loop-boundary fixture (story 315) ───────────────────────────────────
    // The class-level FORMAT above uses an 8-frame buffer at 120 BPM, which is
    // far too coarse to express a loop lap. The loop-boundary regression tests
    // below therefore build their own engine/transport/mixer around the
    // constants in this block.

    /**
     * Tempo for the loop-boundary tests. At {@link #SAMPLE_RATE} this gives
     * samplesPerBeat = 44100 × 60 ÷ 128 = 20671.875 — an exact binary
     * fraction (20671 + 7/8), so every frame position below is reproducible
     * to the last bit rather than being tempo-rounding noise.
     */
    private static final double LOOP_TEMPO = 128.0;

    /** Block size in frames for the loop-boundary tests. */
    private static final int LOOP_BLOCK_FRAMES = 512;

    /** Loop region start for the loop-boundary tests, in beats. */
    private static final double LOOP_START_BEAT = 0.0;

    /**
     * Loop region end for the loop-boundary tests, in beats. 4 × 20671.875 =
     * 82687.5 frames — deliberately half a frame off the frame grid, which is
     * the ordinary case: almost no musically useful loop length lands on a
     * whole frame. The pipeline can only split a block at a whole frame, so a
     * lap boundary here is always quantized and leaves a sub-frame residue on
     * the far side of the wrap. (Every SECOND lap boundary — 165375.0,
     * 330750.0, … — does land exactly on the grid, so the residue there is
     * only the ~1e-11-frame floating-point remainder; the MIDI window test is
     * an exact {@code >=} with no epsilon, so even that residue is enough to
     * drop a note sitting on the loop start.)
     */
    private static final double LOOP_END_BEAT = 4.0;

    /**
     * samplesPerBeat for the loop-boundary fixture: 44100 × 60 ÷ 128 =
     * 20671.875, exact in binary (20671 + 7/8).
     */
    private static final double LOOP_SAMPLES_PER_BEAT = 20_671.875;

    /**
     * Frames in one lap of the loop-boundary fixture: 4 beats ×
     * {@link #LOOP_SAMPLES_PER_BEAT} = 82687.5 — a half frame, which is what
     * makes every second wrap leave a sub-frame residue and every other wrap
     * land on a whole frame.
     */
    private static final double LOOP_LAP_FRAMES = 4.0 * LOOP_SAMPLES_PER_BEAT;

    /**
     * Blocks rendered by each loop-boundary test. 1292 × 512 = 661504 frames,
     * against a lap of 82687.5 frames: 8 × 82687.5 = 661500 ≤ 661504, and
     * 9 × 82687.5 = 744187.5 > 661504. The render therefore contains exactly
     * 8 loop wraps and 9 lap starts, and a note at beat 1.0 — comfortably
     * inside the loop — is crossed on the 8 laps that reach it.
     */
    private static final int LOOP_BLOCK_COUNT = 1292;

    /** Lap starts within {@link #LOOP_BLOCK_COUNT} blocks — the initial lap plus 8 wraps. */
    private static final int LOOP_LAP_STARTS = 9;

    /** Laps within {@link #LOOP_BLOCK_COUNT} blocks that run far enough to reach beat 1.0. */
    private static final int LOOP_LAPS_REACHING_BEAT_ONE = 8;

    /**
     * Reported insert-chain latency for the delay-compensation regression
     * below. Any nonzero value makes {@code renderTracks} walk a cursor
     * shifted ahead of the transport; 16 frames is small enough that the
     * shift stays well inside one 64-frame block, so the block-entry loop
     * mapping fires as a whole-frame quantization CONTINUATION rather than
     * as a multi-block jump.
     */
    private static final int PDC_INSERT_LATENCY_FRAMES = 16;

    private static final Path DUMMY_SF2_PATH = Path.of("/tmp/test.sf2");
    private static final SoundFontAssignment ASSIGNMENT =
            new SoundFontAssignment(DUMMY_SF2_PATH, 0, 0, "Acoustic Grand Piano");

    private AudioEngine engine;
    private Transport transport;
    private Mixer mixer;

    @BeforeEach
    void setUp() {
        engine = new AudioEngine(FORMAT);
        transport = new Transport();
        transport.setTempo(TEMPO);
        mixer = new Mixer();
    }

    // ── MIDI track rendering ────────────────────────────────────────────────

    @Test
    void shouldRenderMidiTrackViaSoundFontRenderer() {
        StubSoundFontRenderer stubRenderer = new StubSoundFontRenderer(0.5f);
        MidiTrackRenderer midiRenderer = new MidiTrackRenderer(
                SAMPLE_RATE, BUFFER_SIZE, stubRenderer::newInstance);

        Track midiTrack = new Track("MIDI Track", TrackType.MIDI);
        midiTrack.setSoundFontAssignment(ASSIGNMENT);
        // Add a note at column 0 (beat 0.0), duration 4 columns (1 beat)
        midiTrack.getMidiClip().addNote(MidiNoteData.of(60, 0, 4, 100));

        MixerChannel mixerChannel = new MixerChannel("MIDI Track");
        mixer.addChannel(mixerChannel);

        transport.play();
        engine.setGraph(transport, mixer, List.of(midiTrack));
        engine.start();

        // Prepare and inject the test MidiTrackRenderer
        midiRenderer.prepareRenderer(midiTrack);
        engine.setMidiTrackRenderer(midiRenderer);

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        // The stub renderer fills with 0.5f; after mixer center-pan gain:
        // gain = cos(π/4) ≈ 0.7071
        double expectedGain = Math.cos(Math.PI / 4.0);
        for (int i = 0; i < BUFFER_SIZE; i++) {
            assertThat((double) output[0][i]).isCloseTo(0.5 * expectedGain, offset(0.01));
            assertThat((double) output[1][i]).isCloseTo(0.5 * expectedGain, offset(0.01));
        }
    }

    @Test
    void shouldSendNoteOnEventForNotesStartingInSegment() {
        StubSoundFontRenderer stubRenderer = new StubSoundFontRenderer(0.0f);
        MidiTrackRenderer midiRenderer = new MidiTrackRenderer(
                SAMPLE_RATE, BUFFER_SIZE, stubRenderer::newInstance);

        Track midiTrack = new Track("MIDI Track", TrackType.MIDI);
        midiTrack.setSoundFontAssignment(ASSIGNMENT);
        // Note at column 0 (beat 0.0), 4 columns (1 beat), C4, velocity 100
        midiTrack.getMidiClip().addNote(MidiNoteData.of(60, 0, 4, 100));

        MixerChannel mixerChannel = new MixerChannel("MIDI Track");
        mixer.addChannel(mixerChannel);

        transport.play();
        engine.setGraph(transport, mixer, List.of(midiTrack));
        engine.start();
        midiRenderer.prepareRenderer(midiTrack);
        engine.setMidiTrackRenderer(midiRenderer);

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        // Should have a note-on event
        StubSoundFontRenderer instance = stubRenderer.lastCreatedInstance;
        assertThat(instance).isNotNull();
        assertThat(instance.receivedEvents).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(MidiEvent.Type.NOTE_ON);
            assertThat(e.data1()).isEqualTo(60);
            assertThat(e.data2()).isEqualTo(100);
        });
    }

    @Test
    void shouldNotSendNoteOnForNotesOutsideSegment() {
        StubSoundFontRenderer stubRenderer = new StubSoundFontRenderer(0.0f);
        MidiTrackRenderer midiRenderer = new MidiTrackRenderer(
                SAMPLE_RATE, BUFFER_SIZE, stubRenderer::newInstance);

        Track midiTrack = new Track("MIDI Track", TrackType.MIDI);
        midiTrack.setSoundFontAssignment(ASSIGNMENT);
        // Note at column 100 (beat 25.0) — far beyond our 8-frame buffer starting at beat 0
        midiTrack.getMidiClip().addNote(MidiNoteData.of(60, 100, 4, 100));

        MixerChannel mixerChannel = new MixerChannel("MIDI Track");
        mixer.addChannel(mixerChannel);

        transport.play();
        engine.setGraph(transport, mixer, List.of(midiTrack));
        engine.start();
        midiRenderer.prepareRenderer(midiTrack);
        engine.setMidiTrackRenderer(midiRenderer);

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        // Should have no note events (note is outside the current segment)
        StubSoundFontRenderer instance = stubRenderer.lastCreatedInstance;
        assertThat(instance).isNotNull();
        assertThat(instance.receivedEvents).noneMatch(
                e -> e.type() == MidiEvent.Type.NOTE_ON || e.type() == MidiEvent.Type.NOTE_OFF);
    }

    @Test
    void shouldProduceSilenceForMidiTrackWithNoSoundFontAssignment() {
        Track midiTrack = new Track("MIDI Track", TrackType.MIDI);
        // No SoundFont assignment set
        midiTrack.getMidiClip().addNote(MidiNoteData.of(60, 0, 4, 100));

        MixerChannel mixerChannel = new MixerChannel("MIDI Track");
        mixer.addChannel(mixerChannel);

        transport.play();
        engine.setGraph(transport, mixer, List.of(midiTrack));
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        for (int i = 0; i < BUFFER_SIZE; i++) {
            assertThat(output[0][i]).isEqualTo(0.0f);
            assertThat(output[1][i]).isEqualTo(0.0f);
        }
    }

    @Test
    void shouldProduceSilenceForMidiTrackWithEmptyClip() {
        StubSoundFontRenderer stubRenderer = new StubSoundFontRenderer(0.0f);
        MidiTrackRenderer midiRenderer = new MidiTrackRenderer(
                SAMPLE_RATE, BUFFER_SIZE, stubRenderer::newInstance);

        Track midiTrack = new Track("MIDI Track", TrackType.MIDI);
        midiTrack.setSoundFontAssignment(ASSIGNMENT);
        // No notes in clip

        MixerChannel mixerChannel = new MixerChannel("MIDI Track");
        mixer.addChannel(mixerChannel);

        transport.play();
        engine.setGraph(transport, mixer, List.of(midiTrack));
        engine.start();
        midiRenderer.prepareRenderer(midiTrack);
        engine.setMidiTrackRenderer(midiRenderer);

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        for (int i = 0; i < BUFFER_SIZE; i++) {
            assertThat(output[0][i]).isEqualTo(0.0f);
            assertThat(output[1][i]).isEqualTo(0.0f);
        }
    }

    @Test
    void shouldRenderBothAudioAndMidiTracksSimultaneously() {
        StubSoundFontRenderer stubRenderer = new StubSoundFontRenderer(0.4f);
        MidiTrackRenderer midiRenderer = new MidiTrackRenderer(
                SAMPLE_RATE, BUFFER_SIZE, stubRenderer::newInstance);

        // Audio track
        Track audioTrack = new Track("Audio Track", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Clip", 0.0, 1.0, null);
        float[][] clipData = new float[CHANNELS][(int) SAMPLES_PER_BEAT];
        for (int i = 0; i < BUFFER_SIZE; i++) {
            clipData[0][i] = 0.6f;
            clipData[1][i] = 0.6f;
        }
        clip.setAudioData(clipData);
        audioTrack.addClip(clip);

        // MIDI track
        Track midiTrack = new Track("MIDI Track", TrackType.MIDI);
        midiTrack.setSoundFontAssignment(ASSIGNMENT);
        midiTrack.getMidiClip().addNote(MidiNoteData.of(60, 0, 4, 100));

        MixerChannel audioChannel = new MixerChannel("Audio Track");
        MixerChannel midiChannel = new MixerChannel("MIDI Track");
        mixer.addChannel(audioChannel);
        mixer.addChannel(midiChannel);

        transport.play();
        engine.setGraph(transport, mixer, List.of(audioTrack, midiTrack));
        engine.start();
        midiRenderer.prepareRenderer(midiTrack);
        engine.setMidiTrackRenderer(midiRenderer);

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        // Both tracks contribute: (0.6 + 0.4) * cos(π/4) ≈ 1.0 * 0.7071
        double expectedGain = Math.cos(Math.PI / 4.0);
        for (int i = 0; i < BUFFER_SIZE; i++) {
            assertThat((double) output[0][i]).isCloseTo((0.6 + 0.4) * expectedGain, offset(0.02));
        }
    }

    @Test
    void shouldHandleSoundFontAssignmentChange() {
        StubSoundFontRenderer stubRenderer = new StubSoundFontRenderer(0.3f);
        MidiTrackRenderer midiRenderer = new MidiTrackRenderer(
                SAMPLE_RATE, BUFFER_SIZE, stubRenderer::newInstance);

        Track midiTrack = new Track("MIDI Track", TrackType.MIDI);
        midiTrack.setSoundFontAssignment(ASSIGNMENT);
        midiTrack.getMidiClip().addNote(MidiNoteData.of(60, 0, 4, 100));

        MixerChannel mixerChannel = new MixerChannel("MIDI Track");
        mixer.addChannel(mixerChannel);

        transport.play();
        engine.setGraph(transport, mixer, List.of(midiTrack));
        engine.start();
        midiRenderer.prepareRenderer(midiTrack);
        engine.setMidiTrackRenderer(midiRenderer);

        // First render — establishes the renderer
        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        StubSoundFontRenderer instance = stubRenderer.lastCreatedInstance;
        assertThat(instance).isNotNull();
        assertThat(instance.selectedBank).isEqualTo(0);
        assertThat(instance.selectedProgram).isEqualTo(0);

        // Change the SoundFont assignment and prepare the renderer
        SoundFontAssignment newAssignment = new SoundFontAssignment(
                DUMMY_SF2_PATH, 0, 42, "Electric Piano");
        midiTrack.setSoundFontAssignment(newAssignment);
        midiRenderer.prepareRenderer(midiTrack);

        // Reset transport to beat 0 for a clean second render. Story 315: a
        // seek issued while PLAYING is queued for the next block boundary, so
        // pause first to make the rewind land immediately, then resume.
        transport.pause();
        transport.setPositionInBeats(0.0);
        transport.play();
        output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        // The same renderer instance should have been reused with the new preset
        assertThat(instance.selectedProgram).isEqualTo(42);
    }

    // ── Sample-accurate timing ──────────────────────────────────────────────

    @Test
    void shouldRenderSilenceBeforeNoteStartingMidSegment() {
        // Use a larger buffer to make mid-segment timing testable
        int largeBuffer = 1024;
        AudioFormat largeFormat = new AudioFormat(SAMPLE_RATE, CHANNELS, 16, largeBuffer);
        AudioEngine largeEngine = new AudioEngine(largeFormat);

        // At 999 BPM, samplesPerBeat = 44100 * 60 / 999 ≈ 2648.6
        // Column 1 = beat 0.25 → frame ≈ 662
        // This places the note-on about halfway through our 1024-frame buffer.
        Transport fastTransport = new Transport();
        fastTransport.setTempo(999.0);

        StubSoundFontRenderer stubRenderer = new StubSoundFontRenderer(0.5f);
        MidiTrackRenderer midiRenderer = new MidiTrackRenderer(
                SAMPLE_RATE, largeBuffer, stubRenderer::newInstance);

        Track midiTrack = new Track("MIDI Track", TrackType.MIDI);
        midiTrack.setSoundFontAssignment(ASSIGNMENT);
        // Note at column 1 (beat 0.25), duration 8 columns (2 beats)
        midiTrack.getMidiClip().addNote(MidiNoteData.of(60, 1, 8, 100));

        MixerChannel mixerChannel = new MixerChannel("MIDI Track");
        Mixer largeMixer = new Mixer();
        largeMixer.addChannel(mixerChannel);

        fastTransport.play();
        largeEngine.setGraph(fastTransport, largeMixer, List.of(midiTrack));
        largeEngine.start();
        midiRenderer.prepareRenderer(midiTrack);
        largeEngine.setMidiTrackRenderer(midiRenderer);

        float[][] input = new float[CHANNELS][largeBuffer];
        float[][] output = new float[CHANNELS][largeBuffer];
        largeEngine.processBlock(input, output, largeBuffer);

        // The note starts at beat 0.25. At 999 BPM with 44100 Hz:
        // samplesPerBeat = 44100 * 60 / 999 ≈ 2648.6
        // frame at beat 0.25 ≈ 662
        // Everything before frame 662 should be silence (only the render() no-event
        // sub-chunk runs before the note-on event)
        double samplesPerBeat = SAMPLE_RATE * 60.0 / 999.0;
        int noteOnFrame = (int) Math.round(0.25 * samplesPerBeat);

        // Check silence before note onset
        for (int i = 0; i < noteOnFrame - 1; i++) {
            assertThat(output[0][i]).as("frame %d should be silent before note-on", i)
                    .isCloseTo(0.0f, offset(0.001f));
        }

        // Check non-silence after note onset (stub fills with 0.5f after note-on)
        // The note-on triggers at noteOnFrame, so rendering after that should produce audio
        boolean hasNonZeroAfterNote = false;
        for (int i = noteOnFrame + 1; i < largeBuffer; i++) {
            if (Math.abs(output[0][i]) > 0.001f) {
                hasNonZeroAfterNote = true;
                break;
            }
        }
        assertThat(hasNonZeroAfterNote).isTrue();
    }

    // ── Loop wrap stuck notes ───────────────────────────────────────────────

    @Test
    void shouldSendAllNotesOffOnLoopWrap() {
        // Use a small buffer and configure loop to wrap within the block
        // At 120 BPM, samplesPerBeat = 22050
        // Loop from beat 0 to beat 0.001 → ~22 frames
        // With BUFFER_SIZE=8, beat range per block = 8/22050 ≈ 0.000363 beats
        // We need the loop to wrap within our 8-frame block.
        // Loop end = 0.0002 beats → ~4.4 frames. Transport at beat ~0.0001.
        StubSoundFontRenderer stubRenderer = new StubSoundFontRenderer(0.0f);
        MidiTrackRenderer midiRenderer = new MidiTrackRenderer(
                SAMPLE_RATE, BUFFER_SIZE, stubRenderer::newInstance);

        Track midiTrack = new Track("MIDI Track", TrackType.MIDI);
        midiTrack.setSoundFontAssignment(ASSIGNMENT);
        // A note that spans the entire first beat
        midiTrack.getMidiClip().addNote(MidiNoteData.of(60, 0, 4, 100));

        MixerChannel mixerChannel = new MixerChannel("MIDI Track");
        mixer.addChannel(mixerChannel);

        // Set up loop that wraps within our block
        transport.setLoopEnabled(true);
        transport.setLoopRegion(0.0, 0.0002); // ~4.4 frames at 120 BPM
        transport.setPositionInBeats(0.0001); // Start near loop end
        transport.play();

        engine.setGraph(transport, mixer, List.of(midiTrack));
        engine.start();
        midiRenderer.prepareRenderer(midiTrack);
        engine.setMidiTrackRenderer(midiRenderer);

        StubSoundFontRenderer instance = stubRenderer.lastCreatedInstance;

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        // The loop should have wrapped during processing and sent allNotesOff
        assertThat(instance).isNotNull();
        assertThat(instance.allNotesOffCallCount).isGreaterThanOrEqualTo(1);
    }

    // ── Loop-boundary event window ──────────────────────────────────────────

    /**
     * Story 315 — a note sitting exactly on the loop start must be triggered
     * on every lap, including laps entered through a quantized wrap.
     *
     * <p>The pipeline can only split a block at a whole frame, so a loop end
     * of 82687.5 frames is overshot by up to one frame and the wrap leaves
     * the render cursor a sub-frame PAST the loop start. The MIDI renderer
     * admits a note-on with an exact {@code noteStartBeat >= startBeat} — no
     * epsilon — so that residue excluded the note at beat 0.0 on every
     * wrapped lap: before the fix note 60 fired exactly ONCE across nine
     * laps, at the very first lap start (the only one not reached through a
     * wrap). Note 62 at beat 1.0 sits nowhere near a segment boundary and
     * fired correctly throughout, which is what makes it a meaningful
     * control: it isolates the failure to the loop-start boundary rather
     * than to the harness.</p>
     */
    @Test
    void noteAtTheLoopStartFiresOnEveryLapWhenTheLoopIsNotFrameAligned() {
        StubSoundFontRenderer instance = renderLoopedSession(List.of(
                MidiNoteData.of(60, 0, 4, 100),   // column 0 → beat 0.0 = the loop start
                MidiNoteData.of(62, 4, 4, 100))); // column 4 → beat 1.0, the control

        assertThat(countNoteOns(instance, 60))
                .as("note-ons for the note sitting exactly on the loop start")
                .isEqualTo(LOOP_LAP_STARTS);
        assertThat(countNoteOns(instance, 62))
                .as("note-ons for the control note at beat 1.0")
                .isEqualTo(LOOP_LAPS_REACHING_BEAT_ONE);
    }

    /**
     * Story 315 — the loop region is half-open, so a note sitting exactly on
     * the loop end lies OUTSIDE the loop and must never sound while looping.
     *
     * <p>The pre-wrap segment's event window was never capped at the loop
     * end: it ran to the end of the whole-frame-quantized split, a sub-frame
     * PAST the loop end, and therefore admitted the note at beat 4.0. Before
     * the fix note 65 fired on all 8 laps — out-of-loop content bleeding into
     * a loop that story 315 guarantees is clean. Note 62 at beat 1.0 is the
     * control: it is genuinely inside the loop and must keep firing.</p>
     *
     * <p>Since the second review round this pins the OUT-OF-LOOP half of the
     * loop-end exemption. Ordinary segment ends now finish half a frame early
     * so that a straggler is carried into the next segment; a loop end must
     * not move in either direction, and the two directions fail differently.
     * Running PAST {@code loopEnd} is what this test catches — that is the
     * original bleed, and the whole-frame split already overshoots by
     * δ ∈ [0, 1) frame, so {@code segmentEndBeat − ½ frame} is past
     * {@code loopEnd} on any lap whose δ exceeds half a frame. Stopping half
     * a frame SHORT is caught by the companion
     * {@link #noteEndingExactlyOnTheLoopEndIsReleasedOncePerLapWhileLooping()}:
     * measured on JDK 26 by applying the shift at the loop end instead of
     * capping there, it loses the release on the four laps whose δ is under
     * half a frame — frames 165374, 330749, 496124 and 661499 disappear while
     * 82687, 248062, 413437 and 578812 survive.</p>
     */
    @Test
    void noteExactlyAtTheLoopEndNeverFiresWhileLooping() {
        StubSoundFontRenderer instance = renderLoopedSession(List.of(
                MidiNoteData.of(65, 16, 4, 100),  // column 16 → beat 4.0 = the loop end
                MidiNoteData.of(62, 4, 4, 100))); // column 4 → beat 1.0, the control

        assertThat(countNoteOns(instance, 65))
                .as("note-ons for the note sitting exactly on the (exclusive) loop end")
                .isZero();
        assertThat(countNoteOns(instance, 62))
                .as("note-ons for the control note at beat 1.0")
                .isEqualTo(LOOP_LAPS_REACHING_BEAT_ONE);
    }

    /**
     * Story 315 review — the loop-start note must fire exactly ONCE per lap,
     * never twice. This test pins the WRAP FLAG half of the fix.
     *
     * <p>The widening that recovers the loop-start note asks whether this
     * segment begins inside the sub-frame residue a quantized wrap leaves
     * behind: {@code beatsIntoLoop × samplesPerBeat < 1.0}. That positional
     * reading cannot, on its own, tell "the wrap overshot and this lap's
     * first frame is still unrendered" apart from "the previous block already
     * rendered this lap's first frame" — for a frame that HAS been consumed
     * the product evaluates to <b>0.99999999999988990</b>, not to 1.0, so the
     * test still passes and the lap fires its loop-start note a SECOND time
     * one frame later.</p>
     *
     * <p>No threshold can separate the two readings, because the two
     * populations OVERLAP — the largest genuine residue sits ABOVE the
     * smallest already-consumed reading:</p>
     * <ul>
     *   <li>largest GENUINE residue, where the widening MUST fire:
     *       <b>0.99999999999994320</b>, at 32768 Hz / 128 BPM
     *       (samplesPerBeat = 15360), loop [0.0, 0.25) beats, 128-frame
     *       blocks;</li>
     *   <li>smallest CONSUMED reading, where the widening MUST NOT fire:
     *       <b>0.99999999999988990</b>, at 48000 Hz / 120 BPM
     *       (samplesPerBeat = 24000), loop [0.0, 0.328125) beats, 64-frame
     *       blocks — the config this test uses.</li>
     * </ul>
     * <p>A cut-off admitting every genuine residue therefore also admits
     * that consumed reading (double note), and one rejecting the consumed
     * reading also rejects a genuine residue (dropped note). The fix
     * therefore carries an explicit per-walk "we wrapped and have not yet
     * scheduled this lap's first frame" flag across the block boundary and
     * conjoins it with the positional test. <i>Provenance: both figures were
     * measured offline by simulating the walk across a sweep of sample
     * rates, tempos, block sizes and loop lengths; they are NOT reproducible
     * from any test in this repo.</i></p>
     *
     * <p>Config: 48 kHz at 120 BPM gives samplesPerBeat = 24000 exactly, and
     * the loop end 0.328125 = 21/64 is exact in binary, so the lap is exactly
     * 7875 frames. 2708 × 64 = 173312 frames spans 22 wraps, hence 23 lap
     * starts. Before the fix the count was <b>24</b>: lap 21 fired at frames
     * 165375 <i>and</i> 165376.</p>
     *
     * <p>This test and {@link #noteAtTheLoopStartFiresOnEveryLapWhenTheWrapResidueRoundsUp()}
     * are orthogonal discriminators, one per half of the fix: this one still
     * fails with the split epsilon alone and no flag, and that one still
     * fails with the flag alone and no epsilon.</p>
     *
     * <p>Unchanged by the second review round's half-frame carry, and
     * re-running it is how that is pinned. The carry hands each segment the
     * PREVIOUS segment's window END, which after a lap start is already at
     * least half a frame into the lap, so it never reaches back over the lap
     * boundary to claim the loop-start note a second time. A 24th note-on
     * here would mean it had.</p>
     */
    @Test
    void noteAtTheLoopStartFiresOnlyOncePerLapWhenTheWrapResidueIsAlreadyConsumed() {
        StubSoundFontRenderer instance = renderSession(
                48_000.0, 120.0, 64, 2708,
                true, 0.0, 0.328125,
                List.of(MidiNoteData.of(60, 0, 1, 100))); // column 0 → beat 0.0 = loop start

        assertThat(countNoteOns(instance, 60))
                .as("note-ons for the note on the loop start — exactly one per lap "
                        + "start, never a second one a frame later")
                .isEqualTo(23);
    }

    /**
     * Story 315 review — the loop-start note must not be DROPPED on a lap
     * whose wrap residue rounds up past one frame. This test pins the SPLIT
     * EPSILON half of the fix.
     *
     * <p>The block split computes
     * {@code framesUntilLoopEnd = ceil(beatsUntilLoopEnd × samplesPerBeat)}
     * with no epsilon. When that product should be a whole number k but
     * evaluates to {@code k + 5.6e-13}, {@code ceil} returns {@code k + 1},
     * the segment overshoots by a full frame, and the wrap leaves a residue
     * of <b>1.000000000000556</b> frames — just OUTSIDE the {@code < 1.0}
     * widening, so that lap's loop-start note is never scheduled. Shaving an
     * epsilon off the {@code ceil} (and flooring the result at one frame, so
     * the segment always consumes at least one frame instead of skipping the
     * clamp entirely) keeps the residue inside the widening.</p>
     *
     * <p>Config: 44.1 kHz at 72 BPM gives samplesPerBeat = 36750 exactly and
     * a loop of 0.25 beats = 9187.5 frames. 54 × 1024 = 55296 frames spans 6
     * wraps, hence 7 lap starts. Before the fix the count was <b>6</b> — lap
     * 2 was missed entirely.</p>
     *
     * <p>This test and
     * {@link #noteAtTheLoopStartFiresOnlyOncePerLapWhenTheWrapResidueIsAlreadyConsumed()}
     * are orthogonal discriminators, one per half of the fix: this one still
     * fails with the wrap flag alone and no epsilon, and that one still fails
     * with the epsilon alone and no flag.</p>
     *
     * <p>Unchanged by the second review round's half-frame carry: a lap start
     * is a hard left edge, taken before the carry bound is consulted, so
     * recovering this lap's loop-start note is still the split epsilon's and
     * the widening's job. The carry cannot stand in for either, and this
     * count staying at 7 while the epsilon still owns it is what says so.</p>
     */
    @Test
    void noteAtTheLoopStartFiresOnEveryLapWhenTheWrapResidueRoundsUp() {
        StubSoundFontRenderer instance = renderSession(
                44_100.0, 72.0, 1024, 54,
                true, 0.0, 0.25,
                List.of(MidiNoteData.of(60, 0, 1, 100))); // column 0 → beat 0.0 = loop start

        assertThat(countNoteOns(instance, 60))
                .as("note-ons for the note on the loop start — no lap may be skipped "
                        + "because its wrap residue rounded up past one frame")
                .isEqualTo(7);
    }

    /**
     * Story 315 review — the content walk's wrap flag must survive the
     * block-entry loop mapping under plugin delay compensation.
     *
     * <p>{@code renderTracks} walks the PDC-shifted cursor
     * {@code transport.getPositionInBeats() + renderOffsetBeats}, so with a
     * latent insert its block-entry mapping
     * ({@code currentBeat >= loopEnd → wrap}) fires for a NON-seek reason on
     * every lap: the shifted cursor crosses the loop end while the raw cursor
     * is still inside it. That mapping is the exact continuation of the
     * whole-frame quantization residue the previous block's split left
     * behind, not a seek to a fresh position. Clearing the wrap flag there
     * therefore destroys the only record that this lap's first frame has not
     * been rendered yet — and the positional residue test alone cannot
     * reconstruct it, which is the whole reason the flag exists.</p>
     *
     * <p>The click walk ({@code mixMetronomeClicks}) DOES clear its flag in
     * the same place, and correctly so: it reads the RAW cursor, whose
     * mapping can only fire on a genuine seek. The asymmetry is deliberate —
     * see the comments on both mappings.</p>
     *
     * <p>Config: 48 kHz at 120 BPM gives samplesPerBeat = 24000 exactly, and
     * the loop end 0.328125 = 21/64 is exact in binary, so the lap is exactly
     * 7875 frames. The insert reports {@value #PDC_INSERT_LATENCY_FRAMES}
     * frames of latency, shifting the content cursor that far ahead of the
     * transport. 616 × 64 = 39424 frames spans five lap starts reached by the
     * content cursor. With the erroneous clear the count was <b>4</b>: the
     * note-on at global frame <b>39360</b> was dropped. That is lap 5's wrap,
     * and it lands exactly on the end of block <b>614</b> — the block
     * boundary itself — so the lap's first frame is the first frame of block
     * 615 and the flag has to survive the boundary to be of any use. The four
     * surviving note-ons are dispatched in blocks 122, 245, 368 and 491, a
     * stride of 123 blocks; the dropped one is the single lap whose stride is
     * 124, i.e. precisely the boundary-aligned wrap.</p>
     *
     * <p>The fixture sits deliberately, and deterministically, on the
     * widening's boundary rather than landing there by accident. Block 615
     * enters with the raw transport at frame 7860 of the 7875-frame lap, so
     * the PDC-shifted cursor is at 7876 — in EXACT arithmetic exactly one
     * frame past the loop start, i.e. exactly the {@code < 1.0} cut-off. What
     * makes the widening fire is that the block-entry modulo evaluates the
     * residue in IEEE-754, where it comes out just UNDER one frame:
     * {@code beatsIntoLoop × samplesPerBeat = 0.99999999999589310}. Every
     * quantity feeding that computation (24000 samples per beat, 21/64 beats
     * of loop) is exact in binary, so the value is reproducible to the last
     * bit on every run and every platform — the test is pinned to the
     * boundary case on purpose, not riding a rounding accident.</p>
     *
     * <p>Lap 0 is legitimately absent from the count on both sides of the
     * fix: with PDC the content cursor starts ahead of beat 0, so the render
     * simply begins past the first lap's downbeat. That is a start-up artifact
     * of delay compensation, not a scheduling defect.</p>
     *
     * <p><b>The FRAMES are asserted, not just the count.</b> A regression
     * that fires the right note on the right lap at the wrong frame — which
     * is exactly what moving the widened window's frame ORIGIN does — is
     * invisible to a count. The expected frames are the content walk's own
     * quantized lap boundaries, and they are the click walk's onsets for the
     * same laps shifted back by the PDC offset: the click walk (pinned in
     * {@code MetronomeLoopSchedulingEngineTest} on this identical 48 kHz
     * fixture) puts laps 1–5 at 7875, 15751, 23626, 31501 and 39376, and
     * subtracting the {@value #PDC_INSERT_LATENCY_FRAMES}-frame render offset
     * gives 7859, 15735, 23610, 31485 and 39360 — the values below. (Laps 2–5
     * sit one frame past the exact boundary 7875·k because the accumulated
     * fractional cursor has not quite reached the loop end on that frame, so
     * the split's {@code Math.max(1, …)} floor spends one more frame before
     * wrapping; lap 1 lands on the boundary exactly.) The frame recorded is
     * the renderer's cumulative rendered-frame index, which here equals the
     * global frame index because every block is rendered while playing.</p>
     *
     * <p>These frames are UNCHANGED by the second review round's half-frame
     * carry, and that is the point of re-running them: a lap start takes the
     * hard-edge branch before the carry bound is consulted, so 7859, 15735,
     * 23610, 31485 and 39360 are the same values before and after. This is
     * also the only fixture here whose block-entry cursor is PDC-shifted, so
     * it is the one that would expose a carry bound that failed to survive a
     * block boundary.</p>
     */
    @Test
    void loopStartNoteSurvivesTheBlockEntryMappingUnderPluginDelayCompensation() {
        StubSoundFontRenderer instance = renderSession(
                48_000.0, 120.0, 64, 616,
                true, 0.0, 0.328125,
                PDC_INSERT_LATENCY_FRAMES,
                List.of(MidiNoteData.of(60, 0, 1, 100))); // column 0 → beat 0.0 = loop start

        assertThat(countNoteOns(instance, 60))
                .as("note-ons for the note on the loop start — the PDC-shifted "
                        + "block-entry mapping must not clear the wrap flag, or the "
                        + "lap whose wrap lands on a block boundary loses its downbeat")
                .isEqualTo(5);
        assertThat(noteOnFrames(instance, 60))
                .as("the frames those note-ons were dispatched at — one per lap, on "
                        + "the lap's first rendered frame, which is the click walk's "
                        + "onset for the same lap minus the %d-frame PDC offset",
                        PDC_INSERT_LATENCY_FRAMES)
                .containsExactly(7859, 15735, 23610, 31485, 39360);
    }

    /**
     * Story 315 review — the content walk's wrap flag must survive a
     * pause/resume, so it must NOT be cleared on {@code renderBlock}'s
     * not-playing path.
     *
     * <p>When a lap's quantized wrap lands on a block boundary the flag is
     * the ONLY record that the new lap's first frame is still unrendered: the
     * transport position is parked inside the sub-frame residue the wrap left
     * behind, and the positional residue test cannot tell that residue apart
     * from one the previous block already consumed (see
     * {@link #noteAtTheLoopStartFiresOnlyOncePerLapWhenTheWrapResidueIsAlreadyConsumed()}
     * for why no threshold can). Pausing on exactly that boundary and
     * resuming must therefore still emit the lap's loop-start note.</p>
     *
     * <p>{@code AudioEngine.processBlock} forwards EVERY backend callback to
     * {@code RenderPipeline.renderBlock}, whatever the transport state — the
     * device callback does not stop at a pause. A clear of the wrap flags on
     * {@code renderBlock}'s not-playing path is consequently executed on
     * every paused callback, wiping the owed loop-start event; the harness
     * keeps calling {@code processBlock} through the pause precisely to
     * reproduce that.</p>
     *
     * <p>Config: the same fixture as
     * {@link #loopStartNoteSurvivesTheBlockEntryMappingUnderPluginDelayCompensation()}
     * — 48 kHz at 120 BPM (samplesPerBeat = 24000 exactly), a loop end of
     * 0.328125 = 21/64 beats (exactly 7875 frames per lap), 64-frame blocks
     * and {@value #PDC_INSERT_LATENCY_FRAMES} frames of insert latency —
     * split into 615 playing blocks, 20 paused blocks and 20 resumed blocks.
     * The pause falls on the boundary-aligned wrap at the end of block 614,
     * so the owed loop-start note is dispatched in the first resumed block —
     * the 636th block handed to {@code processBlock}. With the not-playing
     * clear restored the count is <b>4</b> and that note-on is gone; the four
     * survivors (blocks 122, 245, 368, 491) are all mid-block wraps that
     * never needed the flag to cross a boundary.</p>
     *
     * <p><b>Two different frame numbers describe that dispatch, and only one
     * of them is assertable here.</b> In WALL-CLOCK terms it is global frame
     * <b>40640</b> = 635 × 64, counting every block the backend pushed
     * through the engine. But this test asserts only a count, and the frame
     * the harness could assert is a different quantity:
     * {@link #noteOnFrames} reports {@link StubSoundFontRenderer.Dispatch}'s
     * RENDERED-frame index, and the stub's counter only advances inside
     * {@code render()}, which the paused blocks never reach — while paused,
     * {@code renderBlock} skips {@code renderTracks} entirely. Those 20
     * paused blocks (1280 frames) are therefore not counted, and a frame
     * assertion added here would have to expect <b>39360</b> = 615 × 64, not
     * 40640. Nothing is broken today; this is recorded so a future author
     * does not write the assertion against the wall-clock number and get a
     * confusing 1280-frame discrepancy.</p>
     *
     * <p>Lap 0 is legitimately absent on both sides of the fix: with PDC the
     * content cursor starts ahead of beat 0, so the render begins past the
     * first lap's downbeat — a delay-compensation start-up artifact, not a
     * scheduling defect.</p>
     *
     * <p>The count is UNCHANGED by the second review round's half-frame
     * carry, and re-running it is how that is pinned: a lap start is a hard
     * left edge, taken before the carry bound is consulted, so the owed
     * loop-start note is still the widening's job across the pause and the
     * carry neither supplies it nor duplicates it.</p>
     */
    @Test
    void loopStartNoteSurvivesAPauseOnTheWrapBoundary() {
        StubSoundFontRenderer instance = renderSession(
                48_000.0, 120.0, 64, 615, 20, 20,
                true, 0.0, 0.328125,
                PDC_INSERT_LATENCY_FRAMES, 0.0,
                List.of(MidiNoteData.of(60, 0, 1, 100))); // column 0 → beat 0.0 = loop start

        assertThat(countNoteOns(instance, 60))
                .as("note-ons for the note on the loop start — renderBlock runs on "
                        + "every paused callback too, so clearing the wrap flag there "
                        + "destroys the loop-start event owed across the pause")
                .isEqualTo(5);
    }

    /**
     * Story 315 review (second round) — a note-off whose ideal position falls
     * in the LAST HALF FRAME of a segment must be carried into the next
     * segment and dispatched on its frame 0, not dragged back onto the
     * current segment's last frame. Looping is DISABLED here: this is a plain
     * linear-playback defect, independent of the loop work.
     *
     * <p>Two rounds of the same fixture. The FIRST round fixed a drop: the
     * note-off branch mapped its beat with {@code maxFrame = framesToProcess}
     * while {@code findNextEventFrame} seeds {@code nextFrame =
     * framesToProcess} and selects only on {@code frame < nextFrame}, so such
     * a note-off was never sent at all and its note sustained until the next
     * {@code allNotesOff()} or transport stop — count <b>0</b>. Clamping to
     * {@code framesToProcess - 1} recovered the event but emitted it one
     * sample EARLY, which is the defect this round fixes: the event window is
     * now the segment's frame-ownership interval, ending half a frame before
     * the segment's exclusive end, so block 18 does not admit this note-off
     * at all. Block 19 does — its own window reaches half a frame back past
     * its origin — and maps it to frame 0, the frame nearest-frame rounding
     * always said it belonged on.</p>
     *
     * <p>Config: 44.1 kHz at 68 BPM gives samplesPerBeat = 38911.7647…, so the
     * note-off at beat 0.25 lands on frame 9727.94 — 0.06 frame before block
     * 18's end at 9728. Rounding takes it to 9728, which IS block 19's frame
     * 0. Before this round the dispatch frame was <b>9727</b>; it is
     * <b>9728</b> now.</p>
     */
    @Test
    void noteOffLandingInTheLastHalfFrameIsCarriedIntoTheNextSegment() {
        StubSoundFontRenderer instance = renderSession(
                44_100.0, 68.0, 512, 24,
                false, 0.0, 0.0,
                List.of(MidiNoteData.of(60, 0, 1, 100))); // beat 0.0 → 0.25, off at 9727.94

        assertThat(countNoteOffs(instance, 60))
                .as("note-offs for a note whose end falls in a segment's last half "
                        + "frame — dropping it leaves the note stuck until the next "
                        + "allNotesOff()")
                .isEqualTo(1);
        assertThat(noteOffFrames(instance, 60))
                .as("the frame it was dispatched at: the NEXT block's frame 0 (global "
                        + "9728), which is where 9727.94 rounds to — not 9727, which is "
                        + "what re-quantizing it into the earlier segment produced")
                .containsExactly(9728);
        assertThat(countNoteOns(instance, 60))
                .as("the matching note-on — asserted so the test cannot pass by "
                        + "breaking note-on dispatch instead")
                .isEqualTo(1);
    }

    /**
     * Story 315 review (second round) — the note-ON branch had the identical
     * defect and gets the identical fix. Copilot's finding named only the
     * note-off; the two branches share one predicate, one mapping and one
     * clamp, so a note-off carried while a note-on is still re-quantized
     * would be a half-applied fix.
     *
     * <p>Same fixture as
     * {@link #noteOffLandingInTheLastHalfFrameIsCarriedIntoTheNextSegment()}
     * — 44.1 kHz at 68 BPM, samplesPerBeat = 38911.7647…, 512-frame blocks,
     * looping disabled — with the note moved one 1/16 column later so that
     * its START, rather than its end, sits on beat 0.25 = frame 9727.94, 0.06
     * frame before block 18's end. It must fire on block 19's frame 0, global
     * frame <b>9728</b>; before this round it fired at <b>9727</b>.</p>
     *
     * <p>The note's own end (beat 0.5 = frame 19455.88) lies past the 24
     * blocks = 12288 frames this fixture renders, so no note-off is expected
     * and none is asserted. {@code containsExactly} is used rather than a
     * count so that a duplicate — the failure mode a window that overlaps its
     * neighbour would produce — fails too.</p>
     */
    @Test
    void noteOnLandingInTheLastHalfFrameIsCarriedIntoTheNextSegment() {
        StubSoundFontRenderer instance = renderSession(
                44_100.0, 68.0, 512, 24,
                false, 0.0, 0.0,
                List.of(MidiNoteData.of(60, 1, 1, 100))); // on at beat 0.25 = frame 9727.94

        assertThat(noteOnFrames(instance, 60))
                .as("the frame the note-on was dispatched at: the NEXT block's frame 0 "
                        + "(global 9728), exactly once — never 9727, and never both")
                .containsExactly(9728);
    }

    /**
     * Story 315 review (second round) — the sharpest discriminator: an event
     * sitting EXACTLY on a block boundary. It belongs to the block that
     * BEGINS there, on its frame 0, and to no other.
     *
     * <p>Every quantity in this fixture is dyadic, so the arithmetic is exact
     * in {@code double} and the assertion is not riding a rounding accident.
     * At 32768 Hz and 120 BPM samplesPerBeat is exactly 16384, so beat 0.25 —
     * the end of a 1/16-column note starting at beat 0.0 — is frame
     * <b>4096</b> exactly, which is block 8's frame 0 with 512-frame blocks.
     * Looping is disabled; 16 blocks = 8192 frames carry the render well past
     * it.</p>
     *
     * <p>Before this round the note-off landed on <b>4095</b>: block 7's
     * window ran to beat 0.25 inclusive, the mapping rounded the position to
     * 512 = {@code framesToProcess}, and the clamp dragged it back onto block
     * 7's last frame. Now block 7's window ends half a frame earlier, block
     * 8's begins half a frame before its own origin, and the event maps to
     * offset 0 there. The tie is the exact one the note-off window's
     * inclusive {@code <=} and the note-on window's exclusive {@code <}
     * resolve in opposite directions, which is why the note-on at beat 0.0 is
     * asserted alongside it: it must still land on frame 0 of block 0.</p>
     */
    @Test
    void eventExactlyOnABlockBoundaryBelongsToTheBlockThatBeginsThere() {
        StubSoundFontRenderer instance = renderSession(
                32_768.0, 120.0, 512, 16,      // samplesPerBeat = 16384 exactly
                false, 0.0, 0.0,
                List.of(MidiNoteData.of(60, 0, 1, 100))); // beat 0.0 → 0.25 = frame 4096

        assertThat(noteOffFrames(instance, 60))
                .as("the note-off sits exactly on block 8's first frame (4096) and "
                        + "belongs to block 8, not to block 7's last frame (4095)")
                .containsExactly(4096);
        assertThat(noteOnFrames(instance, 60))
                .as("the matching note-on, still on frame 0 of the very first block")
                .containsExactly(0);
    }

    /**
     * Story 315 review — the widening must recover the note at a NON-ZERO
     * loop start. Every other loop test in this class loops from beat 0.0, so
     * {@code eventWindowStartBeat = loopStart} is only ever exercised at 0.0
     * and would pass identically if the production line read {@code = 0.0}.
     *
     * <p>Here the loop is the bar [4.0, 8.0). Derivation of the expected
     * frames, at {@link #SAMPLE_RATE} and {@link #LOOP_TEMPO} (samplesPerBeat
     * = 20671.875 exactly, so a 4-beat lap is 82687.5 frames — deliberately
     * not a whole number, which is what makes every wrap leave a sub-frame
     * residue):</p>
     * <ul>
     *   <li>The transport is parked on beat 4.0 before playback, so render
     *       frame 0 IS the loop start and lap 0's note-on lands on frame 0.
     *   </li>
     *   <li>Lap k's exact boundary is {@code k × 82687.5} frames. The split
     *       can only land on a whole frame and always consumes at least one,
     *       so a lap begins on the first whole frame STRICTLY past its exact
     *       boundary: {@code floor(k × 82687.5) + 1}. For odd k that is just
     *       the ceiling of a half-frame boundary; for even k the boundary is
     *       itself a whole frame, and the accumulated fractional cursor has
     *       not quite reached the loop end there, so the split's
     *       {@code Math.max(1, …)} floor spends one more frame first.</li>
     *   <li>The note sits exactly on the loop start, so the widened window
     *       maps it onto that first frame — offset 0 within the segment.</li>
     * </ul>
     *
     * <p>1292 blocks of 512 frames = 661504 frames, and 8 × 82687.5 = 661500
     * ≤ 661504 &lt; 9 × 82687.5, so the render contains exactly 8 wraps and 9
     * lap starts.</p>
     *
     * <p>Asserting the FRAMES is what makes this test discriminating.
     * Hard-coding the widened window start to 0.0 does not lose the note —
     * beat 4.0 is still inside the widened window — it merely re-maps it:
     * {@code beatToFrame} then measures 4.0 from an origin four beats away,
     * saturates against the upper clamp, and the note-on lands on the LAST
     * frame of the segment instead of the first.</p>
     *
     * <p>These frames are UNCHANGED by the second review round's half-frame
     * carry, which is what re-running this test pins. The lap-start segment
     * itself takes the hard-edge branch, and the segment FOLLOWING it begins
     * its window where that one's window ENDED — at least half a frame into
     * the lap, because every segment consumes at least one frame — so the
     * note is claimed by exactly one segment per lap. A duplicate would fail
     * {@code containsExactlyElementsOf}, which is why the assertion is a
     * frame list rather than a count.</p>
     */
    @Test
    void noteAtANonZeroLoopStartFiresOnEveryLapAtTheLapsFirstFrame() {
        StubSoundFontRenderer instance = renderSessionFrom(
                4.0,
                SAMPLE_RATE, LOOP_TEMPO, LOOP_BLOCK_FRAMES, LOOP_BLOCK_COUNT,
                true, 4.0, 8.0,
                List.of(MidiNoteData.of(60, 16, 4, 100))); // column 16 → beat 4.0 = loop start

        assertThat(noteOnFrames(instance, 60))
                .as("the note sitting exactly on a NON-ZERO loop start fires once per "
                        + "lap, on the lap's first rendered frame")
                .containsExactlyElementsOf(expectedLapFirstFrames(LOOP_LAP_STARTS));
    }

    /**
     * The first rendered frame of each of the first {@code lapCount} laps of
     * a 82687.5-frame lap: frame 0 for lap 0, and the first whole frame
     * strictly past the exact boundary {@code k × 82687.5} for every lap
     * after it. See
     * {@link #noteAtANonZeroLoopStartFiresOnEveryLapAtTheLapsFirstFrame()}
     * for the derivation.
     */
    private static List<Integer> expectedLapFirstFrames(int lapCount) {
        List<Integer> frames = new ArrayList<>();
        for (int lap = 0; lap < lapCount; lap++) {
            frames.add(lap == 0 ? 0 : (int) Math.floor(lap * LOOP_LAP_FRAMES) + 1);
        }
        return frames;
    }

    /**
     * Story 315 review — a note whose end lands exactly on the loop end must
     * be released exactly once per lap while LOOPING, and must not be
     * released a second time after the wrap.
     * {@link #noteOffLandingInTheLastHalfFrameIsCarriedIntoTheNextSegment()}
     * covers an ordinary block end with looping switched off, where the event
     * is CARRIED into the next segment instead; this is the looped case, and
     * it is the counterexample that keeps the two rules apart. A loop end is
     * a HARD edge — there is no next segment in the lap to carry a release
     * into — so here the upper clamp is still the semantics rather than a
     * re-quantization, and the frames below pin exactly that.
     *
     * <p>The note runs from beat 3.0 to beat 4.0 in the loop [0.0, 4.0), so
     * its end is the loop's exclusive end. It is admitted by the final
     * pre-wrap segment, whose event window the caller caps at the loop end,
     * through {@code noteEndBeat <= endBeat}. It must NOT be admitted again
     * after the wrap: the post-wrap window starts at the loop start, and
     * {@code noteEndBeat > startBeat} would hold there, but
     * {@code noteEndBeat <= endBeat} cannot — the wrapped window ends a few
     * frames into the lap, four beats short of the note end.</p>
     *
     * <p>Derivation of the expected frames, at {@link #SAMPLE_RATE} and
     * {@link #LOOP_TEMPO} (samplesPerBeat = 20671.875, lap = 82687.5
     * frames):</p>
     * <ul>
     *   <li>The note-ON is an ordinary in-loop event: it lands on the
     *       nearest frame to its ideal position,
     *       {@code round(k × 82687.5 + 62015.625)}, where 62015.625 = 3.0 ×
     *       20671.875 is beat 3.0 within the lap.</li>
     *   <li>The note-OFF's ideal position is the lap boundary itself,
     *       {@code (k + 1) × 82687.5}, which belongs to the NEXT lap. The
     *       upper clamp in {@code beatToFrame} pulls it back into the segment
     *       that admitted it, so it lands on the last whole frame strictly
     *       BEFORE the boundary: {@code ceil((k + 1) × 82687.5) − 1}. That
     *       clamp is the one story 315's review added; without it this
     *       note-off maps to exactly {@code framesToProcess} and is never
     *       dispatched at all.</li>
     * </ul>
     *
     * <p>1292 blocks of 512 frames span 8 complete laps, so both counts are
     * 8 — one note-on and one note-off per completed lap, never two.</p>
     */
    @Test
    void noteEndingExactlyOnTheLoopEndIsReleasedOncePerLapWhileLooping() {
        StubSoundFontRenderer instance = renderSession(
                SAMPLE_RATE, LOOP_TEMPO, LOOP_BLOCK_FRAMES, LOOP_BLOCK_COUNT,
                true, 0.0, 4.0,
                List.of(MidiNoteData.of(60, 12, 4, 100))); // beats 3.0 → 4.0 = the loop end

        List<Integer> expectedNoteOns = new ArrayList<>();
        List<Integer> expectedNoteOffs = new ArrayList<>();
        for (int lap = 0; lap < LOOP_LAPS_REACHING_BEAT_ONE; lap++) {
            expectedNoteOns.add((int) Math.round(lap * LOOP_LAP_FRAMES + 3.0 * LOOP_SAMPLES_PER_BEAT));
            expectedNoteOffs.add((int) Math.ceil((lap + 1) * LOOP_LAP_FRAMES) - 1);
        }

        assertThat(noteOffFrames(instance, 60))
                .as("a note ending exactly on the (exclusive) loop end is released "
                        + "exactly once per lap, on the last frame before the wrap — "
                        + "never dropped by the segment-end mapping, never repeated "
                        + "after the wrap")
                .containsExactlyElementsOf(expectedNoteOffs);
        assertThat(noteOnFrames(instance, 60))
                .as("the matching note-ons — asserted so the test cannot pass by "
                        + "breaking note-on dispatch instead")
                .containsExactlyElementsOf(expectedNoteOns);
    }

    /**
     * Story 315 review — the sub-frame guard
     * ({@code loopLength × samplesPerBeat >= 1.0}) on the content walk's
     * widening. Without it every segment of a sub-frame loop is a fresh wrap,
     * so the widening re-admits the loop-start note on every single frame.
     *
     * <p>Derivation. At 32768 Hz / 120 BPM samplesPerBeat is exactly 16384,
     * so one frame is 2⁻¹⁴ beats. The loop starts on beat 4.0 — where the
     * note sits — and is 0.3 frame long, deliberately NOT an exact binary
     * fraction. Every segment is therefore clamped to a single frame
     * ({@code max(1, ceil(0.3 − …))}), and each one wraps: the cursor
     * advances one frame and the modulo maps it back to
     * {@code loopStart + ((r + 1) mod 0.3)} frames. That residue orbit —
     * 0 → 0.1 → 0.2 → 0.3-ish — never returns to the loop start in floating
     * point, so only the very first segment, whose cursor IS the loop start,
     * admits the note through the ordinary window test. The guard blocks the
     * widening on all 1535 later frames, giving exactly one note-on. Without
     * the guard the widening fires on every frame that follows a wrap, i.e.
     * on all of them.</p>
     *
     * <p>A loop length that DIVIDES a frame is a different, pre-existing
     * story and this test deliberately does not cover it: with 0.25 frame the
     * modulo lands the cursor back exactly ON the loop start every frame, so
     * the ordinary window test admits the note 1536 times with the guard AND
     * without it. See the comment on the widening.</p>
     *
     * <p>Since the second review round it ALSO pins that a lap start is
     * exempt from the half-frame carry, and it is the sharpest guard on that
     * rule. Every one of these 1536 single-frame segments begins a lap, and
     * the residue orbit sits under half a frame for most of them, so a window
     * that reached half a frame back past its own cursor unconditionally
     * would re-admit the loop-start note on those frames. Measured on JDK 26
     * by replacing the carry guard with a plain
     * {@code frameOriginBeat − ½ frame}: this count goes from 1 to
     * <b>1536</b>, one note-on per rendered frame.</p>
     */
    @Test
    void subFrameLoopFiresTheLoopStartNoteOnceNotOnEveryFrame() {
        double subFrameSampleRate = 32_768.0;   // → samplesPerBeat = 16384 at 120 BPM
        double subFrameLoopStart = 4.0;
        double subFrameLoopEnd = subFrameLoopStart + 0.3 / 16_384.0; // 0.3 frame

        StubSoundFontRenderer instance = renderSessionFrom(
                subFrameLoopStart,
                subFrameSampleRate, TEMPO, 512, 3,
                true, subFrameLoopStart, subFrameLoopEnd,
                List.of(MidiNoteData.of(60, 16, 1, 100))); // column 16 → beat 4.0 = loop start

        assertThat(countNoteOns(instance, 60))
                .as("a loop shorter than one frame whose length is not an exact "
                        + "binary fraction must not re-trigger its loop-start note on "
                        + "every one of the 1536 rendered frames")
                .isEqualTo(1);
    }

    /**
     * Story 315 review — the content walk's sub-frame guard
     * ({@code loopLength × samplesPerBeat >= 1.0}) must stay ENABLED for a
     * loop of between one and two frames.
     *
     * <p>{@link #subFrameLoopFiresTheLoopStartNoteOnceNotOnEveryFrame()} pins
     * only the lower side of the threshold — it rules out anything at or
     * below 0.3 frame, so 0.5, 1.0, 2.0 and 4.0 all satisfy it alike, and no
     * other fixture uses a loop between one and two frames. This test pins
     * the upper side: the loop is exactly 1.5 frames, so a threshold of 2.0
     * or 4.0 switches the widening off on a loop that still needs it and the
     * note-on count collapses from 1023 to <b>0</b>.</p>
     *
     * <p>Construction, all quantities exact in binary. At 32768 Hz / 120 BPM
     * samplesPerBeat is exactly 16384, so one frame is 2⁻¹⁴ beats; the loop
     * is 3·2⁻¹⁵ beats = 1.5 frames, starting on beat 4.0 where the note
     * sits. The transport is parked a QUARTER of a frame into the loop rather
     * than on the loop start, which is what makes the count a clean
     * discriminator: the residue orbit is then the exact two-cycle
     * 0.25 → 0.75 → 0.25 frame and never returns to the loop start, so the
     * note is NEVER admitted by the ordinary window test
     * ({@code noteStartBeat >= windowStartBeat}). Every note-on counted here
     * is one the widening recovered.</p>
     *
     * <p>Segments alternate 2, 1, 2, 1, … frames long, so over 3 × 512 = 1536
     * frames there are 1536 ÷ 3 × 2 = 1024 of them. All of them fire except
     * the very first, which begins before any wrap has set the flag: 1023.
     * </p>
     *
     * <p><b>The missing 1024th is the second review round's other pin.</b>
     * That round gave ordinary continuation edges a window reaching half a
     * frame back past their own cursor, so that an event in a segment's last
     * half frame is CARRIED into the next segment rather than re-quantized
     * into the current one. The note here sits a quarter frame BEFORE the
     * render's very first cursor, so a naive "not a lap start ⇒ carry" rule
     * would sweep it in and make this 1024. It must not: the start of
     * playback — like a seek — is a HARD left edge, because there is no
     * previous segment that declined the event, and carrying there would let
     * a seek into the middle of a note re-trigger its note-on. Replacing the
     * guarded left edge with that naive rule
     * ({@code segmentBeginsLap ? frameOriginBeat : frameOriginBeat −
     * halfFrameBeats}) fails HERE and nowhere else in this class — 1024
     * against 1023 — which is what this fixture is for.</p>
     *
     * <p><b>Be precise about what it does NOT cover, because the boundary is
     * counter-intuitive.</b> Story 315 review (third round) — this fixture
     * does not pin the CONTINUITY GUARD that decides which edges are
     * continuations, and cannot: forcing that guard false leaves this test
     * green (the three fixtures that fail are
     * {@link #noteOnLandingInTheLastHalfFrameIsCarriedIntoTheNextSegment()},
     * {@link #noteOffLandingInTheLastHalfFrameIsCarriedIntoTheNextSegment()}
     * and
     * {@link #eventExactlyOnABlockBoundaryBelongsToTheBlockThatBeginsThere()}),
     * and forcing it TRUE leaves this test green too. The second case is the
     * surprising one: with the guard forced true the first segment's window
     * start becomes the still-NaN remembered bound, every
     * {@code noteStartBeat >= NaN} is false, and admitting nothing lands on
     * the same 1023 that the hard edge produces. The count agrees by
     * coincidence, not by coverage. Do not read this fixture's green as
     * evidence that the guard is exercised, and do not "simplify" the guard
     * on the strength of it.</p>
     */
    @Test
    void oneAndAHalfFrameLoopKeepsTheEventWindowWideningEnabled() {
        double subFrameSampleRate = 32_768.0;             // → samplesPerBeat = 16384 at 120 BPM
        double loopStart = 4.0;
        double loopEnd = loopStart + 3.0 / 32_768.0;      // 1.5 frames, exact in binary
        double quarterFrameBeats = 1.0 / 65_536.0;        // a quarter of a frame

        StubSoundFontRenderer instance = renderSessionFrom(
                loopStart + quarterFrameBeats,
                subFrameSampleRate, TEMPO, 512, 3,
                true, loopStart, loopEnd,
                List.of(MidiNoteData.of(60, 16, 1, 100))); // column 16 → beat 4.0 = loop start

        assertThat(countNoteOns(instance, 60))
                .as("a loop of 1.5 frames must keep the event-window widening enabled: "
                        + "the cursor never lands on the loop start, so every one of the "
                        + "1023 note-ons is one the widening recovered — and the render's "
                        + "own first segment is a hard left edge, so there is no 1024th")
                .isEqualTo(1023);
    }

    /**
     * Plays {@code notes} through a fresh looping session — see the
     * loop-boundary fixture constants at the top of this class — and returns
     * the stub renderer instance that received the MIDI events.
     *
     * <p>Each call builds its own {@link AudioFormat}, {@link AudioEngine},
     * {@link Transport} and {@link Mixer} because the class-level fixture is
     * an 8-frame buffer at 120 BPM, which cannot express a loop lap.</p>
     */
    private static StubSoundFontRenderer renderLoopedSession(List<MidiNoteData> notes) {
        AudioFormat loopFormat =
                new AudioFormat(SAMPLE_RATE, CHANNELS, 16, LOOP_BLOCK_FRAMES);
        AudioEngine loopEngine = new AudioEngine(loopFormat);
        Transport loopTransport = new Transport();
        loopTransport.setTempo(LOOP_TEMPO);
        Mixer loopMixer = new Mixer();

        // These tests are about events, not audio — render silence.
        StubSoundFontRenderer stubRenderer = new StubSoundFontRenderer(0.0f);
        MidiTrackRenderer midiRenderer = new MidiTrackRenderer(
                SAMPLE_RATE, LOOP_BLOCK_FRAMES, stubRenderer::newInstance);

        Track midiTrack = new Track("MIDI Track", TrackType.MIDI);
        midiTrack.setSoundFontAssignment(ASSIGNMENT);
        for (MidiNoteData note : notes) {
            midiTrack.getMidiClip().addNote(note);
        }

        MixerChannel mixerChannel = new MixerChannel("MIDI Track");
        loopMixer.addChannel(mixerChannel);

        loopTransport.setLoopEnabled(true);
        loopTransport.setLoopRegion(LOOP_START_BEAT, LOOP_END_BEAT);
        loopTransport.play();
        loopEngine.setGraph(loopTransport, loopMixer, List.of(midiTrack));
        loopEngine.start();
        midiRenderer.prepareRenderer(midiTrack);
        loopEngine.setMidiTrackRenderer(midiRenderer);

        float[][] input = new float[CHANNELS][LOOP_BLOCK_FRAMES];
        float[][] output = new float[CHANNELS][LOOP_BLOCK_FRAMES];
        for (int block = 0; block < LOOP_BLOCK_COUNT; block++) {
            loopEngine.processBlock(input, output, LOOP_BLOCK_FRAMES);
        }

        StubSoundFontRenderer instance = stubRenderer.lastCreatedInstance;
        assertThat(instance).isNotNull();
        return instance;
    }

    /**
     * Story 315 review — same idea as {@link #renderLoopedSession}, but with
     * the transport/format constants supplied by the caller. The ulp-level
     * regressions below each need their own sample rate, tempo, block size
     * and loop length (the values are chosen so the arithmetic is exact in
     * binary and the failing frame is reproducible to the last bit), and one
     * of them runs with looping switched off entirely.
     *
     * @param loopEnabled when {@code false} the loop region is left untouched
     *                    and {@code loopStartBeat}/{@code loopEndBeat} are ignored
     */
    private static StubSoundFontRenderer renderSession(double sampleRate, double tempo,
                                                       int blockFrames, int blockCount,
                                                       boolean loopEnabled,
                                                       double loopStartBeat, double loopEndBeat,
                                                       List<MidiNoteData> notes) {
        return renderSession(sampleRate, tempo, blockFrames, blockCount,
                loopEnabled, loopStartBeat, loopEndBeat, 0, notes);
    }

    /**
     * Story 315 review — {@link #renderSession(double, double, int, int,
     * boolean, double, double, List)} with a latent insert on the track's
     * mixer channel, which is what puts the render pipeline's PDC-shifted
     * content cursor ahead of the raw transport cursor.
     *
     * @param insertLatencyFrames frames of latency the channel's insert chain
     *                            reports; {@code 0} adds no insert at all
     */
    private static StubSoundFontRenderer renderSession(double sampleRate, double tempo,
                                                       int blockFrames, int blockCount,
                                                       boolean loopEnabled,
                                                       double loopStartBeat, double loopEndBeat,
                                                       int insertLatencyFrames,
                                                       List<MidiNoteData> notes) {
        return renderSession(sampleRate, tempo, blockFrames, blockCount, 0, 0,
                loopEnabled, loopStartBeat, loopEndBeat, insertLatencyFrames, 0.0, notes);
    }

    /**
     * Story 315 review — {@link #renderSession(double, double, int, int,
     * boolean, double, double, List)} starting somewhere other than beat 0.
     *
     * <p>The seek is issued while the transport is still STOPPED, so it
     * applies inline instead of being queued for the next block boundary.</p>
     *
     * @param startBeat the beat the transport is parked on before playback
     */
    private static StubSoundFontRenderer renderSessionFrom(double startBeat,
                                                           double sampleRate, double tempo,
                                                           int blockFrames, int blockCount,
                                                           boolean loopEnabled,
                                                           double loopStartBeat,
                                                           double loopEndBeat,
                                                           List<MidiNoteData> notes) {
        return renderSession(sampleRate, tempo, blockFrames, blockCount, 0, 0,
                loopEnabled, loopStartBeat, loopEndBeat, 0, startBeat, notes);
    }

    /**
     * Story 315 review — {@link #renderSession(double, double, int, int,
     * boolean, double, double, int, List)} with a pause/resume in the middle
     * of the render.
     *
     * <p>The paused blocks are still handed to
     * {@code AudioEngine.processBlock}, because that is exactly what the
     * audio backend does: the device callback keeps firing at the buffer
     * period regardless of transport state, and {@code processBlock} forwards
     * every one of them to {@code RenderPipeline.renderBlock}. Only
     * {@code renderBlock}'s own {@code playbackActive} branches are skipped
     * while paused; the method itself runs. A regression that clears
     * per-block loop state on the not-playing path therefore fires on every
     * paused callback, not once at the pause.</p>
     *
     * @param pausedBlockCount  blocks pushed through the engine with the
     *                          transport {@code PAUSED}; {@code 0} pauses
     *                          nothing
     * @param resumedBlockCount blocks rendered after {@code play()} resumes
     * @param startBeat         the beat the transport is parked on before
     *                          playback starts; the seek is issued while
     *                          STOPPED so it applies inline
     */
    private static StubSoundFontRenderer renderSession(double sampleRate, double tempo,
                                                       int blockFrames, int blockCount,
                                                       int pausedBlockCount,
                                                       int resumedBlockCount,
                                                       boolean loopEnabled,
                                                       double loopStartBeat, double loopEndBeat,
                                                       int insertLatencyFrames,
                                                       double startBeat,
                                                       List<MidiNoteData> notes) {
        AudioFormat sessionFormat = new AudioFormat(sampleRate, CHANNELS, 16, blockFrames);
        AudioEngine sessionEngine = new AudioEngine(sessionFormat);
        Transport sessionTransport = new Transport();
        sessionTransport.setTempo(tempo);
        Mixer sessionMixer = new Mixer();

        // These tests are about events, not audio — render silence.
        StubSoundFontRenderer stubRenderer = new StubSoundFontRenderer(0.0f);
        MidiTrackRenderer midiRenderer = new MidiTrackRenderer(
                sampleRate, blockFrames, stubRenderer::newInstance);

        Track midiTrack = new Track("MIDI Track", TrackType.MIDI);
        midiTrack.setSoundFontAssignment(ASSIGNMENT);
        for (MidiNoteData note : notes) {
            midiTrack.getMidiClip().addNote(note);
        }
        MixerChannel sessionChannel = new MixerChannel("MIDI Track");
        sessionMixer.addChannel(sessionChannel);
        if (insertLatencyFrames > 0) {
            // A unity-gain passthrough that only REPORTS latency: the channel
            // stays gain-neutral while getSystemLatencySamples() rises, so the
            // render cursor runs insertLatencyFrames ahead of the transport.
            sessionChannel.addInsert(new InsertSlot("Latent",
                    new ReportedLatencyProcessor(insertLatencyFrames)));
            assertThat(sessionMixer.getSystemLatencySamples())
                    .as("harness precondition: the insert chain must report its latency")
                    .isEqualTo(insertLatencyFrames);
        }

        if (loopEnabled) {
            sessionTransport.setLoopEnabled(true);
            sessionTransport.setLoopRegion(loopStartBeat, loopEndBeat);
        }
        if (startBeat != 0.0) {
            sessionTransport.setPositionInBeats(startBeat); // inline: applied while STOPPED
        }
        sessionTransport.play();
        sessionEngine.setGraph(sessionTransport, sessionMixer, List.of(midiTrack));
        sessionEngine.start();
        midiRenderer.prepareRenderer(midiTrack);
        sessionEngine.setMidiTrackRenderer(midiRenderer);

        float[][] input = new float[CHANNELS][blockFrames];
        float[][] output = new float[CHANNELS][blockFrames];
        for (int block = 0; block < blockCount; block++) {
            sessionEngine.processBlock(input, output, blockFrames);
        }
        if (pausedBlockCount > 0) {
            // The backend callback does NOT stop at a pause, so neither does
            // processBlock — see this method's javadoc.
            sessionTransport.pause();
            assertThat(sessionTransport.getState())
                    .as("harness precondition: the transport must actually be paused")
                    .isEqualTo(TransportState.PAUSED);
            for (int block = 0; block < pausedBlockCount; block++) {
                sessionEngine.processBlock(input, output, blockFrames);
            }
            sessionTransport.play();
        }
        for (int block = 0; block < resumedBlockCount; block++) {
            sessionEngine.processBlock(input, output, blockFrames);
        }

        StubSoundFontRenderer instance = stubRenderer.lastCreatedInstance;
        assertThat(instance).isNotNull();
        return instance;
    }

    /** Counts the NOTE_ON events received for a single note number. */
    private static long countNoteOns(StubSoundFontRenderer instance, int noteNumber) {
        return instance.receivedEvents.stream()
                .filter(e -> e.type() == MidiEvent.Type.NOTE_ON && e.data1() == noteNumber)
                .count();
    }

    /** Counts the NOTE_OFF events received for a single note number. */
    private static long countNoteOffs(StubSoundFontRenderer instance, int noteNumber) {
        return instance.receivedEvents.stream()
                .filter(e -> e.type() == MidiEvent.Type.NOTE_OFF && e.data1() == noteNumber)
                .count();
    }

    /**
     * The exact frames a single note number's NOTE_ON events were dispatched
     * at, in dispatch order — see {@link StubSoundFontRenderer.Dispatch}.
     * Counting events alone cannot see a regression that fires the right
     * event on the right lap at the WRONG frame, which is precisely what a
     * change to the widened frame origin does.
     */
    private static List<Integer> noteOnFrames(StubSoundFontRenderer instance, int noteNumber) {
        return dispatchFrames(instance, MidiEvent.Type.NOTE_ON, noteNumber);
    }

    /** {@link #noteOnFrames} for NOTE_OFF. */
    private static List<Integer> noteOffFrames(StubSoundFontRenderer instance, int noteNumber) {
        return dispatchFrames(instance, MidiEvent.Type.NOTE_OFF, noteNumber);
    }

    private static List<Integer> dispatchFrames(StubSoundFontRenderer instance,
                                                MidiEvent.Type type, int noteNumber) {
        return instance.dispatches.stream()
                .filter(d -> d.event().type() == type && d.event().data1() == noteNumber)
                .map(StubSoundFontRenderer.Dispatch::frame)
                .toList();
    }

    // ── MidiTrackRenderer unit tests ────────────────────────────────────────

    @Test
    void midiTrackRendererShouldScheduleNoteOnAndOff() {
        StubSoundFontRenderer stubRenderer = new StubSoundFontRenderer(0.0f);
        MidiTrackRenderer renderer = new MidiTrackRenderer(
                SAMPLE_RATE, BUFFER_SIZE, stubRenderer::newInstance);

        Track track = new Track("MIDI Track", TrackType.MIDI);
        track.setSoundFontAssignment(ASSIGNMENT);
        // Note at column 0, duration 1 column (0.25 beats)
        // At 120 BPM, 0.25 beats = ~5512 samples
        // Our 8-frame buffer at beat 0 covers [0, 8/22050 beats) ≈ [0, 0.000363 beats)
        // Both note start (0.0) and end (0.25) don't both fall in this tiny range.
        // Only the note start (0.0) falls in the range.
        track.getMidiClip().addNote(new MidiNoteData(60, 0, 1, 100, 0));

        // Prepare the renderer before the RT render call
        renderer.prepareRenderer(track);

        float[][] buffer = new float[2][BUFFER_SIZE];
        double samplesPerBeat = SAMPLE_RATE * 60.0 / TEMPO;
        double startBeat = 0.0;
        double endBeat = BUFFER_SIZE / samplesPerBeat;

        // windowStartBeat and frameOriginBeat coincide here: this is a direct
        // unit call with no loop split and no continuation edge to carry an
        // event across, so the segment's cursor is both its admission bound
        // and its frame-mapping origin.
        renderer.renderMidiTrack(track, buffer, startBeat, endBeat,
                startBeat, samplesPerBeat, 0, BUFFER_SIZE);

        StubSoundFontRenderer instance = stubRenderer.lastCreatedInstance;
        assertThat(instance).isNotNull();

        // Should have note-on (note starts at beat 0.0, within range)
        assertThat(instance.receivedEvents).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(MidiEvent.Type.NOTE_ON);
            assertThat(e.data1()).isEqualTo(60);
        });

        // Note-off is at beat 0.25, which is beyond our tiny 8-frame segment
        assertThat(instance.receivedEvents).noneMatch(
                e -> e.type() == MidiEvent.Type.NOTE_OFF);
    }

    @Test
    void midiTrackRendererShouldCloseOnDispose() {
        StubSoundFontRenderer stubRenderer = new StubSoundFontRenderer(0.0f);
        MidiTrackRenderer renderer = new MidiTrackRenderer(
                SAMPLE_RATE, BUFFER_SIZE, stubRenderer::newInstance);

        Track track = new Track("MIDI Track", TrackType.MIDI);
        track.setSoundFontAssignment(ASSIGNMENT);
        track.getMidiClip().addNote(MidiNoteData.of(60, 0, 4, 100));

        renderer.prepareRenderer(track);

        float[][] buffer = new float[2][BUFFER_SIZE];
        double samplesPerBeat = SAMPLE_RATE * 60.0 / TEMPO;
        renderer.renderMidiTrack(track, buffer, 0.0,
                BUFFER_SIZE / samplesPerBeat, 0.0, samplesPerBeat, 0, BUFFER_SIZE);

        assertThat(renderer.hasRenderer(track.getId())).isTrue();

        renderer.close();
        assertThat(renderer.hasRenderer(track.getId())).isFalse();
        assertThat(stubRenderer.lastCreatedInstance.closed).isTrue();
    }

    @Test
    void midiTrackRendererShouldReuseExistingRenderer() {
        StubSoundFontRenderer stubRenderer = new StubSoundFontRenderer(0.0f);
        MidiTrackRenderer renderer = new MidiTrackRenderer(
                SAMPLE_RATE, BUFFER_SIZE, stubRenderer::newInstance);

        Track track = new Track("MIDI Track", TrackType.MIDI);
        track.setSoundFontAssignment(ASSIGNMENT);
        track.getMidiClip().addNote(MidiNoteData.of(60, 0, 4, 100));

        renderer.prepareRenderer(track);

        float[][] buffer = new float[2][BUFFER_SIZE];
        double samplesPerBeat = SAMPLE_RATE * 60.0 / TEMPO;
        double endBeat = BUFFER_SIZE / samplesPerBeat;

        // First render
        renderer.renderMidiTrack(track, buffer, 0.0, endBeat,
                0.0, samplesPerBeat, 0, BUFFER_SIZE);
        StubSoundFontRenderer firstInstance = stubRenderer.lastCreatedInstance;

        // Second render — should reuse the same renderer
        renderer.renderMidiTrack(track, buffer, 0.0, endBeat,
                0.0, samplesPerBeat, 0, BUFFER_SIZE);
        StubSoundFontRenderer secondInstance = stubRenderer.lastCreatedInstance;

        assertThat(secondInstance).isSameAs(firstInstance);
        assertThat(stubRenderer.instanceCount).isEqualTo(1);
    }

    @Test
    void shouldAllocateMidiTrackRendererOnStart() {
        AudioEngine eng = new AudioEngine(FORMAT);
        assertThat(eng.getMidiTrackRenderer()).isNull();

        eng.start();
        assertThat(eng.getMidiTrackRenderer()).isNotNull();
    }

    @Test
    void shouldDisposeMidiTrackRendererOnStop() {
        AudioEngine eng = new AudioEngine(FORMAT);
        eng.start();
        assertThat(eng.getMidiTrackRenderer()).isNotNull();

        eng.stop();
        assertThat(eng.getMidiTrackRenderer()).isNull();
    }

    @Test
    void audioTrackShouldStillRenderNormallyWithMidiRendererPresent() {
        // Verify that audio tracks are unaffected by the MIDI rendering changes
        Track audioTrack = new Track("Audio Track", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Clip", 0.0, 1.0, null);
        float[][] clipData = new float[CHANNELS][(int) SAMPLES_PER_BEAT];
        for (int i = 0; i < BUFFER_SIZE; i++) {
            clipData[0][i] = 0.8f;
            clipData[1][i] = 0.8f;
        }
        clip.setAudioData(clipData);
        audioTrack.addClip(clip);

        MixerChannel mixerChannel = new MixerChannel("Audio Track");
        mixer.addChannel(mixerChannel);

        transport.play();
        engine.setGraph(transport, mixer, List.of(audioTrack));
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        double expectedGain = Math.cos(Math.PI / 4.0);
        for (int i = 0; i < BUFFER_SIZE; i++) {
            assertThat((double) output[0][i]).isCloseTo(0.8 * expectedGain, offset(0.001));
        }
    }

    @Test
    void midiTrackRendererShouldSkipWhenNotPrepared() {
        // Verify that renderMidiTrack returns silently when no renderer
        // has been prepared via prepareRenderer
        StubSoundFontRenderer stubRenderer = new StubSoundFontRenderer(0.5f);
        MidiTrackRenderer midiRenderer = new MidiTrackRenderer(
                SAMPLE_RATE, BUFFER_SIZE, stubRenderer::newInstance);

        Track midiTrack = new Track("MIDI Track", TrackType.MIDI);
        midiTrack.setSoundFontAssignment(ASSIGNMENT);
        midiTrack.getMidiClip().addNote(MidiNoteData.of(60, 0, 4, 100));

        MixerChannel mixerChannel = new MixerChannel("MIDI Track");
        mixer.addChannel(mixerChannel);

        transport.play();
        engine.setGraph(transport, mixer, List.of(midiTrack));
        engine.start();
        // Do NOT call prepareRenderer — renderer should skip silently
        engine.setMidiTrackRenderer(midiRenderer);

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        // Should produce silence since no renderer was prepared
        for (int i = 0; i < BUFFER_SIZE; i++) {
            assertThat(output[0][i]).isEqualTo(0.0f);
            assertThat(output[1][i]).isEqualTo(0.0f);
        }
    }

    // ── Stub SoundFontRenderer ──────────────────────────────────────────────

    /**
     * Factory that creates stub SoundFontRenderer instances for testing.
     * Each created instance records all events and fills render buffers
     * with a known value only when notes are active (after NOTE_ON and
     * before NOTE_OFF), simulating real synthesizer behavior.
     */
    private static class StubSoundFontRenderer implements SoundFontRenderer {

        /**
         * A dispatched MIDI event together with the renderer's cumulative
         * frame count at the moment it was dispatched — i.e. the exact frame
         * the event was placed on.
         *
         * <p>{@code MidiTrackRenderer} renders a segment as sub-chunks
         * partitioned at the event frames: it renders up to the next event's
         * frame offset, sends the event, then renders the remainder. The
         * cumulative frame count is therefore incremented by every frame
         * BEFORE an event and by none after it, so at {@code sendEvent} time
         * it equals the global frame index the event lands on. Frames the
         * renderer never sees (blocks in which the MIDI track is not
         * rendered at all, e.g. while the transport is paused) are not
         * counted, so this is a rendered-frame index, not a wall-clock one.
         * </p>
         *
         * @param event the event as dispatched
         * @param frame the rendered-frame index it was dispatched at
         */
        record Dispatch(MidiEvent event, int frame) {}

        final float renderValue;
        final List<MidiEvent> receivedEvents = new CopyOnWriteArrayList<>();
        /** Every event of {@link #receivedEvents}, paired with its frame. */
        final List<Dispatch> dispatches = new CopyOnWriteArrayList<>();
        /** Frames rendered so far — see {@link Dispatch}. */
        int renderedFrames;
        int selectedBank;
        int selectedProgram;
        boolean closed;
        boolean initialized;
        int instanceCount;
        int allNotesOffCallCount;
        int activeNotes;
        StubSoundFontRenderer lastCreatedInstance;

        StubSoundFontRenderer(float renderValue) {
            this.renderValue = renderValue;
        }

        /**
         * Factory method — creates a new independent stub instance that shares
         * the parent's render value.
         */
        StubSoundFontRenderer newInstance() {
            instanceCount++;
            StubSoundFontRenderer instance = new StubSoundFontRenderer(renderValue);
            instance.instanceCount = instanceCount;
            lastCreatedInstance = instance;
            return instance;
        }

        @Override
        public void initialize(double sampleRate, int bufferSize) {
            initialized = true;
        }

        @Override
        public SoundFontInfo loadSoundFont(Path path) {
            return new SoundFontInfo(0, path, List.of());
        }

        @Override
        public void unloadSoundFont(int soundFontId) {}

        @Override
        public List<SoundFontInfo> getLoadedSoundFonts() {
            return Collections.emptyList();
        }

        @Override
        public void selectPreset(int channel, int bank, int program) {
            selectedBank = bank;
            selectedProgram = program;
        }

        @Override
        public void sendEvent(MidiEvent event) {
            receivedEvents.add(event);
            dispatches.add(new Dispatch(event, renderedFrames));
            if (event.type() == MidiEvent.Type.NOTE_ON) {
                activeNotes++;
            } else if (event.type() == MidiEvent.Type.NOTE_OFF) {
                activeNotes = Math.max(0, activeNotes - 1);
            }
        }

        @Override
        public void render(float[][] outputBuffer, int numFrames) {
            renderedFrames += numFrames;
            if (activeNotes > 0) {
                for (int ch = 0; ch < outputBuffer.length; ch++) {
                    for (int i = 0; i < numFrames; i++) {
                        outputBuffer[ch][i] += renderValue;
                    }
                }
            }
        }

        @Override
        public float[][] bounce(List<MidiEvent> events, int totalFrames) {
            return new float[2][totalFrames];
        }

        @Override
        public void setReverbEnabled(boolean enabled) {}

        @Override
        public void setChorusEnabled(boolean enabled) {}

        @Override
        public void setGain(float gain) {}

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String getRendererName() {
            return "Stub";
        }

        @Override
        public void allNotesOff() {
            allNotesOffCallCount++;
            activeNotes = 0;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
