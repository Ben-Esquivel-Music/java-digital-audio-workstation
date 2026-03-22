package com.benesquivelmusic.daw.sdk.midi;

import java.nio.file.Path;
import java.util.List;

/**
 * Metadata about a loaded SoundFont file.
 *
 * @param id      the SoundFont identifier assigned by the renderer
 * @param path    the file-system path to the SF2 file
 * @param presets the list of presets (instruments) available in the SoundFont
 */
public record SoundFontInfo(int id, Path path, List<SoundFontPreset> presets) {

    public SoundFontInfo {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        presets = presets == null ? List.of() : List.copyOf(presets);
    }
}
