---
title: "Repeated Graph Overloads Raise a Warning with an Opt-In Bypass"
labels: ["feature", "audio-engine", "performance", "reliability"]
---

# Repeated Graph Overloads Raise a Warning with an Opt-In Bypass

## Motivation

Story 123 asked for two things that story 338 did not build:

- "When `GraphOverload` fires 3× in 5 seconds, surface a `NotificationManager` warning suggesting larger buffer size and auto-bypass the most-expensive insert as a safety net (reversible via undo)", with the Non-Goal "Automatic plugin removal on overload — only bypass, and only with user opt-in".
- "Integration test confirms no underruns during a 60-second render of a representative session."

Story 338 built the rest of story 123: the detector's real-time side, its feed, the strip's Xruns cell and the xrun log. As built, no `GraphOverload` event is published: `XrunDetector.reportGraphOverload` has no production caller. The CPU budget enforcer (`TrackCpuBudgetEnforcer`) publishes a `TrackPerformanceEvent.TrackDegraded` for each track it degrades, and `ShedLogSubscriber` writes one "Shed" entry per degradation to the xrun log, naming the track and what the applied policy did ("expensive processing bypassed", "oversampling reduced", "simpler processing substituted"), with the rolling average as a percent of the block time; a Shed entry never raises the Xruns number (story 338, rulings E21 and E42). Nothing warns the person when the overloads repeat.

## Goals

- When the engine reports a graph overload three times within five seconds, a WARNING notice suggests a larger buffer size (story 123).
- With the person's opt-in, and only then, the most expensive insert is bypassed as a safety net, and the bypass is undone by Undo (story 123's Goal and Non-Goal). Nothing is ever removed.
- A 60-second render of a representative session shows no underruns (story 123).

## Constraints

- A graph overload is fed only from the CPU budget enforcer's degradation events, read off the audio thread, never from the worker pool's failure counters (story 338, ruling E21, amended by E42).
- An overload never raises the Xruns number, and each degradation keeps its Shed entry (rulings E17, E21 and E42).
- The enforcer's own real-time purity is future story 448's.
- The notice follows the Failure Surfacing book's §5.4: the level fits the severity, it names what is overloaded, and it is rate-limited by its fingerprint.
- A test that depends on how fast the host is never passes by widening a tolerance.

## Acceptance Criteria

1. Three overloads within five seconds raise one WARNING suggesting a larger buffer size; two, or three spread over more than five seconds, raise none.
2. With the opt-in off, nothing is bypassed. With it on, the most expensive insert is bypassed once, and Undo restores it.
3. The Xruns number does not change for an overload, and the xrun log still shows one Shed entry per degradation.
4. A 60-second render of a representative session reports no underrun.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- A unit test with an injected clock: overload events at chosen times give the notice exactly when three fall within five seconds.
- A test for the opt-in: off, no bypass; on, the most expensive insert is bypassed and Undo restores it.
- A test that the Xruns number and the Shed entries are unchanged by the notice.
- The 60-second render test of story 123.
- `XrunFeedTest`, `XrunJournalTest` and `ShedLogSubscriberTest` stay green.

## Non-Goals

- What counts as an xrun, and the Xruns cell: story 338.
- Real-time purity of the budget enforcer: future story 448.

## Technical Notes

- daw-core: `XrunDetector.reportGraphOverload`, `TrackCpuBudgetEnforcer`, `TrackPerformanceEvent`.
- daw-app: `ShedLogSubscriber`, `XrunJournal`, `NotificationManager`, the undo manager.
- Cross-refs: story 123 (its Goals and Non-Goals), story 338 (rulings E17, E21 and E42), future story 448; Failure Surfacing book §5.4.

## Status

- Deferred
