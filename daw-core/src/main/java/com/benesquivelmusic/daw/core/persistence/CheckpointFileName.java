package com.benesquivelmusic.daw.core.persistence;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The name of a checkpoint file, {@code checkpoint-<index>-<yyyyMMdd'T'HHmmss>.daw}
 * — the one grammar every writer and reader of a project's
 * {@code checkpoints/} directory shares (story 331): the
 * {@link CheckpointManager} that writes and prunes the files, the journal's
 * recovery scan and the session history that list them.
 *
 * <p>The index is written with at least three digits
 * ({@code checkpoint-001-…}, {@code checkpoint-1000-…}) and read as a
 * {@code long}. The stamp is local time in the zone the file was written in,
 * with no offset, as the names on disk have always been. A name matches only
 * in full: a helper's temp sibling ({@code .checkpoint-….daw.<nonce>.tmp}),
 * a backup or a near miss is not a checkpoint name. A name is written in
 * ASCII digits under every default locale ({@link Locale#ROOT}): a
 * {@code %d} in the user's format locale writes Arabic-Indic or Devanagari
 * digits, which no reader would take for a checkpoint.</p>
 *
 * <p>Checkpoints are ordered by {@link #ORDER}: the stamp in the name, then
 * the index (ruling F5). Never by file time, which a copy, a sync client or
 * an archive restore rewrites; and never by index first, which restarted at 1
 * on every launch before story 331 seeded it from the directory.</p>
 */
public final class CheckpointFileName {

    /** Newest last: by the stamp in the name, then by index, then by the name itself. */
    public static final Comparator<CheckpointFileName> ORDER = Comparator
            .comparing(CheckpointFileName::stamp)
            .thenComparingLong(CheckpointFileName::index)
            .thenComparing(CheckpointFileName::fileName);

    private static final Pattern NAME = Pattern.compile("checkpoint-(\\d+)-(\\d{8}T\\d{6})\\.daw");

    private static final String NAME_FORMAT = "checkpoint-%03d-%s.daw";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    private final String fileName;
    private final long index;
    private final LocalDateTime stamp;

    private CheckpointFileName(String fileName, long index, LocalDateTime stamp) {
        this.fileName = fileName;
        this.index = index;
        this.stamp = stamp;
    }

    /**
     * The name of checkpoint {@code index} written at {@code time}, stamped
     * in the system zone.
     *
     * @param index the checkpoint's index, at least 1
     * @param time  when the checkpoint is written
     * @return the file name
     * @throws IllegalArgumentException if {@code index} is below 1
     */
    public static String format(long index, Instant time) {
        return format(index, time, ZoneId.systemDefault());
    }

    /**
     * The name of checkpoint {@code index} written at {@code time}, stamped
     * in {@code zone}.
     *
     * @param index the checkpoint's index, at least 1
     * @param time  when the checkpoint is written
     * @param zone  the zone of the stamp
     * @return the file name
     * @throws IllegalArgumentException if {@code index} is below 1
     */
    public static String format(long index, Instant time, ZoneId zone) {
        return of(index, time, zone).fileName();
    }

    /**
     * Checkpoint {@code index} written at {@code time}, stamped in
     * {@code zone}: what {@link #parse} reads back from its
     * {@linkplain #fileName() name}, so a writer indexes the file it
     * publishes without reading the name again.
     *
     * @param index the checkpoint's index, at least 1
     * @param time  when the checkpoint is written
     * @param zone  the zone of the stamp
     * @return the checkpoint name
     * @throws IllegalArgumentException if {@code index} is below 1
     */
    static CheckpointFileName of(long index, Instant time, ZoneId zone) {
        Objects.requireNonNull(time, "time must not be null");
        Objects.requireNonNull(zone, "zone must not be null");
        if (index < 1) {
            throw new IllegalArgumentException("a checkpoint index is at least 1: " + index);
        }
        LocalDateTime stamp = LocalDateTime.ofInstant(time, zone).truncatedTo(ChronoUnit.SECONDS);
        return new CheckpointFileName(String.format(Locale.ROOT, NAME_FORMAT, index, STAMP.format(stamp)), index,
                stamp);
    }

    /**
     * Reads {@code fileName} as a checkpoint name. Nothing escapes: an index
     * too large for a {@code long}, or a stamp that is no date and time
     * ({@code 20261399T250000}), is no checkpoint name.
     *
     * @param fileName a file name, without directory
     * @return the checkpoint name, or empty when {@code fileName} is not exactly one
     */
    public static Optional<CheckpointFileName> parse(String fileName) {
        Objects.requireNonNull(fileName, "fileName must not be null");
        Matcher matcher = NAME.matcher(fileName);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            long index = Long.parseLong(matcher.group(1));
            LocalDateTime stamp = LocalDateTime.parse(matcher.group(2), STAMP);
            return Optional.of(new CheckpointFileName(fileName, index, stamp));
        } catch (NumberFormatException | DateTimeException notACheckpoint) {
            return Optional.empty();
        }
    }

    /** {@return the file name, exactly as read or written} */
    public String fileName() {
        return fileName;
    }

    /** {@return the checkpoint's index} */
    public long index() {
        return index;
    }

    /** {@return the local date and time in the name} */
    public LocalDateTime stamp() {
        return stamp;
    }

    /**
     * The instant the stamp names, read in {@code zone} — the zone the
     * checkpoint was written in. Inside a repeated daylight-saving hour the
     * earlier of the two instants.
     *
     * @param zone the zone the stamp was written in
     * @return the instant
     */
    public Instant instantIn(ZoneId zone) {
        Objects.requireNonNull(zone, "zone must not be null");
        return stamp.atZone(zone).toInstant();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CheckpointFileName name && fileName.equals(name.fileName);
    }

    @Override
    public int hashCode() {
        return fileName.hashCode();
    }

    @Override
    public String toString() {
        return fileName;
    }
}
