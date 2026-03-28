package com.benesquivelmusic.daw.core.mastering;

import com.benesquivelmusic.daw.sdk.mastering.AlbumTrackEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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

    /**
     * Creates an empty album sequence.
     *
     * @param albumTitle the album title
     * @param artist     the artist name
     */
    public AlbumSequence(String albumTitle, String artist) {
        this.albumTitle = Objects.requireNonNull(albumTitle, "albumTitle must not be null");
        this.artist = Objects.requireNonNull(artist, "artist must not be null");
    }

    /** Returns the album title. */
    public String getAlbumTitle() { return albumTitle; }

    /** Sets the album title. */
    public void setAlbumTitle(String albumTitle) {
        this.albumTitle = Objects.requireNonNull(albumTitle, "albumTitle must not be null");
    }

    /** Returns the artist name. */
    public String getArtist() { return artist; }

    /** Sets the artist name. */
    public void setArtist(String artist) {
        this.artist = Objects.requireNonNull(artist, "artist must not be null");
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
     * Inserts a track at the specified index.
     *
     * @param index the insertion index
     * @param entry the album track entry
     */
    public void insertTrack(int index, AlbumTrackEntry entry) {
        tracks.add(index, Objects.requireNonNull(entry, "entry must not be null"));
    }

    /**
     * Removes the track at the specified index.
     *
     * @param index the index of the track to remove
     * @return the removed track entry
     */
    public AlbumTrackEntry removeTrack(int index) {
        return tracks.remove(index);
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
