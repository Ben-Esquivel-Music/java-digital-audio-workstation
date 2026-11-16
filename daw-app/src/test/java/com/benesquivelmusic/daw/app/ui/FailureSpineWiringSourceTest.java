package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Story 336, the wiring the in-process tests cannot run:
 * {@code DawLauncher.main} never runs in a test, {@code DawApplication} never
 * starts and {@code MainController} is never FXML-loaded, so the tests that
 * drive the failure spine call the same production units these call sites
 * call ({@code FailureSpineFixture}), and this source scan pins the call sites
 * themselves — in the Failure Surfacing book §6.4's order: the log manager
 * named, then the log sink and its banner, then the default handler, all
 * before the toolkit starts; the dispatcher built on the funnel's pulse
 * failure sink and the event bus on its bus failure sink (story 337), and the
 * FX thread's handler right after the
 * dispatcher, before the window is loaded; the toasts armed right after the
 * window's controller has its dispatcher, before the window shows and the
 * Welcome screen or first-run wizard opens, and then the window's audio
 * health drain registered on that dispatcher, removed in its disposal, with
 * its halt response and Restart Audio Engine (story 337). It pins Help ▸ Open Log Folder's
 * route, the seven plugin disposals story 334 left to this story, and that the
 * spine's own code opens no window and takes no focus (story 336, studio-engineer ruling E9) and posts to
 * the FX thread only through the dispatcher (javafx-application-design
 * "## 11. Threading"; book Appendix B). It also pins what the tests that run
 * {@code DawLauncher} cannot tell apart: that the production read names the
 * system property and the environment variable by the constants the rejection's
 * message is built from, the second of which {@code ChildJvm} also uses to
 * remove the variable from each child's environment, that a rejection is
 * logged without parameters, which would make its value a {@code MessageFormat} pattern,
 * and that {@code IOException} is the only failure caught around {@code zgc.conf}'s write
 * (story 336, studio-engineer ruling R992-8). Its pin of that log call also holds that a
 * rejection has no throwable, which {@code PackagedLaunchLoggingTest} checks too.
 *
 * <p>A source scan, not a reflection consumer (dawg-annotations-reflection
 * §4, "Adding a new reflection consumer"): comments are stripped, and string
 * literals too wherever the text pinned is code.</p>
 */
class FailureSpineWiringSourceTest {

    private static final Path APP = SourceScanSupport.locateDawAppModule()
            .resolve("src/main/java/com/benesquivelmusic/daw/app");
    private static final Path UI = APP.resolve("ui");
    private static final String GUARDED_DISPOSAL =
            "DeclaredSubject.pluginDisposal(slot.getName()).guard(slot::disposeAfterQuiescence)";

    @Test
    void theLauncherNamesTheLogManagerInstallsTheSinkAndTheDefaultHandlerBeforeTheToolkitStarts()
            throws IOException {
        String launcher = flat(code(APP.resolve("DawLauncher.java")));

        assertThat(body(launcher, "public static void main(String[] args)"))
                .isEqualTo("{ prepareLaunch(userSettingsDirectory()); Application.launch(DawApplication.class,"
                        + " args); }");
        String prepare = body(launcher, "public static FailureFunnel prepareLaunch(Path settingsDirectory)");
        assertThat(prepare).as("the log manager is named before anything touches java.util.logging")
                .startsWith("{ selectExitHoldingLogManager(); FailureFunnel funnel ="
                        + " installDiagnostics(settingsDirectory, Thread.currentThread());");
        assertThat(prepare.indexOf("installDiagnostics(")).as("the sink and the default handler before zgc.conf")
                .isLessThan(prepare.indexOf("installZgcConfig(settingsDirectory,"));
        String diagnostics = body(launcher,
                "static FailureFunnel installDiagnostics(Path settingsDirectory, Thread launchThread)");
        assertThat(diagnostics).as("an oversized last-resort file aside, then the sink, its banner, the funnel,"
                        + " and the default handler last")
                .containsSubsequence("LastResortLog.moveOversizedAtLaunch(logFolder);",
                        "LogSink sink = LogSink.install(logFolder);", "sink.logSessionStart();",
                        "FailureFunnel funnel = FailureFunnel.forLaunch(sink, launchThread);",
                        "funnel.installAsDefaultHandler();");
        assertThat(Pattern.compile("(?:private|protected|public|static|final)\\s+(?:\\w+\\s+)*"
                        + "(?:java\\.util\\.logging\\.)?Logger\\s+\\w+\\s*[=;]").matcher(launcher).find())
                .as("DawLauncher declares no Logger field: one would initialize LogManager before main names it")
                .isFalse();
        assertThat(launcher).as("and no static initializer").doesNotContain("static {");
    }

