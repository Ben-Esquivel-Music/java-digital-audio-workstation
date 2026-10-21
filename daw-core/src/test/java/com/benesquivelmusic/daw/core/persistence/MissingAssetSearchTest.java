package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.recording.TakeDirectories;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Locate folder's search (Persistence Integrity book §4.3; decision D17):
 * one walk resolves every asset it can, ranked by the longest common
 * trailing path, with ties reported, links never followed, cancellable.
 */
class MissingAssetSearchTest {

    private static final BooleanSupplier NEVER = () -> false;
    /** A guard against a hung helper process, not a tolerance. */
    private static final long PROCESS_GUARD_SECONDS = 60;

    @TempDir
    Path tempDir;

    @Test
    void oneSearchResolvesEveryAssetItFindsAtAnyDepthAndReportsTheRestNotFound() throws IOException {
        Path root = tempDir.resolve("media");
        Path kick = file(root.resolve("drums").resolve("kick.wav"));
        Path vox = file(root.resolve("a").resolve("b").resolve("c").resolve("d").resolve("e").resolve("vox.wav"));
        Path take0 = file(root.resolve("T1").resolve("trk").resolve("segment-000.wav"));
        Path take1 = file(root.resolve("T1").resolve("trk").resolve("segment-001.wav"));
        Path gone = tempDir.resolve("gone");
        AssetReference kickAsset = single(gone.resolve("drums").resolve("kick.wav"));
        AssetReference voxAsset = single(gone.resolve("vox.wav"));
        AssetReference takeAsset = new AssetReference.Take(List.of(
                gone.resolve("T1").resolve("trk").resolve("segment-000.wav").toString(),
                gone.resolve("T1").resolve("trk").resolve("segment-001.wav").toString()));
        AssetReference absentAsset = single(gone.resolve("absent.wav"));

        Map<AssetReference, MissingAssetSearch.Match> matches = MissingAssetSearch.search(root,
                List.of(kickAsset, voxAsset, takeAsset, absentAsset, kickAsset), NEVER);

        assertThat(matches.keySet()).containsExactly(kickAsset, voxAsset, takeAsset, absentAsset);
        assertThat(matches.get(kickAsset)).isEqualTo(new MissingAssetSearch.Resolved(single(kick)));
        assertThat(matches.get(voxAsset)).isEqualTo(new MissingAssetSearch.Resolved(single(vox)));
        assertThat(matches.get(takeAsset)).isEqualTo(new MissingAssetSearch.Resolved(
                new AssetReference.Take(List.of(take0.toString(), take1.toString()))));
        assertThat(matches.get(absentAsset)).isEqualTo(new MissingAssetSearch.NotFound());
    }

    @Test
    void aFileNameMatchesIgnoringCaseAndAReferenceWrittenWithTheOtherSeparator() throws IOException {
        Path root = tempDir.resolve("media");
        Path kick = file(root.resolve("Drums").resolve("KICK.WAV"));
        AssetReference lowerCase = single(tempDir.resolve("gone").resolve("drums").resolve("kick.wav"));
        AssetReference windowsForm = new AssetReference.SingleFile("C:\\Users\\me\\Samples\\drums\\Kick.wav");

        Map<AssetReference, MissingAssetSearch.Match> matches =
                MissingAssetSearch.search(root, List.of(lowerCase, windowsForm), NEVER);

        assertThat(matches.get(lowerCase)).isEqualTo(new MissingAssetSearch.Resolved(single(kick)));
        assertThat(matches.get(windowsForm)).isEqualTo(new MissingAssetSearch.Resolved(single(kick)));
    }

    @Test
    void ofTwoFilesWithTheSameNameTheOneSharingTheLongerTrailingPathWins() throws IOException {
        Path root = tempDir.resolve("media");
        Path kicks = file(root.resolve("a").resolve("Kicks").resolve("kick.wav"));
        file(root.resolve("b").resolve("Snares").resolve("kick.wav"));
        AssetReference asset = single(tempDir.resolve("gone").resolve("Samples").resolve("kicks").resolve("kick.wav"));

        MissingAssetSearch.Match match = MissingAssetSearch.search(root, List.of(asset), NEVER).get(asset);

        assertThat(match).isEqualTo(new MissingAssetSearch.Resolved(single(kicks)));
    }

