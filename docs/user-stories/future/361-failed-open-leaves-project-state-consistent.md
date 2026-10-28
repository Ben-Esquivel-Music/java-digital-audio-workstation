---
title: "A Failed Open Leaves the Project State Consistent"
labels: ["bug", "persistence", "project-load", "locking"]
---

# A Failed Open Leaves the Project State Consistent

## Motivation

`ProjectLifecycleController.loadProjectFromPath` abandons the open project in the project manager before it reads the new one: after the check that refuses an open while a take is recording or being written, the first step inside its `try` calls `abandonOpenProject()`, which runs `ProjectManager.abandonProject()` — the checkpoint manager stops, the open project's lock is released, and the manager's current project is cleared. Only then does `ProjectManager.openProject` read the new folder, and it takes that folder's lock (`ProjectLockManager.tryAcquire`, or `forceAcquire` after a take-over) and starts the lock's 30-second heartbeat before it reads `project.daw`.

When the read or the parse then fails — an unreadable or malformed `project.daw`, or a migration that fails — two things go wrong, and both predate story 329:

- **The target folder's lock leaks.** `openProject` throws with the new lock held and its heartbeat running. Nothing releases them: `abandonProject` returns at once when no project is current. The lock file stays in that folder, and a later open of another folder rebinds the lock manager (`ProjectLockManager` holds one folder at a time) without deleting it. Opening the same folder again in this session meets its own lock as a conflict, because `tryAcquire` reports any lock file it finds.
- **The project on screen is half abandoned.** Since story 329 a failed open deliberately leaves the on-screen project's audio alone — its clips keep loading, and its report and relink stay. But the project manager now has no current project: autosave stopped with the abandon, the project's own folder is no longer locked, so another instance can open it, and Save takes the never-saved branch of `trySaveProject`, prompting for a location and creating a new project there instead of saving to the folder the project came from. A missing `project.daw` and a cancelled lock-conflict prompt (`ProjectLockedException`) fail the same way after the abandon, without the lock leak.

Story 329's tests release the leaked lock in their teardown (`FailedOpenKeepsTheOnScreenAudioTest`, `OpenAnnouncedOnceThroughAMigrationRollBackTest`).

## Goals

- **A failed open changes nothing.** The project on screen stays the project manager's current project, with its lock, heartbeat, autosave and folder, and the failed target folder is left with no lock of this session's.
- **The fix has the shape the review set.** The new project is parsed into locals before any manager state changes, the target's lock is released on failure, and the open project is abandoned only after the parse succeeded.

## Acceptance Criteria

