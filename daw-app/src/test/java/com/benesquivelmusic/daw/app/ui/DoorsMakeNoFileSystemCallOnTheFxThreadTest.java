package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, Goals — Tests, "Off-FX" (the source half; design §4.1, §4.5):
 * the FX-thread halves of New, of a first save and of an open make no direct
 * file-system call. {@code NewFirstSaveAndOpenMakeNoFileSystemCallOnTheFxThreadTest}
 * proves it at run time over a recording file system; this scan covers what
 * that run cannot reach — the location prompt's own default (a native
 * chooser, which a test replaces) — and pins the two calls that moved off the
 * FX thread: {@code ProjectManager.createProject} and the migration dialog's
 * {@code MigrationSuppression} read.
 *
 * <p>It scans, comments stripped and literals blanked (the
 * {@code FxHalfOfASaveMakesNoFileSystemCallTest} idiom, whose call shapes it
 * reuses): {@code promptNewProjectLocation}, {@code saveIntoANewFolder},
 * {@code saveIntoTheCreatedFolder}, {@code createNewProjectAfterClose},
 * {@code newProjectFolderCreated}, {@code maybeShowMigrationReport},
 * {@code finishPreparedLoad} and {@code prepareLoadedProject}. Stage 5 adds
 * Save As's FX methods. It fails closed: a method no longer declared, or
 * declared twice, fails it. A source scan, not a reflection consumer
 * (dawg-annotations-reflection §4, "Adding a new reflection consumer").</p>
 */
class DoorsMakeNoFileSystemCallOnTheFxThreadTest {

    private static final List<String> FX_HALVES = List.of("promptNewProjectLocation", "saveIntoANewFolder",
            "saveIntoTheCreatedFolder", "createNewProjectAfterClose", "newProjectFolderCreated",
            "maybeShowMigrationReport", "finishPreparedLoad", "prepareLoadedProject",
            // The close protocol's FX halves in the controller (the stage-6 review S5, the stage-7 review N8):
            // Close Project's replacement and its Welcome window, FLUSH's save, GUARD's waits and its door's end.
            "installUntitledAfterClose", "installFreshUntitledProject", "showWelcomeAfterClose",
            "saveBeforeClosing", "whenItsSavesHaveReported", "awaitTheSavesOf", "cancelWaitingDoorForExit");

    private static final java.nio.file.Path MAIN_CONTROLLER = SourceScanSupport.locateDawAppModule()
            .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java");

    /** The disk calls that moved off the FX thread (story 333): none may come back. */
    private static final List<Pattern> MOVED_OFF = List.of(
            Pattern.compile("\\bprojectManager\\s*\\.\\s*createProject\\s*\\("),
            Pattern.compile("\\bMigrationSuppression\\s*\\.\\s*isSuppressed\\b"),
            Pattern.compile("\\brememberRecentProject\\s*\\("),
            Pattern.compile("\\bplanSaveAs\\s*\\("),
            Pattern.compile("\\bpublishCopy\\s*\\("),
            Pattern.compile("\\bprepareLockedAccess\\s*\\("),
            Pattern.compile("\\badoptPublishedCopy\\s*\\("),
            Pattern.compile("\\bopenJournalOf\\s*\\("),
            // Stage-5 review N13: an unadopted lease's release and the pre-fill's checks are the workers'.
            Pattern.compile("\\bcloseQuietly\\s*\\("),
            Pattern.compile("\\bfirstFreeName\\s*\\("),
            Pattern.compile("\\bisAWritableDirectory\\s*\\("),
            Pattern.compile("\\bexists\\s*\\("),
            Pattern.compile("\\bwritableDirectory\\s*\\.\\s*test\\s*\\("),
            Pattern.compile("\\bnameTaken\\s*\\.\\s*test\\s*\\("));

