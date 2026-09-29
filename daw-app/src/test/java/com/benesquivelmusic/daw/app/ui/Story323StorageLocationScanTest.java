package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 323 storage-location sentinel (Recording Reliability book §2.6, §9.4;
 * context D6): recorded audio lives in the project — a take is allocated by
 * {@code TakeDirectories.allocate} under {@code ProjectManager.audioDirectory}
 * — and never in the OS temp directory. A source-level scan over
 * {@code daw-app/src/main/java} and the daw-core {@code core.recording}
 * package pins that no production record path can regress to
 * {@code Files.createTempDirectory}; the self-check proves the scanner would
 * see the token if it came back in code, and ignores it in prose.
 */
class Story323StorageLocationScanTest {

    private static final String FORBIDDEN_TOKEN = "createTempDirectory";

    private static final Path RECORDING_PACKAGE = Path.of(
            "src", "main", "java", "com", "benesquivelmusic", "daw", "core", "recording");

    private static final Path TRANSPORT_CONTROLLER = Path.of(
            "src", "main", "java", "com", "benesquivelmusic", "daw", "app", "ui",
            "TransportController.java");

    @Test
    void noProductionRecordPathUsesTheOsTempDirectory() throws IOException {
        Path dawApp = SourceScanSupport.locateDawAppModule();
        Path dawCore = dawApp.resolveSibling("daw-core");
        Path appRoot = dawApp.resolve("src/main/java");
        Path recordingRoot = dawCore.resolve(RECORDING_PACKAGE);
        assertThat(appRoot).as("daw-app production root").isDirectory();
        assertThat(recordingRoot).as("daw-core recording package (sibling module)").isDirectory();

        for (Path root : List.of(appRoot, recordingRoot)) {
            int scanned = 0;
            List<String> offenders = new ArrayList<>();
            try (var files = Files.walk(root)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    scanned++;
                    if (executableSource(Files.readString(file)).contains(FORBIDDEN_TOKEN)) {
                        offenders.add(root.relativize(file).toString());
                    }
                }
            }
            assertThat(scanned).as("non-vacuity: Java files scanned under " + root).isPositive();
            assertThat(offenders)
                    .as("production code under " + root + " allocating in the OS temp "
                            + "directory (recorded audio lives in the project, book §9.4)")
                    .isEmpty();
        }
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
        Path controller = SourceScanSupport.locateDawAppModule().resolve(TRANSPORT_CONTROLLER);
        assertThat(controller).isRegularFile();

        String code = executableSource(Files.readString(controller));

        assertThat(code)
                .as("TransportController allocates the take under the project's audio/takes (D6)")
                .contains("TakeDirectories.allocate(", "ProjectManager.audioDirectory(")
                .doesNotContain(FORBIDDEN_TOKEN);
    }

    private static String executableSource(String source) {
        return SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(source));
    }
}
