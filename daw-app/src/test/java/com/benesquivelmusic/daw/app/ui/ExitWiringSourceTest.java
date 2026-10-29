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
 * Story 333, Goals — Tests, "One-protocol", its source half (design §4.1;
 * {@link ExitProtocolOrderTest} is the behaviour): how the application wires
 * the exit protocol, pinned in the sources because {@code MainController} is
 * never FXML-loaded in tests and {@code DawApplication} never starts in them.
 * {@code DawApplication} installs the primary stage before it shows it and
 * has no hidden handler — its {@code stop()} runs the application-stop
 * fallback first, through {@code MainController.applicationStopping()},
 * and the bus and the dispatcher go down after it, in a {@code finally};
 * {@code MainController} installs the coordinator's
 * consuming close-request filter on that stage, gives the stage no hidden
 * handler and no close-request handler, builds the coordinator right after
 * the lifecycle controller with the application's transport stop, take
 * facts, disposal and terminal exit, routes the three File doors of both
 * hosts — the menu's and the keyboard's, and so the palette's — to it, and
 * is the disposal's only caller through the coordinator. The menu and the
 * palette read one Close Project predicate; Exit passes the input fences as
 * Stop does; the window-inert bit fences every other project action. A
 * source scan, not a reflection consumer (dawg-annotations-reflection §4,
 * "Adding a new reflection consumer").
 */
class ExitWiringSourceTest {

    private static final Path APP = SourceScanSupport.locateDawAppModule()
            .resolve("src/main/java/com/benesquivelmusic/daw/app");
    private static final Path UI = APP.resolve("ui");

    @Test
    void theApplicationInstallsThePrimaryStageBeforeItShowsItAndTearsTheBusDownOnlyInStop() throws IOException {
        String application = flat(code(APP.resolve("DawApplication.java")));

        int install = application.indexOf("mainController.installPrimaryStage(primaryStage);");
        assertThat(install).as("the primary stage is installed").isNotNegative();
        assertThat(install).as("before it shows").isLessThan(application.indexOf("primaryStage.show();"));
        assertThat(install).as("after the controller has its dispatcher")
                .isGreaterThan(application.indexOf("mainController.setFxDispatcher(fxDispatcher);"));
        assertThat(application).as("no hidden handler: the protocol disposes the window before HIDE")
                .doesNotContain("WINDOW_HIDDEN").doesNotContain("setOnHidden(").doesNotContain("setOnCloseRequest(");
        String stop = body(application, "public void stop()");
        assertThat(stop).as("Application.stop() tears the bus and the dispatcher down, last")
                .contains("bus.close();").contains("fxDispatcher.dispose();")
                .contains("FxDispatcher.installDefault(null);");
        assertThat(stop).as("story 333's stage 8: the application-stop fallback runs first, the teardown in a"
                        + " finally after it (ruling R4, constraint 1)")
                .startsWith("{ try { if (mainController != null) { mainController.applicationStopping(); } }"
                        + " finally { if (bus != null) { bus.close();");
        assertThat(stop.indexOf("mainController.applicationStopping();")).as("the fallback before the teardown")
                .isLessThan(stop.indexOf("fxDispatcher.dispose();"));
        String main = flat(code(UI.resolve("MainController.java")));
        assertThat(body(main, "public void applicationStopping()"))
                .as("the controller hands the stop to the exit protocol's coordinator")
                .isEqualTo("{ if (exitCoordinator != null) { exitCoordinator.applicationStopping(); } }");
    }

