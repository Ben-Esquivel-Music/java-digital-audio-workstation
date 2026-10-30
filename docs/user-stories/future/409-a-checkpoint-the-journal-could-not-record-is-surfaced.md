---
title: "A Checkpoint the Journal Could Not Record Is Surfaced"
labels: ["persistence", "failure-surfacing", "ux"]
---

# A Checkpoint the Journal Could Not Record Is Surfaced

## Motivation

Since PR #989 review 5707175128 (story 333), a written checkpoint whose marker the journal cannot record retires nothing. `JournalWriter.onCheckpointSucceeded(long, Path, Instant)` builds the marker — the checkpoint read back and hashed — and only once the journal has accepted it does the capture boundary advance. A checkpoint that cannot be read back, or whose marker is refused, leaves the boundary, the Journal cell's count and the exit's closing-checkpoint trigger (i) as they were. The exit heals itself wherever it writes a closing checkpoint: the records only that checkpoint's capture would have covered stay outside every capture, so they keep the closing-checkpoint trigger (i), and the closing checkpoint's marker covers them unless it, too, cannot be recorded (below).

The user learns of it only from a WARNING log line: `ProjectJournalCoordinator`'s "Could not record checkpoint capture boundary for <file>; the journal's boundary is unchanged" for a checkpoint that cannot be read back, or `CheckpointManager`'s "Checkpoint listener <listener> threw from an event listener; the other listeners are still notified and the outcome stands" for a marker the journal refuses. When the closing checkpoint's own marker cannot be recorded and a record no marker covers remains, the user learns of it from a recovery offer at the next open after a clean exit: CLOSING_CHECKPOINT reports DONE, because the file was written, SEAL follows as usual, and such a record makes `JournalState.needsRecovery` hold. That falls short of Persistence Integrity book §5.2 "The write contract" (every failure row lands in a surface the user can see) and §6.2 "Failure surfacing", and of Failure Surfacing book §2.1 "No failure is silent" and §5.4 "Notification discipline". The underlying fact matters to a tracking engineer: a file the session just wrote to the project volume cannot be read back, and that same volume holds the takes.

Story 333 records this as a known limit under *The exit's own interims*, owned by this story. It was not done there because it needs new UI state and ruled texts, and `NotificationBar` has no fingerprint rate limit yet (story 339); that round already removed the data hazard.

## Goals

- **The strip.** The status strip gets a state of its own for "written, not recorded in the journal", naming the checkpoint file and its cause, and cleared by the next accepted marker. It does not reuse `ProjectOperationProgress.recordCheckpointFailed`, whose contract is a checkpoint "that wrote nothing" (Failure Surfacing book §2.6 "State never lies; copy never overstates") and which shares `Key.CHECKPOINT_STATE` with `recordCheckpointWritten`, which the strip's Written listener posts for the same event.
- **The notification.** One WARNING notification appears on the first occurrence, fingerprint rate-limited as Failure Surfacing book §5.4 "Notification discipline" requires, so a read failure that persists does not toast at every checkpoint and every save during tracking.
- **The close.** After CLOSING_CHECKPOINT's file is written, `ProjectFolderCloser` re-reads `ProjectJournalCoordinator.hasRecordsOutsideACapture()` on the FX thread. The coordinator's checkpoint listener runs before the checkpoint's future completes (`CheckpointManager` tells its listeners before it completes the write's future), so the re-read sees the outcome of that checkpoint's marker. When the fact still holds, the closer reports it in the step's detail, with a WARNING that the next open will offer recovery. The texts are ruled by the studio engineer and kept in the bundle `ProjectCloseText` reads.

## Acceptance Criteria

