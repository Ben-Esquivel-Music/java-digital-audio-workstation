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

## Resolution

**What the owner can now do.** The native build now makes `portaudio.dll` on Windows, the name the Java side asks for, and `PortAudioBindings` finds it through `NativeLibraryLoader`. In any run whose `java.library.path` names the build's native folder, PortAudio is offered in Audio Settings and can be chosen as a backend, and Help > Help… > System Capabilities lists libportaudio as Available with its path. No test opens a PortAudio stream, so whether it plays and records cleanly is the by-hand check below. When no backend is saved and PortAudio cannot be loaded, the person is told once per session instead of silently getting Java Sound: "PortAudio is unavailable — audio will play and record through Java Sound. See System Capabilities in Help." On Linux, a system `libportaudio2` now loads. **A plain launch on Windows still has no PortAudio:** no launcher names the bundled native folder, so the launched application cannot find the library until future story 491 lands.

By-hand check (owner-only: it starts the application and plays through a real device):
1. At the repository root, run `mvn -B -o clean package -DskipTests`. The clean removes the stale `portaudio_x64.dll`.
2. `dir target\build\native\portaudio*.dll` and `dir daw-app\target\dist\native\windows-x64\portaudio*.dll` each list `portaudio.dll` and no `portaudio_x64.dll`.
3. As shipped: in a new cmd window, run `daw-app\target\daw-runtime\bin\daw.bat` with your saved ASIO setup. Expect no PortAudio notice; System Capabilities shows libportaudio Missing (expected until 491). Optional: note your settings, use Settings > General > Reset & profiles > Restore all defaults…, and restart. Expect the notice exactly once; applying settings again in the same session shows no second one.
4. With the bundle named: in a new cmd window, run `set JDK_JAVA_OPTIONS=-Djava.library.path=C:\SourceCode\java-digital-audio-workstation\target\build\native`, then `daw-app\target\daw-runtime\bin\daw.bat`.
   - System Capabilities shows libportaudio Available at `…\target\build\native\portaudio.dll`.
   - In Audio Settings choose PortAudio and Apply. A backend choice takes effect after a restart, so close the application and start `daw.bat` again from the same window. Then choose your interface's MME entry and 96 kHz, and Apply.
   - Play a reference track you know well for 2 minutes: pitch and tempo match another player, no clicks, the Xruns count stays at 0.
   - Record 30 s against a stopwatch: the take is 30 s long, has no gaps and plays back at the right pitch. Repeat at 48 kHz.
   - Pick a WASAPI entry at a rate that differs from the Windows format: expect a refused open that falls to the next rung, not a wrong pitch.
5. Restore your ASIO settings.

