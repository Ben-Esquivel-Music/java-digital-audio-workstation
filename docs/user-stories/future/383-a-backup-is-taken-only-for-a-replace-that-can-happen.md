---
title: "A Backup Is Taken Only for a Replace That Can Happen"
labels: ["bug", "persistence", "reliability"]
---

# A Backup Is Taken Only for a Replace That Can Happen

## Motivation

Two writers back `project.daw` up just before they replace it: a save that owes the open's migration or degraded-open backup (`ProjectManager.backUpIfOwed` → `backUpBeforeFirstWrite` → `writeSiblingBackup`, `project.daw.v<n>.<stamp>.bak` or `project.daw.degraded.<stamp>.bak`), and the recovered-file writer of a journal replay (`ProjectLifecycleController.writeRecoveredProjectFile` → `backUpBeforeRecovery`, `project.daw.recovered.<stamp>.bak`). Story 331 made each ask the replace's link guard first (`AtomicFileWriter.followLinkToDawFile`, ruling M3), so neither copies through a link the guard refuses (one that does not lead to an existing regular `.daw` file), and its round G pinned the dangling case: `ProjectFileSymbolicLinkTest.aBackupOwedThroughADanglingLinkStaysOwedUntilItsDawFileIsBack` shows that a save refused through a dangling link keeps the backup owed, and that the first save once the `.daw` file is back takes it. Two gaps remain between the backup and the replace it is for:

- **A refused replace still leaves its backup.** The read-only refusal (story 331, rulings G10 and H2) happens inside the replace, after the copy. A Recover Session on a project whose `project.daw` — or the `.daw` file a link leads to — is read-only shows `Could not write recovered project: <file>: the file is read-only or not writable; it was not replaced; opened the last clean save`, leaves `project.daw` unchanged, and leaves a `project.daw.recovered.<stamp>.bak` behind; the journal is kept, so every retry leaves one more. A save of a read-only `project.daw` that owes a backup writes the backup, clears the debt and is then refused, so the backup is taken for a save that did not happen; the prober of story 331's round-G review saw the same through a link to a read-only `.daw` file. Story 331's round H scoped `backUpIfOwed`'s Javadoc and inline comment, which said that a link the head replace would refuse leaves the backup owed, to the link guard's refusal, and they now say that a read-only `.daw` file behind a link is backed up before the head replace refuses it. The recovered-file case was found by story 331's prober (the review of its round E); the save's case, which has the same order in `backUpIfOwed`, was found while this story was written. Both orders are on `main`. Story 331's ruling H1b is the model to follow: a read-only `.dawz` is refused before the archive is packed, so no archive-sized temp is written only to be refused.
- **The backup reads through the link a second time.** `backUpBeforeFirstWrite` and `writeRecoveredProjectFile` each resolve the link once, to check it, but copy through `projectFile`, so a link repointed between the check and the copy by another process (a sync client, say) has whatever it then leads to copied into the backup; a save's owed backup is then marked taken. The window is a moment and needs an outside writer. The save's case was found by the prober of story 331's round-F review, and the recovered-file writer's by the studio engineer when story 331's documentation was verified.

## Goals

- **A replace that will be refused takes no backup.** Before either writer copies `project.daw`, it asks the same read-only question the `TARGET` replace asks of the file it would replace — the `.daw` file a followed link leads to, or `project.daw` itself — and when the replace would be refused, nothing is copied and nothing is staged; a save's owed backup stays owed.
- **The backup is of the file the replace replaces.** The copy reads the file the guard resolved, once, and is named beside `project.daw` as today.

## Acceptance Criteria

1. A journal replay refused because `project.daw`, or the `.daw` file its link leads to, is read-only leaves no `project.daw.recovered.*.bak`, however many times it is retried.
2. A save of a read-only `project.daw`, or through a link to a read-only `.daw` file, with a migration or degraded-open backup owed writes no backup; once the file is writable again, the next save writes exactly one, holding the original bytes.
3. The backup holds the content of the file the replace replaced, even when the link is repointed between the guard and the copy; when that cannot be guaranteed, the save or the recovered-file write fails and writes nothing.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Read-only recovered write**: a journal replay onto a read-only `project.daw` (the DOS read-only flag on Windows; mode 0444 on POSIX, assuming the file then reads as not writable), and again through a link to a read-only `.daw` file (gated on being able to create links): no `.recovered.*.bak`, no temp, `project.daw` byte-identical, after two attempts.
- **Read-only save with a backup owed**: open a schema-1 project, make `project.daw` read-only, save: `Failed`, no `project.daw.v1.*.bak`; make it writable, save: exactly one `project.daw.v1.*.bak`, byte-equal to the schema-1 file. Again with `project.daw` a link to a read-only `../shared/Song.daw` (gated on being able to create links): the one backup sits beside the link.
- **Repointed link** (gated on being able to create links): for a save's owed backup, and again for a journal replay's `project.daw.recovered.<stamp>.bak`, with a seam between the guard and the copy repointing the link to another `.daw` file, the backup never holds that file's content.

## Non-Goals

