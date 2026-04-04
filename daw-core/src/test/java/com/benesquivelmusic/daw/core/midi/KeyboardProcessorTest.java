package com.benesquivelmusic.daw.core.midi;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyboardProcessorTest {

    private StubRenderer renderer;
    private KeyboardProcessor processor;

    @BeforeEach
    void setUp() {
        renderer = new StubRenderer();
        processor = new KeyboardProcessor(renderer, KeyboardPreset.grandPiano());
    }

    // ── Construction ───────────────────────────────────────────────────

    @Test
    void shouldRejectNullRenderer() {
        assertThatThrownBy(() -> new KeyboardProcessor(null, KeyboardPreset.grandPiano()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullPreset() {
        assertThatThrownBy(() -> new KeyboardProcessor(renderer, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectInvalidChannel() {
        assertThatThrownBy(() -> new KeyboardProcessor(renderer, KeyboardPreset.grandPiano(), -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeyboardProcessor(renderer, KeyboardPreset.grandPiano(), 16))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldUseDefaultChannelZero() {
        assertThat(processor.getChannel()).isZero();
    }

    @Test
    void shouldCreateWithSpecificChannel() {
        KeyboardProcessor proc = new KeyboardProcessor(renderer, KeyboardPreset.grandPiano(), 5);
        assertThat(proc.getChannel()).isEqualTo(5);
    }

    // ── Preset Management ──────────────────────────────────────────────

    @Test
    void shouldReturnInitialPreset() {
        assertThat(processor.getPreset().name()).isEqualTo("Grand Piano");
    }

    @Test
    void shouldChangePreset() {
        processor.setPreset(KeyboardPreset.organ());
        assertThat(processor.getPreset().name()).isEqualTo("Organ");
    }

    @Test
    void shouldSendBankAndProgramOnPresetChange() {
        processor.setPreset(KeyboardPreset.electricPiano());
        // Should have called selectPreset with bank=0, program=4
        assertThat(renderer.presetSelections).anySatisfy(ps -> {
            assertThat(ps.channel()).isZero();
            assertThat(ps.bank()).isZero();
            assertThat(ps.program()).isEqualTo(4);
        });
    }

    @Test
    void shouldApplyInitialPresetToRenderer() {
        // Constructor should have applied Grand Piano preset (bank=0, program=0)
        assertThat(renderer.presetSelections).anySatisfy(ps -> {
            assertThat(ps.channel()).isZero();
            assertThat(ps.bank()).isZero();
            assertThat(ps.program()).isZero();
        });
    }

    @Test
    void shouldRejectNullPresetOnSet() {
        assertThatThrownBy(() -> processor.setPreset(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Note Playing ───────────────────────────────────────────────────

    @Test
    void shouldSendNoteOnToRenderer() {
        processor.noteOn(60, 100);
        assertThat(renderer.receivedEvents).hasSize(1);
        MidiEvent event = renderer.receivedEvents.getFirst();
        assertThat(event.type()).isEqualTo(MidiEvent.Type.NOTE_ON);
        assertThat(event.data1()).isEqualTo(60);
    }

    @Test
    void shouldSendNoteOffToRenderer() {
        processor.noteOn(60, 100);
        processor.noteOff(60);
        assertThat(renderer.receivedEvents).hasSize(2);
        MidiEvent offEvent = renderer.receivedEvents.get(1);
        assertThat(offEvent.type()).isEqualTo(MidiEvent.Type.NOTE_OFF);
        assertThat(offEvent.data1()).isEqualTo(60);
    }

    @Test
    void shouldTrackActiveNotes() {
        assertThat(processor.isNoteActive(60)).isFalse();
        assertThat(processor.getActiveNoteCount()).isZero();

        processor.noteOn(60, 100);
        assertThat(processor.isNoteActive(60)).isTrue();
        assertThat(processor.getActiveNoteCount()).isEqualTo(1);

        processor.noteOff(60);
        assertThat(processor.isNoteActive(60)).isFalse();
        assertThat(processor.getActiveNoteCount()).isZero();
    }

    @Test
    void shouldNotDoubleNoteOn() {
        processor.noteOn(60, 100);
        processor.noteOn(60, 100); // duplicate, should be ignored
        assertThat(renderer.receivedEvents).hasSize(1);
        assertThat(processor.getActiveNoteCount()).isEqualTo(1);
    }

    @Test
    void shouldNotNoteOffWhenNotActive() {
        processor.noteOff(60); // not active, should be ignored
        assertThat(renderer.receivedEvents).isEmpty();
    }

    @Test
    void shouldApplyTranspositionOnNoteOn() {
        processor.setPreset(KeyboardPreset.grandPiano().withTranspose(12));
        renderer.receivedEvents.clear();
        processor.noteOn(60, 100);
        MidiEvent event = renderer.receivedEvents.stream()
                .filter(e -> e.type() == MidiEvent.Type.NOTE_ON)
                .findFirst().orElseThrow();
        assertThat(event.data1()).isEqualTo(72); // 60 + 12
    }

    @Test
    void shouldSkipNoteOutOfRangeAfterTranspose() {
        processor.setPreset(KeyboardPreset.grandPiano().withTranspose(48));
        renderer.receivedEvents.clear();
        processor.noteOn(100, 100); // 100 + 48 = 148 > 127
        assertThat(renderer.receivedEvents.stream()
                .filter(e -> e.type() == MidiEvent.Type.NOTE_ON)).isEmpty();
    }

    @Test
    void shouldRejectInvalidNoteNumber() {
        assertThatThrownBy(() -> processor.noteOn(-1, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor.noteOn(128, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor.noteOff(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidVelocity() {
        assertThatThrownBy(() -> processor.noteOn(60, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor.noteOn(60, 128))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allNotesOffShouldSilenceAllActiveNotes() {
        processor.noteOn(60, 100);
        processor.noteOn(64, 100);
        processor.noteOn(67, 100);
        assertThat(processor.getActiveNoteCount()).isEqualTo(3);

        processor.allNotesOff();
        assertThat(processor.getActiveNoteCount()).isZero();
        assertThat(processor.isNoteActive(60)).isFalse();
        assertThat(processor.isNoteActive(64)).isFalse();
        assertThat(processor.isNoteActive(67)).isFalse();
    }

    // ── Velocity Curve Application ─────────────────────────────────────

    @Test
    void shouldApplyVelocityCurve() {
        processor.setPreset(KeyboardPreset.grandPiano().withVelocityCurve(VelocityCurve.HARD));
        renderer.receivedEvents.clear();
        processor.noteOn(60, 64);

        MidiEvent event = renderer.receivedEvents.stream()
                .filter(e -> e.type() == MidiEvent.Type.NOTE_ON)
                .findFirst().orElseThrow();
        // Hard curve: (64/127)^2 * 127 ≈ 32
        assertThat(event.data2()).isLessThan(64);
    }

    @Test
    void fixedCurveShouldUseDefaultVelocity() {
        // Organ preset uses FIXED curve with defaultVelocity=100
        processor.setPreset(KeyboardPreset.organ());
        renderer.receivedEvents.clear();
        processor.noteOn(60, 50); // raw velocity 50, but FIXED → defaultVelocity 100

        MidiEvent event = renderer.receivedEvents.stream()
                .filter(e -> e.type() == MidiEvent.Type.NOTE_ON)
                .findFirst().orElseThrow();
        assertThat(event.data2()).isEqualTo(100);
    }

    // ── Recording ──────────────────────────────────────────────────────

    @Test
    void shouldNotBeRecordingInitially() {
        assertThat(processor.isRecording()).isFalse();
    }

    @Test
    void shouldStartAndStopRecording() {
        processor.startRecording(120.0, 0);
        assertThat(processor.isRecording()).isTrue();
        processor.stopRecording();
        assertThat(processor.isRecording()).isFalse();
    }

    @Test
    void shouldRejectNonPositiveRecordingTempo() {
        assertThatThrownBy(() -> processor.startRecording(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor.startRecording(-120, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeColumnOffset() {
        assertThatThrownBy(() -> processor.startRecording(120.0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRecordNotesIntoClip() {
        processor.startRecording(120.0, 0);
        processor.noteOn(60, 100);
        processor.noteOff(60);
        processor.stopRecording();

        assertThat(processor.getClip().size()).isPositive();
        assertThat(processor.getRecordedNotes()).isNotEmpty();
    }

    @Test
    void shouldFinalizeHeldNotesOnStopRecording() {
        processor.startRecording(120.0, 0);
        processor.noteOn(60, 100);
        // Don't send noteOff — stopRecording should finalize it
        processor.stopRecording();

        assertThat(processor.getClip().size()).isEqualTo(1);
    }

    @Test
    void shouldFinalizeHeldNotesWithCorrectDuration() {
        long[] clockTimeMs = useFakeClock(1000L);

        processor.startRecording(120.0, 0);
        processor.noteOn(60, 100);  // starts at clock 1000 → column 0

        // Advance 500ms → at 120 BPM that's 4 columns
        clockTimeMs[0] = 1500L;
        processor.stopRecording();

        List<MidiNoteData> notes = processor.getRecordedNotes();
        assertThat(notes).hasSize(1);
        assertThat(notes.getFirst().durationColumns()).isEqualTo(4); // not hard-coded 1
    }

    @Test
    void shouldClearRecordedNotesOnNewRecording() {
        processor.startRecording(120.0, 0);
        processor.noteOn(60, 100);
        processor.noteOff(60);
        processor.stopRecording();

        int firstCount = processor.getRecordedNotes().size();
        processor.startRecording(120.0, 0);
        assertThat(processor.getRecordedNotes()).isEmpty();
    }

    @Test
    void stopRecordingShouldBeIdempotent() {
        processor.stopRecording(); // not recording, should be safe
        assertThat(processor.isRecording()).isFalse();
    }

    // ── Playback ───────────────────────────────────────────────────────

    @Test
    void shouldNotBePlayingInitially() {
        assertThat(processor.isPlaying()).isFalse();
    }

    @Test
    void shouldNotStartPlaybackOnEmptyClip() {
        processor.startPlayback(120.0);
        assertThat(processor.isPlaying()).isFalse();
    }

    @Test
    void shouldRejectNonPositivePlaybackTempo() {
        assertThatThrownBy(() -> processor.startPlayback(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldStopPlayback() {
        // Add a note to the clip
        processor.getClip().addNote(MidiNoteData.of(60, 0, 4, 100));
        processor.startPlayback(120.0);
        assertThat(processor.isPlaying()).isTrue();
        processor.stopPlayback();
        assertThat(processor.isPlaying()).isFalse();
    }

    @Test
    void stopPlaybackShouldBeIdempotent() {
        processor.stopPlayback(); // not playing, should be safe
        assertThat(processor.isPlaying()).isFalse();
    }

    @Test
    void advancePlaybackShouldTriggerNotesAtColumnZero() {
        long[] clockTimeMs = useFakeClock(1000L);

        processor.getClip().addNote(MidiNoteData.of(60, 0, 2, 100));
        renderer.receivedEvents.clear();
        processor.startPlayback(120.0);  // captures clockTimeMs[0] = 1000
        assertThat(processor.isPlaying()).isTrue();

        // advancePlayback with same time → column 0 (elapsed = 0)
        processor.advancePlayback();

        assertThat(renderer.receivedEvents).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(MidiEvent.Type.NOTE_ON);
            assertThat(e.data1()).isEqualTo(60);
        });
    }

    @Test
    void playbackShouldUpdateActiveNotes() {
        long[] clockTimeMs = useFakeClock(1000L);

        processor.getClip().addNote(MidiNoteData.of(60, 0, 2, 100));
        renderer.receivedEvents.clear();
        processor.startPlayback(120.0);

        // Advance to column 0 — note-on should mark note active
        processor.advancePlayback();
        assertThat(processor.isNoteActive(60)).isTrue();
        assertThat(processor.getActiveNoteCount()).isEqualTo(1);

        // Advance to column 2 (end of note) — note-off should deactivate
        // At 120 BPM: 1 column = 0.125s = 125ms, column 2 = 250ms
        clockTimeMs[0] = 1250L;
        processor.advancePlayback();
        assertThat(processor.isNoteActive(60)).isFalse();
        assertThat(processor.getActiveNoteCount()).isZero();
    }

    @Test
    void stopPlaybackShouldCallRendererAllNotesOff() {
        processor.getClip().addNote(MidiNoteData.of(60, 0, 4, 100));
        processor.startPlayback(120.0);
        assertThat(processor.isPlaying()).isTrue();
        renderer.allNotesOffCalled = false;
        processor.stopPlayback();
        assertThat(renderer.allNotesOffCalled).isTrue();
    }

    // ── Clip ───────────────────────────────────────────────────────────

    @Test
    void shouldReturnClip() {
        assertThat(processor.getClip()).isNotNull();
        assertThat(processor.getClip().isEmpty()).isTrue();
    }

    @Test
    void shouldClearClip() {
        processor.getClip().addNote(MidiNoteData.of(60, 0, 4, 100));
        assertThat(processor.getClip().size()).isEqualTo(1);
        processor.clearClip();
        assertThat(processor.getClip().isEmpty()).isTrue();
    }

    // ── Listeners ──────────────────────────────────────────────────────

    @Test
    void shouldNotifyListenersOnNoteOn() {
        List<MidiEvent> events = new ArrayList<>();
        processor.addListener(events::add);
        processor.noteOn(60, 100);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo(MidiEvent.Type.NOTE_ON);
    }

    @Test
    void shouldNotifyListenersOnNoteOff() {
        List<MidiEvent> events = new ArrayList<>();
        processor.addListener(events::add);
        processor.noteOn(60, 100);
        processor.noteOff(60);
        assertThat(events).hasSize(2);
        assertThat(events.get(1).type()).isEqualTo(MidiEvent.Type.NOTE_OFF);
    }

    @Test
    void shouldRemoveListener() {
        List<MidiEvent> events = new ArrayList<>();
        KeyboardProcessor.KeyboardEventListener listener = events::add;
        processor.addListener(listener);
        processor.noteOn(60, 100);
        processor.removeListener(listener);
        processor.noteOn(64, 100);
        assertThat(events).hasSize(1); // Only first event
    }

    @Test
    void shouldRejectNullListener() {
        assertThatThrownBy(() -> processor.addListener(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Utility methods ────────────────────────────────────────────────

    @Test
    void midiNoteNumberShouldComputeCorrectly() {
        assertThat(KeyboardProcessor.midiNoteNumber(4, 0)).isEqualTo(60); // C4
        assertThat(KeyboardProcessor.midiNoteNumber(-1, 0)).isEqualTo(0); // C-1
        assertThat(KeyboardProcessor.midiNoteNumber(4, 9)).isEqualTo(69); // A4
    }

    @Test
    void midiNoteNumberShouldRejectInvalidIndex() {
        assertThatThrownBy(() -> KeyboardProcessor.midiNoteNumber(4, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KeyboardProcessor.midiNoteNumber(4, 12))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void midiNoteNumberShouldRejectOutOfRangeResult() {
        assertThatThrownBy(() -> KeyboardProcessor.midiNoteNumber(10, 8))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noteNameShouldReturnCorrectNames() {
        assertThat(KeyboardProcessor.noteName(60)).isEqualTo("C4");
        assertThat(KeyboardProcessor.noteName(69)).isEqualTo("A4");
        assertThat(KeyboardProcessor.noteName(0)).isEqualTo("C-1");
        assertThat(KeyboardProcessor.noteName(127)).isEqualTo("G9");
        assertThat(KeyboardProcessor.noteName(61)).isEqualTo("C#4");
    }

    @Test
    void noteNameShouldRejectInvalidNumber() {
        assertThatThrownBy(() -> KeyboardProcessor.noteName(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KeyboardProcessor.noteName(128))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isBlackKeyShouldIdentifyCorrectNotes() {
        assertThat(KeyboardProcessor.isBlackKey(0)).isFalse(); // C
        assertThat(KeyboardProcessor.isBlackKey(1)).isTrue();  // C#
        assertThat(KeyboardProcessor.isBlackKey(2)).isFalse(); // D
        assertThat(KeyboardProcessor.isBlackKey(3)).isTrue();  // D#
        assertThat(KeyboardProcessor.isBlackKey(4)).isFalse(); // E
        assertThat(KeyboardProcessor.isBlackKey(5)).isFalse(); // F
        assertThat(KeyboardProcessor.isBlackKey(6)).isTrue();  // F#
        assertThat(KeyboardProcessor.isBlackKey(7)).isFalse(); // G
        assertThat(KeyboardProcessor.isBlackKey(8)).isTrue();  // G#
        assertThat(KeyboardProcessor.isBlackKey(9)).isFalse(); // A
        assertThat(KeyboardProcessor.isBlackKey(10)).isTrue(); // A#
        assertThat(KeyboardProcessor.isBlackKey(11)).isFalse(); // B
    }

    @Test
    void timestampToColumnShouldConvertAt120Bpm() {
        // At 120 BPM: 1 beat = 0.5s, 1 column = 0.25 beats = 0.125s = 125ms
        processor.startRecording(120.0, 0);
        assertThat(processor.timestampToColumn(0)).isZero();
        assertThat(processor.timestampToColumn(125)).isEqualTo(1);
        assertThat(processor.timestampToColumn(250)).isEqualTo(2);
        assertThat(processor.timestampToColumn(500)).isEqualTo(4);
    }

    @Test
    void timestampToColumnShouldReturnZeroForNegative() {
        assertThat(processor.timestampToColumn(-100)).isZero();
    }

    // ── Constants ──────────────────────────────────────────────────────

    @Test
    void constantsShouldHaveExpectedValues() {
        assertThat(KeyboardProcessor.DEFAULT_CHANNEL).isZero();
        assertThat(KeyboardProcessor.NOTES_PER_OCTAVE).isEqualTo(12);
        assertThat(KeyboardProcessor.BEATS_PER_COLUMN).isEqualTo(0.25);
    }

    // ── Test Helpers ─────────────────────────────────────────────────────

    /**
     * Installs a fake clock on the processor and returns the mutable time holder.
     * Advancing the returned array element simulates the passage of time.
     *
     * @param initialTimeMs the initial clock time in milliseconds
     * @return a single-element array whose value is returned by the fake clock
     */
    private long[] useFakeClock(long initialTimeMs) {
        long[] clockTimeMs = {initialTimeMs};
        processor.setClock(() -> clockTimeMs[0]);
        return clockTimeMs;
    }

    // ── Stub Renderer ──────────────────────────────────────────────────

    /**
     * Minimal stub {@link SoundFontRenderer} that records events for assertion.
     */
    private static final class StubRenderer implements SoundFontRenderer {
        final List<MidiEvent> receivedEvents = new CopyOnWriteArrayList<>();

        record PresetSelection(int channel, int bank, int program) {}
        final List<PresetSelection> presetSelections = new CopyOnWriteArrayList<>();
        volatile boolean allNotesOffCalled;

        @Override public void initialize(double sampleRate, int bufferSize) {}
        @Override public SoundFontInfo loadSoundFont(Path path) { return new SoundFontInfo(0, path, List.of()); }
        @Override public void unloadSoundFont(int soundFontId) {}
        @Override public List<SoundFontInfo> getLoadedSoundFonts() { return Collections.emptyList(); }
        @Override public void selectPreset(int channel, int bank, int program) {
            presetSelections.add(new PresetSelection(channel, bank, program));
        }
        @Override public void sendEvent(MidiEvent event) { receivedEvents.add(event); }
        @Override public void render(float[][] outputBuffer, int numFrames) {}
        @Override public float[][] bounce(List<MidiEvent> events, int totalFrames) { return new float[2][totalFrames]; }
        @Override public void setReverbEnabled(boolean enabled) {}
        @Override public void setChorusEnabled(boolean enabled) {}
        @Override public void setGain(float gain) {}
        @Override public boolean isAvailable() { return true; }
        @Override public String getRendererName() { return "Stub"; }
        @Override public void allNotesOff() { allNotesOffCalled = true; }
        @Override public void close() {}
    }
}
