package com.benesquivelmusic.daw.core.persistence.archive;

/**
 * Options governing the contents of a project archive produced by
 * {@link ProjectArchiver}.
 *
 * <p>Mirrors the choices a user may toggle in {@code ProjectArchiveDialog}:
 * whether to include impulse responses, whether to include unused takes,
 * and similar inclusion controls.</p>
 *
 * @param includeImpulseResponses whether to include convolution impulse-response
 *                                files referenced by the project (when present)
 * @param includeUnusedTakes      whether to include audio assets that are not
 *                                referenced by any active clip but still live
 *                                in the project's takes folder
 * @param includeSoundFonts       whether to include SoundFont (.sf2) files
 *                                referenced by MIDI tracks
 */
public record ArchiveOptions(
        boolean includeImpulseResponses,
        boolean includeUnusedTakes,
        boolean includeSoundFonts
) {

    /**
     * Default options: include every referenced asset (impulse responses and
     * SoundFonts) but skip unused takes.
     */
    public static ArchiveOptions defaults() {
        return new ArchiveOptions(true, false, true);
    }
}