**What was built.**
- *Native build.* `lib/CMakeLists.txt` forces `PA_BUILD_SHARED` ON and `PA_BUILD_STATIC`, `PA_LIBNAME_ADD_SUFFIX` and `PA_USE_ASIO` OFF, replacing the inert `PA_BUILD_SHARED_LIBS`. MSVC builds `portaudio.dll` and `portaudio.lib`; Linux still builds `libportaudio.so`, macOS `libportaudio.dylib`. `lib/README.md` records this.
- *Loading.* `PortAudioBindings` loads through `NativeLibraryLoader.loadLibrary(arena, "portaudio", 2)`, catches `UnsupportedOperationException`, `IllegalArgumentException` and `UnsatisfiedLinkError`, and keeps the loader's message in `unavailableReason()`, which `PortAudioBackend.unavailableReason()` passes on. `resolveLibraryName()` is gone; a missing library still gives `isAvailable() == false`. `NativeLibraryLoader.installHint` names `libportaudio2` (Debian/Ubuntu) and `portaudio` (Homebrew).
- *Latency.* `PortAudioBackend.suggestedLatencySeconds(deviceInfo, StreamDirection)` returns the device's low-latency default for that direction, else its high one, else `FALLBACK_SUGGESTED_LATENCY_SECONDS` (0.010 s). `openStream` reads `Pa_GetDeviceInfo` for each direction and passes the result to `allocateStreamParameters`.
- *The notice.* `DefaultAudioEngineController.tellOnceThatPortAudioIsUnavailable` sends `PORTAUDIO_UNAVAILABLE_NOTICE` once per controller, which is once per session, and logs one WARNING with the loader's message, then FINE. Two callers: `buildStreamingProvision` for a blank or "None" request, through `attemptPortAudioAdapter` (record `PortAudioAttempt`); and the new `AudioEngineController.onSettingsAppliedWithoutABackend()` (default no-op), which `MainController.applyStartupAudioSettings` calls after `applyConfiguration` when the saved backend is blank, because `configuredAudioBackend` has already turned the blank into the provisioned name. The override tells only when Java Sound heads the provision and a fresh PortAudio attempt fails. `installDefaultProvision`, `appendFallbackRungs` and the device probe tell nothing; an explicit PortAudio request keeps `rejectedProvision`.
- *CI.* The new `windows-asioshim.yml` step "Verify PortAudio is built as portaudio.dll" fails when `portaudio.dll` is missing from `target/build/native` or `daw-app/target/dist/native/windows-x64`, or a `portaudio_x64.dll` is in either. Six paths join both path filters.
- *Tests.* New `NativeLibraryLoaderTest` (name lists for `portaudio`, version 2, on Windows, Linux and macOS) and the helper `BundledPortAudio` (the `java.library.path` glob). `NativeLibraryDetectorTest.bundledPortAudioShouldBeDetectedOnWindows`; `PortAudioBindingsTest.bundledPortAudioShouldLoadThroughTheLoader`, on every OS. In `PortAudioBackendTest`: `anUnavailableLibraryAlwaysCarriesTheReasonAndAnAvailableOneNone` (replaces `shouldReportAvailability`), `bundledPortAudioInitializesAndEnumeratesDevicesOnWindows` (a daemon worker bounded at 30 s, no stream) and three latency tests. In `DefaultAudioEngineControllerTest`: `blankAndNoneBackendNamesStillYieldTheDefaultLadderSilently` became `blankAndNoneBackendNamesYieldTheDefaultLadderAndTellOnceWhenJavaSoundHeadsIt`, plus `aBlankSavedBackendAtStartupIsToldOnceThatPortAudioIsUnavailable` and `aSavedAsioBackendAtStartupIsNotToldAboutPortAudio`, whose startup assertions count the PortAudio notice alone (a host with no sound device adds the older Java Sound notice first).
- *Docs.* The `dawg-native-libs` skill (both copies), the comment in `daw-core/native/asio/CMakeLists.txt`, §3 of `docs/native-build-setup.md`, a dated line in stories 220, 224 and 256, a "Note from Story 348" in story 349, and future story 491 with its index row.

**What differs from the story text.**
- On Linux the library was never versioned and the bindings never loaded it: the build makes `libportaudio.so`, not `.so.2`, and the old `dlopen("libportaudio.so")` never read `java.library.path`, so PortAudio did not load on Linux CI either. The bindings' positive test therefore runs on every OS.
- The launched application sets no `java.library.path` in any form (jlink, jpackage, `javafx:run`), so the story's runtime goal on Windows waits for 491.
- The FINE lines never fired for a missing library: it logged nothing, and the FINE and WARNING lines fired only when the binding's constructor threw. The screen now gets a short notice; the loader's full message, which on Windows holds the whole PATH, goes only to the log.
- Added: `PA_USE_ASIO` OFF (R5) and the latency fix. `PA_BUILD_STATIC` OFF is needed because upstream turns the suffix back on whenever MSVC builds both libraries. No test of `platformLibraryNames` existed. The Help tab is System Capabilities, not "Native libraries".

**Rulings.**
- R1: tell the person only when the settings name no backend and PortAudio could not be built; at most once per session, in the words above; WARNING with the loader's message first, FINE after; never from `installDefaultProvision`, `appendFallbackRungs` or the device probe.
- R2: PortAudio's untimed open, start, stop and close calls are a Known limit that story 349 owns; the rung gets no gate.
- R3: PortAudio's own default host API (MME on Windows) is accepted; a rate the device refuses fails the open and the engine moves to the next rung, never a wrong pitch.
- R4: Help's System Capabilities tab is unchanged.
- R5: `Pa_Initialize` plus enumeration may run in the gate, bounded (30 s): an initialize failure fails, only an empty device list skips; `PA_USE_ASIO` stays forced OFF; a test that opens or starts a real PortAudio stream is owner-only.
- R6: future story 491 makes the launched application load its bundled native libraries; it lands after story 349 and the latency fix.
- Latency (studio-engineer): a PortAudio stream never asks for 0 suggested latency; each direction asks for the device's low-latency default (then its high one, then 0.010 s); reported latency still comes from `Pa_GetStreamInfo`.

