---
title: "ASIO Real-Time Audio Streaming via bufferSwitch (ASIOCreateBuffers / ASIOStart / ASIOStop)"
labels: ["bug", "audio-engine", "native", "windows", "asio", "ffm", "real-time"]
---

# ASIO Real-Time Audio Streaming via bufferSwitch (ASIOCreateBuffers / ASIOStart / ASIOStop)

## Motivation

Story 310 makes the DAW able to enumerate, load, and initialise an installed ASIO driver; stories 212–224 expose its capabilities (buffer size, sample rate, clock source, channel names, control panel). But after all of that, **no audio actually flows over ASIO.** The streaming path in `com.benesquivelmusic.daw.sdk.audio.AsioBackend` is entirely a no-op:

- `open(DeviceId, AudioFormat, int)` calls `support.markOpen(...)` and constructs an `AsioFormatChangeShim`, then comments that "Native ASIO buffer-switch wiring lives in the implementation layer that ships the Steinberg ASIO SDK shim" — but that wiring does not exist.
- `inputBlocks()` returns `support.inputBlocks()`, a `SubmissionPublisher` that is never fed: nothing ever calls `AudioBackendSupport#publishInput(AudioBlock)` for the ASIO backend.
- `sink(AudioBlock)` only calls `support.validateOutgoing(block)` — it validates channel count and then drops the block on the floor; the samples never reach the driver's output buffers.
- `asioshim.cpp` has no `ASIOCreateBuffers`, `ASIOStart`, `ASIOStop`, `ASIODisposeBuffers`, and no `bufferSwitch` / `bufferSwitchTimeInfo` callback — the heart of any ASIO host.

The result: a Windows user can select their interface's ASIO driver, open its control panel, read its buffer size — and hear nothing, record nothing. "Full ASIO driver support" is impossible without the buffer-switch streaming loop. This story implements it: create the driver's double-buffered I/O buffers, install the `bufferSwitch` callback, start/stop the driver, and bridge each callback into the engine's `inputBlocks()` publisher and out of `sink()`.

## Goals

- Add the streaming exports to the `daw-core/native/asio/` shim (header-documented per the existing ABI convention):
  - `asioshim_createBuffers(const int* inputChannels, int numInputs, const int* outputChannels, int numOutputs, int bufferFrames)` — call `ASIOCreateBuffers` for the requested active channels with the host-callback set whose `bufferSwitch` / `bufferSwitchTimeInfo` slot routes into the shim's trampoline (the same pattern already used for `asioshim_messageTrampoline` in story 218).
  - `asioshim_start(void)` / `asioshim_stop(void)` wrapping `ASIOStart` / `ASIOStop`.
  - `asioshim_disposeBuffers(void)` wrapping `ASIODisposeBuffers`.
  - `installAsioBufferSwitchCallback(void* upcall)` / `uninstallAsioBufferSwitchCallback()` — register the JVM FFM upcall the shim invokes from each `bufferSwitch(long bufferIndex, long directProcess)`, mirroring `installAsioMessageCallback` from story 218.
- Bridge the buffer-switch callback into the Java backend on the **driver's** ASIO callback thread:
  - On each `bufferSwitch`, copy the active input channels' driver buffers for `bufferIndex` into an `AudioBlock` and publish it via `AudioBackendSupport#publishInput(AudioBlock)` so `inputBlocks()` subscribers (recording / monitoring) receive live input.
  - Pull the most recent block delivered to `AsioBackend#sink(AudioBlock)` and copy it into the active output channels' driver buffers for `bufferIndex`, then call `ASIOOutputReady()` when the driver advertises it. `sink(...)` must hand the block to a lock-free single-producer / single-consumer buffer (no allocation, no locking) so the callback thread never blocks — consistent with the project's real-time-safety conventions (`@RealTimeSafe`, lock-free ring buffers).