    @Test
    void anExactTieIsAmbiguousWithEveryTiedCandidateAndNothingIsGuessed() throws IOException {
        Path root = tempDir.resolve("media");
        Path first = file(root.resolve("a").resolve("kick.wav"));
        Path second = file(root.resolve("b").resolve("kick.wav"));
        file(root.resolve("c").resolve("deeper").resolve("kick.wav"));
        AssetReference asset = single(tempDir.resolve("gone").resolve("x").resolve("kick.wav"));

        MissingAssetSearch.Match match = MissingAssetSearch.search(root, List.of(asset), NEVER).get(asset);

        assertThat(match).isInstanceOfSatisfying(MissingAssetSearch.Ambiguous.class, ambiguous -> {
            assertThat(ambiguous.candidateCount()).isEqualTo(3);
            assertThat(ambiguous.candidates()).contains(first, second);
        });
        AssetReference twoWay = single(tempDir.resolve("gone").resolve("snare.wav"));
        file(root.resolve("a").resolve("snare.wav"));
        file(root.resolve("b").resolve("snare.wav"));
        assertThat(MissingAssetSearch.search(root, List.of(twoWay), NEVER).get(twoWay))
                .isEqualTo(new MissingAssetSearch.Ambiguous(List.of(root.resolve("a").resolve("snare.wav"),
                        root.resolve("b").resolve("snare.wav"))));
    }

    @Test
    void aTakeIsFoundWholeInItsOwnTakeFolderAndNeverInASiblingTakeOrAPartialCopy() throws IOException {
        String first = takeDirectory(1);
        String second = takeDirectory(2);
        String third = takeDirectory(3);
        Path takes = tempDir.resolve("media").resolve("audio").resolve("takes");
        for (String take : List.of(first, second)) {
            file(takes.resolve(take).resolve("track-1").resolve("segment-000.wav"));
            file(takes.resolve(take).resolve("track-1").resolve("segment-001.wav"));
        }
        // Only the first segment of the third take came along.
        file(takes.resolve(third).resolve("track-1").resolve("segment-000.wav"));
        Path gone = tempDir.resolve("gone").resolve("audio").resolve("takes");
        AssetReference secondTake = take(gone.resolve(second).resolve("track-1"));
        AssetReference thirdTake = take(gone.resolve(third).resolve("track-1"));

        Map<AssetReference, MissingAssetSearch.Match> matches =
                MissingAssetSearch.search(tempDir.resolve("media"), List.of(secondTake, thirdTake), NEVER);

        assertThat(matches.get(secondTake))
                .isEqualTo(new MissingAssetSearch.Resolved(take(takes.resolve(second).resolve("track-1"))));
        assertThat(matches.get(thirdTake))
                .as("the first and second takes hold every segment name of track-1, but are other takes")
                .isEqualTo(new MissingAssetSearch.NotFound());
    }

    @Test
    void twoCopiesOfOneTakeFolderAreAmbiguousAndATakeWithoutATakeFolderIsRankedByPathAlone()
            throws IOException {
        String take = takeDirectory(1);
        Path root = tempDir.resolve("media");
        Path copyA = root.resolve("copy-a").resolve("audio").resolve("takes").resolve(take).resolve("track-1");
        Path copyB = root.resolve("copy-b").resolve("audio").resolve("takes").resolve(take).resolve("track-1");
        for (Path folder : List.of(copyA, copyB)) {
            file(folder.resolve("segment-000.wav"));
            file(folder.resolve("segment-001.wav"));
        }
        file(root.resolve("hand-made").resolve("vocals").resolve("segment-000.wav"));
        file(root.resolve("hand-made").resolve("vocals").resolve("segment-001.wav"));
        AssetReference copied = take(tempDir.resolve("gone").resolve("audio").resolve("takes").resolve(take)
                .resolve("track-1"));
        AssetReference handMade = take(tempDir.resolve("gone").resolve("vocals"));

        Map<AssetReference, MissingAssetSearch.Match> matches =
                MissingAssetSearch.search(root, List.of(copied, handMade), NEVER);

        assertThat(matches.get(copied)).isEqualTo(new MissingAssetSearch.Ambiguous(List.of(copyA, copyB)));
        assertThat(matches.get(handMade)).as("the folder sharing the longer trailing path wins")
                .isEqualTo(new MissingAssetSearch.Resolved(take(root.resolve("hand-made").resolve("vocals"))));
    }

    @Test
    void theFileAtTheAssetsOwnReferenceIsNotACandidate() throws IOException {
        Path root = tempDir.resolve("media");
        Path own = file(root.resolve("library").resolve("Kicks").resolve("kick.wav"));
        Path backup = file(root.resolve("backup").resolve("kick.wav"));
        AssetReference unreadable = single(own);

        MissingAssetSearch.Match match = MissingAssetSearch.search(root, List.of(unreadable), NEVER).get(unreadable);

        assertThat(match).as("the own file shares the whole path, but the search looks elsewhere")
                .isEqualTo(new MissingAssetSearch.Resolved(single(backup)));
    }

