package com.benesquivelmusic.daw.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The identity of a project folder that keeps one checkpoint writer per
 * folder (story 333): two names of one folder — a drive letter and its UNC
 * name, a junction or a symbolic link, another letter case — are the same
 * folder; two folders are two; and an identity that cannot be read is read
 * once more, then left empty, and the folder is still told by its real path,
 * so a transient failure never splits one folder across two writers.
 */
class FolderFingerprintTest {

    @TempDir
    Path workspace;

    @Test
    void anIdentityWhoseFirstReadFailsIsReadOnceMore() throws IOException {
        Path folder = Files.createDirectories(workspace.resolve("Song A"));
        AtomicInteger reads = new AtomicInteger();

        FolderFingerprint fingerprint = FolderFingerprint.of(folder, read -> {
            if (reads.incrementAndGet() == 1) {
                throw new IOException("the volume did not answer in time");
            }
            return "volume 7, created at noon";
        });

        assertThat(reads).as("read once more after the failure").hasValue(2);
        assertThat(fingerprint.identity()).contains("volume 7, created at noon");
    }

    @Test
    void anIdentityThatCannotBeReadLeavesTheFolderToldByItsRealPath() throws IOException {
        Path folder = Files.createDirectories(workspace.resolve("Song A"));
        Path another = Files.createDirectories(workspace.resolve("Song B"));
        AtomicInteger reads = new AtomicInteger();

        FolderFingerprint unidentified = FolderFingerprint.of(folder, read -> {
            reads.incrementAndGet();
            throw new IOException("the volume went away");
        });

        assertThat(reads).as("one read and one more, then no further").hasValue(2);
        assertThat(unidentified.identity()).isEmpty();
        FolderFingerprint identified = FolderFingerprint.of(folder);
        assertThat(identified.identity()).as("fixture: the folder's identity reads normally").isPresent();
        assertThat(unidentified.sameFolder(identified)).as("still the same folder, by its real path").isTrue();
        assertThat(identified.sameFolder(unidentified)).isTrue();
        assertThat(unidentified.sameFolder(FolderFingerprint.of(another))).isFalse();
    }

    @Test
    void eitherKeyMatchingMakesTheSameFolderAndNeitherMakesTwo() {
        FolderFingerprint local = new FolderFingerprint(Optional.of("volume 7"), "C:\\Studio\\Song A");
        FolderFingerprint share = new FolderFingerprint(Optional.of("volume 7"), "\\\\studio-pc\\C$\\Studio\\Song A");
        FolderFingerprint unread = new FolderFingerprint(Optional.empty(), "C:\\Studio\\Song A");
        FolderFingerprint other = new FolderFingerprint(Optional.of("volume 9"), "C:\\Studio\\Song B");
        FolderFingerprint otherUnread = new FolderFingerprint(Optional.empty(), "C:\\Studio\\Song B");

        assertThat(local.sameFolder(share)).as("the identities match").isTrue();
        assertThat(share.sameFolder(local)).isTrue();
        assertThat(local.sameFolder(unread)).as("the real paths match").isTrue();
        assertThat(unread.sameFolder(local)).isTrue();
        assertThat(local.sameFolder(other)).isFalse();
        assertThat(unread.sameFolder(otherUnread)).as("two empty identities are no match").isFalse();
    }

    @Test
    void twoFoldersAreTwoFolders() throws IOException {
        Path songA = Files.createDirectories(workspace.resolve("Song A"));
        Path songB = Files.createDirectories(workspace.resolve("Song B"));

        assertThat(FolderFingerprint.of(songA).sameFolder(FolderFingerprint.of(songB))).isFalse();
        assertThat(FolderFingerprint.of(songA).sameFolder(FolderFingerprint.of(songA))).isTrue();
    }

    @Test
    void theUncNameOfALocalFolderIsTheSameFolderThoughItsRealPathDiffers() throws IOException {
        assumeTrue(isWindows(), "UNC names are a Windows file system's");
        Path folder = Files.createDirectories(workspace.resolve("Song A"));
        Path unc = uncNameOf(folder);
        assumeTrue(Files.isDirectory(unc), "the administrative share of the drive is reachable: " + unc);

        FolderFingerprint local = FolderFingerprint.of(folder);
        FolderFingerprint viaTheShare = FolderFingerprint.of(unc);

        assertThat(viaTheShare.realPath()).as("the real paths alone would tell two folders")
                .isNotEqualTo(local.realPath());
        assertThat(local.identity()).isPresent();
        assertThat(local.sameFolder(viaTheShare)).isTrue();
        assertThat(viaTheShare.sameFolder(local)).isTrue();
    }

    @Test
    void aJunctionOrASymbolicLinkHasTheFoldersRealPath() throws Exception {
        Path folder = Files.createDirectories(workspace.resolve("Song A"));
        Path alias = workspace.resolve("Song A alias");
        if (isWindows()) {
            Process command = new ProcessBuilder("cmd", "/c", "mklink", "/J", alias.toString(), folder.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(workspace.resolve("mklink.log").toFile())
                    .start();
            assertThat(command.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assumeTrue(command.exitValue() == 0, "junction creation requires file system support");
        } else {
            try {
                Files.createSymbolicLink(alias, folder);
            } catch (IOException | UnsupportedOperationException unsupported) {
                assumeTrue(false, "symbolic links are unavailable: " + unsupported);
            }
        }

        FolderFingerprint viaTheAlias = FolderFingerprint.of(alias);

        assertThat(viaTheAlias.realPath()).isEqualTo(FolderFingerprint.of(folder).realPath());
        assertThat(viaTheAlias.sameFolder(FolderFingerprint.of(folder))).isTrue();
    }

    @Test
    void anotherLetterCaseOfAWindowsFolderIsTheSameFolder() throws IOException {
        assumeTrue(isWindows(), "letter case names one folder on a Windows file system");
        Path folder = Files.createDirectories(workspace.resolve("Song A"));
        Path shouted = Path.of(folder.toString().toUpperCase(Locale.ROOT));

        assertThat(FolderFingerprint.of(shouted).realPath()).isEqualTo(FolderFingerprint.of(folder).realPath());
    }

    /** {@code \\localhost\C$\…}: the administrative-share name of a folder on a local drive. */
    static Path uncNameOf(Path localFolder) {
        Path absolute = localFolder.toAbsolutePath();
        String root = absolute.getRoot().toString();
        String drive = root.substring(0, 1);
        return Path.of("\\\\localhost\\" + drive + "$\\" + absolute.toString().substring(root.length()));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
