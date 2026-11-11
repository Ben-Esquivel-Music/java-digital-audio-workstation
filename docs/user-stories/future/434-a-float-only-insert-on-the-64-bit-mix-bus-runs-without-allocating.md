---
title: "A Float-Only Insert on the 64-Bit Mix Bus Runs Without Allocating on the Audio Thread"
labels: ["bug", "audio-engine", "mixer", "real-time"]
---

# A Float-Only Insert on the 64-Bit Mix Bus Runs Without Allocating on the Audio Thread

## Motivation

In the default mix precision, `MixPrecision.DOUBLE_64`, the mixer runs the master's insert chain and each return bus's chain through `EffectsChain.processDouble`, a chain with sidechain routing aside. For every link that is not bypassed, `EffectsChain.processDoubleSnapshot` calls the link's processor's `processDouble`. A processor that does not report `supportsDouble()` inherits the SDK's default `AudioProcessor.processDouble`, which allocates two `float[][]` buffers on each call, narrows the input into one, runs `process` and widens the output back. So each float-only insert on the master or a return bus allocates on the audio thread in every block it processes. That covers the built-in processors with no native double path and every external plugin whose processor does not report double support. `PluginInvocationSupervisor` forwards both calls to the processor it wraps, so supervision does not change this.

The adapter dates from story 127, which kept "per-plugin I/O adapters that convert `double` ↔ `float` when a processor reports `supportsDouble() == false`"; story 334 did not introduce it. The review of story 334's pull request (comment C1) found it for the pass-through that story 334's offline inserts use, and story 334 gave `PassThroughProcessor` a native double path. The studio engineer's ruling on that review (R-C1.5) filed the general case as this story, with high priority.

Two texts state what is not so today:

- The SDK Javadoc of `AudioProcessor.processDouble` says that hosts "must provide pre-allocated narrowing buffers via a dedicated adapter", and to see `MixerChannel` "for the reference implementation". Neither `MixerChannel` class, daw-core's or the SDK model's, has such an adapter.
- `EffectsChain.processDouble` says that calling it on the audio thread "is only safe when all processors in the chain support double or intermediate double buffers have been pre-allocated". Pre-allocated double buffers do not stop a float-only processor's adapter from allocating.

## Goals

- **Float scratch prepared with the chain**: `EffectsChain` prepares, with each snapshot, float scratch for every link whose processor does not support double, and narrows into it and widens out of it itself, with channel handling identical to the float path. The SDK's allocating default adapter is never reached from the render path.
- **The texts made true**: the SDK Javadoc of `AudioProcessor.processDouble` (its "reference implementation" sentence) and the real-time note of `EffectsChain.processDouble` state what the code does, and `EffectsChain.processDouble` is marked `@RealTimeSafe` once it is.

## Acceptance Criteria

1. A prepared double chain holding a float-only processor, on the master and on a return bus, allocates nothing on the audio thread.
2. Its output equals, bit for bit, the float path's output widened to double.
3. Neither text names an implementation or a condition the code does not have.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- An allocation test: a prepared double chain holding a float-only processor, on the master and on a return bus, allocates 0 bytes over N blocks, measured with `ThreadMXBean`.
- An equality test: that chain's output equals the float path's output widened, bit for bit.
- Fault check, run once, turning a test red: the default adapter reached again.

## Non-Goals

- **Native double paths for built-in DSP.** A float-only plugin still runs in float (story 127: "keeping per-plugin processing at the plugin's preferred precision").

## Technical Notes

- Ruling: the studio engineer's ruling R-C1.5 on the review of story 334's pull request, recorded in [story 334's Resolution](../334-full-state-persistence-inserts-takes-tempo-map.md#resolution) (Round 5, R-C1).
- Code locators: `AudioProcessor.supportsDouble` and `processDouble` (daw-sdk); `EffectsChain.processDouble`, `processDoubleSnapshot` and `allocateIntermediateDoubleBuffers`; `MixerChannel.prepareEffectsChain`; `Mixer`'s return-bus and master-insert calls of `EffectsChain.processDouble`; `PluginInvocationSupervisor`, which forwards `supportsDouble` and `processDouble`; `PassThroughProcessor.processDouble`, story 334's native path for its own slot.
- Measuring: story 334's pin for its own slot, `OfflineInsertAudioTest.anOfflineInsertAllocatesNothingOnTheDoubleMixBus`, warms up and then requires one batch of 10,000 blocks at exactly 0 bytes out of at most five, because a one-off JVM event can land in one batch on that thread even when no slot code runs, while the default adapter allocates in every batch (about 4 KB per block there). The same form fits this story's allocation test.
- Skills: `dawg-annotations-reflection` §4 "Adding a new `@RealTimeSafe` method"; `research-daw`, `docs/research/open-source-daw-tools.md` "### 3. Real-Time Audio Processing" ("no allocations or locks on the audio thread").
- Cross-refs: story 127 (the 64-bit mix bus and its per-plugin adapters); story 334 (found by its pull request's review, comment C1).

## Status

- Deferred