    @Test
    void aSymbolicLinkToADirectoryIsNotFollowed() throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("media"));
        Path outside = file(tempDir.resolve("outside").resolve("linked").resolve("kick.wav")).getParent();
        Path link = root.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException notPermitted) {
            assumeTrue(false, "symbolic links cannot be created here: " + notPermitted);
        }
        assertThat(link.resolve("kick.wav")).as("fixture: through the link the file is there").isRegularFile();
        AssetReference asset = single(tempDir.resolve("gone").resolve("linked").resolve("kick.wav"));

        MissingAssetSearch.Match match = MissingAssetSearch.search(root, List.of(asset), NEVER).get(asset);

        assertThat(match).isEqualTo(new MissingAssetSearch.NotFound());
    }

    /**
     * Pins JDK behaviour, not the search's choice: {@code Files.walkFileTree}
     * never enters a Windows junction, with or without
     * {@code FileVisitOption.FOLLOW_LINKS}, so this test passes even if the
     * search followed links. Only {@link #aSymbolicLinkToADirectoryIsNotFollowed()}
     * pins the search's {@code FOLLOW_LINKS}-free walk — and only on a host
     * that can create symbolic links.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsAJunctionIsNotFollowed() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("media"));
        Path outside = file(tempDir.resolve("outside").resolve("linked").resolve("kick.wav")).getParent();
        Path junction = root.resolve("junction");
        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), outside.toString())
                .redirectErrorStream(true)
                .redirectOutput(tempDir.resolve("mklink.log").toFile())
                .start();
        assertThat(mklink.waitFor(PROCESS_GUARD_SECONDS, TimeUnit.SECONDS)).isTrue();
        assumeTrue(mklink.exitValue() == 0, "a junction cannot be created here");
        assertThat(junction.resolve("kick.wav")).as("fixture: through the junction the file is there").isRegularFile();
        AssetReference asset = single(tempDir.resolve("gone").resolve("linked").resolve("kick.wav"));

        MissingAssetSearch.Match match = MissingAssetSearch.search(root, List.of(asset), NEVER).get(asset);

        assertThat(match).isEqualTo(new MissingAssetSearch.NotFound());
    }

    @Test
    void cancellingStopsTheWalkAtTheNextEntry() throws IOException {
        Path root = tempDir.resolve("media");
        for (int i = 0; i < 40; i++) {
            file(root.resolve("folder-" + i).resolve("kick.wav"));
        }
        AssetReference asset = single(tempDir.resolve("gone").resolve("kick.wav"));
        AtomicInteger consulted = new AtomicInteger();
        BooleanSupplier cancelledAtTheThirdCheck = () -> consulted.incrementAndGet() >= 3;

        assertThatThrownBy(() -> MissingAssetSearch.search(root, List.of(asset), cancelledAtTheThirdCheck))
                .isInstanceOf(CancellationException.class);
        // 81 entries (the root, 40 folders, 40 files): an uncancelled walk consults the token at each.
        assertThat(consulted.get()).as("the walk stopped once the token said so").isLessThan(10);
    }

    @Test
    void anInterruptedThreadCancelsTheSearch() throws IOException {
        Path root = tempDir.resolve("media");
        file(root.resolve("kick.wav"));
        AssetReference asset = single(tempDir.resolve("gone").resolve("kick.wav"));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> MissingAssetSearch.search(root, List.of(asset), NEVER))
                    .isInstanceOf(CancellationException.class);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aFolderThatIsNotThereOrNotADirectoryIsAnError() throws IOException {
        Path aFile = file(tempDir.resolve("kick.wav"));
        AssetReference asset = single(tempDir.resolve("gone").resolve("kick.wav"));

        assertThatThrownBy(() -> MissingAssetSearch.search(aFile, List.of(asset), NEVER))
                .isInstanceOf(NotDirectoryException.class);
        assertThatThrownBy(() -> MissingAssetSearch.search(tempDir.resolve("nowhere"), List.of(asset), NEVER))
                .isInstanceOf(NoSuchFileException.class);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** Creates a file (the search never reads it) and its folders. */
    private static Path file(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "audio");
    }

    private static AssetReference single(Path path) {
        return new AssetReference.SingleFile(path.toString());
    }

    private static AssetReference take(Path folder) {
        return new AssetReference.Take(List.of(folder.resolve("segment-000.wav").toString(),
                folder.resolve("segment-001.wav").toString()));
    }

    /** A take directory name of the capture layout ({@code <stamp>_take-NNNN}). */
    private static String takeDirectory(int ordinal) {
        String name = TakeDirectories.takeDirectoryName(Instant.parse("2026-10-20T10:00:00Z"), ordinal);
        assertThat(TakeDirectories.isTakeDirectoryName(name)).as("fixture: %s is a take directory name", name)
                .isTrue();
        return name;
    }
}
