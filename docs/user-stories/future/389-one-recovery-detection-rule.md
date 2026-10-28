---
title: "One Recovery Detection Rule"
labels: ["bug", "persistence", "crash-recovery", "project-hub"]
---

# One Recovery Detection Rule

## Motivation

Whether a project owes a recovery decision, and what that decision would recover, are decided by different rules:

- **Detection uses every marker, verified or not.** `JournalState.needsRecovery` owes a decision when the journal has unreadable bytes at its end, does not end in a seal, or holds a change after the highest capture boundary of any `$checkpoint` marker, whether or not that marker's checkpoint still matches its digest. The gate's plan (`RecoveryScanner.plan`) and the Hub's `ProjectHealthScanner.isRecoverable` both use it.
- **The recovery point uses only verified markers.** The plan replays from the highest hash-verified capture boundary (`JournalState.resolveBase`). When the highest marker's checkpoint no longer matches its digest, the two disagree: changes between the verified boundary and that marker's boundary are what a replay would recover, but they do not by themselves make a decision owed.
- **The Hub ignores interrupted saves.** `ProjectHealthScanner.isRecoverable` reads only the journal (or, without one, the lock's staleness), so a project whose only evidence is a complete interrupted save newer than `project.daw` does not show as recoverable, although the gate offers it.
- **A session that saved its work is a candidate.** Story 332's Goals make "records after the last checkpoint marker" a recovery candidate on purpose, and with autosave off a save writes no marker, so a sealed autosave-off session that saved its edits shows the recovery dialog on every reopen. Story 332's interim default button keeps Enter from replacing that save; story 387 makes the save a capture point.

The practical impact is low today, because records carry no state and the gate never deletes checkpoints, but the rules should be one.

## Goals

- **Detection follows the plan.** `JournalState.needsRecovery` derives its boundary from the plan's highest hash-verified capture, as replay does.
- **The Hub counts interrupted saves.** `ProjectHealthScanner.isRecoverable` treats a complete head temp newer than `project.daw` as recovery evidence, as the gate does.
- **Paired with the save capture point.** This lands with story 387's capture-point goal, so that a session that saved its work is not a candidate and detection and the recovery point cannot diverge again.
- **The book says so.** The Persistence Integrity design book's §4.7 gate diagram and its §5.5 rows state the new detection semantics, including that a complete head temp newer than `project.daw` is recovery evidence even when no journal decision is owed (no journal, or journaling off), as the gate already treats it.

## Acceptance Criteria

1. A sealed journal whose highest checkpoint marker no longer matches its file, with a change journaled after the highest verified capture boundary but before that marker's boundary, owes a decision, and the dialog describes that change.
2. A project folder whose only evidence is a complete head temp newer than `project.daw` shows as recoverable in the Hub.
3. With story 387's capture point, an autosave-off session that saved its edits and was sealed opens with no recovery dialog.
4. §4.7's gate diagram and §5.5 describe the detection rule the code implements.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Unverified highest marker** (criterion 1): `JournalState.needsRecovery` and the gate's plan agree, through `OpenRecoveryGate`.
- **Hub and temps** (criterion 2): `ProjectHealthScanner` classifies the folder as recoverable.
- **Saved and sealed** (criterion 3): a gated reopen shows no dialog.

## Non-Goals

- Rebuilding journaled changes (story 387).
- The lock-heuristic mode used when journaling is off (story 332).

## Technical Notes

- Code locators: `JournalState.needsRecovery` (both overloads), `JournalState.resolveBase`, `RecoveryScanner.plan`, `ProjectHealthScanner.isRecoverable`, `RecoveryTemps.inspect`.
- Interrupted saves in the Hub (goal 2): since story 332's resolution of PR 988 review 5666358306, `RecoveryTemps.inspect` (`RecoveryTemps.java:54`) gives a head temp newer than `project.daw` one of three outcomes: complete (the newest such temp is the interrupted save the gate offers); incomplete (bytes that are not strict UTF-8, a missing `daw-project` marker, or XML that is not well-formed), which a plain open deletes; or cannot be assessed (it cannot be read, or this build refuses the complete document, for example one with a newer schema version), for which `inspect` throws and the open fails with nothing deleted. A Hub scan reuses that three-way classification rather than a rule of its own, and for a temp that cannot be assessed it neither throws out of the scan nor shows the project as clean.
- Ruled by the studio engineer in story 332's PR 988 review round 3: the H1 ruling's third deferral, extended by the verification's finding that the reopen prompt after an autosave-off session that saved its edits is routine.
- Design references: Persistence Integrity design book §3.2 (the session seal), §5.5 (the recovery decision table).
- Cross-refs: story 332, story 387.

## Extension: A Recovery Point That Cannot Be Read Is Not a Changed One (Story 332)

`JournalState.resolveBase` proves a recovery point by its digest through `JournalState.matchesHash`. That method returns false when the file cannot be read as well as when its digest differs (`catch (IOException unreadable) { return false; }`). So a checkpoint whose marker has the highest capture boundary is passed over when its bytes cannot be read: for example, when a scanner or a sync client holds it open, when its read permission was removed, or when a network share failed the read. An older capture or the session base then becomes the recovery point, and nothing in the dialog says that a newer one was skipped. A session base that cannot be read becomes `RecoveryPlan.Unavailable(BaseChanged)`. The dialog then says the copy kept when the session opened has changed, when in fact it could not be read, and Restore checkpoint only deletes the journal and the base on that reason (`JournalState.clear`). While records carry no state (story 387), no journaled change is lost this way, but the description is false; once replay rebuilds changes, a journal deleted on a false reason loses them. Found by the verification of story 332's PR 988 review 5666358306, which gave a newer head temp three outcomes at inspection.

Goals and acceptance:

1. A recovery-point candidate has three outcomes, as an interrupted save does at inspection (`RecoveryTemps.inspect`). If its digest matches, it can be the recovery point. If it is missing or its digest differs, it is passed over, or reported as `BaseMissing` or `BaseChanged`, as today. If it cannot be read, no plan is built: the open fails before any dialog with one `Open failed: …` ERROR that names the file and the reason and says nothing was deleted. The reason is a `FileSystemException`'s reason, else the message, else the type; never a bare path (`JournalState.reason`).
2. Detection (`JournalState.needsRecovery`, this story's first goal) and the Hub's scan use the same three outcomes. A scan neither throws nor shows the project as clean when a candidate cannot be read.

Tests, binding as above (headless; none assumes a non-Windows environment):

- **Unreadable highest checkpoint**: a journal's highest checkpoint marker names a checkpoint that cannot be read, beside an older verified capture. Make it unreadable by removing the owner's read permission on POSIX or adding a DENY `READ_DATA` entry for its owner on Windows; skip where it stays readable. The gated open fails with the criterion-1 text before any dialog, and the journal, `checkpoints/` and `project.daw` are byte-identical.
- **Unreadable session base**: the same for `journal/session-base.daw` with no verified capture. No `BaseChanged` dialog is shown, the open fails with the criterion-1 text, and nothing is deleted.
- **Kept behaviour**: a checkpoint whose digest differs is still passed over, and a base whose digest differs is still `BaseChanged`.

Code locators: `JournalState.resolveBase` and `matchesHash`, `RecoveryPlan.Unavailable`, `RecoverySummary.BaseChanged`, `RecoveryScanner.plan`, `OpenRecoveryGate.run`, `ProjectHealthScanner.isRecoverable`.

## Status

- Deferred
