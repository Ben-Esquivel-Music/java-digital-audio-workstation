---
title: "FFM Bridge for ASIOGetBufferSize and ASIOCanSampleRate Driver Capability Queries"
labels: ["bug", "audio-engine", "ffm", "native", "windows", "asio"]
---

# FFM Bridge for ASIOGetBufferSize and ASIOCanSampleRate Driver Capability Queries

## Motivation

Story 213 specified that `AsioBackend#bufferSizeRange(DeviceId)` and `AsioBackend#supportedSampleRates(DeviceId)` would call the driver's native API and return the actual driver-reported values, so the audio settings dialog can offer only the buffer sizes and sample rates the driver accepts. The Java side of `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/AsioBackend.java` ships with placeholders — `bufferSizeRange` returns the hardcoded `BufferSizeRange(64, 2048, 256, 64)` and `supportedSampleRates` returns the canonical `{44100, 48000, 88200, 96000, 176400, 192000}`. The Javadoc on both methods explicitly says: "The FFM implementation layer (story 130) probes `ASIOCanSampleRate` … the SDK-level default returns the canonical set so the dialog still shows the historical menu when the native shim is absent."

That FFM bridge does not exist anywhere in the repository. There is no source file under `daw-core/native/asio/`, no shim DLL in `target/dist/native/windows-x64/`, and no `SymbolLookup.libraryLookup("asioshim", ...)` call inside `AsioBackend` (only `AsioFormatChangeShim` references the shim, and only for the host-callback path). Result: on the user's primary platform (Windows + multi-channel USB ASIO), the audio settings dialog presents a fictional menu of buffer sizes and sample rates, the user picks one, and the driver returns `ASE_InvalidMode` because the dropdown never reflected what the driver actually supports — which is exactly the silent-fallback bug story 130 was designed to prevent.

This story closes the gap. Story 218's `AsioFormatChangeShim` already establishes the FFM-binding pattern (load `asioshim` library, look up entry-point symbols, downcall via `Linker#nativeLinker()`). The same pattern applies here.

## Goals

- Add a native shim source tree at `daw-core/native/asio/` (C/C++) that links the Steinberg ASIO SDK statically and exports four symbols:
  - `int asioshim_getBufferSize(int* min, int* max, int* preferred, int* granularity)` → wraps `ASIOGetBufferSize(...)` and returns `ASE_OK` (1) or `ASE_NotPresent` (0).
  - `int asioshim_canSampleRate(double rate)` → wraps `ASIOCanSampleRate(rate)`; returns 1 if the driver accepts the rate, 0 otherwise.
  - `int asioshim_getSampleRate(double* outRate)` → wraps `ASIOGetSampleRate`; used as a convenience for the controller after a driver-initiated reset (story 218).
  - `int asioshim_setSampleRate(double rate)` → wraps `ASIOSetSampleRate`; used when the user selects a new rate from the dialog.
- Build the shim into `target/dist/native/windows-x64/asioshim.dll` using the same Maven/CMake pipeline already established for `portaudio_x64.dll` (see `daw-core/native/`) so the produced DLL ships with the distribution.
- Implement `AsioBackend#bufferSizeRange(DeviceId)` to call `asioshim_getBufferSize` via FFM (`SymbolLookup.libraryLookup("asioshim", arena)`, downcall handle, MemorySegment for the four out-params) and return `BufferSizeRange(min, max, preferred, granularity)`. When the symbol is absent (e.g., the JVM runs on Linux/macOS, or the DLL was not bundled), keep the existing `BufferSizeRange.DEFAULT_RANGE` fallback path and log at `INFO` once per process.
- Implement `AsioBackend#supportedSampleRates(DeviceId)` to probe each entry of the canonical rate list against `asioshim_canSampleRate(double)` and return the set of rates for which the call returned 1; same fallback behavior as above.
- The FFM downcalls run on the calling thread (typically the JavaFX thread when the audio settings dialog opens), never on the audio render thread. The interaction with a currently-open ASIO stream is the same as story 218's reset path: capability queries are safe between open calls; mid-stream rate changes route through `selectClockSource` / driver-initiated reset, not through these capability queries.
- Tests:
  - A unit test loads `AsioBackend`, mocks the FFM symbol resolution to a stub that returns a known `BufferSizeRange(96, 384, 192, 96)`, and asserts `bufferSizeRange(deviceId)` returns exactly that range. The same test verifies the dropdown rendered by `AudioSettingsDialog` produces `{96, 192, 288, 384}` from the granularity.
  - A second test asserts that on a host where `asioshim` is absent, `bufferSizeRange` returns `BufferSizeRange.DEFAULT_RANGE` and `supportedSampleRates` returns the canonical fallback set, and that the absence is logged exactly once per process.
  - Integration test (Windows-only, gated by `Assumptions.assumeTrue(...)`) opens `AsioBackend` against the platform's default ASIO driver (e.g., the manufacturer-supplied USB driver) and asserts the returned ranges are non-empty and the preferred buffer size is within `[min, max]`.
- Update `AudioSettingsDialog` to call the (now-real) ASIO methods instead of the hardcoded values; if a persisted setting is no longer in the supported set, fall back to `preferred` and surface a `NotificationManager` warning per story 213's existing requirement.
- Add `asioshim.dll` to `daw-app/target/dist/native/windows-x64/` via the existing native-bundling assembly so end users do not need a separate install step.

## Non-Goals

- Distributing the Steinberg ASIO SDK source — link against it as a build-time dependency under the user's existing license.
- Per-driver workarounds for known buggy ASIO drivers (handled lazily as bugs are reported).
- WASAPI / CoreAudio / JACK capability queries — those are separate stories (the WASAPI/CoreAudio analogues already have non-empty default returns).
- Auto-tuning the buffer size for the user's CPU; the user picks from the driver-allowed set.

## Technical Notes

- Files: `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/AsioBackend.java` (replace placeholders), new `daw-core/native/asio/` source tree (CMakeLists + .cpp + ASIO SDK headers), Maven assembly to copy `asioshim.dll` into `target/dist/native/windows-x64/`, `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/AudioSettingsDialog.java` (persist + fallback path).
- The existing FFM pattern lives in `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/AsioFormatChangeShim.java` and `WasapiFormatChangeShim.java`; reuse the same `tryRegister` style with graceful no-op when the library is absent.
- Reference original story: **213 — Driver-Reported Buffer Size and Sample-Rate Enumeration**.
