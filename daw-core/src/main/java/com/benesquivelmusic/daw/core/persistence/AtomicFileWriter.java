package com.benesquivelmusic.daw.core.persistence;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The one atomic-replace writer every durable file write of the persistence
 * path goes through (story 331; Persistence Integrity book §4.1, §2.2: every
 * durable write is atomic, or it is a hazard). A target is either its old
 * bytes or the complete new bytes, never a partial file.
 *
 * <h2>How one write runs</h2>
 * <ol>
 *   <li>A replace ({@link #writeString writeString}, {@link #write write},
 *       a {@link Overwrite#REPLACE} copy) over an existing target the user
 *       made read-only — a {@code project.daw} protecting a delivered mix —
 *       is refused with an {@link AccessDeniedException} naming the target,
 *       before anything is staged and without the retries below (story 331,
 *       ruling G10). On POSIX that is {@link Files#isWritable}'s answer,
 *       asked once: the in-place write this writer replaced was refused
 *       there, and a rename would succeed over the file. On Windows it is
 *       the DOS read-only flag, the one refusal the move would only retry
 *       as if it were transient; {@code isWritable} there also asks the
 *       file's ACL, which can deny a file a network share lets the user
 *       write (JDK-8154915), so everything else is the move's to answer.
 *       The residual (story 331): on local NTFS, a file whose ACL denies
 *       write but not delete, in a folder the user can write, is now
 *       replaced, where the in-place write this writer replaced refused it.
 *       That applies only where an in-place write was used before story 331
 *       — the {@code project.daw} head and the project metadata, and the two
 *       in-place app stores ({@code BackupRetentionPolicyStore},
 *       {@code MigrationSuppression}); where a rename was used, Windows
 *       behaves as before. After that first save the file has the folder's
 *       inherited ACL: ACLs are not carried (see the permission-bits step
 *       below). {@link ReplacePermission#DIRECTORY} skips the refusal for a
 *       file the app keeps for itself; {@link Overwrite#NEVER} has no
 *       existing target to ask. With
 *       {@link ReplaceOption#FOLLOW_LINK_TO_DAW_FILE}, this step and every
 *       step below apply to the {@code .daw} file a symbolic-link target
 *       leads to (see "Links"). Without
 *       {@link ReplaceOption#FOLLOW_LINK_TO_DAW_FILE}, a target that is a
 *       symbolic link is asked about itself, never about the file it leads
 *       to, which the rename leaves untouched: on POSIX it is not refused,
 *       and on Windows only its own DOS read-only flag refuses it (ruling
 *       N3).</li>
 *   <li>With {@link ReplaceOption#DELETE_EARLIER_TEMPS} (the {@code .dawz}
 *       archive only), the temps earlier, unfinished writes of the target
 *       left beside it are deleted — only once the refusal above has passed,
 *       so a refused write deletes nothing (ruling M2; see "Stray temps"
 *       below).</li>
 *   <li>The content goes into a temp sibling in the target's directory,
 *       {@code "." + <target name> + "." + <16 lowercase hex> + ".tmp"}, a
 *       new name per write (so two writers of one target never truncate each
 *       other's temp), opened {@code CREATE_NEW, WRITE, NOFOLLOW_LINKS}. The
 *       leading dot and the {@code .tmp} suffix keep it out of every
 *       reader's filter ({@code checkpoint*}, {@code *.daw}, the checkpoint
 *       name pattern); the leading dot hides it on Linux and macOS, not in
 *       Windows Explorer. It is never a {@code Files.createTempFile}, whose
 *       POSIX {@code rw-------} would carry over to the published file.
 *       {@link #publishedNameOf} reads that name back.</li>
 *   <li>Where the file system has POSIX permissions, the new temp takes the
 *       permission bits of the file it stands for before any content is
 *       written: a replace keeps the existing target's (read without
 *       following a link; a new target, or a link the write replaces, gets
 *       the directory's defaults), and a copy, in either mode, carries its
 *       source's, as {@code COPY_ATTRIBUTES} did at the backup and snapshot
 *       writers this one replaced. Only the bits: the owner, the group, ACLs
 *       and DOS attributes are not carried (story 331, ruling G10). A
 *       failure to read or set them is logged at {@code FINE}, and the write
 *       goes on with the directory's defaults.</li>
 *   <li>The temp's channel — opened for writing, because a read channel's
 *       {@code force} is a no-op on Windows — is forced
 *       ({@code force(true)}) and closed; only then is the temp moved.</li>
 *   <li>{@link Overwrite#REPLACE}: {@code ATOMIC_MOVE + REPLACE_EXISTING},
 *       falling back to {@code REPLACE_EXISTING} alone where the file system
 *       refuses atomic moves ({@link AtomicMoveNotSupportedException}).
 *       {@link Overwrite#NEVER}: a move with no options, which fails with
 *       {@link FileAlreadyExistsException} when the target exists (on POSIX
 *       a check, then the rename: enough for the uniquely named backups and
 *       snapshots that use it, not a mutual-exclusion primitive). In either
 *       mode a refusal of the kind Windows gives transiently — an
 *       {@link AccessDeniedException}, or a plain
 *       {@link FileSystemException}: an antivirus scanner, the indexer or a
 *       sync client holding the target or the just-closed temp — is retried
 *       three times with a short backoff (130 ms in all) and never answered
 *       with the fallback, which on Windows deletes the target before it
 *       moves. The retry is not limited to Windows: on POSIX such a refusal
 *       of a rename is lasting (a folder the user cannot write), and the
 *       retries only report the same refusal 130 ms later. A
 *       {@link FileAlreadyExistsException}, like every other subclass that
 *       names a lasting cause, is thrown at once, so a backup or snapshot
 *       name another writer took moves on to the next name without
 *       waiting.</li>
 *   <li>After the move, a best-effort fsync of the target's directory (a
 *       directory cannot be opened as a channel on Windows; there the
 *       failure is ignored).</li>
 * </ol>
 *
 * <p>Any failure once the temp exists — an {@link IOException}, a
 * {@link RuntimeException} or {@link Error} from the content, an interrupt
 * ({@link java.nio.channels.ClosedByInterruptException}) — deletes the temp
 * and rethrows the original exception, unwrapped: the one that ended the
 * attempt (when the fallback ran and failed, the fallback's, with the
 * {@link AtomicMoveNotSupportedException} attached as suppressed). A temp
 * that cannot be deleted is attached as suppressed, never thrown instead. A
 * temp that could not be created is not deleted: the writer owns nothing
 * there.</p>
 *
 * <h2>Stray temps</h2>
 * <p>Nothing in normal use. Each write cut off before its move — by a crash,
 * a kill, a power cut or an exit past the bounded wait at exit (ruling M1) —
 * leaves one temp file beside its target, named
 * {@code .<target name>.<16 hex>.tmp}, up to the size of that file; the
 * leading dot hides it on Linux and macOS, not in Windows Explorer.
 * Checkpoint temps are deleted at the next writable open of the project
 * ({@link CheckpointManager}'s seeding). An archive's temps are deleted when
 * the next archive to the same file starts
 * ({@link ReplaceOption#DELETE_EARLIER_TEMPS}); one whose file is never
 * archived to again stays. Temps of {@code project.daw} (one per save cut
 * off, any number) and of the project folder's other files stay until story
 * 332's recovery gate removes them; a temp of an app settings file stays
 * (story 331, rulings G6, M2). A {@linkplain #publishDirectory directory
 * publish} cut off before its rename leaves its staging directory,
 * {@code .<folder name>.<purpose>.tmp} ({@link #stagingSiblingOf}), beside the
 * folder it was to become, until the next publish of the same purpose into
 * that folder deletes it before staging again (story 333).</p>
 *
 * <h2>Links</h2>
 * <p>A replace renames over its target's name. A target that is a symbolic
 * link is therefore replaced by a regular file, and the file the link led to
 * keeps its content — for the {@code .dawz} archive (which lands at the path
 * the user chose), the {@code .project.lock} and the session manifest (all
 * three renamed over a link before story 331 as well), and
 * {@code BackupRetentionPolicyStore} and {@code MigrationSuppression} (app
 * metadata; a change from their in-place writes before story 331, which went
 * through a link). So a link to a read-only file is replaced like any other
 * link; the read-only file keeps its content and its flag. Only
 * {@code project.daw}'s writers pass
 * {@link ReplaceOption#FOLLOW_LINK_TO_DAW_FILE}, which keeps the link and
 * replaces the {@code .daw} file it leads to, or refuses a link that leads
 * anywhere else (ruling M3). Checkpoints, {@code .bak} backups and named
 * snapshots are always new names: they never replace anything. Known limit: a
 * target with other hard links is replaced by a new file, and its other names
 * keep the previous content.</p>
 *
 * <p><strong>Threading.</strong> Instances are immutable and may be shared
 * by any number of threads. Every operation blocks on storage — it forces
 * the data and may pause before retrying a refused move — so it runs on a
 * worker thread, never on the JavaFX application thread (Persistence
 * Integrity book §6.1). This package takes no JavaFX dependency, so that is
 * the caller's contract, not a check made here.</p>
 *
 * <p><strong>Test seam.</strong> {@link #AtomicFileWriter(ChannelOpener, Mover)}
 * replaces the two file-system steps a test needs to fail or observe: the
 * open of the temp's channel (wrap the real channel to fail a write or a
 * force, or to see the force) and the move (fail it in the crash window
 * after the forced temp, refuse the atomic move, fail the fallback, or see
 * each publish and the thread that made it). {@link #AtomicFileWriter()}
 * uses the real file system.</p>
 */
public final class AtomicFileWriter {

    /**
     * Opens the channel a write's temp sibling is written through.
     * Production uses {@link #TEMP_CHANNEL_OPENER}; a test passes an opener
     * that wraps the real channel to fail a write or a force, or to record
     * when the force happens. Called on the writing thread.
     */
    @FunctionalInterface
    public interface ChannelOpener {

        /**
         * @param temp the temp sibling to create
         * @return a channel open for writing on the new, empty temp
         * @throws IOException if the temp cannot be created
         */
        FileChannel open(Path temp) throws IOException;
    }

    /**
     * Moves a forced and closed temp sibling over its target — the publish —
     * or a staging directory onto its target ({@link #publishDirectory}).
     * Production uses {@link #FILE_SYSTEM_MOVER}; a test passes a mover that
     * fails, or that delegates and records the target and the calling
     * thread. Called on the writing thread, once per attempt.
     */
    @FunctionalInterface
    public interface Mover {

        /**
         * @param temp    the temp sibling holding the complete content
         * @param target  the file it publishes
         * @param options the move's options, as {@link Files#move} takes them
         * @throws IOException if the move fails
         */
        void move(Path temp, Path target, CopyOption... options) throws IOException;
    }

    /** Writes a streamed write's whole content (the archive ZIP, a session manifest). */
    @FunctionalInterface
    public interface Body {

        /**
         * Writes the content to {@code out}. Closing {@code out} is allowed
         * and does not close the temp: the writer flushes, forces and closes
         * it once this returns.
         *
         * @param out the temp sibling's stream (buffered)
         * @throws IOException if the content cannot be produced or written
         */
        void writeTo(OutputStream out) throws IOException;
    }

    /** What a {@link #copy copy} does when its target already exists. */
    public enum Overwrite {

        /** Replace the target atomically (consolidated archive assets, a migration roll-back). */
        REPLACE,

        /**
         * Publish only when the target does not exist, failing with
         * {@link FileAlreadyExistsException} otherwise (sibling {@code .bak}
         * backups, named snapshots).
         */
        NEVER
    }

    /**
     * Whose write permission a replace
     * ({@link #writeString(Path, String, ReplacePermission, ReplaceOption...) writeString},
     * {@link #write(Path, Body, ReplacePermission, ReplaceOption...) write}) asks for; each call site chooses its
     * own.
     */
    public enum ReplacePermission {

        /**
         * The target's own, as an in-place write asks: an existing target the
         * user made read-only is refused before anything is staged (see the
         * class note). Every document's write.
         */
        TARGET,

        /**
         * Only the directory's, as a rename asks: the target is replaced
         * whoever may write it. For a file the app keeps for itself that
         * another user's session may have created — the {@code .project.lock}
         * a crashed collaborator left in a group-shared project folder, or a
         * session manifest, both of which a rename replaced before story 331.
         * Windows still refuses to replace a file with the DOS read-only flag.
         */
        DIRECTORY
    }

    /**
     * What a replace does besides replacing its target. Each call site
     * passes its own; none is the writer's default (story 331).
     */
    public enum ReplaceOption {

        /**
         * A target that is a symbolic link is not replaced: the file it leads
         * to is (ruling M3). For {@code project.daw} only, whose in-place
         * write before story 331 wrote through a link. The link is resolved
         * fully ({@link Path#toRealPath}); that file must exist, be a regular
         * file and have a name ending in {@code .daw} (in any case), and is
         * then replaced exactly as if it had been named: its temp beside it,
         * the read-only refusal naming it, its permission bits carried, its
         * folder forced. The link stays. A link that leads anywhere else —
         * nowhere, to a directory, to any other file — stages nothing and
         * throws a {@link FileSystemException} naming the link, "a symbolic
         * link that does not lead to a .daw file; it was not replaced", with
         * what resolving it threw as its cause. A target that is not a link
         * is replaced as given, and never resolved.
         * {@link AtomicFileWriter#followLinkToDawFile} names that file, or
         * refuses the link, for a caller that reads it before the replace.
         */
        FOLLOW_LINK_TO_DAW_FILE,

        /**
         * Before the temp is created, and only once the read-only refusal has
         * passed, the temps that earlier, unfinished writes of the target
         * left beside it are deleted (ruling M2). For the {@code .dawz}
         * archive only, whose temps are archive-sized and sit in a folder no
         * recovery looks at. Its folder is listed, not recursively; an entry
         * goes when {@link #publishedNameOf} maps its name exactly to the
         * target's file name and it is a regular file read without following
         * a link — never a link, a directory, or another target's temp. The
         * count deleted is logged at {@code INFO}. A delete that fails is
         * logged at {@code FINE} and the others go on ({@code NoSuchFileException}
         * counts as deleted); a folder that cannot be listed is logged at
         * {@code FINE} and the write goes on. A second writer of the same
         * target, in this process or another, whose temp this deletes fails
         * its move with the move's own exception; the target is never torn.
         */
        DELETE_EARLIER_TEMPS
    }

    /** The production temp opener: a new file, write-only, never through a link. */
    public static final ChannelOpener TEMP_CHANNEL_OPENER = temp -> FileChannel.open(temp,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);

    /** The production mover: {@link Files#move(Path, Path, CopyOption...)}. */
    public static final Mover FILE_SYSTEM_MOVER = Files::move;

    /** The pause before each retry of a transiently refused move; 130 ms in all. */
    private static final long[] TRANSIENT_DENIAL_BACKOFF_MILLIS = {10, 30, 90};

    /** Retries of a transiently refused move before the refusal is thrown. */
    static final int TRANSIENT_DENIAL_RETRIES = TRANSIENT_DENIAL_BACKOFF_MILLIS.length;

    /**
     * The pause before each retry of a transiently refused directory publish
     * ({@link #publishDirectory}); about 7.9 s in all (story 333, studio
     * engineer's ruling S4 on stage 3: an engineering figure, not a
     * measurement). Windows refuses to rename a folder while a program holds
     * a file inside it open — an antivirus scanner, the search indexer or a
     * sync client opening the files a copy just wrote — and a publish that
     * gives up costs the whole staged copy, not one small file.
     */
    private static final long[] DIRECTORY_PUBLISH_BACKOFF_MILLIS = {10, 30, 90, 250, 500, 1000, 2000, 4000};

    /** Retries of a transiently refused directory publish before the refusal is thrown. */
    static final int DIRECTORY_PUBLISH_RETRIES = DIRECTORY_PUBLISH_BACKOFF_MILLIS.length;

    /** The reason of a {@link ReplaceOption#FOLLOW_LINK_TO_DAW_FILE} write's refusal of a link (ruling M3). */
    private static final String NOT_A_DAW_FILE_LINK =
            "a symbolic link that does not lead to a .daw file; it was not replaced";

    /** The extension the file a followed link leads to must have, in any case. */
    private static final String DAW_EXTENSION = ".daw";

    private static final Logger LOG = Logger.getLogger(AtomicFileWriter.class.getName());

    /*
     * The temp-name grammar, TEMP_PREFIX + <target name> + NONCE_SEPARATOR +
     * <nonce> + TEMP_SUFFIX. The generator (tempNameOf) and the recognizer
     * (publishedNameOf) both read these, so the two cannot drift.
     */
    private static final String TEMP_PREFIX = ".";
    private static final String NONCE_SEPARATOR = ".";
    private static final String TEMP_SUFFIX = ".tmp";
    /** Writes a nonce, and checks one read back: a random {@code long} as lowercase hex. */
    private static final HexFormat NONCE_FORMAT = HexFormat.of();
    /** The length of {@code NONCE_FORMAT.toHexDigits(long)}: two hex digits per byte. */
    private static final int NONCE_DIGITS = 2 * Long.BYTES;

    private static final int STREAM_BUFFER_BYTES = 64 * 1024;

    /** What a {@link #stagingSiblingOf} purpose may be: lowercase words joined by hyphens. */
    private static final Pattern STAGING_PURPOSE = Pattern.compile("[a-z]+(-[a-z]+)*");

    private final ChannelOpener opener;
    private final Mover mover;

    /** Creates a writer on the real file system. */
    public AtomicFileWriter() {
        this(TEMP_CHANNEL_OPENER, FILE_SYSTEM_MOVER);
    }

    /**
     * Creates a writer whose temp opener and mover a test supplies (see the
     * class note, "Test seam").
     *
     * @param opener opens each write's temp sibling
     * @param mover  publishes each write
     */
    public AtomicFileWriter(ChannelOpener opener, Mover mover) {
        this.opener = Objects.requireNonNull(opener, "opener must not be null");
        this.mover = Objects.requireNonNull(mover, "mover must not be null");
    }

    /**
     * Atomically replaces {@code target} with {@code content} in UTF-8. Text
     * that UTF-8 cannot encode (a lone surrogate) fails before any temp
     * exists, as {@link Files#writeString} does. An existing target keeps its
     * POSIX permission bits.
     *
     * @param target  the file to replace or create; its directory must exist
     * @param content the complete new content
     * @return {@code target}
     * @throws AccessDeniedException if {@code target} exists and is read-only to the user (see the class
     *                               note); nothing is staged
     * @throws IOException if the content cannot be encoded or written, or the move fails
     */
    public Path writeString(Path target, String content) throws IOException {
        return writeString(target, content, ReplacePermission.TARGET);
    }

    /**
     * As {@link #writeString(Path, String)}, asking for {@code permission}:
     * {@link ReplacePermission#DIRECTORY} replaces a target the user cannot
     * write where the rename may; and doing what {@code options} add.
     *
     * @param target     the file to replace or create; its directory must exist
     * @param content    the complete new content
     * @param permission whose write permission the replace asks for
     * @param options    what the replace does besides (none by default)
     * @return {@code target}
     * @throws AccessDeniedException with {@link ReplacePermission#TARGET}, if the file to replace exists and is
     *                               read-only to the user — a symbolic link is asked about itself, not the file
     *                               it leads to, unless {@link ReplaceOption#FOLLOW_LINK_TO_DAW_FILE}; nothing
     *                               is staged
     * @throws FileSystemException   with {@link ReplaceOption#FOLLOW_LINK_TO_DAW_FILE}, if {@code target} is a
     *                               symbolic link that does not lead to a {@code .daw} file; nothing is staged
     * @throws IOException if the content cannot be encoded or written, or the move fails
     */
    public Path writeString(Path target, String content, ReplacePermission permission, ReplaceOption... options)
            throws IOException {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(permission, "permission must not be null");
        Set<ReplaceOption> chosen = optionsOf(options);
        ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(content));
        Path destination = prepareReplace(target, permission, chosen);
        publish(destination, Overwrite.REPLACE, permissionsOf(destination, LinkOption.NOFOLLOW_LINKS),
                channel -> writeFully(channel, bytes), AtomicFileWriter::keep);
        return target;
    }

    /**
     * Publishes {@code content}, in UTF-8, as the new file {@code target}:
     * the {@link Overwrite#NEVER} form of {@link #writeString(Path, String)}
     * — forced, then moved with no option, so a file another writer put at
     * {@code target} meanwhile is never replaced (story 333: Save As writes
     * the copy's {@code project.daw} this way into a folder it does not own
     * alone). On POSIX the move is a check, then the rename, as for every
     * {@code NEVER} publish.
     *
     * @param target  the file to create; its directory must exist
     * @param content the complete content
     * @return {@code target}
     * @throws FileAlreadyExistsException if {@code target} exists; the temp is deleted
     * @throws IOException                if the content cannot be encoded or written, or the move fails
     */
    public Path writeNewString(Path target, String content) throws IOException {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(content, "content must not be null");
        ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(content));
        publish(target, Overwrite.NEVER, AtomicFileWriter::keepDefaults, channel -> writeFully(channel, bytes),
                AtomicFileWriter::keep);
        return target;
    }

    private static void keepDefaults(Path temp) {
        // A new file keeps the directory's default permissions.
    }

    /**
     * Atomically replaces {@code target} with what {@code body} writes. A
     * body that throws leaves the target as it was; its exception reaches the
     * caller unchanged. An existing target keeps its POSIX permission bits.
     *
     * @param target the file to replace or create; its directory must exist
     * @param body   writes the complete new content
     * @return {@code target}
     * @throws AccessDeniedException if {@code target} exists and is read-only to the user (see the class
     *                               note); nothing is staged
     * @throws IOException if the body or the write fails, or the move fails
     */
    public Path write(Path target, Body body) throws IOException {
        return write(target, body, ReplacePermission.TARGET);
    }

    /**
     * As {@link #write(Path, Body)}, asking for {@code permission}:
     * {@link ReplacePermission#DIRECTORY} replaces a target the user cannot
     * write where the rename may; and doing what {@code options} add.
     *
     * @param target     the file to replace or create; its directory must exist
     * @param body       writes the complete new content
     * @param permission whose write permission the replace asks for
     * @param options    what the replace does besides (none by default)
     * @return {@code target}
     * @throws AccessDeniedException with {@link ReplacePermission#TARGET}, if the file to replace exists and is
     *                               read-only to the user — a symbolic link is asked about itself, not the file
     *                               it leads to, unless {@link ReplaceOption#FOLLOW_LINK_TO_DAW_FILE}; nothing
     *                               is staged or deleted and {@code body} does not run
     * @throws FileSystemException   with {@link ReplaceOption#FOLLOW_LINK_TO_DAW_FILE}, if {@code target} is a
     *                               symbolic link that does not lead to a {@code .daw} file; nothing is staged
     * @throws IOException if the body or the write fails, or the move fails
     */
    public Path write(Path target, Body body, ReplacePermission permission, ReplaceOption... options)
            throws IOException {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(permission, "permission must not be null");
        Set<ReplaceOption> chosen = optionsOf(options);
        Path destination = prepareReplace(target, permission, chosen);
        publish(destination, Overwrite.REPLACE, permissionsOf(destination, LinkOption.NOFOLLOW_LINKS), channel -> {
            OutputStream out = new BufferedOutputStream(new TempChannelStream(channel), STREAM_BUFFER_BYTES);
            body.writeTo(out);
            out.flush();
        }, AtomicFileWriter::keep);
        return target;
    }

    /**
     * Copies {@code source} to {@code target} through a temp sibling of the
     * target. The copy carries the source's last-modified time and, where the
     * file system has them, its POSIX permission bits (read through a link,
     * as its bytes are), as {@code COPY_ATTRIBUTES} did for the writers this
     * replaces; a replaced target's own bits are not kept.
     *
     * @param source    the file to copy
     * @param target    the file to publish; its directory must exist
     * @param overwrite what to do when {@code target} exists
     * @param options   what a {@link Overwrite#REPLACE} copy does besides (none by default); a
     *                  {@link Overwrite#NEVER} copy publishes a new name and takes none
     * @return {@code target}
     * @throws IllegalArgumentException   if {@code overwrite} is {@link Overwrite#NEVER} and an option is given
     * @throws FileAlreadyExistsException with {@link Overwrite#NEVER}, when the target exists
     * @throws AccessDeniedException      with {@link Overwrite#REPLACE}, when the file to replace exists and is
     *                                    read-only to the user (see the class note); nothing is staged
     * @throws FileSystemException        with {@link ReplaceOption#FOLLOW_LINK_TO_DAW_FILE}, if {@code target} is
     *                                    a symbolic link that does not lead to a {@code .daw} file; nothing is
     *                                    staged
     * @throws IOException if the source cannot be read, or the write or the move fails
     */
    public Path copy(Path source, Path target, Overwrite overwrite, ReplaceOption... options) throws IOException {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(overwrite, "overwrite must not be null");
        Set<ReplaceOption> chosen = optionsOf(options);
        if (overwrite == Overwrite.NEVER && !chosen.isEmpty()) {
            throw new IllegalArgumentException("a copy that never overwrites publishes a new name; it takes no"
                    + " replace option: " + chosen);
        }
        FileTime lastModified = Files.getLastModifiedTime(source);
        try (InputStream in = Files.newInputStream(source)) {
            Path destination = overwrite == Overwrite.REPLACE
                    ? prepareReplace(target, ReplacePermission.TARGET, chosen)
                    : target;
            publish(destination, overwrite, permissionsOf(source),
                    channel -> in.transferTo(new TempChannelStream(channel)),
                    temp -> Files.setLastModifiedTime(temp, lastModified));
            return target;
        }
    }

    /**
     * Publishes a fully written staging directory as {@code target} (story
     * 333; Persistence Integrity book §4.1): the directory form of the
     * temp-sibling publish, so a folder the user names is never seen holding
     * half of what it will hold. It refuses when {@code target} exists (read
     * without following a link), then renames {@code staging} to
     * {@code target} in one move through this writer's mover, with no copy
     * option — a refusal of the kind Windows gives transiently is retried,
     * longer than a file's publish (below) — then fsyncs the parent directory, best
     * effort. Every file inside {@code staging} must already be durable: this
     * forces none of them. Archive restore and Save As publish their folders
     * through it, from a staging sibling named by {@link #stagingSiblingOf}.
     *
     * <p>The move is the file system's own rename: {@code staging} is a
     * sibling, on the same volume, and a non-empty directory is never copied
     * across volumes ({@link Files#move} refuses that), so the folder appears
     * whole or not at all. It takes no {@code ATOMIC_MOVE}: on Windows that
     * flag is {@code MOVEFILE_REPLACE_EXISTING}, which replaces a file created
     * at {@code target} between the check and the rename (story 333, measured
     * on Windows 10); without it the rename refuses whatever exists there. On
     * POSIX the move checks once more and then calls {@code rename(2)}, which
     * replaces an empty directory created at {@code target} in the instant
     * between that check and the call, and fails over a non-empty one or a
     * file. A failed move leaves {@code staging} where it was, for the caller
     * to delete.</p>
     *
     * <p>A refusal of the kind Windows gives transiently is retried longer
     * than a file's publish — about 7.9 s in all: Windows refuses to rename a
     * folder while a program holds a file inside it open, and an antivirus
     * scanner, the indexer or a sync client opens the files a copy just
     * wrote. Once the wait passes a file's 130 ms, a {@code WARNING} says the
     * folder waits for another program. A {@link FileAlreadyExistsException}
     * is never retried.</p>
     *
     * @param staging the fully written directory to publish; a sibling of {@code target}
     * @param target  the directory to create; it must not exist
     * @return {@code target}
     * @throws FileAlreadyExistsException if {@code target} exists; nothing is moved
     * @throws IOException                if the move fails
     */
    public Path publishDirectory(Path staging, Path target) throws IOException {
        Objects.requireNonNull(staging, "staging must not be null");
        Objects.requireNonNull(target, "target must not be null");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(target.toString(), null,
                    "it already exists; the staging directory was not published");
        }
        moveRetryingTransientDenials(staging, target, DIRECTORY_PUBLISH_BACKOFF_MILLIS);
        forceDirectoryOf(target);
        return target;
    }

    /**
     * Forces {@code directory}'s entries to storage, best effort (story 333:
     * a Save As copy forces each folder it created before it writes its
     * {@code project.daw} — before a staged copy is published, too — so on
     * POSIX a new folder's entry in its parent survives a crash without
     * relying on the file system's journal order). Windows cannot open a
     * directory as a channel: there, and wherever the force is refused, it
     * does nothing.
     *
     * @param directory the directory whose entries to force
     */
    public static void forceDirectory(Path directory) {
        Objects.requireNonNull(directory, "directory must not be null");
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException notPermitted) {
            // Best effort, as after each publish.
        }
    }

    /**
     * The staging sibling — hidden on POSIX by its leading dot — a {@linkplain #publishDirectory directory
     * publish} of {@code target} uses, {@code <parent>/.<name>.<purpose>.tmp}:
     * {@code .Song B.saving-as.tmp} for Save As, {@code .Song B.restoring.tmp}
     * for an archive restore. The name is fixed per target and purpose, so the
     * next caller that stages the same target finds, and deletes, a staging
     * directory a crash left behind. Its purpose is never a nonce, so it never
     * reads as one of this writer's temps ({@link #publishedNameOf}). No I/O.
     *
     * @param target  the directory the staging sibling is published as
     * @param purpose what publishes it: lowercase words joined by hyphens
     * @return the staging sibling, resolved against {@code target}'s absolute form
     * @throws IllegalArgumentException if {@code target} names no file, or {@code purpose} is not lowercase
     *                                  words joined by hyphens, or is sixteen hex digits
     */
    public static Path stagingSiblingOf(Path target, String purpose) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        if (!STAGING_PURPOSE.matcher(purpose).matches()
                || (purpose.length() == NONCE_DIGITS && isNonce(purpose))) {
            throw new IllegalArgumentException("a staging purpose is lowercase words joined by hyphens, never a"
                    + " writer temp's nonce: " + purpose);
        }
        Path absolute = target.toAbsolutePath();
        Path name = absolute.getFileName();
        if (name == null) {
            throw new IllegalArgumentException("target names no file: " + target);
        }
        return absolute.resolveSibling(TEMP_PREFIX + name + NONCE_SEPARATOR + purpose + TEMP_SUFFIX);
    }

    /**
     * The file name a temp sibling of this writer publishes, when
     * {@code fileName} is exactly one of its temps — a dot, the target name,
     * a dot, 16 lowercase hex digits, {@code .tmp}:
     * {@code ".checkpoint-001-….daw.0123456789abcdef.tmp"} gives
     * {@code "checkpoint-001-….daw"}. It reads the grammar the writer names
     * its temps by, so a writer that cleans up after a hard kill recognises
     * exactly the temps it may delete; it knows nothing of what the target
     * names mean.
     *
     * @param fileName a file name, without directory
     * @return the published name, or empty when {@code fileName} is not such a temp
     */
    public static Optional<String> publishedNameOf(String fileName) {
        Objects.requireNonNull(fileName, "fileName must not be null");
        int nonceEnd = fileName.length() - TEMP_SUFFIX.length();
        int nonceStart = nonceEnd - NONCE_DIGITS;
        int nameEnd = nonceStart - NONCE_SEPARATOR.length();
        if (nameEnd <= TEMP_PREFIX.length()
                || !fileName.startsWith(TEMP_PREFIX)
                || !fileName.startsWith(NONCE_SEPARATOR, nameEnd)
                || !fileName.endsWith(TEMP_SUFFIX)
                || !isNonce(fileName.substring(nonceStart, nonceEnd))) {
            return Optional.empty();
        }
        return Optional.of(fileName.substring(TEMP_PREFIX.length(), nameEnd));
    }

    /**
     * The file a {@link ReplaceOption#FOLLOW_LINK_TO_DAW_FILE} replace of
     * {@code target} publishes (ruling M3): {@code target} itself when it is
     * not a symbolic link — as given, never resolved, whether or not it
     * exists — or, when it is one, the file it leads to, resolved fully
     * ({@link Path#toRealPath}), which must exist, be a regular file and have
     * a name ending in {@code .daw} (in any case). Such a replace asks it
     * first, and so does each writer that backs the file up just before that
     * replace — a save's owed backup and the recovered-file write's — so no
     * backup copies anything through a link this method refuses. The
     * read-only refusal comes later, inside the replace: a read-only
     * {@code .daw} file a link leads to passes this method, so those writers
     * back it up before the replace refuses it (story 383). A read that
     * replaces nothing, such as the open's, follows any link (ruling M3
     * guards writes only).
     *
     * @param target the path a {@link ReplaceOption#FOLLOW_LINK_TO_DAW_FILE} replace is given
     * @return {@code target}, or the {@code .daw} file the link leads to
     * @throws FileSystemException naming {@code target}, with the reason "a symbolic link that does not lead to
     *                             a .daw file; it was not replaced", when it is a link that leads anywhere else
     *                             — nowhere, to a directory, to any other file; what resolving it threw, if that
     *                             failed, is its cause
     */
    public static Path followLinkToDawFile(Path target) throws FileSystemException {
        Objects.requireNonNull(target, "target must not be null");
        if (!Files.isSymbolicLink(target)) {
            return target;
        }
        Path real;
        try {
            real = target.toRealPath();
        } catch (IOException unresolved) {
            FileSystemException refused = notADawFileLink(target);
            refused.initCause(unresolved);
            throw refused;
        }
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS) || !hasDawExtension(real)) {
            throw notADawFileLink(target);
        }
        return real;
    }

    /** Prepares a new, empty temp before any content is written to it. */
    @FunctionalInterface
    private interface TempStart {
        void apply(Path temp);
    }

    /** Writes a temp's content into its open channel. */
    @FunctionalInterface
    private interface TempContent {
        void writeTo(FileChannel channel) throws IOException;
    }

    /** Finishes a temp once its channel is forced and closed, before it is moved. */
    @FunctionalInterface
    private interface TempFinish {
        void apply(Path temp) throws IOException;
    }

    private void publish(Path target, Overwrite overwrite, TempStart start, TempContent content, TempFinish finish)
            throws IOException {
        Path temp = tempSiblingOf(target);
        FileChannel channel = opener.open(temp);
        try {
            try (channel) {
                start.apply(temp);
                content.writeTo(channel);
                channel.force(true);
            }
            finish.apply(temp);
            move(temp, target, overwrite);
        } catch (Throwable failure) {
            deleteAfterFailure(temp, failure);
            throw failure;
        }
        forceDirectoryOf(target);
    }

    private static Set<ReplaceOption> optionsOf(ReplaceOption... options) {
        Objects.requireNonNull(options, "options must not be null");
        Set<ReplaceOption> chosen = EnumSet.noneOf(ReplaceOption.class);
        for (ReplaceOption option : options) {
            chosen.add(Objects.requireNonNull(option, "an option must not be null"));
        }
        return chosen;
    }

    /**
     * A replace's steps before its temp exists (see the class note): the
     * file it replaces — {@code target}, or the {@code .daw} file a followed
     * link leads to ({@link #followLinkToDawFile}) — is refused when the user
     * made it read-only; then, if asked, the temps earlier writes of it left
     * are deleted.
     *
     * @return the file the replace publishes
     */
    private static Path prepareReplace(Path target, ReplacePermission permission, Set<ReplaceOption> options)
            throws IOException {
        Path destination = options.contains(ReplaceOption.FOLLOW_LINK_TO_DAW_FILE)
                ? followLinkToDawFile(target)
                : target;
        if (permission == ReplacePermission.TARGET) {
            refuseReadOnlyTarget(destination);
        }
        if (options.contains(ReplaceOption.DELETE_EARLIER_TEMPS)) {
            deleteEarlierTemps(destination);
        }
        return destination;
    }

    private static FileSystemException notADawFileLink(Path link) {
        return new FileSystemException(link.toString(), null, NOT_A_DAW_FILE_LINK);
    }

    /** Whether {@code file}'s name ends in {@code .daw}, in any case. */
    private static boolean hasDawExtension(Path file) {
        Path name = file.getFileName();
        if (name == null) {
            return false;
        }
        String fileName = name.toString();
        return fileName.regionMatches(true, fileName.length() - DAW_EXTENSION.length(), DAW_EXTENSION, 0,
                DAW_EXTENSION.length());
    }

    /**
     * Deletes the temps earlier, unfinished writes of {@code target} left
     * beside it (ruling M2; see {@link ReplaceOption#DELETE_EARLIER_TEMPS}).
     * Nothing it meets fails the write.
     */
    private static void deleteEarlierTemps(Path target) {
        Path folder = target.toAbsolutePath().getParent();
        Path name = target.getFileName();
        if (folder == null || name == null) {
            return;
        }
        String targetName = name.toString();
        int deleted = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                if (isEarlierTempOf(entry, targetName) && deleteEarlierTemp(entry, targetName)) {
                    deleted++;
                }
            }
        } catch (IOException | RuntimeException unlisted) {
            LOG.log(Level.FINE, unlisted, () -> "Could not look for temp files that earlier writes of " + targetName
                    + " left in " + folder + ": " + unlisted);
        }
        if (deleted > 0) {
            int count = deleted;
            LOG.info(() -> "Deleted " + count + " temp file(s) that earlier, unfinished writes of " + targetName
                    + " left in " + folder);
        }
    }

    /** Exactly this writer's temp of {@code targetName}, and a regular file read without following a link. */
    private static boolean isEarlierTempOf(Path entry, String targetName) {
        Path name = entry.getFileName();
        return name != null
                && publishedNameOf(name.toString()).filter(targetName::equals).isPresent()
                && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS);
    }

    /** Deletes one earlier temp and returns whether it is gone; a delete that fails is logged at {@code FINE}. */
    private static boolean deleteEarlierTemp(Path temp, String targetName) {
        try {
            Files.delete(temp);
        } catch (NoSuchFileException gone) {
            // Already gone: it counts as deleted.
        } catch (IOException | RuntimeException notDeleted) {
            LOG.log(Level.FINE, notDeleted, () -> "Could not delete " + temp + ", a temp file an earlier write of "
                    + targetName + " left: " + notDeleted);
            return false;
        }
        return true;
    }

    /**
     * Refuses a replace of an existing target the user made read-only, before
     * any temp exists (see the class note). It asks about the entry the
     * rename replaces: a target that is a symbolic link is asked about
     * itself, never about the file it leads to, which the rename leaves
     * untouched (ruling N3) — a followed link's {@code .daw} file is no link,
     * so it is asked as named. On POSIX a link is not refused, since its own
     * mode bits protect nothing; for any other target one {@code access(2)}
     * call answers both questions, so a target deleted since the caller
     * looked is a new target, not a refused one. On Windows only the DOS
     * read-only flag refuses, the entry's own, a link's included; a target
     * missing, or whose flag cannot be read, goes on to the write.
     */
    private static void refuseReadOnlyTarget(Path target) throws AccessDeniedException {
        if (target.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            if (Files.isSymbolicLink(target)) {
                return;
            }
            try {
                target.getFileSystem().provider().checkAccess(target, AccessMode.WRITE);
            } catch (NoSuchFileException newTarget) {
                // Nothing to refuse: a new target.
            } catch (IOException notWritable) {
                AccessDeniedException refused = refusalOf(target);
                refused.initCause(notWritable);
                throw refused;
            }
        } else if (hasDosReadOnlyFlag(target)) {
            throw refusalOf(target);
        }
    }

    private static AccessDeniedException refusalOf(Path target) {
        return new AccessDeniedException(target.toString(), null,
                "the file is read-only or not writable; it was not replaced");
    }

    /**
     * Whether an existing {@code target} has the DOS read-only flag set — its
     * own, read without following a link (ruling N3); a missing one has not.
     */
    private static boolean hasDosReadOnlyFlag(Path target) {
        try {
            return Files.readAttributes(target, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isReadOnly();
        } catch (NoSuchFileException newTarget) {
            return false;
        } catch (IOException | RuntimeException unreadable) {
            LOG.log(Level.FINE, unreadable, () -> "The read-only flag of " + target
                    + " could not be read; the move decides whether it is replaced");
            return false;
        }
    }

    private static Path tempSiblingOf(Path target) {
        Path name = target.getFileName();
        if (name == null) {
            throw new IllegalArgumentException("target names no file: " + target);
        }
        return target.resolveSibling(tempNameOf(name.toString(), ThreadLocalRandom.current().nextLong()));
    }

    /** A temp sibling's name, in the grammar {@link #publishedNameOf} reads back. */
    private static String tempNameOf(String targetName, long nonce) {
        return TEMP_PREFIX + targetName + NONCE_SEPARATOR + NONCE_FORMAT.toHexDigits(nonce) + TEMP_SUFFIX;
    }

    /**
     * Whether {@code digits} is a nonce as {@link #tempNameOf} writes it: hex
     * digits that {@code NONCE_FORMAT} gives back unchanged (so uppercase is
     * no nonce).
     */
    private static boolean isNonce(String digits) {
        for (int i = 0; i < digits.length(); i++) {
            if (!HexFormat.isHexDigit(digits.charAt(i))) {
                return false;
            }
        }
        return NONCE_FORMAT.toHexDigits(HexFormat.fromHexDigitsToLong(digits)).equals(digits);
    }

    /** A temp start that gives the temp {@code origin}'s POSIX permission bits, read as {@code options} say. */
    private static TempStart permissionsOf(Path origin, LinkOption... options) {
        return temp -> carryPermissions(origin, temp, options);
    }

    /**
     * Sets {@code origin}'s POSIX permission bits on the new {@code temp},
     * where the file system has them (story 331, ruling G10). A missing
     * origin — a new target — has none to give. An origin that is not a
     * regular file gives none either: a link read without following it has
     * the link's own bits ({@code rwxrwxrwx} on Linux), which would make a
     * project file world-writable. Any failure to read or set
     * them is logged at {@code FINE}; the temp keeps the directory's defaults
     * and the write goes on.
     */
    private static void carryPermissions(Path origin, Path temp, LinkOption... originOptions) {
        PosixFileAttributeView originView =
                Files.getFileAttributeView(origin, PosixFileAttributeView.class, originOptions);
        if (originView == null) {
            return;
        }
        Set<PosixFilePermission> permissions;
        try {
            PosixFileAttributes attributes = originView.readAttributes();
            if (!attributes.isRegularFile()) {
                LOG.log(Level.FINE, () -> "Permissions of " + origin + " are not carried to " + temp
                        + ": not a regular file");
                return;
            }
            permissions = attributes.permissions();
        } catch (NoSuchFileException newTarget) {
            return;
        } catch (IOException | RuntimeException unreadable) {
            LOG.log(Level.FINE, unreadable, () -> "Permissions of " + origin + " could not be read; " + temp
                    + " keeps the directory's defaults");
            return;
        }
        try {
            PosixFileAttributeView tempView =
                    Files.getFileAttributeView(temp, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (tempView == null) {
                LOG.log(Level.FINE, () -> "Permissions of " + origin + " are not carried: " + temp
                        + " has no POSIX permissions");
                return;
            }
            tempView.setPermissions(permissions);
        } catch (IOException | RuntimeException refused) {
            LOG.log(Level.FINE, refused, () -> "Permissions of " + origin + " could not be set on " + temp
                    + "; it keeps the directory's defaults");
        }
    }

    private void move(Path temp, Path target, Overwrite overwrite) throws IOException {
        switch (overwrite) {
            case REPLACE -> replace(temp, target);
            case NEVER -> moveRetryingTransientDenials(temp, target);
        }
    }

    private void replace(Path temp, Path target) throws IOException {
        try {
            moveRetryingTransientDenials(temp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            try {
                mover.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable fallbackFailure) {
                if (fallbackFailure != notAtomic) {
                    fallbackFailure.addSuppressed(notAtomic);
                }
                throw fallbackFailure;
            }
        }
    }

    /**
     * One publish — the atomic replace, or a {@link Overwrite#NEVER} move
     * with no options — retried while it is refused the way Windows refuses
     * a move transiently, on every platform (see the class note). Each
     * refusal carries the one before it as suppressed; the last is thrown.
     * It never tries a fallback.
     */
    private void moveRetryingTransientDenials(Path temp, Path target, CopyOption... options) throws IOException {
        moveRetryingTransientDenials(temp, target, TRANSIENT_DENIAL_BACKOFF_MILLIS, options);
    }

    /**
     * {@link #moveRetryingTransientDenials(Path, Path, CopyOption...)} with
     * {@code backoff} as its pauses. Once the pauses so far pass the per-file
     * schedule's 130 ms, a {@code WARNING} says once that the target waits
     * for another program; a move that then succeeds says, at {@code INFO},
     * how long it waited in all — the field evidence the budget's future
     * validation needs (story 333, the studio engineer's ruling 3 Q3).
     */
    private void moveRetryingTransientDenials(Path temp, Path target, long[] backoff, CopyOption... options)
            throws IOException {
        FileSystemException previous = null;
        long waited = 0L;
        boolean warned = false;
        for (int attempt = 0; ; attempt++) {
            try {
                mover.move(temp, target, options);
                if (warned) {
                    long waitedInAll = waited;
                    LOG.info(() -> target + " was published after " + waitedInAll
                            + " ms of waiting for another program");
                }
                return;
            } catch (FileSystemException refused) {
                if (previous != null && previous != refused) {
                    refused.addSuppressed(previous);
                }
                if (!isTransientDenial(refused) || attempt == backoff.length) {
                    throw refused;
                }
                previous = refused;
                if (!warned && waited >= PER_FILE_RETRY_MILLIS) {
                    warned = true;
                    LOG.warning(() -> target + " is held by another program; waiting to publish it");
                }
                pauseBeforeRetry(backoff[attempt], refused);
                waited += backoff[attempt];
            }
        }
    }

    /** The per-file schedule's pauses in all: 130 ms. */
    private static final long PER_FILE_RETRY_MILLIS = sum(TRANSIENT_DENIAL_BACKOFF_MILLIS);

    private static long sum(long[] pauses) {
        long total = 0L;
        for (long pause : pauses) {
            total += pause;
        }
        return total;
    }

    /**
     * Whether a refused move is the kind Windows gives transiently: an access
     * denial or a plain {@link FileSystemException} (a sharing violation),
     * never a subclass that names a lasting cause — a
     * {@link FileAlreadyExistsException}, a {@link NoSuchFileException}, an
     * {@link AtomicMoveNotSupportedException}. It asks no platform: on POSIX
     * the same refusals are lasting, and their retries only delay the throw
     * by 130 ms (about 7.9 s for a directory publish).
     */
    private static boolean isTransientDenial(FileSystemException refused) {
        return refused instanceof AccessDeniedException || refused.getClass() == FileSystemException.class;
    }

    /** An interrupt ends the retries: the refusal is thrown with the interrupt suppressed, and the flag kept. */
    private static void pauseBeforeRetry(long millis, FileSystemException refused) throws FileSystemException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            refused.addSuppressed(interrupted);
            throw refused;
        }
    }

    private static void deleteAfterFailure(Path temp, Throwable failure) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException | RuntimeException notDeleted) {
            failure.addSuppressed(notDeleted);
        }
    }

    /** Best effort: Windows cannot open a directory as a channel. The file itself was forced before the move. */
    private static void forceDirectoryOf(Path target) {
        Path directory = target.toAbsolutePath().getParent();
        if (directory != null) {
            forceDirectory(directory);
        }
    }

    private static void keep(Path temp) {
        // Nothing to finish: the temp is published as written.
    }

    private static void writeFully(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) {
            channel.write(bytes);
        }
    }

    /** The temp's channel as a stream whose {@code close} leaves the channel open: the writer forces and closes it. */
    private static final class TempChannelStream extends OutputStream {

        private final FileChannel channel;

        TempChannelStream(FileChannel channel) {
            this.channel = channel;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            writeFully(channel, ByteBuffer.wrap(bytes, offset, length));
        }

        @Override
        public void close() {
            // The writer forces and closes the channel once the content is written.
        }
    }
}
