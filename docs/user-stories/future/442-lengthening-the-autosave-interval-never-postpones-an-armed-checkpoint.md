---
title: "Lengthening the Autosave Interval Never Postpones an Armed Checkpoint"
labels: ["bug", "settings", "persistence", "autosave"]
---

# Lengthening the Autosave Interval Never Postpones an Armed Checkpoint

## Motivation

An Apply that changes the autosave interval reconfigures the open project's checkpoints: `LiveSettingsApplier` calls `CheckpointManager.reconfigure`, which re-arms the next tick a full interval of the new configuration from now (`CheckpointEvent.RearmReason.RECONFIGURED`, story 331, ruling H3). daw-core's `CheckpointEventChannelTest.aReconfigureReArmsWithTheNewInterval` pins that rule, and the strip's countdown follows the re-arm (`CheckpointStatusListener`, story 331).

For a shorter interval this is what the user expects. For a longer one it postpones a checkpoint that was about to be written:

- The interval is 2 minutes, and the last checkpoint was written 1:50 ago, so the next one is 10 seconds away. The user changes the interval to 10 minutes and presses Apply. The next checkpoint is now 10 minutes away.
- The work done since the last checkpoint stays unprotected for about 11:50 instead of 2 minutes. In the worst case, the unprotected window is the time elapsed under the old interval plus the whole new interval.

Changing a setting to make checkpoints less frequent should not leave the work done so far without a checkpoint for longer than either interval allows.

## Goals

- **A longer interval never postpones a checkpoint that is already armed.** When a reconfigure lengthens the interval, the next checkpoint stays due at the earlier of the armed due time and now plus the new interval. Ticks after it follow the new interval.
- **A shorter interval keeps today's behaviour.** The next checkpoint is due at now plus the new interval, as ruling H3 says today.
- **The countdown shows the due time that applies.** The `RECONFIGURED` re-arm reports the effective due time, so the strip's countdown runs to the time the checkpoint is really written.
- **When no checkpoint is armed, the reconfigure arms one at now plus the new interval.** For example, when autosave was disabled and the Apply enables it again.

## Rejected Alternative

Anchoring the next checkpoint to the last one written, at the last write plus the new interval, was rejected. After a shorter interval, or after a long gap, that time can already be past, so a checkpoint would be written the instant the user presses Apply.

## Acceptance Criteria

1. Lengthening the interval keeps the armed due time when it is earlier than now plus the new interval.
2. Shortening the interval re-arms at now plus the new interval.
3. The `RECONFIGURED` re-arm, and so the strip's countdown, carries the effective due time.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- With a controllable clock: the interval is 2 minutes, the next checkpoint was armed 1:50 ago, then a reconfigure to 10 minutes. The next checkpoint stays due at the old due time, 10 seconds from now, not 10 minutes from now. The tick after it is due 10 minutes after that checkpoint.
- A reconfigure from 10 minutes to 2 minutes re-arms at now plus 2 minutes. This is the rule `CheckpointEventChannelTest.aReconfigureReArmsWithTheNewInterval` pins today, which keeps passing.
- The `RECONFIGURED` `CheckpointEvent.Rearmed` of a lengthen carries the kept due time and the new interval. In daw-app, the strip's countdown runs to that due time after the Apply.
- A reconfigure that enables autosave again after it was disabled arms at now plus the new interval.
- Fault check, run once, turning a test red: the reconfigure re-arms at now plus the new interval for a lengthen too.

## Non-Goals

- Disabling autosave: it still cancels the next tick (ruling H3).
- What a tick does once it fires, the skip of a revision already checkpointed, and the re-arm after a write, a failure or a skip.
- Changing the interval's range or its Settings row.

## Technical Notes

- daw-core: `CheckpointManager` (`reconfigure`, `rearm`, `armNextTick`, `scheduleNextTick`, the `Session`'s `nextTick`, which today keeps no due time), `CheckpointEvent.Rearmed` and `RearmReason.RECONFIGURED`. Tests: `CheckpointEventChannelTest`.
- The kept due time is cleared wherever the next tick is cancelled, and the scheduler's delay is that due time minus the clock's now, so the reported `nextDue` and the real tick agree. Note that `tick()` itself arms an unreported backstop tick a full interval out.
- daw-app: `LiveSettingsApplier` (the Apply's reconfigure), `status/CheckpointStatusListener` (`rearmed`), `status/ProjectOperationProgress` (`rearmCheckpointCountdown`). Test: `CheckpointIntervalStartupWiringTest`.
- Book: `PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` §5.6, the settings-truth table's "Autosave interval" row ("yes (existing reconfigure)", "strip countdown always matches actual cadence").
- Cross-refs: story 331 (ruling H3, the `RECONFIGURED` re-arm and the strip's countdown that follows it), story 335 (the persisted interval at startup, the §5.6 settings-truth row, and `CheckpointIntervalStartupWiringTest`, which pins the Apply's re-arm on the strip).

## Status

- Deferred
