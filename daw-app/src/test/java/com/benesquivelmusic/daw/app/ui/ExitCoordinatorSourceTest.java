package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333 — the close protocol's source pins (design §1.2, §1.3, §1.5;
 * javafx-application-design skill §11 "Threading", §12 "Event handling and
 * public events"): the primary stage's close request is taken by an event
 * filter that consumes it first, never by {@code setOnCloseRequest}; every
 * hop of the coordinator and the fence is an unkeyed post — a keyed post
 * runs inside the pulse, where a prompt's nested loop throws; no new class
 * of the protocol waits ({@code join}, {@code get()}, {@code sleep}) or
 * styles inline; and no {@code Platform.runLater} outside the dispatcher.
 * The stage wiring's own pins (DawApplication, MainController) are stage 7's
 * {@code ExitWiringSourceTest}.
 */
class ExitCoordinatorSourceTest {

    private static final List<String> NEW_CLASSES = List.of("ExitCoordinator.java", "WindowInputFence.java",
            "ClosingProgress.java", "ClosingProgressDialog.java", "ClosePromptAnswers.java");

    @Test
    void theCloseRequestIsAConsumingFilterAndEveryHopIsAnUnkeyedPost() throws IOException {
        String coordinator = code("ExitCoordinator.java");
        assertThat(coordinator).containsPattern(Pattern.compile(
                "addEventFilter\\(WindowEvent\\.WINDOW_CLOSE_REQUEST,\\s*event\\s*->\\s*\\{\\s*event\\.consume\\(\\);"));
        assertThat(coordinator).doesNotContain("setOnCloseRequest(").doesNotContain("setOnHidden(");
        assertThat(coordinator).as("every post is FxDispatcher.runOnFx(dispatcher, …): unkeyed, and a run's own is"
                        + " guarded (ruling 4 Q3)")
                .contains("FxDispatcher.runOnFx(deps.dispatcher(), () -> guarded(run, work));")
                .doesNotContainPattern(Pattern.compile("\\.onFx\\([^()]*,"));
    }

    /**
     * Ruling 4 Q3: every entry into a run is guarded — its timers too, so a
     * step that throws on a timer's turn ends the run instead of stranding
     * it; and the request's synchronous part.
     */
    @Test
    void everyTimerTaskOfARunIsGuarded() throws IOException {
        String coordinator = code("ExitCoordinator.java").replaceAll("\\s+", " ");
        long timers = Pattern.compile("deps\\.timers\\(\\)\\.after\\(").matcher(coordinator).results().count();
        long guardedTimers = Pattern.compile("deps\\.timers\\(\\)\\.after\\(\\w+, \\(\\) -> guarded\\(run, ")
                .matcher(coordinator).results().count();
        // Story 333's stage 8: the application stop's one-shot wait has a deadline timer of its own, which is not
        // an entry into a run — it only leaves its nested loop, once (pinned below).
        long waitDeadlines = Pattern.compile(Pattern.quote(
                "deps.timers().after(Duration.ofNanos(remaining), () -> leave(false));")).matcher(coordinator)
                .results().count();
        assertThat(guardedTimers).as("non-vacuity: the clock, the detach and the hide timers").isEqualTo(4);
        assertThat(waitDeadlines).as("the nested wait's deadline").isEqualTo(1);
        assertThat(timers).as("every other timer task is a run's, guarded").isEqualTo(guardedTimers + waitDeadlines);
        assertThat(coordinator).contains("guarded(run, () -> { publish(run, ExitStep.INTERCEPT, StepResult.DONE,"
                + " door.name()); post(run, () -> guard(run)); });");
    }

