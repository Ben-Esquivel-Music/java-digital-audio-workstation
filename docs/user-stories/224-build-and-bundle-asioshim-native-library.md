---
title: "Build and Bundle the asioshim.dll Native Library with the Distribution"
labels: ["bug", "build", "native", "windows", "asio", "ffm"]
---

# Build and Bundle the asioshim.dll Native Library with the Distribution

## Motivation

Story 218 introduced `AsioFormatChangeShim` — an FFM upcall stub that translates the ASIO host-callback selectors (`kAsioResetRequest`, `kAsioBufferSizeChange`, `kAsioResyncRequest`) into `AudioDeviceEvent.FormatChangeRequested` events on the backend's `Flow.Publisher`. The Java side is complete and tested in `AsioFormatChangeShimTest`. But the runtime depends on a native library named `asioshim` to actually install the upcall:

```java
SymbolLookup lookup = SymbolLookup.libraryLookup("asioshim", arena);
MemorySegment install = lookup.find("installAsioMessageCallback")
        .orElseThrow(() -> new UnsatisfiedLinkError(
                "installAsioMessageCallback not found"));
```

That library does not exist anywhere in the repo. There is no source file, no CMake target, and no `asioshim.dll` in any of the existing native distribution directories:

```
daw-app/target/dist/native/windows-x64/  →  libmp3lame.dll, ogg.dll, portaudio_x64.dll, vorbis.dll, vorbisenc.dll, vorbisfile.dll  (no asioshim.dll)
target/build/native/                      →  libmp3lame.dll, ogg.dll, portaudio_x64.dll, vorbis.dll, vorbisenc.dll, vorbisfile.dll  (no asioshim.dll)
```

Result: every `AsioFormatChangeShim` constructor takes the "library not present" branch and degrades to a no-op. On the user's primary platform (Windows + multi-channel USB ASIO driver), driver-initiated buffer-size changes, sample-rate changes, and clock-source changes are silently swallowed — the DAW never sees the event, never reopens the stream, and the next render callback fails the same crashy way as a yanked cable that story 214 was supposed to address.

Stories 220 / 221 / 222 / 223 each grow the surface area of the `daw-core/native/asio/` shim with new exports. This story is the build-system underpinning that all four (and 218) ride on: produce the `.dll`, sign it where appropriate, and ship it with the distribution.

## Goals

- Establish a `daw-core/native/asio/` source tree containing:
  - `CMakeLists.txt` configured to build `asioshim` as a shared library (`SHARED`) on Windows, `MODULE` on macOS, with platform-conditional output naming (`asioshim.dll` / `libasioshim.dylib` / `libasioshim.so`).
  - `asioshim.cpp` exporting at least `installAsioMessageCallback(void* upcallStub)` and `uninstallAsioMessageCallback()` per story 218's contract. Stories 220 / 221 / 222 / 223 each add their additional exports (`asioshim_getBufferSize`, `asioshim_canSampleRate`, `asioshim_openControlPanel`, `asioshim_getClockSources`, `asioshim_setClockSource`, `asioshim_getChannelCount`, `asioshim_getChannelInfo`, `asioshim_getSampleRate`, `asioshim_setSampleRate`).
  - A header file documenting each export's ABI (calling convention `__stdcall` on x86-32, default on x64; pointer ABIs match the SDK's `ASIOClockSource` / `ASIOChannelInfo` layout).
- Link against the Steinberg ASIO SDK as a build-time dependency. Document where to obtain the SDK in `docs/native-build-setup.md` (or equivalent existing doc); do not commit the SDK source. Provide a CMake variable `ASIO_SDK_DIR` so contributors can point at their licensed copy.
- Integrate the build into the existing native pipeline. The repo already builds `portaudio_x64.dll` and the libogg / libvorbis stack via CMake invoked from Maven; add the ASIO shim alongside, gated by `-DBUILD_ASIO_SHIM=ON` (default OFF on non-Windows). The default Windows CI build sets the flag ON so PRs detect breakage early.
- Bundle the produced `asioshim.dll` into both `daw-app/target/dist/native/windows-x64/` and `target/build/native/` via the existing assembly descriptor and copy plugin so the runtime `SymbolLookup.libraryLookup("asioshim", arena)` succeeds when the user launches the packaged build.
- Document the third-party dependency in `THIRD_PARTY_NOTICES.md` per the existing convention.
- Add a startup self-test in `NativeLibraryDetector` that checks for `asioshim` on Windows and reports its presence + resolved symbols in the in-app `HelpDialog` "Native libraries" tab so users have a one-glance way to confirm the shim is available.
- CI: a Windows job builds and runs `AsioFormatChangeShimTest` with `asioshim.dll` on the FFM library path. A successful run requires `installAsioMessageCallback` to be present (the test was previously gated by graceful absence; flip to `Assumptions.assumeTrue(NativeLibraryDetector.isAvailable("asioshim"))` so absence is a CI failure on Windows builds, a skip on Linux/macOS).
- Tests:
  - `NativeLibraryDetectorTest` asserts that on a Windows build the bundled `asioshim` is resolvable.
  - `AsioFormatChangeShimTest` adds a Windows-gated case that opens the shim against the real DLL and confirms `isRegistered()` returns true after construction.

## Non-Goals

- Producing macOS or Linux ASIO shims (ASIO is Windows-specific; CoreAudio / JACK have their own format-change paths and do not need an `asioshim` analogue).
- Code-signing the DLL (a deployment concern for a separate "release packaging" story).
- Distributing the Steinberg ASIO SDK itself; build-time dependency only.
- Supporting ARM64 Windows in this iteration; x64 only.

## Technical Notes

- Files: new `daw-core/native/asio/` tree, `daw-core/pom.xml` (CMake invocation), `daw-app/pom.xml` (assembly descriptor for `target/dist/native/windows-x64/`), `daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/NativeLibraryDetector.java` (add `asioshim` to the detection list), `THIRD_PARTY_NOTICES.md`.
- The existing `portaudio_x64.dll` build is the canonical reference — its CMake configuration and Maven assembly are the patterns to copy.
- This story is a hard prerequisite for the runtime usefulness of stories 218 / 220 / 221 / 222 / 223.
- Reference original stories: **218 — Driver-Initiated Reset Request Handling** and **115 — Integrate libogg/libvorbis Native Build** (for the build pipeline pattern).
