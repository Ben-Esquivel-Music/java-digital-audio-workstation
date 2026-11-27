---
title: "The Fault Hold During a Stall Reads Only the Current Stream's Device Periods"
labels: ["bug", "audio-engine", "error-handling", "reliability"]
---

# The Fault Hold During a Stall Reads Only the Current Stream's Device Periods

## Motivation

The window's audio health drain escalates a device-side failure (`audio.callback`, `audio.sink`, `audio.pacing`, `audio.input`, `audio.device`) seen in three consecutive one-second buckets to an ERROR notice and latches the engine-fault fact (story 337). Story 338 holds that escalation while the stream's heartbeat has not advanced since the run began, because those counts are a stall's symptom, so a stall reads as one loss and not as an engine fault first (story 338, ruling E68). As built, the hold ends when either the render drive's beats or the device periods advance (`FailureTrack.Heartbeat.advancedSince`), because the reading's device periods can still carry a closed stream's count while a stream without periods of its own is open. On PortAudio and ASIO the render drive keeps cycling through a device stall (`CallbackBackendAdapter.awaitSinkCapacity` returns at its timeout), so a fault run that began just before the stall can raise its ERROR notice up to 2 s before "stopped responding".

Story 338's studio engineer accepted this as a known limit on two conditions, both built: the loss clears that drive's latched device-side fact when the conditions of story 338's ruling E69 hold, so the Engine cell shows "Device lost" with no fault sentence unless a fault escalates after the loss reached the drain (ruling E69 and E114's Known limit), and a notice already on screen stays (ruling E114).

## Goals

- On a backend that reports device periods, the hold reads the device periods of the current epoch only (ruling E114).

## Constraints

- Ruling E68's outcome stands: an escalation decided before the stall verdict on the same frozen heartbeat never happens; when the heartbeat advances again without a verdict, the held escalation proceeds as before.
- Rulings E69 and E70 stand: the loss clears a device-side fact latched for the lost drive when every category latched since the fact last cleared is device-side, never a halt's and never a newer drive's, and a notice already shown stays.
- `AudioHealthRecord.read` keeps its one consumer, the drain (story 337).
- Never a hold on a count that a closed stream may carry, which could hide a real fault indefinitely (ruling E114's reason).

## Acceptance Criteria

1. On a callback backend, a CALLBACK fault run that starts 1 s before a frozen-period stall raises no ERROR before "stopped responding".
2. A fault run on a stream whose device periods keep advancing escalates as it does today (ruling E68).

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- The studio engineer's verification (criterion 1), in `AudioHealthDrainTest`'s shape with a reading whose render beats advance while its device periods stand still.
- A test for criterion 2.
- `AudioHealthDrainTest`, `AudioHealthRecordTest` and `EngineHealthMonitorTest` stay green.

## Non-Goals

- The stall verdict itself and its window: story 338.

## Technical Notes

- daw-app: `AudioHealthDrain` (`followTheFault`, `streamLost`), `FailureTrack.Heartbeat`.
- daw-core: `AudioHealthRecord` (`deviceCallbackPeriods()`, `renderDriveEpoch()`, `read`).
- Cross-refs: story 337 (the escalation and the engine-fault fact), story 338 (rulings E8, E68 to E70 and E114).

## Status

- Deferred
