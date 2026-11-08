package com.benesquivelmusic.daw.core.persistence;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The identity of a project folder, as the {@link CheckpointManager} tells its
 * folders apart (story 333; Persistence Integrity book §4.2, §6.1): it runs
 * one serial writer per folder, and two generations whose folders are the
 * {@linkplain #sameFolder same folder} share one writer, so two names of one
 * folder — a drive letter and its UNC path, a junction, a symbolic link,
 * another letter case — never write it at once.
 *
 * <p>A fingerprint is read with I/O on that folder's own path only, never on
 * another folder's ({@link #of}, on the folder's checkpoint writer, off the
 * owner's thread), and two fingerprints are compared without any I/O, so a
 * folder on a volume that hangs never holds the writer of another folder.
 * Comparing two folders by opening both ({@link Files#isSameFile}) would.</p>
 *
 * <p>Two folders are the same when either key matches: a transient failure
 * to read one folder's identity never splits it across two writers while its
 * real path still matches. A false "same" — on Windows, two folders on one
 * volume that share a creation instant and a name, such as a copy made with
 * its timestamps kept — only serialises two folders on one writer, which is
 * harmless for checkpoints; that is why Save As ({@code ProjectFolderCopy}),
 * asking whether a target lies inside the open folder, and the reopen of a
 * folder whose lease is retiring ({@code ProjectManager}), asking whether it
 * is that lease's folder, ask the file system ({@link Files#isSameFile}) and
 * do not use this identity. A false
 * "different" needs one folder whose two names disagree on its volume's
 * serial number, its creation instant, its real name and its real path — or
 * one whose identity could not be read under one of its names while its two
 * real paths differ (a share name and a drive letter).</p>
 *
 * @param identity the folder's identity on its volume, or empty when it could
 *                 not be read: the file key where the file system has one
 *                 (POSIX: its device and inode); otherwise — Windows, where
 *                 {@link BasicFileAttributes#fileKey()} is {@code null} — its
 *                 volume's serial number ({@code volume:vsn}, or the store's
 *                 name where there is none), its creation instant to the
 *                 millisecond and its real name in lower case
 * @param realPath the folder's real path ({@link Path#toRealPath}: junctions,
 *                 symbolic links and letter case resolved), or its absolute
 *                 normalised path when that cannot be read
 */
record FolderFingerprint(Optional<Object> identity, String realPath) {

    private static final Logger LOG = Logger.getLogger(FolderFingerprint.class.getName());

    /** Reads the identity of a folder; a test fails it to pin the retry and the fall-back to the real path. */
    @FunctionalInterface
    interface IdentityReader {

        /** @throws IOException if {@code folder}'s identity cannot be read */
        Object read(Path folder) throws IOException;
    }

    FolderFingerprint {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(realPath, "realPath must not be null");
    }

    /**
     * Reads the fingerprint of {@code projectDirectory}, off the owner's
     * thread: it may block on the folder's own volume, never on another
     * folder's. The identity is read once more when the first read fails,
     * then left empty; it never throws.
     *
     * @param projectDirectory the folder
     * @return its fingerprint
     */
    static FolderFingerprint of(Path projectDirectory) {
        return of(projectDirectory, FolderFingerprint::readIdentity);
    }

    /** {@link #of(Path)}, with the reader of the identity (test seam). */
    static FolderFingerprint of(Path projectDirectory, IdentityReader reader) {
        Objects.requireNonNull(projectDirectory, "projectDirectory must not be null");
        Objects.requireNonNull(reader, "reader must not be null");
        Path real = realPathOf(projectDirectory);
        Optional<Object> identity = identityOf(real, reader);
        if (identity.isEmpty()) {
            identity = identityOf(real, reader);
        }
        if (identity.isEmpty()) {
            LOG.fine(() -> "The identity of " + real + " could not be read; its checkpoints are told apart by its"
                    + " real path alone");
        }
        return new FolderFingerprint(identity, real.toString());
    }

    /**
     * Whether {@code other} names the same folder: both identities were read
     * and are equal, or the real paths are equal. No I/O.
     *
     * @param other the other folder's fingerprint
     * @return whether the two are one folder
     */
    boolean sameFolder(FolderFingerprint other) {
        Objects.requireNonNull(other, "other must not be null");
        return (identity.isPresent() && identity.equals(other.identity)) || realPath.equals(other.realPath);
    }

    private static Path realPathOf(Path projectDirectory) {
        try {
            return projectDirectory.toRealPath();
        } catch (IOException | RuntimeException unreadable) {
            LOG.log(Level.FINE, unreadable, () -> "The real path of " + projectDirectory + " could not be read");
            return projectDirectory.toAbsolutePath().normalize();
        }
    }

    private static Optional<Object> identityOf(Path folder, IdentityReader reader) {
        try {
            return Optional.of(Objects.requireNonNull(reader.read(folder), "the identity reader returned none"));
        } catch (IOException | RuntimeException unreadable) {
            LOG.log(Level.FINE, unreadable, () -> "The identity of " + folder + " could not be read");
            return Optional.empty();
        }
    }

    /** The file key, or on a file system without one the volume, the creation instant and the real name. */
    private static Object readIdentity(Path folder) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(folder, BasicFileAttributes.class);
        Object fileKey = attributes.fileKey();
        if (fileKey != null) {
            return fileKey;
        }
        FileStore store = Files.getFileStore(folder);
        Object volume = volumeOf(store);
        Path name = folder.getFileName();
        return List.of(volume, attributes.creationTime().toInstant().truncatedTo(ChronoUnit.MILLIS),
                name == null ? "" : name.toString().toLowerCase(Locale.ROOT));
    }

    /** The volume's serial number where the store has one (Windows), otherwise its name. */
    private static Object volumeOf(FileStore store) throws IOException {
        try {
            Object serial = store.getAttribute("volume:vsn");
            if (serial != null) {
                return serial;
            }
        } catch (UnsupportedOperationException | IllegalArgumentException noSerial) {
            // Not a Windows volume: its store's name stands for it.
        }
        return store.name();
    }
}
