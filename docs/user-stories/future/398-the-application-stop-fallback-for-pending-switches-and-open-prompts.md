---
title: "The Application-Stop Fallback for Pending Switches and Open Prompts"
labels: ["bug", "persistence", "reliability", "crash-recovery"]
---

# The Application-Stop Fallback for Pending Switches and Open Prompts

## Motivation

Story 333's application-stop fallback runs when JavaFX ends without the exit protocol — another caller ending JavaFX, or its last window closing some other way — so that `Application.stop()` reaches `ExitCoordinator.applicationStopping()` with no protocol behind it (story 333, ruling R4). It asks nothing, shows nothing of its own and never writes `project.daw`. Its waits are nested event loops on the FX thread (`NestedWait`), and they share one deadline: `ProjectManager.EXIT_WRITE_WAIT`, 30 seconds from the stop (`stopInNestedLoops`). Story 333 leaves what follows to this story; its Resolution records it in two known-limit categories, prompts open at the stop and switches under way at the stop, with this story as their owner.

**Switches under way at the stop.** The fallback waits for no switch. While a Save As copies or a project is installed (`PendingSwitch.SaveAsCopying`, `PendingSwitch.Installing`), the switch owns the open folder's journal and lock, so the fallback runs no closer: it logs `<switch> was still replacing “<name>” when the application stopped; the closing checkpoint, the seal and the release are skipped, as after a crash.` and skips those three steps (`closeAtTheStop`). Under any other switch — among them an archive extraction, a session recovery and a first save's folder creation (`PendingSwitch.Extracting`, `Recovering`, `CreatingFolder`) — it closes the open project as usual. Either way the end of the process cuts the switch, which may leave a folder partly written or still locked. An exit waits for the same switches: its GUARD holds while one is pending (`rejudge`, the switch wait), under the closing surface, and then goes on with whatever project is open.

**A close's own Save As.** The Save As a close runs at its FLUSH — the takeover dialog's `Save As…` after another session took the lock (`Prefill.TAKEOVER`), or Save and close of a project open read-only (`Prefill.COPY`) — runs under the close's own pending switch and registers none (`SaveAsFlow.publishCopyBeforeClosing`). So the fallback neither waits for its copy nor stands down for it. It closes the original by the decision's row — a lost lock's or a read-only open's, with no closing checkpoint — while the copy may still finish, and keep the changes, or be cut by the end of the process and leave its staging folder. Its log says that the changes were not written into the project's folder and that the Save As keeps them only if its copy finished before the application ended. Waiting for such a copy is the same work as waiting for any switch, so it belongs here.