    @Test
    void theMainControllerInstallsTheConsumingFilterAndGivesThePrimaryStageNoOtherHandler() throws IOException {
        String main = flat(code(UI.resolve("MainController.java")));

        assertThat(body(main, "public void installPrimaryStage(Stage stage)"))
                .contains("exitCoordinator.installOn(stage);")
                .contains("commandPaletteView.setOwner(stage);");
        assertThat(occurrences(main, "exitCoordinator.installOn(")).as("the coordinator is installed on the primary"
                + " stage only").isEqualTo(1);
        assertThat(flat(code(UI.resolve("ExitCoordinator.java"))))
                .as("installOn's filter consumes the close request first, and is added once (the stage-7 review N3)")
                .containsPattern(Pattern.compile("void installOn\\(Stage stage\\) \\{ Objects"
                        + "\\.requireNonNull\\(stage, \"\"\\); if \\(primaryStage == stage\\) \\{ return; \\}"
                        + " if \\(primaryStage != null\\) \\{ throw new IllegalStateException\\(\"\"\\); \\}"
                        + " primaryStage = stage; stage\\.addEventFilter\\(WindowEvent\\.WINDOW_CLOSE_REQUEST,"
                        + " event -> \\{ event\\.consume\\(\\); request\\(new CloseDoor\\.WindowClose\\(\\)\\); \\}\\);"));
        assertThat(handlersOn(main, "setOnHidden")).as("the primary stage has no hidden handler; two tool windows do")
                .containsExactly("atmosAbStage", "renderQueueStage");
        assertThat(handlersOn(main, "setOnCloseRequest"))
                .as("no close-request handler on the primary stage; a floating dock panel has its own")
                .containsExactly("stage");
        assertThat(body(main, "private void ensureFloating(String panelId, DockEntry entry)"))
                .as("a floating panel's close request never reaches the exit (ruling R12 #14)")
                .contains("stage.setOnCloseRequest(").doesNotContain("exitCoordinator");
        int listener = main.indexOf("playButton.sceneProperty().addListener(");
        assertThat(listener).isNotNegative();
        assertThat(main.substring(listener, main.indexOf("});", listener)))
                .as("the scene listener keeps the accelerators and the help, and no dead stage branch")
                .contains("keyboardShortcutController.register(scene);").contains("installContextualHelp(scene);")
                .doesNotContain("instanceof Stage").doesNotContain("runWindowTeardown");
    }

