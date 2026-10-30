package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
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
