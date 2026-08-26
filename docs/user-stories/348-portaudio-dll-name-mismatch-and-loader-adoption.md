---
title: "PortAudio Never Loads on Windows: DLL Name Mismatch and NativeLibraryLoader Adoption"
labels: ["bug", "build", "native", "windows", "audio-engine", "ffm"]
---

# PortAudio Never Loads on Windows: DLL Name Mismatch and NativeLibraryLoader Adoption

## Motivation

On Windows — the project's primary target — the PortAudio backend is silently unavailable in every build produced by the repository's own native pipeline, and nothing in the test suite, the CI lanes, or the application's default logging says so.

The vendored PortAudio CMake project adds an architecture suffix to its output name on the MSVC toolchain: `lib/portaudio/CMakeLists.txt` defaults `PA_LIBNAME_ADD_SUFFIX` to `ON` under `IF(MSVC)` (~line 353) and, under `IF(WIN32 AND MSVC)` with `CMAKE_CL_64`, sets `OUTPUT_NAME portaudio_x64` (~lines 394-407). The aggregate build in `lib/CMakeLists.txt` does not override that option, and neither `daw-app/pom.xml`'s wildcard copy (`includes="*.so,*.so.*,*.dylib,*.dll"`, ~line 239) nor anything else renames the artifact. The result on disk, in both the build tree and the packaged distribution, is:

```
target/build/native/                      →  portaudio_x64.dll
daw-app/target/dist/native/windows-x64/  →  portaudio_x64.dll
```

The Java side asks for a different file. Two independent call sites both use the bare base name `"portaudio"`:

- `PortAudioBindings.resolveLibraryName()` (~line 708) returns `"portaudio"` on Windows and hands it straight to `SymbolLookup.libraryLookup(name, arena)` (~line 283). That is the loader-bypass the `dawg-native-libs` skill documents as tolerated debt: it skips `NativeLibraryLoader` entirely, so it gets neither the bundled-first search of `java.library.path` nor the platform name table.
- `NativeLibraryDetector.detectAll()` (~line 42) probes `detect(os, "libportaudio", "portaudio", 2, …)`, which goes through `NativeLibraryLoader.platformLibraryNames` — and on Windows that table yields exactly `portaudio.dll` and `libportaudio.dll`, neither of which exists.

So `PortAudioBindings.isAvailable()` is `false`, `PortAudioBackend.initialize()` throws `"PortAudio native library is not available"`, `CallbackBackendAdapter.isAvailable()` is `false`, and the Help dialog's "Native libraries" tab (which renders `NativeLibraryDetector.detectAll()`) reports libportaudio as missing on a machine where the DLL sits in the bundled directory.

### Why nothing catches it

- The only tests that touch availability assert `isIn(true, false)` — `PortAudioBackendTest.shouldReportAvailability` and `PortAudioBindingsTest.shouldReportAvailabilityBasedOnNativeLibrary` — which pass whether or not the library loads. `shouldThrowOnInitializeWhenUnavailable` is gated on `!isAvailable()`, so it exercises only the unavailable branch and never proves the available one.
- `ci.yml` runs on `ubuntu-latest`, where the same CMake project emits `libportaudio.so.2` and the base name matches. The Windows-only lane (`windows-asioshim.yml`) builds and uploads the native set but asserts only on `asioshim.dll`.
- At runtime `DefaultAudioEngineController` logs the miss at `FINE` (`"PortAudio unavailable"` ~line 574, `"PortAudio adapter unavailable"` ~line 2363); only an explicit user request for PortAudio reaches `WARNING` (~line 2529). The default streaming provision quietly drops the PortAudio rung and the fallback ladder collapses from `ASIO → PortAudio → Java Sound` to `ASIO → Java Sound`.

### Why it matters more now

Story 316's review rounds made the PortAudio rung load-bearing rather than cosmetic. The recording path now walks the ladder with `CaptureRequirement.REQUIRED` and refuses any rung whose `AudioBackend.openedInputChannels()` is zero — deliberately so that an ASIO driver with no inputs (a playback-only interface, or a multi-channel interface's ASIO driver exposing outputs only) hands the take to PortAudio, which can open the same hardware through the OS host API with its inputs. On a Windows machine where PortAudio never loads, that hand-off lands on Java Sound instead: 16-bit, no host-API-qualified device identity, no driver-reported latency. The fallback the engine was designed around does not exist on the platform it was designed for.

