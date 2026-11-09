---
title: "Exit and Door-Dialog Tests Wait for In-Flight Journal and Checkpoint Writes"
labels: ["testing", "persistence", "journal", "reliability"]
---

# Exit and Door-Dialog Tests Wait for In-Flight Journal and Checkpoint Writes

## Motivation

One build of record of story 334 (2026-11-09 00:51:42) failed two story-333 tests. Both passed in every other run, including the fresh build of record. A read-only analysis traced each failure to a test that lists or deletes Song A's folder while a write that was already under way there is still running. No story-334 change is on either path, and no product code misbehaves.

- **`StolenLockAtExitTest.aStolenLockWithNoUnsavedChangesExitsWithoutADialogWritingNothing`.** The test saves, settles, steals the lock, and then lists Song A's folder. `settle()` waits for the save writer, and the save waits for its AFTER_SAVE checkpoint. When that checkpoint is written, the journal coordinator queues a checkpoint marker and requests a rotation (`JournalWriter.onCheckpointSucceeded`), so `settle()` returns once the marker is queued, not once it is on disk. The journal writer thread then appends the marker group, fsyncs it, and opens the next segment. In the failing run the listing was taken during that fsync, which took about 100 ms under full-build disk load. segment-002 was created after the listing, so `withoutThisSessionsSegment` exempted segment-001 at the listing and segment-002 at the end, and both segment-001 and the `journal` directory's own entry then compared unequal. The exit itself wrote nothing: it ended with `SEAL DONE DRAIN_WITHOUT_MANIFEST`, and segment-001's last write was earlier than the listing. The test's other three listings follow the same pattern.
- **`DoorDialogIsTheOnePendingSwitchTest.aSnapshotRestoreWhileRestoreFromArchivesChooserIsOpenIsRefusedAndTheRestoreGoesOn`.** Its sibling `aSnapshotRestoreWhileOpenProjectsChooserIsOpenIsRefusedAndOpenGoesOnAsAnswered` has the same leak through Open Project and passed in that run. Each test creates a USER checkpoint of Song A, which `SnapshotsController.createCheckpointWithLabel` only queues, and the door then opens another project. `ProjectManager.openProject` stops Song A's checkpoints without waiting, and the only thing that waits for the claimed write is the retirement of Song A's lease. `SaveFlowFixture.close()` waits for `closeProject()`, which releases only the current project's lease, never the retired one. So JUnit deletes the `@TempDir` while `CheckpointManager.publish` is still writing `.checkpoint-order` and the checkpoint, each of them a temp file, an fsync and a move. Usually the deletion wins: the temp file's move fails with `NoSuchFileException`, a SEVERE and a "was not written" WARNING are logged, and the test passes. In the failing run the publish won, and the deletion's walk failed with `DirectoryNotEmptyException`. The same leak appears in story 333's own build of record of 2026-11-03, on code from before story 334.

## Goals

- **Each stolen-lock listing waits for the journal writer's in-flight batch** — its append, its fsync and any rotation. There are two ways to do this:
  - (a) A barrier through `JournalWriter`'s `drainLock`, which `appendNextBatch` holds across the append, the fsync and the rotation. The open writer's `forceFlush()` provides it, reached through a public `ProjectJournalCoordinator` method: the coordinator's writer is private, and the test's package is not the coordinator's.
  - (b) `withoutThisSessionsSegment` exempts every `journal/segment-NNN` from the listing's newest onwards, plus the `journal` directory's own entry. Older segments, the base and the manifests are still compared.
  - (a) is preferred. It keeps the listing as strict as the stage-6 review N6 made it, whereas (b) would also accept a segment that the exit's own drain opened.
- **`SaveFlowFixture.close()` waits for every write its test started.** After `closeProject().get(...)`, it also waits, bounded as that wait is, on `projectManager.whenRetiredLeasesReleased()` or `projectManager.whenWritersIdle()`. The `@TempDir` is then deleted only after Song A's claimed checkpoint has ended. `ExitFixture` closes its `save` fixture, so its tests get the same wait.
- **A claimed "Before the restore" or "Before the open" checkpoint is written** rather than cut short by the folder's deletion. One that the door's switch stops before the checkpoint writer claims it fails unwritten and is reported, as designed: `CheckpointManager.stop` ends the generation, and no job of it is claimed after that. No wait in the fixture changes that.

## Acceptance Criteria

1. Each of `StolenLockAtExitTest`'s four baseline listings (:92, :126, :165, :194) is taken after the journal writer's in-flight batch has ended.
2. `SaveFlowFixture.close()` returns only after every checkpoint its test started has ended. A wait that passes its bound fails the test instead of hanging it.
3. In a daw-app run, a "Before the restore" or "Before the open" checkpoint that the checkpoint writer has claimed is never cut short by the folder's deletion: neither `The checkpoint "Before the restore" was not written` nor `The checkpoint "Before the open" was not written` is logged with a `NoSuchFileException`, and no deletion of the test's `@TempDir` fails with a `DirectoryNotEmptyException`. One stopped before it was claimed is still reported as not written, as `SnapshotsController.createCheckpointWithLabel` documents for a project replaced before its checkpoint was written.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **The checkpoint race, reproduced on demand.** Song A's claimed USER checkpoint is held inside its publish by the fixture's mover. The hold uses `Publishes.afterNext`, whose action runs on the checkpoint writer within the claim. `holdNext` would hold it too, since `close()`'s `publishes.releaseAll()` reaches only holds no publish has entered yet (`move` takes a hold off that list as its publish enters it); but a `holdNext` hold gives up by itself after `GUARD` × 3, and `move` then throws, so that checkpoint is not written, whereas an `afterNext` action is the test's own and runs after the move. With the door's switch done and the hold in place, `close()` does not return. Once the hold is released, it returns with the checkpoint published and no temp file left in `Song A/checkpoints`.
- **The journal race, reproduced on demand.** The marker group's fsync is held through the `JournalSegment.Syncer` seam, as `OverflowEscalationNoDropTest`'s `StallingSyncer` holds it. While the hold is in place, the barrier that the listing takes does not return. Once the hold is released, the barrier returns with the rotation's new segment on disk.
  - `Syncer`, and the `JournalWriter` constructor that takes it, are package-private in daw-core, and the coordinator builds its writer with the public constructor (`Syncer.REAL`).
  - A seam that lets a daw-app test give the coordinator's writer a held fsync is therefore preferred: `StolenLockAtExitTest`'s own listing can then be shown to wait. Without that seam, the proof sits in daw-core, against `JournalWriter` alone.
  - Under (b), a test of the listing helper on synthetic listings: a segment created after the listing, and the `journal` entry's new time, pass; a changed older segment or manifest still fails.
