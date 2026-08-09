---
title: "ASIO as the Production Streaming Path"
labels: ["bug", "audio-engine", "asio", "windows", "backend"]
---

# ASIO as the Production Streaming Path

## Motivation

The engine has two backend slots and only the legacy `NativeAudioBackend` slot streams: `startAudioOutput`/`startAudioInputOutput` open exclusively on it (`AudioEngine.java:442`, `:519`); the SDK slot — where the 310–312 ASIO stack lives — is consulted only for metronome `writeToChannel` routing. Selecting ASIO stores the `AsioBackend` on the SDK slot and installs (or keeps) a legacy backend on the streaming slot — the comment admits the render path stays `NativeAudioBackend`-driven "until the consolidation story" (`DefaultAudioEngineController.java:1234‑1251`) — while `getActiveBackendName()` reports the SDK slot's name (`:184‑191`): **the settings UI says "ASIO" while audio actually flows through PortAudio or Java Sound**. The real ASIO streaming path (`AsioBackend.open`, `AsioBackend.java:204`) has zero production callers; `sink()` is called only by SDK backends and tests.

Device selection is equally cosmetic: `startAudioOutput()` hard-codes device index 0 (`AudioEngine.java:397‑399`), `startAudioInputOutput` hard-codes output device 0 (`:510‑517`), and the resolved device index is consulted only inside `applyConfiguration` — never on a normal Play, so every Play after Stop reopens the first device.

For a studio engineer on the primary platform — Windows with an ASIO-capable multi-channel USB interface — this is disqualifying: they selected ASIO for its latency and channel count, the UI confirms it, and they are actually monitoring through a hidden fallback at unknown latency on a device they did not choose. The stack below the wire is finished (story 310 enumeration/lifecycle, 311 `bufferSwitch` streaming, 312 the full `ASIOSampleType` matrix) — it needs a caller, not a redesign (book §1.9).

## Goals

- **Give the 310–312 stack its production caller**: with ASIO selected, the engine controller actually opens and starts `AsioBackend` — engine `processBlock` output drives `sink()`, and capture reads `inputBlocks()`, over the story‑311 `bufferSwitch` bridge.
- **Consolidate streaming onto the SDK `AudioBackend` interface** (book §4.2): one open/start/stop seam for every backend; the cosmetic dual-slot arrangement is retired, so the reported active backend is — by construction — the backend of the open stream (reported = open, book §2.4).
- **The configured device is honoured on every open**: device identity is resolved from the stable device id per open (book §3.2) — never a hard-coded index 0, never only inside `applyConfiguration`. Play after Stop reopens the same device; a stale index (hardware changed) is a visible error, not a silent index‑0 open.
- **Backend switch and reconfigure route through the same seam**: changing backend, device, or buffer size closes/quiesces the old stream before the new open — no orphan streams, no parallel paths.
- **ASIO open failure produces honest state**: the fallback ladder (ASIO → PortAudio → Java Sound) is explicit and loud — each hop publishes a fallback event so requested ≠ active is always a visible fact, never a silent substitution.

## Goals — Tests

- **Production-caller test**: with ASIO selected, a controller-level test (stub/spy SDK backend behind the same `AudioBackend` interface) asserts `open`/start are invoked with the resolved device identity, the configured buffer size, and that render output flows into `sink()` and input is taken from `inputBlocks()`.
- **Device-persistence test**: select output device B, then Play → Stop → Play — the second open resolves device B; no code path opens index 0 as a default.
- **Honest-reporting test**: the reported active backend/device always equals the open stream's; when ASIO open fails and the ladder falls back, the reported active backend is the fallback *and* a fallback event has been published.
- **Reconfigure-seam test**: switching backend or buffer size closes the old stream before opening the new one, through the single seam (no orphan stream, no half-open state).
- **Windows streaming proof (integration, primary platform)**: with ASIO selected on Windows, Play produces audio through the ASIO driver at the configured buffer size on the chosen multi-channel USB interface, and the settings surface reports the backend actually streaming. Tests must not assume a non-Windows environment — Windows is the primary platform.

## Non-Goals

- Java Sound line-format correctness, Java Sound device-index support, and the transport's *refusal* to enter PLAYING/RECORDING when the stream fails to open — story 317 (Stage 4). This story makes the failure honest and visible; 317 makes the state machine refuse the fake transition.
- The ASIO stack itself — driver enumeration/lifecycle (story 310), `bufferSwitch` streaming (story 311), `ASIOSampleType` conversion (story 312) are landed prerequisites, untouched here.
- Headphone cue mix and click side-output routing — existing stories 135/136. This story unblocks them (`writeToChannel` finally targets an open stream, and `CueBusManager.renderCueBus` — complete `@RealTimeSafe` dead code needing capture/hand-off wiring, not missing DSP (book §1.9) — gains its hand-off point), but their wiring stays theirs.
- Per-track input monitoring modes — existing story 133 (gains a real device seam from this story).
- Multi-device / multi-channel input *capture* routing — story 326 (`RECORDING_RELIABILITY_DESIGN_BOOK.md`), which inherits this story's device identity.
- Output metering taps — story 318. The notification *surface* that renders fallback events as toasts — Book 4 story 339 (production notification injection); this story publishes the events on the existing seam.
- CoreAudio/JACK backends — aspirational, non-primary platforms.

## Technical Notes

- **Implements Stage 3 of `docs/design/AUDIO_ENGINE_WIRING_DESIGN_BOOK.md` — "ASIO as the Production Streaming Path"** (§4.2 backend consolidation, §3.2 backend truth, §5.2 backend & device contract).
- Files: `DefaultAudioEngineController.java` (`:184‑191` name reporting, `:1234‑1251` the admitted consolidation gap — this is "the consolidation story" that comment awaits), `AudioEngine.java` (`:442/:519` legacy-slot opens, `:397‑399/:510‑517` index‑0 defaults), `AsioBackend.java` (`:204` `open`), plus the device-identity resolution per book §3.2 (stable ids resolved per enumeration snapshot; bare indices only within one snapshot).
- Consolidation direction per book §4.2: adapt the legacy backends (PortAudio native, Java Sound) behind the SDK `AudioBackend` interface (or retire them) rather than teaching the legacy slot about ASIO — the SDK backend already carries the finished 310–312 stack, device events, and `writeToChannel`. Story 317 then fixes Java Sound's correctness on this consolidated seam.
- RT-safety of the `bufferSwitch` path was sentinel-tested in story 311 (the `RealTimeSafeContractTest` bytecode sentinel); this story adds routing and lifecycle, not new RT-thread work — keep it that way.
- The `dawg-native-libs` SKILL maps the asioshim/PortAudio FFM seams this consolidation touches (book Appendix B).
- Cross-refs: story 314 (Stage 1 — an audible graph to stream, prerequisite), stories 310/311/312 (the stack below the wire), story 317 (the fallback rung and honest states), existing 130 (Audio Backend Selection — its honest completion on the primary platform), existing 135/136/133 (unblocked as above), Book 2 stories 323/326/327 (inherit real device identity and stream lifecycle), Book 4 stories 338/339 (engine health and notification surfacing of the events this story publishes).