## Goals

- **Make the library name agree in both directions, from one source of truth.** Two acceptable shapes; pick one and document the choice in `lib/README.md` and the `dawg-native-libs` skill:
  - **(A) Fix the artifact name.** Set `PA_LIBNAME_ADD_SUFFIX OFF` in `lib/CMakeLists.txt`'s PortAudio block so the DLL is `portaudio.dll` on every toolchain, matching the detector and the loader's name table. This MUST be paired with `PA_BUILD_STATIC OFF`: the upstream `CMakeLists.txt` (~line 358) warns that with the suffix off and both static and shared libraries built, the import library and the static library overwrite each other. The static library is unused by this project (the FFM layer loads only the DLL), so disabling it is a simplification, not a loss.
  - **(B) Teach the Java side the suffixed name.** Extend `NativeLibraryLoader.platformLibraryNames` so the Windows candidate list also includes `{base}_x64.dll` (and `{base}_x86.dll` for completeness), keeping `{base}.dll` / `lib{base}.dll` first. Because both the detector and the loader share that table, one edit fixes both sites — but only once `PortAudioBindings` is routed through the loader (next goal).
  - Whichever shape is chosen, stories 224 and 256 both cite `portaudio_x64.dll` by name as "the canonical reference"; update those locators or add a note so the next reader does not treat the old filename as still correct.
- **Retire the `PortAudioBindings` loader bypass.** Replace the direct `SymbolLookup.libraryLookup(resolveLibraryName(), tempArena)` call with `NativeLibraryLoader.loadLibrary(arena, "portaudio", 2)`, and delete `resolveLibraryName()`. This is the change that would have caught the mismatch structurally — the loader owns the platform name table — and it removes the one tolerated exception the `dawg-native-libs` skill calls out ("`PortAudioBindings` already does this; it is the one tolerated-debt exception, not precedent"). Model the result on `FluidSynthBindings`, the loader-routed reference. Preserve the existing behaviour that an absent library yields `isAvailable() == false` rather than a thrown constructor: catch the loader's `UnsupportedOperationException` where the bypass currently catches `IllegalArgumentException | UnsatisfiedLinkError` (~line 285), and keep the install-hint text the loader produces so the FINE log finally says which filenames were searched.
- **Fix the inert CMake option while in the file.** `lib/CMakeLists.txt` ~line 50 sets `PA_BUILD_SHARED_LIBS ON`, but the vendored project's option is named `PA_BUILD_SHARED` (~line 350); the set is a no-op and the DLL is produced today only because upstream's default is `ON`. Rename it so the intent is actually enforced, and add the `PA_BUILD_STATIC OFF` from goal (A) beside it whether or not (A) is the chosen shape.
- **Raise the runtime signal.** When the default provision (no backend named, PortAudio-first) cannot construct its PortAudio head, log at `WARNING` — not `FINE` — naming the searched filenames and the bundled directory, and surface the same fact through the existing `NotificationManager` path the controller already uses for a rejected explicit request. A silent ladder collapse on the primary platform is the class of quiet failure story 316's review rounds kept finding.
- **Tests** (this list is the binding acceptance criterion):
  - `NativeLibraryDetectorTest`: a `@EnabledOnOs(OS.WINDOWS)` case, in the exact shape of `asioshimShouldBeResolvableOnWindowsWhenBundled`, that asserts `libportaudio` is `available()` with a non-empty `detectedPath()` whenever the bundled directory on `java.library.path` contains ANY file matching `portaudio*.dll`. The precondition is a filesystem glob, deliberately NOT the detector's own answer — an `assumeTrue(isAvailable(...))` guard would skip on precisely the bug this story fixes. Keep it an assumption-skip only when no `portaudio*.dll` is present at all (a `-DskipNativeBuild=true` workstation), and say so in the skip reason.
  - `PortAudioBindingsTest`: the same Windows-gated, glob-preconditioned assertion that `new PortAudioBindings().isAvailable()` is `true`. Replace the `isIn(true, false)` assertion in `shouldReportAvailabilityBasedOnNativeLibrary` with this; an assertion that accepts both answers proves nothing.
  - `PortAudioBackendTest`: the positive half that `shouldThrowOnInitializeWhenUnavailable` never exercises — under the same precondition, `initialize()` succeeds and `getAvailableDevices()` returns a non-empty list on a host with at least one audio endpoint (assumption-skip, with reason, when the enumeration is empty on a headless runner).
  - `NativeLibraryLoaderTest` (or the existing test for `platformLibraryNames`): pin the Windows candidate order for base name `portaudio` so that whichever shape (A)/(B) is chosen, a later change to either side fails a test instead of silently regressing.
  - CI: extend `windows-asioshim.yml`'s post-build verification step to fail when `target/build/native/` contains no file matching the name the Java side searches for, mirroring its existing `asioshim.dll` check. Until a general Windows lane exists, this lane is the only place the bundled Windows artifact set is ever inspected.
