---
title: "Multi-Channel Input Capture Routing"
labels: ["bug", "recording", "audio-engine", "routing", "windows", "asio"]
---

# Multi-Channel Input Capture Routing

## Motivation

The primary platform is Windows + ASIO + a multi-channel USB interface, and the capture path cannot use it. `startAudioInputOutput` opens `format.channels()` input channels — the *project's* channel count, typically 2 — regardless of what the interface offers or what the armed tracks are routed to (`AudioEngine.java:510-517`); inputs 3+ can never be delivered. Only the **first** armed track's input-device index is used for the whole session (`TransportController.java:452-457`); every other armed track's device choice is silently ignored.

Worst of all is what happens to a track routed past the opened stream: `recordToSessions` skips the arraycopy when the source channel is out of range but **still records the reused routed scratch buffer** (`RecordingPipeline.java:781-790`) — a track on inputs 3-4 of a 2-in stream captures whatever was last left in that array, with no warning. Input metering silently skips the same out-of-range routings (`AudioEngine.java:977-983`), so the meters stay dark instead of diagnosing it (book §1.6).

For a studio engineer this kills the core tracking scenario: mic a drum kit across inputs 1-8, arm eight tracks, press Record — tracks 3-8 come back as stale-buffer garbage, and nothing on screen ever said so. Silent wrong audio is worse than a refused take: the failure is discovered after the performance is gone.

## Goals

- **Open width follows the armed routings, never the project format**: per device, the input stream opens with channel count ≥ the highest channel routed by any armed track on that device (book §5.4 "Open width"). The project channel count is never consulted for capture width.
- **Every armed track's input-device choice is honoured** (union open): one stream per distinct armed input device on backends that allow it; on single-device backends (ASIO), a routing to a non-active device **fails arm-time validation** with a visible error naming the track and the device — pretending otherwise records the wrong signal (book §5.4 "Device set").
- **Validation happens at arm time and record-start, not first-callback**: the performer learns before the take, not after (book §5.4 "Validation moment"). The check slots into the story-325 record-guard row ("routing validation passes (§5.4)", book §5.2).
- **Unsatisfiable routings are zeroed and flagged, never lied about**: if a device shrank between arm and start so a routing exceeds the opened stream, the track's capture is bit-identical silence, flagged in the take manifest, with a visible warning — the stale-scratch-buffer path (`RecordingPipeline.java:781-790`) is deleted (book §9.8: "Skip-but-record" is on the rejection list).
- **Input metering covers every opened channel**: an out-of-range routing surfaces as the same visible flag, never a silent skip (`AudioEngine.java:977-983`), extending the seam existing story 137 (input gain staging / clip indicators) builds on.

## Goals — Tests

- **Inputs 3+ record their own signal** (integration, primary platform): with an ASIO-capable 8-in multi-channel USB interface (or a class-compliant equivalent), a track routed to inputs 7-8 records its own signal — asserted with a stub/mock backend delivering distinct per-channel patterns, and verified manually on hardware. Tests must not assume a non-Windows environment.
- **Open-width computation test**: with armed tracks routed to channels {1-2, 7-8} on one device, the opened input width is ≥ 8; with no armed track past 2, the width is 2 — and in no case does `project.getFormat().channels()` decide it.
- **Per-track device honoured**: two armed tracks on different input devices open both devices (multi-stream backend), each capturing its own device's signal; under a single-device backend (ASIO), the second-device routing fails arm-time validation with an error naming the track and device.
- **Arm-time refusal test**: arming a track routed beyond the device's channel count fails at arm time — visible error, no transition toward RECORDING (story 325's guard row exercises this path).
- **Zero-and-flag test**: a device that shrinks between arm and record-start yields a zeroed, flagged track — the captured segment is bit-identical silence, the manifest carries the flag, and a warning was published; no byte of scratch-buffer content appears.
- **Metering parity test**: input metering spans the opened width, and an out-of-range routing raises the visible flag rather than being silently skipped.

## Non-Goals

- The routing *selection* UI — channel pickers, channel identity, and driver-reported channel names are owned by existing stories 092 (per-track audio I/O routing) and 215 (driver-reported channel names). This story defines capture-side truth; those stories the surface (book §5.4).
- The mixer-side session-level input-device selection and its mismatch warnings — story 322 (`AUDIO_ENGINE_WIRING_DESIGN_BOOK.md`), which explicitly defers multi-device *capture* to this story.
- Input monitoring modes — existing story 133 (not subsumed; this story **unblocks** it: the wider opened stream and per-track capture state are exactly the inputs its render-pipeline monitoring resolution needs, book §1.8/§5.6).
- Input gain staging and clip indicators themselves — existing story 137; this story only extends its metering seam across all opened channels.
- The capture-to-disk machinery the flag lands in — story 323 owns the flush service and `TakeManifest`; story 324 owns RT-safety and engine-format truth; story 325 owns the record state machine hosting the arm-time guard.
- Device identity resolution and the ASIO production stream — story 316 (`AUDIO_ENGINE_WIRING_DESIGN_BOOK.md`); this story inherits its device identity and open/close seam.
- CoreAudio/JACK backends — aspirational, non-primary platforms.

## Technical Notes

- **Implements Stage 4 of `docs/design/RECORDING_RELIABILITY_DESIGN_BOOK.md` — "Multi-Channel Input Capture Routing"** (§5.4 multi-channel routing contract, §1.6 critique, §9.8 rejection of skip-but-record).
- Files: `AudioEngine.java` (`:510-517` project-channel open width; `:977-983` metering skip), `TransportController.java` (`:452-457` first-armed-device-only), `RecordingPipeline.java` (`:781-790` skip-but-record deletion). Routing state lives per `TrackCapture` on the flush side (book §3.1/§4.2 — the callback stays one bounded copy of the raw device block; routing is a flush-thread act, per the book's raw-block-granularity decision).
- The zero-and-flag record lands in the `TakeManifest` sidecar introduced by story 323 (book §3.3); validation errors and warnings ride the notification seam — production injection is Book 4 story 339 (`FAILURE_SURFACING_DESIGN_BOOK.md`); until it lands this degrades to logging (book §6.3 ordering note).
- Arm-time and record-start validation is a guard input to story 325's `RecordCoordinator` (book §5.2, "Record pressed" row: "routing validation passes (§5.4)").
- Prerequisites: stories 323 (flush pipeline + manifest), 324 (RT-safe capture path), 325 (record state machine). Story 316 provides the ASIO production stream and stable device identity this story opens against.
- Unblocks/feeds: existing stories 133 (input monitoring), 137 (gain staging seam), 092/215 (selection surface); Stage 6 (story 328) per-lane takes become trustworthy per track (book §8 Stage 4 "Unblocks").
- Research backing: SKILL `research-daw` §3 (real-time audio I/O discipline); the union-open width rule mirrors how open-source DAW capture engines size input streams from armed-track routing rather than session format.
