package com.benesquivelmusic.daw.core.plugin.builtin.midi;

import com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin.Pattern;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin.Rate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavioural tests for the built-in {@link ArpeggiatorPlugin} — verifies
 * the three guarantees called out by the issue:
 *
 * <ol>
 *   <li>A 4-note chord at 1/16 produces the right number of notes per bar
 *       at the right grid positions.</li>
 *   <li>The {@link Pattern#UP UP} pattern walks the chord in ascending pitch order.</li>
 *   <li>{@code gate=50%} produces a note-off at exactly half the step length.</li>
 * </ol>
 */
class ArpeggiatorPluginTest {

    /** 120 BPM at 48 kHz → 24 000 samples per beat. Choose a tidy block size. */
    private static final double SAMPLE_RATE = 48_000.0;
    private static final double BPM = 120.0;
    private static final double SAMPLES_PER_BEAT = SAMPLE_RATE * 60.0 / BPM;

    /**
     * Drives the plugin in fixed-size blocks for {@code totalFrames} frames,
     * collecting output messages annotated with their absolute song-frame
     * position so tests can assert on global timing.
     */
    private static List<TimedMessage> drive(ArpeggiatorPlugin plugin,
                                            MidiMessage[] firstBlockInput,
                                            int blockSize, int totalFrames) {
        List<TimedMessage> all = new ArrayList<>();
        int sample = 0;
        boolean first = true;
        while (sample < totalFrames) {
            int len = Math.min(blockSize, totalFrames - sample);
            MidiMessage[] in = first ? firstBlockInput : new MidiMessage[0];
            first = false;
            double startBeat = sample / SAMPLES_PER_BEAT;
            MidiProcessContext ctx = new MidiProcessContext(SAMPLE_RATE, len, startBeat, SAMPLES_PER_BEAT);
            MidiMessage[] out = plugin.process(in, sample, ctx);
            for (MidiMessage m : out) {
                all.add(new TimedMessage(sample + m.sampleOffset(), m));
            }
            sample += len;
        }
        return all;
    }

    private record TimedMessage(int songFrame, MidiMessage msg) {}

    // ── Goal 1: 4 notes per bar at 1/16, on the grid ─────────────────────────

    @Test
    void chordAtSixteenthRateProducesNotesPerBarAtCorrectPositions() {
        ArpeggiatorPlugin arp = new ArpeggiatorPlugin();
        arp.setRate(Rate.SIXTEENTH);
        arp.setPattern(Pattern.UP);
        arp.setOctaveRange(1);
        arp.setGate(50.0);

        // 4-note chord on the very first frame (block 0) — C4 E4 G4 Bb4
        MidiMessage[] input = {
                MidiMessage.noteOn(0, 60, 100, 0),
                MidiMessage.noteOn(0, 64, 100, 0),
                MidiMessage.noteOn(0, 67, 100, 0),
                MidiMessage.noteOn(0, 70, 100, 0),
        };

        // One bar at 4 beats = 4 * SAMPLES_PER_BEAT frames
        int barFrames = (int) (4 * SAMPLES_PER_BEAT);
        // 1/16 step at 120 BPM @ 48k = 24000 * 0.25 = 6000 samples per step
        int stepFrames = (int) (Rate.SIXTEENTH.beats() * SAMPLES_PER_BEAT);
        assertThat(stepFrames).isEqualTo(6_000);

        List<TimedMessage> output = drive(arp, input, 512, barFrames);

        // Filter to NOTE_ONs only — there should be exactly 16 in one bar.
        List<TimedMessage> ons = output.stream()
                .filter(t -> t.msg().type() == MidiMessage.Type.NOTE_ON)
                .toList();
        assertThat(ons).as("note-ons in one bar at 1/16 with 4-note chord").hasSize(16);

        // Each note-on must land within ±1 frame of an exact step boundary.
        for (int i = 0; i < ons.size(); i++) {
            int expected = i * stepFrames;
            assertThat(ons.get(i).songFrame())
                    .as("note-on #%d position", i)
                    .isCloseTo(expected, org.assertj.core.data.Offset.offset(1));
        }
    }

    // ── Goal 2: UP pattern is ascending ──────────────────────────────────────

    @Test
    void upPatternEmitsChordNotesInAscendingPitchOrder() {
        ArpeggiatorPlugin arp = new ArpeggiatorPlugin();
        arp.setRate(Rate.SIXTEENTH);
        arp.setPattern(Pattern.UP);
        arp.setOctaveRange(1);
        arp.setGate(50.0);

        // Insert chord notes out-of-order on purpose to exercise sorting.
        MidiMessage[] input = {
                MidiMessage.noteOn(0, 67, 100, 0),
                MidiMessage.noteOn(0, 60, 100, 0),
                MidiMessage.noteOn(0, 64, 100, 0),
                MidiMessage.noteOn(0, 70, 100, 0),
        };

        int stepFrames = (int) (Rate.SIXTEENTH.beats() * SAMPLES_PER_BEAT);
        // Run for the first 4 steps only.
        List<TimedMessage> output = drive(arp, input, 512, 4 * stepFrames + 8);
        int[] pitches = output.stream()
                .filter(t -> t.msg().type() == MidiMessage.Type.NOTE_ON)
                .mapToInt(t -> t.msg().data1())
                .limit(4)
                .toArray();

        assertThat(pitches).containsExactly(60, 64, 67, 70);
    }

    // ── Goal 3: gate=50% produces note-off at half the step length ───────────

    @Test
    void gateAtFiftyPercentEmitsNoteOffAtHalfTheStepLength() {
        ArpeggiatorPlugin arp = new ArpeggiatorPlugin();
        arp.setRate(Rate.SIXTEENTH);
        arp.setPattern(Pattern.UP);
        arp.setOctaveRange(1);
        arp.setGate(50.0);

        MidiMessage[] input = { MidiMessage.noteOn(0, 60, 100, 0) };
        int stepFrames = (int) (Rate.SIXTEENTH.beats() * SAMPLES_PER_BEAT);
        // Render 2 steps and look at the first note's on/off pair.
        List<TimedMessage> out = drive(arp, input, 256, 2 * stepFrames);

        TimedMessage firstOn = out.stream()
                .filter(t -> t.msg().type() == MidiMessage.Type.NOTE_ON)
                .findFirst().orElseThrow();
        TimedMessage firstOff = out.stream()
                .filter(t -> t.msg().type() == MidiMessage.Type.NOTE_OFF
                        && t.msg().data1() == firstOn.msg().data1())
                .findFirst().orElseThrow();

        int distance = firstOff.songFrame() - firstOn.songFrame();
        // Gate=50 → off at stepFrames/2 (allowing ±1 frame for rounding)
        assertThat(distance)
                .as("note-off distance from note-on at gate=50%")
                .isCloseTo(stepFrames / 2, org.assertj.core.data.Offset.offset(1));
    }

    // ── Pattern coverage — ensure DOWN is the mirror of UP ───────────────────

    @Test
    void downPatternEmitsChordNotesInDescendingOrder() {
        ArpeggiatorPlugin arp = new ArpeggiatorPlugin();
        arp.setRate(Rate.SIXTEENTH);
        arp.setPattern(Pattern.DOWN);
        arp.setOctaveRange(1);

        MidiMessage[] input = {
                MidiMessage.noteOn(0, 60, 100, 0),
                MidiMessage.noteOn(0, 64, 100, 0),
                MidiMessage.noteOn(0, 67, 100, 0),
        };
        int stepFrames = (int) (Rate.SIXTEENTH.beats() * SAMPLES_PER_BEAT);
        List<TimedMessage> output = drive(arp, input, 512, 3 * stepFrames + 8);
        int[] pitches = output.stream()
                .filter(t -> t.msg().type() == MidiMessage.Type.NOTE_ON)
                .mapToInt(t -> t.msg().data1())
                .limit(3)
                .toArray();
        assertThat(pitches).containsExactly(67, 64, 60);
    }

    // ── CHORD pattern is pass-through and forwards events ───────────────────

    @Test
    void chordPatternForwardsInputUnchanged() {
        ArpeggiatorPlugin arp = new ArpeggiatorPlugin();
        arp.setPattern(Pattern.CHORD);
        MidiMessage[] input = {
                MidiMessage.noteOn(0, 60, 100, 0),
                MidiMessage.noteOn(0, 64, 100, 0),
        };
        MidiProcessContext ctx = new MidiProcessContext(SAMPLE_RATE, 256, 0.0, SAMPLES_PER_BEAT);
        MidiMessage[] out = arp.process(input, 0, ctx);
        assertThat(out).isSameAs(input);
    }

    // ── Latch keeps the chord playing after note-off ─────────────────────────

    @Test
    void latchKeepsChordPlayingAfterAllKeysReleased() {
        ArpeggiatorPlugin arp = new ArpeggiatorPlugin();
        arp.setRate(Rate.SIXTEENTH);
        arp.setLatch(true);
        arp.setPattern(Pattern.UP);

        // Press chord at frame 0, release all keys at the start of block 2.
        int blockSize = 512;
        MidiProcessContext ctx = new MidiProcessContext(SAMPLE_RATE, blockSize, 0.0, SAMPLES_PER_BEAT);

        MidiMessage[] press = {
                MidiMessage.noteOn(0, 60, 100, 0),
                MidiMessage.noteOn(0, 64, 100, 0),
        };
        // Run a few blocks with the chord pressed.
        arp.process(press, 0, ctx);
        // Release.
        MidiMessage[] release = {
                MidiMessage.noteOff(0, 60, 0),
                MidiMessage.noteOff(0, 64, 0),
        };
        MidiMessage[] afterRelease1 = arp.process(release, blockSize,
                new MidiProcessContext(SAMPLE_RATE, blockSize, blockSize / SAMPLES_PER_BEAT, SAMPLES_PER_BEAT));

        // Continue running blocks with no input — latch should keep firing notes.
        int totalNoteOns = countNoteOns(afterRelease1);
        for (int i = 2; i < 30; i++) {
            int sample = i * blockSize;
            MidiMessage[] none = new MidiMessage[0];
            MidiMessage[] o = arp.process(none, sample,
                    new MidiProcessContext(SAMPLE_RATE, blockSize, sample / SAMPLES_PER_BEAT, SAMPLES_PER_BEAT));
            totalNoteOns += countNoteOns(o);
        }
        assertThat(totalNoteOns).as("note-ons emitted after release with latch on").isGreaterThan(0);
    }

    private static int countNoteOns(MidiMessage[] out) {
        return (int) Arrays.stream(out).filter(m -> m.type() == MidiMessage.Type.NOTE_ON).count();
    }

    // ── Parameter validation ─────────────────────────────────────────────────

    @Test
    void parameterSettersValidateRanges() {
        ArpeggiatorPlugin arp = new ArpeggiatorPlugin();

        assertThatThrownBy(() -> arp.setOctaveRange(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> arp.setOctaveRange(5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> arp.setGate(5.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> arp.setGate(250.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> arp.setSwing(-1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> arp.setSwing(80.0)).isInstanceOf(IllegalArgumentException.class);

        // Boundary values are accepted.
        arp.setOctaveRange(1);
        arp.setOctaveRange(4);
        arp.setGate(10.0);
        arp.setGate(200.0);
        arp.setSwing(0.0);
        arp.setSwing(75.0);
    }
}
