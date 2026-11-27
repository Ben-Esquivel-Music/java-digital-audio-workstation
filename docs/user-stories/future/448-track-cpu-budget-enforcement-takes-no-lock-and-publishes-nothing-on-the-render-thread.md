---
title: "Track CPU Budget Enforcement Takes No Lock and Publishes Nothing on the Render Thread"
labels: ["bug", "real-time", "audio-engine", "performance", "concurrency"]
---

# Track CPU Budget Enforcement Takes No Lock and Publishes Nothing on the Render Thread

## Motivation

`MainController.installTrackCpuBudgetEnforcer` installs a `TrackCpuBudgetEnforcer` on the live engine (`audioEngine.setCpuBudgetEnforcer(cpuBudgetEnforcer)`, story 129). From then on, for every block of a stream whose block time is the enforcer's (`AudioEngine.processBlock`; story 338, ruling E204), `RenderPipeline.renderBlock` mixes through `Mixer.mixDownInstrumented`, which, on the render thread and for every track every block, calls `enforcer.registerTrack(trackId, budget)` ("Auto-register the track with the enforcer"), times the track with `System.nanoTime()`, and calls `enforcer.recordTrackCpu(trackId, trackElapsed)`. `recordTrackCpu`:

- takes a `ReentrantLock` (`lock.lock()`) that `registerTrack` and `unregisterTrack` take too, and that the window takes when it registers channels (`MainController.installTrackCpuBudgetEnforcer`), so the render thread can wait for another thread;
- allocates a `TrackPerformanceEvent.TrackDegraded` or `TrackRestored` when a track crosses its budget;
- hands that event to a `SubmissionPublisher` through `publish` (`publisher.offer(event, reportDrop)`, unless the publisher is closed), whose `offer` takes the publisher's own lock even with no subscriber, and whose drop handler tells a `PerformanceEventLossListener` subscriber, on the same thread, of an event its full buffer could not hold.

The class Javadoc's "Thread safety" paragraph says that `recordTrackCpu` and `evaluateMasterBudget` are called from the audio thread, take the enforcer's `ReentrantLock` and offer each event to a `SubmissionPublisher`, which takes its own lock. The repository's rule is that the render thread never touches a `SubmissionPublisher`, a lock or the allocator on a steady path (book §2.3, §6.1). It is an RT-purity defect on the production path, not a failure site, and story 337 left it to this story (story 337's Resolution, "Deferred").

## Goals

- **What the render thread calls on the enforcer is real-time safe.** On the render thread, recording a track's time and making sure the track is known take no lock, call no `SubmissionPublisher` method, allocate nothing and log nothing, in the steady state and when a track crosses its budget.
- **The decision and the events move off the render thread.** The render thread records each track's elapsed time with plain or release stores into storage built off the real-time path; a non-real-time reader (a drain on the FX pulse or the enforcer's own thread) computes the rolling fractions, the degrade and restore decisions, and publishes the `TrackPerformanceEvent`s.
- **The budget behaviour is unchanged.** The same measurements give the same degrade and restore events, with the same hysteresis, as today.

## Constraints

- A block still counts itself begun (`AudioEngine.blocksBegun()`) after the running check and before it reads the enforcer, and ended (the metering block index) after its last use of the enforcer, so the window closes a replaced enforcer only once no block can still be using it (story 338, ruling E203).
- Each block still uses the enforcer only when the enforcer's block time is that of the stream it renders, so no marking comes from a block measured against another stream's block time (`AudioEngine.processBlock`; story 338, ruling E204).

## Acceptance Criteria

1. The render path reaches no lock, publisher or allocation through `TrackCpuBudgetEnforcer`.
2. A track that exceeds its budget is degraded, and restored after its hysteresis, as today, observed through the published events.
3. Registering and unregistering a track while the engine streams never blocks the render thread.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- A bytecode sentinel in `RealTimeSafeContractTest`'s idiom: from `Mixer.mixDownInstrumented`, followed into `core.audio.performance`, no lock, publisher, logging or atomic read-modify-write is reachable, and nothing allocates; a deliberate break that re-adds `lock.lock()` to the recording method turns it red.
- The enforcer's existing degrade, restore and cascade tests pass unchanged against the new split, driven by an injected clock.
- A test that holds the registration path while the render side records shows the render side completing without waiting.

## Non-Goals

- The budget policy itself (thresholds, rolling window, master cascade) and its UI (`TrackBudgetUiBinding`, the "⚠" badges).
- Failure surfacing: the enforcer reports performance, not failures, and story 337's health record does not carry it.

## Technical Notes

- daw-core, `core.audio.performance.TrackCpuBudgetEnforcer`: `recordTrackCpu(String, long)` (`lock.lock()`, the event allocations, `publish` and its `publisher.offer(event, reportDrop)`), `registerTrack` and `unregisterTrack` (the same lock), the class Javadoc's "Thread safety" paragraph.
- daw-core, `core.mixer.Mixer.mixDownInstrumented` (the per-track `enforcer.registerTrack(…)` auto-registration, the `System.nanoTime()` timing and the two `enforcer.recordTrackCpu(…)` calls) and `RenderPipeline.renderBlock` (`if (cpuBudgetEnforcer != null) { mixer.mixDownInstrumented(…) }`).
- daw-app, `MainController.installTrackCpuBudgetEnforcer` (story 129) installs it on the live engine.
- House shapes for the render side: a lock-free single-producer ring of preallocated slots plus a drain (`XrunEventRingBuffer`, story 337's `AudioHealthLane`), or per-track single-writer counters read by a pulse participant (story 318's metering tap bus). The render-path sentinel's `RENDER_PATH_INVOKE_OFFENDER` and story 337's `RT_GUARD_INVOKE_OFFENDER` are the predicates to extend.
- Skills: `dawg-annotations-reflection` "## 2. The eight reflection consumers" (row 8, `RealTimeSafeContractTest`); `research-daw` "### Architectural Patterns (5 Key Lessons)", item 3.
- Book: `FAILURE_SURFACING_DESIGN_BOOK.md` §2.3 and §6.1.
- Cross-refs: story 337 (its Resolution's "Deferred" list), story 129 (the enforcer), story 318 (the tap bus's drain shape).

## Status

- Deferred