    @Test
    void theLauncherReadsTheSessionMemorySettingByItsConstantsAndCatchesOnlyTheWriteFailure() throws IOException {
        String launcher = flat(code(APP.resolve("DawLauncher.java")));
        String prepare = body(launcher, "public static FailureFunnel prepareLaunch(Path settingsDirectory)");

        assertThat(occurrences(prepare, "log(Level.WARNING, rejected.warning());"))
                .as("a rejection is logged with neither parameters, which would make its value a MessageFormat"
                        + " pattern, nor a throwable")
                .isEqualTo(1);
        assertThat(body(launcher, "static SessionMem resolveSessionMem()"))
                .as("the production read names the property and the variable by the constants the rejection's"
                        + " message is built from")
                .isEqualTo("{ return resolveSessionMem(Optional.ofNullable(System.getProperty(SESSION_MEM_PROPERTY)),"
                        + " Optional.ofNullable(System.getenv(SESSION_MEM_VARIABLE))); }");
        assertThat(occurrences(prepare, "catch (")).as("one catch in prepareLaunch").isEqualTo(1);
        assertThat(prepare).as("and it catches only zgc.conf's IOException").contains("} catch (IOException e) {");
    }

    @Test
    void theApplicationInstallsTheFxHandlerWithTheDispatcherAndArmsTheToastsBeforeTheWindowShows()
            throws IOException {
        String start = body(flat(code(APP.resolve("DawApplication.java"))),
                "public void start(Stage primaryStage)");

        assertThat(start).as("the funnel first, the dispatcher built on its pulse sink, the FX thread's handler"
                        + " right after the dispatcher, the bus built on the funnel's bus sink, all before the window"
                        + " loads (story 337, rulings E32 and E37)")
                .containsSubsequence("failureFunnel = DawLauncher.failureFunnel();",
                        "fxDispatcher = new FxDispatcher(failureFunnel.pulseFailureSink());", "fxDispatcher.start();",
                        "FxDispatcher.installDefault(fxDispatcher);", "failureFunnel.installOnCurrentFxThread();",
                        "DefaultEventBus.builder(failureFunnel.busFailureSink())", "loader.load();");
        assertThat(occurrences(start, "new FxDispatcher(")).as("the one dispatcher, with the sink").isEqualTo(1);
        assertThat(start).as("the toasts armed right after the controller has its dispatcher, before the window"
                        + " shows and the Welcome screen or first-run wizard opens (book §6.4)")
                .containsSubsequence("mainController.setFxDispatcher(fxDispatcher);"
                                + " mainController.attachFailureFunnel(failureFunnel);",
                        "mainController.installPrimaryStage(primaryStage);", "primaryStage.show();",
                        "mainController.showWelcomeIfNoProjectOpen();");
        assertThat(occurrences(start, "attachFailureFunnel(")).isEqualTo(1);
    }

