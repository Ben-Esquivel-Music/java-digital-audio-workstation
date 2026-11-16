---
title: "Stop and Play Never Wait on the Engine's Lifecycle Lock Behind a Reopen"
labels: ["bug", "audio-engine", "concurrency", "javafx", "reliability"]
---

# Stop and Play Never Wait on the Engine's Lifecycle Lock Behind a Reopen

## Motivation

The transport's Stop and Play run on the FX thread and reach `AudioEngine.stopAudioOutput()` and `AudioEngine.startAudioOutput()` (`TransportController.stopAudioOutputWhenIdle()` and `startAudioOutputOrRefuse(String)`), and both take the engine's `lifecycleLock` with `lock()`. A reopen on another thread holds that lock across the whole device open: Restart Audio Engine's virtual thread (`daw-audio-engine-restart`), Settings ▸ Apply's `settings-audio-reconfigure` worker, a format-change or device-return reopen, and a take's preparation. A Stop or Play pressed during such an open therefore holds the FX thread — the playhead, the meters and every other pulse item — until the open ends. This is on `main` already. It was the Non-Goal of future story 453, which story 337's code review round resolved, and the round records it here (story 337, ruling E93).

The halt's own close, `AudioEngine.closeHaltedStream()`, never waits for the lock: it takes it with `tryLock` and answers `LIFECYCLE_BUSY` while another lifecycle step holds it (story 337, ruling E69). Once it holds the lock, though, it runs the ordinary stop transition, the device's `close()` included, on the FX thread, as the user's Stop does. Story 337 records that as a Known limit owned here.

## Goals

- **No FX frame waits on the lifecycle lock for a Stop or a Play.** A Stop or Play pressed while another thread holds the lock returns to the FX thread at once.
- **No FX frame waits for a device's close.** Neither the user's Stop nor the halt's close runs a device close on the FX thread.
- **The gesture still takes effect** on the stream that is open once the other lifecycle step ends, as the studio engineer rules; with the studio engineer, this story rules what Stop and Play mean while a reopen is in progress.
- **No stream is left open behind a Stop.**

## Acceptance Criteria

1. With a backend whose open blocks while another thread holds the lifecycle lock, a Stop and a Play each return on the FX thread without waiting for the open.
2. After the open ends, the transport and the stream are as the ruling says.
3. A Stop never leaves a stream open, and a halt's close still closes only the halted stream (story 337, ruling E69).
4. No device close runs on the FX thread, for the user's Stop or for the halt's close.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- A daw-app test: a Stop and a Play during a held open return at once, with the engine's lock held by a blocking open, as `AudioEngineOutputTest`'s and `AudioEngineRenderDriveHaltTest`'s fixtures hold it (`closingTheHaltedStreamNeverWaitsForAnOpenOnAnotherThread` is the precedent for the halt's close).
- A daw-app test: after the open ends, the transport and the stream are as ruled, and no stream is left open behind the Stop.
- A test that a backend whose `close()` blocks never blocks the FX thread through the user's Stop or the halt's close.
- `AudioEngineLifecycleLockContractTest` stays green, with any new acquirer on its lists.

## Non-Goals

- The halt rules themselves and what the halt notice says: story 337.
- The lock-region scan of `AudioEngineLifecycleLockContractTest`: future story 457.

## Technical Notes

- daw-app: `TransportController.stopAudioOutputWhenIdle()` and its callers (the user's Stop through `stopTransport`, and `finishPostRoll()`), `startAudioOutputOrRefuse(String)` and its callers (`start()` among them), `closeStreamAfterEngineHalt()` and `retryCloseStreamAfterEngineHalt()`.
- daw-core: `AudioEngine.stopAudioOutput()`, `startAudioOutput()` and `closeHaltedStream()`; the lock's Javadoc catalogue of acquirers and its budget; `AudioEngineLifecycleLockContractTest`'s `isAcquisition`, which counts `tryLock`.
- Skills: `javafx-application-design` "## 11. Threading" (never block the FX thread).
- Cross-refs: story 337 (rulings E69, E93 and E97; its Known limits); future story 453.

## Status

- Deferred
