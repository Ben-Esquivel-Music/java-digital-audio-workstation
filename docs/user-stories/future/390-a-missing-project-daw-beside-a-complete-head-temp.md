---
title: "A Missing project.daw Beside a Complete Head Temp"
labels: ["bug", "persistence", "crash-recovery", "project-load"]
---

# A Missing project.daw Beside a Complete Head Temp

## Motivation

A project folder can hold a complete atomic-writer temp of `project.daw` and no `project.daw`: for example after a crash during a new project's first save, once its temp was complete and before it was moved into place, or after `project.daw` was moved away by hand or by a sync tool. `RecoveryTemps.inspect` compares each head temp's last-modified time with `project.daw`'s, and it calls `Files.getLastModifiedTime(head)` outside the `try` block that guards the rest of that check. With no `project.daw`, that call throws `NoSuchFileException`, the recovery gate fails before any dialog, and the open ends with an `Open failed: …` ERROR. Nothing is deleted, so this is safe, but the project cannot be opened without moving files by hand, and the complete temp, the newest state of the project, is never offered.

The dialog is not ready for the case either. Its Recover everything sentence for a promoted interrupted save, "Recover everything makes the interrupted save the project and keeps a backup of the last clean save, in a file beside it whose name starts with "project.daw.recovered".", would be false with no `project.daw`, and its interrupted-save sentence is omitted whenever `project.daw` is missing.

## Goals

- **A complete temp is evidence even without a head.** When `project.daw` is missing, a complete head temp is treated as newer-head evidence, so the gate offers it instead of failing.
- **The dialog has true words for it.** The dialog says that Recover everything makes the interrupted save the project, with no backup to keep, and that Restore checkpoint only cannot open the project and keeps everything.
- **Nothing is deleted without a copy.** As for every interrupted save (story 332), no choice deletes the temp without a copy, and Restore checkpoint only refuses before deleting anything.

## Acceptance Criteria

1. A writable open of a folder with a complete head temp and no `project.daw` shows the recovery dialog, not an ERROR.
2. Every sentence of that dialog is true: Recover everything writes the temp's bytes as `project.daw`, with no backup mentioned, and Restore checkpoint only cannot open the project.
3. Recover everything writes the temp's bytes as `project.daw`, removes the temp, and announces "Recovered an interrupted save".
4. Restore checkpoint only is refused with nothing deleted: the temp and any journal stay byte-identical.
5. With an owed journal beside the temp, the dialog describes both, and no choice deletes the temp without a copy.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Temp only, no head**, through `OpenRecoveryGate`: the plan promotes the temp; Recover everything writes its bytes as `project.daw` and the temp is gone; Restore checkpoint only leaves the folder byte-identical.
- **Through a real door**: File ▸ Open of such a folder shows the dialog, and Recover everything opens the project with the criterion-3 announcement.
- **Dialog words**: `RecoveryDialogOptionsTest`'s promoted-interrupted-save case with no last clean save has the exact explanation, and no sentence mentions a backup.

## Non-Goals

- Temps of files other than `project.daw`, which the gate already handles (story 332).
- Read-only opens, which still touch nothing.

## Technical Notes

- Code locators: `RecoveryTemps.inspect` (its `Files.getLastModifiedTime(head)` call), `RecoveryScanner.plan` and its `newerHead`, `RecoveryDialog` (`recoverSentence`, `interruptedSaveSentence` and its Javadoc), `OpenRecoveryGate.restoreCheckpointOnly` and `recoverEverything`, `ProjectLifecycleController.writeRecoveredProjectFile` (it backs up only a file that exists).
- Ruled by the studio engineer in story 332's PR 988 review round 3: the H1 ruling's fourth deferral.
- Design references: Persistence Integrity design book §4.7 (the recovery gate), §5.5 (the recovery decision table); story 332's goal that the gate handles the temps of story 331's writer.
- Cross-refs: story 331 (`AtomicFileWriter`), story 332.

## Extension: Every Complete Head Temp Is Kept (Story 332)

