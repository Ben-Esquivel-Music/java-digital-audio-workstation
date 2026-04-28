package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.plugin.builtin.midi.MidiMessage;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.MidiProcessContext;

/**
 * MIDI-effect variant of the built-in plugin sealed hierarchy.
 *
 * <p>A MIDI effect sits before a sound source in the plugin chain and
 * transforms incoming MIDI: an arpeggiator, a chord generator, a velocity
 * randomizer, etc. It accepts a batch of {@link MidiMessage} events and
 * returns a (possibly different) batch — the result is fed either to the
 * next MIDI effect in the chain or to the instrument that consumes the
 * track's MIDI.</p>
 *
 * <p>This interface is {@code non-sealed} to allow an open set of MIDI
 * effects (each declared in {@link BuiltInDawPlugin}'s {@code permits}
 * clause) without requiring every concrete effect to be re-listed here.</p>
 *
 * @see BuiltInDawPlugin
 * @see com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin
 */
public non-sealed interface MidiEffectPlugin extends BuiltInDawPlugin {

    /**
     * Processes a block of MIDI input and returns the events to forward
     * to the next stage of the chain.
     *
     * <p>Implementations may return:</p>
     * <ul>
     *   <li>The {@code in} array unchanged (pass-through).</li>
     *   <li>A new array with fewer events (filtering).</li>
     *   <li>A new array with more events (expansion — e.g. an arpeggiator
     *       turning one note-on into several).</li>
     * </ul>
     *
     * <p>Output events must carry a {@code sampleOffset} in the closed
     * range {@code [0, ctx.blockSize() - 1]} so the host can schedule
     * them within the current audio block.</p>
     *
     * @param in           the input MIDI batch (never {@code null})
     * @param sampleOffset the global sample offset of frame 0 of this block
     * @param ctx          per-block timing context
     * @return the output MIDI batch (never {@code null})
     */
    MidiMessage[] process(MidiMessage[] in, int sampleOffset, MidiProcessContext ctx);
}