**Prompts open at the stop.** A prompt or file chooser that a close had open when another caller ended JavaFX stays open until the application ends, and its answer decides nothing: the fallback fences the close it takes over (`takeOver`), closes only that close's progress surface, and closes the project its own way, without saving. Inside the fallback's waits a new modal is a hazard of its own. A modal opened inside a nested event loop holds that loop past its deadline, because the toolkit leaves nested loops innermost first (`NestedWait`'s class note). Story 333 opens neither the takeover dialog nor the Welcome window once the fallback runs. But a Save As chosen before the stop whose target is refused while the fallback waits asks again — its warning, then its chooser (`SaveAsFlow.targetRefused`) — and the application cannot end until that is answered; answered after the 30-second bound, the rest of the close is skipped, as after a crash.

No production caller reaches the fallback today. The only `Platform.exit` in main code is the one the exit protocol runs after HIDE (`ExitCoordinator`'s `terminalExit`, which `MainController` passes as `Platform::exit`), and after a protocol exit `stop()` only waits, bounded, for the end of DISPOSE's hand-off (`awaitTheHandOff`). The fallback is reached by a future `Platform.exit` caller, a primary stage hidden outside the protocol, or an OS session end, if the toolkit passes one through JavaFX, which is unverified (the studio engineer's stage-8 review, “Reach”). It is the safety net for those, and a safety net that cuts a switch, or that cannot leave a wait because of a modal, is weaker than it needs to be.

## Goals

- **A switch under way is waited for, bounded.** The fallback waits for a pending switch on its one deadline, as an exit's GUARD waits for one, and then closes the project that is open when the switch ends. A switch still under way at the deadline is waited for no longer: the end of the process cuts it, and while a Save As copies or a project is installed no closer runs, as today. The Resolution states, per switch, what the fallback closes after the wait, what it still does once the wait has spent the deadline, and what its log says.
- **A close's own Save As is joined.** A close's own Save As whose copy is being planned or made when the application stops is waited for, on the same deadline; the fallback logs its outcome — copied, failed, cancelled or still running at the deadline — in place of today's line that the Save As keeps the changes only if its copy finished.
- **Prompts and choosers are fenced or closed.** A prompt or chooser a close had open at the stop is closed, or stays fenced as today; the Resolution says which for each, and, for a native file chooser, which cannot be closed from Java, what the user sees.
- **No modal opens inside `Application.stop()`'s waits.** A Save As chosen before the stop whose target is refused while the fallback waits shows neither its warning nor its chooser again; the Resolution states how that Save As ends and what the log says. No other path may open a modal inside a wait either.
- **The categories close.** Story 333's two known-limit categories that name this story — prompts open at the stop, and switches under way at the stop — are marked closed by it, or reworded to what remains.

## Acceptance Criteria

1. A Save As copy, an installation, an archive extraction, a session recovery and a first save's folder creation, each under way when the application stops and finishing within the deadline, are waited for; the fallback then closes the project open at that moment, by its row, and DISPOSE follows.
2. The same switches still under way at the deadline are waited for no longer, and DISPOSE follows; while a Save As copies or a project is installed, no closer runs, as today.
3. A close's own Save As whose copy is under way at the stop is waited for within the deadline, and the fallback logs its outcome.
4. A refused Save As target while the fallback waits opens no dialog and no chooser, and `stop()` returns within its bounds with no answer given.
5. A prompt or chooser open at the stop is closed or fenced as the Resolution states, and `stop()` returns with no answer given.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Switch waited for**: hold a Save As copy (`SaveAsFlowTest`'s hold), stop the application (`ExitFixture.stopTheApplication()`), release the copy inside the deadline. The fallback waits, then runs its close on the project open then; `stopped` completes. One case per switch kind of criterion 1.
- **Switch past the deadline**: the same with the copy held past the deadline: the steps are `CLOSING_CHECKPOINT SKIPPED`, `SEAL SKIPPED`, `RELEASE SKIPPED`, `DISPOSE DONE`, with no closer, as `ApplicationStopFallbackTest.aSaveAsCopyingWhenTheApplicationStopsGetsNoCloserFromTheFallback` asserts today; that test changes with this story, and a resolver who sees it go red is seeing this story's change, not a regression.
- **A close's own Save As joined**: a takeover Save As whose copy is held when the application stops, released inside the deadline: the fallback logs the copy's outcome, `project.daw` is byte-identical and no closing checkpoint is written. Story 333's `ApplicationStopFallbackTest.aTakeoverSaveAsStillCopyingAtTheStopIsNotWaitedForAndItsLogSaysSo` pins today's no-wait behaviour and changes with this story.
- **No modal in a wait**: a close's own Save As planning at the stop whose target the plan refuses: no warning and no chooser is shown, and `stopped` completes with no answer given.
- **Prompt open at the stop**: an unsaved-changes prompt open when the application stops: the Resolution's behaviour, and `stopped` completes with no answer given.
- **Fault checks**, each run once, each turning a test of this story red: the switch wait removed; the join of a close's own Save As removed; the refused target's re-ask restored inside a wait.

## Non-Goals

- What the exit protocol does on a normal exit; this story changes only the fallback.
- The 15-second bound on the audio engine's stop (`ExitCoordinator.ENGINE_STOP_AFTER_HIDE_BOUND`), which stands.
- An end of the process that does not pass through JavaFX — Task Manager's End task, a kill, or an OS session end that ends the process directly — which runs neither the protocol nor the fallback (story 331's shutdown hook).

## Technical Notes

- Code locators: `ExitCoordinator` — `applicationStopping`, `stopInNestedLoops`, `closeAtTheStop`, `joinTheDecidedClose`, `closeAsTheFallback`, `planAtTheStop`, `accountForTheUnsavedChanges`, `takeOver`, `retireTheTakenOver`, `NestedWait`, `rejudge` (an exit's switch wait), `saveAsAtFlush`, `savedAs`, `EXIT_WRITE_WAIT_SECONDS`; `ProjectLifecycleController.PendingSwitch` and `closeReadiness()`; `SaveAsFlow` — `publishCopyBeforeClosing`, `targetRefused`, `Purpose.PUBLISH_BEFORE_CLOSING`; `ProjectManager.EXIT_WRITE_WAIT`; `ApplicationStopFallbackTest`, `ExitFixture.stopTheApplication()`, `SaveAsFlowTest`.
- A close's own Save As whose chooser is open at the stop cannot be joined: `stop()` then runs inside the chooser's nested event loop, and the chooser can return only after `stop()` has, so a wait for it would only spend the deadline (the studio engineer's final ruling F2c). Its case is the third Goal's, a chooser open at the stop.
- The fallback's waits all spend one deadline (`ApplicationStopFallbackTest.everyWaitSharesTheOneDeadline`); a switch wait spends from it too, so the fallback's close still ends within 30 seconds of the stop, and DISPOSE then has the engine's own bound.
- Ruled by the studio engineer in story 333: its stage-8 review's known-limit categories 4 and 5, and its ruling on the stage-8 report's question about a pending switch at the stop (the fallback does not wait for a switch; a bounded wait, as an exit's GUARD waits, is this story); then its final rulings F2c (the join of a close's own Save As, declined in story 333 and given to this story) and NEW-2 (a refused target's re-ask inside `stop()`'s waits), which amend categories 4 and 5. This story's scope, as ruled: a bounded wait, on the one deadline, for a switch under way, as an exit's GUARD waits; a close's own Save As joined, with its outcome logged; prompts and choosers fenced or closed; no modal opened inside `Application.stop()`'s waits.
- Design references: Persistence Integrity design book §2.7 (exit is a protocol, not an event), §5.4 (the exit protocol, ordered).
- Cross-refs: story 333 (the fallback, ruling R4; its categories for prompts open at the stop and switches under way at the stop), story 331 (the shutdown hook, its M1 limit).

## Status

- Deferred
