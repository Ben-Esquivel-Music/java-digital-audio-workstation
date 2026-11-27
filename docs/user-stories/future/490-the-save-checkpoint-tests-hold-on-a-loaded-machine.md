---
title: "The Save Checkpoint Tests Hold on a Loaded Machine"
labels: ["bug", "testing", "persistence"]
---

# The Save Checkpoint Tests Hold on a Loaded Machine

## Motivation

A save waits for its checkpoint, or for the safety copy of a save that failed, for a bounded time and then completes without it, saying that the copy is still being written (story 331, ruling J3; `ProjectManager.CHECKPOINT_WAIT`, 5 s, and the tests' 300 ms `SaveTestSupport.SHORT_CHECKPOINT_WAIT`). Two tests of that bounded wait each failed once in a full gate of story 338's second review round, on the project's Windows development machine while it ran slower than usual, and passed again when run on their own. Neither that round nor story 338's commits touch either test or the code it runs.

- `SaveWriterIdleTest.theIdleFutureCompletesWhenTheCheckpointsBoundedWaitRunsOut` (daw-core) holds the checkpoint at its move, saves with the 300 ms wait, waits for `ProjectManager.whenSavesIdle()`, and then reads once, with no wait, whether the checkpoint writer has reached the held move (`checkpointHeld.isReached()`). `whenSavesIdle()` completes once the save's wait has run out, whatever the checkpoint writer has done by then, and before its move the writer still creates the checkpoints folder, may write the checkpoint order and writes and forces the checkpoint's temporary file. When that takes longer than the wait, the check fails with "[non-vacuity: the checkpoint is held] Expecting value to be true but was false", although the behaviour under test happened: the save completed without its checkpoint and logged that it was still being written.
- `SaveOutcomeNotificationTest.aSafetyCopyStillBeingWrittenWhenTheSaveStoppedWaitingIsSaidSo` (daw-app) fails the head write, holds the safety copy at its move until the save has stopped waiting for it, releases it, and after `SaveFlowFixture.settle()` lists the project's checkpoints folder once. `settle()` waits for the save writer, which had already stopped waiting for the copy, and not for the checkpoint writer, so nothing orders the listing after the copy's move. When the move comes later, the folder holds only the copy's temporary file, which `checkpointFiles` leaves out, and the test fails with "[the copy still lands] Expected size: 1 but was: 0". The gate's log records no failure of that copy: it was late, not lost.

In both, a test reads what the checkpoint writer has done at a moment that nothing orders after the writer's step. Neither probe of the two failures found a defect of the production code.

## Goals

- Each test that reads what the checkpoint writer has done, or whether it has reached a step, reads it only once the writer has done it or reached it, so the test passes however late the writer gets there within the test's bound, and still fails when the writer never gets there. This holds for the two tests above and for every other test of daw-core's persistence package and of daw-app's save flow that reads the checkpoint writer's output after a wait that does not wait for that writer.

## Constraints

- What each test proves is unchanged: `theIdleFutureCompletesWhenTheCheckpointsBoundedWaitRunsOut` still proves that the save's idle future completes while its checkpoint is held at its move and has published nothing, and `aSafetyCopyStillBeingWrittenWhenTheSaveStoppedWaitingIsSaidSo` still proves that the save says its safety copy is still being written and that the copy then lands as one checkpoint file.
- The save's bounded waits, what a save says when one runs out, and the behaviour of `ProjectManager` and `CheckpointManager` are unchanged.
- Every wait a test adds is bounded, within the bound its class already uses (`SaveTestSupport.TIMEOUT_SECONDS`, `SaveOutcomes`' bound), and a thread a test holds is released in a `finally`.
- No test assumes a Windows or a non-Windows host.

## Acceptance Criteria

1. With the checkpoint writer made to reach its move later than the save's wait runs out, by longer than the time the test takes from the end of that wait to its read, `theIdleFutureCompletesWhenTheCheckpointsBoundedWaitRunsOut` passes.
2. With the released safety copy's move made to come later than `settle()` returns, `aSafetyCopyStillBeingWrittenWhenTheSaveStoppedWaitingIsSaidSo` passes.
3. With a checkpoint that never reaches its move, the first test fails within its bound; with a safety copy that never lands, the second test fails within its bound.
4. Every other test the sweep of the Goals finds meets criteria 1 to 3 for its own read.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- **Fault check, a late writer**: the delays of criteria 1 and 2, made in the tests' own checkpoint movers for the check and then restored; each test stays green.
- **Fault check, a writer that never gets there**: the checkpoint held before its move for longer than the test's bound, and the copy never moved; each test turns red within its bound. Restore the movers afterwards.
- `SaveWriterIdleTest`, `SaveOutcomeNotificationTest` and every test class the sweep changes stay green, alone and in the full gate.

## Non-Goals

- How long a save waits for its checkpoint, and what it says when the wait runs out (story 331, ruling J3).
- Tests of other writers than the checkpoint writer.

## Technical Notes

- Code locators: `SaveWriterIdleTest.theIdleFutureCompletesWhenTheCheckpointsBoundedWaitRunsOut` (the read of `checkpointHeld.isReached()` after `await(idle)`); `SaveTestSupport` (`SHORT_CHECKPOINT_WAIT`, `TIMEOUT_SECONDS`, the gate's `isReached` and `awaitReached`); `SaveOutcomeNotificationTest.aSafetyCopyStillBeingWrittenWhenTheSaveStoppedWaitingIsSaidSo` (the listing after `fixture.settle()`); `SaveFlowFixture` (`settle`, `checkpointFiles`, `Publishes.holdNext`); `SaveOutcomes.awaitIdle`; `ProjectManager` (`CHECKPOINT_WAIT`, `whenSavesIdle`); `CheckpointManager`, whose virtual thread writes the checkpoints; `AtomicFileWriter` (the temporary file, its force and its move).
- Observed on 2026-11-26, in story 338's second review round: the first failure in a full gate whose daw-core module took 22:31 min, against 10:30 min in a green gate later that day, the second in a full gate whose daw-core took 12:27 min and daw-app 16:58 min, against 13:01 min. Their probes ran `SaveWriterIdleTest` five times alone and once with its package, and `SaveOutcomeNotificationTest` five times alone, each green.
- Cross-refs: story 331 (the checkpoint's bounded wait, ruling J3; both tests), story 333 (the save flow's fixture), story 338 (where the failures were found).

## Status

- Deferred
