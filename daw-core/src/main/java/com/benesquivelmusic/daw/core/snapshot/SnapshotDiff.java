package com.benesquivelmusic.daw.core.snapshot;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Computes structural differences between two {@link DawProject} states.
 *
 * <p>The diff used by the snapshot browser is intentionally coarse: it
 * lists tracks and clips that were added, removed, or modified, along with
 * per-track mixer parameter changes (volume / pan / mute / solo /
 * insert plug-in count). Tracks and clips are matched by their stable
 * IDs (so renames are reported as modifications, not as add+remove). It
 * is sufficient to power the "Compare with current" view and the per-row
 * notable changes summary
 * ("+3 clips, -1 track, 14 edits") shown in the timeline.</p>
 *
 * <p>This is <em>not</em> a precise audio-content diff and intentionally
 * does not attempt git-style merge or branching semantics (see Non-Goals
 * on the issue).</p>
 */
public final class SnapshotDiff {

    /** What kind of change was detected for a row in the diff. */
    public enum ChangeType { ADDED, REMOVED, MODIFIED }

    /**
     * A single row in the diff.
     *
     * @param category    e.g. {@code "track"}, {@code "clip"}, {@code "mixer"},
     *                    or {@code "project"}
     * @param identifier  a stable identifier within the category (e.g.
     *                    {@code "Lead Vocals"} or {@code "Lead Vocals/Verse 1"})
     * @param changeType  what changed
     * @param description a human-readable summary of the change
     */
    public record Entry(String category, String identifier,
                        ChangeType changeType, String description) {
        public Entry {
            Objects.requireNonNull(category, "category must not be null");
            Objects.requireNonNull(identifier, "identifier must not be null");
            Objects.requireNonNull(changeType, "changeType must not be null");
            Objects.requireNonNull(description, "description must not be null");
        }
    }

    private final List<Entry> entries;