    @Test
    void theWindowArmsTheToastsAndRoutesHelpOpenLogFolderToTheFunnel() throws IOException {
        String main = flat(code(UI.resolve("MainController.java")));

        String attach = body(main, "public void attachFailureFunnel(FailureFunnel funnel)");
        assertThat(attach).as("set once, a second attach refused before anything is touched (story 337, ruling E73)")
                .startsWith("{ Objects.requireNonNull(funnel, \"\"); if (failureFunnel != null) {"
                        + " throw new IllegalStateException(")
                .contains("this.failureFunnel = funnel; if (notificationBar == null || fxDispatcher == null) {"
                        + " LOG.warning(")
                .as("the toasts armed, then the audio health drain registered (story 337, ruling E42)")
                .endsWith("funnel.armToasts(fxDispatcher, notificationBar); attachAudioHealthDrain(funnel); }");
        assertThat(body(main, "} void onOpenLogFolder()"))
                .as("the window's own method, not the MenuActions override that calls it")
                .isEqualTo("{ if (failureFunnel != null) { failureFunnel.openLogFolder(); } }");
        assertThat(occurrences(main,
                "@Override public void onOpenLogFolder() { MainController.this.onOpenLogFolder(); }"))
                .as("the window's MenuActions routes Help ▸ Open Log Folder to it").isEqualTo(1);
        assertThat(body(main, "public void applicationStopping()"))
                .as("story 333's pinned body is untouched")
                .isEqualTo("{ if (exitCoordinator != null) { exitCoordinator.applicationStopping(); } }");

        assertThat(flat(code(UI.resolve("DawMenuBarController.java"))))
                .contains("void onHelp(); default void onOpenLogFolder() { } }");
        assertThat(flat(SourceScanSupport.stripComments(read(UI.resolve("MenuConstructionService.java")))))
                .as("Help ▸ Open Log Folder: the bundle's words, the folder icon, no DawAction, no shortcut")
                .contains("MenuItem openLogFolderItem = menuItem(msg(\"menu.help.openLogFolder\"),"
                        + " DawIcon.FOLDER, null, host::onOpenLogFolder);"
                        + " helpMenu.getItems().addAll(helpItem, openLogFolderItem);");
    }