    @Test
    void theCoordinatorIsBuiltOverTheApplicationsTransportDisposalAndTerminalExitRightAfterTheLifecycle()
            throws IOException {
        String main = flat(code(UI.resolve("MainController.java")));

        assertThat(body(main, "private void initialize()"))
                .contains("createProjectLifecycleController(); createExitCoordinator();");
        assertThat(body(main, "private void createExitCoordinator()"))
                .contains("ExitCoordinator.TakeFacts.of(() -> transportController),")
                .contains("() -> transportController != null && transportController.stopForClose(),")
                .contains("this::disposeWindowLifetime, javafx.application.Platform::exit, prompts, prompts,")
                .contains("() -> new ClosingProgressDialog(mainWindow()), projectOperationProgress, notificationBar,")
                .contains("new WindowInputFence(), CloseTimers.onFx(dispatcher()), System::nanoTime, dispatcher(),")
                .contains("ExitCoordinator.NestedLoops.PLATFORM,")
                .contains("projectLifecycleController.setWelcomeAfterClose(this::showWelcomeIfNoProjectOpen);");
        assertThat(occurrences(main, "this::disposeWindowLifetime")).as("DISPOSE has one caller, the coordinator")
                .isEqualTo(1);
        assertThat(occurrences(main, "disposeWindowLifetime(")).as("and no direct call").isEqualTo(1);
        int scanned = 0;
        try (Stream<Path> sources = Files.walk(APP)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")
                    && !path.getFileName().toString().equals("MainController.java")).toList()) {
                assertThat(SourceScanSupport.stripComments(Files.readString(source, StandardCharsets.UTF_8)))
                        .as("%s never disposes the window", source.getFileName())
                        .doesNotContain("disposeWindowLifetime");
                scanned++;
            }
        }
        assertThat(scanned).as("non-vacuity: the application's sources were scanned").isGreaterThan(100);
    }

    @Test
    void theDisposalHandsTheEngineStopToItsThreadEvenAfterAFailureAndNamesTheStorysHiddenDisposal()
            throws IOException {
        String raw = Files.readString(UI.resolve("MainController.java"), StandardCharsets.UTF_8);
        String main = flat(code(UI.resolve("MainController.java")));

        String disposal = body(main, "java.util.concurrent.CompletionStage<Void> disposeWindowLifetime()");
        assertThat(disposal).as("the hand-off starts in a finally, so a failing FX part still stops the engine")
                .contains("} finally { renderQueue = null; windowLifetimeDisposal ="
                        + " ExitCoordinator.onTheEngineShutdownThread(new EngineShutdown(audioEngine, queueToPersist,"
                        + " pluginSupervisor, backupRetentionController, diskScanner)); }")
                .contains("if (windowLifetimeDisposal != null) { return windowLifetimeDisposal; }")
                .contains("this::hideEveryOtherWindow);")
                .doesNotContain("audioEngine.shutdown()").doesNotContain("pluginSupervisor.close()")
                .doesNotContain("renderQueue.persist()").doesNotContain("diskScanner.stop()")
                .doesNotContain("journal").doesNotContain("closeSession");
        assertThat(body(main, "private record EngineShutdown(")).as("the engine's stop runs on the hand-off's thread")
                .contains("audioEngine.shutdown();").contains("renderQueue.persist();")
                .contains("pluginSupervisor.close();").contains("diskScanner.stop();");
        assertThat(occurrences(main, "audioEngine.shutdown();")).isEqualTo(1);
        assertThat(body(main, "private void hideEveryOtherWindow()"))
                .contains("exitCoordinator.ownsWindow(window)").contains("window.hide();");
        int declaration = raw.indexOf("java.util.concurrent.CompletionStage<Void> disposeWindowLifetime()");
        assertThat(raw.substring(raw.lastIndexOf("/**", declaration), declaration))
                .as("the Javadoc says this is the story's \"setOnHidden disposal\" (COV-N-5)")
                .contains("\"{@code setOnHidden} disposal\"").contains("after RELEASE");
    }

    @Test
    void bothHostsRouteTheThreeFileDoorsToOneIntentAndExitPassesTheFencesAsStopDoes() throws IOException {
        String main = flat(code(UI.resolve("MainController.java")));

        assertThat(occurrences(main, "@Override public void onCloseProject() {"
                + " exitCoordinator.request(new ExitCoordinator.CloseDoor.CloseProject()); }"))
                .as("the menu's MenuActions and the keyboard's Host").isEqualTo(2);
        assertThat(occurrences(main, "@Override public void onExit() {"
                + " exitCoordinator.request(new ExitCoordinator.CloseDoor.FileExit()); }")).isEqualTo(2);
        assertThat(occurrences(main, "@Override public void onSaveProjectAs() {"
                + " projectLifecycleController.onSaveProjectAs(); }")).isEqualTo(2);
        assertThat(occurrences(main, "exitCoordinator.request(")).as("no other door reaches the coordinator")
                .isEqualTo(4);

        String keyboard = flat(code(UI.resolve("KeyboardShortcutController.java")));
        assertThat(keyboard).contains("actionHandlers.put(DawAction.CLOSE_PROJECT, host::onCloseProject);")
                .contains("actionHandlers.put(DawAction.EXIT, host::onExit);")
                .contains("if (action == DawAction.STOP || action == DawAction.EXIT"
                        + " || projectActionsAllowed.getAsBoolean()) handler.run();");
        String menus = Files.readString(UI.resolve("MenuConstructionService.java"), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");
        assertThat(menus)
                .contains("menuItem(msg(\"menu.file.closeProject\"), DawIcon.CLOSE, DawAction.CLOSE_PROJECT,"
                        + " host::onCloseProject);")
                .contains("menuItem(msg(\"menu.file.exit\"), DawIcon.POWER, DawAction.EXIT, host::onExit);")
                .contains("if (action == DawAction.STOP || action == DawAction.EXIT"
                        + " || projectActionsAllowed.getAsBoolean()) {");
    }

    @Test
    void theMenuAndThePaletteReadOneCloseProjectPredicateAndTheInertWindowFencesProjectActions()
            throws IOException {
        String main = flat(code(UI.resolve("MainController.java")));

        assertThat(body(main, "private boolean closeProjectAvailable()"))
                .isEqualTo("{ return exitCoordinator != null && exitCoordinator.closeProjectAvailable(); }");
        assertThat(body(main, "private void createMenuBar()"))
                .contains("() -> projectLifecycleController != null && projectLifecycleController.hasUnresolvedAssets(),"
                        + " this::closeProjectAvailable);");
        assertThat(body(main, "private void createCommandPaletteView()"))
                .contains("commandPaletteEntries(handlers, keyBindingManager, this::closeProjectAvailable)");
        // Ruling 4 Q4 and the app-fix-2 review S1: the installation gate is its own predicate, half of the autosave's; the inert bit is input-only.
        assertThat(body(main, "private boolean projectActionsAllowed()"))
                .isEqualTo("{ return noProjectInstallationPending()"
                        + " && (exitCoordinator == null || !exitCoordinator.isWindowInert()); }");
        assertThat(body(main, "private boolean noProjectInstallationPending()"))
                .isEqualTo("{ return projectLifecycleController == null"
                        + " || !projectLifecycleController.isProjectInstallationPending(); }");
    }

    /**
     * The studio engineer's stage-7 N2 (ruling 4 Q5 (viii)): DISPOSE's
     * hand-off persists the render queue before the audio driver's stop, and
     * ends the queue's workers after it. The behaviour is
     * {@code WindowLifetimeDisposalTest}'s; this pins the shape the pins of
     * {@code MeterWiringConformanceScanTest} read around.
     */
    @Test
    void theHandOffPersistsTheRenderQueueBeforeTheEnginesStop() throws IOException {
        String handOff = body(flat(code(UI.resolve("MainController.java"))), "private record EngineShutdown(");
        int persist = handOff.indexOf("renderQueue.persist();");
        int stop = handOff.indexOf("audioEngine.shutdown();");
        int workers = handOff.indexOf("renderQueue.shutdown();");
        assertThat(persist).isNotNegative().isLessThan(stop);
        assertThat(workers).isGreaterThan(stop);
        assertThat(occurrences(handOff, "renderQueue.persist();")).as("persisted once, first").isEqualTo(1);
    }

    @Test
    void theDoorsJournalCloseIsTheMethodThatChecksTheOpenFolder() throws IOException {
        String main = flat(code(UI.resolve("MainController.java")));

        assertThat(body(main, "private void createProjectLifecycleController()"))
                .contains("this::prepareDoorJournalClose, projectJournalCoordinator::openForRequired);");
        assertThat(body(main, "Runnable prepareDoorJournalClose()"))
                .contains("Path closingFolder = currentProjectFolder();")
                .contains("closingFolder != null && closingFolder.equals(journalDirectory) ? journalDirectory : null;")
                .contains("return () -> projectJournalCoordinator.closeCurrentFor(journalOfTheFolder, outgoing);");
    }

    @Test
    void theProtocolsPostsAreUnkeyedButSaveAsProgressAndNoneOfItsClassesStylesInline() throws IOException {
        for (String file : List.of("ProjectFolderCloser.java", "SaveAsFlow.java")) {
            String code = code(UI.resolve(file));
            Matcher keyed = Pattern.compile("\\.onFx\\(([^(),]+),").matcher(code);
            while (keyed.find()) {
                assertThat(keyed.group(1).trim()).as("%s: a keyed post is only Save As's progress update", file)
                        .isEqualTo("run.progressKey");
            }
        }
        for (String file : List.of("ExitCoordinator.java", "WindowInputFence.java", "ClosingProgress.java",
                "ClosingProgressDialog.java", "ClosePromptAnswers.java", "ProjectFolderCloser.java", "SaveAsFlow.java",
                "SaveAsProgressDialog.java", "SessionJournalEnding.java", "JournalEnding.java",
                "ProjectClosePrompts.java")) {
            assertThat(code(UI.resolve(file))).as(file).doesNotContain("setStyle(");
        }
    }

    /** The names of the receivers of every {@code .<method>(} call in {@code code}, in order. */
    private static List<String> handlersOn(String code, String method) {
        Matcher matcher = Pattern.compile("(\\w+)\\." + method + "\\(").matcher(code);
        java.util.ArrayList<String> receivers = new java.util.ArrayList<>();
        while (matcher.find()) {
            receivers.add(matcher.group(1));
        }
        return receivers;
    }

    private static String code(Path file) throws IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(source.length()).as("non-vacuity: %s was read", file.getFileName()).isGreaterThan(500);
        return SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(source));
    }

    private static String flat(String code) {
        return code.replaceAll("\\s+", " ");
    }

    private static int occurrences(String code, String fragment) {
        int count = 0;
        for (int at = code.indexOf(fragment); at >= 0; at = code.indexOf(fragment, at + 1)) {
            count++;
        }
        return count;
    }

    private static String body(String code, String signature) {
        int declaration = code.indexOf(signature);
        assertThat(declaration).as("declares %s", signature).isNotNegative();
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
}
