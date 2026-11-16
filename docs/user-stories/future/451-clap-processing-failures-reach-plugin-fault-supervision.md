---
title: "CLAP Processing Failures Reach Plugin-Fault Supervision"
labels: ["bug", "plugins", "clap", "error-handling", "real-time"]
---

# CLAP Processing Failures Reach Plugin-Fault Supervision

## Motivation

A CLAP plugin reaches the insert chain as `ClapInsertEffect`, the `AudioProcessor` that `ClapPluginHost.asAudioProcessor()` returns. Its failures never reach the plugin-fault supervision that every other plugin insert gets (`PluginInvocationSupervisor`: the fault log, the truthful "bypassed" notice, quarantine and, with story 340, eviction):

- **`ClapPluginHost.process` swallows every failure itself.** When the plugin's `process` returns `CLAP_PROCESS_ERROR`, or anything throws inside the call, the host copies the input to the output and returns normally. Since story 337 it records the failure as `audio.plugin` into the render thread's lane (`RenderThread.recordOnCurrentThread(AudioFailureCodes.PLUGIN, …)`; story 337, ruling E30), so the user sees a notice naming the plugin, but nothing is thrown to the slot.
- **`ClapInsertEffect.process` therefore never sees a failure.** Its own `catch (Exception e)`, which records `audio.plugin` and sets its `faulted` flag for pass-through, is reached only by an exception the host does not catch. The supervisor wrapping the slot sees a normal return, so a CLAP insert that fails on every block is never bypassed, logged in the fault log, quarantined or evicted: it passes its input through, unprocessed, until the user removes it. Off a render thread, where nothing can be recorded, `ClapInsertEffect` logs its crash at SEVERE and `ClapPluginHost.process` logs its failure at WARNING, rate-bounded, instead (story 337, ruling E77).
- **First-use allocations on the render thread.** The host hands per-call state to its static upcall callbacks through `ThreadLocal`s (`CURRENT_EVENT_SEGMENTS`, `CURRENT_EVENT_COUNT`, and `CURRENT_WRITE_TARGET` and `CURRENT_READ_SOURCE` for state streams); the first `set` on a render thread allocates that thread's map and entry there.

Story 337 only counts these failures, and story 340's eviction acts only on faults the supervisor reports, so story 337 left routing them into supervision to this story (story 337's Resolution, "Deferred"; the code comment in `ClapPluginHost.process` names this story).

## Goals

- **A CLAP processing failure is a supervised plugin fault.** A throw inside the CLAP process call, and the plugin's own `CLAP_PROCESS_ERROR` report, reach `PluginInvocationSupervisor` as a fault of that slot, exactly once per occurrence, like a throw from any other supervised plugin: the slot is bypassed, the fault is logged in the fault log and notified, quarantine counts it, and story 340's eviction applies.
- **No double surfacing.** Once a CLAP failure travels the supervision channel, it is not also recorded as `audio.plugin` in the audio health record (story 337, ruling E26: one fact on one channel).
- **No allocation on the render thread.** The per-call state the static callbacks need is handed over without a first-use `ThreadLocal` allocation on the render thread — for example preallocated per host, or set up off the real-time path.
- **The audio while faulted is the supervisor's.** What the slot outputs after a fault follows the supervisor's rule for every plugin, not a CLAP-specific pass-through.

## Acceptance Criteria

1. A CLAP insert whose process call throws, or returns `CLAP_PROCESS_ERROR`, is reported to the supervisor once per occurrence and handled like any supervised plugin fault.
2. The same failure is not recorded a second time as `audio.plugin`.
3. Processing a CLAP insert allocates nothing on the render thread, on the first block included.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- A supervised mixer with a CLAP insert double whose process throws: the slot is bypassed, one fault is published, and the audio health record's `audio.plugin` count stays zero.
- The same with a `CLAP_PROCESS_ERROR` return.
- A bytecode or allocation check: the CLAP process path, from `ClapInsertEffect.process`, allocates nothing on its first call on a fresh render thread. The off-render-thread log branches (story 337, ruling E77) never run on a render thread, so this check does not cover them.
- `ClapPluginHostTest.everyGuardedHostCallbackAnswersItsAbiValueOnAFaultAndRecordsIt` and the upcall gate (`RealTimeSafeContractTest.everyUpcallTargetCatchesEverything`) stay green: the host callbacks' own guards are unchanged.

## Non-Goals

- The host callbacks' upcall guards (`pendingEventsSize`, `pendingEventsGet`, `ostreamWrite`, `istreamRead`): story 337 guarded them (ruling E31).
- Eviction itself and the truthful notice copy: story 340.
- Running CLAP plugins out of process.

## Technical Notes

- daw-core, `core.plugin.clap.ClapPluginHost`: `process` (the `CLAP_PROCESS_ERROR` branch with the preallocated `PROCESS_ERROR_REPORTED`, the `catch (Throwable e)` pass-through, the `finally` that clears the `ThreadLocal`s), the `ThreadLocal` fields, `asAudioProcessor()`; off a render thread both failure branches log through the host's `OffLaneFailureLog`, naming the plugin by `pluginLabel()` (story 337, ruling E77).
- daw-core, `core.mixer.ClapInsertEffect`: `process` (`catch (Exception e)`, the `faulted` flag, `copyBuffer`), `isFaulted()`, `reset()`; off a render thread the catch logs at SEVERE, naming the plugin by `pluginName()`, and sets `faulted` with that record (story 337, ruling E77). What a freeze does with a CLAP insert that had already faulted when it began is future story 454's.
- daw-core, `core.plugin.PluginInvocationSupervisor`: the wrapper's catch, `reportAudioFault`, the `plugin-fault-drain` thread (story 337 moved its `Error` SEVERE there, ruling E26).
- Story 340's Note from Story 334: the supervised wrapper and `InsertSlot.drainParametersToAudio` catch narrower than `Throwable`; widening them is story 340's.
- Skills: `dawg-native-libs` "## 3. The Java FFM layer — the rules" ("### Upcall guards"); `research-daw` "### Architectural Patterns (5 Key Lessons)", item 3.
- Book: `FAILURE_SURFACING_DESIGN_BOOK.md` §4.7 and §5.5 (fault eviction), §3.2 (one fact, one channel).
- Cross-refs: story 337 (rulings E26, E30 and E31, and its Resolution's "Deferred" list), story 340 (eviction), story 128 (plugin-fault supervision), story 034 (CLAP hosting).
- The CLAP host callbacks (`pendingEventsSize`, `pendingEventsGet`, `ostreamWrite`, `istreamRead`) are static and record `audio.plugin` with no subject, so story 337's drain shows the unnamed plugin sentence for them (story 337, ruling E64); routing them to supervision should also name the plugin.

## Status

- Deferred
