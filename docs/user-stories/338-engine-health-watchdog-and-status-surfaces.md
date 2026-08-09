---
title: "Engine Health: Watchdog, Xrun Counter, Clock and State Surfaces"
labels: ["bug", "error-handling", "audio-engine", "reliability", "ui"]
---

# Engine Health: Watchdog, Xrun Counter, Clock and State Surfaces

## Motivation

An entire engine-health layer exists in the tree with zero production wiring — dropouts, stalls, and a dead audio engine are all invisible to the user:

- **No watchdog exists anywhere.** A grep for "watchdog" across all main source returns zero hits; the only heartbeat in the codebase guards the project lock file (`ProjectLockManager`), not the engine. Nothing detects "the callback has not run for N ms while the stream is nominally open" — which is exactly the observable signature of a dead PortAudio upcall, a silently-dead JavaSound loop, a stalled ASIO driver, and a physically unplugged interface alike.
- **Xruns are never detected, counted, or reported.** `XrunDetector` is constructed and rebuilt on every reconfigure, but there are zero production callers of `beginTick`/`recordTick`/`reportDropped`/`reportGraphOverload` — the render loop never reports timing into it. Its event stream is exposed (`DefaultAudioEngineController.java:466-467`) with no subscriber, and the story-123 `XrunCounterLabel`/`XrunLogDialog` exist only as a javadoc mention (`XrunDetector.java:105-111`). Worse, the detector's own publish path is not RT-safe: `recordTick` calls `SubmissionPublisher.offer(...)` inline on the caller thread (`XrunDetector.java:203-208`), whose `offer` acquires a `ReentrantLock` — the javadoc's claim that the audio thread "never blocks" (`:33-36`) is false, so it cannot simply be fed from the callback as it stands.
- **Engine state has no UI.** `setEngineState` offers every transition — RUNNING, RECONFIGURING, DEVICE_LOST, STOPPED — to a publisher (`DefaultAudioEngineController.java:661-672`, accessor `engineStateEvents()` at `:481-483`); grep finds no production subscriber. Transport silence never has a visible cause.
- **The clock-lock surface is finished and dead.** `ClockStatusIndicator` — a complete control with a notification hook and a recording-pause callback (`ClockStatusIndicator.java:105`) — is never instantiated in main source. The only `ClockLockEvent` publisher anywhere is the mock backend's test fixture (`MockAudioBackend.java:451`); the `AudioBackend` default is an empty publisher (`AudioBackend.java:568`) that no real backend overrides.

The studio-engineer consequence: a mid-take dropout leaves no evidence, a dead engine looks identical to a project that renders silence, and an external-clock rig losing lock records garbage with no warning — on the primary Windows + ASIO + multi-channel USB target where clocking and USB stalls are routine facts of life.

## Goals

- **Callback-heartbeat watchdog.** The story-337 render guards already bump a heartbeat timestamp in the RT health record (one wait-free atomic store). A low-priority daemon monitor — owned by the engine controller, rebuilt on reconfigure — checks the gap while a stream is nominally open; a gap exceeding a few buffer periods (scaled from the active format, with a floor for scheduling jitter) is a stall verdict that drives the **existing** reaction machinery: engine state → DEVICE_LOST, the controller's notification (visible via story 339's injected sink), and a restart offer. The watchdog *observes* the callback; it never runs on it. It is deliberately backend-agnostic — it detects PortAudio upcall death, JavaSound loop death, ASIO driver stalls, and device disappearance identically, including on backends offering no error callback at all.
- **XrunDetector publish side made RT-pure first.** Tick recording keeps only atomics on the caller thread; event *publication* moves off the hot path (drained by the same monitor/pulse machinery, or offered only from non-RT callers). The `:33-36` javadoc claim becomes true before anything feeds it.
- **Then feed it:** elapsed-time ticks recorded around the render body, dropped buffers reported from the story-337 guards, graph overloads from the worker pool's new failure counters.
- **Xrun counter surfaced:** the story-123 counter lands as a cell in the story-295 session status strip, with click-through to the xrun log dialog.
- **Engine-state cell:** a status-strip cell subscribes to `engineStateEvents()` via the dispatcher — RUNNING quiet, RECONFIGURING informational, DEVICE_LOST in the warning-accent token treatment (tokens, never raw hex). Transport silence permanently gains a visible cause.
- **Clock-lock surfaces live:** mount `ClockStatusIndicator` in the transport/status area bound to the active backend's `clockLockEvents()`, and implement the ASIO-side `ClockLockEvent` publisher in the shim (resync-request / sample-position-not-advancing detection) — so external-clock studios get the lost-lock warning and recording auto-pause the control was built for.

## Goals — Tests

