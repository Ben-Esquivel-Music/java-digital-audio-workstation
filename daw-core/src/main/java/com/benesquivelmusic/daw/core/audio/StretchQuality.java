package com.benesquivelmusic.daw.core.audio;

/**
 * Quality setting for audio time-stretching and pitch-shifting algorithms.
 *
 * <p>Higher quality settings produce better-sounding results but require
 * more CPU. The quality level controls internal parameters such as FFT
 * size and overlap factor in the phase-vocoder implementation.</p>
 */
public enum StretchQuality {

    /** Fastest processing; suitable for quick previews. */
    LOW,

    /** Balanced quality and performance; good for general use. */
    MEDIUM,

    /** Highest quality; best for final renders and critical listening. */
    HIGH
}
