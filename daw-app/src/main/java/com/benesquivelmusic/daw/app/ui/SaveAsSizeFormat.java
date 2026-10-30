package com.benesquivelmusic.daw.app.ui;

import java.util.List;
import java.util.Locale;

/**
 * The sizes Save As shows (story 333; the studio engineer's ruling Q2): the
 * disk cell's base, 1024, and its units — below 1 MB whole {@code KB}, below
 * 1 GB whole {@code MB}, below 1 TB {@code <n.n> GB}, else {@code <n.n> TB}
 * — in {@link Locale#ROOT}, each number formatted on its own, so a copy's
 * readout moves ({@code 412 MB of 3.2 GB}) where the disk cell's whole
 * gigabytes would stand still. When the free-space refusal's two sizes come
 * out as the same text, both are written with two decimals. A number that
 * rounds to 1024 of its unit is written in the next unit instead, so a size
 * just under a unit's ceiling reads {@code 1.0 GB}, never {@code 1024 MB}
 * (the studio engineer's stage-5 N2). The disk cell's own format is not
 * changed.
 */
final class SaveAsSizeFormat {

    private static final double KIB = 1024.0;
    private static final double MIB = KIB * 1024.0;
    private static final double GIB = MIB * 1024.0;
    private static final double TIB = GIB * 1024.0;

    /** The units, smallest first: their size, their name and their decimals when one decimal count is not asked. */
    private static final double[] SCALES = {KIB, MIB, GIB, TIB};
    private static final String[] NAMES = {"KB", "MB", "GB", "TB"};
    private static final int[] DECIMALS = {0, 0, 1, 1};

    private SaveAsSizeFormat() {
    }

    /**
     * {@code bytes} as Save As shows it.
     *
     * @param bytes a byte count, not negative
     * @return the size, its unit after a space
     */
    static String format(long bytes) {
        return format(bytes, false);
    }

    /**
     * The free-space refusal's two sizes: what Save As needs and what the
     * volume has, each formatted on its own — both with two decimals when
     * they would otherwise read the same.
     *
     * @param needed what the copy needs
     * @param usable what the volume has
     * @return {@code [needed, usable]}
     */
    static List<String> neededAndUsable(long needed, long usable) {
        String neededText = format(needed);
        String usableText = format(usable);
        if (neededText.equals(usableText)) {
            return List.of(format(needed, true), format(usable, true));
        }
        return List.of(neededText, usableText);
    }

    private static String format(long bytes, boolean twoDecimals) {
        if (bytes < 0) {
            throw new IllegalArgumentException("a size is never negative: " + bytes);
        }
        int unit = bytes >= TIB ? 3 : bytes >= GIB ? 2 : bytes >= MIB ? 1 : 0;
        while (true) {
            String text = number(bytes / SCALES[unit], twoDecimals ? 2 : DECIMALS[unit]);
            if (unit < SCALES.length - 1 && Double.parseDouble(text) >= 1024.0) {
                unit++;
                continue;
            }
            return text + " " + NAMES[unit];
        }
    }

    private static String number(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }
}
