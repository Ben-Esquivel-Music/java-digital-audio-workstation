package com.benesquivelmusic.daw.sdk.store;

import com.benesquivelmusic.daw.sdk.event.ProjectChange;
import com.benesquivelmusic.daw.sdk.model.Project;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Computes the per-entity {@link ProjectChange} list induced by replacing
 * one {@link Project} snapshot with another.
 *
 * <p>Map keys are {@link UUID}s and values are themselves immutable records,
 * so the diff is a straightforward structural comparison: ids present only
 * in {@code previous} are removals, ids only in {@code next} are additions,
 * ids in both with non-equal values are updates.</p>
 *
 * <p>The emitted event list is deterministic: within each entity type,
 * events are appended in {@link UUID#compareTo natural UUID order} of the
 * affected ids. Across entity types, events are appended in a fixed
 * sequence (tracks → audio clips → MIDI clips → mixer channels → returns
 * → automation lanes). Subscribers and tests can therefore rely on a
 * stable sequence even when several entities change in one transition.</p>
 *
 * <p>Package-private — used by {@link ProjectStore} when an action produces
 * a new snapshot.</p>
 */
final class ProjectDiff {

    private ProjectDiff() {}

    static List<ProjectChange> diff(Project previous, Project next) {
        Objects.requireNonNull(previous, "previous must not be null");
        Objects.requireNonNull(next, "next must not be null");

        List<ProjectChange> out = new ArrayList<>();

        // Tracks
        diffMap(previous.tracks(), next.tracks(), out,
                v -> new ProjectChange.TrackAdded(v),
                ProjectChange.TrackUpdated::new,
                v -> new ProjectChange.TrackRemoved(v));

        // Audio clips
        diffMap(previous.audioClips(), next.audioClips(), out,
                v -> new ProjectChange.AudioClipAdded(v),
                ProjectChange.AudioClipUpdated::new,
                v -> new ProjectChange.AudioClipRemoved(v));

        // MIDI clips
        diffMap(previous.midiClips(), next.midiClips(), out,
                v -> new ProjectChange.MidiClipAdded(v),
                ProjectChange.MidiClipUpdated::new,
                v -> new ProjectChange.MidiClipRemoved(v));

        // Mixer channels
        diffMap(previous.mixerChannels(), next.mixerChannels(), out,
                v -> new ProjectChange.MixerChannelAdded(v),
                ProjectChange.MixerChannelUpdated::new,
                v -> new ProjectChange.MixerChannelRemoved(v));

        // Returns
        diffMap(previous.returns(), next.returns(), out,
                v -> new ProjectChange.ReturnAdded(v),
                ProjectChange.ReturnUpdated::new,
                v -> new ProjectChange.ReturnRemoved(v));

        // Automation lanes
        diffMap(previous.automationLanes(), next.automationLanes(), out,
                v -> new ProjectChange.AutomationLaneAdded(v),
                ProjectChange.AutomationLaneUpdated::new,
                v -> new ProjectChange.AutomationLaneRemoved(v));

        return List.copyOf(out);
    }

    private static <V> void diffMap(
            Map<UUID, V> prev,
            Map<UUID, V> next,
            List<ProjectChange> out,
            Function<V, ProjectChange> added,
            BiFunction<V, V, ProjectChange> updated,
            Function<V, ProjectChange> removed) {

        // Deterministic iteration: union of all ids, sorted by UUID's
        // natural ordering. This avoids dependence on the iteration order
        // of Map.copyOf (which is unspecified) and produces a stable event
        // sequence subscribers and tests can rely on.
        Set<UUID> ordered = new TreeSet<>(prev.keySet());
        ordered.addAll(next.keySet());
        for (UUID id : ordered) {
            V before = prev.get(id);
            V after  = next.get(id);
            if (before == null && after != null) {
                out.add(added.apply(after));
            } else if (before != null && after == null) {
                out.add(removed.apply(before));
            } else if (before != null && !before.equals(after)) {
                out.add(updated.apply(before, after));
            }
        }
    }
}
