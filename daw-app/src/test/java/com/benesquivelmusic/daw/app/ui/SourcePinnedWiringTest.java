package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 331 wiring no behaviour test reaches, pinned by a source scan in the
 * style of {@code CheckpointCaptureFailureReachesTheStripTest} (the
 * {@link SourceScanSupport} harness; a scan, not a reflection consumer):
 *
 * <ul>
 *   <li>{@code MainController}, which no test FXML-loads: the one JVM
 *       shutdown hook that waits for the writes at exit (ruling M1) —
 *       registered once, right after the project manager is built, as a
 *       platform thread that only calls {@code awaitWritesBeforeExit} — and
 *       no other in the application, the calls counted on the code without
 *       its comments and string literals; the strip's 5&nbsp;s poll that
 *       publishes today's date (ruling L1), mounted at startup and played;
 *       and no countdown guessed when a project's status is published
 *       (ruling M4), which the model's own countdown writers back up — a
 *       re-arm, and the end of the checkpoint generation (ruling N2).</li>
 *   <li>{@code ProjectLifecycleController}: the archive's load and the load
 *       after a journal was discarded, which no UI path can refuse since
 *       rulings L3 (a) and L4, use the late switch's never-rate-limited
 *       WARNING (ruling L3 (b)).</li>
 * </ul>
 *
 * <p>Comments are stripped before matching; whitespace is collapsed.</p>
 */
class SourcePinnedWiringTest {

    private static final Path APP_MAIN = SourceScanSupport.locateDawAppModule().resolve("src/main/java");
    private static final Path UI = APP_MAIN.resolve("com/benesquivelmusic/daw/app/ui");

    @Test
    void theOneShutdownHookIsRegisteredRightAfterTheProjectManagerIsBuiltAndOnlyWaitsForTheWrites()
            throws IOException {
        String code = code(UI.resolve("MainController.java"));

        assertThat(methodBody(code, "private void initialize()"))
                .as("registered right after the project manager is built")
                .contains("projectManager = new ProjectManager(checkpointManager, recentProjectsStore);"
                        + " registerExitWriteWait(projectManager);");
        assertThat(code).as("once").containsOnlyOnce("registerExitWriteWait(projectManager);");
        assertThat(methodBody(code, "static void registerExitWriteWait(ProjectManager manager)"))
                .as("a platform thread that only waits for the writes, refused at FINE while shutting down")
                .contains("Thread hook = Thread.ofPlatform().name(\"daw-exit-write-wait\")"
                        + ".unstarted(manager::awaitWritesBeforeExit);")
                .contains("Runtime.getRuntime().addShutdownHook(hook);")
                .contains("catch (IllegalStateException shuttingDown) { LOG.log(Level.FINE,");
        String calls = callsIn(UI.resolve("MainController.java"));
        assertThat(occurrences(calls, "addShutdownHook(")).as("one shutdown hook in MainController").isEqualTo(1);
        assertThat(occurrences(calls, "registerExitWriteWait("))
                .as("registerExitWriteWait declared and called once").isEqualTo(2);
        assertThat(sitesIn(APP_MAIN, "addShutdownHook(")).as("the application's one shutdown hook")
                .containsExactly("com/benesquivelmusic/daw/app/ui/MainController.java");
    }

    @Test
    void theStripsFiveSecondPollPublishesTodaysDate() throws IOException {
        String code = code(UI.resolve("MainController.java"));

        assertThat(methodBody(code, "private void refreshStripDynamicState()"))
                .contains("projectOperationProgress.setToday(java.time.LocalDate.now(java.time.ZoneId.systemDefault()));");
        assertThat(methodBody(code, "private void mountLockStatusIndicator()"))
                .as("the 5 s KeyFrame calls it, and the poll runs")
                .contains("new KeyFrame(Duration.seconds(5), _ -> { repaintLockIndicator();"
                        + " refreshStripDynamicState(); })")
                .contains("lockIndicatorTimeline.play();");
        assertThat(methodBody(code, "private void initialize()")).as("the poll is mounted at startup")
                .contains("mountLockStatusIndicator();");
    }

    @Test
    void noCountdownIsGuessedOnlyARearmWritesItAndOnlyTheEndOfTheCheckpointsClearsIt()
            throws IOException {
        String mainController = code(UI.resolve("MainController.java"));
        assertThat(methodBody(mainController, "private void publishProjectStatusToModel()"))
                .doesNotContain("Checkpoint").doesNotContain("autoSaveInterval");
        assertThat(mainController).as("the application never writes the countdown itself")
                .doesNotContain("rearmCheckpointCountdown(").doesNotContain("setNextCheckpoint(");

        String model = code(UI.resolve("status/ProjectOperationProgress.java"));
        assertThat(occurrences(model, "nextCheckpointInstant.set(")).as("two writers of the countdown").isEqualTo(2);
        assertThat(methodBody(model, "public void rearmCheckpointCountdown("))
                .contains("nextCheckpointInstant.set(stillRunning.getAsBoolean() ? nextDue : null);");
        assertThat(methodBody(model, "public void checkpointsEnded()"))
                .contains("nextCheckpointInstant.set(null);");
        assertThat(methodBody(model, "public void resetForProject(Instant lastSaved)"))
                .contains("checkpointsEnded();");
        assertThat(methodBody(model, "public void resetForSnapshotRestore(Instant lastSaved)"))
                .doesNotContain("nextCheckpointInstant").doesNotContain("checkpointInterval");
    }