1. A checkpoint that is written but cannot be read back for its marker sets the strip's "written, not recorded in the journal" state, naming the file and its cause; the next checkpoint whose marker the journal accepts clears it.
2. The first such checkpoint shows one WARNING notification; another inside the rate window shows none.
3. A close whose closing checkpoint's marker cannot be recorded, while a record no marker covers remains, reports it in CLOSING_CHECKPOINT's detail and logs a WARNING that the next open will offer recovery, with the ruled texts; SEAL, RELEASE and HIDE still follow either way.
4. After playback, then Save and close with autosave off, a closing checkpoint whose marker cannot be recorded leads to a recovery dialog that keeps story 332's newer-save handling: it shows the last clean save beside the recovery point, and Restore checkpoint only is its default button, so Enter never replaces that save.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **The AFTER_SAVE variant**: a save whose checkpoint is deleted through `SaveFlowFixture.Publishes.afterNext` right after its publish; the strip's state is set, naming the file, and cleared by the next checkpoint whose marker is accepted.
- **The CLOSING variant**: the closing checkpoint deleted the same way; CLOSING_CHECKPOINT's detail and the WARNING carry the ruled texts, and the exit still seals, releases and hides.
- **No repeated toast**: a second checkpoint the journal could not record, inside the rate window, shows no second notification.
- **The recovery dialog after the CLOSING variant** (acceptance criterion 4): playback, then Save and close with autosave off, the closing checkpoint deleted the same way; at the next open the dialog shows the last clean save beside the recovery point, and Restore checkpoint only is its default button.
- **Fault checks**, each run once, each turning its test red: the closer without its re-read of `ProjectJournalCoordinator.hasRecordsOutsideACapture()` (the CLOSING variant); the strip's state left set when the next marker is accepted (the AFTER_SAVE variant); the notification shown at every occurrence (no repeated toast); and `RecoveryDialog` without its default-button step for a newer save (the recovery dialog after the CLOSING variant).

## Non-Goals

- The order in which a checkpoint's marker is built and the capture boundary advances, which story 333 landed (PR #989 review 5707175128).
- The fingerprint rate limit itself, which is story 339's (Failure Surfacing book §5.4).
- The recovery decision table and replay base (story 332, landed; Persistence Integrity book §5.5 "The recovery decision table"), a recovery point never older than the newest save and a save as a capture point (story 387), and the detection rule itself (story 389). Acceptance criterion 4 only pins story 332's newer-save handling for this case.

## Technical Notes

- Code locators: `JournalWriter` — `onCheckpointSucceeded(long, Path, Instant)`, `offeredMutationsAfterCheckpointBoundary()`; `JournalListener.onQueuedCountChanged`; `ProjectJournalCoordinator` — its checkpoint listener, `hasRecordsOutsideACapture()`; `ProjectFolderCloser` — `closingCheckpointEnded`; `ProjectOperationProgress` — `recordCheckpointWritten`, `recordCheckpointFailed`, `Key.CHECKPOINT_STATE`; `SessionStatusStripSkin` (the Journal cell); `NotificationBar`; `CheckpointManager` — `recordWritten`; `ProjectCloseText`; `RecoveryDialog` (its constructor's default-button step for a newer save); tests `CheckpointBoundaryAfterMarkerContractTest`, `CleanExitOpensPlainlyTest`, `ClosingCheckpointFailureDoesNotStopTheExitTest`, `JournalWriterMutationBoundaryTest`, `ProjectCloseTextTest`; the fixture seam `SaveFlowFixture.Publishes.afterNext`.
- Ruled by the studio engineer on PR #989 review 5707175128 (story 333), Q2 and Q3. Three more items belong in this story's surface ruling. The coordinator's listener catches only `IOException`, so a marker the journal refuses (a Written event racing the seal) reaches `CheckpointManager`'s isolated listener call, which logs a generic WARNING; catching `IllegalStateException` with the same line is a candidate. When the read fails because another program removed the file, the Discard INFO line "Discarded changes of “<name>” were kept in checkpoint <file>." would be untrue. After Discard and close, if the closing checkpoint's marker cannot be recorded, any record of the discarded edits that no marker covers makes the next open offer recovery for work the user chose to discard, which, as story 404's ruling says, teaches users to dismiss the recovery dialog; if story 387 makes records carry state, Recover everything would restore that work. The journal records no Discard, so the dialog cannot say so today.
- Detection: the closer's WARNING that the next open will offer recovery holds only while `JournalWriter.offeredMutationsAfterCheckpointBoundary()` predicts `JournalState.needsRecovery`. If story 389 changes the detection rule first, with story 387's save as a capture point, this story's re-read and its WARNING follow the rule that has landed.
- Design references: Persistence Integrity book §3.2 "The durability artifact model", §5.2 "The write contract", §6.2 "Failure surfacing"; Failure Surfacing book §2.1 "No failure is silent", §2.6 "State never lies; copy never overstates", §2.8 "Diagnosability outlives the session", §5.4 "Notification discipline".
- Cross-refs: story 332 (the recovery gate and its dialog), story 333 (the exit protocol and its known limits), story 339 (notification discipline), story 387 (replay fidelity and newer-head safety), story 389 (one recovery detection rule), story 404 (Discard at the in-app doors).

## Status

- Deferred