- The read-only refusal itself and the link guard (story 331).
- Backups of other files.

## Technical Notes

- Code locators: `ProjectManager` (`backUpIfOwed`, `backUpBeforeFirstWrite`, `writeSiblingBackup`, `replaceProjectFile`), `ProjectLifecycleController` (`writeRecoveredProjectFile`, `backUpBeforeRecovery`), `AtomicFileWriter` (`followLinkToDawFile`, the read-only refusal), `ProjectFileSymbolicLinkTest` (`aBackupOwedThroughALinkTheSaveRefusesCopiesNothingAndStaysOwedForTheNextSave`, `aBackupOwedThroughADanglingLinkStaysOwedUntilItsDawFileIsBack`), `AppWritersFollowAProjectFileLinkTest`.
- Story 331's rulings: G10 and H2 (the read-only refusal), H1b (refuse before the pack: the model here), M3 (the link guard), F10 (a failed backup fails the save and keeps the backup owed).
- Cross-refs: story 330 (the degraded-open backup), story 331, story 373 (the roll-back's own copies).

## Extension: The Recovery Gate's Interrupted-Save Retention (Story 332)

Story 332's PR 988 review round 3 added a second copy taken before a replace that can still be refused. When Recover everything replays a journal beside a complete interrupted save (an atomic-writer temp of `project.daw` newer than it), `OpenRecoveryGate.recoverEverything` replays first, then keeps the interrupted save as an "Interrupted save" snapshot (`keepInterruptedSave`, through `SnapshotStore.createSnapshotFromFile`), and only then commits the recovered project through `writeRecoveredProjectFile`. A refused commit throws `ReplayFailed` before the gate clears the journal and cleans the temps, so the journal and the temp are both kept, as story 332 rules. The next writable open plans again and copies the same temp again. After N refused attempts, `snapshots/` holds N identical "Interrupted save" snapshots beside this story's N `project.daw.recovered.*.bak` files. Nothing is lost, but snapshots are never deleted automatically (Persistence Integrity design book §3.2), so the copies stay until the user removes them.

Goals and acceptance:

1. The gate's retention copy follows this story's rule: it is not taken for a commit that will be refused, or it is skipped when `snapshots/` already holds a byte-identical "Interrupted save" snapshot of the same temp. The Resolution states which, and why.
2. A Recover everything beside an owed journal, refused twice because `project.daw` is read-only, leaves at most one "Interrupted save" snapshot, byte-identical to the temp, and the temp and the journal are still there.
3. When the commit succeeds, the interrupted save is still kept before anything is deleted, as story 332 requires; Restore checkpoint only keeps it as it does today.

Tests, binding as above (headless, none assuming a non-Windows environment):

- **Refused twice**: a complete newer temp beside an unsealed journal, `project.daw` read-only (the DOS read-only flag on Windows; mode 0444 on POSIX, assuming the file then reads as not writable), Recover everything twice through the gate: at most one file in `snapshots/` whose name contains "interrupted-save", byte-identical to the temp; no `project.daw.recovered.*.bak`; the temp and `journal/` unchanged.
- **Kept on success**: the same with a writable `project.daw`: exactly one interrupted-save snapshot, and the temp is gone.

A takeover is another refused commit. Since story 332's resolution of PR 988 review 5666358306, the gate verifies the open's lease after the replay, before it copies the interrupted save (`OpenRecoveryGate.java:180`), and `writeRecoveredProjectFile` verifies it again after its backup, immediately before the replace (`ProjectLifecycleController.java:3719`). When another session takes the folder over between those two checks, the commit is refused with a `LockStolenException`, not `ReplayFailed`: the open fails, the journal and the temp are kept, and the "Interrupted save" snapshot and the `project.daw.recovered.*.bak` stay in the new holder's folder. The new holder's gate then plans again, and its Recover everything or Restore checkpoint only copies the same temp again. Of the two rules goal 1 allows, the byte-identical-snapshot dedupe is what keeps that second copy from being added; a refusal asked before the copy cannot, because the takeover comes after the question and the new holder's commit is not refused. When the Resolution moves the refusal questions before the copy, the lease question belongs among them, and the check immediately before the replace stays, because a takeover can still come during the copy.

Code locators: `OpenRecoveryGate` (`recoverEverything`, `keepInterruptedSave`, `INTERRUPTED_SAVE_SNAPSHOT`), `ProjectLifecycleController.writeRecoveredProjectFile`, `SnapshotStore.createSnapshotFromFile`, `OpenRecoveryGateTest` (`theInterruptedSaveIsKeptAsAnInterruptedSaveSnapshotBeforeAnythingIsDeleted`). Found by the verification of story 332's PR 988 review round 3 and ruled by the studio engineer as the same defect class as this story's first criterion. Story 385 (`docs/user-stories/future/385-make-the-read-only-fallback-announcement-truthful-and-actionable.md`) rewords the `projectOpen.recoveredWriteFailed` ERROR that this story's Motivation quotes; whichever lands second updates the other's quote and tests. Cross-refs: story 332.

## Status

- Deferred
