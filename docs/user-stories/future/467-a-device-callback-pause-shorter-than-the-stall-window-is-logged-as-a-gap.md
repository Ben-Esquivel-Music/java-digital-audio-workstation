---
title: "A Device Callback Pause Shorter Than the Stall Window Is Logged as a Gap"
labels: ["feature", "audio-engine", "reliability"]
---

# A Device Callback Pause Shorter Than the Stall Window Is Logged as a Gap

## Motivation

Story 338's xrun feed counts what grows in the engine's counters: the health record's callback, render and sink totals and the open backend's drop counters (output starved, blocks never played, driver overloads, driver resyncs). What grows while the stream's watched heartbeat stands still is carried, and counted when the heartbeat advances again, so the refused and skipped blocks of a hiccup shorter than the stall window are not lost (story 338, ruling E73). A device callback pause shorter than the stall window in which no block was refused or skipped grows no counter: it leaves no log entry and no count, although the device may have played silence. Story 338's studio engineer recorded it and ruled no new detection; story 338's manager filed it here.

## Goals

- On a backend that reports device periods, a device callback pause longer than N device periods but shorter than the stall window is logged in the xrun log as a Gap (the requirement story 338's studio engineer wrote with rulings E72 to E80). N is the studio engineer's to rule when the story is taken up.

## Constraints

- A pause that becomes a stall is the stall's: one Stall entry, never a Gap and never xruns on top of the verdict (rulings E19 and E73).
- An entry follows ruling E20's form, "`<hh:mm:ss.mmm>`  Gap  `<detail>`  [near m:ss.s]", with no exception class name; its detail text is the studio engineer's to rule.
- The real-time side records only: nothing on a device callback or the render drive logs, locks, allocates or publishes.

## Acceptance Criteria

1. A test backend that holds its callbacks back for longer than N periods and less than the stall window gives one Gap entry for the pause.
2. A pause of N periods or fewer gives none.
3. A pause as long as the stall window gives the Stall entry and no Gap entry.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- A test that holds the callbacks of a test backend back, for each of the three lengths above.
- `XrunFeedTest`, `XrunJournalTest` and `EngineHealthMonitorTest` stay green.

## Non-Goals

- Blocks refused or skipped during a pause: story 338 counts them when the heartbeat advances again (ruling E73).

## Technical Notes

- daw-app: `XrunFeed`, `XrunJournal`, `EngineHeartbeatWatchdog`, `EngineHealthFeed`.
- daw-sdk: `BackendDropoutCounters`, the backends' device-callback period counts (`AudioBackend.threadCounters()`).
- Cross-refs: story 338 (rulings E17, E19, E20 and E73).

## Status

- Deferred
