---
title: "Record-State Integrity: Guards Against Lying States"
labels: ["bug", "recording", "transport", "reliability"]
---

# Record-State Integrity: Guards Against Lying States

## Motivation

The RECORDING state can be entered — and displayed — without any of its preconditions holding. Four shipped defects make the REC indicator a lie:

- **Double press orphans the take.** `onRecord` never checks transport state (`TransportController.java:399-413` validates armed tracks only); a second press while recording overwrites the `recordingPipeline` field with a new pipeline (`TransportController.java:443`) — the old pipeline's sessions never finalize — and reopens the live stream mid-take (`TransportController.java:457`). The code even documents a guard that does not exist: *"onRecord() is a no-op while already recording because of the state guard"* (`TransportController.java:891-895`), and the Record button is deliberately left enabled during recording (`TransportController.java:896-897`), so the destructive path is one click away. The MIDI side has the same bug: `startMidiRecording` re-puts a new recorder per track, replacing — and never stopping — the previous one, leaking the open MIDI device (`TransportController.java:775-778`).
- **Input-open failure records nothing, says Recording.** The catch around `startAudioInputOutput` shows an error toast but does not stop the already-started pipeline or transport (`TransportController.java:458-462`); lines `481-491` then set *"Recording — N tracks armed — auto-save active"* and light the REC indicator. Worse, a missing backend is a *successful* record start: `AudioEngine.startAudioInputOutput` with no backend logs *"recording without hardware I/O"* and returns success (`AudioEngine.java:500-503`).
- **Applying audio settings mid-record silently guts the take.** `applyConfiguration` unconditionally stops the stream and engine with no check for an active pipeline (`DefaultAudioEngineController.java:343-349`); the reopen is `startAudioOutput` — output-only, no input channels (`DefaultAudioEngineController.java:418-419`) — while the pipeline keeps appending the pre-allocated routed buffers each block (`RecordingPipeline.java:790`), growing the take with stale content under a live REC indicator.
- **MIDI devices without timestamps collapse the take.** `MidiRecorder` uses -1 as its uninitialized start-time sentinel and sets it from the device timestamp (`MidiRecorder.java:220,342-343`); the `javax.sound.midi` contract permits a device to deliver -1 forever, which keeps the sentinel and lands every note at column 0.

For a performer, a false RECORDING state is worse than a refused one: they sing a take to a dead pipeline and discover the loss afterwards. Book principle §2.4: the record state machine never lies.

## Goals

