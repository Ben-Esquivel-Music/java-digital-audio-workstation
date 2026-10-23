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

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Malformed file**: open project A, then open folder B whose `project.daw` is malformed; B holds no lock file, A's folder holds A's lock, `getCurrentProject()` names A, and a Save writes A's `project.daw` with no location prompt.
- **Migration failure**: the same with a `project.daw` whose migration fails.
- **Unchecked exception**: the `ProjectManager` uses a test `MigrationRegistry` at the current schema version (2) whose 1→2 step throws `IllegalStateException` (through `ProjectManager`'s deserializer-injection constructor, as `OpenAnnouncedOnceThroughAMigrationRollBackTest` does). Open project A, whose `project.daw` is at the current schema, so no step runs for it, then open folder B, whose `project.daw` is at schema 1, so the throwing 1→2 step runs only for B; one `Open failed: …` ERROR is shown, A stays current and locked (`getCurrentProject()` names A and A's folder holds A's lock), and B holds no lock of this session's.
- **Lock conflict cancelled**: B is locked by another session and the prompt is answered Cancel; A stays current and locked.
- **Retry**: after the malformed open, B's file is fixed and B opens without a lock-conflict prompt.
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

## Status

- Deferred
