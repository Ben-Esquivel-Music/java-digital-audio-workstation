package com.benesquivelmusic.daw.sdk.mastering;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable configuration for a single stage in a mastering chain.
 *
 * <p>Each stage has a type, a human-readable name, a set of processor
 * parameters (stored as name-value pairs), and a bypass flag.</p>
 *
 * @param stageType  the type of mastering stage
 * @param name       a human-readable name for this stage
 * @param parameters processor parameter values keyed by name
 * @param bypassed   whether this stage is bypassed
 */
public record MasteringStageConfig(
        MasteringStageType stageType,
        String name,
        Map<String, Double> parameters,
        boolean bypassed
) {

    public MasteringStageConfig {
        Objects.requireNonNull(stageType, "stageType must not be null");
        Objects.requireNonNull(name, "name must not be null");
        parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters must not be null"));
    }

    /**
     * Creates an enabled stage configuration.
     *
     * @param stageType  the stage type
     * @param name       the display name
     * @param parameters the processor parameters
     * @return a new stage configuration
     */
    public static MasteringStageConfig of(MasteringStageType stageType, String name,
                                           Map<String, Double> parameters) {
        return new MasteringStageConfig(stageType, name, parameters, false);
    }
}
