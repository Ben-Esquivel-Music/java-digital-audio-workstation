---
title: "A Plug-In Whose Latency Query Fails in a Session Is Faulted Like a Process Fault"
labels: ["plugin", "latency", "failure-surfacing", "reliability"]
---

# A Plug-In Whose Latency Query Fails in a Session Is Faulted Like a Process Fault

## Motivation

Delay compensation asks every insert's processor for its latency, live: `PluginDelayCompensation.recalculate` runs whenever a strip's insert chain is rebuilt (`Mixer.recalculateDelayCompensation`, the callback `MixerChannel.rebuildEffectsChain` runs), through `EffectsChain.captureLatency`, and its `plugin-latency-refresh` watcher thread asks every `DynamicLatencyProcessor` again every 5 ms. Story 334's ruling 4 (Q8) settles a latency query that throws as a project opens: it is the last step of the insert's load, and a plugin whose query throws opens offline as failed to load (`ProjectDeserializer.finishPluginInsert`). In a session nothing guards it. A query that throws while an insert is added from the plugin browser, or while any later edit rebuilds a chain, fails that edit with the plugin's exception; one that throws on the watcher thread ends the watcher, so a later change of a live latency is compensated only when a chain is next rebuilt; and a query that returns a negative value is used as it is.

Ruling 4 (Q8) records it as a future story: "A plugin whose latency query throws or returns a negative value during a session (insert from the browser, a parameter change, the live-latency watcher) is faulted like a process() fault (bypassed for the session, reported 0, one notification), never failing the edit."

## Goals

- **Faulted like a process() fault**: a latency query that throws or returns a negative value during a session faults the insert as `PluginInvocationSupervisor` faults a `process()` throw: bypassed for the session, its latency reported 0, one notification.
- **Never failing the edit**: adding an insert from the browser, a parameter change and every other chain rebuild complete; the watcher keeps running.
- **One fault, one notification**, as for a `process()` fault; the fault log names the call.
- **The open is unchanged**: a query that throws at open still opens the insert offline (ruling 4 Q8).

## Acceptance Criteria

1. Adding a plugin whose latency query throws from the browser leaves the edit done and the insert faulted (bypassed, 0 latency), with one notification.
2. A `DynamicLatencyProcessor` whose query starts to throw mid-play is faulted once, and the watcher goes on compensating the other inserts.
3. A query that returns a negative value is faulted the same way.
4. Re-enabling the insert from the fault log asks the latency again.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- A mixer test: an insert whose latency query throws, added to a strip beside a latency-L plugin: the edit completes, the faulted strip is delayed by L, and one fault is published.
- A watcher test: a dynamic-latency fixture that throws after N queries: one fault, and a later latency change on another strip is still compensated.
- A negative-latency fixture: faulted, reported 0.
- Fault checks, each run once, each turning a test red: the query unguarded in `captureLatency`; the watcher's query unguarded.

## Non-Goals

- Changing the open's rule (ruling 4 Q8).
- Persisting a fault (story 416).

## Technical Notes

- Ruling: story 334's ruling 4 (Q8), recorded in [story 334's Resolution](../334-full-state-persistence-inserts-takes-tempo-map.md#resolution).
- Code locators: `EffectsChain.captureLatency` and `LiveLatency.samples`, `PluginDelayCompensation` (`recalculate`, `watchLatencies`, `rebuildCompensation`), `Mixer.recalculateDelayCompensation`, `MixerChannel.rebuildEffectsChain`, `PluginInvocationSupervisor` (`handleFault`, `reportAudioFault`, `reenable`).
- No new work on the audio thread: the latency queries run on the control thread and the watcher (dawg-annotations-reflection §4 "Adding a new `@RealTimeSafe` method").
- Design references: Failure Surfacing book §2.6 "State never lies; copy never overstates"; studio-mixing "Mixing decisions" (latency compensation of parallel paths).
- Cross-refs: story 334 (the open's rule), story 416 (a fault's bypass stays out of the saved project).

## Note from Story 334

Story 334's verification found three more places where a plugin's own code fails outside a guard. The review of story 334's pull request closed two of them in story 334 itself: a checked exception a plugin does not declare, while it loads in a session and while the install panel installs it. This story owns the third, which sits where its guard of the latency query does:

- **A later delay-compensation pass during an open.** Ruling 4 (Q8) asks a plugin for its latency once, as the last step of its insert's load (`ProjectDeserializer.finishPluginInsert`). An insert the open then adds to a strip in the mixer rebuilds that strip's chain, which runs `Mixer.recalculateDelayCompensation`; that asks every strip's chain for its latency again, unguarded (`EffectsChain.captureLatency`, `LiveLatency.samples`). A plugin that answers the load's query and throws at a later pass fails the whole open today: the read releases what it built and rethrows (`ProjectDeserializer.parseDocument`). With this story's guard the open succeeds, and that insert opens offline as failed to load with Q8's detail (`… failed to load (<exception> when asked for its latency)`), counted once, whichever pass the throw comes at.

The review of story 334's pull request found one more, in the drain this story's faults take:

- **A fault whose exception cannot be read.** `PluginInvocationSupervisor`'s `plugin-fault-drain` thread reads each queued fault's exception off the audio thread (`materialize`: its stack trace and `getMessage()`), and its loop's guard catches `RuntimeException`, not an `Error`. An exception whose reading throws an `Error` — a `NoClassDefFoundError` for one that needs a class only its plugin's JAR holds, once its insert was removed and its class loader closed before the drain reached it — ends the drain thread, and no later fault of any plugin is written to the fault log or published. With this story, the drain survives whatever reading a fault throws, the virtual machine's own error excepted, and a fault whose exception cannot be read is still written and published, named by its class. Fault check, run once, turning a test red: the loop's guard narrowed back to `RuntimeException`.

## Status

- Deferred
