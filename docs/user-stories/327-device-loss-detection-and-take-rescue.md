---
title: "Device-Loss Detection and Take Rescue, Live"
labels: ["bug", "recording", "audio-engine", "reliability", "windows"]
---

# Device-Loss Detection and Take Rescue, Live

## Motivation

Story 214 landed a complete device-loss reaction chain: `onDeviceRemoved` stops the engine, flushes the `IncompleteTakeStore`, enters `DEVICE_LOST`, and notifies (`DefaultAudioEngineController.java:676-708`); `onDeviceArrived` reopens and resumes (`:711`). None of it can run in production (book §1.7):

- The **only** publisher of `DeviceRemoved`/`DeviceArrived` in main source is the mock backend's test fixture (`MockAudioBackend.java:190-192`). The live PortAudio/JavaSound path emits no device events, nothing polls stream health, and the OS-level device-change hooks are no-op stubs.
- `captureRecordingFrames` — the only feeder of `IncompleteTakeStore` — has exactly one caller, a test (`DefaultAudioEngineController.java:652-654`; `DefaultAudioEngineControllerTest.java:196`). Its javadoc's claim that *"production wiring … calls this from the audio callback"* is false, so the rescue flush at `:699` always flushes an empty store.
- Production constructs the controller via the 2-arg constructor (`MainController.java:806`), which injects a **no-op notification sink** and roots the take store at the OS temp directory (`DefaultAudioEngineController.java:148-152`) — even if events fired, *"Audio device disconnected"* goes nowhere and the rescue WAV lands outside the project.
- `EngineState` promises a *"Reconnecting…"* transport indicator in its javadoc (`EngineState.java:8-9`); no production class subscribes to `engineStateEvents()` (the accessor at `DefaultAudioEngineController.java:481-483`; `AudioEngineController.java:320` and `DefaultAudioEngineController.java:481` are the only references).

The irony the book calls out: `IncompleteTakeStore` is the one class in the whole capture path that actually knows how to write a WAV file (`IncompleteTakeStore.java:130-155`) — and it is never fed. For a studio engineer, a USB cable snagged mid-take is today an *undetected total loss*: the performer keeps playing to a dead rig, no indicator changes, and nothing was on disk to salvage. USB interfaces get unplugged; a recorder that only works when hardware never fails does not meet the session invariant (book §2.1, §2.8).

## Goals

