---
title: "RT-Safe Capture Path"
labels: ["bug", "recording", "audio-engine", "real-time-safety"]
---

# RT-Safe Capture Path

## Motivation

The engine's render block is annotated `@RealTimeSafe` (`AudioEngine.java:909`) and the repo polices real-time paths with a bytecode sentinel (`daw-core/src/test/.../annotation/RealTimeSafeContractTest.java`) — yet the capture path violates everything that contract stands for, on the device callback thread:

- `RenderPipeline` invokes `recordingCallback.onAudioCaptured` inline in the render block (`RenderPipeline.java:495-497`), reaching `RecordingSession.recordAudioData`, which **doubles** the capture array and copies the *entire take so far* whenever capacity runs out (`RecordingSession.java:210-217`). A 2-hour 48 kHz stereo take is ~2.8 GB resident with multi-hundred-MB copies on the deadline-bound callback — dropout risk grows with take length, so the longest, most valuable takes are the ones the capture path itself ruins.
- Segment-rotation bookkeeping allocates wall-clock objects per captured block (`RecordingSession.java:382-393`) and formats strings + iterates listeners at each rotation (`RecordingSession.java:395-405`) — all on the audio thread.
- Loop-take finalization runs *synchronously inside the callback* at every loop wrap: constructing a new session, taking a full-take trimmed copy, and building take/clip objects (`RecordingPipeline.java:895-964`, copy at `:921` via `RecordingSession.java:249-258`) — despite the pipeline's own comment that finalization I/O *"never runs on the audio callback thread"* (`RecordingPipeline.java:168-170`). The virtual-thread executor hand-off it gestures at is an explicit documented no-op (`RecordingPipeline.java:954-961`).

Capture state also crosses threads with no synchronization: `RecordingSession`'s `active`/`paused`/`capturedAudio`/`capturedSampleCount` are plain fields (`RecordingSession.java:47-48,68-70`) mutated by the audio thread while the FX thread calls `stop()` and `getCapturedAudio()` — a data race that can tear the buffer-grow copy or lose the take tail. The pipeline's `active` flag is a plain boolean read in `onAudioCaptured` (`RecordingPipeline.java:70`), and its `sessions`/`routedInputBuffers` maps are plain `LinkedHashMap`s (`RecordingPipeline.java:53-55`).

Finally, the pipeline records at a format the engine is not running. `applyConfiguration` sets the settings `AudioFormat` on the **engine only** (`DefaultAudioEngineController.java:351-357`), but `onRecord` constructs the `RecordingPipeline` with `project.getFormat()` (`TransportController.java:443-445`) — pinned at the project's creation-time `STUDIO_QUALITY` default of 96 kHz / 256 frames (`MainController.java:778`, `AudioFormat.java:17`) regardless of what the engine now streams at. Routed capture buffers are sized from the project format (`RecordingPipeline.java:226,236`) while `recordToSessions` copies the frame count the engine stream actually delivered (`RecordingPipeline.java:782`) — any settings buffer size above the project's overruns the array **on the audio callback**, which the unguarded PortAudio upcall (`PortAudioBackend.java:432-448`; `FAILURE_SURFACING_DESIGN_BOOK.md` §1.7) turns into JVM death. A sample-rate mismatch corrupts every recorded clip's duration and position, computed from the project rate (`RecordingPipeline.java:300-302`).

The house already knows how to do this correctly: the ASIO bridge captures into preallocated lock-free `AudioBlockRing`s drained by a dedicated thread, with every allocation done at open time (`AsioBufferSwitchShim.java:99,161-162,223-224`). This story brings capture up to that standard on the seam story 323 builds.

## Goals

- Complete the book §5.1 RT-safety contract on the capture path: on the audio callback, capture does exactly bounded arithmetic, volatile scalar reads, and one bounded copy into preallocated memory — no allocation, no boxing, no locks, no string ops, no wall-clock objects, no listener iteration, no `SubmissionPublisher.offer` (the story-311 lesson), no file I/O.
- Delete the on-callback allocation/copy growth (`RecordingSession.java:210-217`), the on-callback rotation bookkeeping and listener iteration (`RecordingSession.java:382-405`), and retire the on-callback listener loop per the RT-safe observer rule (book §6.2).
- Size the `CaptureRing` at record-start from the engine's **live** format (book §4.2): slot payload = stream buffer frames × stream input channels; slot count covers ≥ 250 ms of audio and never fewer than 8 slots — following the `AudioBlockRing` sizing discipline. Overflow policy: the callback drops the *oldest unwritten block*, increments an atomic overflow counter, and the flush thread records a gap marker in the manifest; the callback never blocks and never silently overwrites (book §4.2).
- Replace the unbounded in-RAM `capturedAudio` copy with the bounded, decimated peak mirror of book §4.5 (min/max pairs per fixed frame bucket), maintained by the flush thread and published through an `FxDispatcher` continuous channel for live waveform drawing; post-stop playback loads clip audio from the sealed segments off the FX thread.
- Move loop-take finalization to the flush thread (book §4.6): wrap detection from slot-header beat positions (position decreased between consecutive blocks); on wrap, seal the lane's segments, append the take, and open pre-opened next-lane writers — giving the no-op executor hand-off (`RecordingPipeline.java:954-961`) its intended real body.
- Make the stop handoff a real fence (book §5.2 FINALIZING): deregister callback → drain ring → seal — fixing the `RecordingSession` plain-field races via the single-writer topology of book §2.3 (device callback writes ring slots; flush thread writes files/mirror/take model; FX thread writes coordination state only).
- The capture path stops reading racy transport fields mid-block by using slot-header snapshots stamped on the callback — the **record half** of the transport shared-state work. The transport's own volatile/seek-queue fix is story 315's scope (`AUDIO_ENGINE_WIRING_DESIGN_BOOK.md`).
- Derive all capture formats and per-block sizes per book §2.7: sample rate, channel count, and buffer size from the format the engine is *actually streaming*, captured at record-start; per-block sizes from the frame count the stream delivered — never `project.getFormat()`, never a captured constant. Where engine and project formats diverge, the divergence is **declared**: the clip records the take's true sample rate and beat math uses the true rate; surfacing rides the sample-rate-badge seam owned by story 342.
- Extend the `RealTimeSafeContractTest`-pattern bytecode sentinel over the capture-path classes so the §5.1 contract is enforced mechanically, not by review.

