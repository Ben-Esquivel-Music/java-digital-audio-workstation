package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 327's registry-role sentinel (Recording Reliability book §2.9): the
 * {@code IncompleteTakeStore} is the registry of takes sealed by failure,
 * fed only by the seal paths, and holds no audio. A source-level scan in the
 * {@link SourceScanSupport} idiom pins the four facts that make that true:
 * <ol>
 *   <li>the RAM feeder {@code captureRecordingFrames} exists nowhere — not
 *       in a main source, not in a test (a zero-caller method still pending
 *       deletion is not acceptable; the story removes it);</li>
 *   <li>the only main sources that register a take with the store are
 *       {@code RecordCoordinator} (the live rescue) and the store's own
 *       {@code registerRecoveredTakes} (the recovery scan);</li>
 *   <li>no daw-app main source reads {@code java.io.tmpdir}: the one use was
 *       the engine controller's OS-temp rooting of the store, which is gone
 *       (book §9 item 4 — a rescue never lands in the OS temp directory).
 *       Literals are kept for this check, since the property name is one;
 *       the two test-side uses (an export dialog's and a screenshot
 *       harness's output directory) are outside the scope;</li>
 *   <li>{@code DefaultAudioEngineController} does not reference the store at
 *       all: it holds no copy of a take's audio and writes nothing on a
 *       device loss.</li>
 * </ol>
 * Each check is guarded against vacuity: the scanned tree must hold the
 * files it is expected to, and the positive counterpart of each rule is
 * asserted on the real sources.
 */
class RescuedTakeRegistryRoleScanTest {

    private static final String RAM_FEEDER = "captureRecordingFrames";
    private static final String STORE_TYPE = "IncompleteTakeStore";
    private static final String OS_TEMP_PROPERTY = "java.io.tmpdir";
    private static final Pattern STORE_REFERENCE = Pattern.compile("\\b" + STORE_TYPE + "\\b");
    private static final Pattern REGISTER_CALL = Pattern.compile("\\bregister\\s*\\(");

    private static final Path UI = Path.of("com", "benesquivelmusic", "daw", "app", "ui");
    private static final Path RECORD_COORDINATOR = UI.resolve("RecordCoordinator.java");
    private static final Path STORE = UI.resolve(STORE_TYPE + ".java");
    private static final Path ENGINE_CONTROLLER = UI.resolve("DefaultAudioEngineController.java");

    @Test
    void theRamFeederExistsNowhereInMainOrTestSources() throws IOException {
        Path dawApp = SourceScanSupport.locateDawAppModule();
        Path dawCore = dawApp.resolveSibling("daw-core");
        Map<Path, String> everything = new TreeMap<>();
        for (Path root : new Path[]{dawApp.resolve("src/main/java"), dawApp.resolve("src/test/java"),
                dawCore.resolve("src/main/java"), dawCore.resolve("src/test/java")}) {
            assertThat(root).as("source root").isDirectory();
            everything.putAll(javaSources(root));
        }
        assertThat(everything.keySet()).as("non-vacuity: the scan sees the controller and this test")
                .anyMatch(p -> p.endsWith(ENGINE_CONTROLLER))
                .anyMatch(p -> p.endsWith(Path.of("RescuedTakeRegistryRoleScanTest.java")));

        Set<Path> mentioning = everything.entrySet().stream()
                .filter(e -> !e.getKey().endsWith(Path.of("RescuedTakeRegistryRoleScanTest.java")))
                .filter(e -> SourceScanSupport.stripComments(e.getValue()).contains(RAM_FEEDER))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(mentioning)
                .as("the never-called RAM feeder is removed, not left with zero callers (story 327)")
                .isEmpty();
    }

    @Test
    void onlyTheLiveRescueAndTheRecoveryScanRegisterARescuedTake() throws IOException {
        Map<Path, String> main = javaSources(SourceScanSupport.locateDawAppModule().resolve("src/main/java"));
        assertThat(main).as("non-vacuity").containsKeys(RECORD_COORDINATOR, STORE, ENGINE_CONTROLLER);

        Set<Path> registering = main.entrySet().stream()
                .filter(e -> {
                    String code = executableSource(e.getValue());
                    return STORE_REFERENCE.matcher(code).find() && REGISTER_CALL.matcher(code).find();
                })
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(registering)
                .as("main sources that name the store and call register(...) on something")
                .containsExactlyInAnyOrder(STORE, RECORD_COORDINATOR);
        assertThat(executableSource(main.get(STORE)))
                .as("the store's own registration is the recovery scan's")
                .contains("registerRecoveredTakes(");
    }

    @Test
    void noMainSourceReadsTheOsTempDirectoryProperty() throws IOException {
        Map<Path, String> main = javaSources(SourceScanSupport.locateDawAppModule().resolve("src/main/java"));
        assertThat(main).as("non-vacuity").containsKey(ENGINE_CONTROLLER);
        assertThat(SourceScanSupport.stripComments("Paths.get(System.getProperty(\"java.io.tmpdir\"))"))
                .as("fixture: the property name is a literal, which this check keeps")
                .contains(OS_TEMP_PROPERTY);

        Set<Path> reading = main.entrySet().stream()
                .filter(e -> SourceScanSupport.stripComments(e.getValue()).contains(OS_TEMP_PROPERTY))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(reading)
                .as("a rescue never lands in the OS temp directory (book §9 item 4)")
                .isEmpty();
    }

    @Test
    void theEngineControllerDoesNotReferenceTheStore() throws IOException {
        Map<Path, String> main = javaSources(SourceScanSupport.locateDawAppModule().resolve("src/main/java"));
        assertThat(main).as("non-vacuity").containsKeys(ENGINE_CONTROLLER, RECORD_COORDINATOR);

        assertThat(STORE_REFERENCE.matcher(executableSource(main.get(ENGINE_CONTROLLER))).find())
                .as("the engine controller holds no take store: it has no audio to put in one")
                .isFalse();
        assertThat(STORE_REFERENCE.matcher(executableSource(main.get(RECORD_COORDINATOR))).find())
                .as("positive counterpart: the record coordinator owns the registry")
                .isTrue();
    }

    @Test
    void theScannerFlagsAnIdentifierInCodeAndIgnoresItInComments() {
        assertThat(executableSource("void captureRecordingFrames(float[][] in, int n) { }"))
                .contains(RAM_FEEDER);
        assertThat(executableSource("/** {@code captureRecordingFrames} is gone. */ void rescue() { }"))
                .doesNotContain(RAM_FEEDER);
        assertThat(executableSource("// IncompleteTakeStore.register(take)\nvoid x() { }"))
                .doesNotContain(STORE_TYPE);
    }

    private static Map<Path, String> javaSources(Path root) throws IOException {
        Map<Path, String> sources = new TreeMap<>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                sources.put(root.relativize(file), Files.readString(file));
            }
        }
        return sources;
    }

    private static String executableSource(String source) {
        return SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(source));
    }
}
