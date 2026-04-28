package com.benesquivelmusic.daw.core.mastering;

import com.benesquivelmusic.daw.sdk.mastering.AlbumTrackEntry;
import com.benesquivelmusic.daw.sdk.mastering.album.AlbumMetadata;
import com.benesquivelmusic.daw.sdk.mastering.album.AlbumTrackMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An ordered sequence of tracks for album assembly.
 *
 * <p>Manages track ordering, per-track gap timing, crossfade configuration,
 * and PQ sheet generation for CD mastering workflows.</p>
 *
 * <p>Each track in the sequence has a configurable pre-gap (silent pause
 * before the track starts) and an optional crossfade with the preceding
 * track. The first track's pre-gap is always zero.</p>
 */
public final class AlbumSequence {

    private String albumTitle;
    private String artist;
    private final List<AlbumTrackEntry> tracks = new ArrayList<>();
    private AlbumMetadata albumMetadata;
    private final Map<Integer, AlbumTrackMetadata> trackMetadata = new HashMap<>();

    /**
     * Creates an empty album sequence.
     *
     * @param albumTitle the album title
     * @param artist     the artist name
     */
    public AlbumSequence(String albumTitle, String artist) {
        this.albumTitle = Objects.requireNonNull(albumTitle, "albumTitle must not be null");
        this.artist = Objects.requireNonNull(artist, "artist must not be null");
        this.albumMetadata = AlbumMetadata.of(albumTitle, artist);
    }

    /** Returns the album title. */
    public String getAlbumTitle() { return albumTitle; }

    /** Sets the album title. The change is mirrored in {@link #getAlbumMetadata()}. */
    public void setAlbumTitle(String albumTitle) {
        this.albumTitle = Objects.requireNonNull(albumTitle, "albumTitle must not be null");
        this.albumMetadata = this.albumMetadata.withTitle(albumTitle);
    }

    /** Returns the artist name. */
    public String getArtist() { return artist; }

    /** Sets the artist name. The change is mirrored in {@link #getAlbumMetadata()}. */
    public void setArtist(String artist) {
        this.artist = Objects.requireNonNull(artist, "artist must not be null");
        this.albumMetadata = this.albumMetadata.withArtist(artist);
    }

    /**
     * Returns the album-level metadata (title, artist, year, genre, UPC/EAN, release date).
     *
     * @return the album metadata (never {@code null})
     */
    public AlbumMetadata getAlbumMetadata() {
        return albumMetadata;
    }

    /**
     * Replaces the album-level metadata. The {@code title} and {@code artist}
     * fields of {@code metadata} are also written back to the legacy
     * {@link #setAlbumTitle(String)} / {@link #setArtist(String)} accessors so
     * that older callers stay consistent.
     *
     * @param metadata the new album metadata
     */
    public void setAlbumMetadata(AlbumMetadata metadata) {
        this.albumMetadata = Objects.requireNonNull(metadata, "metadata must not be null");
        this.albumTitle = metadata.title();
        this.artist = metadata.artist();
    }

    /**
     * Returns the optional per-track metadata for the track at {@code index}.
     *
     * @param index the track index
     * @return the metadata, or empty if none has been assigned
     */
    public Optional<AlbumTrackMetadata> getTrackMetadata(int index) {
        if (index < 0 || index >= tracks.size()) {
            throw new IndexOutOfBoundsException("index: " + index);
        }
        return Optional.ofNullable(trackMetadata.get(index));
    }

    /**
     * Assigns per-track metadata for the track at {@code index}.
     *
     * @param index    the track index
     * @param metadata the metadata to associate (or {@code null} to clear)
     */
    public void setTrackMetadata(int index, AlbumTrackMetadata metadata) {
        if (index < 0 || index >= tracks.size()) {
            throw new IndexOutOfBoundsException("index: " + index);
        }
        if (metadata == null) {
            trackMetadata.remove(index);
        } else {
            trackMetadata.put(index, metadata);
        }
    }

