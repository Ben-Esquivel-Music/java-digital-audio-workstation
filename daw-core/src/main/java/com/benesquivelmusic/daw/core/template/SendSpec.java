package com.benesquivelmusic.daw.core.template;

import com.benesquivelmusic.daw.core.mixer.SendMode;

import java.util.Objects;

/**
 * A serializable specification for a send routing.
 *
 * <p>Sends are captured by target <em>name</em> (the return bus name) rather
 * than by object reference, so that templates remain portable across projects.
 * When the template is applied, the target name is looked up against the
 * project's mixer return buses. If no return bus with the matching name
 * exists, the send is skipped.</p>
 *
 * @param targetName the display name of the target return bus (must not be
 *                   {@code null} or blank)
 * @param level      the send level in {@code [0.0, 1.0]}
 * @param mode       the send mode (pre- or post-fader)
 */
public record SendSpec(String targetName, double level, SendMode mode) {

    public SendSpec {
        Objects.requireNonNull(targetName, "targetName must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        if (targetName.isBlank()) {
            throw new IllegalArgumentException("targetName must not be blank");
        }
        if (level < 0.0 || level > 1.0) {
            throw new IllegalArgumentException("level must be between 0.0 and 1.0: " + level);
        }
    }
}