- **Fault checks, each run once, each turning its test red:**
  - the new wait removed from `SaveFlowFixture.close()`;
  - the barrier removed from the listing, or, under (b), the exemption narrowed back to the newest segment.

## Non-Goals

- **A change of product behaviour.** Neither failure is a product bug. The exit wrote nothing, and Song A's lease waited for the claimed checkpoint as designed. A seam this story adds only exposes a wait or the fsync.
- **The other "was not written" lines in the test logs**, such as "Before the edit" and "Before the take", which the analysis did not trace.
- **Story 334.** It owns none of this; its build of record only met these races.

## Technical Notes

- **Source.** The read-only analysis of story 334's failing build of record. The fresh build of record (01:25:55) passed, and the stolen-lock test passed in 6 of 6 isolated runs. The leak in the door-dialog test shows in every full run the analysis read and in 4 of the 6 isolated runs, the deletion winning in all of them but the failing full run (00:51:42), where the publish won. In the final build of record (02:35:51), Song A's checkpoints again stopped with work claimed, and the test logged no checkpoint failure. The fsync attribution rests on NTFS timestamps (a file's mtime is its last write, before the fsync; a directory's mtime is its last entry creation), and the failure was not reproduced.
- **Stolen-lock code locators:**
  - `StolenLockAtExitTest.java`: the test is at :159-174, with its listing at :165; the other listings are at :92, :126 and :194. `withoutThisSessionsSegment` is at :574-579, and `listing` (:581-589) is a `Files.walk`, so the `journal` directory's own entry is compared.
  - `SaveFlowFixture.settle` (:375-379) → `SaveOutcomes.awaitIdle` (:75-76) → `ProjectManager.whenSavesIdle` (:1102). `ProjectManager.checkpointOfTheSave` (:2248-2258) waits on the checkpoint with `checkpointWait`.
  - `CheckpointManager.write` (:1717) → `recordWritten` (:1780) → `notifyWritten` (:2109), whose listeners run synchronously. `ProjectJournalCoordinator.java:365-372` is the `Written` listener that calls `onCheckpointSucceeded`, and :338 builds the writer with the public constructor.
  - `JournalWriter`:
    - `onCheckpointSucceeded` (:546-561) queues the marker under `appendLock` and sets `rotateRequested` (:559);
    - `forceFlush` is at :585;
    - `appendNextBatch` (:630-641) holds `drainLock` across the append, the fsync and the rotation;
    - `appendBatchAndMaybeRotate` (:698-716) reads `checkpointRotate` at :709;
    - `rotateSegment` (:719) → `openSegment` (:794).
  - `JournalSegment.appendGroup` (:114) calls `syncer.force(channel)` at :138; `JournalSegment.Syncer` (:39) is package-private.
- **Door-dialog code locators:**
  - `DoorDialogIsTheOnePendingSwitchTest.java`: the restore test is at :226-250 and the "Before the open" sibling at :72-99; `@TempDir` is at :48-49.
  - `SnapshotsController.createCheckpointWithLabel` (:365) and `writeCheckpoint` (:426); the javadoc at :345-346 says "The call only queues".
  - `ProjectLifecycleController.restoreArchive` (:3080) → `openRestoredArchive` (:3168) → `loadProjectFromPath` (:4472) → `loadAttempt` (:4489), which is ungated because the fixture installs no recovery gate.
  - `ProjectManager.openProject` (:651): `stopCheckpointsOf` is at :654, and `retire(outgoing, allOf(savesQueued, headIdle, outgoingClaims))` at :686.
  - `SaveFlowFixture.close()` (:484-504) calls `closeProject().get(GUARD × 3)` at :502. `ProjectManager.closeProject(CompletionStage)` (:1653-1677) returns only the current lease's release (:1669).
  - The two candidate waits are `whenRetiredLeasesReleased` (:1693), which `StolenLockAtExitTest` already uses at :553-554 (and at :235 and :511), and `whenWritersIdle` (:1121-1122), which covers every checkpoint job of every generation and folder through `CheckpointManager.whenNoJobIsHeld` (:1430).
  - `CheckpointManager.stop` (:494) never waits; its "claimed there" INFO is at :518-519. A claim is never cut short (`claimsOutlivedTheBound`, :1271). `publish` (:1834) writes `.checkpoint-order` and then the checkpoint (:1852-1853).
  - `SaveFlowFixture.Publishes` (:701): `afterNext` :749, `holdNext` :754, `releaseAll` :780, `move` :785.
- **Cross-refs:** story 333 (the tests, the fixtures, the exit protocol); story 334 (the build of record that met these races).

## Status

- Deferred
