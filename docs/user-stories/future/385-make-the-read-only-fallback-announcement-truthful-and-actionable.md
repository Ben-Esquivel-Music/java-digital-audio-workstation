---
title: "Make the Read-Only-Fallback Announcement Truthful and Actionable"
labels: ["bug", "persistence", "crash-recovery", "notifications"]
---

# Make the Read-Only-Fallback Announcement Truthful and Actionable

## Motivation

When a gated Recover everything fails, story 332 keeps the recovery evidence and opens the last clean save read-only: `ProjectLifecycleController.beginGatedLoad` catches `OpenRecoveryGate.ReplayFailed` and calls `ProjectManager.prepareReadOnlyOpen`. One ERROR then says what happened. It is `projectOpen.recoveredWriteFailed` ("Could not write recovered project: {0}; opened the last clean save") when the commit was refused, and `projectOpen.failedReplay` ("Recovery failed ({0}); opened the last clean save instead") when the replay could not run. In both, `{0}` is the failure's message followed by "; recovery evidence was preserved; fallback is read-only". Three things about that outcome are wrong:

- **What it opens may not be a project.** `ProjectManager.readProject` hands any `project.daw` whose text does not start with `<?xml` or `<daw-project` to the legacy key=value reader `parseProjectFile`, which accepts any text and names the project "Untitled" when it finds no `name=` line. In the refused-link case that `AppWritersFollowAProjectFileLinkTest` pins, `project.daw` is a link to `../shared/notes.txt`. The read-only fallback opens that text file as an empty "Untitled" project, and the ERROR calls it "the last clean save".
- **It gives no next step.** The journal and any interrupted save are kept, so after the cause is fixed the next writable open of the project offers the recovery again. Nothing says so.
- **Three of the four reasons a replay cannot start are not named.** For a missing or changed session base, or no base at all, the ERROR reads "Recovery failed (Cannot establish the session replay base; recovery evidence was preserved; fallback is read-only); opened the last clean save instead", although the dialog a moment earlier named the reason (for example "the copy of the project kept when this session opened has changed"). Story 332's PR 988 review round 3 fixed only the damaged-segment case, whose ERROR now names the segment.

## Goals

- **The fallback opens a project, or says it cannot.** The legacy reader recognises a legacy project file instead of accepting any text: a head with none of its keys, such as a link's `notes.txt`, is refused with its reason, so the recovery fallback never presents it as the last clean save. A legacy metadata-only `project.daw` still opens.
- **The ERRORs give the next step.** `projectOpen.recoveredWriteFailed` and `projectOpen.failedReplay` say what to do, for example "fix the cause and reopen; recovery will be offered again".
- **Every reason is named.** A replay that cannot start because the session base is missing or changed, or because there is no base, names that reason as the dialog did, following `FAILURE_SURFACING_DESIGN_BOOK.md`: one surface, the cause named, never a bare path.

## Acceptance Criteria

1. With `project.daw` a link to a text file that is not a project, a refused recovered write opens no "Untitled" project; one ERROR says that the recovered project could not be written and that the last clean save could not be opened, and why.
2. Both fallback ERRORs end with the next step; with only the root bundle, their text is the ruled wording.
3. A Recover everything whose session base is missing or changed, or that has no base, shows an ERROR that names that reason in the dialog's words, never "Cannot establish the session replay base".
4. In every case the recovery evidence stays byte-identical, as it does today, and the next writable open after the cause is fixed shows the recovery dialog again.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Refused link to a non-project** (gated on being able to create links): `project.daw` links to `../shared/notes.txt`; Recover everything through the gate is refused; no project named "Untitled" is current, one ERROR has the criterion-1 text, and the journal and `checkpoints/.checkpoint-order` are byte-identical.
- **Next step**: a refused write to a read-only `project.daw` (the DOS read-only flag on Windows; mode 0444 on POSIX, assuming the file then reads as not writable), and a changed session base: each ERROR ends with the next step; after the cause is fixed, reopening shows the recovery dialog.
- **Named reasons**: for a missing base, a changed base and no base, the ERROR contains the reason in the dialog's words.
- **Legacy file still opens**: a legacy metadata-only `project.daw` with a `name=` line opens under that name.

## Non-Goals

- The backup a refused replace leaves behind (story 383). Story 383's Motivation quotes the composed `projectOpen.recoveredWriteFailed` text; whichever of the two stories lands second updates the other's quote and tests.
- The ungated, test-only recovery path's fallbacks (story 384).
- What the recovery dialog says before the choice (story 332).

## Technical Notes

- Code locators: `ProjectLifecycleController.beginGatedLoad` (the `ReplayFailed` catch that composes "; recovery evidence was preserved; fallback is read-only") and `failureReason`; `OpenAnnouncement.doorText`; `OpenDoor.RecoveredWriteFailed` and `OpenDoor.FailedReplayFallback`; `Messages.properties` (`projectOpen.recoveredWriteFailed`, `projectOpen.failedReplay`, `recoveryDialog.reason.*`); `JournalReplayer.cannotStart` and `describe`; `ProjectManager.readProject` and `parseProjectFile`; `AppWritersFollowAProjectFileLinkTest.aJournalReplayThroughALinkThatLeadsToNoDawFileBacksNothingUpAndWritesNothing`.
- Ruled by the studio engineer in story 332's PR 988 review round 3: the CI ruling deferred this wording and the legacy reader's acceptance as behaviour and i18n work beyond a CI fix; the H1 fix round named the damaged segment.
- Design references: `FAILURE_SURFACING_DESIGN_BOOK.md`; Persistence Integrity design book §6.2 (failure surfacing).
- Cross-refs: story 332, story 383 (`docs/user-stories/future/383-a-backup-is-taken-only-for-a-replace-that-can-happen.md`), story 384.

## Status

- Deferred
