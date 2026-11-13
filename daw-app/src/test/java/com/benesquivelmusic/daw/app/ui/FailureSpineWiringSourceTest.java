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
 * before the toolkit starts; the FX thread's handler right after the
 * dispatcher, before the window is loaded; the toasts armed right after the
 * window's controller has its dispatcher, before the window shows and the
 * Welcome screen or first-run wizard opens. It pins Help ▸ Open Log Folder's
 * route, the seven plugin disposals story 334 left to this story, and that the
 * spine's own code opens no window and takes no focus (story 336, studio-engineer ruling E9) and posts to
 * the FX thread only through the dispatcher (javafx-application-design
 * "## 11. Threading"; book Appendix B).
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
    void theApplicationInstallsTheFxHandlerWithTheDispatcherAndArmsTheToastsBeforeTheWindowShows()
            throws IOException {
        String start = body(flat(code(APP.resolve("DawApplication.java"))),
                "public void start(Stage primaryStage)");

        assertThat(start).as("the FX thread's handler right after the dispatcher, before the window loads")
                .containsSubsequence("FxDispatcher.installDefault(fxDispatcher);",
                        "failureFunnel = DawLauncher.failureFunnel(); failureFunnel.installOnCurrentFxThread();",
                        "loader.load();");
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
        assertThat(attach).startsWith("{ this.failureFunnel = Objects.requireNonNull(funnel, \"\");")
                .contains("if (notificationBar == null || fxDispatcher == null) { LOG.warning(")
                .endsWith("funnel.armToasts(fxDispatcher, notificationBar); }");
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
