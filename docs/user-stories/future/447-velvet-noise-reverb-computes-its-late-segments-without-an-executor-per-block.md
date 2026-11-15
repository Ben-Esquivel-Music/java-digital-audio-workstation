---
title: "Velvet Noise Reverb Computes Its Late Segments Without an Executor per Block"
labels: ["bug", "real-time", "audio-engine", "dsp", "performance"]
---

# Velvet Noise Reverb Computes Its Late Segments Without an Executor per Block

## Motivation

`VelvetNoiseReverbProcessor.process` is `@RealTimeSafe` and runs on the render thread every block. With four or more late-reverb segments (`PARALLEL_SEGMENT_THRESHOLD = 4`), its `processLateSegments` builds a new virtual-thread executor for that block, submits one task per segment, and blocks on each segment's future:

- `try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {` creates an executor per block, and the try-with-resources `close()` waits for every task before the block can finish.
- `Future<?>[] futures = new Future<?>[lateSegmentCount];` and `futures[seg] = executor.submit(() -> computeSegment(ch, segIdx, baseWritePos, numFrames));` allocate an array, a lambda and a task per segment per block.
- `futures[seg].get();` parks the render thread until each segment's virtual thread has been scheduled and has finished.

So a processor that the contract calls real-time-safe allocates, creates threads and blocks on the render thread every block. The render thread's deadline is a few milliseconds; scheduling one virtual thread per segment through the common carrier pool can take longer under load, and every wait is a dropout risk. Story 443 excluded changing this construct, and story 337 only made its failure visible: a failed segment is zeroed and recorded as `audio.dsp`, and an interrupted render thread restores its flag (story 337, ruling E29). The construct itself is this story's (story 337's Resolution, "Deferred").

## Goals

- **No executor, task, future or lambda per block.** The late segments of one block are computed without creating an executor, submitting tasks or waiting on futures on the render thread.
- **Parallelism, if kept, uses the engine's render workers.** When splitting the late segments across cores is still worth it, the work runs on threads built once and reused, such as the multi-core worker pool (`AudioWorkerPool`), not on threads created per block.
- **The output is unchanged.** The reverb's output for any input and parameter set is sample-identical to today's sequential path (`computeSegment` for each segment in order).
- **The failure path stays recorded.** A segment that fails is still zeroed for the block and recorded as `audio.dsp` on a render thread, as story 337 left it.

## Acceptance Criteria

1. `VelvetNoiseReverbProcessor.process` creates no executor, thread, future, task or lambda per block, at any segment count.
2. Its output equals the sequential path's for the same input, sample for sample, at a segment count below and above the old threshold.
3. A segment failure still zeroes that segment for the block and is recorded as `audio.dsp` on a render thread.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- A bytecode sentinel in `RealTimeSafeContractTest`'s idiom: `VelvetNoiseReverbProcessor.process`, walked through its own class, invokes no `ExecutorService`, `Executors`, `Future` or `Thread` method and allocates no task, future array or lambda; a deliberate break that re-adds `Executors.newVirtualThreadPerTaskExecutor()` turns it red.
- An output-parity test at 3, 4 and the maximum late-segment count: the processor's output equals the sequential computation's.
- `VelvetNoiseReverbProcessorTest.aFailedLateSegmentIsZeroedAndRecordedOnARenderThread` and `anInterruptedRenderThreadKeepsItsInterruptAndRecordsNothing` stay green, adapted to the new construct where they inject the failure.

## Non-Goals

- The reverb's sound, its parameters or its segment layout.
- How a segment failure is surfaced: story 337 records it as `audio.dsp`, and its drain shows it.
- Other processors' real-time purity, beyond this one construct.

## Technical Notes

- daw-core, `core.dsp.VelvetNoiseReverbProcessor`: `process` (`@RealTimeSafe`), `processLateSegments` (the executor, the submits and the `get` loop), `computeSegment` (writes one segment's work buffer frame by frame), `PARALLEL_SEGMENT_THRESHOLD`.
- Story 337's catch in `processLateSegments`: `InterruptedException` restores the flag and records nothing; `ExecutionException | CancellationException` zeroes `segmentWorkBuffers[seg]` over `[0, numFrames)` and calls `RenderThread.recordOnCurrentThread(AudioFailureCodes.DSP, cause, this, seg)` (story 337, ruling E29).
- `AudioWorkerPool` is the engine's render-worker pool: workers are `RenderThread`s built once per engine start, `invokeAll` runs a batch with the coordinator participating, and a task failure is counted as `audio.worker` (story 337, ruling E27). A processor has no reference to it today.
- Skills: `dawg-annotations-reflection` "### Adding a new `@RealTimeSafe` method"; `research-daw` "### Architectural Patterns (5 Key Lessons)", item 3 (no allocations or locks on the audio thread).
- Book: `FAILURE_SURFACING_DESIGN_BOOK.md` §2.3 and §6.1 (what the RT thread may not do).
- Cross-refs: story 337 (ruling E29 and its Resolution's "Deferred" list), story 443 (which excluded this construct), story 125 (the multi-core graph scheduler and its worker pool).

## Status

- Deferred