    /**
     * Save As's FX-thread halves (story 333, SKL-S10): the target, the
     * capture, the refusals, the original's close and the adopted copy's FX
     * half. The workers' methods ({@code *OnTheWorker}) are not scanned.
     */
    private static final List<String> SAVE_AS_FX_HALVES = List.of("start", "publishCopyBeforeClosing",
            "firstPrefill", "prefillOffTheFxThread", "writableWithin", "freeNameWithin", "freeNameOffTheFxThread",
            "askForTheTarget", "choose", "publishedAtExit", "beginClosingTheOriginal", "closeTheOriginal",
            "planForTheOriginal", "startClosing", "offerToContinue", "continuePressed", "originalClosed",
            "originalNotClosed", "adopted",
            "isTheOpenFolder", "reportDone", "notOpened", "targetRefused", "reopened", "cancelPressed", "fail", "end",
            "showTheDialog", "hideTheDialog");

    private static final java.nio.file.Path SAVE_AS_FLOW = SourceScanSupport.locateDawAppModule()
            .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/SaveAsFlow.java");

    @Test
    void theFxHalvesOfSaveAsMakeNoFileSystemCallAndHandTheirDiskWorkToTheWorkers() throws IOException {
        String saveAs = FxHalfOfASaveMakesNoFileSystemCallTest.code(
                java.nio.file.Files.readString(SAVE_AS_FLOW, java.nio.charset.StandardCharsets.UTF_8));
        assertThat(saveAs.length()).as("non-vacuity: SaveAsFlow was read").isGreaterThan(10_000);
        List<String> offenders = new ArrayList<>();
        for (String method : SAVE_AS_FX_HALVES) {
            FxHalfOfASaveMakesNoFileSystemCallTest.ScannedBody body =
                    FxHalfOfASaveMakesNoFileSystemCallTest.body("SaveAsFlow.java", saveAs, method);
            for (FxHalfOfASaveMakesNoFileSystemCallTest.Site site
                    : FxHalfOfASaveMakesNoFileSystemCallTest.fileSystemCalls(body.code())) {
                offenders.add(method + ": " + site.label() + " in " + site.line());
            }
            for (Pattern movedOff : MOVED_OFF) {
                Matcher found = movedOff.matcher(body.code());
                if (found.find()) {
                    offenders.add(method + ": " + found.group() + " is the worker's");
                }
            }
        }

        assertThat(offenders).as("no disk I/O on the FX thread (javafx-application-design skill §11 \"Threading\")")
                .isEmpty();
        String choose = FxHalfOfASaveMakesNoFileSystemCallTest.body("SaveAsFlow.java", saveAs, "choose").code()
                .replaceAll("\\s+", " ");
        assertThat(choose).as("the plan and the copy are handed to the worker")
                .contains("worker.execute(() -> planOnTheWorker(run, capture, target));");
        assertThat(FxHalfOfASaveMakesNoFileSystemCallTest.body("SaveAsFlow.java", saveAs, "originalClosed").code()
                .replaceAll("\\s+", " ")).as("and the adoption")
                .contains("worker.execute(() -> adoptOnTheWorker(run, published, lease, continued));");
    }

    @Test
    void theFxHalvesOfNewAFirstSaveAndAnOpenMakeNoFileSystemCall() throws IOException {
        String lifecycle = FxHalfOfASaveMakesNoFileSystemCallTest.code(
                FxHalfOfASaveMakesNoFileSystemCallTest.read(FxHalfOfASaveMakesNoFileSystemCallTest.LIFECYCLE));
        List<String> offenders = new ArrayList<>();
        for (String method : FX_HALVES) {
            FxHalfOfASaveMakesNoFileSystemCallTest.ScannedBody body =
                    FxHalfOfASaveMakesNoFileSystemCallTest.body("ProjectLifecycleController.java", lifecycle, method);
            for (FxHalfOfASaveMakesNoFileSystemCallTest.Site site
                    : FxHalfOfASaveMakesNoFileSystemCallTest.fileSystemCalls(body.code())) {
                offenders.add(method + ": " + site.label() + " in " + site.line());
            }
            for (Pattern movedOff : MOVED_OFF) {
                Matcher found = movedOff.matcher(body.code());
                if (found.find()) {
                    offenders.add(method + ": " + found.group() + " is the worker's");
                }
            }
        }

        assertThat(offenders).as("no disk I/O on the FX thread (javafx-application-design skill §11 \"Threading\")")
                .isEmpty();
    }

