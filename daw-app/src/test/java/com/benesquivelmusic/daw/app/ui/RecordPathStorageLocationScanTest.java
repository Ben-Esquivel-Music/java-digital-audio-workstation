package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 323 storage-location sentinel (Recording Reliability book §2.6;
 * context D6): recorded audio lives in the project — a take is allocated by
 * {@code TakeDirectories.allocate} under {@code ProjectManager.audioDirectory}
 * — and, as the story's acceptance criterion puts it, no capture-path code
 * references {@code Files.createTempDirectory}. A source-level scan, with
 * comments and string literals stripped, pins exactly that over two scopes:
 * <ul>
 *   <li>every production file of the daw-core {@code core.recording}
 *       package;</li>
 *   <li>the daw-app production files on the record path, found by a
 *       predicate rather than listed: a file is on it when its executable
 *       source names a <em>capture type</em> — a top-level type of
 *       {@code core.recording} other than the metronome, count-in and
 *       input-monitoring settings types in {@link #NOT_CAPTURE_TYPES}. An
 *       import is executable source, so a file that imports a capture type
 *       only for a Javadoc link is on it too — a fail-closed choice. The
 *       record entry point {@code TransportController} is found that way
 *       (asserted); so is any other daw-app file that names
 *       {@code TakeDirectories} to allocate a take or
 *       {@code RecordingPipeline} to drive the pipeline, such as a record
 *       coordinator extracted from it; and a type added to
 *       {@code core.recording} counts as a capture type until it is listed
 *       as a settings type.</li>
 * </ul>
 *
 * <p>A daw-app feature that names no capture type — an archive or an export
 * staged in a temporary directory — is outside this sentinel (story 323,
 * Copilot review round 2). Book §9 item 4 also rejects the OS temp directory
 * for rescue stores and staging; this sentinel does not enforce that. The
 * self-checks prove that the scanner sees the token in code and ignores it
 * in prose, and that the predicate takes a record entry point in and leaves
 * an unrelated feature out.</p>
 */
class RecordPathStorageLocationScanTest {

    private static final String FORBIDDEN_TOKEN = "createTempDirectory";

    private static final Path RECORDING_PACKAGE = Path.of(
            "src", "main", "java", "com", "benesquivelmusic", "daw", "core", "recording");

    private static final Path TRANSPORT_CONTROLLER = Path.of(
            "com", "benesquivelmusic", "daw", "app", "ui", "TransportController.java");

    /**
     * The types of {@code core.recording} that do not make a daw-app file part
     * of the record path: the click and the count-in ({@code Metronome},
     * {@code MetronomeSettingsStore}, {@code MetronomeSideOutputRouter},
     * {@code ClickSound}, {@code CountInMode}, {@code Subdivision}) and what
     * the performer hears while recording ({@code InputMonitoringMode}). In
     * daw-app the metronome, main and transport controllers and the settings
     * UI use them; none of them allocates, writes, drives or finishes a take.
     * Every other type of the package is a capture type.
     */
    private static final Set<String> NOT_CAPTURE_TYPES = Set.of(
            "ClickSound", "CountInMode", "InputMonitoringMode", "Metronome",
            "MetronomeSettingsStore", "MetronomeSideOutputRouter", "Subdivision");

    @Test
    void noProductionRecordPathUsesTheOsTempDirectory() throws IOException {
        Path dawApp = SourceScanSupport.locateDawAppModule();
        Path appRoot = dawApp.resolve("src/main/java");
        Path recordingRoot = recordingPackage();
        assertThat(appRoot).as("daw-app production root").isDirectory();
        assertThat(recordingRoot).as("daw-core recording package (sibling module)").isDirectory();

        Map<Path, String> recording = javaSources(recordingRoot);
        assertThat(recording).as("non-vacuity: Java files scanned under " + recordingRoot).isNotEmpty();
        assertThat(filesUsingTheToken(recording))
                .as("core.recording code allocating in the OS temp directory "
                        + "(recorded audio lives in the project, book §2.6)")
                .isEmpty();

        Set<String> captureTypes = captureTypesAmong(typesDeclaredBy(recording.keySet()));
        Map<Path, String> app = javaSources(appRoot);
        Set<Path> recordPath = recordPathFiles(app, captureTypes);
        assertThat(recordPath)
                .as("non-vacuity: the daw-app record path found by the predicate holds the record entry point")
                .contains(TRANSPORT_CONTROLLER);
        assertThat(filesUsingTheToken(restrictedTo(app, recordPath)))
                .as("daw-app record-path code allocating in the OS temp directory "
                        + "(recorded audio lives in the project, book §2.6); record path: " + recordPath)
                .isEmpty();
    }

    @Test
    void theCaptureTypesAreEveryRecordingTypeButTheSettingsTypes() throws IOException {
        Set<String> recordingTypes = typesDeclaredBy(javaSources(recordingPackage()).keySet());

        assertThat(recordingTypes)
                .as("every type kept off the record path is a type of core.recording")
                .containsAll(NOT_CAPTURE_TYPES);
        assertThat(captureTypesAmong(recordingTypes))
                .as("the types that allocate, capture, write, describe and finish a take are capture types")
                .contains("TakeDirectories", "RecordingPipeline", "CaptureFlushService", "CaptureRing",
                        "RecordingSession", "SegmentWriter", "TakeManifest", "StopSealFailure")
                .doesNotContainAnyElementsOf(NOT_CAPTURE_TYPES);
    }

    @Test
    void aTypeAddedToTheRecordingPackageIsACaptureTypeUntilItIsListedAsASettingsType() {
        assertThat(captureTypesAmong(List.of("CaptureSession", "Metronome")))
                .as("a new type widens the record path by default; only a listed settings type narrows it")
                .containsExactly("CaptureSession");
    }

    @Test
    void anUnrelatedFeatureThatStagesAnArchiveInATemporaryDirectoryIsNotFlagged() throws IOException {
        String archiveStager = """
                package com.benesquivelmusic.daw.app.ui.archive;

                import java.io.IOException;
                import java.nio.file.Files;
                import java.nio.file.Path;

                final class ArchiveStager {
                    Path stage() throws IOException {
                        return Files.createTempDirectory("daw-archive-");
                    }
                }
                """;
        assertThat(executableSource(archiveStager))
                .as("fixture: the token is in the stager's code")
                .contains(FORBIDDEN_TOKEN);

        assertThat(recordPathOffenders(Map.of(Path.of("ArchiveStager.java"), archiveStager)))
                .as("a feature that names no capture type is outside the capture-path invariant")
                .isEmpty();
    }

    @Test
    void aCaptureTypeNamedOnlyInCommentsOrLiteralsDoesNotPutAFileOnTheRecordPath() throws IOException {
        String latencyExport = """
                package com.benesquivelmusic.daw.app.ui;

                /** Reported to {@link com.benesquivelmusic.daw.core.recording.RecordingPipeline#setReportedLatency}. */
                final class LatencyExport {
                    // TakeDirectories.allocate is the record path's business, not this class's
                    Path export() throws IOException {
                        LOG.info("measured for the RecordingPipeline");
                        return Files.createTempDirectory("daw-latency-");
                    }
                }
                """;

        assertThat(recordPathOffenders(Map.of(Path.of("LatencyExport.java"), latencyExport)))
                .as("the predicate reads executable source: a capture type in a comment or a literal is prose"
                        + " (an import is code, see the next test)")
                .isEmpty();
    }

    /**
     * An import is code, not prose: a file that imports a capture type — even
     * one it names only in a Javadoc {@code {@link}} — depends on it and is on
     * the record path. A deliberate fail-closed choice: counting imports can
     * only add a file to the record path, never drop one; such a file keeps a
     * temporary directory by linking the type fully qualified instead of
     * importing it, as the previous test's file does. A file that reaches the
     * record path without naming a capture type at all — a helper that hands
     * back a temporary directory to a caller on the record path, say — is
     * outside this predicate either way; the behavioural tests of where a
     * take lives cover that.
     */
    @Test
    void aCaptureTypeImportedOnlyForAJavadocLinkPutsTheFileOnTheRecordPath() throws IOException {
        String latencyDoc = """
                package com.benesquivelmusic.daw.app.ui;

                import com.benesquivelmusic.daw.core.recording.RecordingPipeline;

                /** Reported to {@link RecordingPipeline#setReportedLatency}. */
                final class LatencyDoc {
                    Path export() throws IOException {
                        return Files.createTempDirectory("daw-latency-");
                    }
                }
                """;
        assertThat(executableSource(latencyDoc))
                .as("fixture: with comments stripped, the capture type is named only by the import")
                .containsOnlyOnce("RecordingPipeline");

        assertThat(recordPathOffenders(Map.of(Path.of("LatencyDoc.java"), latencyDoc)))
                .as("an import names a capture type in code (fail-closed)")
                .containsExactly(Path.of("LatencyDoc.java"));
    }

    @Test
    void aMetronomeOrMonitoringSettingsUserIsNotFlagged() throws IOException {
        String clickRenderer = """
                package com.benesquivelmusic.daw.app.ui;

                import com.benesquivelmusic.daw.core.recording.CountInMode;
                import com.benesquivelmusic.daw.core.recording.InputMonitoringMode;
                import com.benesquivelmusic.daw.core.recording.Metronome;

                final class ClickRenderer {
                    Path render(Metronome metronome, CountInMode countIn, InputMonitoringMode mode)
                            throws IOException {
                        return Files.createTempDirectory("daw-click-");
                    }
                }
                """;

        assertThat(recordPathOffenders(Map.of(Path.of("ClickRenderer.java"), clickRenderer)))
                .as("the recording package's settings types do not make a file part of the record path")
                .isEmpty();
    }

    @Test
    void aRecordEntryPointThatAllocatesInATemporaryDirectoryIsFlagged() throws IOException {
        String recordCoordinator = """
                package com.benesquivelmusic.daw.app.ui.record;

                import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
                import com.benesquivelmusic.daw.core.recording.TakeDirectories;

                final class RecordCoordinator {
                    RecordingPipeline record(Path projectDirectory, Instant now) throws IOException {
                        Path take = TakeDirectories.allocate(ProjectManager.audioDirectory(projectDirectory), now);
                        Path scratch = Files.createTempDirectory("daw-recording-");
                        return new RecordingPipeline(engine, transport, format, scratch);
                    }
                }
                """;

        assertThat(recordPathOffenders(Map.of(Path.of("RecordCoordinator.java"), recordCoordinator)))
                .as("a record entry point is found by the predicate, not by a list of file names")
                .containsExactly(Path.of("RecordCoordinator.java"));
    }

    @Test
    void theScannerFlagsTheTokenInCodeAndIgnoresItInCommentsAndLiterals() {
        String inCode = executableSource("""
                Path dir = Files.createTempDirectory("x");
                """);
        assertThat(inCode)
                .as("the token in CODE survives comment and literal stripping")
                .contains(FORBIDDEN_TOKEN);

        String inComments = executableSource("""
                // Files.createTempDirectory("x")
                /* Files.createTempDirectory("x") */
                /** {@code Files.createTempDirectory} first-save (never again). */
                Path dir = TakeDirectories.allocate(ProjectManager.audioDirectory(projectDir), now);
                """);
        assertThat(inComments)
                .as("the token only in COMMENTS is stripped before matching")
                .doesNotContain(FORBIDDEN_TOKEN)
                .contains("TakeDirectories.allocate(", "ProjectManager.audioDirectory(");

        String inLiteral = executableSource("""
                String note = "never createTempDirectory here";
                """);
        assertThat(inLiteral)
                .as("the token only in a STRING LITERAL is blanked before matching")
                .doesNotContain(FORBIDDEN_TOKEN);
    }

    @Test
    void theRecordPathAllocatesTheTakeUnderTheProjectsAudioTakes() throws IOException {
        Path controller = SourceScanSupport.locateDawAppModule().resolve("src/main/java")
                .resolve(TRANSPORT_CONTROLLER);
        assertThat(controller).isRegularFile();

        String code = executableSource(Files.readString(controller));

        assertThat(code)
                .as("TransportController allocates the take under the project's audio/takes (D6)")
                .contains("TakeDirectories.allocate(", "ProjectManager.audioDirectory(")
                .doesNotContain(FORBIDDEN_TOKEN);
    }

    /**
     * The files of {@code sources} (path → raw source) that are on the record
     * path and name the forbidden token in code, against the capture types of
     * the real {@code core.recording} package.
     */
    private static Set<Path> recordPathOffenders(Map<Path, String> sources) throws IOException {
        Set<String> captureTypes = captureTypesAmong(typesDeclaredBy(javaSources(recordingPackage()).keySet()));
        return filesUsingTheToken(restrictedTo(sources, recordPathFiles(sources, captureTypes)));
    }

    /** The files whose executable source names a capture type. */
    private static Set<Path> recordPathFiles(Map<Path, String> sources, Set<String> captureTypes) {
        // An empty alternation would match at every word boundary.
        assertThat(captureTypes).as("non-vacuity: capture types").isNotEmpty();
        Pattern captureTypeReference = Pattern.compile(captureTypes.stream()
                .map(Pattern::quote)
                .collect(Collectors.joining("|", "\\b(?:", ")\\b")));
        return sources.entrySet().stream()
                .filter(e -> captureTypeReference.matcher(executableSource(e.getValue())).find())
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** The recording package's types less the settings types; a new type is a capture type by default. */
    private static Set<String> captureTypesAmong(Collection<String> recordingTypes) {
        return recordingTypes.stream()
                .filter(type -> !NOT_CAPTURE_TYPES.contains(type))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<Path> filesUsingTheToken(Map<Path, String> sources) {
        return sources.entrySet().stream()
                .filter(e -> executableSource(e.getValue()).contains(FORBIDDEN_TOKEN))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Map<Path, String> restrictedTo(Map<Path, String> sources, Set<Path> files) {
        return sources.entrySet().stream()
                .filter(e -> files.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, TreeMap::new));
    }

    /** Every {@code .java} file under {@code root}, keyed by its path relative to {@code root}. */
    private static Map<Path, String> javaSources(Path root) throws IOException {
        Map<Path, String> sources = new TreeMap<>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                sources.put(root.relativize(file), Files.readString(file));
            }
        }
        return sources;
    }

    private static Path recordingPackage() {
        return SourceScanSupport.locateDawAppModule().resolveSibling("daw-core").resolve(RECORDING_PACKAGE);
    }

    /**
     * The top-level types the source files declare: each file name without
     * {@code .java} — {@code package-info} and {@code module-info} declare
     * none.
     */
    private static Set<String> typesDeclaredBy(Collection<Path> files) {
        return files.stream()
                .map(file -> file.getFileName().toString())
                .map(name -> name.substring(0, name.length() - ".java".length()))
                .filter(name -> !name.equals("package-info") && !name.equals("module-info"))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String executableSource(String source) {
        return SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(source));
    }
}