**Known limits.**
- Until 491 lands, a person on Windows with no saved backend sees the notice at every start, because the launched application cannot find the bundled library.
- On a host with no sound device and no saved backend, the person gets the older "Java Sound not available — falling back to Java Sound", then this story's notice that audio will play and record through Java Sound; the engine routes there, but nothing will sound.
- Only startup calls the hook: Settings Apply and Restart Audio Engine with a blank saved backend tell nothing, and a startup whose configuration throws tells nothing that session.
- PortAudio's open, start, stop and close calls still have no time limit (story 349, R2).
- PortAudio's default host API on Windows is MME: shared through the Windows mixer, resampled, slower than WASAPI or WDM-KS (R3).
- The `PortAudioBackend` class javadoc still lists ASIO among PortAudio's Windows host APIs and the `appendFallbackRungs` javadoc argues from it; with `PA_USE_ASIO` OFF that is untrue (comments only).
- No test reaches the latency fix's call site in `PortAudioBackend.openStream`, because opening a stream is owner-only; the helper and the struct write are tested.
- The stale `portaudio_x64.dll`, `.lib` and `.exp` stay in `target/build` until a Maven clean; `daw-app/target/dist` holds only the stale DLL until the next packaging run, and a packaging run without a clean ships both DLLs.
- On a Linux box with a system `libportaudio2`, PortAudio loads in daw-app's test JVM and its default-ladder tests call `Pa_Initialize` (the CI runner and WSL have none).
- `PortAudioBackendTest.shouldThrowOnInitializeWhenUnavailable` asserts nothing wherever PortAudio loads, this machine and Linux CI included.
- Every gate run on this machine runs `Pa_Initialize` and device enumeration on the real endpoints (R5; 0.1–0.2 s).
- The windows-asioshim lane runs only with the `ASIO_SDK_ZIP_BASE64` secret, so its PortAudio check is skipped on pull requests from forks.
- The lane's older asioshim verify step calls `Write-Error` under pwsh's stop preference, so its directory listing never prints.
- Both skill copies still say `lib/RoomAcoustiCpp-1.0.1` "physically still exists"; the folder is gone.
- A foreign `portaudio.dll` missing a required symbol shows Available in Help while the notice says PortAudio is unavailable; both are true (R4).
- `attemptPortAudioAdapter` leaves the `PortAudioBackend` unclosed if the `CallbackBackendAdapter` constructor throws, as the earlier code did.
- `BundledPortAudio` fails the test with `UncheckedIOException`, instead of skipping, when a `java.library.path` directory exists but cannot be listed.
- The loader's message in the WARNING calls a default `java.library.path` the "bundled native directory" (older wording).
- Where Java Sound works, the startup assertions no longer pin that nothing else is notified at startup.
- Production comments cite "(story 348, studio-engineer ruling R1)" rather than "Story 348 (studio-engineer ruling R1)".

## Verification

Windows, Maven 3.10.0, JDK 27, unpiped, one process at a time:
- `mvn -B -o -pl daw-core -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=NativeLibraryLoaderTest,NativeLibraryDetectorTest,PortAudioBindingsTest,PortAudioBackendTest` (with the native build), exit 0. It made `target/build/native/portaudio.dll` (169,472 bytes) and `target/build/portaudio/Release/portaudio.lib`; the configure output has no "PA_LIBNAME_ADD_SUFFIX was set to ON"; the cache reads `PA_BUILD_SHARED=ON`, `PA_BUILD_STATIC=OFF`, `PA_LIBNAME_ADD_SUFFIX=OFF`, `PA_USE_ASIO=OFF`. Tests 3 + 13 + 10 + 27, 0 failures, 2 skipped (the asioshim tests, without `asioshim.dll`).
- `mvn -B -o -pl daw-app -am test -DskipNativeBuild=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DefaultAudioEngineControllerTest`, exit 0 on the final tree: 70 tests, 0 failures, 0 skipped.
- Neighbours, green: `RealTimeSafeContractTest` (159), `CallbackBackendAdapterTest` (60), `CallbackInvokerTest` (22), `FailureSpineWiringSourceTest` (13), `MainControllerAudioSettingsWiringTest` (4), `MainControllerRestartAudioEngineTest` (6).

