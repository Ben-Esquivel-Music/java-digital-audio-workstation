package com.benesquivelmusic.daw.sdk.mastering.album;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validates and formats International Standard Recording Codes (ISRCs)
 * per ISO 3901.
 *
 * <p>An ISRC is composed of four parts:</p>
 * <ol>
 *     <li>{@code CC}     — 2-letter ISO 3166-1 alpha-2 country code</li>
 *     <li>{@code XXX}    — 3-character alphanumeric registrant code</li>
 *     <li>{@code YY}     — 2-digit year of reference</li>
 *     <li>{@code NNNNN}  — 5-digit designation code (unique within the year)</li>
 * </ol>
 *
 * <p>The canonical hyphenated form used by this DAW is
 * {@code CC-XXX-YY-NNNNN} (15 characters total). The 12-character "tight"
 * form {@code CCXXXYYNNNNN} (the form embedded into audio files and
 * pressed to disc) is also accepted by {@link #isValid(String)} after
 * normalization.</p>
 *
 * <p>This class is final and stateless; all methods are static.</p>
 */
public final class IsrcValidator {

    /** Canonical hyphenated ISRC pattern: {@code CC-XXX-YY-NNNNN}. */
    private static final Pattern HYPHENATED = Pattern.compile(
            "^[A-Z]{2}-[A-Z0-9]{3}-\\d{2}-\\d{5}$");

    /** Tight (no-hyphen) ISRC pattern: {@code CCXXXYYNNNNN}. */
    private static final Pattern TIGHT = Pattern.compile(
            "^[A-Z]{2}[A-Z0-9]{3}\\d{2}\\d{5}$");

    private IsrcValidator() {
        // utility class
    }

    /**
     * Returns {@code true} if {@code isrc} parses as a valid ISRC in either
     * the hyphenated ({@code CC-XXX-YY-NNNNN}) or tight ({@code CCXXXYYNNNNN})
     * form. Case is not significant — values are upper-cased before matching.
     *
     * @param isrc the candidate ISRC string (may be {@code null})
     * @return whether the string is a syntactically valid ISRC
     */
    public static boolean isValid(String isrc) {
        if (isrc == null) {
            return false;
        }
        String upper = isrc.toUpperCase();
        return HYPHENATED.matcher(upper).matches()
                || TIGHT.matcher(upper).matches();
    }

    /**
     * Normalizes an ISRC into the canonical hyphenated form
     * {@code CC-XXX-YY-NNNNN}.
     *
     * <p>Accepts either the tight or hyphenated form (case-insensitive)
     * and inserts hyphens at the standard positions. Whitespace is
     * stripped before parsing.</p>
     *
     * @param isrc the candidate ISRC string
     * @return the canonical hyphenated representation, in upper case
     * @throws IllegalArgumentException if {@code isrc} is not a valid ISRC
     * @throws NullPointerException     if {@code isrc} is {@code null}
     */
    public static String normalize(String isrc) {
        Objects.requireNonNull(isrc, "isrc must not be null");
        String compact = isrc.replace("-", "").replace(" ", "").toUpperCase();
        if (!TIGHT.matcher(compact).matches()) {
            throw new IllegalArgumentException(
                    "Invalid ISRC: '" + isrc + "' — expected CC-XXX-YY-NNNNN (ISO 3901)");
        }
        return compact.substring(0, 2) + '-'
                + compact.substring(2, 5) + '-'
                + compact.substring(5, 7) + '-'
                + compact.substring(7, 12);
    }

    /**
     * Performs progressive ("real-time") formatting of partial ISRC input
     * for use behind a text-field key listener. The input is uppercased,
     * stripped of existing hyphens, truncated to 12 characters, and
     * hyphens are reinserted at the {@code 2}, {@code 5}, and {@code 7}
     * boundaries — even if the value is incomplete or contains characters
     * that would not pass strict validation. Callers can therefore feed
     * each keystroke through this method to produce nicely formatted text.
     *
     * @param partial the partial user input (may be {@code null})
     * @return the auto-hyphenated string (never {@code null}; possibly empty)
     */
    public static String autoHyphenate(String partial) {
        if (partial == null || partial.isEmpty()) {
            return "";
        }
        String compact = partial.replace("-", "").replace(" ", "").toUpperCase();
        if (compact.length() > 12) {
            compact = compact.substring(0, 12);
        }
        StringBuilder sb = new StringBuilder(15);
        for (int i = 0; i < compact.length(); i++) {
            if (i == 2 || i == 5 || i == 7) {
                sb.append('-');
            }
            sb.append(compact.charAt(i));
        }
        return sb.toString();
    }

    /**
     * Computes the next ISRC in a sequence by incrementing the 5-digit
     * designation code (the trailing {@code NNNNN} group). The country,
     * registrant, and year segments are preserved.
     *
     * <p>For example, {@code US-RC1-25-00042} → {@code US-RC1-25-00043}.</p>
     *
     * @param previous the prior ISRC (any accepted form)
     * @return the next ISRC, in canonical hyphenated form
     * @throws IllegalArgumentException if {@code previous} is not a valid ISRC,
     *                                  or if the designation would overflow {@code 99999}
     * @throws NullPointerException     if {@code previous} is {@code null}
     */
    public static String next(String previous) {
        String normalized = normalize(previous);
        int designation = Integer.parseInt(normalized.substring(10, 15));
        if (designation >= 99_999) {
            throw new IllegalArgumentException(
                    "ISRC designation overflow: " + normalized
                            + " — start a new year or registrant block");
        }
        return normalized.substring(0, 10)
                + String.format("%05d", designation + 1);
    }
}
