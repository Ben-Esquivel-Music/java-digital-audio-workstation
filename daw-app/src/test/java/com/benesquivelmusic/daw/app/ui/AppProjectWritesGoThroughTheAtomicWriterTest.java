package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single-writer sentinel of story 331, application half (Persistence
 * Integrity book §2.2, §4.1; the story's test "Single-writer adoption"): no
 * daw-app main source on the persistence path writes a file outside the
 * {@code AtomicFileWriter}.
 *
 * <p>A main source is on the persistence path when its code — comments
 * stripped, literals kept ({@link SourceScanSupport#stripComments}) — names
 * the project file (a literal holding {@code project.daw},
 * {@code PROJECT_FILE_NAME}, {@code projectFile()}), the checkpoint directory
 * (the literal {@code "checkpoints"}, {@code CHECKPOINT_DIR_NAME},
 * {@code ProjectDiskUsage.AUTOSAVES_DIR}, {@code checkpointDirectory(}) or a
 * persistence manager
 * ({@code ProjectManager}, {@code CheckpointManager}, {@code ProjectArchiver},
 * {@code SnapshotStore}). A name in a comment does not count; a name in a
 * literal does, so the scan errs towards reading a file. Every such source
 * is read with its literals blanked too
 * ({@link SourceScanSupport#stripStringLiterals}), and the write primitives it
 * holds are counted with exactly the daw-core sentinel's patterns
 * ({@code DurableWritesGoThroughTheAtomicWriterTest}, which
 * {@link #theWritePrimitivesAreTheCoreSentinelsOwn()} reads to keep the two
 * equal): {@code Files.writeString | write | newOutputStream |
 * newBufferedWriter | copy | move | newByteChannel | createFile |
 * createTempFile}, called or as a method reference; {@code FileChannel.open}
 * and {@code AsynchronousFileChannel.open}; a file-system provider's writes
 * reached through {@code provider()}; {@code new FileOutputStream |
 * FileWriter | RandomAccessFile | PrintWriter | PrintStream | StreamResult},
 * constructed or as a constructor reference (every {@code StreamResult}
 * counts: a scan cannot see whether it wraps a file or a writer);
 * {@code File.renameTo} and {@code File.createNewFile}, called or as a
 * method reference; and any static import from {@code Files} or a
 * file channel, which would hide the call's receiver (fails closed). The
 * computed map, file → primitive → count, must equal {@link #ALLOWED}, whose
 * every entry says why it is not an atomic replace: a new site fails, and
 * so does an entry its site no longer holds.</p>
 *
 * <p>This is a source scan, not a reflection consumer: the
 * {@code dawg-annotations-reflection} rules for adding one (§4, "Adding a
 * new reflection consumer") do not apply, and it reads no annotation.</p>
 */
class AppProjectWritesGoThroughTheAtomicWriterTest {

    /** The daw-core sentinel whose write primitives this one counts, relative to the repository root. */
    private static final String CORE_SENTINEL = "daw-core/src/test/java/com/benesquivelmusic/daw/core/persistence/"
            + "DurableWritesGoThroughTheAtomicWriterTest.java";

    // ── the write primitives: the daw-core sentinel's, verbatim ──────────────

    private static final Pattern FILES_WRITE = Pattern.compile(
            "\\bFiles\\s*(?:\\.|::)\\s*(writeString|write|newOutputStream|newBufferedWriter|copy|move"
            + "|newByteChannel|createFile|createTempFile)\\b");
    private static final Pattern CHANNEL_OPEN = Pattern.compile(
            "\\b(FileChannel|AsynchronousFileChannel)\\s*(?:\\.|::)\\s*open\\b");
    private static final Pattern PROVIDER_WRITE = Pattern.compile(
            "\\bprovider\\s*\\(\\s*\\)\\s*\\.\\s*(newOutputStream|newByteChannel|newFileChannel"
            + "|newAsynchronousFileChannel|copy|move)\\b");
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

    /** The daw-core sentinel's patterns by field name: this one's must be exactly these. */
    private static final Map<String, Pattern> CORE_PATTERNS = orderedPatterns();

    // ── what puts a file on the persistence path ─────────────────────────────

    private static final Pattern NAMES_THE_PROJECT_FILE = Pattern.compile(
            "\"[^\"\\n]*project\\.daw[^\"\\n]*\"|\\bPROJECT_FILE_NAME\\b|\\bprojectFile\\s*\\(\\s*\\)");
    private static final Pattern NAMES_THE_CHECKPOINT_DIRECTORY = Pattern.compile(
            "\"checkpoints/?\"|\\bCHECKPOINT_DIR_NAME\\b|\\bAUTOSAVES_DIR\\b|\\bcheckpointDirectory\\s*\\(");
    private static final Pattern NAMES_A_PERSISTENCE_MANAGER = Pattern.compile(
            "\\b(?:ProjectManager|CheckpointManager|ProjectArchiver|SnapshotStore)\\b");

    /**
     * A file's write primitive the scan finds that is no atomic replace,
     * declared with why.
     *
     * @param file      the source, relative to {@code com/benesquivelmusic/daw/app}
     *                  ({@code ui/ProjectLifecycleController.java})
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

    /**
     * The writes on the application's persistence path that are no atomic
     * replace, each with why. None: since story 331 the recovered project
     * file and its backup, the restored archive's {@code project.daw} and the
     * migration roll-back's copy go through the writer, and every other
     * project write is the project manager's, the checkpoint manager's, the
     * archiver's or the snapshot store's, which the daw-core sentinel reads.
     * A new write that is no replace by design is declared here, with its
     * reason.
     */
    private static final List<AllowedWrite> ALLOWED = List.of();

    @Test
    void everyWriteOnTheApplicationsPersistencePathIsTheWritersOrDeclared() {
        Map<String, Map<String, List<String>>> found = writePrimitives(persistencePath(mainSources()));

        assertThat(discrepancies(found, ALLOWED)).as("write primitives outside the atomic writer").isEmpty();
    }

    @Test
    void aNewSiteAStaleEntryAndATwiceDeclaredEntryEachFailTheComparison() {
        Map<String, Map<String, List<String>>> found = new TreeMap<>();
        found.put("ui/Declared.java", Map.of("Files.copy", List.of("'Files.copy(a, b);'")));
        found.put("ui/Undeclared.java", Map.of("Files.writeString", List.of("'Files.writeString(target, xml);'")));
        List<AllowedWrite> allowed = List.of(
                new AllowedWrite("ui/Declared.java", "Files.copy", 1, "a copy of a file the user chose"),
                new AllowedWrite("ui/Gone.java", "Files.move", 1, "a site the source no longer holds"),
                new AllowedWrite("ui/Twice.java", "Files.write", 1, "declared once"),
                new AllowedWrite("ui/Twice.java", "Files.write", 1, "and declared again"));

        assertThat(discrepancies(found, allowed)).containsExactly(
                "ui/Gone.java: Files.move declared 1 time(s), found 0 — the allow-list entry is stale; correct or"
                        + " delete it",
                "ui/Twice.java: Files.write declared more than once — one allow-list entry per file and primitive",
                "ui/Twice.java: Files.write declared 1 time(s), found 0 — the allow-list entry is stale; correct or"
                        + " delete it",
                "ui/Undeclared.java: Files.writeString at ['Files.writeString(target, xml);'], 0 declared — write"
                        + " through AtomicFileWriter, or declare the site with its reason");
        assertThat(discrepancies(found, allowed.subList(0, 1)))
                .as("a declared site and nothing else: only the undeclared one fails").singleElement()
                .asString().startsWith("ui/Undeclared.java: ");
    }

    @Test
    void onlyTheWriterNamesItsRealFileSystemSeam() {
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, String> source : persistencePath(mainSources()).entrySet()) {
            String code = SourceScanSupport.stripStringLiterals(source.getValue());
            Matcher seam = REAL_SEAM.matcher(code);
            while (seam.find()) {
                offenders.add(source.getKey() + ": " + seam.group(1) + " in " + statementAt(code, seam.start()));
            }
        }
        assertThat(offenders)
                .as("the production opener and mover open and move files this scan cannot see; only the"
                        + " writer's no-arg constructor uses them")
                .isEmpty();
    }

    @Test
    void theScanReadsTheWholeApplicationAndSelectsItsPersistencePath() {
        Map<String, String> sources = mainSources();
        Map<String, String> selected = persistencePath(sources);

        assertThat(sources).as("every daw-app main source is read").hasSizeGreaterThan(300);
        // Ten files are selected today: the floor is that count, so a selection that
        // shrinks fails here until the floor is lowered on purpose.
        assertThat(selected.keySet())
                .as("the persistence path of the application")
                .contains("ui/ProjectLifecycleController.java", "ui/MainController.java",
                        "ui/SnapshotsController.java", "ui/status/CheckpointStatusListener.java")
                .hasSizeGreaterThanOrEqualTo(10);
        assertThat(selected).as("a file whose code names nothing of the path is not selected")
                .doesNotContainKey("ui/status/StatusStripCell.java");
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
                        Function<File, StreamResult> result = StreamResult::new;
                        BiPredicate<File, File> rename = File::renameTo;
                        String renamed = "a.renameTo(b) and new StreamResult(f)";
                    }
                }
                """;

        Map<String, Map<String, List<String>>> found = writePrimitives(
                Map.of("Writer.java", SourceScanSupport.stripComments(source)));

        assertThat(found).containsOnlyKeys("Writer.java");
        Map<String, Integer> counts = new TreeMap<>();
        found.get("Writer.java").forEach((primitive, sites) -> counts.put(primitive, sites.size()));
        // A static import counts twice: as the import, and as the member it names.
        assertThat(counts).isEqualTo(Map.ofEntries(
                Map.entry("import static", 2),
                Map.entry("Files.writeString", 1),
                Map.entry("Files.write", 1),
                Map.entry("Files.copy", 1),
                Map.entry("Files.move", 2),
                Map.entry("FileChannel.open", 2),
                Map.entry("FileSystemProvider.newOutputStream", 1),
                Map.entry("new FileOutputStream", 1),
                Map.entry("new PrintWriter", 1),
                Map.entry("new RandomAccessFile", 1),
                Map.entry("File.renameTo", 2),
                Map.entry("File.createNewFile", 1),
                Map.entry("new StreamResult", 2)));
    }

    @Test
    void aFileIsOnThePersistencePathOnlyWhenItsCodeNamesPartOfIt() {
        Map<String, String> sources = new TreeMap<>();
        sources.put("ProjectFileLiteral.java", "class A { Path p = dir.resolve(\"project.daw\"); }");
        sources.put("BackupLiteral.java", "class B { String n = \"project.daw.recovered.\" + stamp; }");
        sources.put("ProjectFileOfAContext.java", "class C { Path p = context.projectFile(); }");
        sources.put("CheckpointDirectory.java", "class D { Path p = dir.resolve(\"checkpoints\"); }");
        sources.put("CheckpointDirectoryOfAContext.java", "class E { Path p = context.checkpointDirectory(); }");
        sources.put("AutosavesDirectory.java",
                "class E2 { Path p = dir.resolve(ProjectDiskUsage.AUTOSAVES_DIR); }");
        sources.put("Manager.java", "class F { private final ProjectManager manager; }");
        sources.put("Archiver.java", "class G { ProjectArchiver archiver = new ProjectArchiver(); }");
        sources.put("OnlyInAComment.java", "/** Writes project.daw through the ProjectManager. */ class H { }");
        sources.put("OnlyInALineComment.java", "class I { // the \"checkpoints\" folder\n int x; }");
        sources.put("Unrelated.java", "class J { Path p = home.resolve(\"workspaces.json\"); }");

        Map<String, String> stripped = new TreeMap<>();
        sources.forEach((file, code) -> stripped.put(file, SourceScanSupport.stripComments(code)));

        assertThat(persistencePath(stripped).keySet()).containsExactlyInAnyOrder(
                "ProjectFileLiteral.java", "BackupLiteral.java", "ProjectFileOfAContext.java",
                "CheckpointDirectory.java", "CheckpointDirectoryOfAContext.java", "AutosavesDirectory.java",
                "Manager.java", "Archiver.java");
    }

    @Test
    void theWritePrimitivesAreTheCoreSentinelsOwn() throws IOException {
        Path repositoryRoot = SourceScanSupport.locateDawAppModule().getParent();
        assertThat(repositoryRoot).as("the daw-app module has a parent: the repository root").isNotNull();
        Path coreSentinel = repositoryRoot.resolve(CORE_SENTINEL);
        assertThat(coreSentinel).as("the daw-core sentinel this one mirrors").isRegularFile();

        Map<String, String> coreRegexes = patternsDeclaredIn(Files.readString(coreSentinel, StandardCharsets.UTF_8));
        Map<String, String> ours = new TreeMap<>();
        CORE_PATTERNS.forEach((name, pattern) -> ours.put(name, pattern.pattern()));

        assertThat(coreRegexes)
                .as("the daw-core sentinel's patterns, each read from its source: change both sentinels together")
                .isEqualTo(ours);
    }

    // ── the scan ─────────────────────────────────────────────────────────────

    /** Every daw-app main source, comments stripped, keyed by its path relative to {@code com/benesquivelmusic/daw/app}. */
    private static Map<String, String> mainSources() {
        Path root = SourceScanSupport.locateDawAppModule().resolve("src/main/java/com/benesquivelmusic/daw/app");
        assertThat(root).as("daw-app main sources").isDirectory();
        Map<String, String> sources = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                sources.put(root.relativize(file).toString().replace('\\', '/'),
                        SourceScanSupport.stripComments(Files.readString(file, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sources;
    }

    /** The sources, comments stripped, whose code names the project file, the checkpoint directory or a manager. */
    private static Map<String, String> persistencePath(Map<String, String> sources) {
        Map<String, String> selected = new TreeMap<>();
        sources.forEach((file, code) -> {
            if (NAMES_THE_PROJECT_FILE.matcher(code).find()
                    || NAMES_THE_CHECKPOINT_DIRECTORY.matcher(code).find()
                    || NAMES_A_PERSISTENCE_MANAGER.matcher(code).find()) {
                selected.put(file, code);
            }
        });
        return selected;
    }

    /**
     * The scan's findings compared with an allow-list, one line per
     * discrepancy in file order — a site the list does not declare, an entry
     * whose site the source no longer holds, an entry declared twice; empty
     * when they agree.
     *
     * @param found   file → primitive label → the statement of each occurrence
     * @param allowed the declared writes
     * @return the discrepancies
     */
    private static List<String> discrepancies(Map<String, Map<String, List<String>>> found,
                                              List<AllowedWrite> allowed) {
        Map<String, Map<String, Integer>> declared = new TreeMap<>();
        Map<String, List<String>> declaredTwice = new TreeMap<>();
        for (AllowedWrite entry : allowed) {
            Integer earlier = declared.computeIfAbsent(entry.file(), file -> new TreeMap<>())
                    .putIfAbsent(entry.primitive(), entry.count());
            if (earlier != null) {
                declaredTwice.computeIfAbsent(entry.file(), file -> new ArrayList<>()).add(entry.file() + ": "
                        + entry.primitive() + " declared more than once — one allow-list entry per file and primitive");
            }
        }

        List<String> discrepancies = new ArrayList<>();
        Set<String> files = new TreeSet<>(found.keySet());
        files.addAll(declared.keySet());
        for (String file : files) {
            discrepancies.addAll(declaredTwice.getOrDefault(file, List.of()));
            Map<String, List<String>> foundInFile = found.getOrDefault(file, Map.of());
            Map<String, Integer> declaredInFile = declared.getOrDefault(file, Map.of());
            Set<String> primitives = new TreeSet<>(foundInFile.keySet());
            primitives.addAll(declaredInFile.keySet());
            for (String primitive : primitives) {
                List<String> sites = foundInFile.getOrDefault(primitive, List.of());
                int allowedCount = declaredInFile.getOrDefault(primitive, 0);
                if (sites.size() > allowedCount) {
                    discrepancies.add(file + ": " + primitive + " at " + sites + ", " + allowedCount
                            + " declared — write through AtomicFileWriter, or declare the site with its reason");
                } else if (sites.size() < allowedCount) {
                    discrepancies.add(file + ": " + primitive + " declared " + allowedCount + " time(s), found "
                            + sites.size() + (sites.isEmpty() ? "" : " at " + sites)
                            + " — the allow-list entry is stale; correct or delete it");
                }
            }
        }
        return discrepancies;
    }

    /**
     * The write primitives each source holds — file → primitive label → the
     * statement of each occurrence — read from the code with its literals
     * blanked; files that hold none are left out.
     */
    private static Map<String, Map<String, List<String>>> writePrimitives(Map<String, String> sources) {
        Map<String, Map<String, List<String>>> found = new TreeMap<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            String code = SourceScanSupport.stripStringLiterals(source.getValue());
            Map<String, List<String>> inFile = new TreeMap<>();
            collect(inFile, code, FILES_WRITE, "Files.", "");
            collect(inFile, code, CHANNEL_OPEN, "", ".open");
            collect(inFile, code, PROVIDER_WRITE, "FileSystemProvider.", "");
            collect(inFile, code, STREAM_CONSTRUCTION, "new ", "");
            collect(inFile, code, STREAM_CONSTRUCTOR_REFERENCE, "new ", "");
            collect(inFile, code, FILE_RENAME_OR_CREATE, "File.", "");
            Matcher staticImport = STATIC_IMPORT.matcher(code);
            while (staticImport.find()) {
                inFile.computeIfAbsent("import static", label -> new ArrayList<>())
                        .add(statementAt(code, staticImport.start()));
            }
            if (!inFile.isEmpty()) {
                found.put(source.getKey(), inFile);
            }
        }
        return found;
    }

    private static void collect(Map<String, List<String>> inFile, String code, Pattern shape, String prefix,
                                String suffix) {
        Matcher match = shape.matcher(code);
        while (match.find()) {
            inFile.computeIfAbsent(prefix + match.group(1) + suffix, label -> new ArrayList<>())
                    .add(statementAt(code, match.start()));
        }
    }

    /**
     * The line of {@code code} at {@code offset}, trimmed — how a site is
     * named in a failure: comment stripping folds comments, so a line number
     * of the scanned code is not the source's.
     */
    private static String statementAt(String code, int offset) {
        int start = code.lastIndexOf('\n', offset) + 1;
        int end = code.indexOf('\n', offset);
        return "'" + code.substring(start, end < 0 ? code.length() : end).trim() + "'";
    }

    // ── the daw-core sentinel's patterns ─────────────────────────────────────

    private static Map<String, Pattern> orderedPatterns() {
        Map<String, Pattern> patterns = new LinkedHashMap<>();
        patterns.put("FILES_WRITE", FILES_WRITE);
        patterns.put("CHANNEL_OPEN", CHANNEL_OPEN);
        patterns.put("PROVIDER_WRITE", PROVIDER_WRITE);
        patterns.put("STREAM_CONSTRUCTION", STREAM_CONSTRUCTION);
        patterns.put("STREAM_CONSTRUCTOR_REFERENCE", STREAM_CONSTRUCTOR_REFERENCE);
        patterns.put("FILE_RENAME_OR_CREATE", FILE_RENAME_OR_CREATE);
        patterns.put("STATIC_IMPORT", STATIC_IMPORT);
        patterns.put("REAL_SEAM", REAL_SEAM);
        return patterns;
    }

    /** A {@code Pattern} field of a Java source, its regex written as one or more concatenated literals. */
    private static final Pattern PATTERN_FIELD = Pattern.compile(
            "static\\s+final\\s+Pattern\\s+(\\w+)\\s*=\\s*Pattern\\s*\\.\\s*compile\\s*\\(\\s*"
            + "((?:\"(?:\\\\.|[^\"\\\\])*\"\\s*\\+?\\s*)+)\\)\\s*;");
    private static final Pattern JAVA_LITERAL = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");

    /** Every {@code Pattern} field {@code source} declares — name → the regex its literals spell. */
    private static Map<String, String> patternsDeclaredIn(String source) {
        Map<String, String> patterns = new TreeMap<>();
        Matcher field = PATTERN_FIELD.matcher(SourceScanSupport.stripComments(source));
        while (field.find()) {
            StringBuilder regex = new StringBuilder();
            Matcher literal = JAVA_LITERAL.matcher(field.group(2));
            while (literal.find()) {
                regex.append(unescaped(literal.group(1)));
            }
            patterns.put(field.group(1), regex.toString());
        }
        return patterns;
    }

    /** A Java string literal's content as the string it denotes (the escapes a regex literal uses). */
    private static String unescaped(String content) {
        StringBuilder text = new StringBuilder(content.length());
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\\' && i + 1 < content.length()) {
                char escaped = content.charAt(++i);
                text.append(switch (escaped) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    default -> escaped;
                });
            } else {
                text.append(c);
            }
        }
        return text.toString();
    }
}
