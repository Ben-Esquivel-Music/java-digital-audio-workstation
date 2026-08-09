---
title: "RT-Safe Error Channel and Callback Guards"
labels: ["bug", "error-handling", "audio-engine", "real-time", "reliability"]
---

# RT-Safe Error Channel and Callback Guards

## Motivation

The two default streaming backends handle a render-path exception in the two worst possible ways, and every guard that does exist is a black hole:

- **PortAudio — the production default (`AudioBackendFactory.createDefault()` wired at `MainController.java:802`) — is one exception from JVM death.** The FFM upcall body calls `callback.process(...)` with no try/catch (`PortAudioBackend.java:448`). The callback target is `AudioEngine::processBlock`, registered raw at `AudioEngine.java:442` and `:519`, and it **throws `IllegalStateException` when `running` is false** (`AudioEngine.java:910-913`) — `stop()` flips `running` independently of stream teardown, so a stop racing an in-flight callback throws straight through the FFM upcall into the native frame. Any plugin or DSP `RuntimeException` in the render pipeline takes the same exit. An exception crossing an FFM upcall boundary kills the JVM — total loss of an unsaved session. The codebase already knows better one module over: the ASIO upcall wraps its body in a catch-everything guard precisely because the driver cannot recover from a Java stack unwind (`AsioBufferSwitchShim.java:364-371`).
- **JavaSound — the always-available fallback — dies silently and lies about it.** The render loop calls `currentCallback.process(...)` bare inside `while (streamActive)` (`JavaSoundBackend.java:265-275`) on an unwrapped virtual thread (`:155`). One DSP exception kills the thread; `streamActive` stays true, so the backend *reports* an active stream while producing silence forever. Nothing notices, restarts, or reports it.
- **The existing swallows are amnesiac.** The ASIO shim swallows `Throwable` in its upcall and drain publisher with no counter, health flag, or user signal (`AsioBufferSwitchShim.java:364-371`, `:515`), and `AudioWorkerPool` swallows every task `Throwable` with an empty catch (`AudioWorkerPool.java:216`) — a track whose inserts throw every block silently mixes a stale buffer, uncounted.
- **The fan-out choke points have no isolation.** `FxDispatcher.pulse()` (`FxDispatcher.java:443-461`) runs each keyed runnable (`:455`) and each continuous-channel drain (`:460`) bare — one persistently throwing consumer propagates onto the FX thread and starves the playhead, status cells, and every other channel, sixty times a second. `DefaultEventBus` does isolate subscriber throwables but routes them only to the invisible log (`DefaultEventBus.java:191`, `:352`).

For a studio engineer this means a single misbehaving plugin can end the session — either the whole application vanishes mid-take (PortAudio) or audio stops with the UI still claiming an active stream (JavaSound) — and lesser failures (a failing track render, a throwing meter consumer) degrade the session with zero trace. The book's principle §2.3 governs the fix: the RT thread records, it never surfaces — atomics and rings on the callback path, a drain on the FX pulse turning records into visible facts.

## Goals

