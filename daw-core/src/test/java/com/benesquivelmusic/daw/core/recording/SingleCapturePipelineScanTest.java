package com.benesquivelmusic.daw.core.recording;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 328 — single-pipeline conformance (Recording Reliability book §2.10,
 * §9.12): the capture workflows — count-in, punch, loop-record — are gates on
 * the one capture pipeline, never pipelines of their own. A source scan
 * pins it: the capture buffer ({@code CaptureRing}), the session
 * ({@code RecordingSession}) and the segment writer ({@code SegmentWriter})
 * are constructed only by the pipeline set named in
 * {@link #CAPTURE_CONSTRUCTORS}, and the workflow surfaces
 * ({@link #WORKFLOW_GATE_SOURCES}) do not so much as name those types.
 *
 * <p>Both lists are exact and every file they name must exist and be
 * non-empty, so the scan cannot pass vacuously: a capture type constructed
 * from a new place fails, and so does a renamed or removed file.</p>
 */
class SingleCapturePipelineScanTest {

    private static final Path CORE_MAIN = Path.of("src", "main", "java", "com", "benesquivelmusic", "daw", "core");
    private static final Path RECORDING = CORE_MAIN.resolve("recording");
    private static final Path APP_UI = Path.of("..", "daw-app", "src", "main", "java",
            "com", "benesquivelmusic", "daw", "app", "ui");

    /** A construction of one of the three capture types: {@code new X(} or a static factory on {@code X}. */
    private static final Pattern CONSTRUCTION = Pattern.compile(
            "\\bnew\\s+(SegmentWriter|CaptureRing|RecordingSession)\\s*\\("
                    + "|\\b(SegmentWriter|CaptureRing|RecordingSession)\\.(open|create|start|of)\\s*\\(");

    /** Any mention of the three capture types by name. */
    private static final Pattern MENTION = Pattern.compile("\\b(SegmentWriter|CaptureRing|RecordingSession)\\b");

    /** The only files of {@code core.recording} allowed to construct a capture type (exact). */
    private static final Set<String> CAPTURE_CONSTRUCTORS = Set.of(
            "CaptureRing.java", "RecordingPipeline.java", "RecordingSession.java", "SegmentWriter.java");

    /** The workflow gates: count-in, punch and loop-record — none of them names a capture type. */
    private static final List<Path> WORKFLOW_GATE_SOURCES = List.of(
            CORE_MAIN.resolve("audio").resolve("CountInSchedule.java"),
            CORE_MAIN.resolve("transport").resolve("Transport.java"),
            APP_UI.resolve("vm").resolve("command").resolve("CoreTransportIntentHandler.java"),
            APP_UI.resolve("vm").resolve("command").resolve("TogglePunchCommand.java"),
            APP_UI.resolve("vm").resolve("command").resolve("SetPunchInCommand.java"),
            APP_UI.resolve("vm").resolve("command").resolve("SetPunchOutCommand.java"),
            APP_UI.resolve("vm").resolve("command").resolve("ToggleLoopRecordCommand.java"),
            APP_UI.resolve("TransportController.java"),
            APP_UI.resolve("RecordCoordinator.java"),
            APP_UI.resolve("TimelineRuler.java"));

    @Test
    void onlyThePipelineSetConstructsACaptureBufferSessionOrWriter() throws IOException {
        List<Path> sources = recordingSources();
        assertThat(sources).as("non-vacuity: the recording package was scanned").hasSizeGreaterThan(10);

        Set<String> constructing = new TreeSet<>();
        List<String> offenders = new ArrayList<>();
        for (Path source : sources) {
            String name = source.getFileName().toString();
            if (CONSTRUCTION.matcher(stripComments(Files.readString(source, StandardCharsets.UTF_8))).find()) {
                constructing.add(name);
                if (!CAPTURE_CONSTRUCTORS.contains(name)) {
                    offenders.add(name);
                }
            }
        }
        assertThat(offenders)
                .as("a capture buffer, session or writer is constructed outside the pipeline set (book §9.12)")
                .isEmpty();
        assertThat(constructing)
                .as("the allow-list is exact: every entry constructs a capture type, and nothing else does")
                .containsExactlyInAnyOrderElementsOf(CAPTURE_CONSTRUCTORS);
    }

    @Test
    void theWorkflowGatesDoNotNameACaptureBufferSessionOrWriter() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : WORKFLOW_GATE_SOURCES) {
            assertThat(source).as("non-vacuity: the gate source exists at " + source.toAbsolutePath()).isRegularFile();
            String text = stripComments(Files.readString(source, StandardCharsets.UTF_8));
            assertThat(text).as("non-vacuity: " + source.getFileName() + " has code").isNotBlank();
            if (MENTION.matcher(text).find()) {
                offenders.add(source.getFileName().toString());
            }
        }
        assertThat(offenders)
                .as("a workflow gate reaches for a capture type of its own instead of gating the one pipeline")
                .isEmpty();
    }

    private static List<Path> recordingSources() throws IOException {
        assertThat(RECORDING).as("run from the daw-core module directory").isDirectory();
        try (Stream<Path> files = Files.list(RECORDING)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".java")).sorted().toList();
        }
    }

    /** Drops block and line comments so a Javadoc that names a type is not a finding. */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\r\\n]*", "");
    }
}