    /**
     * Adds a track to the end of the sequence.
     *
     * @param entry the album track entry
     */
    public void addTrack(AlbumTrackEntry entry) {
        tracks.add(Objects.requireNonNull(entry, "entry must not be null"));
    }

    /**
     * Inserts a track at the specified index. Existing per-track metadata
     * at and after {@code index} is shifted up by one to keep references
     * aligned with the new track positions.
     *
     * @param index the insertion index
     * @param entry the album track entry
     */
    public void insertTrack(int index, AlbumTrackEntry entry) {
        tracks.add(index, Objects.requireNonNull(entry, "entry must not be null"));
        shiftMetadata(index, +1);
    }

    /**
     * Removes the track at the specified index. The associated per-track
     * metadata (if any) is removed and any later metadata entries are
     * shifted down by one.
     *
     * @param index the index of the track to remove
     * @return the removed track entry
     */
    public AlbumTrackEntry removeTrack(int index) {
        AlbumTrackEntry removed = tracks.remove(index);
        trackMetadata.remove(index);
        shiftMetadata(index + 1, -1);
        return removed;
    }

    /**
     * Replaces the track at the specified index.
     *
     * @param index the index
     * @param entry the new track entry
     */
    public void setTrack(int index, AlbumTrackEntry entry) {
        tracks.set(index, Objects.requireNonNull(entry, "entry must not be null"));
    }

    /**
     * Moves a track from one position to another (drag-and-drop reordering).
     *
     * @param fromIndex the current index
     * @param toIndex   the target index
     */
    public void moveTrack(int fromIndex, int toIndex) {
        if (fromIndex < 0 || fromIndex >= tracks.size()) {
            throw new IndexOutOfBoundsException("fromIndex: " + fromIndex);
        }
        if (toIndex < 0 || toIndex >= tracks.size()) {
            throw new IndexOutOfBoundsException("toIndex: " + toIndex);
        }
        AlbumTrackEntry entry = tracks.remove(fromIndex);
        tracks.add(toIndex, entry);
        AlbumTrackMetadata movedMeta = trackMetadata.remove(fromIndex);
        // Shift the rest to fill the gap, then insert the moved metadata at toIndex.
        shiftMetadata(fromIndex + 1, -1);
        shiftMetadata(toIndex, +1);
        if (movedMeta != null) {
            trackMetadata.put(toIndex, movedMeta);
        }
    }

    /**
     * Shifts every per-track metadata entry whose index is {@code >= fromIndex}
     * by {@code delta}.
     */
    private void shiftMetadata(int fromIndex, int delta) {
        if (delta == 0 || trackMetadata.isEmpty()) {
            return;
        }
        Map<Integer, AlbumTrackMetadata> shifted = new HashMap<>();
        for (Map.Entry<Integer, AlbumTrackMetadata> e : trackMetadata.entrySet()) {
            int idx = e.getKey();
            if (idx >= fromIndex) {
                shifted.put(idx + delta, e.getValue());
            } else {
                shifted.put(idx, e.getValue());
            }
        }
        trackMetadata.clear();
        trackMetadata.putAll(shifted);
    }

    /**
     * Returns an unmodifiable view of the track list.
     *
     * @return the ordered list of album track entries
     */
    public List<AlbumTrackEntry> getTracks() {
        return Collections.unmodifiableList(tracks);
    }

    /** Returns the number of tracks. */
    public int size() {
        return tracks.size();
    }

    /**
     * Computes the total album duration in seconds, including gaps.
     *
     * <p>For each track, adds the track duration and its pre-gap.
     * The first track's pre-gap is excluded (albums start at track 1
     * with no leading silence). Crossfade durations reduce the total
     * time because the outgoing and incoming tracks overlap.</p>
     *
     * @return the total album duration in seconds
     */
    public double getTotalDurationSeconds() {
        double total = 0.0;
        for (int i = 0; i < tracks.size(); i++) {
            AlbumTrackEntry entry = tracks.get(i);
            total += entry.durationSeconds();
            if (i > 0) {
                total += entry.preGapSeconds();
                total -= entry.crossfadeDuration();
            }
        }
        return total;
    }

