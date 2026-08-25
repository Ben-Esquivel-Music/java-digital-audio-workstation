---
title: "Fallback Backend Correctness and Honest Engine State"
labels: ["bug", "audio-engine", "backend", "reliability"]
---

# Fallback Backend Correctness and Honest Engine State

## Motivation

The always-available fallback backend is broken on its own terms, and the transport lies about failure. `JavaSoundBackend` builds its line format with the five-argument constructor — 32-bit **PCM_SIGNED**, despite the inline comment "32-bit float" (`JavaSoundBackend.java:103-109`) — then writes raw IEEE-754 float bit patterns into it (`:307`). Either the line rejects the format, or it plays float bits as integer PCM: loud, potentially speaker-damaging noise. `isAvailable()` is unconditionally `true` (`:229`), and `openStream` never reads the configuration's device indices at all (`JavaSoundBackend.java:115`, `:121`) — device selection is silently ignored on the one backend every machine has.

Failure is then papered over instead of surfaced. `doPlay` catches the stream-open exception, shows a toast, and **proceeds to `transport.play()` anyway** (`TransportController.java:266-274`) — the UI enters "Playing…" with zero audio and a frozen playhead. The MIDI-only record branch is worse: it swallows the `startAudioOutput` failure with a log only, then calls `transport.record()` (`TransportController.java:472-479`) — RECORDING with no output stream and no error at all. The startup fallback log claims "playback will use UI timer only" (`MainController.java:804`) — no such timer advances the transport; the claim is false. And when the persisted audio configuration fails to apply at startup (interface off or unplugged), `applyStartupAudioSettings` catches the `RuntimeException` and only logs a WARNING (`MainController.java:1132-1134`) — a silent no-audio launch with nothing on screen.

For a studio engineer this is the floor of the product: on any machine without ASIO, the fallback either refuses to open or produces garbage; and every failure mode — wrong device, unplugged interface, unopenable line — converts into a fake "Playing…"/"Recording…" state instead of an actionable error. A recoverable hiccup becomes a mystery.

## Goals

- `JavaSoundBackend` encodes to the line's **actual** format: negotiate `PCM_FLOAT` where the line supports it; otherwise open a properly declared signed-PCM format and convert float32 samples to it (saturating clamp, correct byte layout). Float bit patterns are never written into an integer-encoded line.
- `JavaSoundBackend` opens the line on the **selected** `Mixer.Info` resolved from the configured device index; where device selection is genuinely unsupported it says so explicitly ("device selection not supported on this backend", per book §5.2) — never a silent ignore.
- `isAvailable()` reflects reality: a line supporting a negotiated format can actually be obtained.
- **Fail stopped, fail visible** (book §2.8): on stream-open failure, Play does **not** transition — the transport stays STOPPED, no time ticker starts, and a visible, actionable error (naming the backend/device, offering the audio settings as remediation) is shown. No code path sets "Playing…" without a running callback.
- The same refusal applies to Record's output-open: the MIDI-only record branch never enters RECORDING after `startAudioOutput` fails — stopped, with a visible error instead of a log-only swallow.
- Startup configuration failure surfaces: when `applyStartupAudioSettings` fails to apply the persisted configuration, a visible ERROR notification names the failed backend/device and offers "Open Audio Settings" — no more silent no-audio launch.
- The false "playback will use UI timer only" startup claim is deleted along with the behaviour it misdescribes.

## Goals — Tests

- **Format negotiation test**: on a line supporting `PCM_FLOAT`, the opened `AudioFormat` is float-encoded and a full-scale sine round-trips bit-exact; on a line supporting only signed PCM, the written bytes are correctly converted signed-PCM samples (full-scale-plus-epsilon clamps saturating, no wraparound) — an assertion proves no raw IEEE-754 bit pattern reaches an integer-encoded line.
- **Device selection test**: `openStream` resolves and opens the configured `Mixer.Info`; when the backend cannot honour a selection, the result is an explicit declared-unsupported outcome, not an index silently dropped.
- **Play refusal test**: `doPlay` against an engine stub whose stream open throws → transport state remains STOPPED, the wall-clock ticker is not started, no "Playing…" status is set, and a visible error with settings remediation is surfaced.
- **Record refusal test**: the MIDI-only record branch with a throwing `startAudioOutput` → transport never enters RECORDING and a visible error is shown (not log-only).
- **Startup failure surfacing test**: `applyStartupAudioSettings` with a controller whose `applyConfiguration` throws → a visible ERROR notification is posted naming the failure and offering the audio settings route (asserted via the notification seam, not the log).
- **Healthy-path regression**: with a stream that opens successfully, Play transitions to PLAYING and Record to RECORDING exactly as today.

## Non-Goals

- ASIO as the production streaming path, backend consolidation, and the explicit fallback ladder with published fallback events — story 316 owns them; this story fixes the ladder's last rung and the transition semantics below it.
- Wiring the engine to the live project (the null transport/mixer/tracks silence) — story 314.
- Production `NotificationManager` injection wherever `noop()` ships today, and the notification conformance harness — story 339 (`FAILURE_SURFACING_DESIGN_BOOK.md`); this story routes its errors through the surfaces already visible in production (e.g. the notification bar) and owns only the *refusal to transition*.
- Record-state integrity beyond the refused transition — the record double-press guard, input-open failure orphaning the take, and mid-record settings apply are story 325 (`RECORDING_RELIABILITY_DESIGN_BOOK.md`).
- Engine health surfaces (watchdog, xrun counter, `EngineState` stream UI) — stories 336/338; they build on the honest states this story establishes.

## Technical Notes

- Implements **Stage 4 — Fallback Backend Correctness and Honest Engine State** of `docs/design/AUDIO_ENGINE_WIRING_DESIGN_BOOK.md` (§4.2 backend consolidation — "Java Sound is corrected, not blessed"; §5.2 backend & device contract; §2.8 fail stopped, fail visible; rejection-list items 8 and 9).
- Files: `daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/javasound/JavaSoundBackend.java` (format negotiation, mixer selection, `isAvailable`); `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/TransportController.java` (`doPlay` `:266-274`, the MIDI-only record branch `:472-479`); `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java` (`:804` false log, `applyStartupAudioSettings` `:1132-1134`).
- The float→signed-PCM conversion mirrors the saturating-clamp house style established by story 312's `ASIOSampleType` matrix — reuse its conventions (clamp, no wraparound) rather than inventing a second scaling idiom.
- Java Sound remains the **last rung**, not the default experience (book §4.2); story 316's ladder decides when it is reached. Reported active backend/device always equals the open stream's (book §3.2).
- Cross-references: story **314** (stage 1 — makes the opened stream carry the project), **316** (stage 3 — backend consolidation above this rung), **325** (record-state integrity), **336/338/339** (failure surfacing and health), existing **130** (Audio Backend Selection — its honest floor on machines without ASIO).
- **Review routing:** findings inside `JavaxSoundBackend` internals (line lifecycle, capture-thread lifecycle, format/mixer handling) raised after story 316 merges are owned here — see 316's "Review routing" note; the PortAudio rung's equivalent is story **349**.