    /**
     * Story 337, rulings E11, E12 and E42: the window registers its audio
     * health drain over its engine's record as a pulse participant of its own
     * dispatcher, once (ruling E73), removes it in its disposal next to the
     * meters, stops and closes through the Stop gesture's paths when the
     * render drive halts — only while the open stream's drive is the halted
     * one, the Stop closing nothing and the close never waiting for the
     * engine's lifecycle lock (ruling E69) — and restarts the engine through
     * the controller on the restart's own thread.
     * {@code FailureSpineStartupWiringTest} runs the registration;
     * {@code MainControllerRestartAudioEngineTest} the restart's helper. The
     * window is never FXML-loaded in a test, so its halt fence is pinned here;
     * {@code AudioEngineRenderDriveHaltTest} and {@code TransportControllerTest}
     * run the engine's and the transport's halves on real objects, the
     * transport answering what its halt Stop did (ruling E101), and
     * {@code CountInRecordStartTest} runs the window's halt response over a
     * real halt.
     */
    @Test
    void theWindowRegistersTheAudioHealthDrainRemovesItOnDisposalAndRestartsThroughTheController()
            throws IOException {
        String main = flat(code(UI.resolve("MainController.java")));

        assertThat(body(main, "private void attachAudioHealthDrain(FailureFunnel funnel)"))
                .startsWith("{ if (audioEngine == null) { return; }")
                .as("one drain for the window's life: no replacement removes an earlier one (ruling E73)")
                .doesNotContain("audioHealthDrainRemoval.run();")
                .contains("audioHealthDrain = new AudioHealthDrain(new AudioHealthDrain.Collaborators("
                        + "audioEngine.audioHealth(), funnel, System::nanoTime, InstantSource.system(),"
                        + " this::audioDeviceDisplayName, () -> restartAudioEngine(),")
                .contains("return stopTransportForEngineHalt();").contains("return closeStreamForEngineHalt();")
                .contains("public boolean retryCloseStreamForHalt() { return retryCloseStreamForEngineHalt(); }")
                .endsWith("audioHealthDrainRemoval = fxDispatcher.addPulseParticipant(audioHealthDrain::pulse); }");
        assertThat(occurrences(main, "new AudioHealthDrain(")).as("one drain, built there").isEqualTo(1);
        assertThat(occurrences(main, "attachAudioHealthDrain(")).as("declared once, called by attachFailureFunnel")
                .isEqualTo(2);

        assertThat(body(main, "java.util.concurrent.CompletionStage<Void> disposeWindowLifetime()"))
                .as("the drain's participant is removed next to the meters' (ruling E42)")
                .containsSubsequence("engineStateBridge.cancel();",
                        "if (audioHealthDrainRemoval != null) { audioHealthDrainRemoval.run();"
                                + " audioHealthDrainRemoval = null; } audioHealthDrain = null;",
                        "meterFeed.dispose();");

        assertThat(body(main, "private HaltContext stopTransportForEngineHalt()"))
                .as("a halt whose stream is no longer the halted one stops nothing (rulings E69, E70 and E72)")
                .startsWith("{ AudioEngine engine = audioEngine; if (engine == null || !engine.isRenderDriveHalted())"
                        + " { return HaltContext.STREAM_REPLACED; }")
                .as("then the transport's halt Stop runs and answers what it did, which the window hands over"
                        + " (rulings E11 step 1, E67 and E101)")
                .isEqualTo("{ AudioEngine engine = audioEngine; if (engine == null || !engine.isRenderDriveHalted())"
                        + " { return HaltContext.STREAM_REPLACED; } TransportController transport = transportController;"
                        + " if (transport == null || project == null) { return HaltContext.IDLE; }"
                        + " return transport.stopForEngineHalt(); }");
        String transport = flat(code(UI.resolve("TransportController.java")));
        assertThat(body(transport, "HaltContext stopForEngineHalt()"))
                .as("only while the transport rolls or a take is in flight, the normal Stop, then the end of any"
                        + " post-roll on the same turn (ruling E67), neither closing the stream: the halt's own close"
                        + " follows (ruling E69); it answers what that Stop did (ruling E101)")
                .isEqualTo("{ Transport transport = project.getTransport(); boolean wasRolling = transport.getState()"
                        + " != TransportState.STOPPED; if (!wasRolling && !isRecordingInFlight()) {"
                        + " return HaltContext.IDLE; } boolean countingIn = recordCoordinator.isCountingIn();"
                        + " boolean endedAStartedTake = stopTransport(NO_CLOSE); if (transport.isInPostRoll()) {"
                        + " if (postRollTimer != null) { postRollTimer.stop(); } endPostRoll(NO_CLOSE); }"
                        + " if (endedAStartedTake) { return HaltContext.RECORDING; }"
                        + " return wasRolling && !countingIn ? HaltContext.PLAYBACK : HaltContext.IDLE; }");
        assertThat(body(main, "private boolean closeStreamForEngineHalt()"))
                .as("only the halted drive's stream is closed; else the transport closes it (rulings E11 step 2 and"
                        + " E69)")
                .contains("if (engine == null || !engine.isRenderDriveHalted()) { return true; }")
                .endsWith("return transport == null || transport.closeStreamAfterEngineHalt(); }");
        assertThat(body(transport, "boolean closeStreamAfterEngineHalt()"))
                .as("closes even with graph instruments, once the take no longer defers the stop, only the halted"
                        + " stream and never waiting for the lifecycle lock (ruling E69)")
                .isEqualTo("{ if (recordCoordinator.deferInputStreamStop()) return false;"
                        + " return audioEngine.closeHaltedStream() != AudioEngine.HaltedStreamClose.LIFECYCLE_BUSY; }");
        assertThat(body(main, "private boolean retryCloseStreamForEngineHalt()"))
                .as("a later frame's retry closes only the halted drive's stream too, through the transport (E66,"
                        + " E69)")
                .contains("if (engine == null || !engine.isRenderDriveHalted()) { return true; }")
                .endsWith("return transport == null || transport.retryCloseStreamAfterEngineHalt(); }");
        assertThat(body(transport, "boolean retryCloseStreamAfterEngineHalt()"))
                .as("the retry only asks whether the take defers the stop; it never restarts its cleanup (E66)")
                .isEqualTo("{ if (recordCoordinator.isInputStreamStopDeferred()) return false;"
                        + " return audioEngine.closeHaltedStream() != AudioEngine.HaltedStreamClose.LIFECYCLE_BUSY; }");

        assertThat(body(main, "static Thread restartAudioEngine(SettingsModel settings,"
                + " AudioEngineController controller, BooleanSupplier streamOpen, Runnable onRestarted)"))
                .as("the restart's own virtual thread applies the persisted configuration through the controller"
                        + " (ruling E12), and only a restart that left open a stream open at its start succeeded"
                        + " (ruling E62)")
                .contains("Thread.ofVirtual().name(RESTART_AUDIO_ENGINE_THREAD).unstarted(")
                .containsSubsequence("AudioEngineController.Request request = audioConfigurationRequest(settings,"
                                + " controller);", "boolean wasOpen = streamOpen.getAsBoolean();",
                        "controller.applyConfiguration(request);",
                        "} catch (AudioConfigurationDeclinedException declined) {", "return;",
                        "} catch (RuntimeException failure) {", "return;",
                        "if (wasOpen && !streamOpen.getAsBoolean()) { return; }", "onRestarted.run();",
                        "worker.start();");
        assertThat(flat(SourceScanSupport.stripComments(read(UI.resolve("MainController.java")))))
                .contains("static final String RESTART_AUDIO_ENGINE_THREAD = \"daw-audio-engine-restart\";");
        assertThat(body(main, "public Thread restartAudioEngine()"))
                .as("the engine's stream answers whether a stream is open; on success, the window's engine fault"
                        + " clears on the FX thread")
                .contains("return restartAudioEngine(settingsModel, audioEngineController, () -> {"
                        + " AudioEngine engine = audioEngine; return engine != null && engine.isStreamOpen(); },"
                        + " () -> FxDispatcher.runOnFx(fxDispatcher, () -> audioHealthDrain()"
                        + ".ifPresent(AudioHealthDrain::clearEngineFault)));");
        assertThat(occurrences(main, "applyConfiguration(")).as("startup's apply and the restart's")
                .isEqualTo(2);
        assertThat(main).as("startup and the restart build the request in one place")
                .contains("controller.applyConfiguration(audioConfigurationRequest(settings, backend));");
    }