    /**
     * The application stop's waits (story 333, design §1.7; the feasibility
     * critique's S8): every nested loop is entered and left in one place, the
     * one-shot {@code NestedWait} — its condition checked before the loop, its
     * completion attached asynchronously through the dispatcher, and the loop
     * left only through one compare-and-set; the defensive branch's waits are
     * bounded and run on the non-daemon {@code daw-exit-fallback} thread,
     * never in the coordinator's file.
     */
    @Test
    void theApplicationStopsWaitsAreOneShotNestedLoopsOrBoundedWaitsOffTheFxThread() throws IOException {
        String coordinator = code("ExitCoordinator.java").replaceAll("\\s+", " ");
        assertThat(count(coordinator, "deps.nestedLoops().enter(")).as("one place enters a nested loop").isEqualTo(1);
        assertThat(count(coordinator, "deps.nestedLoops().exit(")).as("one place leaves it").isEqualTo(1);
        String until = coordinator.substring(coordinator.indexOf("Waited until(CompletionStage<?> completion, long"
                + " deadlineNanos) {"));
        assertThat(until.indexOf("if (completion.toCompletableFuture().isDone()) { return Waited.ENDED; }"))
                .as("the condition is checked first").isNotNegative()
                .isLessThan(until.indexOf("deps.nestedLoops().enter(this)"));
        assertThat(until).contains("completion.whenCompleteAsync((_, _) -> leave(true), task ->"
                + " FxDispatcher.runOnFx(deps.dispatcher(), task));");
        assertThat(coordinator).contains("private void leave(boolean ended) { if (done.compareAndSet(false, true))"
                + " { deps.nestedLoops().exit(this, ended); } }");
        assertThat(count(coordinator, "Thread.ofPlatform().name(EXIT_FALLBACK_THREAD).daemon(false).start("))
                .as("the defensive branch's own non-daemon thread").isEqualTo(1);
        // The stage-8 review N6 and S8: every bounded wait lies inside that thread's body — the drain, every writer
        // and the release — and the drain itself runs on a virtual thread of its own, waited for within the bound.
        String fallbacksThread = lambdaBody(coordinator,
                "Thread.ofPlatform().name(EXIT_FALLBACK_THREAD).daemon(false).start(() -> {");
        assertThat(count(coordinator, "BoundedWait.until(")).as("its three bounded waits").isEqualTo(3);
        assertThat(count(fallbacksThread, "BoundedWait.until(")).as("all inside the fallback's thread").isEqualTo(3);
        assertThat(fallbacksThread).contains("Thread.ofVirtual().name(ProjectFolderCloser.DRAIN_WORKER).start(() -> {"
                + " try { closer.drainTheOpenFolderUnsealed(); drained.complete(null); }")
                .contains("if (!BoundedWait.until(drained, deadline, clock)) {");
        assertThat(fallbacksThread.indexOf("closer.drainTheOpenFolderUnsealed();"))
                .as("the drain is not on the thread the JVM waits for")
                .isGreaterThan(fallbacksThread.indexOf("Thread.ofVirtual()"));
        String bounded = code("BoundedWait.java").replaceAll("\\s+", " ");
        assertThat(bounded).as("a wait with a timeout, the only one")
                .contains("stage.toCompletableFuture().get(remaining, TimeUnit.NANOSECONDS);")
                .doesNotContain(".join(").doesNotContain(".get()").doesNotContain("Platform");
        try (java.util.stream.Stream<java.nio.file.Path> sources = Files.walk(SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java"))) {
            List<String> callers = sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            return SourceScanSupport.stripComments(Files.readString(path, StandardCharsets.UTF_8))
                                    .contains("BoundedWait.until(");
                        } catch (IOException unreadable) {
                            throw new java.io.UncheckedIOException(unreadable);
                        }
                    })
                    .map(path -> path.getFileName().toString()).toList();
            assertThat(callers).as("no other main file waits through it").containsExactly("ExitCoordinator.java");
        }
    }

    /**
     * Stage-6 S1's rule, as the studio engineer's ruling on app fix pass
     * 2a, item 3, and the app-fix-2 review S3 require it (Java N11): GUARD
     * makes no lock read. The protocol reads the lock in two places only —
     * FLUSH's {@code LOCK_CHECK}, under the clock, and the application-stop
     * fallback's row (story 333's stage 8, after the ruling was written) —
     * and makes no other lock I/O; the lifecycle controller's facts GUARD
     * reads make none either, the lease's in-memory status aside.
     */
    @Test
    void guardMakesNoLockReadAndTheProtocolReadsTheLockOnlyAtFlushAndInTheFallbacksRow() throws IOException {
        String coordinator = code("ExitCoordinator.java").replaceAll("\\s+", " ");
        assertThat(Pattern.compile("\\bwhenLockChecked\\(").matcher(coordinator).results().count())
                .as("non-vacuity, and no third read").isEqualTo(2);
        assertThat(Pattern.compile("armClock\\(run\\); setWait\\(run, WaitKind\\.LOCK_CHECK, [^)]*\\);"
                + " deps\\.projectManager\\(\\)\\.whenLockChecked\\(\\)").matcher(coordinator).results().count())
                .as("FLUSH's read, under the clock").isEqualTo(1);
        assertThat(coordinator).as("the fallback's read, in its row")
                .containsOnlyOnce("CompletableFuture<Optional<LockStolenException>> checked ="
                        + " deps.projectManager().whenLockChecked();");
        assertThat(methodBodyOf(coordinator, "private void decideAfterTheWaits(CloseRun run)"))
                .doesNotContain("whenLockChecked");
        assertThat(methodBodyOf(coordinator, "private void guard(CloseRun run)")).doesNotContain("whenLockChecked");
        Pattern lockIo = Pattern.compile(
                "\\b(verifyOurs|verifyHeld|withHeldLease|heartbeat|peekLock|tryAcquire|forceAcquire)\\(");
        assertThat(coordinator).as("no other lock I/O").doesNotContainPattern(lockIo);
        String lifecycle = FxHalfOfASaveMakesNoFileSystemCallTest.code(
                FxHalfOfASaveMakesNoFileSystemCallTest.read(FxHalfOfASaveMakesNoFileSystemCallTest.LIFECYCLE));
        for (String fact : List.of("closeReadiness", "pendingSwitch")) {
            String body = FxHalfOfASaveMakesNoFileSystemCallTest.body("ProjectLifecycleController.java", lifecycle,
                    fact).code().replaceAll("\\s+", " ");
            assertThat(body).as(fact).doesNotContain("whenLockChecked(").doesNotContainPattern(lockIo);
        }
        assertThat(FxHalfOfASaveMakesNoFileSystemCallTest.body("ProjectLifecycleController.java", lifecycle,
                "closeReadiness").code()).as("non-vacuity: the in-memory status it may read")
                .contains("getLockManager().status()");
    }

    /**
     * The app-fix-2 review N2: a step that throws ends its run whatever the
     * text of its ERROR does — the text is built inside the cleanup that
     * shows it, and {@code end(...)} follows. Unreachable while the RULED rows
     * pin the keys, so a source pin.
     */
    @Test
    void theFailedRunsErrorTextIsBuiltInsideTheCleanupThatShowsIt() throws IOException {
        String stepFailed = methodBodyOf(code("ExitCoordinator.java").replaceAll("\\s+", " "),
                "private void stepFailed(CloseRun run, Throwable failed)");
        assertThat(stepFailed).contains("quietly(() -> deps.notifications().show(NotificationLevel.ERROR,"
                + " ProjectCloseText.text(errorKey, doorName(run), run.projectName, reason)));"
                + " end(run, new CloseOutcome.Failed(failed));")
                .doesNotContain("String message = ProjectCloseText.text(");
    }

    /**
     * The stage-8 review N4: {@code mayAsk()} is declarative — the application
     * stop is sent to the fallback before any run is made, so it never
     * reaches GUARD, which is the flag's one reader.
     */
    @Test
    void theApplicationStopIsSentToTheFallbackBeforeAnyRunIsMade() throws IOException {
        String coordinator = code("ExitCoordinator.java").replaceAll("\\s+", " ");
        String request = methodBodyOf(coordinator, "CompletionStage<CloseOutcome> request(CloseDoor door)");
        assertThat(request.indexOf("if (door instanceof CloseDoor.ApplicationStop) { applicationStopping();"))
                .isNotNegative().isLessThan(request.indexOf("new CloseRun(door)"));
        assertThat(count(coordinator, ".mayAsk()")).as("GUARD is its one reader").isEqualTo(1);
        assertThat(methodBodyOf(coordinator, "private void guard(CloseRun run)")).contains("if (run.door.mayAsk())");
    }

    /**
     * A source pin: a decided Discard with no closer yet when the application stops needs its lock read held,
     * which no seam offers; these two terms keep its row, so its next open stays plain (ruling R3; the studio
     * engineer's final ruling F3).
     */
    @Test
    void theStopsPlanKeepsADecidedDiscardsRow() throws IOException {
        String plan = methodBodyOf(code("ExitCoordinator.java").replaceAll("\\s+", " "),
                "private Optional<StopPlan> planAtTheStop(");
        assertThat(plan)
                .contains("boolean fallbacksOwn = decision == null || (decision != Decision.DISCARD"
                        + " && readiness.dirty());")
                .contains(": decision == Decision.DISCARD ? ClosingCheckpoint.IF_RECORDS_OR_NOT_CHECKPOINTED");
    }

    /** The brace-balanced body of the method declared by {@code signature} in whitespace-collapsed code. */
    private static String methodBodyOf(String code, String signature) {
        int declaration = code.indexOf(signature);
        assertThat(declaration).as("the code declares %s", signature).isNotNegative();
        return balanced(code, code.indexOf('{', declaration));
    }

    /** The brace-balanced body of the lambda whose opening — ending in its {@code {} — is {@code opening}. */
    private static String lambdaBody(String code, String opening) {
        int start = code.indexOf(opening);
        assertThat(start).as("the code has %s", opening).isNotNegative();
        return balanced(code, start + opening.length() - 1);
    }

    private static String balanced(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces");
    }

    /**
     * The closer always answers (the studio engineer's ruling on app fix pass
     * 2a, item 5): every post inside {@code ProjectFolderCloser.Closing} runs
     * its FX turn through the guard that completes the close's result
     * exceptionally when the turn throws.
     */
    @Test
    void everyFxTurnOfTheFolderCloserIsGuarded() throws IOException {
        String closer = code("ProjectFolderCloser.java").replaceAll("\\s+", " ");
        String closing = closer.substring(closer.indexOf("final class Closing {"));
        long posts = Pattern.compile("\\bpost\\(").matcher(closing).results().count();
        long guardedPosts = Pattern.compile(Pattern.quote("post(() -> guarded(")).matcher(closing).results().count();
        assertThat(guardedPosts).as("non-vacuity: the generation's idle, the closing checkpoint, the journal end's"
                + " start and end, the writers' idle and the release").isEqualTo(6);
        assertThat(posts).as("every post of a close is guarded").isEqualTo(guardedPosts);
        assertThat(closing).contains("private void guarded(Runnable turn) { try { turn.run(); } catch"
                + " (RuntimeException | Error failed) { failed(failed); } }")
                .contains("result.completeExceptionally(thrown);");
    }

    private static long count(String code, String fragment) {
        return Pattern.compile(Pattern.quote(fragment)).matcher(code).results().count();
    }

    /**
     * Ruling 4 Q5 (ii): the take facts' recording in flight names the
     * count-in on its own, a deliberate superset of the lifecycle
     * controller's check — redundant in fact today, and kept so that ruling
     * R1 still holds if a take is ever allocated only at the downbeat. The
     * transport controller is final, so a source pin, beside the COUNT_IN
     * behaviour row of {@code TakeAtExitTest}.
     */
    @Test
    void theRecordingInFlightOfTheTakeFactsNamesTheCountInOnItsOwn() throws IOException {
        String coordinator = code("ExitCoordinator.java").replaceAll("\\s+", " ");
        assertThat(coordinator).contains("return current != null && (current.isRecordingInFlight()"
                + " || current.isCountingIn());");
    }

    /**
     * The studio engineer's ruling R-3.3 (PR #989 review 5711460812): while
     * it is up, the fence also covers every window shown, so a window the
     * close itself shows takes input only through the close-surface mark —
     * one it misses can be answered only by its close box, an unanswerable
     * close. The six builders of the close's windows mark them; and, as a
     * category ({@link CloseSurfaceRule}), every dialog class the protocol's
     * files declare marks in each of its constructors, and every window built
     * there — through {@code new}, a static call on a type whose name ends in
     * {@code Dialog} or {@code Alert}, such as a {@code DawgDialog} factory,
     * or a {@code ::new} reference — is built in a member that marks or is of
     * a dialog class whose constructors mark.
     */
    @Test
    void everyWindowTheCloseShowsCarriesTheCloseSurfaceMark() throws IOException {
        record Builder(String file, String signature) { }
        String mark = "WindowInputFence.markCloseSurface(";
        for (Builder builder : List.of(
                new Builder("ProjectClosePrompts.java", "public StopTakeAnswer confirmStopTake(String header)"),
                new Builder("ProjectClosePrompts.java", "public TakenOverAnswer takenOver(TakenOverPrompt prompt)"),
                new Builder("ProjectClosePrompts.java", "public void refuseSaveAsTarget(String message)"),
                new Builder("ProjectLifecycleController.java",
                        "UnsavedChangesAnswer askAboutUnsavedChanges(UnsavedChangesPrompt prompt)"),
                new Builder("ClosingProgressDialog.java", "ClosingProgressDialog(Optional<Window> owner)"),
                new Builder("SaveAsProgressDialog.java", "SaveAsProgressDialog(Optional<Window> owner)"))) {
            assertThat(methodBodyOf(code(builder.file()).replaceAll("\\s+", " "), builder.signature()))
                    .as("%s, %s: a window the close shows must carry the close-surface mark (R-3.3)",
                            builder.file(), builder.signature())
                    .contains(mark);
        }

        java.util.Map<String, String> protocolFiles = new java.util.LinkedHashMap<>();
        for (String file : List.of("ExitCoordinator.java", "ProjectClosePrompts.java", "SaveAsFlow.java",
                "ClosingProgressDialog.java", "SaveAsProgressDialog.java")) {
            protocolFiles.put(file, code(file));
        }
        CloseSurfaceRule rule = CloseSurfaceRule.over(protocolFiles);
        assertThat(rule.unmarked()).as("a window the close shows must carry the close-surface mark (R-3.3)")
                .isEmpty();
        assertThat(rule.markingDialogClasses()).as("non-vacuity: the two progress dialogs are found, and mark")
                .containsExactlyInAnyOrder("ClosingProgressDialog", "SaveAsProgressDialog");
        String unsavedPrompt = methodBodyOf(code("ProjectLifecycleController.java").replaceAll("\\s+", " "),
                "UnsavedChangesAnswer askAboutUnsavedChanges(UnsavedChangesPrompt prompt)");
        Matcher built = CloseSurfaceRule.WINDOW.matcher(unsavedPrompt);
        int constructions = rule.windowsBuilt() + rule.constructorsChecked();
        while (built.find()) {
            constructions++;
            assertThat(unsavedPrompt)
                    .as("ProjectLifecycleController.java, %s in askAboutUnsavedChanges: a window the close shows"
                            + " must carry the close-surface mark (R-3.3)", built.group())
                    .contains("markCloseSurface(");
        }
        assertThat(constructions).as("non-vacuity: the close's Alerts, its Save As refusal, the Save As surface's"
                + " reference and the two progress dialogs' constructors are found").isGreaterThanOrEqualTo(7);
    }

    /**
     * The category rule sees an unmarked window of each shape it names — a
     * {@code new} of a type whose name ends in {@code Dialog}, {@code Alert}
     * or {@code Stage} ({@code new Alert(}, {@code new TextInputDialog(},
     * {@code new ChoiceDialog<>(}, {@code new Stage(},
     * {@code new DawgDialog<>(}, {@code new Dialog<>(}, {@code new} of a
     * dialog class), a static call on a type whose name ends in
     * {@code Dialog} or {@code Alert} (a {@code DawgDialog} factory, another
     * dialog class's own helper) and a {@code ::new} reference to one of the
     * first shape's types — and a dialog class, generic or not, with a
     * constructor that does not mark, or with none; and passes each once it
     * marks, a dialog class once it declares constructors that each mark.
     * That is its bound, as a category. A dialog that another class's own
     * helper builds is outside the rule unless that helper's class is in the
     * scanned set and marks: the rule reports a static call of the helper on
     * a type so named, and passes it once that type is a dialog class of the
     * scanned set that marks, or once the calling member marks, which cannot
     * mark the dialog the helper builds; a helper of another kind it does not
     * see at all. A popup ({@code ContextMenu}, {@code Tooltip},
     * {@code Popup}), which no shape names, is outside the rule: the fence
     * exempts it only through the mark or, as every popup today, through an
     * owner chain that reaches a marked surface ({@code WindowInputFence}'s
     * owner rule).
     */
    @Test
    void theCloseSurfaceRuleCatchesAnUnmarkedWindowOfEveryShape() {
        String unmarked = """
                final class Fixture {
                    void alert() { Alert alert = new Alert(Alert.AlertType.WARNING); }
                    void factory() { DawgDialog<ButtonType> refusal = DawgDialog.warn(title, message); }
                    void helper() { Optional<ButtonType> answer = SealFailedDialog.ask(owner); }
                    void textInput() { TextInputDialog name = new TextInputDialog(initial); }
                    void choice() { ChoiceDialog<String> choice = new ChoiceDialog<>(first, choices); }
                    void stage() { Stage stage = new Stage(); }
                    void dawgDialog() { DawgDialog<Void> dialog = new DawgDialog<>(); }
                    void dialog() { Dialog<ButtonType> dialog = new Dialog<>(); }
                    void subclass() { LateDialog late = new LateDialog(owner); }
                    private Function<Window, LateDialog> dialogs = LateDialog::new;
                }

                final class LateDialog extends DawgDialog<Void> {
                    LateDialog(Window owner) {
                        initOwner(owner);
                    }
                }

                final class BareDialog extends Dialog<Void> { }

                final class LateAnswer<T> extends DawgDialog<T> {
                    LateAnswer(Window owner) {
                        initOwner(owner);
                    }
                }
                """;
        CloseSurfaceRule caught = CloseSurfaceRule.over(java.util.Map.of("Fixture.java", fixture(unmarked)));
        assertThat(caught.unmarked()).as("one offender per shape").hasSize(13)
                .anySatisfy(offender -> assertThat(offender).endsWith("the constructor of LateDialog does not mark"))
                .anySatisfy(offender -> assertThat(offender)
                        .endsWith("the constructor of BareDialog does not mark (it has none)"))
                .anySatisfy(offender -> assertThat(offender).endsWith("the constructor of LateAnswer does not mark"));
        for (String shape : List.of("new Alert(", "DawgDialog.warn(", "SealFailedDialog.ask(", "new TextInputDialog(",
                "new ChoiceDialog<>(", "new Stage(", "new DawgDialog<>(", "new Dialog<>(", "new LateDialog(",
                "LateDialog::new")) {
            assertThat(caught.unmarked()).as("an unmarked %s is caught", shape)
                    .anySatisfy(offender -> assertThat(offender).endsWith(shape));
        }
        assertThat(caught.markingDialogClasses()).as("a dialog class whose constructor does not mark").isEmpty();

        String marked = """
                final class Fixture {
                    void alert() {
                        Alert alert = new Alert(Alert.AlertType.WARNING);
                        WindowInputFence.markCloseSurface(alert);
                    }
                    void factory() {
                        DawgDialog<ButtonType> refusal = DawgDialog.warn(title, message);
                        WindowInputFence.markCloseSurface(refusal);
                    }
                    void textInput() {
                        TextInputDialog name = new TextInputDialog(initial);
                        WindowInputFence.markCloseSurface(name);
                    }
                    void choice() {
                        ChoiceDialog<String> choice = new ChoiceDialog<>(first, choices);
                        WindowInputFence.markCloseSurface(choice);
                    }
                    void stage() {
                        Stage stage = new Stage();
                        WindowInputFence.markCloseSurface(stage);
                    }
                    void dawgDialog() {
                        DawgDialog<Void> dialog = new DawgDialog<>();
                        WindowInputFence.markCloseSurface(dialog);
                    }
                    void dialog() {
                        Dialog<ButtonType> dialog = new Dialog<>();
                        WindowInputFence.markCloseSurface(dialog);
                    }
                    void subclass() { MarkingDialog surface = new MarkingDialog(owner); }
                    void helper() { Optional<Void> answer = MarkingDialog.ask(owner); }
                    private Function<Window, MarkingDialog> dialogs = MarkingDialog::new;
                }

                final class MarkingDialog extends DawgDialog<Void> {
                    MarkingDialog(Window owner) {
                        WindowInputFence.markCloseSurface(this);
                    }

                    MarkingDialog() {
                        this(null);
                    }

                    static Optional<Void> ask(Window owner) {
                        return new MarkingDialog(owner).showAndWait();
                    }
                }

                final class MarkedAnswer<T> extends DawgDialog<T> {
                    MarkedAnswer(Window owner) {
                        WindowInputFence.markCloseSurface(this);
                    }
                }
                """;
        CloseSurfaceRule passed = CloseSurfaceRule.over(java.util.Map.of("Fixture.java", fixture(marked)));
        assertThat(passed.unmarked()).as("every shape, marked, passes").isEmpty();
        assertThat(passed.markingDialogClasses()).containsExactlyInAnyOrder("MarkedAnswer", "MarkingDialog");
        assertThat(passed.windowsBuilt()).as("and each is still seen, the helper's own window with them")
                .isEqualTo(11);
        assertThat(passed.constructorsChecked()).isEqualTo(3);
    }

    private static String fixture(String source) {
        return SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(source));
    }

    /**
     * Ruling R-3.3's category over a set of files (name to code, comments and
     * literals stripped). A dialog class is one a file declares as extending
     * a type whose name ends in {@code Dialog}, {@code Alert} or
     * {@code Stage}; it marks when each of its constructors calls
     * {@code markCloseSurface(} or delegates to another with {@code this(},
     * and it has at least one. A window is built by {@code new} of such a
     * type — {@code new Alert(}, {@code new Stage(}, {@code new DawgDialog<>(},
     * {@code new Dialog<>(}, a subclass's {@code new} — by a static call of a
     * lower-case method on a type whose name begins upper-case and ends in
     * {@code Dialog} or {@code Alert} — a {@code DawgDialog} factory, or a
     * dialog class's own helper — or by a {@code ::new} reference to a type
     * whose name ends in {@code Dialog}, {@code Alert} or {@code Stage}; it
     * is marked when the member that builds it calls
     * {@code markCloseSurface(}, or when its type is a dialog class that
     * marks.
     *
     * @param unmarked             each offender, as {@code <file>: <what>}
     * @param markingDialogClasses the dialog classes that mark
     * @param windowsBuilt         how many windows the files build
     * @param constructorsChecked  how many dialog-class constructors were checked
     */
    private record CloseSurfaceRule(List<String> unmarked, java.util.Set<String> markingDialogClasses,
                                    int windowsBuilt, int constructorsChecked) {

        static final Pattern WINDOW = Pattern.compile(
                "\\bnew\\s+(?:\\w+\\.)*(\\w*(?:Dialog|Alert|Stage))\\s*(?:<[^<>;{}()]*>)?\\s*\\("
                        + "|\\b([A-Z]\\w*(?:Dialog|Alert))\\s*\\.\\s*[a-z]\\w*\\s*\\("
                        + "|\\b(?:\\w+\\.)*(\\w*(?:Dialog|Alert|Stage))\\s*::\\s*new\\b");
        private static final Pattern DIALOG_CLASS = Pattern.compile(
                "\\bclass\\s+(\\w+)\\s*(?:<[^{]*?>)?\\s+extends\\s+(?:\\w+\\.)*\\w*(?:Dialog|Alert|Stage)\\b");

        CloseSurfaceRule {
            unmarked = List.copyOf(unmarked);
            markingDialogClasses = java.util.Set.copyOf(markingDialogClasses);
        }

        static CloseSurfaceRule over(java.util.Map<String, String> files) {
            List<String> unmarked = new java.util.ArrayList<>();
            java.util.Set<String> marking = new java.util.TreeSet<>();
            int constructors = 0;
            for (java.util.Map.Entry<String, String> file : files.entrySet()) {
                Matcher declared = DIALOG_CLASS.matcher(file.getValue());
                while (declared.find()) {
                    String name = declared.group(1);
                    List<String> bodies = constructorBodies(file.getValue(), name);
                    constructors += bodies.size();
                    boolean marks = !bodies.isEmpty();
                    for (String body : bodies) {
                        String statements = body.substring(1).strip();
                        if (!body.contains("markCloseSurface(") && !statements.startsWith("this(")) {
                            marks = false;
                            unmarked.add(file.getKey() + ": the constructor of " + name + " does not mark");
                        }
                    }
                    if (bodies.isEmpty()) {
                        unmarked.add(file.getKey() + ": the constructor of " + name + " does not mark (it has none)");
                    }
                    if (marks) {
                        marking.add(name);
                    }
                }
            }
            int windows = 0;
            for (java.util.Map.Entry<String, String> file : files.entrySet()) {
                Matcher built = WINDOW.matcher(file.getValue());
                while (built.find()) {
                    windows++;
                    String type = built.group(1) != null ? built.group(1)
                            : built.group(2) != null ? built.group(2) : built.group(3);
                    if (!marking.contains(type)
                            && !enclosingMember(file.getValue(), built.start()).contains("markCloseSurface(")) {
                        unmarked.add(file.getKey() + ": " + built.group().replaceAll("\\s+", " "));
                    }
                }
            }
            return new CloseSurfaceRule(unmarked, marking, windows, constructors);
        }

        /** The balanced bodies of {@code name}'s constructors declared in {@code code}. */
        private static List<String> constructorBodies(String code, String name) {
            List<String> bodies = new java.util.ArrayList<>();
            Matcher declared = Pattern.compile("(?m)^[ \\t]*(?:(?:public|protected|private)\\s+)?"
                    + Pattern.quote(name) + "\\s*\\(").matcher(code);
            while (declared.find()) {
                int close = matchingParenthesis(code, declared.end() - 1);
                Matcher head = Pattern.compile("\\s*(?:throws\\s+[\\w.,\\s]+?)?\\s*\\{").matcher(code)
                        .region(close + 1, code.length());
                if (head.lookingAt()) {
                    bodies.add(balanced(code, head.end() - 1));
                }
            }
            return bodies;
        }

        private static int matchingParenthesis(String code, int open) {
            int depth = 0;
            for (int i = open; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')' && --depth == 0) {
                    return i;
                }
            }
            throw new AssertionError("unbalanced parentheses");
        }
    }

    /**
     * The member of the class body — a declaration starting at its
     * indentation, four spaces — that holds {@code position} of {@code code}:
     * from its declaration to its body's closing brace, or, for a field, to
     * the semicolon that ends its initializer.
     */
    private static String enclosingMember(String code, int position) {
        Matcher declarations = Pattern.compile("(?m)^ {4}(?=[^\\s}@])").matcher(code);
        int start = -1;
        while (declarations.find() && declarations.start() < position) {
            start = declarations.start();
        }
        assertThat(start).as("a member is declared before position %d", position).isNotNegative();
        int end = memberEnd(code, start);
        assertThat(end).as("position %d lies in the member", position).isGreaterThan(position);
        return code.substring(start, end);
    }

    /**
     * Where the member declared at {@code start} ends: past its balanced body,
     * or, for a field — an {@code =} or a {@code ;} before any body, outside
     * parentheses — past the {@code ;} that ends it outside parentheses and
     * braces (an initializer may hold a lambda's block).
     */
    private static int memberEnd(String code, int start) {
        int parentheses = 0;
        for (int i = start; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                parentheses++;
            } else if (c == ')') {
                parentheses--;
            } else if (parentheses == 0 && c == '{') {
                return i + balanced(code, i).length();
            } else if (parentheses == 0 && (c == '=' || c == ';')) {
                int depth = 0;
                for (int j = i; j < code.length(); j++) {
                    char d = code.charAt(j);
                    if (d == '(' || d == '{') {
                        depth++;
                    } else if (d == ')' || d == '}') {
                        depth--;
                    } else if (d == ';' && depth == 0) {
                        return j + 1;
                    }
                }
                return code.length();
            }
        }
        return code.length();
    }

    @Test
    void theProtocolsClassesNeverWaitOnTheFxThreadNorStyleInlineNorPostAroundTheDispatcher() throws IOException {
        for (String file : NEW_CLASSES) {
            String code = code(file);
            assertThat(code).as(file).doesNotContain("setStyle(").doesNotContain("Platform.runLater")
                    .doesNotContain(".join()").doesNotContain("Thread.sleep").doesNotContain("TimeUnit")
                    .doesNotContainPattern(Pattern.compile("\\.get\\([^)]"))
                    .doesNotContain("Files.");
        }
    }

    private static String code(String file) throws IOException {
        String source = Files.readString(SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/" + file), StandardCharsets.UTF_8);
        assertThat(source.length()).as("non-vacuity: %s was read", file).isGreaterThan(500);
        return SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(source));
    }
}
