package com.benesquivelmusic.daw.core.persistence;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single-writer sentinel of story 331 (Persistence Integrity book §2.2,
 * §4.1): no durable file write in daw-core's persistence path —
 * {@code core/persistence/**}, {@code core/session/**},
 * {@code core/snapshot/**} — bypasses the {@link AtomicFileWriter}.
 *
 * <p>It reads every main source of those packages, comments stripped and
 * literals blanked ({@link PersistenceSourceScan#stripComments},
 * {@link PersistenceSourceScan#withoutLiterals}), and counts the write
 * primitives each file holds: {@code Files.writeString | write |
 * newOutputStream | newBufferedWriter | copy | move | newByteChannel |
 * createFile | createTempFile}, called or as a method reference;
 * {@code FileChannel.open} and {@code AsynchronousFileChannel.open}; a
 * file-system provider's writes reached through {@code provider()};
 * {@code new FileOutputStream | FileWriter | RandomAccessFile | PrintWriter
 * | PrintStream | StreamResult}, constructed or as a constructor reference
 * (every {@code StreamResult} counts, for a scan cannot tell the one built
 * from a file, a path or a systemId from the one that wraps a writer or a
 * stream); {@code File.renameTo} and {@code File.createNewFile}, called or as
 * a method reference; and any static
 * import from {@code Files} or a file channel, which would hide the call's
 * receiver (fails closed). The computed map, file → primitive → count, must
 * equal {@link #ALLOWED}, whose every entry says why it is not an atomic
 * replace: a new site fails, and so does an entry its site no longer holds.</p>
 *
 * <p>This is a source scan, not a reflection consumer: the
 * {@code dawg-annotations-reflection} rules for adding one (§4, "Adding a
 * new reflection consumer") do not apply, and it reads no annotation.</p>
 */
class DurableWritesGoThroughTheAtomicWriterTest {

    /** The core packages of the persistence path, each scanned with every subpackage. */
    private static final List<String> SCANNED_PACKAGES = List.of("persistence", "session", "snapshot");

    private static final Pattern FILES_WRITE = Pattern.compile(
            "\\bFiles\\s*(?:\\.|::)\\s*(writeString|write|newOutputStream|newBufferedWriter|copy|move"
            + "|newByteChannel|createFile|createTempFile)\\b");
    private static final Pattern CHANNEL_OPEN = Pattern.compile(
            "\\b(FileChannel|AsynchronousFileChannel)\\s*(?:\\.|::)\\s*open\\b");
    private static final Pattern PROVIDER_WRITE = Pattern.compile(
            "\\bprovider\\s*\\(\\s*\\)\\s*\\.\\s*(newOutputStream|newByteChannel|newFileChannel"
            + "|newAsynchronousFileChannel|copy|move)\\b");
    /**
     * The java.io and XML-transform constructions that open a file for
     * writing. A {@code StreamResult} built from a {@code File}, a
     * {@code Path}'s file or a systemId {@code String} is the transformer's own
     * file write; the scan cannot see a constructor argument's type, so it
     * counts every one, and the sites that wrap a {@code Writer} or an
     * {@code OutputStream} are declared.
     */
    private static final Pattern STREAM_CONSTRUCTION = Pattern.compile(
            "\\bnew\\s+(?:[A-Za-z_][\\w$]*\\s*\\.\\s*)*(FileOutputStream|FileWriter|RandomAccessFile|PrintWriter"
            + "|PrintStream|StreamResult)\\s*\\(");
    private static final Pattern STREAM_CONSTRUCTOR_REFERENCE = Pattern.compile(
            "\\b(FileOutputStream|FileWriter|RandomAccessFile|PrintWriter|PrintStream|StreamResult)\\s*::\\s*new\\b");
    /** {@code java.io.File}'s rename and create, called or as a method reference. */
    private static final Pattern FILE_RENAME_OR_CREATE = Pattern.compile("(?:\\.|::)\\s*(renameTo|createNewFile)\\b");
    private static final Pattern STATIC_IMPORT = Pattern.compile(
            "\\bimport\\s+static\\s+java\\s*\\.\\s*nio\\s*\\.\\s*(file\\s*\\.\\s*Files"
            + "|channels\\s*\\.\\s*(?:Asynchronous)?FileChannel)\\b");
    /** The writer's real file-system seam, which only the writer itself may name. */
    private static final Pattern REAL_SEAM = Pattern.compile("\\b(TEMP_CHANNEL_OPENER|FILE_SYSTEM_MOVER)\\b");

    /**
     * A file's write primitive the scan finds that is no atomic replace,
     * declared with why.
     *
     * @param file      the source, relative to {@code core/} ({@code persistence/ProjectLog.java})
     * @param primitive the primitive's label as the scan names it ({@code Files.writeString})
     * @param count     how many times the file holds it
     * @param reason    why this write is not an atomic replace through the writer
     */
    private record AllowedWrite(String file, String primitive, int count, String reason) {

        AllowedWrite {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("an allowed write must say why: " + file + " " + primitive);
            }
            if (count < 1) {
                throw new IllegalArgumentException("an allowed write is held at least once: " + file);
            }
        }
    }

    private static final List<AllowedWrite> ALLOWED = List.of(
            new AllowedWrite("persistence/AtomicFileWriter.java", "FileChannel.open", 2,
                    "the writer itself: the temp sibling's CREATE_NEW, WRITE, NOFOLLOW_LINKS channel and the"
                            + " best-effort READ channel of the directory fsync after the publish"),
            new AllowedWrite("persistence/AtomicFileWriter.java", "Files.move", 1,
                    "the writer itself: its production mover, Files::move, the publish of every write"),
            new AllowedWrite("persistence/ProjectLog.java", "Files.writeString", 1,
                    "the append-only audit log (CREATE, WRITE, APPEND): an event is a line added, never a"
                            + " replace of the file"),
            new AllowedWrite("persistence/ProjectLockManager.java", "Files.writeString", 1,
                    "the lock's acquisition: CREATE_NEW is the create-or-fail exclusion two sessions race on;"
                            + " the takeover and heartbeat replaces go through the writer"),
            new AllowedWrite("persistence/journal/JournalSegment.java", "FileChannel.open", 1,
                    "a journal segment is appended and forced per group by design (its class note): a failed"
                            + " group is truncated back, never a segment replaced"),
            new AllowedWrite("persistence/archive/ProjectArchiver.java", "Files.newOutputStream", 1,
                    "archive restore: each entry is extracted into a staging directory (or a target"
                            + " directory the caller made) that is published whole, not a file replace"),
            new AllowedWrite("persistence/archive/ProjectArchiver.java", "Files.move", 2,
                    "archive restore: the staging directory's publish (ATOMIC_MOVE) and its plain-move"
                            + " fallback, a directory publish rather than a file replace"),
            new AllowedWrite("session/dawproject/DawProjectSessionExporter.java", "Files.newOutputStream", 1,
                    "the DAWproject interchange export writes a file the user chose outside the project, no"
                            + " durability artifact of it; story 331 does not move it onto the writer"),
            new AllowedWrite("persistence/ProjectDocument.java", "new StreamResult", 1,
                    "the render's result wraps a StringWriter: the document becomes a string in memory, which"
                            + " the save, the checkpoint and the archive write through the writer"),
            new AllowedWrite("session/SessionManifestStore.java", "new StreamResult", 1,
                    "the manifest's result wraps the OutputStream the writer's Body is given: it writes into the"
                            + " writer's own temp"),
            new AllowedWrite("session/dawproject/DawProjectXmlSerializer.java", "new StreamResult", 1,
                    "the result wraps the OutputStream its caller passes — the DAWproject export's archive"
                            + " entry — and opens no file itself"));

    @Test
    void everyDurableWriteOfThePersistencePathIsTheWritersOrDeclared() {
        Map<String, String> sources = scannedSources();
        Map<String, Map<String, List<Integer>>> found = writePrimitives(sources);

        Map<String, Map<String, Integer>> declared = new TreeMap<>();
        Set<String> entries = new HashSet<>();
        for (AllowedWrite allowed : ALLOWED) {
            assertThat(entries.add(allowed.file() + " " + allowed.primitive()))
                    .as("one allow-list entry per file and primitive: %s %s", allowed.file(), allowed.primitive())
                    .isTrue();
            declared.computeIfAbsent(allowed.file(), file -> new TreeMap<>())
                    .put(allowed.primitive(), allowed.count());
        }

        List<String> discrepancies = new ArrayList<>();
        Set<String> files = new TreeSet<>(found.keySet());
        files.addAll(declared.keySet());
        for (String file : files) {
            Map<String, List<Integer>> foundInFile = found.getOrDefault(file, Map.of());
            Map<String, Integer> declaredInFile = declared.getOrDefault(file, Map.of());
            Set<String> primitives = new TreeSet<>(foundInFile.keySet());
            primitives.addAll(declaredInFile.keySet());
            for (String primitive : primitives) {
                List<Integer> lines = foundInFile.getOrDefault(primitive, List.of());
                int allowedCount = declaredInFile.getOrDefault(primitive, 0);
                if (lines.size() > allowedCount) {
                    discrepancies.add(file + ": " + primitive + " at line(s) " + lines + ", " + allowedCount
                            + " declared — write through AtomicFileWriter, or declare the site with its reason");
                } else if (lines.size() < allowedCount) {
                    discrepancies.add(file + ": " + primitive + " declared " + allowedCount + " time(s), found "
                            + lines.size() + (lines.isEmpty() ? "" : " at line(s) " + lines)
                            + " — the allow-list entry is stale; correct or delete it");
                }
            }
        }
        assertThat(discrepancies).as("write primitives outside the atomic writer").isEmpty();
    }

    @Test
    void onlyTheWriterNamesItsRealFileSystemSeam() {
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, String> source : scannedSources().entrySet()) {
            if (source.getKey().equals("persistence/AtomicFileWriter.java")) {
                continue;
            }
            Matcher seam = REAL_SEAM.matcher(PersistenceSourceScan.withoutLiterals(source.getValue()));
            while (seam.find()) {
                offenders.add(source.getKey() + ":" + lineOf(source.getValue(), seam.start()) + " " + seam.group(1));
            }
        }
        assertThat(offenders)
                .as("the production opener and mover open and move files the sentinel cannot see; only the"
                        + " writer's no-arg constructor uses them")
                .isEmpty();
    }

    @Test
    void theScanReadsEveryPackageOfThePersistencePath() {
        Map<String, String> sources = scannedSources();

        assertThat(sources.keySet().stream().filter(file -> file.startsWith("persistence/")).count())
                .as("the persistence package and its subpackages").isGreaterThanOrEqualTo(60);
        assertThat(sources.keySet().stream().filter(file -> file.startsWith("session/")).count())
                .as("the session package and its subpackages").isGreaterThanOrEqualTo(5);
        assertThat(sources.keySet().stream().filter(file -> file.startsWith("snapshot/")).count())
                .as("the snapshot package").isGreaterThanOrEqualTo(3);
        assertThat(sources).containsKeys("persistence/AtomicFileWriter.java", "persistence/ProjectManager.java",
                "persistence/CheckpointManager.java", "persistence/snapshot/SnapshotStore.java",
                "session/SessionManifestStore.java");
        assertThat(writePrimitives(sources)).as("the scan finds the writer's own primitives")
                .containsKey("persistence/AtomicFileWriter.java");
    }

    @Test
    void theScanCountsEveryPrimitiveShapeAndNothingInACommentOrALiteral() {
        String source = """
                import static java.nio.file.Files.move;
                import static java.nio.channels.FileChannel.open;
                final class Writer {
                    // Files.writeString(target, xml) in a comment is no write.
                    /* nor is FileChannel.open(target) in a block comment */
                    private static final String LITERAL = "Files.copy(a, b) and new FileWriter(f)";
                    void write(Path target, Path other) throws IOException {
                        Files.writeString(target, "x");
                        Files
                            .write(target, new byte[0]);
                        java.nio.file.Files.copy(target, other);
                        Mover mover = Files::move;
                        try (FileChannel channel = FileChannel.open(target)) { }
                        target.getFileSystem().provider().newOutputStream(target);
                        new java.io.FileOutputStream(target.toFile()).close();
                        Function<File, PrintWriter> writer = PrintWriter::new;
                        new RandomAccessFile(target.toFile(), "rw").close();
                        target.toFile().renameTo(other.toFile());
                        other.toFile().createNewFile();
                        transformer.transform(source, new StreamResult(target.toFile()));
                        transformer.transform(source, new javax.xml.transform.stream.StreamResult(
                                target.toUri().toString()));
                        Function<File, StreamResult> result = StreamResult::new;
                        BiPredicate<File, File> rename = File::renameTo;
                        // target.toFile().renameTo(other.toFile()) in a comment is no rename.
                        String renamed = "a.renameTo(b) and new StreamResult(f)";
                    }
                }
                """;

        Map<String, Map<String, List<Integer>>> found = writePrimitives(
                Map.of("Writer.java", PersistenceSourceScan.stripComments(source)));

        assertThat(found).containsOnlyKeys("Writer.java");
        // A static import counts twice: as the import, and as the member it names.
        assertThat(found.get("Writer.java")).isEqualTo(Map.ofEntries(
                Map.entry("import static", List.of(1, 2)),
                Map.entry("Files.writeString", List.of(8)),
                Map.entry("Files.write", List.of(9)),
                Map.entry("Files.copy", List.of(11)),
                Map.entry("Files.move", List.of(1, 12)),
                Map.entry("FileChannel.open", List.of(2, 13)),
                Map.entry("FileSystemProvider.newOutputStream", List.of(14)),
                Map.entry("new FileOutputStream", List.of(15)),
                Map.entry("new PrintWriter", List.of(16)),
                Map.entry("new RandomAccessFile", List.of(17)),
                Map.entry("File.renameTo", List.of(18, 24)),
                Map.entry("File.createNewFile", List.of(19)),
                Map.entry("new StreamResult", List.of(20, 21, 23))));
    }

    @Test
    void aStreamResultThatWrapsAWriterOrAStreamIsCountedSoItsSiteMustBeDeclared() {
        String source = """
                final class Renderer {
                    String render(Transformer transformer, Source source, OutputStream out) throws Exception {
                        StringWriter writer = new StringWriter();
                        transformer.transform(source, new StreamResult(writer));
                        transformer.transform(source, new StreamResult(out));
                        return writer.toString();
                    }
                }
                """;

        Map<String, Map<String, List<Integer>>> found = writePrimitives(
                Map.of("Renderer.java", PersistenceSourceScan.stripComments(source)));

        assertThat(found).as("the scan cannot see an argument's type: an undeclared site fails the sentinel")
                .isEqualTo(Map.of("Renderer.java", Map.of("new StreamResult", List.of(4, 5))));
    }

    /** Every main source of {@link #SCANNED_PACKAGES}, comments stripped, keyed {@code <package>/<relative path>}. */
    private static Map<String, String> scannedSources() {
        Map<String, String> sources = new TreeMap<>();
        for (String corePackage : SCANNED_PACKAGES) {
            Path root = PersistenceSourceScan.locateCoreSource(corePackage);
            assertThat(Files.isDirectory(root)).as("main sources of core/%s at %s", corePackage, root).isTrue();
            PersistenceSourceScan.sources(root)
                    .forEach((relative, code) -> sources.put(corePackage + "/" + relative, code));
        }
        return sources;
    }

    /**
     * The write primitives each source holds — file → primitive label → the
     * line of each occurrence — read from the code with its literals blanked;
     * files that hold none are left out.
     */
    private static Map<String, Map<String, List<Integer>>> writePrimitives(Map<String, String> sources) {
        Map<String, Map<String, List<Integer>>> found = new TreeMap<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            String code = PersistenceSourceScan.withoutLiterals(source.getValue());
            Map<String, List<Integer>> inFile = new TreeMap<>();
            collect(inFile, code, FILES_WRITE, "Files.");
            collect(inFile, code, CHANNEL_OPEN, "", ".open");
            collect(inFile, code, PROVIDER_WRITE, "FileSystemProvider.");
            collect(inFile, code, STREAM_CONSTRUCTION, "new ");
            collect(inFile, code, STREAM_CONSTRUCTOR_REFERENCE, "new ");
            collect(inFile, code, FILE_RENAME_OR_CREATE, "File.");
            Matcher staticImport = STATIC_IMPORT.matcher(code);
            while (staticImport.find()) {
                inFile.computeIfAbsent("import static", label -> new ArrayList<>())
                        .add(lineOf(code, staticImport.start()));
            }
            if (!inFile.isEmpty()) {
                found.put(source.getKey(), inFile);
            }
        }
        return found;
    }

    private static void collect(Map<String, List<Integer>> inFile, String code, Pattern shape, String prefix) {
        collect(inFile, code, shape, prefix, "");
    }

    private static void collect(Map<String, List<Integer>> inFile, String code, Pattern shape, String prefix,
                                String suffix) {
        Matcher match = shape.matcher(code);
        while (match.find()) {
            inFile.computeIfAbsent(prefix + match.group(1) + suffix, label -> new ArrayList<>())
                    .add(lineOf(code, match.start()));
        }
    }

    private static int lineOf(String code, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