1. An open whose `project.daw` cannot be read or does not parse, or whose migration fails, leaves no lock file of this session's in the target folder and no heartbeat running for it.
2. After such a failed open, `ProjectManager.getCurrentProject()` still names the on-screen project's folder, that folder's lock and heartbeat are still held, autosave still runs, and Save writes to that folder without the new-project location prompt.
3. A missing `project.daw` and a cancelled lock-conflict prompt leave the on-screen project in the same state.
4. Opening the same folder again after fixing its file succeeds without a lock-conflict prompt against this session.
5. Story 329's failed-open behaviour is kept: the on-screen project's clips keep loading, its report and relink remain, and the open shows one ERROR (`Open failed: …` or `Migration failed: …`).
6. A successful open still releases the old project's lock and stops its checkpoint manager exactly once, and the lock-conflict prompt still comes before the new folder's file is read.
7. An unchecked exception from the project deserializer is reported as `Open failed: …` and leaves the state criteria 1–5 require, exactly as an `IOException` does. Today `ProjectDeserializer.deserialize` wraps only parser exceptions and `loadAttempt` catches only `MigrationException` and `IOException`, so such an exception shows neither `Open failed: …` nor `Migration failed: …`: it escapes `loadAttempt` with no ERROR at all. Story 330 removed the VCA-group instance (a VCA group whose gain or id the model rejected refused the whole project), not the path, and others remain. A migration step that throws is one, and it is the example the binding test uses because no other story removes it: `MigrationRegistry.migrate` runs each step as `step.apply().apply(current)` with nothing around it, and `ProjectDeserializer.migrateAndParse` wraps nothing either, so an `IllegalStateException` from a step escapes the open; it is not a `MigrationException`, so this criterion reports it as `Open failed: …`. Until story 334 lands, an older real session can reach the path too: a legacy id-keyed insert `<parameter id="…" value="…"/>`, saved before the preset system, whose value the effect's setter now rejects. `ReflectiveParameterRegistry.createParameterHandler` wraps the setter's exception (an `InvocationTargetException` from `Method.invoke`) in an `IllegalStateException`, which `ProjectDeserializer.parseInsertSlot`'s `catch (IllegalArgumentException ignored)` does not hold, so it escapes the whole open (story 334 skips the parameter instead).
8. After a failed open, the strip is as it was before the open. The countdown keeps the on-screen project's armed tick, and never reads "No checkpoint scheduled" or stops at 0:00. The Saved cell, the last good checkpoint and any standing failure are that project's. Story 331's `checkpointsEnded()` in `abandonOpenProject` then runs only for an open that parsed, before its replacement (story 331, ruling N2). Until then story 331's interim holds: the abandon ends the countdown, so it reads "No checkpoint scheduled", the last good checkpoint is cleared, and the Saved cell keeps that project's last save.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Malformed file**: open project A, then open folder B whose `project.daw` is malformed; B holds no lock file, A's folder holds A's lock, `getCurrentProject()` names A, and a Save writes A's `project.daw` with no location prompt.
- **Migration failure**: the same with a `project.daw` whose migration fails.
- **Unchecked exception**: the `ProjectManager` uses a test `MigrationRegistry` at the current schema version (2) whose 1→2 step throws `IllegalStateException` (through `ProjectManager`'s deserializer-injection constructor, as `OpenAnnouncedOnceThroughAMigrationRollBackTest` does). Open project A, whose `project.daw` is at the current schema, so no step runs for it, then open folder B, whose `project.daw` is at schema 1, so the throwing 1→2 step runs only for B; one `Open failed: …` ERROR is shown, A stays current and locked (`getCurrentProject()` names A and A's folder holds A's lock), and B holds no lock of this session's.
- **Lock conflict cancelled**: B is locked by another session and the prompt is answered Cancel; A stays current and locked.
- **Retry**: after the malformed open, B's file is fixed and B opens without a lock-conflict prompt.
- **Strip kept** (story 331, ruling N2): Song A's countdown is armed and a checkpoint failure stands; open folder B, whose `project.daw` is malformed: after the `Open failed: …` ERROR the countdown still reads A's due time and never "No checkpoint scheduled", and the Saved cell, the last good checkpoint and the standing failure are A's. `CheckpointCountdownAcrossReplacementsTest.aFailedOpenEndsTheCountdownOfTheProjectItAbandonedAndLeavesTheSavedCell`, which pins story 331's interim, changes to this.
- **Story 329 kept**: `FailedOpenKeepsTheOnScreenAudioTest` still passes, and it and `OpenAnnouncedOnceThroughAMigrationRollBackTest` no longer release a leaked lock in their teardown; each asserts instead that the failed folder holds no lock of this session's.

## Non-Goals

- The application-exit protocol and Save As (story 333).
- Reading `project.daw` off the FX thread.
- The write-ahead journal of an abandoned project (story 362).

## Technical Notes

- `ProjectLockManager` binds one project folder at a time (`bind` replaces the held lock and folder), so ordering the new folder's lock against the release of the old one is the design question; the Resolution states the order chosen and why the lock-conflict prompt can still be cancelled without side effects.
- Code locators: `ProjectLifecycleController.loadProjectFromPath` and its private `abandonOpenProject` (the story-329 comment there explains why the audio is not retired before the parse), `ProjectLifecycleController.trySaveProject` (the never-saved branch), `ProjectManager.openProject` (lock and heartbeat before `Files.readString`), `ProjectManager.abandonProject` (returns when no project is current), `ProjectLockManager.tryAcquire`, `forceAcquire`, `startHeartbeat` and `release`.
- Design references: Persistence Integrity design book §5.3 (the open contract) and §8 Stage 5 (lock lifecycle, story 333).
- Cross-refs: story 329 (a failed open keeps the on-screen audio), story 333 (lock lifecycle), story 362 (the journal of an abandoned project).

## Sibling: Story 386

Story 386 (`docs/user-stories/future/386-a-disk-open-whose-journal-cannot-start-after-its-target-is-installed-opens-writable-with-journaling-paused.md`) is this story's sibling. This story owns failures before the target project is installed (the parse, the migration and the lock prompt), after which the project on screen stays the current one. Story 386 owns the open contract's step-6 JOURNAL failure after `prepareLoadedProject` has installed the target, which it opens writable with journaling paused. Both change the not-installed `finally` of `ProjectLifecycleController.beginGatedLoad`, toward different end states, so they must land in order, and whichever lands second re-runs the other's tests. This story's Motivation and code locators describe `loadProjectFromPath` as it was before story 332's `ProjectManager.PreparedOpen` flow, through which production's disk opens now go (`beginGatedLoad`); re-judge them against that flow before implementing either story. Story 362 is the journal-close sibling.

## Extension: The Migration Roll-Back's Reload (Story 332)

Since story 332's `ProjectManager.PreparedOpen` flow, a disk open prepares the target's lease and runs the recovery gate before it retires the outgoing project (`ProjectLifecycleController.beginGatedLoad`: "target lease leaves outgoing project intact"). A gate failure on those doors therefore leaves the outgoing project current. The migration roll-back's reload is the exception. Its folder is the open project's own, and `ProjectLockManager` holds one lease per folder. So the `OpenDoor.MigrationRollback` branch of `beginGatedLoad` closes the outgoing journal, verifies the lease, clears the journal and calls `ProjectManager.abandonProject()`, all before `prepareOpen` runs the gate. Any failure from that point until the target is installed ends with one `Open failed: …` ERROR and leaves the half-abandoned state this story's Motivation describes. Such failures include a lease check of the gate that fails, a newer head temp that cannot be read or that this build refuses (`RecoveryTemps.inspect`, since story 332's resolution of PR 988 review 5666358306), an interrupted save that cannot be copied, and a parse or migration failure. In that state the pre-roll-back model is on screen, with no current project, no lease and no journal, while `project.daw` already holds the backup that the roll-back copied over it. Found by the verification of story 332's PR 988 review 5666358306.

