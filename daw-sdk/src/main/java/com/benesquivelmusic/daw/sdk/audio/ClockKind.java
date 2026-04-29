package com.benesquivelmusic.daw.sdk.audio;

/**
 * Sealed enumeration of hardware clock-source kinds that a multi-channel
 * audio interface may expose.
 *
 * <p>The DAW lets the user lock the interface to any one of these sources
 * via the Audio Settings dialog. Picking the wrong one produces audible
 * sample-slip clicks every few seconds (or, worse, silent corruption that
 * only surfaces on careful listening). The kind is independent of the
 * driver's integer clock-source id — it categorises sources so the UI can
 * label them ("INT", "EXT W/C", "SPDIF", "ADAT", "AES") and so persisted
 * settings remain meaningful across driver re-enumerations.</p>
 *
 * <p>Permitted implementations:</p>
 * <ul>
 *   <li>{@link Internal} — the interface's own crystal oscillator
 *       (the default and only safe choice when the DAW is the only
 *       digital device in the rig).</li>
 *   <li>{@link WordClock} — dedicated word-clock BNC input.
 *       Standard in studios that share a single house clock across
 *       multiple converters.</li>
 *   <li>{@link Spdif} — S/PDIF coaxial or optical input. Required
 *       when receiving from an outboard preamp with a digital out.</li>
 *   <li>{@link Adat} — ADAT lightpipe input. Required when expanding
 *       inputs through a lightpipe-connected preamp.</li>
 *   <li>{@link Aes} — AES/EBU XLR digital input.</li>
 *   <li>{@link External} — any other vendor-specific external source
 *       (Dante, MADI, …).</li>
 * </ul>
 */
public sealed interface ClockKind {

    /** Returns a short, all-caps label suitable for the transport-bar badge. */
    String shortLabel();

    /** The interface's own internal crystal oscillator. */
    record Internal() implements ClockKind {
        @Override public String shortLabel() { return "INT"; }
    }

    /** Word-clock BNC input. */
    record WordClock() implements ClockKind {
        @Override public String shortLabel() { return "W/C"; }
    }

    /** S/PDIF coaxial or optical digital input. */
    record Spdif() implements ClockKind {
        @Override public String shortLabel() { return "SPDIF"; }
    }

    /** ADAT lightpipe input. */
    record Adat() implements ClockKind {
        @Override public String shortLabel() { return "ADAT"; }
    }

    /** AES/EBU digital input. */
    record Aes() implements ClockKind {
        @Override public String shortLabel() { return "AES"; }
    }

    /** Any other vendor-specific external source (Dante, MADI, …). */
    record External() implements ClockKind {
        @Override public String shortLabel() { return "EXT"; }
    }
}
