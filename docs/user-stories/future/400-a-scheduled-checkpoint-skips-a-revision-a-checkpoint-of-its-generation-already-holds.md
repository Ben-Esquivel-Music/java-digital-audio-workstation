---
title: "A Scheduled Checkpoint Skips a Revision a Checkpoint of Its Generation Already Holds"
labels: ["bug", "persistence", "snapshots", "reliability"]
---

# A Scheduled Checkpoint Skips a Revision a Checkpoint of Its Generation Already Holds

## Motivation

Story 331's checkpoint manager skips a scheduled tick when nothing changed. `CheckpointManager.captureOnOwner` reads the generation's last checkpointed revision (`Session.checkpointed`) and, when the project's revision equals it, re-arms with `SKIPPED` and writes nothing. The same record is the generation's recovery base, so `recordWritten` replaces it only when the new checkpoint's capture carries a journal sequence at least as high as the recorded one's, or when the recorded one carries none:

```java
        if (previous == null || previous.capture().journalSequence().isEmpty()
                || (job.capture().journalSequence().isPresent()
                    && job.capture().journalSequence().getAsLong() >= previous.capture().journalSequence().getAsLong())) {
            target.checkpointed = new Checkpointed(job.capture(), file);
        }
```

A capture carries a journal sequence only while a journal is open: `captureStarted` reads the sequence source the open journal installs (`observeJournalSequence`, called by `ProjectJournalCoordinator` when it opens a journal), and the journal's close removes it. So once a checkpoint with a sequence is recorded, a checkpoint written with no journal open is never recorded as `checkpointed`. After one edit in that state, every scheduled tick finds the revision unrecorded and writes the same revision again. A project journaled from start to end is not affected, and nor is one with journaling off from the start, whose checkpoints carry no sequence and are each recorded.

Story 333 makes that mixed state reachable in production. Its paused journaling — among them a close kept open after it detached the journal (Don't quit, Keep project open), a seal failure kept open, and a Save As whose original's close failed after the detach — leaves the window open with no journal, and its checkpoints still run. What the user meets:

- An idle project in that state gets one identical checkpoint per tick.
- At the checkpoint cap, the prune deletes seeded checkpoints first — earlier sessions' — and then this generation's oldest; it never deletes the checkpoint just written or the file `checkpointed` names. So the copies push the project's older checkpoints, the rollback points the snapshot browser lists, out of `checkpoints/`. The current state is never lost.

The condition is story 331 and 332's code; it is the same at the commit before story 333. Story 333's Resolution records the known limit “Keep after the detach leaves the open project journaled no further until it is reopened”, which says that these checkpoints are written again, and names this story as the owner.

## Goals

- **A tick skips a revision its generation already wrote.** A scheduled tick writes nothing when the project's revision equals that of the last checkpoint its generation wrote, whatever journal sequence that checkpoint carries.
- **The recovery base keeps its rule.** `checkpointed` stays the generation's recovery base, recorded by today's journal-sequence rule, so the journal's capture boundary never moves back to a checkpoint with no sequence.
- **What reads or sets the recovery base is unchanged.** The seeded first tick's match (`SeedMatch`, which records a match against the newest seeded checkpoint as `checkpointed`), the prune's spare of `checkpointed`'s file and the closing checkpoint's trigger (ii) (`isCheckpointed`, through `ProjectManager.isCheckpointed`, which `ProjectFolderCloser` reads) behave as before; the Resolution shows each with a test.
- **Story 333's known limit on keeping after the detach follows.** Story 333's Resolution drops the sentence that each tick writes a checkpoint again, once this story lands.

## Acceptance Criteria

1. With a checkpoint written while a journal was open, then the journal closed and one edit made, the next scheduled tick writes one checkpoint and every later tick on the same revision re-arms `SKIPPED`.
2. In the same sequence, the generation's recovery base stays the checkpoint written with the journal open.
3. Seeding's first-tick match, the prune and trigger (ii) pass their existing tests unchanged, plus one new test each for the mixed state.
4. With journaling off from the start, and with a journal open from start to end, a tick on an unchanged revision is skipped, as today.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Paused ticks skip** (daw-core): a manager with a journal sequence source installed (`observeJournalSequence`) writes one scheduled checkpoint; the source is removed (the remover it returned is run); an edit; two ticks on that revision — the first writes, the second re-arms `SKIPPED`. Criterion 1.
- **Recovery base kept**: after the same sequence, `isCheckpointed` answers for the sequenced checkpoint's revision, not the later one's. Criterion 2.
- **Prune spare**: at the cap, in the mixed state, the prune never deletes the recovery base's file or the checkpoint just written.
- **Trigger (ii)**: a Discard and close in the mixed state writes the closing checkpoint exactly when the live revision is not the recovery base's, as story 333's trigger (ii) rules.
- **Fault check**: the new skip removed turns the first test red.

## Non-Goals

- Journaling paused itself, and when it resumes (story 333's paused state; the next open).
- The checkpoint cap, the prune's order and retention across sessions (story 331, rulings F5 and H4).
- What the snapshot browser lists.

## Technical Notes

- Code locators: daw-core `persistence/CheckpointManager` — `captureOnOwner`, `recordWritten`, `captureStarted`, `observeJournalSequence`, `isCheckpointed`, `prune`, `Session.checkpointed`, `Checkpointed`, `SeedMatch`; `ProjectJournalCoordinator` (the `observeJournalSequence(newWriter::captureSequence)` call when a journal opens, and its remover run in `closeCurrent`); `ProjectManager.isCheckpointed`; `ProjectFolderCloser` (trigger (ii)).
- The studio engineer's suggested fix (the Resolution chooses the mechanism): keep `checkpointed` as the recovery base; add a `volatile Checkpointed lastWritten` to the session, set in `recordWritten` for every write; in `captureOnOwner`, skip when `(done != null && done.revision() == revision) || (last != null && last.revision() == revision)`.
- Found by story 333's third app fix pass, where scheduled ticks after DISPOSE wrote instead of skipping under one of its fault runs; ruled by the studio engineer as a SHOULD and left to this story (story 333's final ruling F10, its new finding NEW-1): it is daw-core's bookkeeping, and `checkpointed` also feeds the prune's spare and trigger (ii), so the fix needs its own core tests.
- Design references: Persistence Integrity design book §4.2 (checkpoint snapshot isolation), §4.8 (the exit coordinator and retention unification).
- Cross-refs: story 331 (the checkpoint manager, its skip and its retention), story 332 (the journal's capture boundary), story 333 (journaling paused; its known limit on keeping after the detach).

## Status

- Deferred
