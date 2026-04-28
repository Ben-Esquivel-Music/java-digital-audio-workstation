package com.benesquivelmusic.daw.core.plugin.builtin.midi;

import com.benesquivelmusic.daw.core.plugin.BuiltInPlugin;
import com.benesquivelmusic.daw.core.plugin.BuiltInPluginCategory;
import com.benesquivelmusic.daw.core.plugin.MidiEffectPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Built-in MIDI arpeggiator plugin.
 *
 * <p>Converts a held chord (one or more concurrent note-on events) into a
 * rhythmic sequence of the chord's notes. Supports a small but expressive
 * vocabulary of patterns ({@link Pattern#UP}, {@link Pattern#DOWN},
 * {@link Pattern#UP_DOWN}, {@link Pattern#DOWN_UP},
 * {@link Pattern#RANDOM}, {@link Pattern#AS_PLAYED},
 * {@link Pattern#CHORD}), a configurable {@link Rate step rate} from
 * 1/4 down to 1/32T, an octave range of 1–4, a gate time as a percent of
 * the step length, swing for the off-beat, and a latch toggle that keeps
 * the chord playing after the user releases all keys.</p>
 *
 * <h2>Realtime model</h2>
 * <p>The plugin does <b>not</b> own a thread. The host calls
 * {@link #process(MidiMessage[], int, MidiProcessContext)} once per audio
 * block and the implementation walks the block by sample offset, emitting
 * any step boundaries (and their note-off counterparts at {@code gate}
 * fraction of the step length) that fall inside the block. Held notes
 * that arrive in the input mutate the chord pool; output is a brand-new
 * array each call.</p>
 *
 * <p>This is a deliberately self-contained, headless implementation —
 * suitable for unit testing without any audio hardware.</p>
 */
@BuiltInPlugin(label = "Arpeggiator", icon = "arpeggiator", category = BuiltInPluginCategory.MIDI_EFFECT)
public final class ArpeggiatorPlugin implements MidiEffectPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.builtin.arpeggiator";

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Arpeggiator",
            "1.0.0",
            "DAW Built-in",
            PluginType.MIDI_EFFECT
    );

    /** Step note value, expressed as a fraction of a whole note. */
    public enum Rate {
        QUARTER(0.25, "1/4"),
        EIGHTH(0.125, "1/8"),
        SIXTEENTH(0.0625, "1/16"),
        THIRTYSECOND(0.03125, "1/32"),
        SIXTEENTH_TRIPLET(1.0 / 24.0, "1/16T"),
        THIRTYSECOND_TRIPLET(1.0 / 48.0, "1/32T"),
        SIXTEENTH_DOTTED(0.09375, "1/16D");

        /** Length of one step in whole notes (e.g. SIXTEENTH = 0.0625). */
        private final double whole;
        private final String label;

        Rate(double whole, String label) {
            this.whole = whole;
            this.label = label;
        }

        /** Returns the step length in beats (4 beats per whole note). */
        public double beats() {
            return whole * 4.0;
        }

        /** Human-readable label such as {@code "1/16"}. */
        public String label() {
            return label;
        }
    }

    /** Pattern that determines the order in which chord notes are played. */
    public enum Pattern {
        /** Ascending. */
        UP,
        /** Descending. */
        DOWN,
        /** Up then down (highest/lowest played once). */
        UP_DOWN,
        /** Down then up. */
        DOWN_UP,
        /** Pseudo-random selection across the chord. */
        RANDOM,
        /** In the order the keys were pressed. */
        AS_PLAYED,
        /** All notes simultaneously on each step. */
        CHORD
    }

    /** Minimum / maximum / default values for parameters. */
    public static final int MIN_OCTAVE = 1;
    public static final int MAX_OCTAVE = 4;
    public static final double MIN_GATE = 10.0;
    public static final double MAX_GATE = 200.0;
    public static final double MIN_SWING = 0.0;
    public static final double MAX_SWING = 75.0;

    // ── Parameters ────────────────────────────────────────────────────────────

    private Rate rate = Rate.SIXTEENTH;
    private Pattern pattern = Pattern.UP;
    private int octaveRange = 1;
    private double gate = 50.0;       // percent
    private double swing = 0.0;       // percent
    private boolean latch = false;

    // ── State ─────────────────────────────────────────────────────────────────

    /** The currently-held chord (note-as-played order). 0..127, no duplicates. */
    private final List<Integer> heldNotes = new ArrayList<>();
    /** Latched chord — replaces {@link #heldNotes} when latch is on and all keys are released. */
    private final List<Integer> latchedNotes = new ArrayList<>();
    /** Step counter for the running pattern; advanced once per emitted step. */
    private int stepIndex;
    /** Sample-frame within the song at which the next step fires. */
    private double nextStepSample = -1.0;
    /** PRNG for {@link Pattern#RANDOM}. */
    private final java.util.Random rng = new java.util.Random(0xA12B0L);
    /** Active output notes — used to emit pending note-offs at the gate boundary. */
    private final List<PendingOff> pending = new ArrayList<>();

    private record PendingOff(int channel, int note, int sampleOffset) {}

    private boolean active;

    public ArpeggiatorPlugin() {
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
    }

    @Override
    public void activate() {
        active = true;
    }

    @Override
    public void deactivate() {
        active = false;
        reset();
    }

    @Override
    public void dispose() {
        active = false;
        reset();
    }

    /** Clears all held / latched / pending state — used on stop or transport seek. */
    public void reset() {
        heldNotes.clear();
        latchedNotes.clear();
        pending.clear();
        stepIndex = 0;
        nextStepSample = -1.0;
    }

    @Override
    public List<PluginParameter> getParameters() {
        // ids: 0 rate (Rate ordinal), 1 pattern (Pattern ordinal), 2 octave, 3 gate, 4 swing, 5 latch
        return List.of(
                new PluginParameter(0, "Rate",        0.0, Rate.values().length - 1.0,    Rate.SIXTEENTH.ordinal()),
                new PluginParameter(1, "Pattern",     0.0, Pattern.values().length - 1.0, Pattern.UP.ordinal()),
                new PluginParameter(2, "Octave Range", MIN_OCTAVE, MAX_OCTAVE, 1.0),
                new PluginParameter(3, "Gate (%)",    MIN_GATE, MAX_GATE, 50.0),
                new PluginParameter(4, "Swing (%)",   MIN_SWING, MAX_SWING, 0.0),
                new PluginParameter(5, "Latch",       0.0, 1.0, 0.0)
        );
    }

    // ── Parameter getters / setters (target of the reflective parameter binder) ──

    public Rate getRate() { return rate; }
    public void setRate(Rate rate) { this.rate = Objects.requireNonNull(rate); }

    public Pattern getPattern() { return pattern; }
    public void setPattern(Pattern pattern) { this.pattern = Objects.requireNonNull(pattern); }

    public int getOctaveRange() { return octaveRange; }
    public void setOctaveRange(int octaveRange) {
        if (octaveRange < MIN_OCTAVE || octaveRange > MAX_OCTAVE) {
            throw new IllegalArgumentException(
                    "octaveRange must be %d–%d: %d".formatted(MIN_OCTAVE, MAX_OCTAVE, octaveRange));
        }
        this.octaveRange = octaveRange;
    }

    public double getGate() { return gate; }
    public void setGate(double gate) {
        if (gate < MIN_GATE || gate > MAX_GATE) {
            throw new IllegalArgumentException("gate must be %.0f–%.0f%%: %f".formatted(MIN_GATE, MAX_GATE, gate));
        }
        this.gate = gate;
    }

    public double getSwing() { return swing; }
    public void setSwing(double swing) {
        if (swing < MIN_SWING || swing > MAX_SWING) {
            throw new IllegalArgumentException("swing must be %.0f–%.0f%%: %f".formatted(MIN_SWING, MAX_SWING, swing));
        }
        this.swing = swing;
    }

    public boolean isLatch() { return latch; }
    public void setLatch(boolean latch) {
        this.latch = latch;
        if (!latch) {
            latchedNotes.clear();
        }
    }

    /** Returns the current step index — used by the UI to drive the step indicator. */
    public int getStepIndex() {
        return stepIndex;
    }

    // ── Core processing ───────────────────────────────────────────────────────

    @Override
    public MidiMessage[] process(MidiMessage[] in, int sampleOffset, MidiProcessContext ctx) {
        Objects.requireNonNull(in, "in must not be null");
        Objects.requireNonNull(ctx, "ctx must not be null");

        // Fast path: pass-through when CHORD pattern is selected — the plugin
        // becomes a transparent forwarder that still consumes note-on/-off
        // events into its held state in case the pattern changes mid-block.
        if (pattern == Pattern.CHORD) {
            for (MidiMessage msg : in) {
                updateHeldFromInput(msg);
            }
            return in;
        }

        // Filter input note-on / note-off events: we consume them into the
        // chord pool but do NOT forward the raw events — only arpeggiated
        // output reaches the instrument. Other event types (CC, PB, PC) are
        // forwarded unchanged so things like sustain pedal still work.
        List<MidiMessage> out = new ArrayList<>(in.length + 8);
        for (MidiMessage msg : in) {
            if (msg.type() == MidiMessage.Type.NOTE_ON || msg.type() == MidiMessage.Type.NOTE_OFF) {
                updateHeldFromInput(msg);
            } else {
                out.add(msg);
            }
        }

        emitSteps(out, sampleOffset, ctx);

        return out.toArray(new MidiMessage[0]);
    }

    /** Updates {@link #heldNotes} and {@link #latchedNotes} based on an incoming note event. */
    private void updateHeldFromInput(MidiMessage msg) {
        int note = msg.data1();
        if (msg.type() == MidiMessage.Type.NOTE_ON && msg.data2() > 0) {
            // First key after release re-arms the latch buffer
            if (latch && heldNotes.isEmpty()) {
                latchedNotes.clear();
            }
            if (!heldNotes.contains(note)) {
                heldNotes.add(note);
            }
            if (latch && !latchedNotes.contains(note)) {
                latchedNotes.add(note);
            }
        } else { // NOTE_OFF or NOTE_ON with vel 0
            heldNotes.remove(Integer.valueOf(note));
        }
    }

    /** The chord we are arpeggiating right now — held notes, or latched notes if latch is on. */
    private List<Integer> activeChord() {
        if (latch && heldNotes.isEmpty() && !latchedNotes.isEmpty()) {
            return latchedNotes;
        }
        return heldNotes;
    }

    /** Walks the block emitting any step boundaries that fall inside it. */
    private void emitSteps(List<MidiMessage> out, int sampleOffset, MidiProcessContext ctx) {
        int blockSize = ctx.blockSize();
        double samplesPerBeat = ctx.samplesPerBeat();
        double stepSamples = rate.beats() * samplesPerBeat;
        int blockStartSample = sampleOffset;
        int blockEndSample = sampleOffset + blockSize;

        // First call after reset → align next step to the start of this block
        if (nextStepSample < 0.0) {
            nextStepSample = blockStartSample;
        }

        // Emit any pending note-offs that fall inside this block
        var it = pending.iterator();
        while (it.hasNext()) {
            PendingOff p = it.next();
            int relOffset = p.sampleOffset - blockStartSample;
            if (relOffset < blockSize) {
                int clamped = Math.max(0, relOffset);
                out.add(MidiMessage.noteOff(p.channel, p.note, clamped));
                it.remove();
            }
        }

        // Walk the block and fire any step boundaries that lie inside it
        while (nextStepSample < blockEndSample) {
            int stepFrame = (int) Math.round(nextStepSample - blockStartSample);
            if (stepFrame < 0) stepFrame = 0;
            if (stepFrame >= blockSize) break;

            List<Integer> chord = activeChord();
            if (!chord.isEmpty()) {
                fireStep(out, chord, stepFrame, blockStartSample, stepSamples);
            }

            stepIndex++;
            // Advance with swing: even steps land on the grid; odd steps are
            // pushed forward by (swing/100) * (stepSamples / 2).
            double nextDelta = stepSamples;
            if (swing > 0.0 && (stepIndex % 2) == 1) {
                nextDelta = stepSamples + (swing / 100.0) * (stepSamples * 0.5);
            } else if (swing > 0.0 && (stepIndex % 2) == 0) {
                nextDelta = stepSamples - (swing / 100.0) * (stepSamples * 0.5);
            }
            nextStepSample += nextDelta;
        }
    }

    /** Fires one step: emit one or more note-ons and queue matching note-offs. */
    private void fireStep(List<MidiMessage> out, List<Integer> chord,
                          int stepFrame, int blockStartSample, double stepSamples) {
        int[] notes = expandWithOctaves(orderedChord(chord));
        if (notes.length == 0) return;

        int channel = 0;
        int gateSamples = (int) Math.max(1.0, Math.round(stepSamples * (gate / 100.0)));
        int offSongSample = blockStartSample + stepFrame + gateSamples;

        if (pattern == Pattern.CHORD) {
            for (int n : notes) {
                out.add(MidiMessage.noteOn(channel, n, 100, stepFrame));
                pending.add(new PendingOff(channel, n, offSongSample));
            }
            return;
        }

        int idx = patternIndex(stepIndex, notes.length);
        int note = notes[idx];
        out.add(MidiMessage.noteOn(channel, note, 100, stepFrame));
        pending.add(new PendingOff(channel, note, offSongSample));
    }

    /** Returns the chord notes ordered according to the AS_PLAYED / sorted order. */
    private List<Integer> orderedChord(List<Integer> chord) {
        if (pattern == Pattern.AS_PLAYED) {
            return chord;
        }
        List<Integer> sorted = new ArrayList<>(chord);
        sorted.sort(Integer::compare);
        return sorted;
    }

    /** Returns the chord expanded across {@link #octaveRange} octaves (ascending). */
    private int[] expandWithOctaves(List<Integer> base) {
        int[] out = new int[base.size() * octaveRange];
        int k = 0;
        for (int o = 0; o < octaveRange; o++) {
            for (Integer n : base) {
                int shifted = n + 12 * o;
                if (shifted < 0 || shifted > 127) continue;
                out[k++] = shifted;
            }
        }
        return Arrays.copyOf(out, k);
    }

    /** Maps a 0-based step counter to a chord index based on the active pattern. */
    private int patternIndex(int step, int n) {
        if (n <= 0) return 0;
        return switch (pattern) {
            case UP, AS_PLAYED, CHORD -> Math.floorMod(step, n);
            case DOWN -> n - 1 - Math.floorMod(step, n);
            case UP_DOWN -> {
                int period = Math.max(1, 2 * n - 2);
                int p = Math.floorMod(step, period);
                yield (p < n) ? p : period - p;
            }
            case DOWN_UP -> {
                int period = Math.max(1, 2 * n - 2);
                int p = Math.floorMod(step, period);
                int idxUp = (p < n) ? p : period - p;
                yield n - 1 - idxUp;
            }
            case RANDOM -> rng.nextInt(n);
        };
    }

    /** Whether the plugin has been activated. */
    public boolean isActive() {
        return active;
    }
}