- **Device-loss detection is live in production**: the capture path consumes real detection feeds, in the book's preference order (§5.5) — backend device events (ASIO, live once story 316 opens the backend in production) first; the OS device-change watcher and the callback-heartbeat watchdog (Book 4 story 338) for event-less backends. This story consumes those signals; it does not implement watchers (book §6.3).
- **The §5.5 rescue sequence runs end-to-end** on `DeviceRemoved` (or a watchdog-declared stall) while RECORDING, each step completing regardless of later-step failure: ring drains → all active segments sealed, manifest marked `sealed-by: device-loss` → rescued take registered → state → DEVICE_LOST, REC indicator off, status strip shows the cause → notification names the device, says "take preserved", and offers review.
- **`IncompleteTakeStore` is repurposed as the rescued-take registry** (book §2.9): project-rooted, fed by the flush service's seal path — rescue *is* the ordinary seal applied early, never a second in-RAM copy of the audio. The never-called `captureRecordingFrames` RAM feeder (`DefaultAudioEngineController.java:652`) is retired, and the OS-temp rooting of the production constructor (`:148-152`) goes with it.
- **Reconnect is user-driven and identity-matched**: `DeviceArrived` matching the lost device's identity offers resume with the armed set preserved — recording never restarts itself (book §5.2/§5.5). The reopen path keeps existing story 218's reset-ordering discipline.
- **DEVICE_LOST is visible** per book §6.5: the record-state machine feeds the `EngineState` bridge and the status strip so the promised *"Reconnecting…"* indicator becomes real (the strip cell itself is Book 4 story 338's scope; the record-state feed is this story's).
- **The same rescue grammar recovers crash orphans**: a `.part` segment plus an unsealed manifest reconstructs a playable RESCUED take registered for review (book §4.7) — one rescue grammar for unplug, crash, and power loss. (Invoking the scan on every project-open path is story 332's scope.)

## Goals — Tests

- **End-to-end rescue test** (mock backend event fixture): publish `DeviceRemoved` mid-take — within the detection window the take is sealed on disk under the project, the rescued take is registered in the project-rooted `IncompleteTakeStore`, a notification names the device and offers review, the status strip shows the loss, and the REC indicator is off.
- **Hardware proof** (manual, primary platform): yank the ASIO-capable multi-channel USB interface mid-take on Windows — same observable sequence; replugging offers resume. Automated tests must not assume a non-Windows environment.
- **Identity-match test**: a *different* device arriving does not trigger reopen — state stays DEVICE_LOST and the remediation notification repeats (book §5.2 "Device returned" row).
- **No-auto-restart test**: after a matching `DeviceArrived` and successful reopen, the machine returns to IDLE with the armed set preserved; recording resumes only on an explicit user action.
- **Crash-grammar test**: a simulated crash leaving a `.part` segment and an unsealed manifest reconstructs a playable RESCUED take (sample count derived from file length per book §3.4) registered with the store.
- **Registry-role test**: `IncompleteTakeStore` is fed only by the flush service's seal path; `captureRecordingFrames` no longer exists (or has zero callers pending deletion is not acceptable — the method is removed and its test migrated); the store's root is inside the project directory, never OS temp.
- **Step-independence test**: a failure injected at the notification step still leaves the take sealed and registered (each §5.5 step completes regardless of later-step failure).

## Non-Goals

- **Implementing detection machinery** — the OS device watcher and callback-heartbeat watchdog are Book 4 story 338 (`FAILURE_SURFACING_DESIGN_BOOK.md`); the RT-safe error channel is story 337; this story consumes them by contract (book §6.3).
- **Production notification injection** — Book 4 story 339 replaces the no-op sink app-wide; this story's messages ride the injected manager. End-to-end reachability of this story's flow genuinely requires 337/338/339 and/or story 316's backend events (book §6.3 ordering note) — until they land, detection degrades to the mock-fixture-driven test path plus logging.
- **Opening the ASIO backend in production** and its native device events — story 316 (`AUDIO_ENGINE_WIRING_DESIGN_BOOK.md`).
- **The seal machinery itself** — story 323 built the flush service, segment writer, and atomic seal; this story applies that seal early and registers the result.
- **Recovery-scan invocation on every project-open path** — story 332 (`PERSISTENCE_INTEGRITY_DESIGN_BOOK.md`); this story provides the grammar and the registry it populates. Take persistence in the project file — story 334.
- **The hot-plug reaction chain's internals** — existing story 214 landed them; this story completes its *detection* half and gives its flush step real content. Driver reset-request handling — existing story 218's ordering discipline is retained, not redesigned.

## Technical Notes

- **Implements Stage 5 of `docs/design/RECORDING_RELIABILITY_DESIGN_BOOK.md` — "Device-Loss Detection and Take Rescue, Live"** (§5.5 device-loss and rescue contract, §2.8/§2.9 principles, §4.7 recovery grammar, §6.5 UI truth surfaces).
- Files: `DefaultAudioEngineController.java` (`:676-744` reaction chain gains its feed; `:652-654` `captureRecordingFrames` retired; `:148-152` constructor's no-op sink + tmpdir rooting fixed), `MainController.java:806` (production construction), `IncompleteTakeStore.java` (`:28-35` on-disk contract, `:130-155` WAV writer — kept, re-rooted, registry role), `MockAudioBackend.java:190-192` (remains the CI event source for the end-to-end test).
- The engine-state stream accessor is **`engineStateEvents()`** (`DefaultAudioEngineController.java:481-483`) — not `engineStates()`; the DEVICE_LOST/reconnect bridge subscribes to it.
- Rescue rides the story-323 pipeline: seal = flush remaining frames → force → patch header → atomic rename → manifest `sealed-by: device-loss` (book §3.4, §5.5 steps 1-2 on the flush thread, steps 3-6 marshalled to FX via the existing `FxDispatcher`/`EventBus` seams, book §6.1).
- DEVICE_LOST is a state of story 325's `RecordCoordinator` machine (book §3.2) — this story plugs detection into an existing machine rather than inventing states (book §8 Stage 3 "Unblocks").
- Prerequisites: 323 (seal path), 324 (bounded rescue window — ring depth + force cadence, book §2.1), 325 (state machine). Cross-refs: 316 (ASIO events), Book 4 336-339 (consumed mechanisms), 332/334 (Book 3 hand-offs), existing 214 (detection half completed) and 218 (reopen ordering).
- Research backing: SKILL `research-daw` §3 — hot-plug robustness and never-block-the-callback discipline on device teardown.
