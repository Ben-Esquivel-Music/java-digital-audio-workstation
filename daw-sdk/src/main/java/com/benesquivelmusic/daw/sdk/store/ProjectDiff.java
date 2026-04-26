package com.benesquivelmusic.daw.sdk.store;

import com.benesquivelmusic.daw.sdk.event.ProjectChange;
import com.benesquivelmusic.daw.sdk.model.Project;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Computes the per-entity {@link ProjectChange} list induced by replacing
 * one {@link Project} snapshot with another.
 *
 * <p>Map keys are {@link UUID}s and values are themselves immutable records,
 * so the diff is a straightforward structural comparison: ids present only
 * in {@code previous} are removals, ids only in {@code next} are additions,
 * ids in both with non-equal values are updates.</p>
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
                ProjectChange.TrackAdded::new,
                ProjectChange.TrackUpdated::new,
                ProjectChange.TrackRemoved::new);

        // Audio clips
        diffMap(previous.audioClips(), next.audioClips(), out,
                ProjectChange.AudioClipAdded::new,
                ProjectChange.AudioClipUpdated::new,
                ProjectChange.AudioClipRemoved::new);

        // MIDI clips
        diffMap(previous.midiClips(), next.midiClips(), out,
                ProjectChange.MidiClipAdded::new,
                ProjectChange.MidiClipUpdated::new,
                ProjectChange.MidiClipRemoved::new);

        // Mixer channels
        diffMap(previous.mixerChannels(), next.mixerChannels(), out,
                ProjectChange.MixerChannelAdded::new,
                ProjectChange.MixerChannelUpdated::new,
                ProjectChange.MixerChannelRemoved::new);

        // Returns
        diffMap(previous.returns(), next.returns(), out,
                ProjectChange.ReturnAdded::new,
                ProjectChange.ReturnUpdated::new,
                ProjectChange.ReturnRemoved::new);

        // Automation lanes
        diffMap(previous.automationLanes(), next.automationLanes(), out,
                ProjectChange.AutomationLaneAdded::new,
                ProjectChange.AutomationLaneUpdated::new,
                ProjectChange.AutomationLaneRemoved::new);

        return List.copyOf(out);
    }

    private static <V> void diffMap(
            Map<UUID, V> prev,
            Map<UUID, V> next,
            List<ProjectChange> out,
            BiFunction<UUID, V, ProjectChange> added,
            TriFunction<UUID, V, V, ProjectChange> updated,
            BiFunction<UUID, V, ProjectChange> removed) {

        Set<UUID> all = new HashSet<>(prev.keySet());
        all.addAll(next.keySet());
        for (UUID id : all) {
            V before = prev.get(id);
            V after  = next.get(id);
            if (before == null && after != null) {
                out.add(added.apply(id, after));
            } else if (before != null && after == null) {
                out.add(removed.apply(id, before));
            } else if (before != null && !before.equals(after)) {
                out.add(updated.apply(id, before, after));
            }
        }
    }

    @FunctionalInterface
    private interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }
}