- **Watchdog stall test:** killing the mock/JavaSound callback mid-stream flips the engine-state cell to DEVICE_LOST and raises the (now-visible) device-lost notification within the watchdog window, with a restart offer.
- **Watchdog no-false-positive test:** healthy streaming at the largest supported buffer size never trips the verdict (threshold scales with format, jitter floor respected), and a nominally *closed* stream is never watched — stopping the transport raises nothing.
- **Xrun publish RT-purity test:** extend the bytecode-sentinel idiom — the tick-recording path references no `SubmissionPublisher`, `ReentrantLock`, or logging on the caller thread; publication happens only off-RT.
- **Xrun feed test:** forced-late blocks increment the status-strip counter and populate the xrun log dialog; a dropped-buffer report from a story-337 guard and a graph-overload report from the worker counters each count too.
- **Engine-state cell test:** the cell tracks RUNNING → RECONFIGURING → RUNNING through a settings apply, and shows the warning-token treatment on DEVICE_LOST.
- **Clock-lock test:** a mock clock-unlock event pauses an active recording and shows the indicator's warning state; relock clears it and surfaces the recovery per the indicator's existing contract.
- **Injected-sink test:** the watchdog's DEVICE_LOST notification is asserted through a real injected `NotificationManager` — never `noop()` — proving the story-339 dependency direction end-to-end.

## Non-Goals

- The RT health record, heartbeat store, callback guards, and worker-pool counters — **story 337** (prerequisite; this story reads that substrate).
- Production notification injection — **story 339** (prerequisite; Book 4's stage order runs 339 *before* this story because the watchdog drives controller notify() calls that are discarded by `noop()` until 339 lands).
- OS/backend device-change detection (`DeviceArrived`/`DeviceRemoved` producers) — **story 327** (`RECORDING_RELIABILITY_DESIGN_BOOK.md`), per the story-214 split; this watchdog is deliberately the detection-independent backstop, not a hot-plug watcher.
- Honest PLAYING/RECORDING state when the stream fails to *open* — **story 317** (`AUDIO_ENGINE_WIRING_DESIGN_BOOK.md`).
- Meter/analyzer feeds — **stories 318/319**; the health drain and the metering tap bus are siblings on the same pulse, never one mechanism (book §7).
- Clock-source *selection* UI — existing story **216** (this story supplies the lock-status surfaces it needs); driver-initiated reset notices — existing story **218** (landed; its notices fold into this one health picture rather than growing a parallel surface).

## Technical Notes

- Implements **Stage 5 — Engine Health: Watchdog, Xrun Counter, Clock and State Surfaces** of `docs/design/FAILURE_SURFACING_DESIGN_BOOK.md` (§4.4 architecture, §5.3 health-fact table, §6.2 escalation). **Stage order deviation (deliberate):** Book 4's stages run 313, 336, 337, 339, **338**, 340 — this story lands *after* 339 because its watchdog notifications are noop until the sink is injected, and *after* 337 because its heartbeat, drop reports, and overload counts are story-337 substrate.
- The engine-state accessor is **`engineStateEvents()`** (`DefaultAudioEngineController.java:481-483`) — not `engineStates()`.
- Files: `daw-core/.../core/audio/performance/XrunDetector.java` (`:203-208` publish path made RT-pure; `:33-36` javadoc), `daw-app/.../ui/DefaultAudioEngineController.java` (watchdog ownership + rebuild-on-reconfigure; streams at `:466-467`, `:481-483`; state transitions `:661-672`), `daw-app/.../ui/ClockStatusIndicator.java` (`:105` — mount it), `daw-sdk/.../sdk/audio/AudioBackend.java` (`:568` default publisher), `daw-sdk/.../sdk/audio/AsioBufferSwitchShim.java` (ASIO `ClockLockEvent` publisher, off the RT drain thread), the story-295 status strip (xrun + engine-state cells), `daw-app/.../ui/marshal/FxDispatcher.java` (drains; counters are continuous channels, transitions are events per the `CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` signal taxonomy).
- The `ProjectLockManager` heartbeat is the in-tree precedent for the watchdog shape (`PROJECT_MANAGER_DESIGN_BOOK.md`); one heartbeat substrate serves every backend — per-backend bespoke stall detectors are book §9.14 rejects.
- Completes existing story **123 — Buffer-Underrun Detection and Reporting** (detector landed; feeding and the counter UI were the missing half). Serves existing story **216 — Hardware Clock Source Selection**; folds existing story **218 — Driver-Initiated Reset Request Handling** (landed) into the same health picture. Cross-references: **337** (substrate), **339** (sink), **327**/**214** (detection half), **317** (open-failure truth), **314** (a silent Play now points at engine state instead of guesswork).
- Research backing: `research-daw` §3 (real-time engine monitoring and engine/UI separation); SKILL `dawg-native-libs` (ASIO shim drain-thread discipline for the clock publisher).