Goals and acceptance:

1. A roll-back reload that fails before its target is installed leaves a coherent state, not a half-abandoned one. The Resolution chooses one of two end states and says why. In the first, the outgoing project stays current under its own lease with journaling resumed, and the ERROR says that `project.daw` now holds the rolled-back file, so a Save would replace it. In the second, the outgoing project is fully retired, so no model is on screen that the project manager does not own, and the ERROR says that the roll-back was copied, that the reopen failed, and why.
2. The failure changes nothing on disk: the rolled-back `project.daw`, the evidence the gate refused and, once story 373 lands, its pre-roll-back copies stay byte-identical.

Tests, binding as above (headless; none assumes a non-Windows environment):

- **Roll-back reload refused by the gate**:
  - Open a schema-1 project and save it, which writes `project.daw.v1.<stamp>.bak`.
  - Place a head temp that cannot be read beside `project.daw`, with its last-modified time set ahead so that it is newer than the rolled-back file. Make it unreadable by removing the owner's read permission on POSIX or adding a DENY `READ_DATA` entry for its owner on Windows; skip where it stays readable.
  - Roll back on that backup (`rollbackMigration(projectDir, report, backup)`).
  - Assert: one `Open failed: The interrupted save … could not be read …` ERROR; `getCurrentProject()`, the lease, the journal and the on-screen model agree with the chosen end state; `project.daw` equals the backup; the temp is byte-identical.

Code locators: `ProjectLifecycleController.beginGatedLoad` (its `OpenDoor.MigrationRollback` branch and the not-installed `finally`), `rollBackNow`, `ProjectManager.prepareOpen` and `abandonProject`. Story 373 reworks Roll back (its pre-roll-back copies and Undo), and whichever of the two stories lands second re-runs the other's tests.

## Status

- Deferred
