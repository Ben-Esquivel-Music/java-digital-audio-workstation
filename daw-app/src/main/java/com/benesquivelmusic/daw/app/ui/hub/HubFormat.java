package com.benesquivelmusic.daw.app.ui.hub;

import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Pure, toolkit-free helpers for the story-296 Project Hub cards: a humanised
 * byte count, a relative "last opened" label, and a path base-name. All methods
 * are static and side-effect-free.
 */
public final class HubFormat {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter SHORT_DATE =
            DateTimeFormatter.ofPattern("dd MMM", Locale.ROOT);

    private HubFormat() {
        // Static utility — no instances.
    }

    /**
     * The base (file) name of {@code path} — the single shared implementation
     * for the hub's card-name seeding (previously copied, with a divergent
     * fallback, across {@code ProjectHubView} and {@code ProjectHealthScanner}).
     * Falls back to the full path string for a root path whose
     * {@link Path#getFileName()} is {@code null}, so a card never seeds blank.
     *
     * @param path the path; must not be {@code null}
     * @return the file name, or the full path string when there is no file name
     */
    public static String baseName(Path path) {
        Path fileName = path.getFileName();
        return fileName != null ? fileName.toString() : path.toString();
    }

    /**
     * Humanises a byte count: one decimal at terabyte scale ({@code "X.X TB"}),
     * whole units below it ({@code "X GB"} / {@code "X MB"} / {@code "X KB"}),
     * and {@code "—"} for a negative (unknown) count.
     *
     * <p>Known intentional duplication of
     * {@code SessionStatusStripSkin.formatBytes} — the hub keeps its own copy
     * to avoid coupling the {@code hub} package to the {@code status} package.</p>
     *
     * @param bytes the byte count ({@code < 0} → unknown)
     * @return the humanised string
     */
    public static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "—";
        }
        double kb = bytes / 1024.0;
        double mb = kb / 1024.0;
        double gb = mb / 1024.0;
        double tb = gb / 1024.0;
        if (tb >= 1.0) {
            return String.format(Locale.ROOT, "%.1f TB", tb);
        }
        if (gb >= 1.0) {
            return String.format(Locale.ROOT, "%.0f GB", gb);
        }
        if (mb >= 1.0) {
            return String.format(Locale.ROOT, "%.0f MB", mb);
        }
        return String.format(Locale.ROOT, "%.0f KB", kb);
    }

    /**
     * Formats a "last opened" instant relative to {@code clock}: {@code "—"}
     * when {@code null}; {@code "HH:mm today"} on the same calendar day;
     * {@code "Yesterday"} for the previous day; {@code "N days ago"} when under
     * a week; otherwise a short {@code "dd MMM"} date. {@link Locale#ROOT} and
     * the system default zone.
     *
     * @param when  the instant, or {@code null} for unknown
     * @param clock the clock supplying "now"; must not be {@code null}
     * @return the relative label
     */
    public static String formatRelativeOpened(Instant when, InstantSource clock) {
        if (when == null) {
            return "—";
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDate day = when.atZone(zone).toLocalDate();
        LocalDate today = clock.instant().atZone(zone).toLocalDate();

        if (day.equals(today)) {
            return CLOCK.format(when.atZone(zone)) + " today";
        }
        if (day.equals(today.minusDays(1))) {
            return "Yesterday";
        }
        long daysBetween = ChronoUnit.DAYS.between(day, today);
        if (daysBetween > 0 && daysBetween < 7) {
            return daysBetween + " days ago";
        }
        return SHORT_DATE.format(when.atZone(zone));
    }
}
