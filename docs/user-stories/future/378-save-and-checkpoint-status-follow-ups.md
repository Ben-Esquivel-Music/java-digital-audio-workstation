---
title: "Save and Checkpoint Status Follow-Ups: Slow Checkpoints, an Idle Countdown, Capture Timing"
labels: ["enhancement", "persistence", "status-strip", "diagnostics"]
---

# Save and Checkpoint Status Follow-Ups: Slow Checkpoints, an Idle Countdown, Capture Timing

## Motivation

Story 331 gave the Session Status Strip separate head-save and checkpoint states and a countdown that only the checkpoint cadence's re-arm writes. Three things the studio engineer asked for in its rulings were left for later, each checked in the current tree:

- **A slow checkpoint shows nothing.** A head save still running after 500 ms shows `◐ Saving project…` in the Saved cell (`ProjectLifecycleController.SLOW_SAVE_THRESHOLD`, `ProjectOperationProgress.HEAD_SAVE_OPERATION_NAME`). A checkpoint registers no operation, however long it takes, although the Project Manager design book's §4.5 gives a checkpoint as its own example of a slow save: "For slow saves (> 500 ms, e.g. the big checkpoint at the end of a 12‑hour session) a slim progress bar overlays the "Saved" cell". On a slow or stalled volume the strip looks idle while the checkpoint writer works.
- **The countdown counts down to a tick that writes nothing.** `CheckpointCountdownCanvas` reads only `Next checkpoint in M:SS` or `No checkpoint scheduled`. On a project with no edit since its last checkpoint, every tick skips — no file, no index, no checkpoint written or failed (story 331, ruling F4) — and re-arms, so an idle project shows a countdown reaching 0:00 and starting over, hour after hour, with nothing written.
- **The FX capture's cost is not measured.** Every tick captures the whole project on the FX thread (the interim shape of story 331's Goal; the scaling strategy is the Project Manager design book's §5.3 copy-on-write work). Nothing logs how long a capture takes, so that work has no numbers; under `AutoSaveConfig.LONG_SESSION` (30 s, keep 200) the capture runs every 30 s. A capture that fails is already logged, at SEVERE with its stack, and reported (story 331).

## Goals

- **A slow checkpoint shows in the strip** (story 331, ruling G's item for the Project Manager design book §4.5): a checkpoint still being written 500 ms after its write began shows in the Saved cell, below a running head save in precedence, until it ends; a checkpoint that ends sooner shows nothing; a checkpoint never flashes the cell and never changes its time (story 331, ruling G4 (c)).
- **An idle countdown says the checkpoints are up to date** (story 331, ruling G's "idle countdown text" item): while the project's revision is the one last checkpointed, the countdown reads "Checkpoint up to date" instead of counting down to a tick that will skip; the first edit brings the countdown back.
- **The FX capture's duration is logged** (story 331, ruling G's item for the Project Manager design book §5.3): each tick's and each save's FX capture logs its duration at FINE, and a capture over a threshold logs at WARNING, rate-limited, naming the project and the duration.

## Acceptance Criteria

1. A checkpoint held at its write for more than 500 ms shows in the Saved cell until it ends; one that ends within 500 ms never shows; neither changes the cell's time or flashes it.
2. A head save running past 500 ms still shows `◐ Saving project…` while a slow checkpoint also runs.
3. On a project unchanged since its last checkpoint the countdown shows "Checkpoint up to date"; after an edit it counts down to the next tick.
4. A capture that takes longer than the threshold logs one WARNING with its duration; the others log at FINE.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Slow checkpoint**: with a checkpoint held in the writer's `Mover` past the threshold, the Saved cell's text shows it and `getLastSaveTime()` is unchanged; released, the cell returns to `✓ Saved HH:mm:ss` without a flash.
- **Fast checkpoint**: a checkpoint that lands before the threshold never shows in the cell.
- **Idle countdown**: a clean project whose revision is checkpointed shows "Checkpoint up to date" across a skipped tick; `markDirty()` brings back `Next checkpoint in M:SS`.
- **Capture timing**: a capture source that takes longer than a test threshold logs one WARNING with its duration.

## Non-Goals

- The copy-on-write capture itself (Project Manager design book §5.3).
- A percentage or byte count for a checkpoint's progress.

## Technical Notes

- Code locators: `ProjectLifecycleController.SLOW_SAVE_THRESHOLD` and `armSlowSaveIndicator`; `ProjectOperationProgress` (`HEAD_SAVE_OPERATION_NAME`, the operations list, `nextCheckpointInstant`); `SessionStatusStripSkin.savedText`; `CheckpointCountdownCanvas`; `CheckpointStatusListener`; `CheckpointManager` (the tick's capture on the owner executor, `CheckpointEvent.Rearmed` with `RearmReason.SKIPPED`); `MainController.wireAutosave`.
- Ruled by the studio engineer in story 331, ruling G's future-story items (slow checkpoints in the Saved cell, the idle countdown text, the FX capture's duration). Story 331's design recorded "No progress affordance for a slow checkpoint" as a known limit.
- Design references: Project Manager design book §4.5 (the save HUD), §5.3 (the capture's scaling); Persistence Integrity design book §4.2.
- Cross-refs: story 295 (the Session Status Strip), story 331.

## Status

- Deferred