- Introduce `RecordCoordinator` (daw-app, FX thread) owning the book §3.2 record state machine — IDLE / COUNT_IN / RECORDING / FINALIZING / DEVICE_LOST / ABORTED — as the **only** mutator of record state, replacing `TransportController.onRecord`'s inline orchestration. Every transition follows the book §5.2 table.
- RECORDING is entered only after every precondition holds: ≥ 1 armed track, routing validation passes (today's validation this stage; the §5.4 width contract is story 326), the input stream opened, the capture ring is allocated, and the flush service is running. Any precondition failing → ABORTED → IDLE: full rollback of everything already started, in reverse start order, with a visible error naming the failed precondition; the transport stays STOPPED — never the fake-RECORDING state.
- Record double-press becomes **toggle-stop**: a second press in COUNT_IN or RECORDING transitions to FINALIZING and stops cleanly — one finalized take, never pipeline replacement (chosen over ignore-while-recording because a dead-feeling Record button reads as a bug; book §5.2).
- A missing backend is a hard record-start failure — *"recording without hardware I/O"* returning success (`AudioEngine.java:500-503`) is removed.
- Applying audio settings during RECORDING is **blocked** behind a "Stop the take and apply?" prompt; the apply proceeds only after FINALIZING completes (`DefaultAudioEngineController.java:343-349` gains the guard). Chosen over silent-defer (a deferred apply that fires later surprises) and over allow (the gutted-take bug).
- MIDI recorders follow the same machine: stop always drains `activeMidiRecorders` and closes devices; a second Record press can never re-put a recorder over a live one. Per-track MIDI device open failure skips that track with a visible warning; if *no* track opens, it is a Record guard failure.
- MIDI timestamp -1 sentinel: when a device delivers -1, event times fall back to a monotonic-clock capture at receipt, so notes land where they were played instead of collapsing to column 0.
- Every recording surface — REC indicator, transport Record button enablement, status-bar text, session status strip cell — renders the coordinator's machine and nothing else (book §6.5).
- The false state-guard comment (`TransportController.java:891-895`) becomes true, with a test behind it (book §9.10: a contract claim in a comment must have a test or must go).

## Goals — Tests

Host-level tests driving the transport (the in-process substitute pattern — `MainController` is never FXML-loaded in tests):

- A double-press test starts recording and presses Record again mid-take: exactly one finalized take, one clip set, and the input stream was opened exactly once — the second press stopped cleanly (toggle), never replaced the pipeline.
- An input-open-failure test records with an unopenable input: the transport ends STOPPED, the REC indicator is off, and an ERROR notification is visible naming the failure — never a lit REC over a dead pipeline.
- A no-backend test asserts a record attempt with no audio backend is a guard failure surfaced as an error — never a logged *"recording without hardware I/O"* success.
- A settings-apply test requests an audio-settings apply mid-take: the apply is refused until the user confirms stop; after FINALIZING completes the apply proceeds; declining leaves the take recording untouched.
- A MIDI-timestamp test feeds a -1-timestamp MIDI stream and asserts notes land at their played (monotonic-clock) positions, not column 0.
- A MIDI-lifecycle test asserts stop drains and closes every active MIDI recorder and device, and that a second Record press never leaks a live recorder or open device.
- A rollback-order test fails a late precondition (e.g. flush service refuses to start) and asserts everything already started is rolled back in reverse start order, ending in IDLE with a visible error.
- A surface-consistency test asserts REC indicator, Record button state, and status text all derive from the coordinator's state machine — no surface computes "recording" independently.

## Non-Goals

- **COUNT_IN gate behaviour** — the state exists in the machine here, but audible count-in, the transport-anchored MIDI window, and the capture-start gate are story 328 (Stage 6); until then the machine passes straight through COUNT_IN when the mode is Off.
- **DEVICE_LOST detection and rescue** — the state exists in the machine here; its feeds (backend events, watchdog) and the seal/rescue sequence are story 327 (Stage 5), consuming stories 316/337/338.
- **The FINALIZING fence internals** (deregister → drain → seal) and all RT-safety — story 324 (Stage 2); this story drives the fence, it does not build it.
- **Routing width/validation contract** (§5.4 union-open, arm-time named-track errors) — story 326 (Stage 4); this stage invokes today's validation as a precondition.
- **Production notification injection** — story 339 (`FAILURE_SURFACING_DESIGN_BOOK.md`); visible errors here use the transport's existing toast path, and the coordinator takes the notification seam so 339's injection lands without rework.
- **The settings apply contract generally** — `SETTINGS_VIEW_DESIGN_BOOK.md` owns the apply model; this story adds the mid-record block as a new precondition row in it (book §7).
- **Transport clock/loop/stop semantics** — story 315 (`AUDIO_ENGINE_WIRING_DESIGN_BOOK.md`).

## Technical Notes

- Implements **Stage 3 — Record-State Integrity: Guards Against Lying States** of `docs/design/RECORDING_RELIABILITY_DESIGN_BOOK.md` (§8). Contracts bound: §3.2 (the machine), §5.2 (the transition table — the reviewer checks every arrow), §2.4 (the never-lies principle), §6.5 (UI truth surfaces).
- Files to touch: `TransportController.java` (`onRecord`/`onStop` orchestration at `:399-492` extracted into `RecordCoordinator`; false comment `:891-895` corrected; MIDI re-put `:775-778` fixed), `AudioEngine.java` (`:500-503` no-backend success removed), `DefaultAudioEngineController.java` (`:343-349` mid-record apply guard), `MidiRecorder.java` (`:220,342-343` monotonic fallback).
- `RecordCoordinator` lives in daw-app on the FX thread (book §3.1); transitions happen only there; record-state facts publish as view-model properties via the `FxDispatcher` seam per `CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` — no surface polls capture internals.
- The status-strip record cell and the `EngineState` bridge consume this machine later (book §6.5; strip cell is Book 4 story 338's scope — this story supplies the record-state feed).
- Cross-refs: story **323** (ring/flush preconditions exist because of Stage 1), **324** (FINALIZING fence), **326** (routing validation widens), **327** (DEVICE_LOST goes live), **328** (COUNT_IN goes live), **339** (notification injection), **315** (transport semantics).
- Testing note: capture-and-rethrow FX assertions and headless-platform setup per the repo's JavaFX headless conventions; drive the coordinator through the in-process controller substitute, not FXML loading.