## Goals — Tests

- A `RealTimeSafeContractTest`-pattern bytecode sentinel over the capture-path classes asserts no allocation, boxing, locks, or string ops are reachable from the callback.
- A stress test records while the engine runs a larger buffer size than the project format: no array overrun, and the finalized clip length is correct.
- A stop-race test drives high-frequency start/stop cycles and asserts no take tail is ever torn or lost — the FINALIZING fence (deregister → drain → seal) is exercised under contention.
- A heap assertion test shows resident capture memory is flat over a long simulated take (the unbounded `capturedAudio` growth is gone; only the bounded mirror and ring remain).
- An overflow-policy test stalls the flush thread until the ring fills: the oldest block is dropped, the atomic overflow counter increments, and the manifest records a gap marker — the callback never blocks.
- A loop-wrap test asserts finalization work (session construction, trimmed copies, take/clip building) happens on the flush thread, lanes seal correctly at the wrap, and no finalization code is reachable from the callback (covered by the sentinel).
- A format-divergence test records at an engine sample rate different from the project's: the clip records the true rate and its duration/position math is correct at that rate.
- A mirror test asserts the published peak mirror is bounded and decimated (fixed bucket count) and rides the `FxDispatcher` continuous channel, never a callback-side listener.

## Non-Goals

- **The disk pipeline skeleton itself** (ring/flush/writer classes, on-disk layout, manifest, atomic seal) — story 323 (Stage 1, prerequisite); this story finishes the RT contract on that seam.
- **Record-state machine and guards** (double-press toggle-stop, input-open rollback, mid-record settings block, MIDI sentinel) — story 325 (Stage 3).
- **The transport's own state synchronization** — the volatile position/seek-queue fix on `Transport` is story 315 (`AUDIO_ENGINE_WIRING_DESIGN_BOOK.md`); this story only removes the capture path's mid-block reads of racy transport fields.
- **Multi-channel routing width and validation** — story 326 (Stage 4).
- **Rendering the SRC-mismatch badge** — story 342 (`INTERACTION_COMPLETENESS_DESIGN_BOOK.md`); this story supplies the declared-divergence facts it renders.
- **Loop-record enablement, take lanes UI, and comping** — story 328 (Stage 6); this story only moves the *existing* loop finalization machinery off the callback (cross-ref story 132, whose landed loop-record compromise this fixes).
- **Surfacing the overflow/dropout counters to the user** — the RT-safe error channel and xrun surfaces are stories 337/338 (`FAILURE_SURFACING_DESIGN_BOOK.md`); until they land, counters degrade to logging (book §6.3).
- **Guarding the PortAudio FFM upcall itself** — story 337.

## Technical Notes

- Implements **Stage 2 — RT-Safe Capture Path** of `docs/design/RECORDING_RELIABILITY_DESIGN_BOOK.md` (§8). Contracts bound: §5.1 (the RT table — the reviewer's law for this PR), §2.2/§2.3/§2.7 (principles), §4.2 (ring + overflow), §4.5 (mirror), §4.6 (loop rotation), §6.2 (observer rules).
- Files to touch: `RenderPipeline.java` (`:495-497` — the callback hand-off becomes a ring write), `RecordingSession.java` (growth, rotation bookkeeping, and listener loop retired; plain-field races removed by topology), `RecordingPipeline.java` (loop finalization to flush; buffer sizing from live format; `:70` active flag and `:53-55` maps re-homed per single-writer), `TransportController.java` (`:443-445` — pipeline construction takes the engine's live format, not `project.getFormat()`), plus the story-323 `CaptureRing`/`CaptureFlushService`.
- Seams to reuse: `AudioBlockRing` + `AsioBufferSwitchShim` drain-thread discipline (stories 311-312) as the proven local idiom; `RealTimeSafeContractTest` as the sentinel mechanism to extend; `FxDispatcher` continuous channels (story 289; `FxDispatcher.java:390`) for the mirror and elapsed-time facts; the RT-safe observer rule (volatile snapshot read once — never listener iteration on the callback).
- `SubmissionPublisher.offer` takes a `ReentrantLock` and is forbidden on the RT thread — publish into the ring or a preallocated structure and drain on the flush/FX side (story 311 finding; book §6.2).
- Engine/project divergence numbers verified: the project default is `AudioFormat.STUDIO_QUALITY` — 96 kHz / 256 frames (`AudioFormat.java:17`), set at project creation (`MainController.java:778`).
- Cross-refs: story **323** (prerequisite seam), **315** (transport-side synchronization), **342** (SRC badge surface), **337/338** (error/xrun surfacing), **132** (loop-record's landed RT compromise, fixed here).
- Research backing: SKILL `research-daw` §3 (real-time audio: lock-free hand-off, dedicated I/O threads, never block the callback).
