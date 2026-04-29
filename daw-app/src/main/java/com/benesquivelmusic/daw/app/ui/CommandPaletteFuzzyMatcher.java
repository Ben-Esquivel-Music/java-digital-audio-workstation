package com.benesquivelmusic.daw.app.ui;

import java.util.Locale;
import java.util.Objects;

/**
 * CamelCase-aware fuzzy matcher for the {@link CommandPaletteView}.
 *
 * <p>Given a query string and a candidate label, computes a numeric score
 * indicating how well the query matches. The matcher returns the
 * {@linkplain #NO_MATCH sentinel} {@code -1} when the query cannot match
 * the candidate at all (i.e. the query characters do not appear in order).
 * A higher score indicates a better match.</p>
 *
 * <h2>Scoring</h2>
 * Matches accumulate points for:
 * <ul>
 *   <li><b>Word-boundary matches</b> — query characters that align with the
 *       start of a word (after a space, punctuation, or a lowercase→uppercase
 *       transition such as the {@code T} in {@code "newTrack"}). This makes
 *       typing the initials {@code nt} the highest-ranked match for
 *       <em>"<u>N</u>ew <u>T</u>rack."</em></li>
 *   <li><b>Consecutive matches</b> — adjacent characters in the candidate
 *       that match adjacent characters in the query (favors substring hits).</li>
 *   <li><b>Prefix matches</b> — extra bonus when the very first candidate
 *       character matches the first query character.</li>
 *   <li><b>Exact (case-insensitive) substring</b> — large bonus.</li>
 * </ul>
 * The empty query matches every candidate with score {@code 0}.
 */
public final class CommandPaletteFuzzyMatcher {

    /** Sentinel returned by {@link #score(String, String)} when no match. */
    public static final int NO_MATCH = -1;

    private CommandPaletteFuzzyMatcher() {
        // Utility class — no instances.
    }

    /**
     * Computes a fuzzy-match score for the given query against the candidate.
     *
     * @param query      the user's typed query (may be empty; must not be null)
     * @param candidate  the candidate label to score (must not be null)
     * @return a non-negative score, or {@link #NO_MATCH} if no match
     */
    public static int score(String query, String candidate) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        if (query.isEmpty()) {
            return 0;
        }
        String q = query.toLowerCase(Locale.ROOT);
        String c = candidate.toLowerCase(Locale.ROOT);

        int score = 0;

        // Bonus for an exact case-insensitive substring hit, scaled by where
        // it lands. A prefix or word-boundary substring is dramatically more
        // useful than a random mid-word substring (which the consecutive-match
        // bonuses below already rank well enough on their own).
        int substringIdx = c.indexOf(q);
        if (substringIdx >= 0) {
            if (substringIdx == 0) {
                score += 150; // strong prefix bonus
            } else if (isBoundary(candidate, substringIdx)) {
                score += 80; // word-start substring (e.g. "tr" hitting "Track")
            } else {
                score += 10; // mid-word substring is worth only a little
            }
            // Fall through so word-boundary scoring can also contribute.
        }

        int qi = 0;
        boolean lastMatched = false;
        for (int i = 0; i < candidate.length() && qi < q.length(); i++) {
            char cc = c.charAt(i);
            char qc = q.charAt(qi);
            if (cc == qc) {
                int gain = 1;
                boolean atStart = (i == 0);
                boolean afterBoundary = i > 0 && isBoundary(candidate, i);
                if (atStart) {
                    gain += 30;
                } else if (afterBoundary) {
                    gain += 25;
                }
                if (lastMatched) {
                    gain += 5;
                }
                score += gain;
                qi++;
                lastMatched = true;
            } else {
                lastMatched = false;
            }
        }
        if (qi < q.length()) {
            return NO_MATCH;
        }
        return score;
    }

    /**
     * Returns whether position {@code i} in {@code candidate} marks a
     * word boundary — i.e., it is preceded by a non-letter-or-digit character,
     * or it is an uppercase letter following a lowercase letter (camelCase).
     */
    private static boolean isBoundary(String candidate, int i) {
        char prev = candidate.charAt(i - 1);
        char curr = candidate.charAt(i);
        if (!Character.isLetterOrDigit(prev)) {
            return true;
        }
        if (Character.isLowerCase(prev) && Character.isUpperCase(curr)) {
            return true;
        }
        return false;
    }
}
