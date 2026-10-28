---
title: "Replay Fidelity and Newer-Head Safety"
labels: ["bug", "persistence", "crash-recovery", "journal"]
---

# Replay Fidelity and Newer-Head Safety

## Motivation

Journal mutation records carry no state. `JournalEventRecorder` journals each event with a null payload, and `JournalReplayer`'s per-type delta map is empty, so replay dispatches every change as a no-op and Recover everything yields the recovery point itself. This is story 298's limit, which story 332 left unchanged. Story 332's PR 988 review round 3 made the recovery dialog and the open announcement say so ("This version cannot rebuild journaled changes, so … will not be in the recovered project."; "Recovered the project from its recovery point; the N journaled changes after it could not be rebuilt"). The hazards behind those sentences remain:

- **Recover everything can replace a newer save with an older project.** With autosave off a save writes no checkpoint marker, so the recovery point can be the session-start copy of the project, older than a later save. Recover everything then writes that older project as `project.daw`; only a `project.daw.recovered.<stamp>.bak` keeps the save. Story 332 made Restore checkpoint only the default (Enter) button when the last clean save is newer than the recovery point, as an interim step, but Recover everything can still be chosen.
- **A session that saved its work reopens into the dialog.** Detection owes a decision for any change journaled after the highest checkpoint marker's capture boundary, sealed or not, so an autosave-off session that saved its edits shows the recovery dialog on every reopen (story 389).
- **The announcement has no words for a partial rebuild.** `projectOpen.journal.notRebuilt` covers a replay that rebuilt nothing. Once some families rebuild, a replay that rebuilds some changes and not others needs its own sentence; `projectOpen.journal` ("Recovered N changes from the journal") is right only when every change was rebuilt.
- **Only one choice says what happens to the journal.** The Restore checkpoint only sentence says that the journal is deleted; Recover everything deletes it too after a successful commit (`OpenRecoveryGate.run` clears the journal when journaled persistence is on), and no sentence says so. While nothing is rebuilt this costs no work, because the journal holds only type tags, which Inspect events shows before the choice; once changes are rebuilt it matters.
- **The rebuilt rule is not pinned.** The summary's rebuilt count uses `JournalReplayer.rebuilds`, the rule replay applies, but while the delta map is empty no test can tell that from a hard-coded 0.

## Goals

- **Recover everything never yields less than the newest save.** Either journal state-bearing deltas for the event families, so that replay rebuilds the changes onto the recovery point, or choose the newer of `project.daw` and the recovery point as the base. The Resolution states which, and why.
- **A save is a capture point.** With autosave off, a successful save becomes a verified capture point, so a session that saved its edits neither reopens into the recovery dialog, sealed or not, nor offers a recovery point older than that save. Story 389 changes detection to match.
- **Partial rebuilds are announced truthfully.** The open announcement distinguishes a replay that rebuilt every change, some of them, or none.
- **Both choices say what they do with the journal.** The dialog's sentences state, for each choice, whether the journal is deleted or kept.
- **The interim default button retires last.** Restore checkpoint only stops being the default for a newer last clean save only once Recover everything can no longer replace a newer save.

## Acceptance Criteria

1. With autosave off: edit, save, edit, then crash. The recovery point the dialog names is not older than the save, and Recover everything leaves the saved edits in `project.daw`.
2. With autosave off: edit, save, then switch projects. Reopening the project shows no recovery dialog.
3. A record type whose delta replay applies is counted as rebuilt by the plan and changes the recovered project.
4. A replay that rebuilds some changes and not others is announced with a sentence that says both.
5. Each choice's sentence says what happens to the journal.
6. Once criteria 1 and 3 hold, Recover everything is the default button in every case.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Save then crash** (criterion 1), through the gate with autosave disabled.
- **Save then switch** (criterion 2), through the real project-switch seal.
- **A delta counts as rebuilt** (criterion 3): with a test delta for one type, `RecoveryPlan`'s rebuilt count and the recovered project both reflect it, and a type without one stays not rebuilt.
- **Partial rebuild** (criterion 4): the announcement's exact text for a replay with one rebuilt and one not-rebuilt change.
- **Journal sentences** (criterion 5): the explanation for each recovery kind, with journaled persistence on and off.

## Non-Goals

- The detection rule itself (story 389), which pairs with the capture-point goal here.
- Moving the dialog's remaining literals to the bundle (story 382's extension).
- Relabelling Restore checkpoint only (story 388).

## Technical Notes

- Code locators: `JournalEventRecorder` (the null payload it offers), `JournalReplayer` (the delta map, `rebuilds`, `applyOne`, `applyRecords`), `RecoveryPlan` (`changes`, `relation`), `RecoverySummary.Changes.rebuilt`, `RecoveryDialog` (the default-button step at the end of its constructor, `recoverSentence`, `restoreSentence`), `OpenRecoveryGate.run` and `recoverEverything` (the `notRebuilt` count, the journal clear), `OpenAnnouncement.doorText`, and `Messages.properties` (`projectOpen.journal`, `projectOpen.journal.notRebuilt`, `recoveryDialog.explain.*`); the save path with autosave off (story 332's "Capture points, replay bases and ordering").
- Ruled by the studio engineer in story 332's PR 988 review round 3: the H1 ruling's fidelity fork and its first deferral; the interim default button and the not-rebuilt announcement landed there as narrow interim steps; the verification added the journal-sentence asymmetry and the rebuilt-rule test to this story.
- Design references: Persistence Integrity design book §4.7, §5.5; Project Manager design book §4.6.1.
- Cross-refs: story 298, story 332, story 389, story 382.

## Status

- Deferred