    /**
     * Close Project's replacement runs the controller's folder-changed hook on
     * the FX thread, which MainController wires to its folder-dependent work
     * ({@code projectFolderChanged}): it makes no direct file-system call
     * either (the stage-7 review N8; javafx skill §11).
     */
    @Test
    void theFolderChangedHookMainControllerWiresMakesNoFileSystemCall() throws IOException {
        String main = FxHalfOfASaveMakesNoFileSystemCallTest.code(
                java.nio.file.Files.readString(MAIN_CONTROLLER, java.nio.charset.StandardCharsets.UTF_8));
        FxHalfOfASaveMakesNoFileSystemCallTest.ScannedBody hook =
                FxHalfOfASaveMakesNoFileSystemCallTest.body("MainController.java", main, "projectFolderChanged");
        List<String> offenders = new ArrayList<>();
        for (FxHalfOfASaveMakesNoFileSystemCallTest.Site site
                : FxHalfOfASaveMakesNoFileSystemCallTest.fileSystemCalls(hook.code())) {
            offenders.add("projectFolderChanged: " + site.label() + " in " + site.line());
        }

        assertThat(hook.code()).as("non-vacuity: the hook's body was read").contains("syncMenuStateIfPresent");
        assertThat(hook.code()).as("no Files. call").doesNotContain("Files.");
        assertThat(offenders).as("no disk I/O on the FX thread").isEmpty();
    }

    @Test
    void theScanReadsTheHandOffsToTheWorkers() throws IOException {
        String lifecycle = FxHalfOfASaveMakesNoFileSystemCallTest.code(
                FxHalfOfASaveMakesNoFileSystemCallTest.read(FxHalfOfASaveMakesNoFileSystemCallTest.LIFECYCLE));
        String newProject = FxHalfOfASaveMakesNoFileSystemCallTest.body("ProjectLifecycleController.java", lifecycle,
                "createNewProjectAfterClose").code().replaceAll("\\s+", " ");
        String prompt = FxHalfOfASaveMakesNoFileSystemCallTest.body("ProjectLifecycleController.java", lifecycle,
                "promptNewProjectLocation").code().replaceAll("\\s+", " ");
        String migration = FxHalfOfASaveMakesNoFileSystemCallTest.body("ProjectLifecycleController.java", lifecycle,
                "maybeShowMigrationReport").code().replaceAll("\\s+", " ");

        String fresh = FxHalfOfASaveMakesNoFileSystemCallTest.body("ProjectLifecycleController.java", lifecycle,
                "installFreshUntitledProject").code().replaceAll("\\s+", " ");
        assertThat(newProject).as("New hands its folder's creation to the worker")
                .contains("createProjectOffTheFxThread(newProject.getName(), parentDirectory)");
        // Re-pinned deliberately (the studio engineer's stage-7 S3): New's replacement is the one it shares with
        // Close Project, whose body holds the rebuild and the layout reset.
        assertThat(newProject).contains("DawProject newProject = installFreshUntitledProject();");
        assertThat(newProject.indexOf("createProjectOffTheFxThread("))
                .as("stage-4 review S1: the create starts after the replacement — the rebuild and the layout"
                        + " reset — as the FX half's last statement, so the rebuild never reads a half-made project"
                        + " manager")
                .isGreaterThan(newProject.indexOf("installFreshUntitledProject();"))
                .isGreaterThan(-1);
        assertThat(fresh.indexOf("deps.applyLayoutJson().accept(null);"))
                .as("the replacement rebuilds the UI, then resets the layouts")
                .isGreaterThan(fresh.indexOf("rebuildUI();")).isGreaterThan(-1);
        assertThat(fresh.indexOf("rebuildUI();")).isGreaterThan(-1);
        assertThat(prompt).as("the prompt reads the workspace the worker resolved")
                .contains("java.io.File workspace = workspaceRootIfDirectory;");
        assertThat(migration).as("the dialog is told whether it is suppressed")
                .contains("MigrationReportDialog.showUnlessSuppressed(report, projectDir, suppressed,");
    }
}
