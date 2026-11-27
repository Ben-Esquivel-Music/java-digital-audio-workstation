---
title: "A Stream That Opens on a Fallback Device Tells the Person"
labels: ["bug", "audio-engine", "notifications", "reliability"]
---

# A Stream That Opens on a Fallback Device Tells the Person

## Motivation

Story 316's provision ladder opens the configured device's rung first. When that open fails, it opens the fallback rungs: PortAudio's default device where PortAudio is available, then Java Sound's default device (`DefaultAudioEngineController.appendFallbackRungs`). An open on the configured device fails, for instance, when an ASIO driver does not accept the saved buffer size (`AsioBackend.requireAcceptedBufferSize`) or cannot be loaded. For each failed rung, `AudioEngine.openLadder` logs the WARNING "Backend ladder hop failed: <backend> could not open device '<device>'" with the failure as its cause, and walks on. `AudioEngine.publishFallbackEvents` publishes one `BackendFallbackEvent` per failed rung on the application's event bus, which the session journal records (`JournalEventRecorder`); daw-app's production code has no subscriber for it. The notice "<backend> not available — falling back to <backend>" (`DefaultAudioEngineController.rejectedProvision`) is the provision gate's. It is shown when the provision is built, for a requested backend that is unavailable or cannot stream; an open that fails gets no notice.

So a Play whose open on the interface fails sounds on the computer's default output, and nothing on screen says so. The clock badge is hidden while that stream runs (story 338, ruling E145), and the session status strip's Engine cell shows nothing for a running stream. Audio Settings' "Active backend" readout names the fallback's backend while it streams, but only for a person who opens Audio Settings to look. Story 316 publishes the events and leaves the surface that shows them to story 339 (its Non-Goals), whose Goals do not name them. A Record walks the same ladder. On the PortAudio rung its capture is PortAudio's own default input (`appendFallbackRungs`); which input channels an armed track records from on that rung was not traced.

## Goals

- When a Play's or a Record's open on the configured device fails and a fallback rung carries the stream, the person is told at that open, on screen, which device carries the stream in place of the configured one and why the configured one did not open. The notice uses words that name the device and the setting (story 339's notification discipline), never an engine message or a class name (future story 471).
- For a Record, the notice also names the input the take records from.
- A failed rung keeps its WARNING and its `BackendFallbackEvent` (story 316).

## Constraints

- A fallback that the provision gate announced keeps the gate's notice, and the same open is not announced twice.
- An open that fails on every rung keeps today's notices.
- The notice is shown on the JavaFX thread, never from the audio callback thread, and the open waits for nothing new.
- The ladder's rungs and their order stay story 316's.

## Acceptance Criteria

1. ASIO configured, a driver that does not accept the saved buffer size, and PortAudio's default device opens: at Play one notice says that playback runs on PortAudio's default device in place of the interface, because the interface did not accept the buffer size.
2. The same at Record: the notice also names the input the take records from.
3. An open on the configured device: no notice, as today.
4. A requested backend that the provision gate refused: the gate's notice alone, as today.
5. On hardware (the owner's): the first Play of story 338's owner's checks, at a buffer size the driver does not accept, shows the notice.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- A daw-app test with a controller whose configured rung's backend fake throws at the open and whose fallback rung opens: the window's notification sink gets one notice naming both devices and the cause in words.
- The same for a Record: the notice names the input.
- A test whose configured rung opens: no notice; and one whose requested backend the gate refused: the gate's notice alone.
- `AudioEngineOutputTest`, `Story316StreamingSeamTest`, `DefaultAudioEngineControllerTest` and `TransportControllerTest` stay green.

## Non-Goals

- The provision gate's notice naming the recording input: future story 351.
- The ladder's rungs and their order: story 316.

## Technical Notes

- daw-core: `AudioEngine` (`openLadder`, `publishFallbackEvents`), `StreamingProvision`.
- daw-app: `DefaultAudioEngineController` (`appendFallbackRungs`, `rejectedProvision`), `TransportController`, `RecordCoordinator`, the window's notification sink.
- daw-sdk: `BackendFallbackEvent`, `AsioBackend.requireAcceptedBufferSize`.
- Cross-refs: stories 316, 317 and 339; story 338 (ruling E145 and its owner's checks); future stories 351 and 471.

## Status

- Deferred
