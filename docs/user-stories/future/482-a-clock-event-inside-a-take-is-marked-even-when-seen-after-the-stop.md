---
title: "A Clock Event Inside a Take Is Marked Even When Seen After the Stop"
labels: ["bug", "recording", "audio-engine", "reliability"]
---

# A Clock Event Inside a Take Is Marked Even When Seen After the Stop

## Motivation

Story 338 marks the clock's unlocks, relocks and resyncs in an audio take's `take.manifest` (`clock=<unlocked|locked|resync>|<frame>`). Every mark goes through `RecordCoordinator.noteClockEvent` or `RecordCoordinator.noteTakeStartsUnlocked`, which write nothing unless an audio take is RECORDING whose capture has not been sealed (ruling E280). A clock event reaches them only on the JavaFX thread and only after it is declared and seen: an unlock is declared at the second consecutive bad poll interval of the clock-lock monitor, about a second after its first bad answer, and the clock badge reads it on its next pulse; a resync is counted by the xrun feed on the engine-health monitor's 100 ms tick, kept by `XrunJournal` and handed to the take by `EngineHealthFeed` on the next pulse. So an event whose evidence lies inside the take but which is declared, or first seen, after the take's Stop, or after its capture thread sealed it early, is not marked. Story 338 records two Known limits for this (story 338, rulings E229 and E280):

- A resync that arrives within about the last 0.1 s of a take, or of the audio a take sealed early keeps (longer while E73 carries growth), is logged but not marked in the take.
- An unlock whose first bad answer is inside a take but which is declared after the Stop, or after the capture thread sealed the take early, or a clock event the window could not show before that Stop or early seal, is not marked in the take, and its notice comes without the take sentence; after an early seal such an unlock lies at the end of the take, from its date to the early seal, up to about the take's last second, or two when its first bad interval is dated at a resync, and more when it is its measurement's first decision (E269), where the early seal's 'Recording stopped' report already points.

## Goals

The requirement (story 338, ruling E229): "A clock unlock, relock or resync whose evidence lies inside an audio take's recording span is written to that take's manifest before it is sealed, even when declared or first seen after the take stopped recording."

## Constraints

- A clock event never stops or changes a take, and a device-loss seal wins (ruling E30).
- One mark per counted resync (rulings E40 and E225).
- The marks are dated as story 338 dates them: an unlock and a resync at the newest frame sampled at or before their evidence, a relock at the oldest frame sampled at or after its evidence (ruling E221), and evidence before the take's reach at its start-frame (rulings E173, E177 and E183).
- A MIDI-only take is never marked (ruling E108).
- No new key, no new field and no `manifest-version` change (ruling E46).
- Nothing is added to the real-time path, and the JavaFX thread blocks on nothing.

## Acceptance Criteria

With a fake clock-lock monitor and a deterministic Stop:
1. A resync that arrives inside the take and is handed over after the Stop gives one `clock=resync` line in the sealed manifest.
2. An unlock whose first bad answer is inside the take, declared after the Stop, gives `clock=unlocked`.
3. A relock whose evidence is inside the take, first seen after the Stop, gives `clock=locked`.
4. Evidence after the Stop gives nothing.
5. After a device-loss seal nothing is added.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- One test per acceptance criterion, each reading the sealed `take.manifest`.
- `TakeDropoutsAndClockMarksTest`, `ClockBadgeDuringATakeTest`, `ClockStatusIndicatorTest`, `TakeFrameHistoryTest`, `EngineHealthFeedTest` and `XrunJournalTest` stay green.

## Non-Goals

- The notice of an event seen after the Stop, which keeps its words without the take sentence.
- Dating several resync requests of one xrun-log entry at their own arrivals: built in story 338 (ruling E279).

## Technical Notes

- daw-app: `RecordCoordinator` (`noteClockEvent`, `noteTakeStartsUnlocked`, `takeStoppedRecording`), `TakeFrameHistory`, `EngineHealthFeed`, `XrunJournal`, `ClockLockAnnouncer`.
- daw-core: `CaptureFlushService`, `TakeManifest`.
- Cross-refs: story 338 (rulings E30, E40, E46, E73, E108, E173, E177, E183, E221, E225, E229 and E280, and its Known limits).

## Status

- Deferred
