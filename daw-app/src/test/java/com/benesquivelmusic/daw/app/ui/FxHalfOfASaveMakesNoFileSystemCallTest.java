package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 331, Goals — Tests, "Single-save test": "the FX thread performs no
 * disk I/O (blocked only for the capture)". Rulings F2 and F3 give the save
 * that shape: its caller only captures, and one save writer renders, checks
 * the lock, backs up and writes the head, one save at a time in capture
 * order, then the checkpoint from the bytes the head write wrote.
 * {@code SingleSaveRequestTest} and daw-core's {@code SingleCanonicalSaveTest}
 * prove it for the disk work a save does today, through the
 * {@code AtomicFileWriter}'s mover seam and the lock-reader seam; neither
 * would see a disk call added later to the code that runs on the FX thread.
 * This source pin would, if the call has one of the shapes listed below.
 *
 * <p>It reads both modules' main sources, comments stripped and literals
 * blanked ({@link SourceScanSupport}), and scans the bodies of the methods
 * a save runs on the FX thread, on its caller and when its outcome comes
 * back:</p>
 * <ul>
 *   <li>daw-core's {@code ProjectManager.save}: the stamp, the capture and
 *       the hand-off to the save writer;</li>
 *   <li>{@code ProjectLifecycleController.submitSave}, the outcome's
 *       {@code whenComplete} callback included: it runs on the FX thread
 *       whenever the outcome is already complete when the callback is
 *       registered, which happens for a read-only project, a capture that
 *       threw, a save writer that could not start, or a save the writer
 *       finished first;</li>
 *   <li>{@code ProjectLifecycleController.saveOpenProject}, the request it
 *       coalesces into a first save whose folder is being created
 *       ({@code coalescedIntoTheFirstSave}), and the two halves
 *       of its branch for a project never saved (story 333):
 *       {@code saveIntoANewFolder}, the location prompt and the hand-off of
 *       the folder's creation to a worker, and {@code saveIntoTheCreatedFolder},
 *       the create's outcome back on the FX thread and the save into the
 *       folder. The folder is created off the FX thread
 *       ({@code createProjectOffTheFxThread}, not scanned: its body is the
 *       worker's);</li>
 *   <li>the outcome's FX-thread half, which that callback posts through the
 *       {@code FxDispatcher}: {@code ProjectLifecycleController.saveEnded},
 *       and the {@code reportSaveFailed}, {@code showSaveFailure} and
 *       {@code saveFailureMessage} it runs, and the takeover dialog a lost
 *       lock asks in a later FX turn, {@code offerSaveAsAfterTakeover}
 *       (story 333).</li>
 * </ul>
 *
 * <p>None of those bodies may make one of these direct file-system calls:
 * {@code Files.} or {@code FileSystems.} (called or as a method reference),
 * {@code FileChannel} or {@code AsynchronousFileChannel}, {@code toRealPath},
 * {@code newDirectoryStream}, {@code RandomAccessFile}, a java.io file
 * stream ({@code FileInputStream}, {@code FileOutputStream},
 * {@code FileReader}, {@code FileWriter}), a {@code new PrintWriter},
 * {@code new Scanner} or {@code new Formatter}, a {@code ZipFile} or
 * {@code JarFile}, a {@code java.io.File} ({@code new File},
 * {@code File::new}, {@code toFile()}), a {@code java.io.File} disk call
 * ({@code exists()}, {@code isFile()}, {@code isDirectory()},
 * {@code length()}, {@code delete()}, {@code mkdirs()} and
 * {@code createNewFile()} with no argument, {@code listFiles(} with any, or
 * one of them as a {@code File::} method reference), a file-system provider
 * ({@code provider()}), or the {@code AtomicFileWriter}'s
 * {@code followLinkToDawFile}, {@code writeString}, {@code write} and
 * {@code copy}. A scan cannot tell a receiver's type, so some shapes count
 * whatever they are called on (it fails closed), for example a
 * {@code PrintWriter}, {@code Scanner} or {@code Formatter} over memory or a
 * stream, a {@code String}'s {@code length()} or another no-argument query
 * of a listed name on a receiver that is not a {@code File}, and a
 * {@code writeString}, {@code write} or {@code copy} on a receiver other
 * than the writer. Neither file may import a static member of
 * {@code Files}, {@code FileSystems}, a file channel or the writer, which
 * would hide a call's receiver.</p>
 *
 * <p>That list is the test's whole guarantee. The other methods the scanned
 * bodies call are not scanned, and a way to reach the disk that is not
 * listed here is not detected.</p>
 *
 * <p>It fails closed: a scanned method that its file no longer declares, or
 * declares more than once, fails it, so a rename cannot make it pass on
 * nothing. A source scan, not a reflection consumer: the
 * {@code dawg-annotations-reflection} rules for adding one (§4, "Adding a
 * new reflection consumer") do not apply, and it reads no annotation.</p>
 */
class FxHalfOfASaveMakesNoFileSystemCallTest {

    private static final Path DAW_APP = SourceScanSupport.locateDawAppModule();
    private static final Path PROJECT_MANAGER = DAW_APP.resolveSibling("daw-core")
            .resolve("src/main/java/com/benesquivelmusic/daw/core/persistence/ProjectManager.java");
    static final Path LIFECYCLE = DAW_APP
            .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/ProjectLifecycleController.java");

    /** A direct file-system call, by the label a site is reported with. */
    private record FileSystemCall(String label, Pattern shape) {

        FileSystemCall(String label, String regex) {
            this(label, Pattern.compile(regex));
        }
    }

    private static final List<FileSystemCall> FILE_SYSTEM_CALLS = List.of(
            new FileSystemCall("Files", "\\bFiles\\s*(?:\\.|::)"),
            new FileSystemCall("FileSystems", "\\bFileSystems\\s*(?:\\.|::)"),
            new FileSystemCall("FileChannel", "\\b(?:Asynchronous)?FileChannel\\b"),
            new FileSystemCall("toRealPath", "\\btoRealPath\\b"),
            new FileSystemCall("newDirectoryStream", "\\bnewDirectoryStream\\b"),
            new FileSystemCall("RandomAccessFile", "\\bRandomAccessFile\\b"),
            new FileSystemCall("java.io file stream", "\\bFile(?:(?:Input|Output)Stream|Reader|Writer)\\b"),
            new FileSystemCall("PrintWriter/Scanner/Formatter",
                    "\\bnew\\s+(?:java\\s*\\.\\s*(?:io|util)\\s*\\.\\s*)?(?:PrintWriter|Scanner|Formatter)\\s*\\("),
            new FileSystemCall("ZipFile/JarFile", "\\b(?:Zip|Jar)File\\b"),
            new FileSystemCall("java.io.File", "\\bnew\\s+(?:java\\s*\\.\\s*io\\s*\\.\\s*)?File\\s*\\("
                    + "|\\bFile\\s*::\\s*new\\b|(?:\\.|::)\\s*toFile\\b"),
            new FileSystemCall("java.io.File query", "\\.\\s*(?:exists|isFile|isDirectory|length|delete|mkdirs"
                    + "|createNewFile)\\s*\\(\\s*\\)|\\.\\s*listFiles\\s*\\(|\\bFile\\s*::\\s*(?:exists|isFile"
                    + "|isDirectory|length|delete|mkdirs|createNewFile|listFiles)\\b"),
            new FileSystemCall("FileSystemProvider", "\\bprovider\\s*\\(\\s*\\)"),
            new FileSystemCall("followLinkToDawFile", "\\bfollowLinkToDawFile\\b"),
            new FileSystemCall("writeString/write/copy", "(?:\\.|::)\\s*(?:writeString|write|copy)\\b"));

    /**
     * A static import from {@code Files}, {@code FileSystems}, a file channel or the writer: it would hide a
     * call's receiver.
     */
    private static final Pattern STATIC_IMPORT_HIDING_A_RECEIVER = Pattern.compile(
            "\\bimport\\s+static\\s+(?:java\\s*\\.\\s*nio\\s*\\.\\s*(?:file\\s*\\.\\s*(?:Files|FileSystems)"
            + "|channels\\s*\\.\\s*(?:Asynchronous)?FileChannel)"
            + "|com\\s*\\.\\s*benesquivelmusic\\s*\\.\\s*daw\\s*\\.\\s*core\\s*\\.\\s*persistence\\s*\\.\\s*"
            + "AtomicFileWriter)\\b");

    /** A scanned method: its file, its name, and its body as the scan reads it. */
    record ScannedBody(String file, String method, String code) {
    }

    /** A direct file-system call the scan found: its label and its line, trimmed and quoted. */
    record Site(String label, String line) {
    }

    @Test
    void theCodeASaveRunsOnTheFxThreadMakesNoFileSystemCall() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (ScannedBody body : fxHalfOfASave()) {
            for (Site site : fileSystemCalls(body.code())) {
                offenders.add(body.file() + "#" + body.method() + ": " + site.label() + " in " + site.line());
            }
        }
        offenders.addAll(staticImportsHidingAReceiver("ProjectManager.java", code(read(PROJECT_MANAGER))));
        offenders.addAll(staticImportsHidingAReceiver("ProjectLifecycleController.java", code(read(LIFECYCLE))));

        assertThat(offenders)
                .as("the FX thread performs no disk I/O on a save (Goals — Tests, Single-save): do the disk work"
                        + " on the save writer")
                .isEmpty();
    }

    @Test
    void theScanReadsTheCaptureTheHandOffAndTheOutcomeOfASave() throws IOException {
        Map<String, String> scanned = new TreeMap<>();
        for (ScannedBody body : fxHalfOfASave()) {
            scanned.put(body.file() + "#" + body.method(), body.code().replaceAll("\\s+", " "));
        }

        assertThat(scanned.get("ProjectManager.java#save"))
                .as("the capture (with the save target's former roots, story 333), and the hand-off to the save"
                        + " writer")
                .contains("serializer.capture(project, target.formerRoots())")
                .contains("submit(job);");
        assertThat(scanned.get("ProjectLifecycleController.java#submitSave"))
                .as("the call of the canonical save, and its outcome's callback, which posts the outcome to the FX"
                        + " thread")
                .contains("outcome = projectManager.save(project);")
                .contains("outcome.whenComplete(")
                .contains("postFx(() -> saveEnded(generation, readOnly, folder, result, then));");
        assertThat(scanned.get("ProjectLifecycleController.java#saveOpenProject"))
                .as("a request during a first save's create is that save's, the never-saved branch, and the save"
                        + " request (story 333)")
                .contains("if (creatingFolder != null) return Optional.of(coalescedIntoTheFirstSave(then));")
                .contains("return saveIntoANewFolder(then, beforeCapture);")
                .contains("beforeCapture.run();")
                .contains("return submitSave(deps.project().get(), deps.captureLayoutJson().get(), then);");
        assertThat(scanned.get("ProjectLifecycleController.java#saveIntoANewFolder"))
                .as("the location prompt as the one switch pending, and the folder created off the FX thread")
                .contains("whileItsDialogIsOpen(SAVE_PROJECT_DOOR,")
                .contains("setCreatingFolder(new FolderBeingCreated(SAVE_PROJECT_DOOR, composed, new ArrayList<>()));")
                .contains("createProjectOffTheFxThread(deps.project().get().getName(), parentDirectory)");
        assertThat(scanned.get("ProjectLifecycleController.java#saveIntoTheCreatedFolder"))
                .as("the create's outcome, and the save into the created folder, captured now")
                .contains("setCreatingFolder(null);")
                .contains("submitSave(deps.project().get(), deps.captureLayoutJson().get(), completing(composed, then));");
        assertThat(scanned.get("ProjectLifecycleController.java#saveEnded"))
                .as("the outcome on the FX thread: a success, and a failure of the open project")
                .contains("progress.recordSaveSucceeded(generation, headSavedAt,")
                .contains("case SaveOutcome.Failed failed -> reportSaveFailed(generation, failed, readOnly, then);");
        assertThat(scanned.get("ProjectLifecycleController.java#reportSaveFailed"))
                .as("the head's failure state, a lost lock caught as itself (its follow-up may own it, or a close"
                        + " waiting for the save — ruling 2 OQ-4 — else the takeover dialog asks in a later FX turn,"
                        + " story 333), and its ERROR")
                .contains("progress.recordSaveFailed(generation, Instant.now(), cause);")
                .contains("if (cause instanceof LockStolenException stolen) {")
                .contains("if (!then.ownsLockLoss() && !takenOverByTheClose(stolen)) {")
                .contains("postFx(() -> offerSaveAsAfterTakeover(generation, stolen));")
                .contains("showSaveFailure(saveFailureMessage(failed, readOnly, null, progress.getToday()), retry,");
        assertThat(scanned.get("ProjectLifecycleController.java#showSaveFailure"))
                .as("the ERROR with its one action: a Retry, or Save As (story 333, ruling Q1/Q5)")
                .contains("notificationBar.show(NotificationLevel.ERROR, message, \"\", () -> {")
                .contains("notificationBar.show(NotificationLevel.ERROR, message, ProjectCloseText.text(\"\"), () -> {")
                .contains("onSaveProjectAs();")
                .doesNotContain("notificationBar.show(NotificationLevel.ERROR, message);");
        assertThat(scanned.get("ProjectLifecycleController.java#offerSaveAsAfterTakeover"))
                .as("the takeover dialog of the open project's save, whose Save As keeps the work (ruling R7)")
                .contains("if (generation != progress.projectGeneration()) {")
                .contains("prompts.takenOverOnSave(deps.project().get().getName(), stolen.currentHolder())")
                .contains("saveAsFlow.start(SaveAsFlow.Prefill.TAKEOVER);");
        assertThat(scanned.get("ProjectLifecycleController.java#saveFailureMessage"))
                .as("the message, naming where the work is")
                .contains("Optional<Path> safety = failed.safetyCheckpoint();")
                .contains("failed.safetyCopyStillWriting()");
    }

    @Test
    void everyFileSystemCallShapeIsFoundAndNothingInACommentOrALiteral() {
        String code = code("""
                import static java.nio.file.Files.exists;
                import static com.benesquivelmusic.daw.core.persistence.AtomicFileWriter.followLinkToDawFile;
                import static java.nio.file.FileSystems.newFileSystem;
                final class Saver {
                    Optional<CompletableFuture<SaveOutcome>> submitSave(Path target, String name, File existing) {
                        // Files.exists(target) and existing.exists() in a comment are no call.
                        /* nor are target.toRealPath() and new FileWriter(name) in a block comment */
                        String literal = "Files.readString(target), new FileInputStream(name) and existing.delete()";
                        Files.exists(target);
                        java.nio.file.Files
                            .isSymbolicLink(target);
                        Predicate<Path> exists = Files::exists;
                        FileSystems.newFileSystem(target).close();
                        FileChannel.open(target).close();
                        AsynchronousFileChannel.open(target).close();
                        target.toRealPath();
                        secure.newDirectoryStream(target);
                        new RandomAccessFile(name, "r").close();
                        new java.io.FileInputStream(name).close();
                        OutputStream out = new FileOutputStream(name);
                        new FileReader(name).close();
                        Writer writer = new java.io.FileWriter(name);
                        new PrintWriter(name).close();
                        new java.util.Scanner(target).close();
                        new Formatter(name).close();
                        new ZipFile(name).close();
                        new java.util.jar.JarFile(name).close();
                        new File(name).exists();
                        target.toFile();
                        Function<String, File> file = File::new;
                        existing.isFile();
                        existing.isDirectory();
                        existing.length();
                        existing.listFiles(filter);
                        existing.delete();
                        existing.mkdirs();
                        existing.createNewFile();
                        Predicate<File> present = File::exists;
                        target.getFileSystem().provider().checkAccess(target);
                        AtomicFileWriter.followLinkToDawFile(target);
                        fileWriter.writeString(target, name);
                        fileWriter.write(target, body);
                        fileWriter.copy(target, target, AtomicFileWriter.Overwrite.REPLACE);
                        Mover copy = fileWriter::copy;
                        List<String> names = List.copyOf(ProjectFiles.names(target));
                        builder.delete(0, 1);
                        return Optional.of(projectManager.save(project));
                    }
                }
                """);

        Map<String, Integer> counts = new TreeMap<>();
        for (Site site : fileSystemCalls(body("Saver.java", code, "submitSave").code())) {
            counts.merge(site.label(), 1, Integer::sum);
        }

        assertThat(counts).isEqualTo(Map.ofEntries(
                Map.entry("Files", 3),
                Map.entry("FileSystems", 1),
                Map.entry("FileChannel", 2),
                Map.entry("toRealPath", 1),
                Map.entry("newDirectoryStream", 1),
                Map.entry("RandomAccessFile", 1),
                Map.entry("java.io file stream", 4),
                Map.entry("PrintWriter/Scanner/Formatter", 3),
                Map.entry("ZipFile/JarFile", 2),
                Map.entry("java.io.File", 3),
                Map.entry("java.io.File query", 9),
                Map.entry("FileSystemProvider", 1),
                Map.entry("followLinkToDawFile", 1),
                Map.entry("writeString/write/copy", 4)));
        assertThat(staticImportsHidingAReceiver("Saver.java", code)).as("all three static imports").hasSize(3);
    }

    @Test
    void aMethodTheSourceNoLongerDeclaresOrDeclaresTwiceFailsTheScan() {
        String renamed = code("""
                final class Saver {
                    void requestSave(Path target) {
                        Files.exists(target);
                    }
                }
                """);
        String overloaded = code("""
                final class Saver {
                    void submitSave(Path target) {
                        submitSave(target, false);
                    }

                    private void submitSave(Path target, boolean quietly) {
                        Files.exists(target);
                    }
                }
                """);

        assertThatThrownBy(() -> body("Saver.java", renamed, "submitSave"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Saver.java no longer declares submitSave");
        assertThatThrownBy(() -> body("Saver.java", overloaded, "submitSave"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Saver.java declares submitSave 2 times");
    }

    /**
     * The bodies of the methods a save runs on the FX thread, on its caller and when its outcome comes back,
     * read from the main sources.
     */
    private static List<ScannedBody> fxHalfOfASave() throws IOException {
        String manager = code(read(PROJECT_MANAGER));
        String lifecycle = code(read(LIFECYCLE));
        return List.of(
                body("ProjectManager.java", manager, "save"),
                body("ProjectLifecycleController.java", lifecycle, "submitSave"),
                body("ProjectLifecycleController.java", lifecycle, "saveOpenProject"),
                body("ProjectLifecycleController.java", lifecycle, "coalescedIntoTheFirstSave"),
                body("ProjectLifecycleController.java", lifecycle, "saveIntoANewFolder"),
                body("ProjectLifecycleController.java", lifecycle, "saveIntoTheCreatedFolder"),
                body("ProjectLifecycleController.java", lifecycle, "saveEnded"),
                body("ProjectLifecycleController.java", lifecycle, "reportSaveFailed"),
                body("ProjectLifecycleController.java", lifecycle, "offerSaveAsAfterTakeover"),
                body("ProjectLifecycleController.java", lifecycle, "showSaveFailure"),
                body("ProjectLifecycleController.java", lifecycle, "saveFailureMessage"));
    }

    static String read(Path file) throws IOException {
        assertThat(file).as("the source the scan reads").isRegularFile();
        String source = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(source.length()).as("non-vacuity: %s was read", file.getFileName()).isGreaterThan(10_000);
        return source;
    }

    /** {@code source}'s code: comments stripped, then literals blanked, so only code is matched. */
    static String code(String source) {
        return SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(source));
    }

    /**
     * The body, brace to matching brace, of the one method named
     * {@code method} that {@code code} declares. Fails when the code declares
     * no such method (a rename) or more than one, whose other bodies the scan
     * would not read.
     */
    static ScannedBody body(String file, String code, String method) {
        Matcher declaration = Pattern.compile("^[ \\t]*[\\w.<>?,\\[\\] ]*[\\w>\\]]\\s+" + Pattern.quote(method)
                + "\\s*\\([^;{}]*\\)\\s*(?:throws\\s[^;{}]*)?\\{", Pattern.MULTILINE).matcher(code);
        List<Integer> openingBraces = new ArrayList<>();
        while (declaration.find()) {
            openingBraces.add(declaration.end() - 1);
        }
        if (openingBraces.isEmpty()) {
            throw new AssertionError(file + " no longer declares " + method + ": the scan would read nothing;"
                    + " point it at the method that holds this code now");
        }
        if (openingBraces.size() > 1) {
            throw new AssertionError(file + " declares " + method + " " + openingBraces.size() + " times: the"
                    + " scan reads one body; scan each one a save runs on the FX thread");
        }
        int open = openingBraces.getFirst();
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return new ScannedBody(file, method, code.substring(open, i + 1));
            }
        }
        throw new AssertionError("unbalanced braces after " + file + "#" + method);
    }

    /** The direct file-system calls {@code code} makes, in the order of {@link #FILE_SYSTEM_CALLS}. */
    static List<Site> fileSystemCalls(String code) {
        List<Site> sites = new ArrayList<>();
        for (FileSystemCall call : FILE_SYSTEM_CALLS) {
            Matcher match = call.shape().matcher(code);
            while (match.find()) {
                sites.add(new Site(call.label(), lineAt(code, match.start())));
            }
        }
        return sites;
    }

    private static List<String> staticImportsHidingAReceiver(String file, String code) {
        List<String> imports = new ArrayList<>();
        Matcher match = STATIC_IMPORT_HIDING_A_RECEIVER.matcher(code);
        while (match.find()) {
            imports.add(file + ": a static import hides a call's receiver: " + lineAt(code, match.start()));
        }
        return imports;
    }

    /**
     * The line of {@code code} at {@code offset}, trimmed and quoted — how a
     * site is named in a failure: comment stripping folds comments, so a line
     * number of the scanned code is not the source's.
     */
    private static String lineAt(String code, int offset) {
        int start = code.lastIndexOf('\n', offset) + 1;
        int end = code.indexOf('\n', offset);
        return "'" + code.substring(start, end < 0 ? code.length() : end).trim() + "'";
    }
}