    @Test
    void theArchivesLoadAndTheLoadAfterAJournalWasDiscardedUseTheNeverRateLimitedWarning() throws IOException {
        String code = code(UI.resolve("ProjectLifecycleController.java"));

        // Story 333 (the studio engineer's stage-4 S3): the archive's late load carries what a stopped wait of it
        // reports, and the warning names a first save for what it is (stage-4 N1), and — stage 6 — the close
        // protocol's own run for what it is; re-pinned deliberately. Ruling 4 Q5 (i): those two are the bundle's,
        // read through ProjectCloseText; ruling L3 (b)'s own text stays 331's literal.
        assertThat(methodBody(code, "void openRestoredArchive(Path destination,"))
                .contains("whenNoAppWriteIsPending(RESTORE_FROM_ARCHIVE_DOOR, restored.alreadyDone(), load.refused(),"
                        + " () -> switchIfTheAnswerCovers(load), load.stoppedAfterItsWork());");
        assertThat(methodBody(code, "private void discardJournalThenLoad(Path projectDir)"))
                .contains("whenNoAppWriteIsPending(RECOVER_SESSION_DOOR, alreadyDone,"
                        + " () -> refuseLateLoadWhileATakeIsRecordingOrBeingWritten(alreadyDone),"
                        + " () -> loadProjectFromPath(projectDir))");
        assertThat(methodBody(code, "private void whenNoAppWriteIsPending(String name, String alreadyDone,"
                + " BooleanSupplier refused, Runnable switchNow, Optional<Runnable> stoppedAfterItsWork)"))
                .as("that overload warns every time")
                .contains("String warning = closing != null"
                        + " ? ProjectCloseText.text(\"door.lateRefused.closing\", alreadyDone)"
                        + " : aFirstSaveIsPending() ? ProjectCloseText.text(\"door.lateRefused.firstSave\", alreadyDone)"
                        + " : lateSwitchRefusedMessage(alreadyDone, pendingSwitchName());"
                        + " LOG.warning(warning); notificationBar.show(NotificationLevel.WARNING, warning);")
                .doesNotContain("refuseWhileASwitchIsPending");
        assertThat(methodBody(code, "private void whenNoAppWriteIsPending(String name, String alreadyDone,"
                + " BooleanSupplier refused, Runnable switchNow) {"))
                .as("and the other overload is that one, with the late switch's own report when it is stopped")
                .contains("whenNoAppWriteIsPending(name, alreadyDone, refused, switchNow,"
                        + " Optional.of(() -> reportStoppedAfterItsWork(alreadyDone)));");
    }

    /** {@code file}'s code: comments stripped, string literals kept, whitespace collapsed. */
    private static String code(Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(raw.length()).as("non-vacuity: %s was read", file.getFileName()).isGreaterThan(10_000);
        return SourceScanSupport.stripComments(raw).replaceAll("\\s+", " ");
    }

    /** {@code file}'s code with its comments and its string literals stripped, so only real calls are counted. */
    private static String callsIn(Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(raw.length()).as("non-vacuity: %s was read", file.getFileName()).isGreaterThan(10_000);
        return SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(raw));
    }

    /** The braces-balanced body that follows {@code signature} in {@code code}. */
    private static String methodBody(String code, String signature) {
        int declaration = code.indexOf(signature);
        assertThat(declaration).as("the source declares %s", signature).isNotNegative();
        int open = code.indexOf('{', declaration);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces after " + signature);
    }

    private static int occurrences(String code, String token) {
        Matcher matcher = Pattern.compile(Pattern.quote(token)).matcher(code);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /** The files under {@code root}, relative and with forward slashes, whose code calls {@code token}. */
    private static List<String> sitesIn(Path root, String token) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> sources = files.filter(file -> file.toString().endsWith(".java")).toList();
            assertThat(sources).as("non-vacuity: the application's sources were found").hasSizeGreaterThan(300);
            return sources.stream()
                    .filter(file -> {
                        try {
                            return SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(
                                    Files.readString(file, StandardCharsets.UTF_8))).contains(token);
                        } catch (IOException unreadable) {
                            throw new AssertionError("could not read " + file, unreadable);
                        }
                    })
                    .map(file -> root.relativize(file).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }
}
