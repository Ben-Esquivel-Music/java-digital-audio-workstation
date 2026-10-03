package com.benesquivelmusic.daw.core.recording;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Allocates take directories under a project's {@code audio/} directory
 * (Recording Reliability book §2.6, §3.3; story 323).
 *
 * <p>Layout: {@code <audio>/takes/<yyyy-MM-dd'T'HH-mm-ss>_take-NNNN} — a
 * sortable UTC stamp plus a per-project ordinal, so directories sort
 * chronologically, never collide across sessions, and are meaningful in a
 * file manager when the user goes looking after a crash. The ordinal is
 * {@code 1 + the highest ordinal already present} under {@code takes/}
 * (zero-padded to four digits, wider if ever needed); each candidate is
 * claimed with {@link Files#createDirectory} in a loop so a collision
 * (a concurrent allocation, or a name the scan did not see) simply bumps the
 * ordinal instead of co-occupying an existing take.</p>
 *
 * <p>Thread: any non-real-time thread; in the app the FX thread calls
 * {@link #allocate(Path, Instant)} once per record gesture before the engine
 * is touched. The OS temp directory is never used (book §9.4).</p>
 */
public final class TakeDirectories {

    /** Name of the takes directory under {@code audio/}. */
    public static final String TAKES_DIR_NAME = "takes";

    /** The stamp format, always rendered in UTC. */
    static final DateTimeFormatter STAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss").withZone(ZoneOffset.UTC);

    private static final String ORDINAL_SEPARATOR = "_take-";
    private static final Pattern TAKE_NAME = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2})" + ORDINAL_SEPARATOR + "(\\d{4,})");

    private TakeDirectories() {
        // utility class
    }

    /**
     * Creates and returns a fresh take directory under
     * {@code <audioDirectory>/takes/}.
     *
     * @param audioDirectory the project's {@code audio/} directory (created if missing)
     * @param now            the take start instant (stamped in UTC)
     * @return the newly created, empty take directory
     * @throws IOException if the directories cannot be created or listed
     */
    public static Path allocate(Path audioDirectory, Instant now) throws IOException {
        Objects.requireNonNull(audioDirectory, "audioDirectory must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Path takes = takesDirectory(audioDirectory);
        Files.createDirectories(takes);
        return allocate(takes, now, highestOrdinal(takes) + 1);
    }

    /**
     * Claims {@code takes/<stamp>_take-<ordinal>}, bumping the ordinal on each
     * {@link FileAlreadyExistsException} until a directory is created.
     * Package-private so the collision loop can be exercised directly.
     */
    static Path allocate(Path takes, Instant now, int firstOrdinal) throws IOException {
        int ordinal = Math.max(1, firstOrdinal);
        while (true) {
            Path candidate = takes.resolve(takeDirectoryName(now, ordinal));
            try {
                return Files.createDirectory(candidate);
            } catch (FileAlreadyExistsException e) {
                ordinal++;
            }
        }
    }

    /** Returns {@code <audioDirectory>/takes}. */
    public static Path takesDirectory(Path audioDirectory) {
        return Objects.requireNonNull(audioDirectory, "audioDirectory must not be null")
                .resolve(TAKES_DIR_NAME);
    }

    /** Renders {@code <stamp>_take-NNNN} for {@code now} and {@code ordinal}, in ASCII digits whatever the default locale. */
    public static String takeDirectoryName(Instant now, int ordinal) {
        Objects.requireNonNull(now, "now must not be null");
        if (ordinal <= 0) {
            throw new IllegalArgumentException("ordinal must be positive: " + ordinal);
        }
        // Locale.ROOT: %d localises its digits, and the default FORMAT locale
        // may use non-ASCII ones, which the take-name grammar does not admit.
        return stamp(now) + ORDINAL_SEPARATOR + String.format(Locale.ROOT, "%04d", ordinal);
    }

    /** Renders the UTC stamp {@code yyyy-MM-dd'T'HH-mm-ss} of {@code instant}. */
    public static String stamp(Instant instant) {
        return STAMP_FORMAT.format(Objects.requireNonNull(instant, "instant must not be null"));
    }

    /** Returns whether {@code name} has the take-directory shape. */
    public static boolean isTakeDirectoryName(String name) {
        return name != null && TAKE_NAME.matcher(name).matches();
    }

    /** Returns the ordinal encoded in a take-directory name, or empty if the name has another shape. */
    public static OptionalInt ordinalOf(String name) {
        if (name == null) {
            return OptionalInt.empty();
        }
        Matcher m = TAKE_NAME.matcher(name);
        if (!m.matches()) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(m.group(2)));
        } catch (NumberFormatException overflow) {
            return OptionalInt.empty();
        }
    }

    private static int highestOrdinal(Path takes) throws IOException {
        int highest = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(takes)) {
            for (Path entry : entries) {
                Path name = entry.getFileName();
                if (name == null) {
                    continue;
                }
                OptionalInt ordinal = ordinalOf(name.toString());
                if (ordinal.isPresent()) {
                    highest = Math.max(highest, ordinal.getAsInt());
                }
            }
        }
        return highest;
    }
}
