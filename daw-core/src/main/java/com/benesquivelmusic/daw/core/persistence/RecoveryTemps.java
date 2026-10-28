package com.benesquivelmusic.daw.core.persistence;

import org.w3c.dom.Document;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/** The exact atomic-writer temps inspected by a writable, locked open. */
public record RecoveryTemps(List<Path> files, Path newerHead) {
    private static final Logger LOG = Logger.getLogger(RecoveryTemps.class.getName());
    public RecoveryTemps { files = List.copyOf(files); }

    /**
     * Lists every exact atomic-writer temp of {@code folder} into {@link #files()}, which a plain
     * open deletes, and assesses each temp that could be an interrupted save of
     * {@code project.daw}: named for the head, beside it (beside a linked head's target) and
     * written after it. Every open is a potential recovery (Persistence Integrity book §2.6), the
     * recovery gate on every open path decides on the evidence before the new session's journal
     * opens (Persistence Integrity book §4.7), and it reads an open's evidence once, before its
     * one dialog describes it (story 332's rule of one plan per gated open). So such a temp has
     * one of three outcomes, decided by the step that stops it (read, decode, markers, parse, read
     * as a project), never by the type of the failure:
     * <ul>
     *   <li><b>complete</b>: strict UTF-8 ({@link ProjectDeserializer#decodeUtf8}, the decoding
     *       its promotion repeats), both {@code daw-project} markers, well-formed, and a project
     *       this build reads. The newest such temp is {@link #newerHead()}, the interrupted save
     *       the dialog offers.</li>
     *   <li><b>incomplete</b>: bytes that are not strict UTF-8, a missing marker, or XML that is
     *       not well-formed, the forms a write cut short by a crash takes. It is no candidate and
     *       stays in {@link #files()}, so a plain open deletes it.</li>
     *   <li><b>cannot be assessed</b>: it cannot be read (it may also have vanished since it was
     *       listed), or this build refuses the complete document it holds, for example one with a
     *       newer schema version. Inspection throws and nothing is deleted: evidence that was never
     *       read can be neither described nor safely discarded.</li>
     * </ul>
     * Older temps and temps of other files are never read, and stay in {@link #files()}. The
     * head's time and each temp's time are read outside every classification, so a time that
     * cannot be read fails the inspection.
     *
     * @throws IOException if the evidence cannot be listed or its times read (including a project
     *                     file link that cannot be resolved), or a newer head temp cannot be
     *                     assessed; the last names the temp and says nothing was deleted
     */
    public static RecoveryTemps inspect(Path folder) throws IOException {
        Path projectFile = folder.resolve(ProjectManager.PROJECT_FILE_NAME);
        Path head = Files.isSymbolicLink(projectFile) ? projectFile.toRealPath() : projectFile;
        LinkedHashSet<Path> temps = new LinkedHashSet<>();
        collect(folder, null, temps);
        collect(folder.resolve("snapshots"), null, temps);
        collect(folder.resolve("checkpoints"), null, temps);
        if (!head.getParent().equals(folder)) collect(head.getParent(), head.getFileName().toString(), temps);
        Path newest = null;
        FileTime newestWrittenAt = null;
        for (Path temp : temps) {
            Optional<String> target = AtomicFileWriter.publishedNameOf(temp.getFileName().toString());
            if (!temp.getParent().equals(head.getParent()) || !target.orElse("").equals(head.getFileName().toString())) continue;
            FileTime writtenAt = Files.getLastModifiedTime(temp);
            if (writtenAt.compareTo(Files.getLastModifiedTime(head)) <= 0) continue;
            if (!isComplete(temp, read(temp), folder)) {
                LOG.fine(() -> "An unfinished head temp is not a complete recovery base: " + temp);
                continue;
            }
            if (newest == null || writtenAt.compareTo(newestWrittenAt) > 0) {
                newest = temp;
                newestWrittenAt = writtenAt;
            }
        }
        return new RecoveryTemps(new ArrayList<>(temps), newest);
    }

    /** The temp's bytes. A temp that cannot be read cannot be assessed, so the open fails before anything is deleted. */
    private static byte[] read(Path temp) throws IOException {
        try {
            return Files.readAllBytes(temp);
        } catch (IOException unreadable) {
            throw new IOException("The interrupted save " + temp + " could not be read (" + reason(unreadable)
                    + "), so it was not checked for recovery; nothing was deleted. "
                    + "Make it readable and open the project again", unreadable);
        }
    }

    /**
     * Whether {@code bytes} are a complete project document: {@code false} at the first step a
     * crash truncation stops (decode, markers, parse); a throw, naming {@code temp}, when this
     * build refuses a document that passed them all.
     */
    private static boolean isComplete(Path temp, byte[] bytes, Path folder) throws IOException {
        String xml;
        try {
            xml = ProjectDeserializer.decodeUtf8(bytes);
        } catch (CharacterCodingException notUtf8) {
            return false;
        }
        if (!xml.contains("<daw-project") || !xml.stripTrailing().endsWith("</daw-project>")) return false;
        Document document;
        try {
            document = ProjectDeserializer.parseXml(xml);
        } catch (IOException notWellFormed) {
            return false;
        }
        try {
            new ProjectDeserializer().deserialize(document, folder);
        } catch (RuntimeException refused) {
            throw new IOException("The interrupted save " + temp + " could not be checked for recovery ("
                    + reason(refused) + "); nothing was deleted", refused);
        }
        return true;
    }

    /**
     * Why {@code failure} happened, for a message that already names the temp: a
     * {@link FileSystemException}'s reason, any other failure's message, or its type when there is
     * none; never the path. The rule of {@code JournalState.reason}, mirrored here because the
     * journal package depends on this one.
     */
    private static String reason(Throwable failure) {
        String reason = failure instanceof FileSystemException fileSystemFailure
                ? fileSystemFailure.getReason()
                : failure.getMessage();
        if (reason != null && !reason.isBlank()) return reason;
        String simpleName = failure.getClass().getSimpleName();
        return simpleName.isEmpty() ? failure.getClass().getName() : simpleName;
    }

    private static void collect(Path directory, String onlyTarget, java.util.Set<Path> result) throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
            entries.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> AtomicFileWriter.publishedNameOf(path.getFileName().toString())
                            .filter(name -> onlyTarget == null || name.equals(onlyTarget)).isPresent())
                    .forEach(result::add);
        }
    }

    public void clean() throws IOException {
        for (Path path : files) Files.deleteIfExists(path);
        LOG.info(() -> "Handled " + files.size() + " atomic-writer temp file(s) during project recovery");
    }
}