- **PortAudio upcall guard.** Wrap the entire FFM upcall body — including buffer marshalling — in a catch-everything guard mirroring `AsioBufferSwitchShim.java:364-371`: on `Throwable`, zero the output block, record into the health record (failure code + counter bump), and return the stream-continue status. An exception must never cross into the native frame.
- **Engine stop-race fix.** `processBlock` on a stopped engine renders silence and returns — the `IllegalStateException` at `AudioEngine.java:910-913` stops being an exception path. A stop racing a live callback is normal teardown, not a fault: it produces no failure record and no surface (book §5.1 row 4 — claiming a fault that isn't one is also lying).
- **JavaSound per-iteration guard with honest exit.** Each loop iteration guards the callback invocation: one bad block zeroes output, records, and continues. Past a consecutive-failure threshold the loop exits *honestly* — stream marked inactive, plus a channel-B record the drain escalates to an "audio engine fault — restart?" surface. The thread never again dies with `streamActive` still true.
- **Counters behind the existing swallows.** The ASIO shim's upcall/drain `Throwable` swallows and `AudioWorkerPool`'s task catch each bump a named counter in the health record. The swallow was always RT-correct; the defect was the missing count.
- **The RT health record and error ring** (channel B of book §3.2): a small struct of atomics — per-category failure counters, last failure code, and a callback heartbeat timestamp (shared substrate for story 338's watchdog) — plus a fixed-size ring of recent failure descriptors, preallocated at stream open. Nothing on the RT side touches a `SubmissionPublisher`, a lock, or the logger.
- **The FX-pulse drain and escalation.** A drain on the `FxDispatcher` pulse reads the health record once per frame and converts deltas into facts: first failure of a fingerprint → WARNING toast; sustained failure → "audio engine fault — N callback errors" with a restart-engine action. Rate limiting and escalation follow book §6.2 (frequency raises prominence, not volume).
- **Pulse isolation.** `pulse()` wraps each keyed runnable and each continuous-channel drain in its own guard: log once per offender fingerprint, keep draining the siblings, route repeated offenders to the story-336 funnel. One throwing meter consumer never again starves the playhead.
- **Bus escalation.** `DefaultEventBus`'s existing per-subscriber isolation gains the same fingerprint counting, so a persistently failing subscriber becomes a visible fact instead of an invisible WARNING.

## Goals — Tests

- **RT purity gate (book §6.3 gate 3).** Extend the bytecode-sentinel idiom of the ASIO shim tests (`RealTimeSafeContractTest`): the callback-path guard classes and the health record must not reference `SubmissionPublisher`, `ReentrantLock`, logging, or collection iteration in RT methods — asserted for the PortAudio and JavaSound paths, not just ASIO.
- **PortAudio guard test:** injecting a throwing callback yields a zeroed output block, a counter delta, and the stream-continue status returned from the upcall — no `Throwable` escapes toward the native boundary.
- **Stop-race test:** a `stop()` racing an in-flight `processBlock` renders silence with no exception and **no failure record** — the not-a-fault row is part of the contract.
- **JavaSound guard test:** a throwing callback produces silence-and-continue with a counter delta; sustained consecutive failures make the loop exit honestly — `streamActive` false plus an escalation record — never active-and-silent. A transient failure (below threshold) leaves the stream running.
- **Worker-pool counter test:** a task that throws every block bumps the named counter each time; the drain aggregates the deltas and escalates past the sustained threshold instead of toasting per occurrence.
- **Pulse isolation test:** a keyed runnable and a continuous-channel drain that throw every frame do not prevent sibling channels from draining in the same frame; the offender is logged once per fingerprint; a persistent offender escalates to a visible fact.
- **Bus escalation test:** a persistently throwing subscriber becomes a surfaced fact at the threshold while other subscribers keep receiving events.
- **Drain escalation test:** first failure of a fingerprint surfaces a WARNING immediately; repeats within the window are counted silently; a repeat after the window re-surfaces carrying the accumulated count; sustained failure produces the persistent fault fact with a restart action.

## Non-Goals

- The watchdog, xrun feeding, engine-state cell, and clock surfaces — **story 338** (it reads the heartbeat and counters this story's health record provides).
- The exception spine, log sink, and notification funnel — **story 336** (prerequisite; this story's drain toasts land on that spine, and the pulse guards' once-per-fingerprint logging is only visible because of its file sink).
- Production notification injection into `DefaultAudioEngineController` / `MixerView` — **story 339**.
- Insert-chain copy-on-write, quiesce, and fault eviction — **story 340** (its RT-generation publication piggybacks on this story's health record).
- Honest PLAYING/RECORDING state when a stream fails to *open* — **story 317** (`AUDIO_ENGINE_WIRING_DESIGN_BOOK.md`); this story owns failures *during* streaming.
- RT-safety of the recording capture path — **story 324** (`RECORDING_RELIABILITY_DESIGN_BOOK.md`).
- The metering tap bus — **story 318**; taps and the health record are siblings on the same pulse drain, never one mechanism (book §7).
- `LoudnessMeter` RT rehabilitation — **story 318**.

## Technical Notes

- Implements **Stage 3 — RT-Safe Error Channel and Callback Guards** of `docs/design/FAILURE_SURFACING_DESIGN_BOOK.md` (§4.3 architecture, §3.2 channel B, §5.1 rows 3-5/11-12, §6.2 escalation, §6.3 gate 3). Book stage order is dependency order: 313, 336, **337**, 339, 338, 340 — this story lands after 336 and before 339/338.
- Files: `daw-core/.../core/audio/portaudio/PortAudioBackend.java` (`:448` upcall body), `daw-core/.../core/audio/AudioEngine.java` (`:910-913` stop-race; raw registrations `:442`/`:519`), `daw-core/.../core/audio/javasound/JavaSoundBackend.java` (`:155` thread, `:265-275` loop), `daw-sdk/.../sdk/audio/AsioBufferSwitchShim.java` (`:364-371`, `:515` counters), `daw-core/.../core/audio/AudioWorkerPool.java` (`:216`), `daw-app/.../ui/marshal/FxDispatcher.java` (`:443-461` pulse guards; continuous channels `:390`/`:410` are the drain's transport precedent), `daw-core/.../core/event/DefaultEventBus.java` (`:191`, `:352`). New health-record/ring types live in `daw-core` beside the audio path, annotated `@RealTimeSafe` on RT-facing methods.
- Repo rule (codified after the ASIO work): the RT thread never touches a `SubmissionPublisher` — `offer()` takes a `ReentrantLock`. Ring plus drain thread, enforced by the bytecode sentinel; SKILL `dawg-native-libs` documents the FFM upcall guard discipline.
- Completes the backend-callback half of existing story **128 — Crash-Safe Audio Thread Isolation** (the plugin-supervisor half landed); existing story **096 — Graceful MIDI Playback Degradation with User Notification** (landed) is the copy precedent for the drain's WARNING messages.
- Cross-references: story **336** (spine the drain surfaces onto), **338** (watchdog/xrun consumer of the health record), **339** (sink injection), **340** (RT generation rides the record), **317** (open-failure state truth), **318** (sibling tap-bus drain), **324** (capture-path RT safety).
- Research backing: `research-daw` §3 (real-time audio — lock-free hand-off; the audio thread never blocks on UI); `CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` §4.5 (the one marshalling seam the drain rides).
