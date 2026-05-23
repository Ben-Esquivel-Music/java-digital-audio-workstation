package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.track.Track;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure lookup helpers for {@link DawProject}.
 *
 * <p>Every consumer that needed to walk {@code project.getTracks()} to find
 * a track by UUID, an {@link InsertSlot} by {@code (trackId, insertIndex)},
 * or an {@link AudioClip} by id was previously hand-rolling the iteration.
 * This utility centralises those three lookups behind small static methods
 * so the UI controllers (story 281 Workshop wiring, inspector wiring) can
 * resolve a typed selection to a domain object without each one growing
 * its own copy of the same loop.</p>
 *
 * <p>The class is intentionally <strong>stateless</strong> — every method
 * is a {@code static} pure function returning {@link Optional}/-1 for the
 * not-found case. No project model is mutated; no caching is performed
 * here (callers cache at their own layer when appropriate, e.g. the
 * Workshop's per-{@code (trackId, insertIndex)} plugin-panel cache).</p>
 *
 * <p>Track IDs are stored as {@link String} on {@link Track#getId()} but
 * UI selection events ({@link com.benesquivelmusic.daw.core.mixer.MixerChannel}
 * strips, the inspector's {@code InsertSelection}) carry {@link UUID}.
 * The lookups below safely tolerate non-UUID track ids (test fixtures
 * occasionally forge a plain string) and simply skip them — they cannot
 * match a UUID selection anyway.</p>
 */
public final class ProjectLookups {

    private ProjectLookups() {
        // Static utility — no instances.
    }

    /**
     * Finds the {@link Track} in {@code project} whose {@link Track#getId()}
     * equals the string representation of the given UUID. Returns the first
     * match; track ids are unique within a project so there is at most one.
     *
     * @param project the project to scan (must not be {@code null})
     * @param trackId the track UUID to match (may be {@code null} → empty)
     * @return the matching track, or {@link Optional#empty()} if no track
     *         exists or {@code trackId} is {@code null}
     */
    public static Optional<Track> findTrack(DawProject project, UUID trackId) {
        Objects.requireNonNull(project, "project must not be null");
        if (trackId == null) {
            return Optional.empty();
        }
        String target = trackId.toString();
        for (Track t : project.getTracks()) {
            if (target.equals(t.getId())) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the 0-based index of the track with the given UUID in
     * {@link DawProject#getTracks()}, or {@code -1} when no such track
     * exists. The 1-based display index used by the §4 Concept F
     * breadcrumb ({@code Track 03}) is {@code findTrackIndex(...) + 1}.
     *
     * @param project the project to scan (must not be {@code null})
     * @param trackId the track UUID to match (may be {@code null} → -1)
     * @return the 0-based index, or {@code -1}
     */
    public static int findTrackIndex(DawProject project, UUID trackId) {
        Objects.requireNonNull(project, "project must not be null");
        if (trackId == null) {
            return -1;
        }
        String target = trackId.toString();
        List<Track> tracks = project.getTracks();
        for (int i = 0; i < tracks.size(); i++) {
            if (target.equals(tracks.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Resolves the {@link InsertSlot} at {@code (trackId, insertIndex)} —
     * the lookup the unified inspector's {@code InsertSelection} feeds the
     * Workshop's right-pane plugin focus (story 281).
     *
     * <p>Returns {@link Optional#empty()} when any of these conditions
     * hold:</p>
     * <ul>
     *   <li>{@code trackId} is {@code null} or does not match any track in
     *       the project,</li>
     *   <li>the matched track has no associated {@link MixerChannel} yet
     *       (the channel is provisioned the first time the track is added
     *       — defensive),</li>
     *   <li>{@code insertIndex} is negative or beyond the channel's current
     *       insert count.</li>
     * </ul>
     *
     * @param project     the project to scan (must not be {@code null})
     * @param trackId     the track UUID identifying the mixer channel
     * @param insertIndex the 0-based insert slot index on that channel
     * @return the resolved insert slot, or empty when not addressable
     */
    public static Optional<InsertSlot> findInsertSlot(DawProject project,
                                                       UUID trackId,
                                                       int insertIndex) {
        Objects.requireNonNull(project, "project must not be null");
        if (insertIndex < 0) {
            return Optional.empty();
        }
        Optional<Track> track = findTrack(project, trackId);
        if (track.isEmpty()) {
            return Optional.empty();
        }
        MixerChannel channel = project.getMixerChannelForTrack(track.get());
        if (channel == null) {
            return Optional.empty();
        }
        if (insertIndex >= channel.getInsertCount()) {
            return Optional.empty();
        }
        return Optional.of(channel.getInsertSlot(insertIndex));
    }

    /**
     * Resolves the {@link AudioClip} whose {@link AudioClip#getId()} equals
     * the string representation of the given UUID. Walks every track's clip
     * list once; clip ids are unique within a project so the first match is
     * the answer.
     *
     * <p>Note: {@link com.benesquivelmusic.daw.core.midi.MidiClip} does not
     * carry an id today; this method therefore resolves AUDIO clips only.
     * The Workshop clip-detail factory accepts a {@link
     * com.benesquivelmusic.daw.core.clip.Clip} directly (including MIDI
     * clips), so callers that already hold the {@code Clip} should skip
     * this lookup and pass it through.</p>
     *
     * @param project the project to scan (must not be {@code null})
     * @param clipId  the clip UUID to match (may be {@code null} → empty)
     * @return the matching audio clip, or {@link Optional#empty()}
     */
    public static Optional<AudioClip> findAudioClip(DawProject project, UUID clipId) {
        Objects.requireNonNull(project, "project must not be null");
        if (clipId == null) {
            return Optional.empty();
        }
        String target = clipId.toString();
        for (Track t : project.getTracks()) {
            for (AudioClip c : t.getClips()) {
                if (target.equals(c.getId())) {
                    return Optional.of(c);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Finds the {@link Track} that owns the given {@link AudioClip}, by
     * reference. Returns {@link Optional#empty()} when the clip is not
     * attached to any track in the project (e.g. it has been removed).
     *
     * @param project the project to scan (must not be {@code null})
     * @param clip    the clip to locate (may be {@code null} → empty)
     * @return the owning track, or {@link Optional#empty()}
     */
    public static Optional<Track> findOwningTrack(DawProject project, AudioClip clip) {
        Objects.requireNonNull(project, "project must not be null");
        if (clip == null) {
            return Optional.empty();
        }
        for (Track t : project.getTracks()) {
            for (AudioClip c : t.getClips()) {
                if (c == clip) {
                    return Optional.of(t);
                }
            }
        }
        return Optional.empty();
    }
}
