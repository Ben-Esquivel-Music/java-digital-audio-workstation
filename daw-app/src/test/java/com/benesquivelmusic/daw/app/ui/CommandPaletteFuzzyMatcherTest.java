package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CommandPaletteFuzzyMatcher}. These are pure-logic tests
 * that do not require the JavaFX toolkit.
 */
class CommandPaletteFuzzyMatcherTest {

    @Test
    void emptyQueryMatchesEverythingWithZeroScore() {
        assertThat(CommandPaletteFuzzyMatcher.score("", "Anything")).isZero();
    }

    @Test
    void noMatchWhenCharactersMissing() {
        assertThat(CommandPaletteFuzzyMatcher.score("xyz", "New Track"))
                .isEqualTo(CommandPaletteFuzzyMatcher.NO_MATCH);
    }

    @Test
    void noMatchWhenOrderViolated() {
        // 'tn' cannot match "New Track" — there is no 't' before any 'n'.
        assertThat(CommandPaletteFuzzyMatcher.score("tn", "New Track"))
                .isEqualTo(CommandPaletteFuzzyMatcher.NO_MATCH);
    }

    @Test
    void caseInsensitive() {
        assertThat(CommandPaletteFuzzyMatcher.score("NEW", "new project"))
                .isPositive();
    }

    @Test
    void initialsAcrossWordsAreHighlyRanked_ntPrefersNewTrack() {
        // "nt" should match "New Track" significantly better than other
        // candidates like "Settings" (which doesn't even match).
        int newTrack = CommandPaletteFuzzyMatcher.score("nt", "New Track");
        int notification = CommandPaletteFuzzyMatcher.score("nt", "Toggle Notification History");
        int settings = CommandPaletteFuzzyMatcher.score("nt", "Settings");
        assertThat(newTrack).isPositive();
        assertThat(notification).isPositive();
        // "Settings" — has 'n' but no 't' after it, so no match.
        assertThat(settings).isEqualTo(CommandPaletteFuzzyMatcher.NO_MATCH);
        // Word-initial "n" + "t" wins over a 't' that comes mid-word.
        assertThat(newTrack).isGreaterThan(notification);
    }

    @Test
    void camelCaseAware_ntMatchesNewTrack() {
        // The "T" in camelCase newTrack is treated as a word boundary too.
        int score = CommandPaletteFuzzyMatcher.score("nt", "newTrack");
        assertThat(score).isPositive();
        assertThat(score)
                .as("camelCase boundary scoring should beat plain mid-word match")
                .isGreaterThan(CommandPaletteFuzzyMatcher.score("nt", "intent"));
    }

    @Test
    void contiguousSubstringWinsOverScattered() {
        int contiguous = CommandPaletteFuzzyMatcher.score("undo", "Undo");
        int scattered = CommandPaletteFuzzyMatcher.score("undo", "Up Next Down Out");
        assertThat(contiguous).isGreaterThan(scattered);
    }

    @Test
    void rankingProducesExpectedOrder_typedNT() {
        List<String> candidates = List.of(
                "New Track",
                "New Project",
                "Toggle Notification History",
                "Toggle Snap",
                "Settings");
        // Sort by descending score, hiding non-matches.
        List<String> ranked = candidates.stream()
                .filter(c -> CommandPaletteFuzzyMatcher.score("nt", c)
                        != CommandPaletteFuzzyMatcher.NO_MATCH)
                .sorted(Comparator.<String>comparingInt(
                        c -> CommandPaletteFuzzyMatcher.score("nt", c)).reversed())
                .toList();
        // "New Track" should rank first; it's the only candidate where "nt"
        // matches the initials of consecutive words.
        assertThat(ranked).first().isEqualTo("New Track");
        // All matches must contain both 'n' and 't' in order.
        assertThat(ranked).allSatisfy(c ->
                Stream.of("nt").forEach(q ->
                        assertThat(CommandPaletteFuzzyMatcher.score(q, c)).isPositive()));
    }
}
