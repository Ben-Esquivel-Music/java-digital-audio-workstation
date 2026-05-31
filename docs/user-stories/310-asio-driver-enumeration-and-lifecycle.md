---
title: "ASIO Driver Enumeration and Init/Exit Lifecycle (asioshim_listDrivers / loadDriver)"
labels: ["bug", "audio-engine", "native", "windows", "asio", "ffm"]
---

# ASIO Driver Enumeration and Init/Exit Lifecycle (asioshim_listDrivers / loadDriver)

## Motivation

Stories 130 / 212–224 / 256–259 build the entire ASIO capability surface — buffer-size and sample-rate enumeration (213 / 220), the driver control panel (212 / 221), clock-source selection (216 / 222), channel-name enumeration (215 / 223), the format-change host callback (218), and the `asioshim.dll` build/bundle pipeline (224 / 256). Every one of those exports — `asioshim_getBufferSize`, `asioshim_canSampleRate`, `asioshim_getClockSources`, `asioshim_getChannelInfo`, `asioshim_openControlPanel` — calls straight into the Steinberg SDK's `ASIOGetBufferSize` / `ASIOCanSampleRate` / etc. **as though a driver were already loaded and `ASIOInit` already called.** Nothing in the repo ever loads a driver:

- `com.benesquivelmusic.daw.sdk.audio.AsioBackend#listDevices()` returns `List.of()` — an empty list — so the user can never even see, let alone select, an installed ASIO driver.
- `asioshim.cpp` forward-declares only the per-call SDK entry points; it has no `asiodrivers.cpp` / `AsioDrivers` glue to enumerate the registry (`HKEY_LOCAL_MACHINE\SOFTWARE\ASIO`), instantiate a driver's COM object, or call `ASIOInit(ASIODriverInfo*)` / `ASIOExit()`.
- Because no driver is ever bound, every capability export silently hits the SDK's "no driver loaded" path and the Java layer takes its graceful-absence branch — exactly the behaviour the capability stories were written to avoid.

On the project's primary platform (Windows + a multi-channel USB interface), the user's installed ASIO driver (the device's own driver or ASIO4ALL) is invisible to the DAW. This story supplies the missing foundation that all of 212–224 implicitly depend on: enumerate the installed ASIO drivers, load and initialise the selected one, and tear it down cleanly.

## Goals

- Extend the `daw-core/native/asio/` shim with driver enumeration and lifecycle exports, documented in `asioshim.h` alongside the existing ABI notes:
  - `asioshim_listDrivers(void* outArray, int* outCount)` — enumerate installed ASIO drivers from the Windows registry via the SDK's `AsioDrivers` glue, writing a fixed-width record per driver (driver name, CLSID string) using the same normalised int32 / fixed-`char[]` struct convention already established for `asioshim_getClockSources` and `asioshim_getChannelInfo`.
  - `asioshim_loadDriver(const char* driverName)` — instantiate the named driver's COM object and call `ASIOInit`, returning `1` (`SHIM_OK`) on `ASE_OK` and `0` otherwise, following the existing return-code convention.
  - `asioshim_getDriverName(void* outName, int nameCapacity)` and `asioshim_getDriverInfo(...)` to surface the active driver's name and version for display.
  - `asioshim_unloadDriver(void)` — call `ASIOExit` and release the COM object; idempotent and safe to call when no driver is loaded.
- Link the SDK's `asiodrivers.cpp` / `asiolist.cpp` (the host glue, not the proprietary driver) into the CMake target from story 224, gated by the existing `-DBUILD_ASIO_SHIM=ON` flag. No driver lifecycle export may run on the audio render thread (consistent with the threading note already in `asioshim.cpp`).
- Add a Java capability wrapper (mirroring `AsioCapabilityShim`) that resolves the new symbols via `SymbolLookup.libraryLookup("asioshim", arena)` and exposes typed enumeration / load / unload methods, with a test-injectable factory hook in the same style as `AsioBackend#setCapabilityShimFactory(Supplier)`.
- Make `AsioBackend#listDevices()` return one `AudioDeviceInfo` per enumerated driver (driver name as the device id / display name) instead of an empty list. When the shim or its `asioshim_listDrivers` symbol is absent the method returns an empty list (current behaviour), so non-Windows hosts and shim-less builds degrade gracefully.
- Make `AsioBackend#open(DeviceId, AudioFormat, int)` call `asioshim_loadDriver(device)` before `support.markOpen(...)`, and make `AsioBackend#close()` call `asioshim_unloadDriver()` after releasing the format-change shim, so a driver is loaded for exactly the lifetime of an open stream. Re-opening a different device unloads the previous driver first (one active ASIO driver per process, per the SDK's single-driver model).
- Ordering contract: a driver must be loaded (`asioshim_loadDriver` succeeded) before any capability query (213 / 215 / 216 / 220 / 222 / 223) or `asioshim_openControlPanel` (212 / 221) is meaningful. Document this dependency in `asioshim.h` and have the capability wrappers throw / return graceful-absence when no driver is loaded rather than calling into an uninitialised SDK.

## Non-Goals

- Real-time audio streaming (`ASIOCreateBuffers` / `ASIOStart` / `bufferSwitch`) — owned by the streaming story (311). This story only enumerates, loads, initialises, and exits drivers; it does not move a single audio frame.
- Sample-format conversion — owned by story 312.
- macOS / Linux equivalents — ASIO is Windows-only; CoreAudio / JACK enumerate devices through their own backends.
- ARM64 Windows or x86-32; x64 only, consistent with story 224's non-goals.
- Hot-plug re-enumeration semantics — story 214 owns device-arrival/removal handling; this story only provides the snapshot enumeration it calls.

## Technical Notes

- Files: `daw-core/native/asio/asioshim.cpp` + `asioshim.h` (new lifecycle exports), `daw-core/native/asio/CMakeLists.txt` (link `asiodrivers.cpp` / `asiolist.cpp`), `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/AsioBackend.java` (`listDevices` / `open` / `close`), a new `AsioDriverShim` wrapper alongside `AsioCapabilityShim.java`.
- The existing `AsioCapabilityShim` (symbol lookup + struct-decode pattern) and `asioshim_getClockSources` (fixed-width struct array ABI) are the canonical references to copy for `asioshim_listDrivers`.
- This story is a hard prerequisite for stories 311 and 312 and the runtime usefulness of 212 / 213 / 215 / 216 / 220 / 221 / 222 / 223 — none of those capability calls return real data until a driver is actually loaded.
- Research backing: `docs/research/open-source-daw-tools.md` (§ Audio I/O Abstraction — "ASIO: Low-latency audio on Windows") and `docs/research/audio-development-tools.md` (JAsioHost — "Java-based ASIO host for low-latency audio I/O on Windows", relevance High) describe the driver-enumeration-then-init model this story implements.
- Reference original stories: **130 — Audio Backend Selection**, **219 — Instantiate Platform Audio Backends**, and **224 — Build and Bundle asioshim.dll**.