Deliberate breaks, each one edit, one targeted run and an exact revert (checked with `cmp`):

| # | Break | Failing test |
|---|---|---|
| M1 | `SymbolLookup.libraryLookup("portaudio", …)` instead of the loader | `PortAudioBindingsTest.bundledPortAudioShouldLoadThroughTheLoader` |
| M2 | `suggestedLatency` written as 0.0 | `PortAudioBackendTest.eachDirectionAsksForTheDevicesOwnLowLatencyDefault` |
| M3 | the once-per-session flag never set | `blankAndNoneBackendNamesYieldTheDefaultLadderAndTellOnceWhenJavaSoundHeadsIt`, `aBlankSavedBackendAtStartupIsToldOnceThatPortAudioIsUnavailable` |
| M4 | the notice sent from `installDefaultProvision` | `aBlankSavedBackendAtStartupIsToldOnceThatPortAudioIsUnavailable`, `aSavedAsioBackendAtStartupIsNotToldAboutPortAudio` |
| M5 | Windows names swapped in `platformLibraryNames` | `NativeLibraryLoaderTest.windowsTriesPortaudioDllThenLibportaudioDll` |
| M6 | `MainController` no longer calls `onSettingsAppliedWithoutABackend()` | `aBlankSavedBackendAtStartupIsToldOnceThatPortAudioIsUnavailable` |

Linux (WSL Ubuntu, a mirror under the WSL home with its own native build; Temurin 27+35, Maven 3.10.0, CMake 4.2.3, GCC 15.2): `libportaudio.so`, 187,344 bytes, unversioned, same four cache values. The four daw-core classes: 53 tests, 0 failures, 4 skipped (the Windows-only cases); `bundledPortAudioShouldLoadThroughTheLoader` ran and passed, failed with the old bypass put back ("Cannot open library: libportaudio.so") and passed again once restored. `DefaultAudioEngineControllerTest`: 70 tests, 0 failures, 1 skipped (Java Sound is not available on that host).

Gate of record: `mvn -B -o -DskipNativeBuild=true verify` at the root, after the last code change: exit 0, BUILD SUCCESS, 22:52 min, finished 2026-11-27 13:13:59. 17,553 tests, 0 failures, 0 errors, 45 skipped: daw-sdk 1,555 (2 skipped), daw-acoustics 106, daw-core 9,820 (40), daw-fx 29, daw-app 6,043 (3). The Windows PortAudio positives ran, none skipped. `PortAudioBackendTest` took 0.21 s for 27 tests, its enumeration test 0.104 s (0.39–0.49 s for the class in the targeted runs); no new test class comes near 5 s.

Not verified: no test runs `MainController.initialize()` (the startup tests call `installDefaultProvision()` and the static `MainController.applyStartupAudioSettings(...)`; the order inside `initialize()` is pinned only by the source scan in `FailureSpineWiringSourceTest`); the PortAudio branch of the startup assertion never ran, because no daw-app test JVM loads PortAudio; macOS has no runner; the ASIO integration tests skipped themselves without `asioshim.dll`.

Left for the owner:
- The commit.
- The push and CI: `ubuntu-latest`, and the windows-asioshim lane (needs the secret). Whether `Pa_Initialize` succeeds on that lane's runner, which has no audio hardware, is unverified; if it fails, `bundledPortAudioInitializesAndEnumeratesDevicesOnWindows` fails rather than skips.
- A Maven clean to remove the stale `portaudio_x64.dll` (step 1 of the by-hand check does it).
- The by-hand check above.
- Another Windows checkout that holds only `portaudio_x64.dll` needs one build without `-DskipNativeBuild` before the new Windows tests pass there.
