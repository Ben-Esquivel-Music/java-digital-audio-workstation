package com.benesquivelmusic.daw.sdk.model;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The full immutable session state of a DAW project.
 *
 * <p>A {@code Project} is a value: every collection it carries is a
 * defensively-copied immutable {@link Map} keyed by {@link UUID}. Mutations
 * are expressed through {@code withX(...)} methods that return a new
 * {@code Project} with the requested change applied via
 * {@link Map#copyOf(Map)} so the original snapshot remains untouched.</p>
 *
 * <p>This design makes concurrent reads lock-free, structural equality
 * automatic, and undo/redo trivial: an action is just {@code (before, after)}
 * snapshots that can be swapped in atomically.</p>
 *
 * @param id              stable unique identifier
 * @param name            display name
 * @param tracks          tracks indexed by id
 * @param audioClips      audio clips indexed by id
 * @param midiClips       MIDI clips indexed by id
 * @param mixerChannels   mixer channels indexed by id
 * @param returns         return buses indexed by id
 * @param automationLanes automation lanes indexed by id
 */
public record Project(
        UUID id,
        String name,
        Map<UUID, Track> tracks,
        Map<UUID, AudioClip> audioClips,
        Map<UUID, MidiClip> midiClips,
        Map<UUID, MixerChannel> mixerChannels,
        Map<UUID, Return> returns,
        Map<UUID, AutomationLane> automationLanes) {

    public Project {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        tracks          = Map.copyOf(Objects.requireNonNull(tracks, "tracks must not be null"));
        audioClips      = Map.copyOf(Objects.requireNonNull(audioClips, "audioClips must not be null"));
        midiClips       = Map.copyOf(Objects.requireNonNull(midiClips, "midiClips must not be null"));
        mixerChannels   = Map.copyOf(Objects.requireNonNull(mixerChannels, "mixerChannels must not be null"));
        returns         = Map.copyOf(Objects.requireNonNull(returns, "returns must not be null"));
        automationLanes = Map.copyOf(Objects.requireNonNull(automationLanes, "automationLanes must not be null"));
    }

    /** Creates a freshly-identified, empty project with the given name. */
    public static Project empty(String name) {
        return new Project(UUID.randomUUID(), name,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    public Project withId(UUID id) {
        return new Project(id, name, tracks, audioClips, midiClips, mixerChannels, returns, automationLanes);
    }

    public Project withName(String name) {
        return new Project(id, name, tracks, audioClips, midiClips, mixerChannels, returns, automationLanes);
    }

    public Project withTracks(Map<UUID, Track> tracks) {
        return new Project(id, name, tracks, audioClips, midiClips, mixerChannels, returns, automationLanes);
    }

    public Project withAudioClips(Map<UUID, AudioClip> audioClips) {
        return new Project(id, name, tracks, audioClips, midiClips, mixerChannels, returns, automationLanes);
    }

    public Project withMidiClips(Map<UUID, MidiClip> midiClips) {
        return new Project(id, name, tracks, audioClips, midiClips, mixerChannels, returns, automationLanes);
    }

    public Project withMixerChannels(Map<UUID, MixerChannel> mixerChannels) {
        return new Project(id, name, tracks, audioClips, midiClips, mixerChannels, returns, automationLanes);
    }

    public Project withReturns(Map<UUID, Return> returns) {
        return new Project(id, name, tracks, audioClips, midiClips, mixerChannels, returns, automationLanes);
    }

    public Project withAutomationLanes(Map<UUID, AutomationLane> automationLanes) {
        return new Project(id, name, tracks, audioClips, midiClips, mixerChannels, returns, automationLanes);
    }

    // ----- per-entity convenience updaters -----------------------------------------------------

    /** Returns a copy of this project with {@code track} added or replaced (keyed by its id). */
    public Project putTrack(Track track) {
        return withTracks(plus(tracks, track.id(), track));
    }

    /** Returns a copy of this project with the given track id removed (no-op if absent). */
    public Project removeTrack(UUID trackId) {
        return withTracks(minus(tracks, trackId));
    }

    public Project putAudioClip(AudioClip clip) {
        return withAudioClips(plus(audioClips, clip.id(), clip));
    }

    public Project removeAudioClip(UUID clipId) {
        return withAudioClips(minus(audioClips, clipId));
    }

    public Project putMidiClip(MidiClip clip) {
        return withMidiClips(plus(midiClips, clip.id(), clip));
    }

    public Project removeMidiClip(UUID clipId) {
        return withMidiClips(minus(midiClips, clipId));
    }

    public Project putMixerChannel(MixerChannel channel) {
        return withMixerChannels(plus(mixerChannels, channel.id(), channel));
    }

    public Project removeMixerChannel(UUID channelId) {
        return withMixerChannels(minus(mixerChannels, channelId));
    }

    public Project putReturn(Return ret) {
        return withReturns(plus(returns, ret.id(), ret));
    }

    public Project removeReturn(UUID returnId) {
        return withReturns(minus(returns, returnId));
    }

    public Project putAutomationLane(AutomationLane lane) {
        return withAutomationLanes(plus(automationLanes, lane.id(), lane));
    }

    public Project removeAutomationLane(UUID laneId) {
        return withAutomationLanes(minus(automationLanes, laneId));
    }

    // ----- helpers ------------------------------------------------------------------------------

    private static <K, V> Map<K, V> plus(Map<K, V> source, K key, V value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        // Preserve insertion order for stable iteration in views and tests.
        Map<K, V> next = new LinkedHashMap<>(source);
        next.put(key, value);
        return Map.copyOf(next);
    }

    private static <K, V> Map<K, V> minus(Map<K, V> source, K key) {
        if (!source.containsKey(key)) {
            return source;
        }
        Map<K, V> next = new HashMap<>(source);
        next.remove(key);
        return Map.copyOf(next);
    }
}
