package com.benesquivelmusic.daw.sdk.export;

/**
 * Naming conventions for stem export files.
 *
 * <p>Controls how the output filename is constructed for each exported
 * stem. The convention is applied by the stem exporter to generate a
 * unique, descriptive filename for each track or group stem.</p>
 */
public enum StemNamingConvention {

    /**
     * Use the track name as the filename.
     *
     * <p>Example: {@code "Vocals.wav"}, {@code "Bass.wav"}</p>
     */
    TRACK_NAME,

    /**
     * Prefix each filename with the project name.
     *
     * <p>Example: {@code "MyProject_Vocals.wav"}, {@code "MyProject_Bass.wav"}</p>
     */
    PROJECT_PREFIX,

    /**
     * Number each file sequentially with the track name.
     *
     * <p>Example: {@code "01_Vocals.wav"}, {@code "02_Bass.wav"}</p>
     */
    NUMBERED
}
