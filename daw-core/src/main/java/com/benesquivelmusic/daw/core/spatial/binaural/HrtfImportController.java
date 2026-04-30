package com.benesquivelmusic.daw.core.spatial.binaural;

import com.benesquivelmusic.daw.sdk.spatial.HrtfProfile;
import com.benesquivelmusic.daw.sdk.spatial.PersonalizedHrtfProfile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Headless coordinator for the "Import SOFA…" workflow that the binaural
 * monitoring UI exposes.
 *
 * <p>This class deliberately contains <em>no</em> JavaFX dependencies so the
 * import pipeline — file picker → schema validation → coverage warnings →
 * sample-rate matching → persistence — can be exercised in pure-JVM unit
 * tests. The thin {@code HrtfProfileImportDialog} in {@code daw-app} merely
 * wraps this controller in a {@code Dialog} for visual feedback.</p>
 *
 * <p>The controller also encapsulates the per-project profile-resolution
 * policy described in the issue: when a project references an HRTF profile
 * that is not present on the loading machine, the active selection silently
 * falls back to the closest factory profile and a one-shot warning is
 * produced for the UI to surface.</p>
 */
public final class HrtfImportController {

    private final HrtfProfileLibrary library;
    private final double sessionSampleRate;

    /**
     * Creates a controller bound to the given library and session sample rate.
     *
     * @param library           profile library to read from / write to
     * @param sessionSampleRate session sample rate, in Hz; SOFA impulses are
     *                          resampled to this rate at import time
     */
    public HrtfImportController(HrtfProfileLibrary library, double sessionSampleRate) {
        this.library = Objects.requireNonNull(library, "library must not be null");
        if (sessionSampleRate <= 0) {
            throw new IllegalArgumentException(
                    "sessionSampleRate must be positive: " + sessionSampleRate);
        }
        this.sessionSampleRate = sessionSampleRate;
    }

    /** Returns the underlying profile library. */
    public HrtfProfileLibrary library() {
        return library;
    }

    /** Returns the session sample rate this controller is bound to, in Hz. */
    public double sessionSampleRate() {
        return sessionSampleRate;
    }

    /**
     * Imports a SOFA file from disk: parses, validates, resamples to the
     * session rate when needed, then persists into {@link HrtfProfileLibrary}
     * so the new profile appears in the binaural monitoring chooser.
     *
     * @param sofaFile path to the {@code .sofa} file selected by the user
     * @return the import result (profile, original sample rate, warnings)
     * @throws IOException if the file cannot be read or fails SOFA validation;
     *                     the message is suitable for direct display in the
     *                     import dialog's error label
     */
    public SofaFileReader.ImportResult importSofaFile(Path sofaFile) throws IOException {
        Objects.requireNonNull(sofaFile, "sofaFile must not be null");
        return library.importSofa(sofaFile, sessionSampleRate);
    }

    /**
     * Resolves the profile referenced by a project (typically the value of
     * {@code DawProject.getActiveHrtfProfileName()}) into a usable selection.
     *
     * <p>If the reference matches a built-in {@link HrtfProfile} display name,
     * a {@link Resolution#hit} is returned for that factory profile.
     * If it matches an imported profile in the library, the personalized
     * profile is loaded. If it matches neither (the project came from another
     * machine that had the profile imported), the resolver falls back to the
     * "Medium" factory profile — the closest neutral baseline — and the
     * resulting {@link Resolution#fallbackWarning()} is non-empty so the UI
     * can show a one-shot notification.</p>
     *
     * @param requested the project's stored profile reference; may be {@code null}
     * @return a {@link Resolution} describing the chosen profile
     */
    public Resolution resolve(String requested) {
        if (requested == null || requested.isBlank()) {
            return new Resolution(HrtfProfile.MEDIUM, null, Optional.empty());
        }

        for (HrtfProfile p : HrtfProfile.values()) {
            if (p.displayName().equals(requested) || p.name().equals(requested)) {
                return new Resolution(p, null, Optional.empty());
            }
        }

        try {
            Optional<PersonalizedHrtfProfile> imported = library.loadImportedProfile(requested);
            if (imported.isPresent()) {
                return new Resolution(null, imported.get(), Optional.empty());
            }
        } catch (IOException ignored) {
            // Treat read errors the same as a missing profile and fall back.
        }

        // Fall back: pick the first generic profile whose display name is
        // closest in length to the missing reference; "Medium" is the default
        // when nothing else applies.
        HrtfProfile fallback = pickClosestFactoryProfile(requested);
        String warning = "HRTF profile \"" + requested
                + "\" is not available on this machine; falling back to \""
                + fallback.displayName() + "\". Re-import the SOFA file to restore "
                + "the personalized profile.";
        return new Resolution(fallback, null, Optional.of(warning));
    }

    private static HrtfProfile pickClosestFactoryProfile(String requested) {
        HrtfProfile best = HrtfProfile.MEDIUM;
        int bestDelta = Math.abs(best.displayName().length() - requested.length());
        for (HrtfProfile p : HrtfProfile.values()) {
            int delta = Math.abs(p.displayName().length() - requested.length());
            if (delta < bestDelta) {
                best = p;
                bestDelta = delta;
            }
        }
        return best;
    }

    /**
     * Convenience: returns the union of factory + imported chooser entries
     * exposed by the underlying library, suitable for direct use in a combo
     * box.
     */
    public List<HrtfProfileLibrary.ProfileEntry> chooserEntries() {
        return library.chooserEntries();
    }

    /**
     * Resolution returned by {@link #resolve(String)}. Exactly one of
     * {@code generic} or {@code personalized} is non-null.
     *
     * @param generic         the resolved built-in profile, or {@code null} when
     *                        the resolution chose a personalized profile
     * @param personalized    the resolved personalized profile, or {@code null}
     *                        when the resolution chose a built-in profile
     * @param fallbackWarning a human-readable notification to show the user
     *                        when the requested reference was missing on the
     *                        current machine
     */
    public record Resolution(
            HrtfProfile generic,
            PersonalizedHrtfProfile personalized,
            Optional<String> fallbackWarning) {

        public Resolution {
            Objects.requireNonNull(fallbackWarning, "fallbackWarning must not be null");
            if ((generic == null) == (personalized == null)) {
                throw new IllegalArgumentException(
                        "exactly one of generic or personalized must be non-null");
            }
        }

        /** Returns {@code true} when the resolution surfaced a fallback warning. */
        public boolean hasFallbackWarning() {
            return fallbackWarning.isPresent();
        }

        /** Display name for UI selection, regardless of which kind was chosen. */
        public String displayName() {
            return generic != null ? generic.displayName() : personalized.name();
        }
    }
}
