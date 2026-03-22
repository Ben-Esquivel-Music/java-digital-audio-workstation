package com.benesquivelmusic.daw.core.spatial.objectbased;

import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Validates Dolby Atmos session constraints.
 *
 * <p>Enforces the Atmos production limits:</p>
 * <ul>
 *   <li>Maximum 128 simultaneous tracks (7.1.4 bed + 118 objects)</li>
 *   <li>Bed channels must be assigned to valid speakers in the layout</li>
 *   <li>Maximum one bed channel per speaker position</li>
 * </ul>
 */
public final class AtmosSessionValidator {

    /** Maximum number of audio objects in a Dolby Atmos session. */
    public static final int MAX_OBJECTS = 118;

    /** Maximum total tracks (bed channels + objects). */
    public static final int MAX_TOTAL_TRACKS = 128;

    private AtmosSessionValidator() {
        // utility class
    }

    /**
     * Validates that the session configuration conforms to Dolby Atmos constraints.
     *
     * @param bedChannels  the bed channel assignments
     * @param audioObjects the audio objects
     * @param layout       the speaker layout for bed validation
     * @return a list of validation error messages (empty if valid)
     */
    public static List<String> validate(List<BedChannel> bedChannels,
                                        List<AudioObject> audioObjects,
                                        SpeakerLayout layout) {
        Objects.requireNonNull(bedChannels, "bedChannels must not be null");
        Objects.requireNonNull(audioObjects, "audioObjects must not be null");
        Objects.requireNonNull(layout, "layout must not be null");

        var errors = new ArrayList<String>();

        int totalTracks = bedChannels.size() + audioObjects.size();
        if (totalTracks > MAX_TOTAL_TRACKS) {
            errors.add("Total track count %d exceeds Atmos limit of %d"
                    .formatted(totalTracks, MAX_TOTAL_TRACKS));
        }

        if (audioObjects.size() > MAX_OBJECTS) {
            errors.add("Audio object count %d exceeds Atmos limit of %d"
                    .formatted(audioObjects.size(), MAX_OBJECTS));
        }

        // Validate bed channels are assigned to speakers present in the layout
        var assignedSpeakers = new java.util.HashSet<com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel>();
        for (var bed : bedChannels) {
            if (!layout.contains(bed.speakerLabel())) {
                errors.add("Bed channel '%s' is assigned to speaker %s which is not in layout '%s'"
                        .formatted(bed.trackId(), bed.speakerLabel(), layout.name()));
            }
            if (!assignedSpeakers.add(bed.speakerLabel())) {
                errors.add("Duplicate bed channel assignment to speaker %s"
                        .formatted(bed.speakerLabel()));
            }
        }

        return Collections.unmodifiableList(errors);
    }
}
