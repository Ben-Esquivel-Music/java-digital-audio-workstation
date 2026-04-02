package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.midi.SoundFontAssignment;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
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
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(midiTrack));
        engine.start();

        // Inject the test MidiTrackRenderer
        injectMidiTrackRenderer(engine, midiRenderer);

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
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(midiTrack));
        engine.start();
        injectMidiTrackRenderer(engine, midiRenderer);

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
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(midiTrack));
        engine.start();
        injectMidiTrackRenderer(engine, midiRenderer);

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
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(midiTrack));
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
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(midiTrack));
        engine.start();
        injectMidiTrackRenderer(engine, midiRenderer);

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
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(audioTrack, midiTrack));
        engine.start();
        injectMidiTrackRenderer(engine, midiRenderer);

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
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(midiTrack));
        engine.start();
        injectMidiTrackRenderer(engine, midiRenderer);

        // First render — establishes the renderer
        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        StubSoundFontRenderer instance = stubRenderer.lastCreatedInstance;
        assertThat(instance).isNotNull();
        assertThat(instance.selectedBank).isEqualTo(0);
        assertThat(instance.selectedProgram).isEqualTo(0);

        // Change the SoundFont assignment
        SoundFontAssignment newAssignment = new SoundFontAssignment(
                DUMMY_SF2_PATH, 0, 42, "Electric Piano");
        midiTrack.setSoundFontAssignment(newAssignment);

        // Reset transport to beat 0 for a clean second render
        transport.setPositionInBeats(0.0);
        output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        // The same renderer instance should have been reused with the new preset
        assertThat(instance.selectedProgram).isEqualTo(42);
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

        float[][] buffer = new float[2][BUFFER_SIZE];
        double samplesPerBeat = SAMPLE_RATE * 60.0 / TEMPO;
        double startBeat = 0.0;
        double endBeat = BUFFER_SIZE / samplesPerBeat;

        renderer.renderMidiTrack(track, buffer, startBeat, endBeat,
                samplesPerBeat, 0, BUFFER_SIZE);

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

        float[][] buffer = new float[2][BUFFER_SIZE];
        double samplesPerBeat = SAMPLE_RATE * 60.0 / TEMPO;
        renderer.renderMidiTrack(track, buffer, 0.0,
                BUFFER_SIZE / samplesPerBeat, samplesPerBeat, 0, BUFFER_SIZE);

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

        float[][] buffer = new float[2][BUFFER_SIZE];
        double samplesPerBeat = SAMPLE_RATE * 60.0 / TEMPO;
        double endBeat = BUFFER_SIZE / samplesPerBeat;

        // First render
        renderer.renderMidiTrack(track, buffer, 0.0, endBeat,
                samplesPerBeat, 0, BUFFER_SIZE);
        StubSoundFontRenderer firstInstance = stubRenderer.lastCreatedInstance;

        // Second render — should reuse the same renderer
        renderer.renderMidiTrack(track, buffer, 0.0, endBeat,
                samplesPerBeat, 0, BUFFER_SIZE);
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
        engine.setTransport(transport);
        engine.setMixer(mixer);
        engine.setTracks(List.of(audioTrack));
        engine.start();

        float[][] input = new float[CHANNELS][BUFFER_SIZE];
        float[][] output = new float[CHANNELS][BUFFER_SIZE];
        engine.processBlock(input, output, BUFFER_SIZE);

        double expectedGain = Math.cos(Math.PI / 4.0);
        for (int i = 0; i < BUFFER_SIZE; i++) {
            assertThat((double) output[0][i]).isCloseTo(0.8 * expectedGain, offset(0.001));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Injects a custom MidiTrackRenderer into the engine by replacing the
     * one allocated during start().
     */
    private static void injectMidiTrackRenderer(AudioEngine engine, MidiTrackRenderer renderer) {
        try {
            java.lang.reflect.Field field = AudioEngine.class.getDeclaredField("midiTrackRenderer");
            field.setAccessible(true);
            field.set(engine, renderer);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject MidiTrackRenderer", e);
        }
    }

    // ── Stub SoundFontRenderer ──────────────────────────────────────────────

    /**
     * Factory that creates stub SoundFontRenderer instances for testing.
     * Each created instance records all events and fills render buffers
     * with a known value.
     */
    private static class StubSoundFontRenderer implements SoundFontRenderer {

        final float renderValue;
        final List<MidiEvent> receivedEvents = new CopyOnWriteArrayList<>();
        int selectedBank;
        int selectedProgram;
        boolean closed;
        boolean initialized;
        int instanceCount;
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
        }

        @Override
        public void render(float[][] outputBuffer, int numFrames) {
            for (int ch = 0; ch < outputBuffer.length; ch++) {
                for (int i = 0; i < numFrames; i++) {
                    outputBuffer[ch][i] += renderValue;
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
        public void allNotesOff() {}

        @Override
        public void close() {
            closed = true;
        }
    }
}
