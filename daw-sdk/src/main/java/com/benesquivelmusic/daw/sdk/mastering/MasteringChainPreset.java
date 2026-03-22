package com.benesquivelmusic.daw.sdk.mastering;

import java.util.List;
import java.util.Objects;

/**
 * An immutable, serializable mastering chain preset.
 *
 * <p>A preset captures the full configuration of a mastering chain:
 * a human-readable name, a genre tag, and an ordered list of stage
 * configurations with all processor parameters.</p>
 *
 * @param name   the preset name (e.g., "Pop Master v2")
 * @param genre  the genre tag (e.g., "Pop/EDM")
 * @param stages the ordered list of mastering stage configurations
 */
public record MasteringChainPreset(
        String name,
        String genre,
        List<MasteringStageConfig> stages
) {

    public MasteringChainPreset {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(genre, "genre must not be null");
        stages = List.copyOf(Objects.requireNonNull(stages, "stages must not be null"));
    }
}
