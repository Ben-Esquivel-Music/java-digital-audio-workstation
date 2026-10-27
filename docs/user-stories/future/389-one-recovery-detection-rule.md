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
- Ruled by the studio engineer in story 332's PR 988 review round 3: the H1 ruling's third deferral, extended by the verification's finding that the reopen prompt after an autosave-off session that saved its edits is routine.
- Design references: Persistence Integrity design book §3.2 (the session seal), §5.5 (the recovery decision table).
- Cross-refs: story 332, story 387.

## Status

- Deferred