    /**
     * Computes the start time of each track in seconds from the album start.
     *
     * @return a list of start times (one per track, in sequence order)
     */
    public List<Double> getTrackStartTimes() {
        List<Double> startTimes = new ArrayList<>();
        double position = 0.0;
        for (int i = 0; i < tracks.size(); i++) {
            AlbumTrackEntry entry = tracks.get(i);
            if (i > 0) {
                position += entry.preGapSeconds();
                position -= entry.crossfadeDuration();
            }
            startTimes.add(position);
            position += entry.durationSeconds();
        }
        return Collections.unmodifiableList(startTimes);
    }

    /**
     * Generates a PQ sheet as a formatted string.
     *
     * <p>A PQ sheet contains the track number, start time, duration, title,
     * and ISRC code for each track — used in CD replication workflows.</p>
     *
     * @return the PQ sheet text
     */
    public String generatePqSheet() {
        StringBuilder sb = new StringBuilder();
        sb.append("PQ Sheet: ").append(albumTitle).append('\n');
        sb.append("Artist: ").append(artist).append('\n');
        sb.append(String.format("Total Duration: %s%n", formatTime(getTotalDurationSeconds())));
        sb.append('\n');
        sb.append(String.format("%-6s %-12s %-12s %-30s %-20s %-15s%n",
                "Track", "Start", "Duration", "Title", "Artist", "ISRC"));
        sb.append("-".repeat(95)).append('\n');

        List<Double> startTimes = getTrackStartTimes();
        for (int i = 0; i < tracks.size(); i++) {
            AlbumTrackEntry entry = tracks.get(i);
            sb.append(String.format("%-6d %-12s %-12s %-30s %-20s %-15s%n",
                    i + 1,
                    formatTime(startTimes.get(i)),
                    formatTime(entry.durationSeconds()),
                    entry.title(),
                    entry.artist() != null ? entry.artist() : "",
                    entry.isrc() != null ? entry.isrc() : ""));
        }
        return sb.toString();
    }

    /**
     * Generates a cue sheet as a formatted string.
     *
     * <p>A cue sheet lists the album metadata followed by each track's number,
     * title, artist, start time, duration, and ISRC code — commonly used for
     * disc replication and digital distribution workflows.</p>
     *
     * @return the cue sheet text
     */
    public String generateCueSheet() {
        StringBuilder sb = new StringBuilder();
        sb.append("TITLE \"").append(albumTitle).append("\"\n");
        sb.append("PERFORMER \"").append(artist).append("\"\n");
        sb.append('\n');

        List<Double> startTimes = getTrackStartTimes();
        for (int i = 0; i < tracks.size(); i++) {
            AlbumTrackEntry entry = tracks.get(i);
            sb.append("  TRACK ").append(String.format("%02d", i + 1)).append(" AUDIO\n");
            sb.append("    TITLE \"").append(entry.title()).append("\"\n");
            if (entry.artist() != null) {
                sb.append("    PERFORMER \"").append(entry.artist()).append("\"\n");
            }
            if (entry.isrc() != null) {
                sb.append("    ISRC ").append(entry.isrc()).append('\n');
            }
            if (i > 0 && entry.preGapSeconds() > 0) {
                sb.append("    PREGAP ").append(formatCueTime(entry.preGapSeconds())).append('\n');
            }
            sb.append("    INDEX 01 ").append(formatCueTime(startTimes.get(i))).append('\n');
        }
        return sb.toString();
    }

    private static String formatCueTime(double seconds) {
        int mins = (int) (seconds / 60);
        double secs = seconds - mins * 60;
        int wholeSecs = (int) secs;
        int frames = (int) ((secs - wholeSecs) * 75);
        return String.format("%02d:%02d:%02d", mins, wholeSecs, frames);
    }

    private static String formatTime(double seconds) {
        int mins = (int) (seconds / 60);
        double secs = seconds - mins * 60;
        return String.format("%02d:%06.3f", mins, secs);
    }
}
