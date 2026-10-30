package com.benesquivelmusic.daw.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The former roots as pure path arithmetic (story 333, ENG-S10; Persistence
 * Integrity book §3.3; PR #989 review 5704604621): each folder of a chain of
 * Save As copies keeps its own held set — the newest folder the newest copy's
 * files, an older one its set intersected with every later copy's files — a
 * folder the chain revisits appears once, at its latest place with its latest
 * set, and a copy taken from no folder carries every set the same way with no
 * new root.
 */
class FormerRootsTest {

    @TempDir
    Path tmp;

    @Test
    void eachFolderOfAChainKeepsItsOwnSet() {
        Path songA = tmp.resolve("Song A");
        Path songB = tmp.resolve("Song B");

        FormerRoots roots = FormerRoots.NONE
                .thenCopiedFrom(songA, Set.of(Path.of("audio/x.wav"), Path.of("audio/y.wav")))
                .thenCopiedFrom(songB, Set.of(Path.of("audio/y.wav"), Path.of("audio/b.wav")));

        assertThat(roots.roots()).extracting(FormerRoots.Root::folder).containsExactly(songA, songB);
        assertThat(roots.heldFrom(songA)).as("A's set intersected with the B-to-C copy's files")
                .containsExactly(Path.of("audio/y.wav"));
        assertThat(roots.heldFrom(songB)).as("the newest folder's set is its copy's files, nothing intersected")
                .containsExactlyInAnyOrder(Path.of("audio/y.wav"), Path.of("audio/b.wav"));
        assertThat(roots.heldFrom(tmp.resolve("Elsewhere"))).as("the copy took nothing from another folder")
                .isEmpty();
    }

    @Test
    void aFolderTheChainRevisitsKeepsOnlyItsLatestPlaceAndSet() {
        Path songA = tmp.resolve("Song A");
        Path songB = tmp.resolve("Song B");
        // A to B, A emptied, B to A, then A to C: the chain reads A, B, A.
        FormerRoots roots = FormerRoots.NONE
                .thenCopiedFrom(songA, Set.of(Path.of("audio/first.wav"), Path.of("audio/both.wav")))
                .thenCopiedFrom(songB, Set.of(Path.of("audio/both.wav"), Path.of("audio/second.wav")))
                .thenCopiedFrom(songA, Set.of(Path.of("audio/both.wav"), Path.of("audio/third.wav")));

        assertThat(roots.roots()).extracting(FormerRoots.Root::folder).containsExactly(songB, songA);
        assertThat(roots.heldFrom(songA)).as("the latest incarnation's set, the earlier one's dropped, not merged")
                .containsExactlyInAnyOrder(Path.of("audio/both.wav"), Path.of("audio/third.wav"));
        assertThat(roots.heldFrom(songB)).containsExactly(Path.of("audio/both.wav"));
    }

    /**
     * Folders compare as the platform's paths compare: on Windows a chain that
     * revisits "Song A" as "SONG A" revisits the same folder, so it appears
     * once, at its latest place with its latest set, and a lookup in any
     * letter case finds that set; elsewhere the two names are two folders.
     * The branch follows the OS the test runs on — no Windows-only assumption
     * either way.
     */
    @Test
    void aFolderTheChainRevisitsInAnotherLetterCaseIsTheSameFolderOnWindows() {
        Path songA = tmp.resolve("Song A");
        Path songB = tmp.resolve("Song B");
        Path songAUpperCase = tmp.resolve("SONG A");

        FormerRoots roots = FormerRoots.NONE
                .thenCopiedFrom(songA, Set.of(Path.of("audio/a.wav")))
                .thenCopiedFrom(songB, Set.of(Path.of("audio/a.wav")))
                .thenCopiedFrom(songAUpperCase, Set.of(Path.of("audio/c.wav")));

        if (OS.current() == OS.WINDOWS) {
            assertThat(roots.roots()).as("one folder in any letter case: once, at its latest place").hasSize(2);
            assertThat(roots.roots().getLast().folder()).isEqualTo(songA);
            assertThat(roots.heldFrom(tmp.resolve("song a"))).as("its latest set, found in another letter case")
                    .containsExactly(Path.of("audio/c.wav"));
        } else {
            assertThat(roots.roots()).as("names that differ in letter case are different folders").hasSize(3);
            assertThat(roots.heldFrom(tmp.resolve("song a"))).as("no folder of that name").isEmpty();
        }
    }

    @Test
    void theConstructorKeepsTheLatestEntryOfARepeatedFolder() {
        Path songA = tmp.resolve("Song A");
        Path songB = tmp.resolve("Song B");

        FormerRoots roots = new FormerRoots(List.of(
                new FormerRoots.Root(songA, Set.of(Path.of("old.wav"))),
                new FormerRoots.Root(songB, Set.of()),
                new FormerRoots.Root(songA.resolve("audio").resolve(".."), Set.of(Path.of("new.wav")))));

        assertThat(roots.roots()).extracting(FormerRoots.Root::folder).containsExactly(songB, songA);
        assertThat(roots.heldFrom(songA)).containsExactly(Path.of("new.wav"));
    }

    @Test
    void aCopyTakenFromNoFolderCarriesEveryRootWithOnlyTheFilesItHolds() {
        Path songA = tmp.resolve("Song A");
        Path songB = tmp.resolve("Song B");
        FormerRoots roots = FormerRoots.NONE
                .thenCopiedFrom(songA, Set.of(Path.of("audio/x.wav"), Path.of("audio/y.wav")))
                .thenCopiedFrom(songB, Set.of(Path.of("audio/x.wav"), Path.of("audio/y.wav"), Path.of("audio/b.wav")));

        FormerRoots emptyCopy = roots.carriedBy(Set.of());
        FormerRoots partialCopy = roots.carriedBy(Set.of(Path.of("audio/y.wav"), Path.of("audio/new.wav")));

        assertThat(emptyCopy.roots()).as("no new root").extracting(FormerRoots.Root::folder)
                .containsExactly(songA, songB);
        assertThat(emptyCopy.roots()).as("a copy that holds nothing holds nothing of any folder")
                .allSatisfy(root -> assertThat(root.heldFiles()).isEmpty());
        assertThat(partialCopy.heldFrom(songA)).containsExactly(Path.of("audio/y.wav"));
        assertThat(partialCopy.heldFrom(songB)).as("never the copy's own files passed through")
                .containsExactly(Path.of("audio/y.wav"));
    }

    @Test
    void aHeldFileOutsideItsFolderIsRefused() {
        Path songA = tmp.resolve("Song A");

        assertThatThrownBy(() -> new FormerRoots.Root(songA, Set.of(Path.of("..", "x.wav"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FormerRoots.Root(songA, Set.of(songA.resolve("x.wav"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FormerRoots.NONE.carriedBy(Set.of(Path.of(""))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new FormerRoots.Root(songA, Set.of(Path.of("audio", ".", "x.wav"))).holds(Path.of("audio/x.wav")))
                .as("held files are normalised").isTrue();
    }
}