    @Test
    void theSevenPluginDisposalsDeclareThePluginToTheFunnel() throws IOException {
        String rack = flat(SourceScanSupport.stripComments(read(UI.resolve("InsertEffectRack.java"))));
        for (String thread : List.of("cancelled-plugin-dispose", "refused-plugin-dispose", "failed-plugin-dispose")) {
            assertThat(rack).as("the rack's %s thread", thread)
                    .contains("Thread.ofVirtual().name(\"" + thread + "\") .start(" + GUARDED_DISPOSAL + ");");
        }
        assertThat(occurrences(rack, GUARDED_DISPOSAL)).isEqualTo(3);
        assertThat(occurrences(rack, "slot::disposeAfterQuiescence")).as("no bare disposal is left").isEqualTo(3);

        String menu = flat(code(UI.resolve("PluginViewController.java")));
        assertThat(occurrences(menu, "activationWorker.execute( " + GUARDED_DISPOSAL + ");"))
                .as("the plugin menu's four discards").isEqualTo(4);
        assertThat(occurrences(menu, "slot::disposeAfterQuiescence")).as("no bare disposal is left").isEqualTo(4);
    }

    @Test
    void theSpinesOwnCodeOpensNoWindowTakesNoFocusAndPostsOnlyThroughTheDispatcher() throws IOException {
        Pattern forbidden = Pattern.compile("\\bPlatform\\s*\\.|\\brunOnFx\\s*\\(|\\bisFxApplicationThread\\b"
                + "|\\bStage\\b|\\bDialog\\b|\\bAlert\\b|\\bshowAndWait\\b|\\brequestFocus\\b|\\btoFront\\b"
                + "|\\baddShutdownHook\\s*\\(");
        List<Path> scanned = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (Path folder : List.of(APP.resolve("diagnostics"), APP.resolve("logging"))) {
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.filter(each -> each.toString().endsWith(".java")).sorted().toList()) {
                    scanned.add(file);
                    Matcher found = forbidden.matcher(code(file));
                    while (found.find()) {
                        offenders.add(file.getFileName() + ": " + found.group());
                    }
                }
            }
        }

        assertThat(scanned).as("non-vacuity: the funnel and the log manager were read")
                .extracting(path -> path.getFileName().toString())
                .contains("FailureFunnel.java", "ExitHoldingLogManager.java", "LogFolderOpener.java");
        String funnel = flat(code(APP.resolve("diagnostics/FailureFunnel.java")));
        assertThat(body(funnel, "private void postOnFx(Target target, Handling handling, Thread reportOn,"
                + " Runnable body)"))
                .as("postOnFx's own post: every toast body runs as un-keyed dispatcher work on the FX thread")
                .startsWith("{ target.dispatcher().onFx(() -> {");
        assertThat(occurrences(funnel, ".bar().show("))
                .as("non-vacuity: the funnel shows its toasts").isEqualTo(3)
                .as("and every one is a body handed to postOnFx")
                .isEqualTo(count(Pattern.compile("postOnFx\\([^;]*?\\(\\) -> target\\.bar\\(\\)\\.show\\("), funnel));
        assertThat(offenders).isEmpty();
    }

    /**
     * No log line holds a line break its writer did not add (story 336,
     * studio-engineer ruling R992-2): the three writers of {@code daw-*.log},
     * the console and the last-resort record end every line through
     * {@code GuardedThrowables.endLine}, the one place that appends a line
     * separator. Read with comments stripped and string literals kept, so an
     * appended {@code "\n"} is found too.
     */
    @Test
    void everyLineTheLogWritersEndPassesThroughEndLine() throws IOException {
        Pattern separatorAppend = Pattern.compile("\\.append\\(\\s*(?:lineSeparator|nl"
                + "|System\\s*\\.\\s*lineSeparator\\(\\s*\\)|'\\\\[nr]'|\"\\\\[nr]\")\\s*\\)");
        Map<String, Integer> separatorAppends = new LinkedHashMap<>();
        Map<String, Integer> endLines = new LinkedHashMap<>();
        for (String writer : List.of("GuardedThrowables.java", "LastResortLog.java", "LogLineFormatter.java")) {
            String source = SourceScanSupport.stripComments(read(APP.resolve("diagnostics").resolve(writer)));
            separatorAppends.put(writer, count(separatorAppend, source));
            endLines.put(writer, occurrences(source, "endLine("));
        }

        assertThat(separatorAppends).as("a line separator is appended once, in endLine, and nowhere else")
                .containsExactly(entry("GuardedThrowables.java", 1), entry("LastResortLog.java", 0),
                        entry("LogLineFormatter.java", 0));
        String guarded = flat(SourceScanSupport.stripComments(read(APP.resolve("diagnostics/GuardedThrowables.java"))));
        assertThat(body(guarded, "static void endLine(StringBuilder out, int lineStart, String lineSeparator)"))
                .as("the one append is endLine's own").contains(".append(lineSeparator)");
        assertThat(endLines).as("non-vacuity: the lines each writer ends through endLine")
                .hasEntrySatisfying("GuardedThrowables.java", calls -> assertThat(calls).isGreaterThanOrEqualTo(6))
                .hasEntrySatisfying("LastResortLog.java", calls -> assertThat(calls).isGreaterThanOrEqualTo(3))
                .hasEntrySatisfying("LogLineFormatter.java", calls -> assertThat(calls).isGreaterThanOrEqualTo(3));
    }

    /**
     * Story 337, ruling E91: the undeclared rethrow is written twice in the
     * application — once for the diagnostics package, in
     * {@code GuardedThrowables}, which the audio health drain and
     * {@code DeclaredSubject} share, and once in {@code FxDispatcher}, whose
     * marshalling seam imports nothing from diagnostics (ruling E32). Read
     * with comments stripped.
     */
    @Test
    void theUndeclaredRethrowIsWrittenOnceForDiagnosticsAndOnceForTheMarshallingSeam() throws IOException {
        Path sources = SourceScanSupport.locateDawAppModule().resolve("src/main/java");
        Map<String, Integer> rethrows = new LinkedHashMap<>();
        List<String> scanned = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                scanned.add(file.getFileName().toString());
                int found = occurrences(SourceScanSupport.stripComments(
                        Files.readString(file, StandardCharsets.UTF_8)), "throw (T) ");
                if (found > 0) {
                    rethrows.put(file.getFileName().toString(), found);
                }
            }
        }

        assertThat(scanned).as("non-vacuity: the application's sources were read")
                .contains("AudioHealthDrain.java", "DeclaredSubject.java", "GuardedThrowables.java",
                        "FxDispatcher.java");
        assertThat(rethrows).as("by path: diagnostics, then ui/marshal")
                .containsExactly(entry("GuardedThrowables.java", 1), entry("FxDispatcher.java", 1));
    }

    private static String read(Path file) throws IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(source.length()).as("non-vacuity: %s was read", file.getFileName()).isGreaterThan(500);
        return source;
    }

    /** {@code file}'s code: comments stripped, string literals blanked to {@code ""}. */
    private static String code(Path file) throws IOException {
        return SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(read(file)));
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

    private static int count(Pattern pattern, String code) {
        Matcher matcher = pattern.matcher(code);
        int count = 0;
        while (matcher.find()) {
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