`RecoveryTemps.inspect` makes the newest complete head temp newer than `project.daw` the plan's interrupted save (`RecoveryTemps.newerHead`, chosen by last-modified time). Any other complete one stays in `RecoveryTemps.files`, and `temps.clean()` in `OpenRecoveryGate.run` deletes it after the choice, with no copy. That contradicts story 332's rule, stated in `OpenRecoveryGate`'s class note and in story 332's H1 recovery-dialog summary, that an interrupted save (a complete atomic-writer temp of `project.daw` newer than it) is never deleted without a copy. The case needs two or more writes of `project.daw` that were cut off after their temps were complete, with no writable gated open between them. An example is a run of saves whose move and whose temp cleanup were both refused while a sync client or a scanner held the files, followed by a crash. When there is no `project.daw` (this story's case), every complete temp is a candidate, so the case is likelier here. The newest temp by last-modified time is also not always the latest capture: if the clock steps back between two such writes, the gate offers the earlier capture and deletes the later one. Found by the verification of story 332's PR 988 review 5666358306.

Goals and acceptance:

1. Before anything is cleared, every complete head temp that a choice does not promote is kept as an "Interrupted save" snapshot, as the newest one is today (`OpenRecoveryGate.keepInterruptedSave`). Byte-identical copies are kept once (story 383's dedupe). A copy that fails aborts the decision with nothing deleted.
2. When more than one interrupted save is kept, the dialog's interrupted-save sentence says how many.
3. Recover everything still promotes one temp. The Resolution states which one (the newest by last-modified time, or another rule) and why.

Tests, binding as above (headless; none assumes a non-Windows environment):

- **Three complete temps** newer than `project.daw`, through `OpenRecoveryGate`: Recover everything makes one of them `project.daw` and keeps the other two as snapshots, each byte-identical to its temp. Restore checkpoint only keeps all three. No temp is deleted before its copy exists. When the copy of the second temp fails, all three temps stay byte-identical.
- **No `project.daw`** (this story's case) with two complete temps: the same rules hold, and Restore checkpoint only is refused with nothing deleted.

Code locators: `RecoveryTemps.inspect` and `newerHead`, `RecoveryScanner.plan`, `RecoveryPlan.interruptedSave`, `OpenRecoveryGate` (`run`, `recoverEverything`, `keepInterruptedSave`), `SnapshotStore.createSnapshotFromFile`, and `RecoveryDialog`'s interrupted-save sentence.

## Extension: Inspection's Time Reads Name the File and the Reason (Story 332)

`RecoveryTemps.inspect` reads each head temp's last-modified time, and then `project.daw`'s, outside every classification, so a time that cannot be read fails the open with nothing deleted. Story 332's resolution of PR 988 review 5666358306 keeps these reads there on purpose. Their failure leaves inspection unchanged, though. A temp that vanished between the listing and its time read, for example because another program deleted or quarantined it, fails the open with a `NoSuchFileException` whose message is the temp's path, and the user reads `Open failed: <path of the temp>`. This story's own case reads `Open failed: <path of project.daw>`. A failure text names the thing that failed (Failure Surfacing design book §5.4, "Message names the thing"), and its reason is never a bare path (`JournalState.reason`). Found by the verification of story 332's PR 988 review 5666358306.

Goal and acceptance: a time read that fails in inspection still fails the open with nothing deleted, and it never becomes a classification, but its text names the file and the reason. For a temp, use inspection's text for a temp whose bytes cannot be read: `The interrupted save <temp> could not be read (<reason>), so it was not checked for recovery; nothing was deleted. Make it readable and open the project again`. A vanished temp's reason is `NoSuchFileException`. For `project.daw`, other than its absence (which this story makes evidence), use a text that names it, gives the reason and says that nothing was deleted.

Test, binding as above (headless; none assumes a non-Windows environment): through a seam on inspection's time reads (add one if none exists), make a temp's time read throw `NoSuchFileException`, and in a second case make `project.daw`'s time read throw `AccessDeniedException`. Each gives one `Open failed: …` ERROR with the worded text, whose reason is not a path, and every file stays byte-identical. Fault check: leaving a time read's failure unworded turns the test red.

## Status

- Deferred