    private SnapshotDiff(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /**
     * Computes a diff describing the changes needed to transform {@code from}
     * into {@code to}. Either side may be {@code null}, in which case the
     * other side is reported entirely as added or removed.
     *
     * @param from the baseline project
     * @param to   the comparison project
     * @return the diff
     */
    public static SnapshotDiff between(DawProject from, DawProject to) {
        List<Entry> entries = new ArrayList<>();

        if (from == null && to == null) {
            return new SnapshotDiff(entries);
        }
        if (from == null) {
            entries.add(new Entry("project", to.getName(), ChangeType.ADDED,
                    "Project added"));
            for (Track t : to.getTracks()) {
                entries.add(new Entry("track", t.getName(), ChangeType.ADDED,
                        "Track added"));
            }
            return new SnapshotDiff(entries);
        }
        if (to == null) {
            entries.add(new Entry("project", from.getName(), ChangeType.REMOVED,
                    "Project removed"));
            for (Track t : from.getTracks()) {
                entries.add(new Entry("track", t.getName(), ChangeType.REMOVED,
                        "Track removed"));
            }
            return new SnapshotDiff(entries);
        }

        if (!Objects.equals(from.getName(), to.getName())) {
            entries.add(new Entry("project", from.getName(),
                    ChangeType.MODIFIED,
                    "Renamed: '" + from.getName() + "' → '" + to.getName() + "'"));
        }

        Map<String, Track> fromTracks = byId(from.getTracks());
        Map<String, Track> toTracks = byId(to.getTracks());

        for (Map.Entry<String, Track> e : fromTracks.entrySet()) {
            if (!toTracks.containsKey(e.getKey())) {
                entries.add(new Entry("track", e.getValue().getName(),
                        ChangeType.REMOVED, "Track removed"));
            }
        }
        for (Map.Entry<String, Track> e : toTracks.entrySet()) {
            if (!fromTracks.containsKey(e.getKey())) {
                Track added = e.getValue();
                entries.add(new Entry("track", added.getName(),
                        ChangeType.ADDED, "Track added"));
                for (AudioClip clip : added.getClips()) {
                    entries.add(new Entry("clip",
                            added.getName() + "/" + clip.getName(),
                            ChangeType.ADDED, "Clip added"));
                }
            }
        }
        for (Map.Entry<String, Track> e : fromTracks.entrySet()) {
            Track ft = e.getValue();
            Track tt = toTracks.get(e.getKey());
            if (tt != null) {
                if (!Objects.equals(ft.getName(), tt.getName())) {
                    entries.add(new Entry("track", tt.getName(),
                            ChangeType.MODIFIED,
                            "Track renamed: '" + ft.getName() + "' → '" + tt.getName() + "'"));
                }
                diffTrack(ft, tt, from, to, entries);
            }
        }

        return new SnapshotDiff(entries);
    }

    private static Map<String, Track> byId(List<Track> tracks) {
        Map<String, Track> map = new HashMap<>();
        for (Track t : tracks) {
            map.putIfAbsent(trackKey(t), t);
        }
        return map;
    }

    private static String trackKey(Track t) {
        String id = t.getId();
        return (id != null && !id.isBlank()) ? id : "name:" + t.getName();
    }

    private static void diffTrack(Track ft, Track tt,
                                  DawProject from, DawProject to,
                                  List<Entry> entries) {
        Map<String, AudioClip> fromClips = clipsById(ft.getClips());
        Map<String, AudioClip> toClips = clipsById(tt.getClips());

        for (Map.Entry<String, AudioClip> e : fromClips.entrySet()) {
            if (!toClips.containsKey(e.getKey())) {
                entries.add(new Entry("clip",
                        ft.getName() + "/" + e.getValue().getName(),
                        ChangeType.REMOVED, "Clip removed"));
            }
        }
        for (Map.Entry<String, AudioClip> e : toClips.entrySet()) {
            AudioClip b = e.getValue();
            if (!fromClips.containsKey(e.getKey())) {
                entries.add(new Entry("clip",
                        tt.getName() + "/" + b.getName(),
                        ChangeType.ADDED, "Clip added"));
            } else {
                AudioClip a = fromClips.get(e.getKey());
                if (Double.compare(a.getStartBeat(), b.getStartBeat()) != 0
                        || Double.compare(a.getDurationBeats(), b.getDurationBeats()) != 0
                        || Double.compare(a.getGainDb(), b.getGainDb()) != 0) {
                    entries.add(new Entry("clip",
                            tt.getName() + "/" + b.getName(),
                            ChangeType.MODIFIED,
                            "Clip parameters changed"));
                }
            }
        }

        if (Double.compare(ft.getVolume(), tt.getVolume()) != 0) {
            entries.add(new Entry("mixer", tt.getName(), ChangeType.MODIFIED,
                    "Volume " + ft.getVolume() + " → " + tt.getVolume()));
        }
        if (Double.compare(ft.getPan(), tt.getPan()) != 0) {
            entries.add(new Entry("mixer", tt.getName(), ChangeType.MODIFIED,
                    "Pan " + ft.getPan() + " → " + tt.getPan()));
        }
        if (ft.isMuted() != tt.isMuted()) {
            entries.add(new Entry("mixer", tt.getName(), ChangeType.MODIFIED,
                    "Mute " + ft.isMuted() + " → " + tt.isMuted()));
        }
        if (ft.isSolo() != tt.isSolo()) {
            entries.add(new Entry("mixer", tt.getName(), ChangeType.MODIFIED,
                    "Solo " + ft.isSolo() + " → " + tt.isSolo()));
        }

        MixerChannel fc = from.getMixerChannelForTrack(ft);
        MixerChannel tc = to.getMixerChannelForTrack(tt);
        if (fc != null && tc != null) {
            int fInserts = fc.getInsertCount();
            int tInserts = tc.getInsertCount();
            if (fInserts != tInserts) {
                entries.add(new Entry("plugin", tt.getName(),
                        ChangeType.MODIFIED,
                        "Insert plug-ins " + fInserts + " → " + tInserts));
            }
        }
    }

    private static Map<String, AudioClip> clipsById(List<AudioClip> clips) {
        Map<String, AudioClip> map = new HashMap<>();
        for (AudioClip c : clips) {
            String id = c.getId();
            map.putIfAbsent((id != null && !id.isBlank()) ? id : "name:" + c.getName(), c);
        }
        return map;
    }

    /** Returns an immutable list of all diff entries. */
    public List<Entry> entries() {
        return entries;
    }

    /** Returns {@code true} if there are no differences. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Returns a short human-readable summary suitable for a row label,
     * such as {@code "+3 clips, -1 track, 14 edits"}.
     *
     * @return the summary
     */
    public String shortSummary() {
        int addedClips = 0, removedClips = 0;
        int addedTracks = 0, removedTracks = 0;
        int edits = 0;
        for (Entry e : entries) {
            switch (e.category()) {
                case "clip" -> {
                    switch (e.changeType()) {
                        case ADDED -> addedClips++;
                        case REMOVED -> removedClips++;
                        case MODIFIED -> edits++;
                    }
                }
                case "track" -> {
                    switch (e.changeType()) {
                        case ADDED -> addedTracks++;
                        case REMOVED -> removedTracks++;
                        case MODIFIED -> edits++;
                    }
                }
                default -> edits++;
            }
        }
        StringBuilder sb = new StringBuilder();
        appendCount(sb, addedClips, "+", "clip");
        appendCount(sb, removedClips, "-", "clip");
        appendCount(sb, addedTracks, "+", "track");
        appendCount(sb, removedTracks, "-", "track");
        if (edits > 0) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(edits).append(" edit").append(edits == 1 ? "" : "s");
        }
        return sb.length() == 0 ? "No changes" : sb.toString();
    }

    private static void appendCount(StringBuilder sb, int count,
                                    String sign, String unit) {
        if (count == 0) return;
        if (sb.length() > 0) sb.append(", ");
        sb.append(sign).append(count).append(' ').append(unit);
        if (count != 1) sb.append('s');
    }
}