- Wire `AsioBackend#open(...)` to, after the story-310 driver load, call `asioshim_createBuffers(...)` for the opened `AudioFormat`'s channel set and `bufferFrames`, install the buffer-switch upcall, then `asioshim_start()`. Wire `close()` to `asioshim_stop()` → `asioshim_disposeBuffers()` → uninstall the upcall, in that order, before the story-310 `asioshim_unloadDriver()`.
- Honour the driver-reported preferred buffer size (story 213 / 220): `open(...)` uses the negotiated `bufferFrames`, and a buffer-size mismatch surfaces the same `AudioBackendException` style already used elsewhere in `AsioBackend` rather than silently resizing.
- Real-time safety: the FFM upcall body and `publishInput` path on the callback thread must not allocate, lock, or block (the existing `AudioBackendSupport#publishDeviceEvent` already documents the `offer()`-with-drop rationale for callback-thread safety; apply the same discipline to the input publish and output pull). Add or extend a contract test in the spirit of `RealTimeSafeContractTest` asserting the buffer-switch bridge method is real-time annotated.
- Integrate with the format-change path (story 218): a `kAsioBufferSizeChange` / `kAsioResetRequest` during streaming stops and disposes buffers cleanly before the backend reopens, so a driver-initiated reset does not race the running `bufferSwitch`.

## Goals — Tests

- A JVM-level test using a stub buffer-switch shim (injected via the same factory-hook pattern as `AsioBackend#setCapabilityShimFactory`) drives synthetic `bufferSwitch` callbacks and asserts: (a) each callback publishes exactly one `AudioBlock` of the opened channel count and `bufferFrames` length to `inputBlocks()`; (b) a block written to `sink(...)` is the one copied into the stub's output buffer on the next callback; (c) `open` → `start`, `close` → `stop` → `dispose` ordering holds.
- A Windows-gated integration test (`Assumptions.assumeTrue(NativeLibraryDetector.isAvailable("asioshim"))`, matching story 224's CI gating) opens ASIO4ALL against the system default device, starts streaming, confirms ≥1 real `bufferSwitch` fires within a timeout, and stops cleanly with no leaked driver state.

## Non-Goals

- Driver enumeration / load / `ASIOInit` / `ASIOExit` — owned by story 310 (prerequisite).
- Sample-format conversion between the driver's `ASIOSampleType` and the engine's float32 — owned by story 312; this story may assume a single format for its tests and let 312 generalise the conversion at the copy boundary.
- Changing `AudioBackend`, `AudioBlock`, `AudioFormat`, or `DeviceId` shapes.
- Round-trip latency reporting / compensation — owned by story 217; this story exposes the buffers, 217 measures the delay.
- Multi-driver simultaneous streaming — ASIO's single-driver-per-process model means exactly one driver streams at a time.
- macOS / Linux; x64 Windows only, consistent with story 224.

## Technical Notes

- Files: `daw-core/native/asio/asioshim.cpp` + `asioshim.h` (streaming exports + `bufferSwitch` trampoline), `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/AsioBackend.java` (`open` / `sink` / `close` + a new `AsioBufferSwitchShim` alongside `AsioFormatChangeShim.java`), `daw-sdk/.../AudioBackendSupport.java` (already exposes `publishInput`).
- `AsioFormatChangeShim` (story 218) is the canonical reference for the FFM upcall install / trampoline pattern; the `bufferSwitch` upcall follows the same `installX` / `uninstallX` / `asioshim_xTrampoline` shape.
- The lock-free single-producer/single-consumer hand-off for `sink(...)` → callback thread should reuse the project's existing ring-buffer / lock-free conventions (see story 128 — Crash-Safe Audio Thread Isolation, story 123 — Buffer-Underrun Detection).
- Research backing: `docs/research/audio-development-tools.md` (JAsioHost, relevance High) is the reference Java ASIO host whose `bufferSwitch`-driven streaming model this story mirrors; `docs/research/open-source-daw-tools.md` § Real-Time Audio Processing (lock-free audio thread, ring buffers, fixed-size blocks) describes the callback-thread discipline required here.
- Reference original stories: **310 — ASIO Driver Enumeration and Lifecycle** (prerequisite), **218 — Driver-Initiated Reset Request Handling** (upcall pattern), **130 / 219** (backend selection / instantiation), **128 / 123** (real-time thread isolation / underrun handling).
