package com.benesquivelmusic.daw.core.persistence;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The atomic-replace writer of story 331 (Persistence Integrity book §4.1):
 * a target is its old bytes or the complete new bytes, a failed write leaves
 * no temp, and the caller gets the exception that ended the attempt. A write
 * that asks deletes exactly the temps earlier, unfinished writes of its
 * target left (ruling M2), and one that asks follows a symbolic link to the
 * {@code .daw} file it leads to, or refuses it (ruling M3); one that does not
 * asks a link about itself whether it is read-only, never the file it leads
 * to (ruling N3). Faults are
 * injected through the writer's own seam — the temp's channel and the move —
 * on real files; no fake file system. Tests that make symbolic links skip
 * where the account may not create them.
 */
class AtomicFileWriterTest {

    private static final String OLD = "<daw-project>old</daw-project>";
    private static final String NEW = "<daw-project>new, and longer than the old one</daw-project>";

    /** The checkpoint file-name pattern the recovery scan and the session manager match. */
    private static final Pattern CHECKPOINT_NAME = Pattern.compile("checkpoint-(\\d+)-(\\d{8}T\\d{6})\\.daw");

    @TempDir
    Path dir;

    @Test
    void aWriteReplacesTheContentAndLeavesNoTemp() throws IOException {
        Path target = Files.writeString(dir.resolve("project.daw"), OLD);
        List<String> publishes = new CopyOnWriteArrayList<>();
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, to, options) -> {
                    AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, to, options);
                    publishes.add(to.getFileName() + " on " + Thread.currentThread().getName());
                });

        assertThat(writer.writeString(target, NEW)).isEqualTo(target);

        assertThat(Files.readString(target)).isEqualTo(NEW);
        assertThat(entries()).containsExactly(target);
        assertThat(publishes).as("one publish, made on the writing thread")
                .containsExactly("project.daw on " + Thread.currentThread().getName());
    }

    @Test
    void aStreamedBodyThatClosesItsStreamIsPublishedWhole() throws IOException {
        Path target = Files.writeString(dir.resolve("mix.dawz"), OLD);

        new AtomicFileWriter().write(target, out -> {
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("project.daw"));
                zip.write(NEW.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        });

        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(target))) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("project.daw");
            assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(NEW);
        }
        assertThat(entries()).containsExactly(target);
    }

    @Test
    void aCopyCarriesTheSourcesBytesAndLastModifiedTime() throws IOException {
        Path source = Files.writeString(dir.resolve("project.daw"), NEW);
        FileTime saved = FileTime.from(Instant.parse("2026-06-21T12:00:00Z"));
        Files.setLastModifiedTime(source, saved);
        Path replaced = Files.writeString(dir.resolve("asset.wav"), OLD);
        AtomicFileWriter writer = new AtomicFileWriter();

        writer.copy(source, replaced, AtomicFileWriter.Overwrite.REPLACE);
        Path created = writer.copy(source, dir.resolve("project.daw.v1.20260621-120000.bak"),
                AtomicFileWriter.Overwrite.NEVER);

        assertThat(Files.mismatch(replaced, source)).isEqualTo(-1L);
        assertThat(Files.mismatch(created, source)).isEqualTo(-1L);
        assertThat(Files.getLastModifiedTime(created)).isEqualTo(saved);
        assertThat(Files.getLastModifiedTime(replaced)).isEqualTo(saved);
        assertThat(entries()).containsExactlyInAnyOrder(source, replaced, created);
    }

    @Test
    void aFailureBetweenTheForcedTempAndTheMoveLeavesThePreviousTargetAndNoTemp() throws IOException {
        Path target = Files.writeString(dir.resolve("project.daw"), OLD);
        byte[] before = Files.readAllBytes(target);
        IOException crash = new IOException("injected failure in the crash window");
        AtomicReference<String> tempAtTheMove = new AtomicReference<>();
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, to, options) -> {
                    tempAtTheMove.set(Files.readString(temp));
                    throw crash;
                });

        assertThatThrownBy(() -> writer.writeString(target, NEW)).isSameAs(crash);

        assertThat(tempAtTheMove.get()).as("the window opens once the temp holds the whole content")
                .isEqualTo(NEW);
        assertThat(Files.readAllBytes(target)).as("the previous target, byte for byte").isEqualTo(before);
        assertThat(entries()).as("no temp is left").containsExactly(target);
    }

    @Test
    void aRefusedAtomicMoveWhoseFallbackFailsRethrowsTheFallbacksFailureWithTheRefusalSuppressed()
            throws IOException {
        Path target = Files.writeString(dir.resolve("checkpoint-001-20260621T120000.daw"), OLD);
        byte[] before = Files.readAllBytes(target);
        AtomicMoveNotSupportedException notAtomic = new AtomicMoveNotSupportedException("a", "b", "no atomic move");
        IOException fallbackFailure = new IOException("injected fallback failure");
        List<List<CopyOption>> attempts = new CopyOnWriteArrayList<>();
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, to, options) -> {
                    attempts.add(List.of(options));
                    throw Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)
                            ? notAtomic : fallbackFailure;
                });

        assertThatThrownBy(() -> writer.writeString(target, NEW)).isSameAs(fallbackFailure);

        assertThat(fallbackFailure.getSuppressed()).containsExactly(notAtomic);
        assertThat(attempts).containsExactly(
                List.of(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
                List.of(StandardCopyOption.REPLACE_EXISTING));
        assertThat(Files.readAllBytes(target)).isEqualTo(before);
        assertThat(entries()).containsExactly(target);
    }

    @Test
    void aRefusedAtomicMoveFallsBackToAPlainReplace() throws IOException {
        Path target = Files.writeString(dir.resolve("project.daw"), OLD);
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, to, options) -> {
                    if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                        throw new AtomicMoveNotSupportedException(temp.toString(), to.toString(), "network share");
                    }
                    AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, to, options);
                });

        writer.writeString(target, NEW);

        assertThat(Files.readString(target)).isEqualTo(NEW);
        assertThat(entries()).containsExactly(target);
    }

    @Test
    void aBodyThatThrowsLeavesTheTargetAndNoTempAndItsExceptionReachesTheCaller() throws IOException {
        Path target = Files.writeString(dir.resolve("mix.dawz"), OLD);
        byte[] before = Files.readAllBytes(target);
        IllegalStateException broken = new IllegalStateException("the model walk broke mid-write");

        assertThatThrownBy(() -> new AtomicFileWriter().write(target, out -> {
            out.write(NEW.getBytes(StandardCharsets.UTF_8));
            throw broken;
        })).isSameAs(broken);

        assertThat(Files.readAllBytes(target)).isEqualTo(before);
        assertThat(entries()).containsExactly(target);
    }

    @Test
    void neverRefusesAnExistingTargetAndLeavesItUntouched() throws IOException {
        Path source = Files.writeString(dir.resolve("project.daw"), NEW);
        Path existing = Files.writeString(dir.resolve("project.daw.v1.20260621-120000.bak"), OLD);
        byte[] before = Files.readAllBytes(existing);

        assertThatThrownBy(() -> new AtomicFileWriter().copy(source, existing, AtomicFileWriter.Overwrite.NEVER))
                .isInstanceOf(FileAlreadyExistsException.class);

        assertThat(Files.readAllBytes(existing)).isEqualTo(before);
        assertThat(entries()).containsExactlyInAnyOrder(source, existing);
    }

    @Test
    void neverRefusesATargetThatAppearsBetweenTheTempAndThePublish() throws IOException {
        Path source = Files.writeString(dir.resolve("project.daw"), NEW);
        Path backup = dir.resolve("project.daw.degraded.20260621-120000.bak");
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, to, options) -> {
                    Files.writeString(to, OLD);   // another writer takes the name first
                    AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, to, options);
                });

        assertThatThrownBy(() -> writer.copy(source, backup, AtomicFileWriter.Overwrite.NEVER))
                .isInstanceOf(FileAlreadyExistsException.class);

        assertThat(Files.readString(backup)).as("the other writer's file is never overwritten").isEqualTo(OLD);
        assertThat(entries()).containsExactlyInAnyOrder(source, backup);
    }

    @Test
    void aTransientAccessDenialOfTheAtomicMoveIsRetriedAndTheWriteLands() throws IOException {
        Path target = Files.writeString(dir.resolve("project.daw"), OLD);
        List<List<CopyOption>> attempts = new CopyOnWriteArrayList<>();
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, to, options) -> {
                    attempts.add(List.of(options));
                    if (attempts.size() == 1) {
                        throw new AccessDeniedException(to.toString(), null, "held by a virus scanner");
                    }
                    AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, to, options);
                });

        writer.writeString(target, NEW);

        assertThat(Files.readString(target)).isEqualTo(NEW);
        assertThat(attempts).as("two atomic attempts, no fallback").containsExactly(
                List.of(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
                List.of(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
        assertThat(entries()).containsExactly(target);
    }

    @Test
    void aSharingViolationThatOutlastsTheRetriesIsThrownAndNeverFallsBack() throws IOException {
        Path target = Files.writeString(dir.resolve("project.daw"), OLD);
        byte[] before = Files.readAllBytes(target);
        List<FileSystemException> refusals = new CopyOnWriteArrayList<>();
        List<List<CopyOption>> attempts = new CopyOnWriteArrayList<>();
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, to, options) -> {
                    attempts.add(List.of(options));
                    FileSystemException refused = new FileSystemException(to.toString(), null,
                            "The process cannot access the file because it is being used by another process");
                    refusals.add(refused);
                    throw refused;
                });

        assertThatThrownBy(() -> writer.writeString(target, NEW)).isSameAs(refusals.getLast());

        assertThat(attempts).as("the first attempt and every retry, all atomic")
                .hasSize(1 + AtomicFileWriter.TRANSIENT_DENIAL_RETRIES)
                .allSatisfy(options -> assertThat(options).contains(StandardCopyOption.ATOMIC_MOVE));
        assertThat(refusals.getLast().getSuppressed()).as("the refusal before it")
                .containsExactly(refusals.get(refusals.size() - 2));
        assertThat(Files.readAllBytes(target)).isEqualTo(before);
        assertThat(entries()).containsExactly(target);
    }

    @Test
    void aTransientAccessDenialOfANeverMoveIsRetriedAndTheCopyLands() throws IOException {
        Path source = Files.writeString(dir.resolve("project.daw"), NEW);
        Path backup = dir.resolve("project.daw.v1.20260621-120000.bak");
        List<List<CopyOption>> attempts = new CopyOnWriteArrayList<>();
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, to, options) -> {
                    attempts.add(List.of(options));
                    if (attempts.size() == 1) {
                        throw new AccessDeniedException(temp.toString(), null,
                                "the just-closed temp is held by a virus scanner");
                    }
                    AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, to, options);
                });

        writer.copy(source, backup, AtomicFileWriter.Overwrite.NEVER);

        assertThat(Files.mismatch(backup, source)).isEqualTo(-1L);
        assertThat(attempts).as("two moves with no options: it never replaces, and there is no fallback")
                .containsExactly(List.of(), List.of());
        assertThat(entries()).containsExactlyInAnyOrder(source, backup);
    }

    @Test
    void aNeverMoveThatFindsItsNameTakenIsNotRetried() throws IOException {
        Path source = Files.writeString(dir.resolve("project.daw"), NEW);
        Path backup = dir.resolve("project.daw.v1.20260621-120000.bak");
        FileAlreadyExistsException taken = new FileAlreadyExistsException(backup.toString());
        AtomicInteger attempts = new AtomicInteger();
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, to, options) -> {
                    attempts.incrementAndGet();
                    throw taken;
                });

        assertThatThrownBy(() -> writer.copy(source, backup, AtomicFileWriter.Overwrite.NEVER)).isSameAs(taken);

        assertThat(attempts).as("a lasting cause: the backup's suffix loop moves on at once").hasValue(1);
        assertThat(taken.getSuppressed()).isEmpty();
        assertThat(entries()).as("no temp is left").containsExactly(source);
    }

    @Test
    void aRefusalThatNamesALastingCauseIsNotRetried() throws IOException {
        Path target = Files.writeString(dir.resolve("project.daw"), OLD);
        NoSuchFileException gone = new NoSuchFileException("the directory went away");
        AtomicInteger attempts = new AtomicInteger();
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, to, options) -> {
                    attempts.incrementAndGet();
                    throw gone;
                });

        assertThatThrownBy(() -> writer.writeString(target, NEW)).isSameAs(gone);

        assertThat(attempts).hasValue(1);
        assertThat(Files.readString(target)).isEqualTo(OLD);
        assertThat(entries()).containsExactly(target);
    }

    @Test
    void theTempIsForcedAndClosedBeforeTheMove() throws IOException {
        Path target = Files.writeString(dir.resolve("project.daw"), OLD);
        List<String> journal = new CopyOnWriteArrayList<>();
        AtomicFileWriter writer = new AtomicFileWriter(
                temp -> new ObservedChannel(AtomicFileWriter.TEMP_CHANNEL_OPENER.open(temp), journal, () -> { }),
                (temp, to, options) -> {
                    journal.add("move");
                    AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, to, options);
                });

        writer.writeString(target, NEW);

        assertThat(journal).as("content, then force, then close, and only then the move")
                .startsWith("write")
                .endsWith("force(true)", "close", "move")
                .containsOnlyOnce("force(true)", "close", "move");
        assertThat(Files.readString(target)).isEqualTo(NEW);
    }

    @Test
    void aFailedForceLeavesThePreviousTargetAndNoTemp() throws IOException {
        Path target = Files.writeString(dir.resolve("project.daw"), OLD);
        byte[] before = Files.readAllBytes(target);
        List<String> journal = new CopyOnWriteArrayList<>();
        IOException failedFlush = new IOException("injected force failure: the disk refused the flush");
        AtomicFileWriter writer = new AtomicFileWriter(
                temp -> new ObservedChannel(AtomicFileWriter.TEMP_CHANNEL_OPENER.open(temp), journal,
                        () -> {
                            throw failedFlush;
                        }),
                (temp, to, options) -> {
                    journal.add("move");
                    AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, to, options);
                });

        assertThatThrownBy(() -> writer.writeString(target, NEW)).isSameAs(failedFlush);

        assertThat(journal).as("the temp was closed and never moved").contains("close").doesNotContain("move");
        assertThat(Files.readAllBytes(target)).isEqualTo(before);
        assertThat(entries()).containsExactly(target);
    }

    @Test
    void theTempIsADotPrefixedTmpSiblingThatNoReaderFilterMatches() throws IOException {
        Path target = dir.resolve("checkpoint-001-20260621T120000.daw");
        List<Path> temps = new CopyOnWriteArrayList<>();
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, to, options) -> {
                    temps.add(temp);
                    AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, to, options);
                });

        writer.writeString(target, OLD);
        writer.writeString(target, NEW);

        assertThat(temps).hasSize(2).allSatisfy(temp -> {
            String name = temp.getFileName().toString();
            assertThat(temp.getParent()).as("a sibling of the target").isEqualTo(dir);
            assertThat(name).matches("\\.checkpoint-001-20260621T120000\\.daw\\.[0-9a-f]{16}\\.tmp");
            assertThat(glob("checkpoint*").matches(temp.getFileName())).isFalse();
            assertThat(glob("*.daw").matches(temp.getFileName())).isFalse();
            assertThat(glob("{autosave*,checkpoint*,*.daw}").matches(temp.getFileName())).isFalse();
            assertThat(CHECKPOINT_NAME.matcher(name).matches()).isFalse();
            assertThat(AtomicFileWriter.publishedNameOf(name)).contains(target.getFileName().toString());
        });
        assertThat(temps.get(0)).as("a new name per write").isNotEqualTo(temps.get(1));
        assertThat(AtomicFileWriter.publishedNameOf(target.getFileName().toString())).isEmpty();
        assertThat(AtomicFileWriter.publishedNameOf(".project.daw.tmp")).isEmpty();
        assertThat(AtomicFileWriter.publishedNameOf("take.manifest.tmp")).isEmpty();
    }

    @Test
    void anInterruptedWriterLeavesNoTempAndThePreviousTarget() throws IOException {
        Path target = Files.writeString(dir.resolve("project.daw"), OLD);
        byte[] before = Files.readAllBytes(target);

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> new AtomicFileWriter().writeString(target, NEW))
                    .isInstanceOf(ClosedByInterruptException.class);
        } finally {
            Thread.interrupted();
        }

        assertThat(Files.readAllBytes(target)).isEqualTo(before);
        assertThat(entries()).containsExactly(target);
    }

    @Test
    void anInterruptDuringTheRetryPauseEndsTheRetriesAndKeepsTheInterrupt() throws IOException {
        Path target = Files.writeString(dir.resolve("project.daw"), OLD);
        AccessDeniedException denied = new AccessDeniedException("project.daw");
        AtomicInteger attempts = new AtomicInteger();
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, to, options) -> {
                    attempts.incrementAndGet();
                    Thread.currentThread().interrupt();
                    throw denied;
                });

        boolean interruptKept;
        try {
            assertThatThrownBy(() -> writer.writeString(target, NEW)).isSameAs(denied);
        } finally {
            interruptKept = Thread.interrupted();
        }

        assertThat(interruptKept).as("the interrupt status survives").isTrue();
        assertThat(attempts).as("no retry after the interrupt").hasValue(1);
        assertThat(denied.getSuppressed()).singleElement().isInstanceOf(InterruptedException.class);
        assertThat(Files.readString(target)).isEqualTo(OLD);
        assertThat(entries()).containsExactly(target);
    }

    @Test
    void aTempThatCouldNotBeCreatedIsNotDeleted() throws IOException {
        Path target = Files.writeString(dir.resolve("project.daw"), OLD);
        AtomicReference<Path> planted = new AtomicReference<>();
        AtomicFileWriter writer = new AtomicFileWriter(temp -> {
            planted.set(Files.writeString(temp, "another writer's file"));
            throw new FileAlreadyExistsException(temp.toString());
        }, AtomicFileWriter.FILE_SYSTEM_MOVER);

        assertThatThrownBy(() -> writer.writeString(target, NEW)).isInstanceOf(FileAlreadyExistsException.class);

        assertThat(planted.get()).as("the writer owns nothing it did not create").hasContent("another writer's file");
        assertThat(Files.readString(target)).isEqualTo(OLD);
    }

    @Test
    void aTempThatCannotBeDeletedIsAttachedToTheOriginalFailure() throws IOException {
        Path target = Files.writeString(dir.resolve("project.daw"), OLD);
        IOException crash = new IOException("injected failure in the crash window");
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, to, options) -> {
                    // A non-empty directory where the temp was: deleteIfExists refuses it.
                    Files.delete(temp);
                    Files.createDirectories(temp.resolve("held"));
                    throw crash;
                });

        assertThatThrownBy(() -> writer.writeString(target, NEW)).isSameAs(crash);

        assertThat(crash.getSuppressed()).singleElement().isInstanceOf(DirectoryNotEmptyException.class);
        assertThat(Files.readString(target)).isEqualTo(OLD);
    }

    @Test
    void textThatUtf8CannotEncodeFailsBeforeAnyTempExists() throws IOException {
        Path target = Files.writeString(dir.resolve("project.daw"), OLD);
        AtomicInteger opens = new AtomicInteger();
        AtomicFileWriter writer = new AtomicFileWriter(temp -> {
            opens.incrementAndGet();
            return AtomicFileWriter.TEMP_CHANNEL_OPENER.open(temp);
        }, AtomicFileWriter.FILE_SYSTEM_MOVER);

        assertThatThrownBy(() -> writer.writeString(target, "lone surrogate \uD800"))
                .isInstanceOf(CharacterCodingException.class);

        assertThat(opens).hasValue(0);
        assertThat(Files.readString(target)).isEqualTo(OLD);
        assertThat(entries()).containsExactly(target);
    }

    @Test
    void aReplaceKeepsTheExistingTargetsPosixPermissionBits() throws IOException {
        assumePosixPermissions();
        Path target = dir.resolve("project.daw");
        AtomicFileWriter writer = new AtomicFileWriter();

        // No umask gives a new file both modes, so at least one of them is never the directory's default.
        for (String mode : List.of("rw-r-----", "rw----r--")) {
            Files.writeString(target, OLD);
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString(mode));

            writer.writeString(target, NEW);
            assertThat(modeOf(target)).as("%s after a string replace", mode).isEqualTo(mode);

            writer.write(target, out -> out.write(OLD.getBytes(StandardCharsets.UTF_8)));
            assertThat(modeOf(target)).as("%s after a streamed write", mode).isEqualTo(mode);
            assertThat(Files.readString(target)).isEqualTo(OLD);
        }
        assertThat(entries()).containsExactly(target);
    }

    @Test
    void aCopyCarriesTheSourcesPosixPermissionBitsInEitherMode() throws IOException {
        assumePosixPermissions();
        Path source = dir.resolve("project.daw");
        Path replaced = dir.resolve("asset.wav");
        AtomicFileWriter writer = new AtomicFileWriter();
        List<Path> backups = new ArrayList<>();

        // No umask gives a new file both modes, so at least one of them is never the directory's default.
        for (String mode : List.of("rw-------", "rw-r-----")) {
            Files.writeString(source, NEW);
            Files.setPosixFilePermissions(source, PosixFilePermissions.fromString(mode));
            Files.writeString(replaced, OLD);
            Files.setPosixFilePermissions(replaced, PosixFilePermissions.fromString("rw-r--r--"));

            Path backup = writer.copy(source, dir.resolve("project.daw.v" + (backups.size() + 1) + ".bak"),
                    AtomicFileWriter.Overwrite.NEVER);
            backups.add(backup);
            writer.copy(source, replaced, AtomicFileWriter.Overwrite.REPLACE);

            assertThat(modeOf(backup)).as("%s copied to a new name", mode).isEqualTo(mode);
            assertThat(modeOf(replaced)).as("%s copied over a rw-r--r-- target", mode).isEqualTo(mode);
            assertThat(Files.mismatch(replaced, source)).isEqualTo(-1L);
        }
        assertThat(entries()).containsExactlyInAnyOrder(source, replaced, backups.get(0), backups.get(1));
    }

    @Test
    void aNewTargetGetsTheDirectorysDefaultPermissions() throws IOException {
        assumePosixPermissions();

        Path created = new AtomicFileWriter().writeString(dir.resolve("project.daw"), NEW);

        assertThat(modeOf(created)).as("what any new file in the directory gets, never a temp file's rw-------")
                .isEqualTo(modeOf(Files.createFile(dir.resolve("plain.daw"))));
    }

    @Test
    void aTargetThatIsALinkIsReplacedWithTheDirectorysDefaultsNotTheLinksOwnBits() throws IOException {
        assumePosixPermissions();
        Path linked = Files.writeString(dir.resolve("delivered.daw"), OLD);
        Files.setPosixFilePermissions(linked, PosixFilePermissions.fromString("rw-r-----"));
        Path target = Files.createSymbolicLink(dir.resolve("project.daw"), linked.getFileName());

        new AtomicFileWriter().writeString(target, NEW);

        assertThat(Files.isSymbolicLink(target)).as("the rename replaces the link itself").isFalse();
        assertThat(modeOf(target)).as("a link's rwxrwxrwx is never carried")
                .isEqualTo(modeOf(Files.createFile(dir.resolve("plain.daw"))));
        assertThat(Files.readString(linked)).isEqualTo(OLD);
    }

    @Test
    void aReadOnlyTargetRefusesEveryReplaceAndNothingIsStaged() throws IOException {
        Path target = Files.writeString(dir.resolve("project.daw"), OLD);
        byte[] before = Files.readAllBytes(target);
        Path source = Files.writeString(dir.resolve("asset.wav"), NEW);
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger moves = new AtomicInteger();
        AtomicFileWriter writer = new AtomicFileWriter(temp -> {
            opens.incrementAndGet();
            return AtomicFileWriter.TEMP_CHANNEL_OPENER.open(temp);
        }, (temp, to, options) -> {
            moves.incrementAndGet();
            AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, to, options);
        });

        makeReadOnly(target, true);
        try {
            assumeFalse(Files.isWritable(target), "a superuser may write a read-only file");
            List<ThrowingCallable> replaces = List.of(
                    () -> writer.writeString(target, NEW),
                    () -> writer.writeString(target, NEW, AtomicFileWriter.ReplacePermission.TARGET),
                    () -> writer.write(target, out -> out.write(NEW.getBytes(StandardCharsets.UTF_8))),
                    () -> writer.write(target, out -> out.write(NEW.getBytes(StandardCharsets.UTF_8)),
                            AtomicFileWriter.ReplacePermission.TARGET),
                    () -> writer.copy(source, target, AtomicFileWriter.Overwrite.REPLACE));

            for (ThrowingCallable replace : replaces) {
                assertThatThrownBy(replace).isInstanceOfSatisfying(AccessDeniedException.class, denied -> {
                    assertThat(denied.getFile()).as("names the target").isEqualTo(target.toString());
                    assertThat(denied.getReason()).as("ruling H1b's words")
                            .isEqualTo("the file is read-only or not writable; it was not replaced");
                });
            }

            assertThat(opens).as("nothing is staged").hasValue(0);
            assertThat(moves).as("no move, so no transient-denial retry").hasValue(0);
            assertThat(Files.readAllBytes(target)).isEqualTo(before);
            assertThat(entries()).as("no temp sibling").containsExactlyInAnyOrder(target, source);

            assertThatThrownBy(() -> writer.copy(source, target, AtomicFileWriter.Overwrite.NEVER))
                    .as("NEVER is not asked about writability: its target must not exist")
                    .isInstanceOf(FileAlreadyExistsException.class);
            assertThat(Files.readAllBytes(target)).isEqualTo(before);
            assertThat(entries()).containsExactlyInAnyOrder(target, source);
        } finally {
            makeReadOnly(target, false);
        }
    }

    @Test
    void onWindowsOnlyTheReadOnlyFlagRefusesAReplaceNotTheAclCheck() throws IOException {
        assumeFalse(hasPosixPermissions(), "the Windows refusal");
        Path target = Files.writeString(dir.resolve("project.daw"), OLD);
        AclFileAttributeView acl = Files.getFileAttributeView(target, AclFileAttributeView.class);
        assumeTrue(acl != null, "the file system has ACLs");
        UserPrincipal user = dir.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        List<AclEntry> before = acl.getAcl();
        List<AclEntry> denyingWrites = new ArrayList<>();
        denyingWrites.add(AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(user)
                .setPermissions(AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA).build());
        denyingWrites.addAll(before);
        acl.setAcl(denyingWrites);
        try {
            // As a network share's ACL can deny a file its server lets the user write (JDK-8154915).
            assumeFalse(Files.isWritable(target), "Files.isWritable asks the file's ACL");

            new AtomicFileWriter().writeString(target, NEW);

            assertThat(Files.readString(target)).as("no DOS read-only flag, so the move decides").isEqualTo(NEW);
            assertThat(entries()).containsExactly(target);
        } finally {
            Files.getFileAttributeView(target, AclFileAttributeView.class).setAcl(before);
        }
    }

    @Test
    void aDirectoryPermissionReplaceTakesOverATargetTheUserCannotWrite() throws IOException {
        assumePosixPermissions();
        Path lock = Files.writeString(dir.resolve(".project.lock"), OLD);
        makeReadOnly(lock, true);
        assumeFalse(Files.isWritable(lock), "a superuser may write a read-only file");

        new AtomicFileWriter().writeString(lock, NEW, AtomicFileWriter.ReplacePermission.DIRECTORY);

        assertThat(Files.readString(lock)).isEqualTo(NEW);
        assertThat(modeOf(lock)).as("the replaced file's bits, carried").isEqualTo("r--r--r--");
        assertThat(entries()).containsExactly(lock);

        new AtomicFileWriter().write(lock, out -> out.write(OLD.getBytes(StandardCharsets.UTF_8)),
                AtomicFileWriter.ReplacePermission.DIRECTORY);

        assertThat(Files.readString(lock)).as("a streamed replace asks the directory too").isEqualTo(OLD);
        assertThat(modeOf(lock)).isEqualTo("r--r--r--");
        assertThat(entries()).containsExactly(lock);
    }

    @Test
    void windowsRefusesToReplaceAReadOnlyFileEvenWhenOnlyTheDirectoryIsAsked() throws IOException {
        assumeFalse(hasPosixPermissions(), "a POSIX rename replaces a read-only file");
        Path target = Files.writeString(dir.resolve(".project.lock"), OLD);
        byte[] before = Files.readAllBytes(target);
        AtomicInteger moves = new AtomicInteger();
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, to, options) -> {
                    moves.incrementAndGet();
                    AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, to, options);
                });

        makeReadOnly(target, true);
        try {
            assertThatThrownBy(() -> writer.writeString(target, NEW, AtomicFileWriter.ReplacePermission.DIRECTORY))
                    .isInstanceOf(AccessDeniedException.class);

            assertThat(moves).as("the move itself refused it, retried as a transient denial")
                    .hasValue(1 + AtomicFileWriter.TRANSIENT_DENIAL_RETRIES);
            assertThat(Files.readAllBytes(target)).isEqualTo(before);
            assertThat(entries()).as("no temp sibling").containsExactly(target);
        } finally {
            makeReadOnly(target, false);
        }
    }

    @Test
    void aReadOnlySourceIsStillCopied() throws IOException {
        Path source = Files.writeString(dir.resolve("project.daw"), NEW);
        makeReadOnly(source, true);
        try {
            Path backup = new AtomicFileWriter().copy(source, dir.resolve("project.daw.v1.20260621-120000.bak"),
                    AtomicFileWriter.Overwrite.NEVER);

            assertThat(Files.mismatch(backup, source)).isEqualTo(-1L);
            if (Files.getFileStore(dir).supportsFileAttributeView(PosixFileAttributeView.class)) {
                assertThat(modeOf(backup)).as("its bits, as COPY_ATTRIBUTES gave").isEqualTo("r--r--r--");
            }
        } finally {
            makeReadOnly(source, false);
        }
    }

    @Test
    void theRecognizerReadsBackExactlyTheNamesTheWriterGivesItsTemps() throws IOException {
        Path target = dir.resolve("mix.v2.final.daw");
        List<String> temps = new CopyOnWriteArrayList<>();
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, to, options) -> {
                    temps.add(temp.getFileName().toString());
                    AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, to, options);
                });

        writer.writeString(target, NEW);

        String produced = temps.getFirst();
        int nonceEnd = produced.length() - ".tmp".length();
        String nonce = produced.substring(nonceEnd - 16, nonceEnd);
        assertThat(AtomicFileWriter.publishedNameOf(produced)).as("a target name with dots of its own")
                .contains("mix.v2.final.daw");

        String temp = produced.replace(nonce, "0123456789abcdef");
        assertThat(AtomicFileWriter.publishedNameOf(temp)).contains("mix.v2.final.daw");
        assertThat(AtomicFileWriter.publishedNameOf("." + temp + ".fedcba9876543210.tmp"))
                .as("a temp of a temp's name gives that name: one layer, no more").contains(temp);
        assertThat(List.of(
                temp.substring(1),
                temp.replace("0123456789abcdef", "123456789abcdef"),
                temp.replace("0123456789abcdef", "0123456789abcdef0"),
                temp.replace("0123456789abcdef", "0123456789ABCDEF"),
                temp.replace("0123456789abcdef", "0123456789abcdeF"),
                temp.replace("0123456789abcdef", "0123456789abcdeg"),
                temp.substring(0, temp.length() - ".tmp".length()),
                temp + ".bak",
                temp.replace(".tmp", ".TMP"),
                temp.replace(".0123456789abcdef", "-0123456789abcdef"),
                "..0123456789abcdef.tmp",
                ""))
                .as("no leading dot; 15 or 17 digits; uppercase or non-hex digits; no .tmp; an extra suffix;"
                        + " no dot before the nonce; no target name; nothing")
                .allSatisfy(nearMiss -> assertThat(AtomicFileWriter.publishedNameOf(nearMiss)).as(nearMiss)
                        .isEmpty());
    }

    @Test
    void deletingEarlierTempsRemovesExactlyTheTargetsOwnTempsAndOnlyWhenAsked() throws IOException {
        Path target = Files.writeString(dir.resolve("song.dawz"), OLD);
        List<Path> earlier = List.of(
                Files.writeString(dir.resolve(".song.dawz.0123456789abcdef.tmp"), "an archive cut off"),
                Files.writeString(dir.resolve(".song.dawz.fedcba9876543210.tmp"), "another archive cut off"));
        List<Path> strangers = List.of(
                Files.writeString(dir.resolve(".other.dawz.0123456789abcdef.tmp"), "another archive's temp"),
                Files.writeString(dir.resolve(".song.dawz.0123456789abcde.tmp"), "15 hex digits"),
                // Other digits: Windows names are case-insensitive, so the first temp's in uppercase would be it.
                Files.writeString(dir.resolve(".song.dawz.00000000DEADBEEF.tmp"), "uppercase hex digits"),
                Files.writeString(dir.resolve("song.dawz.0123456789abcdef.tmp"), "no leading dot"),
                Files.writeString(dir.resolve(".song.dawz.0123456789abcdef.tmp.bak"), "an extra suffix"),
                Files.createDirectory(dir.resolve(".song.dawz.1111111111111111.tmp")));
        AtomicFileWriter writer = new AtomicFileWriter();

        writer.write(target, out -> out.write(OLD.getBytes(StandardCharsets.UTF_8)));
        assertThat(entries()).as("a write that does not ask deletes nothing").containsAll(earlier);

        try (WriterLog log = new WriterLog()) {
            writer.write(target, out -> out.write(NEW.getBytes(StandardCharsets.UTF_8)),
                    AtomicFileWriter.ReplacePermission.TARGET, AtomicFileWriter.ReplaceOption.DELETE_EARLIER_TEMPS);

            assertThat(log.messages(Level.INFO)).containsExactly("Deleted 2 temp file(s) that earlier, unfinished"
                    + " writes of song.dawz left in " + dir);
        }
        assertThat(Files.readString(target)).isEqualTo(NEW);
        List<Path> left = new ArrayList<>(strangers);
        left.add(target);
        assertThat(entries()).as("the target's two temps are gone, and nothing else is: the empty directory neither")
                .containsExactlyInAnyOrderElementsOf(left);
    }

    @Test
    void aWriteRefusedAsReadOnlyDeletesNoEarlierTemp() throws IOException {
        Path target = Files.writeString(dir.resolve("song.dawz"), OLD);
        Path earlier = Files.writeString(dir.resolve(".song.dawz.0123456789abcdef.tmp"), "an archive cut off");

        makeReadOnly(target, true);
        try {
            assumeFalse(Files.isWritable(target), "a superuser may write a read-only file");

            assertThatThrownBy(() -> new AtomicFileWriter().write(target,
                    out -> out.write(NEW.getBytes(StandardCharsets.UTF_8)), AtomicFileWriter.ReplacePermission.TARGET,
                    AtomicFileWriter.ReplaceOption.DELETE_EARLIER_TEMPS)).isInstanceOf(AccessDeniedException.class);

            assertThat(earlier).as("the refusal comes first, so nothing is deleted").exists();
            assertThat(Files.readString(target)).isEqualTo(OLD);
            assertThat(entries()).containsExactlyInAnyOrder(target, earlier);
        } finally {
            makeReadOnly(target, false);
        }
    }

    @Test
    void aWriterWhoseTempTheNextWriteDeletesFailsItsMoveWithTheMovesOwnExceptionAndTheTargetIsWhole()
            throws Exception {
        Path target = Files.writeString(dir.resolve("song.dawz"), OLD);
        CountDownLatch firstStaged = new CountDownLatch(1);
        CountDownLatch secondPublished = new CountDownLatch(1);
        AtomicReference<IOException> firstMoveFailure = new AtomicReference<>();
        AtomicFileWriter first = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, to, options) -> {
                    try {
                        AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, to, options);
                    } catch (IOException failed) {
                        firstMoveFailure.set(failed);
                        throw failed;
                    }
                });

        try (ExecutorService firstWriter = Executors.newSingleThreadExecutor()) {
            Future<Path> firstWrite = firstWriter.submit(() -> first.write(target, out -> {
                out.write("the first writer's archive".getBytes(StandardCharsets.UTF_8));
                out.flush();
                firstStaged.countDown();
                awaitLatch(secondPublished);
            }, AtomicFileWriter.ReplacePermission.TARGET, AtomicFileWriter.ReplaceOption.DELETE_EARLIER_TEMPS));
            awaitLatch(firstStaged);

            new AtomicFileWriter().write(target, out -> out.write(NEW.getBytes(StandardCharsets.UTF_8)),
                    AtomicFileWriter.ReplacePermission.TARGET, AtomicFileWriter.ReplaceOption.DELETE_EARLIER_TEMPS);
            secondPublished.countDown();

            assertThatThrownBy(() -> firstWrite.get(CheckpointTestSupport.TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .satisfies(failed -> assertThat(failed.getCause()).as("the first writer's own move failure")
                            .isSameAs(firstMoveFailure.get())
                            .isInstanceOf(NoSuchFileException.class));
        }
        assertThat(Files.readString(target)).as("the second writer's content, whole").isEqualTo(NEW);
        assertThat(entries()).as("no temp is left").containsExactly(target);
    }

    @Test
    void anEarlierTempThatRefusesItsDeleteIsLoggedAndTheWriteGoesOn() throws IOException {
        assumeFalse(hasPosixPermissions(),
                "Windows refuses to delete a read-only file; POSIX lets the folder's writer delete any file in it");
        Path target = Files.writeString(dir.resolve("song.dawz"), OLD);
        Path held = Files.writeString(dir.resolve(".song.dawz.0123456789abcdef.tmp"), "an archive cut off");
        Path gone = Files.writeString(dir.resolve(".song.dawz.fedcba9876543210.tmp"), "another archive cut off");

        makeReadOnly(held, true);
        try (WriterLog log = new WriterLog()) {
            new AtomicFileWriter().write(target, out -> out.write(NEW.getBytes(StandardCharsets.UTF_8)),
                    AtomicFileWriter.ReplacePermission.TARGET, AtomicFileWriter.ReplaceOption.DELETE_EARLIER_TEMPS);

            assertThat(Files.readString(target)).isEqualTo(NEW);
            assertThat(held).exists();
            assertThat(gone).doesNotExist();
            assertThat(log.messages(Level.FINE)).filteredOn(message -> message.startsWith("Could not delete "))
                    .singleElement().asString()
                    .startsWith("Could not delete " + held + ", a temp file an earlier write of song.dawz left: "
                            + AccessDeniedException.class.getName());
            assertThat(log.messages(Level.INFO)).containsExactly("Deleted 1 temp file(s) that earlier, unfinished"
                    + " writes of song.dawz left in " + dir);
        } finally {
            makeReadOnly(held, false);
        }
    }

    @Test
    void aFolderThatCannotBeListedIsLoggedAndTheWriteGoesOn() throws IOException {
        assumePosixPermissions();
        Path drop = Files.createDirectory(dir.resolve("drop"));
        Path earlier = Files.writeString(drop.resolve(".song.dawz.0123456789abcdef.tmp"), "an archive cut off");
        Path target = drop.resolve("song.dawz");

        // A write-only drop folder: its entries can be created and renamed, but not listed.
        Files.setPosixFilePermissions(drop, PosixFilePermissions.fromString("-wx------"));
        try (WriterLog log = new WriterLog()) {
            assumeFalse(Files.isReadable(drop), "a superuser may list a folder it cannot read");

            new AtomicFileWriter().write(target, out -> out.write(NEW.getBytes(StandardCharsets.UTF_8)),
                    AtomicFileWriter.ReplacePermission.TARGET, AtomicFileWriter.ReplaceOption.DELETE_EARLIER_TEMPS);

            assertThat(Files.readString(target)).as("the write landed").isEqualTo(NEW);
            assertThat(earlier).as("never found, so never deleted").exists();
            assertThat(log.messages(Level.FINE)).filteredOn(message -> message.startsWith("Could not look for "))
                    .singleElement().asString()
                    .startsWith("Could not look for temp files that earlier writes of song.dawz left in " + drop
                            + ": " + AccessDeniedException.class.getName());
            assertThat(log.messages(Level.INFO)).as("nothing was deleted").isEmpty();
        } finally {
            Files.setPosixFilePermissions(drop, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void anEarlierTempsNameOnALinkIsLeftAloneAndSoIsTheFileItLeadsTo() throws IOException {
        SaveTestSupport.assumeSymbolicLinks(dir);
        Path target = Files.writeString(dir.resolve("song.dawz"), OLD);
        Path linked = Files.writeString(dir.resolve("kept.txt"), "the link's file");
        Path link = Files.createSymbolicLink(dir.resolve(".song.dawz.0123456789abcdef.tmp"), linked.getFileName());

        new AtomicFileWriter().write(target, out -> out.write(NEW.getBytes(StandardCharsets.UTF_8)),
                AtomicFileWriter.ReplacePermission.TARGET, AtomicFileWriter.ReplaceOption.DELETE_EARLIER_TEMPS);

        assertThat(Files.isSymbolicLink(link)).as("a link is no temp of this writer's").isTrue();
        assertThat(linked).hasContent("the link's file");
        assertThat(Files.readString(target)).isEqualTo(NEW);
    }

    @Test
    void aFollowedLinkStaysAndTheDawFileItLeadsToIsReplaced() throws IOException {
        SaveTestSupport.assumeSymbolicLinks(dir);
        Path shared = Files.createDirectories(dir.resolve("shared"));
        Path song = Files.writeString(shared.resolve("Song.daw"), OLD);
        Path project = Files.createDirectories(dir.resolve("project"));
        Path relative = Path.of("..", "shared", "Song.daw");
        Path link = Files.createSymbolicLink(project.resolve("project.daw"), relative);
        Path backup = Files.writeString(project.resolve("project.daw.v1.20260621-120000.bak"), "the backup");
        Path real = song.toRealPath();
        List<Path> published = new CopyOnWriteArrayList<>();
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, to, options) -> {
                    assertThat(temp.getParent()).as("the temp is staged beside the file it replaces")
                            .isEqualTo(to.getParent());
                    AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, to, options);
                    published.add(to);
                });

        assertThat(writer.writeString(link, NEW, AtomicFileWriter.ReplacePermission.TARGET,
                AtomicFileWriter.ReplaceOption.FOLLOW_LINK_TO_DAW_FILE)).isEqualTo(link);
        assertThat(Files.readString(song)).isEqualTo(NEW);

        writer.copy(backup, link, AtomicFileWriter.Overwrite.REPLACE,
                AtomicFileWriter.ReplaceOption.FOLLOW_LINK_TO_DAW_FILE);
        assertThat(Files.readString(song)).as("a roll-back's copy follows the link too").isEqualTo("the backup");

        assertThat(published).as("both published onto the real file").containsExactly(real, real);
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(Files.readSymbolicLink(link)).isEqualTo(relative);
        assertThat(entriesOf(project)).as("no temp beside the link").containsExactlyInAnyOrder(link, backup);
        assertThat(entriesOf(shared)).as("no temp beside the real file").containsExactly(song);
    }

    @Test
    void aFollowedLinkKeepsThePosixBitsOfTheFileItLeadsTo() throws IOException {
        assumePosixPermissions();
        SaveTestSupport.assumeSymbolicLinks(dir);
        Path song = Files.writeString(dir.resolve("Song.daw"), OLD);
        Path link = Files.createSymbolicLink(dir.resolve("project.daw"), song.getFileName());

        // No umask gives a new file both modes, so at least one of them is never the directory's default.
        for (String mode : List.of("rw-r-----", "rw----r--")) {
            Files.setPosixFilePermissions(song, PosixFilePermissions.fromString(mode));

            new AtomicFileWriter().writeString(link, NEW, AtomicFileWriter.ReplacePermission.TARGET,
                    AtomicFileWriter.ReplaceOption.FOLLOW_LINK_TO_DAW_FILE);

            assertThat(modeOf(song)).as("%s, carried from the file the link leads to", mode).isEqualTo(mode);
            assertThat(Files.isSymbolicLink(link)).isTrue();
        }
        assertThat(entries()).containsExactlyInAnyOrder(song, link);
    }

    @Test
    void aFollowedLinkToAReadOnlyDawFileIsRefusedNamingThatFile() throws IOException {
        SaveTestSupport.assumeSymbolicLinks(dir);
        Path song = Files.writeString(dir.resolve("Song.daw"), OLD);
        Path link = Files.createSymbolicLink(dir.resolve("project.daw"), song.getFileName());
        String real = song.toRealPath().toString();

        makeReadOnly(song, true);
        try {
            assumeFalse(Files.isWritable(song), "a superuser may write a read-only file");

            assertThatThrownBy(() -> new AtomicFileWriter().writeString(link, NEW,
                    AtomicFileWriter.ReplacePermission.TARGET, AtomicFileWriter.ReplaceOption.FOLLOW_LINK_TO_DAW_FILE))
                    .isInstanceOfSatisfying(AccessDeniedException.class, denied -> {
                        assertThat(denied.getFile()).as("names the file the link leads to").isEqualTo(real);
                        assertThat(denied.getReason())
                                .isEqualTo("the file is read-only or not writable; it was not replaced");
                    });

            assertThat(Files.readString(song)).isEqualTo(OLD);
            assertThat(Files.isSymbolicLink(link)).isTrue();
            assertThat(entries()).as("no temp").containsExactlyInAnyOrder(song, link);
        } finally {
            makeReadOnly(song, false);
        }
    }

    @Test
    void aFollowedLinkThatDoesNotLeadToADawFileIsRefusedNamingTheLinkAndNothingIsStaged() throws IOException {
        SaveTestSupport.assumeSymbolicLinks(dir);
        Path notes = Files.writeString(dir.resolve("notes.txt"), OLD);
        Path folder = Files.createDirectory(dir.resolve("Folder.daw"));
        Path dangling = linkIn("dangling", Path.of("..", "Gone.daw"));
        Path toNotes = linkIn("notes", Path.of("..", "notes.txt"));
        Path toFolder = linkIn("folder", Path.of("..", "Folder.daw"));
        AtomicInteger opens = new AtomicInteger();
        AtomicFileWriter writer = new AtomicFileWriter(temp -> {
            opens.incrementAndGet();
            return AtomicFileWriter.TEMP_CHANNEL_OPENER.open(temp);
        }, AtomicFileWriter.FILE_SYSTEM_MOVER);

        for (Path link : List.of(dangling, toNotes, toFolder)) {
            assertThatThrownBy(() -> writer.writeString(link, NEW, AtomicFileWriter.ReplacePermission.TARGET,
                    AtomicFileWriter.ReplaceOption.FOLLOW_LINK_TO_DAW_FILE))
                    .as(link.toString())
                    .isExactlyInstanceOf(FileSystemException.class)
                    .satisfies(refused -> {
                        FileSystemException notADawFile = (FileSystemException) refused;
                        assertThat(notADawFile.getFile()).as("names the link").isEqualTo(link.toString());
                        assertThat(notADawFile.getReason())
                                .isEqualTo("a symbolic link that does not lead to a .daw file; it was not replaced");
                    });
            assertThat(Files.isSymbolicLink(link)).as("the link stays").isTrue();
            assertThat(entriesOf(link.getParent())).as("no temp beside it").containsExactly(link);
        }

        assertThatThrownBy(() -> writer.writeString(dangling, NEW, AtomicFileWriter.ReplacePermission.TARGET,
                AtomicFileWriter.ReplaceOption.FOLLOW_LINK_TO_DAW_FILE))
                .as("resolving the dangling link failed: that is the cause")
                .hasCauseInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> writer.writeString(toNotes, NEW, AtomicFileWriter.ReplacePermission.TARGET,
                AtomicFileWriter.ReplaceOption.FOLLOW_LINK_TO_DAW_FILE))
                .as("the link resolved: there is no cause").hasNoCause();
        assertThat(opens).as("nothing was staged").hasValue(0);
        assertThat(notes).as("never written").hasContent(OLD);
        assertThat(entriesOf(folder)).isEmpty();
        assertThat(dir.resolve("Gone.daw")).as("a missing file is never created").doesNotExist();
    }

    @Test
    void aLinkToAReadOnlyFileIsReplacedLikeAnyOtherLinkAndTheFileKeepsItsContentAndItsFlag() throws IOException {
        SaveTestSupport.assumeSymbolicLinks(dir);
        Path old = Files.writeString(dir.resolve("old.dawz"), OLD);
        Path link = Files.createSymbolicLink(dir.resolve("song.dawz"), old.getFileName());
        byte[] before = Files.readAllBytes(old);

        makeReadOnly(old, true);
        try {
            assumeFalse(Files.isWritable(old), "a superuser may write a read-only file");

            new AtomicFileWriter().write(link, out -> out.write(NEW.getBytes(StandardCharsets.UTF_8)),
                    AtomicFileWriter.ReplacePermission.TARGET);

            assertThat(Files.isRegularFile(link, LinkOption.NOFOLLOW_LINKS)).as("the rename replaced the link")
                    .isTrue();
            assertThat(Files.readString(link)).isEqualTo(NEW);
            assertThat(Files.readAllBytes(old)).as("the read-only file keeps its content").isEqualTo(before);
            assertThat(isMarkedReadOnly(old)).as("and its flag").isTrue();
            assertThat(entries()).as("no temp").containsExactlyInAnyOrder(old, link);
        } finally {
            makeReadOnly(old, false);
        }
    }

    @Test
    void onWindowsALinksOwnReadOnlyFlagRefusesItsReplaceAndNothingIsStaged() throws IOException {
        assumeFalse(hasPosixPermissions(), "a link has a DOS read-only flag of its own on Windows");
        SaveTestSupport.assumeSymbolicLinks(dir);
        Path old = Files.writeString(dir.resolve("old.dawz"), OLD);
        Path link = Files.createSymbolicLink(dir.resolve("song.dawz"), old.getFileName());
        DosFileAttributeView linkFlags =
                Files.getFileAttributeView(link, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        AtomicInteger opens = new AtomicInteger();
        AtomicFileWriter writer = new AtomicFileWriter(temp -> {
            opens.incrementAndGet();
            return AtomicFileWriter.TEMP_CHANNEL_OPENER.open(temp);
        }, AtomicFileWriter.FILE_SYSTEM_MOVER);

        linkFlags.setReadOnly(true);
        try {
            assumeTrue(Files.readAttributes(link, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isReadOnly(),
                    "the link's own read-only flag reads back");

            assertThatThrownBy(() -> writer.write(link, out -> out.write(NEW.getBytes(StandardCharsets.UTF_8)),
                    AtomicFileWriter.ReplacePermission.TARGET))
                    .isInstanceOfSatisfying(AccessDeniedException.class, denied -> {
                        assertThat(denied.getFile()).as("names the link").isEqualTo(link.toString());
                        assertThat(denied.getReason())
                                .isEqualTo("the file is read-only or not writable; it was not replaced");
                    });

            assertThat(opens).as("nothing is staged").hasValue(0);
            assertThat(Files.isSymbolicLink(link)).as("the link stays").isTrue();
            assertThat(Files.readString(old)).isEqualTo(OLD);
            assertThat(isMarkedReadOnly(old)).as("the file it leads to is not read-only").isFalse();
            assertThat(entries()).containsExactlyInAnyOrder(old, link);
        } finally {
            linkFlags.setReadOnly(false);
        }
    }

    @Test
    void followingAPathThatIsNoLinkGivesThatPathAsGivenAndNeverResolvesIt() throws IOException {
        Path notes = Files.writeString(dir.resolve("notes.txt"), OLD);
        Path missing = dir.resolve("missing").resolve("..").resolve("Gone.daw");

        assertThat(AtomicFileWriter.followLinkToDawFile(notes)).as("no link: its name is not asked").isSameAs(notes);
        assertThat(AtomicFileWriter.followLinkToDawFile(missing)).as("never resolved, so a missing path is no refusal")
                .isSameAs(missing);
    }

    @Test
    void followingALinkToADawFileGivesThatFileResolvedFully() throws IOException {
        SaveTestSupport.assumeSymbolicLinks(dir);
        Path shared = Files.createDirectories(dir.resolve("shared"));
        Path song = Files.writeString(shared.resolve("Song.daw"), OLD);
        Path mix = Files.writeString(shared.resolve("MIX.DAW"), OLD);
        Path toSong = linkIn("song", Path.of("..", "shared", "Song.daw"));
        Path toMix = linkIn("mix", Path.of("..", "shared", "MIX.DAW"));
        Path toLink = linkIn("chain", Path.of("..", "song", "project.daw"));

        assertThat(AtomicFileWriter.followLinkToDawFile(toSong)).isEqualTo(song.toRealPath());
        assertThat(AtomicFileWriter.followLinkToDawFile(toMix)).as("the extension in any case")
                .isEqualTo(mix.toRealPath());
        assertThat(AtomicFileWriter.followLinkToDawFile(toLink)).as("a link to a link, resolved fully")
                .isEqualTo(song.toRealPath());
        assertThat(Files.readSymbolicLink(toSong)).as("the link is only read").isEqualTo(Path.of("..", "shared",
                "Song.daw"));
        assertThat(song).hasContent(OLD);
    }

    @Test
    void followingALinkThatDoesNotLeadToADawFileIsRefusedNamingTheLink() throws IOException {
        SaveTestSupport.assumeSymbolicLinks(dir);
        Path notes = Files.writeString(dir.resolve("notes.txt"), OLD);
        Files.createDirectory(dir.resolve("Folder.daw"));
        Path dangling = linkIn("dangling", Path.of("..", "Gone.daw"));
        Path toNotes = linkIn("notes", Path.of("..", "notes.txt"));
        Path toFolder = linkIn("folder", Path.of("..", "Folder.daw"));
        Path loop = linkIn("loop", Path.of("..", "loop2", "project.daw"));
        linkIn("loop2", Path.of("..", "loop", "project.daw"));

        for (Path link : List.of(dangling, toNotes, toFolder, loop)) {
            assertThatThrownBy(() -> AtomicFileWriter.followLinkToDawFile(link))
                    .as(link.toString())
                    .isExactlyInstanceOf(FileSystemException.class)
                    .satisfies(refused -> {
                        FileSystemException notADawFile = (FileSystemException) refused;
                        assertThat(notADawFile.getFile()).as("names the link").isEqualTo(link.toString());
                        assertThat(notADawFile.getOtherFile()).isNull();
                        assertThat(notADawFile.getReason())
                                .isEqualTo("a symbolic link that does not lead to a .daw file; it was not replaced");
                    });
        }
        assertThatThrownBy(() -> AtomicFileWriter.followLinkToDawFile(dangling))
                .as("resolving the dangling link failed: that is the cause")
                .hasCauseInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> AtomicFileWriter.followLinkToDawFile(loop))
                .as("resolving the loop failed: that is the cause")
                .hasMessage(loop + ": a symbolic link that does not lead to a .daw file; it was not replaced")
                .hasCauseInstanceOf(FileSystemException.class);
        assertThatThrownBy(() -> AtomicFileWriter.followLinkToDawFile(toNotes)).as("it resolved: no cause")
                .hasNoCause();
        assertThatThrownBy(() -> AtomicFileWriter.followLinkToDawFile(toFolder)).as("it resolved: no cause")
                .hasNoCause();
        assertThat(notes).hasContent(OLD);
        assertThat(dir.resolve("Gone.daw")).as("a missing file is never created").doesNotExist();
    }

    @Test
    void aCopyThatNeverOverwritesTakesNoReplaceOption() throws IOException {
        Path source = Files.writeString(dir.resolve("project.daw"), NEW);
        Path backup = dir.resolve("project.daw.v1.20260621-120000.bak");

        for (AtomicFileWriter.ReplaceOption option : AtomicFileWriter.ReplaceOption.values()) {
            assertThatThrownBy(() -> new AtomicFileWriter().copy(source, backup, AtomicFileWriter.Overwrite.NEVER,
                    option)).as(option.name()).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(entries()).containsExactly(source);
    }

    /** A folder {@code name} under the test's folder holding {@code project.daw} as a link to {@code target}. */
    private Path linkIn(String name, Path target) throws IOException {
        return Files.createSymbolicLink(Files.createDirectory(dir.resolve(name)).resolve("project.daw"), target);
    }

    private static void awaitLatch(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(CheckpointTestSupport.TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new InterruptedIOException("the other writer never reached its step");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while waiting for the other writer");
        }
    }

    private void assumePosixPermissions() throws IOException {
        assumeTrue(hasPosixPermissions(), "the file system has POSIX permission bits (Linux CI)");
    }

    private boolean hasPosixPermissions() throws IOException {
        return Files.getFileStore(dir).supportsFileAttributeView(PosixFileAttributeView.class);
    }

    private static String modeOf(Path file) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS));
    }

    /**
     * Makes {@code file} read-only, or writable again, the way its file
     * system can: its POSIX permission bits where it has them (a Linux file
     * system also offers a DOS view, which no write obeys), else the DOS
     * read-only flag (Windows, where {@code @TempDir} cannot delete a
     * read-only file).
     */
    private static void makeReadOnly(Path file, boolean readOnly) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString(readOnly ? "r--r--r--" : "rw-r--r--"));
            return;
        }
        DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class);
        assumeTrue(dos != null, "the file system can make a file read-only");
        dos.setReadOnly(readOnly);
    }

    /** Whether {@code file} is read-only as {@link #makeReadOnly} marks it: no owner write bit, else the DOS flag. */
    private static boolean isMarkedReadOnly(Path file) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            return !posix.readAttributes().permissions().contains(PosixFilePermission.OWNER_WRITE);
        }
        return Files.readAttributes(file, DosFileAttributes.class).isReadOnly();
    }

    private List<Path> entries() throws IOException {
        return entriesOf(dir);
    }

    private static List<Path> entriesOf(Path folder) throws IOException {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.toList();
        }
    }

    /** Records the writer's log lines, {@code FINE} and up, while it is open. */
    private static final class WriterLog implements AutoCloseable {

        private final Logger logger = Logger.getLogger(AtomicFileWriter.class.getName());
        private final Level levelBefore = logger.getLevel();
        private final List<LogRecord> records = new CopyOnWriteArrayList<>();
        private final Handler handler = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                records.add(logRecord);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };

        WriterLog() {
            handler.setLevel(Level.ALL);
            logger.setLevel(Level.FINE);
            logger.addHandler(handler);
        }

        /** The messages logged at exactly {@code level}, in order. */
        List<String> messages(Level level) {
            return records.stream().filter(logRecord -> logRecord.getLevel().equals(level))
                    .map(LogRecord::getMessage).toList();
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
            logger.setLevel(levelBefore);
        }
    }

    private static PathMatcher glob(String pattern) {
        return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    }

    /** Runs on the forcing thread before a force reaches the file; throwing fails the force. */
    @FunctionalInterface
    private interface BeforeForce {
        void run() throws IOException;
    }

    /**
     * A temp channel that delegates to the real one and journals its writes,
     * its force and its close; its {@link BeforeForce} hook can fail the
     * force before it reaches the file.
     */
    private static final class ObservedChannel extends FileChannel {

        private final FileChannel delegate;
        private final List<String> journal;
        private final BeforeForce beforeForce;

        ObservedChannel(FileChannel delegate, List<String> journal, BeforeForce beforeForce) {
            this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
            this.journal = Objects.requireNonNull(journal, "journal must not be null");
            this.beforeForce = Objects.requireNonNull(beforeForce, "beforeForce must not be null");
        }

        @Override
        public void force(boolean metaData) throws IOException {
            beforeForce.run();
            delegate.force(metaData);
            journal.add("force(" + metaData + ")");
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            int written = delegate.write(src);
            journal.add("write");
            return written;
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
            long written = delegate.write(srcs, offset, length);
            journal.add("write");
            return written;
        }

        @Override
        public int write(ByteBuffer src, long position) throws IOException {
            int written = delegate.write(src, position);
            journal.add("write");
            return written;
        }

        @Override
        protected void implCloseChannel() throws IOException {
            delegate.close();
            journal.add("close");
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return delegate.read(dst);
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
            return delegate.read(dsts, offset, length);
        }

        @Override
        public int read(ByteBuffer dst, long position) throws IOException {
            return delegate.read(dst, position);
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public FileChannel position(long newPosition) throws IOException {
            delegate.position(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public FileChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }

        @Override
        public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
            return delegate.transferTo(position, count, target);
        }

        @Override
        public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
            return delegate.transferFrom(src, position, count);
        }

        @Override
        public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
            return delegate.map(mode, position, size);
        }

        @Override
        public FileLock lock(long position, long size, boolean shared) throws IOException {
            return delegate.lock(position, size, shared);
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) throws IOException {
            return delegate.tryLock(position, size, shared);
        }
    }
}
