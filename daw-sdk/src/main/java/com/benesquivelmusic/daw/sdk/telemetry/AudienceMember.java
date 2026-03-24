package com.benesquivelmusic.daw.sdk.telemetry;

import java.util.Objects;

/**
 * Immutable description of an audience member in a recording room.
 *
 * <p>Represents a person present in the recording space who is not a
 * performer or engineer — for example, a concert-goer in a live recording,
 * a congregation member in a church service, or a student in a lecture
 * hall session. Audience members affect the room's acoustic absorption
 * and may be relevant for microphone placement decisions.</p>
 *
 * <p>Multiple audience members can share overlapping or adjacent positions
 * to model seated or standing crowds in a recording area.</p>
 *
 * @param name     a descriptive label for this audience member or group
 * @param position the 3D position of the audience member in the room
 */
public record AudienceMember(String name, Position3D position) {

    public AudienceMember {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(position, "position must not be null");
    }
}
