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

Story 334's verification found three more places where a plugin's own code fails outside a guard. This story owns all three. The first two sit where its guard of the latency query and its browser-insert test do; the third, in the install panel the plugin browser opens, is the second's failure in another place.

- **A later delay-compensation pass during an open.** Ruling 4 (Q8) asks a plugin for its latency once, as the last step of its insert's load (`ProjectDeserializer.finishPluginInsert`). An insert the open then adds to a strip in the mixer rebuilds that strip's chain, which runs `Mixer.recalculateDelayCompensation`; that asks every strip's chain for its latency again, unguarded (`EffectsChain.captureLatency`, `LiveLatency.samples`). A plugin that answers the load's query and throws at a later pass fails the whole open today: the read releases what it built and rethrows (`ProjectDeserializer.parseDocument`). With this story's guard the open succeeds, and that insert opens offline as failed to load with Q8's detail (`… failed to load (<exception> when asked for its latency)`), counted once, whichever pass the throw comes at.
- **A checked exception a plugin does not declare, while it loads in a session.** Story 334's fix round 2 made the open release a plugin whatever its own code throws (`ExternalPluginLoader.instantiate`, finding FX2-2), but the session's two loads still catch only `RuntimeException | Error`. `PluginSlotLoader.load` disposes the plugin only on those, so such an exception from `InsertEffectFactory.createSlotFromPlugin` or `activate()` leaves the plugin undisposed and its class loader open; one from `initialize()` is released by `instantiate` and propagates as thrown. The insert rack's `insert-plugin-load` worker (`InsertEffectRack.loadAndInsertExternalPlugin`) then ends with the rack still disabled and no error shown. The plugin menu's load (`PluginViewController.loadForInsertion`) ends with its pending activation never removed, so activating that plugin on that strip again does nothing. Whatever the plugin's code throws, the load disposes the plugin once with its class loader closed, adds no insert, enables the rack again or forgets the pending activation, and shows the load's existing error, naming the exception's simple class name when it has no message (studio-signal-flow "Diagnosis and result": name the first thing that went wrong). Test: a daw-app fixture plugin built into a JAR of its own, as daw-core's `UndeclaredThrowPlugins` is, whose `activate()` throws an `IOException` it does not declare. Loaded from the browser and from the plugin menu, it is disposed exactly once each time with its class loader closed; the rack is enabled again, the menu can load it again, and each load shows one error. Narrowing either catch back to `RuntimeException | Error` turns the test red.
- **A checked exception a plugin does not declare, while the install panel installs it.** `PluginInstallPanel`, which the plugin browser and the Manage Plugins dialog open, runs a plugin's own code in two places that catch only `RuntimeException | Error`. The descriptor check (`prepare`, through `PluginRegistry.refusalFor`, which asks the plugin's `getDescriptor()`) runs on the `plugin-install-load` worker: such an exception there ends the worker, so the panel stays open with its install button disabled and no error shown, and no plugin of the JAR loaded so far is installed or released, their class loaders left open. The release loop (`runReleases`), which disposes each refused, cancelled or replaced plugin and then closes its class loader, ends at such an exception from `dispose()`: the plugins after it stay undisposed with their class loaders open. Either way the installation never completes. Whatever the plugin's code throws, the descriptor check refuses that plugin with one failure line, as it refuses one whose descriptor throws a `RuntimeException`; every release runs; and the installation completes. Test: plugins made as `ManifestIdentityInstallTest` makes them, one whose `getDescriptor()` throws an `IOException` it does not declare and a refused one whose `dispose()` throws one, each ahead of another refused plugin in its JAR: every plugin is released exactly once with its class loader closed, and the installation completes. Narrowing either catch back to `RuntimeException | Error` turns the test red.

## Status

- Deferred