- **Docs**: update the `dawg-native-libs` skill's §3 and §5 to remove the "tolerated exception" language once the bypass is gone, and correct the table row that lists the PortAudio binding as loader-bypassing. Mirror to `.github/instructions/` if that copy exists — the skill notes it is not auto-synced.

## Non-Goals

- Moving PortAudio downcalls onto a dedicated control thread with bounded waits. `CallbackBackendAdapter`'s class javadoc documents that exposure at length and explains why it is a subsystem, not a wrapper; it stays a separate story.
- Changing PortAudio's SOVERSION, the Linux/macOS artifact names, or the `NativeLibraryLoader` search order (bundled first, then system) — those are correct today.
- ARM64 Windows. The upstream suffix logic has only `x64` / `x86` branches; ARM64 support would be its own build story.
- Re-vendoring or upgrading PortAudio.
- Auditing the other vendored libraries for the same class of mismatch. A quick check shows `ogg.dll`, `vorbis.dll`, `vorbisenc.dll`, `vorbisfile.dll`, `libmp3lame.dll` all match their detector base names via the loader's `{base}.dll` / `lib{base}.dll` pair; only PortAudio applies an architecture suffix.

## Technical Notes

- Files: `lib/CMakeLists.txt` (PortAudio block, ~lines 48-67), `daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/portaudio/PortAudioBindings.java` (constructor ~lines 275-292, `resolveLibraryName` ~line 708), `daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/NativeLibraryLoader.java` (`platformLibraryNames`, only under shape B), `daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/NativeLibraryDetector.java` (no change expected under shape A; the entry at ~line 42 is already correct once the artifact name matches), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/DefaultAudioEngineController.java` (log levels ~lines 574 / 2363), `.github/workflows/windows-asioshim.yml`, `lib/README.md`, `.claude/skills/dawg-native-libs/SKILL.md`.
- Line numbers above are locators, not contracts — they drift; verify against the file before editing.
- Shape (A) is the recommended choice: it fixes the packaged distribution's filename to match every other vendored DLL's convention (`{base}.dll`), needs no Java name-table change, and keeps the loader's Windows rule ("DLLs are unversioned — `{base}.dll`, `lib{base}.dll`") true rather than special-casing one library. Shape (B) is the fallback if a downstream consumer is found to depend on the suffixed filename; none is known in this repository.
- The `PA_BUILD_STATIC OFF` pairing is mandatory under (A), not optional: `target/build/portaudio/Release/portaudio_x64.lib` today is the import library, and with the suffix off both it and the static library would be named `portaudio.lib` in the same directory.
- The loader-adoption goal is independent of the name fix and should land in the same change: fixing only the filename leaves `PortAudioBindings` outside the shared name table, so the next naming divergence (a future upstream default change, a different toolchain) reproduces this bug with nothing to catch it.
- The RT-safety, C-`long` ABI, and struct-layout work already in `PortAudioBindings` (`NativeAbi.C_LONG`, `PA_STREAM_PARAMETERS_LAYOUT`, `PA_HOST_API_INFO_LAYOUT`) is untouched by this story — only the library-loading prologue changes.
- Related: **097** (introduced `NativeLibraryDetector`), **116** (established bundled-first lookup), **224 / 256** (cite `portaudio_x64.dll` as the reference artifact — update their locators), **316** (the fallback ladder this bug silently shortens; its review round 10 made the PortAudio rung the recording fallback for input-less ASIO drivers).
