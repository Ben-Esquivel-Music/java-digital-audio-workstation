---
name: dawg-native-libs
description: Map of the vendored C/C++ native libraries under lib/ — how they are built by CMake, loaded into Java via the FFM API (JEP 454), and bound. Use this skill when adding, upgrading, building, binding, or troubleshooting a native library (PortAudio, FluidSynth, LAME, libogg/libvorbis, CLAP, asioshim), or when a native symbol/SymbolLookup/UnsatisfiedLinkError needs diagnosing.
allowed-tools: Read, Grep, Glob, Edit, Write, Bash
---

# DAWG Native Libraries (`lib/`)

How the Java Digital Audio Workstation vendors third-party **C / C++**
libraries, compiles them with one CMake build, and reaches them from
Java through the Foreign Function & Memory API (JEP 454) — **no JNI, no
generated bindings.**

## Repo coordinates

- Repo root: `C:\SourceCode\java-digital-audio-workstation`
- Vendored sources + aggregate build: `lib/` (`lib/CMakeLists.txt`)
- Per-library docs: `lib/README.md`
- Java FFM layer: `daw-core` (and `daw-sdk` for the ASIO shim)
- Native build output: `target/build/native/` (every `.dll`/`.so`/`.dylib` lands here, flat)
- Driven by Maven: `daw-core/pom.xml` (`exec-maven-plugin`, `generate-resources` phase)

## 1. The vendored libraries

| Dir under `lib/` | Library | `baseName` · SOVERSION | License | CMake | Java binding |
|---|---|---|---|---|---|
| `portaudio/` | PortAudio | `portaudio` · in-tree build is **unversioned**: Linux `libportaudio.so` (SONAME `libportaudio.so`), Windows/MSVC `portaudio_x64.dll` (`PA_LIBNAME_ADD_SUFFIX` defaults `ON` under MSVC). Upstream applies `PA_SOVERSION` only when the macOS-only `PA_OUTPUT_OSX_FRAMEWORK` option (default `OFF`) is on (`lib/portaudio/CMakeLists.txt:420-429`). The `2` that `NativeLibraryDetector` probes is the distro package's `libportaudio.so.2`; the in-tree Linux build is found via the unversioned fallback name | MIT | yes (`BUILD_PORTAUDIO`) | `core.audio.portaudio.PortAudioBindings` / `PortAudioBackend` |
| `fluidsynth-2.5.3/fluidsynth-2.5.3/` | FluidSynth | `fluidsynth` · 3 | LGPL-2.1 | yes (`BUILD_FLUIDSYNTH`) | `core.midi.fluidsynth.FluidSynthBindings` / `FluidSynthRenderer` |
| `libmp3lame/` | LAME | `mp3lame` · 0 | LGPL-2 | yes (`BUILD_LIBMP3LAME`) | `core.export.Mp3Exporter` |
| `ogg-1.3.6/` | libogg | `ogg` · 0 | BSD-3 | yes (`BUILD_LIBOGG`) | (transitive dep of vorbis) |
| `vorbis-1.3.7/` | libvorbis / vorbisenc / vorbisfile | `vorbis` · 0, `vorbisenc` · 2, `vorbisfile` · 3 | BSD-3 | yes (`BUILD_LIBVORBIS`) | `core.audioimport.OggVorbisFileReader` / `core.export.OggVorbisExporter` |
| `Clap-1.2.7/` | CLAP | header-only (no shared lib) | MIT | n/a | `core.plugin.clap.ClapBindings` / `ClapPluginHost` — binds *external `.clap` plugin binaries*, not a vendored lib (see §3) |
| `RoomAcoustiCpp-1.0.1/` | RoomAcoustiCpp | **empty stub** | GPLv3 | n/a | ported to pure Java in `daw-acoustics/` |

Two libraries are **not** vendored under `lib/` but are still bound:

- **fdk-aac** — `baseName` `fdk-aac` · SOVERSION 2, system-only (`brew install fdk-aac` / `apt install libfdk-aac2`), used by `core.export.AacExporter`. No CMake target; degrades gracefully when absent.
- **asioshim** — custom FFM bridge, source in `daw-core/native/asio/` (`asioshim.cpp/.h/.CMakeLists.txt/README.md`), Java side `daw-sdk` `AsioCapabilityShim` / `AsioFormatChangeShim`. Built only when `-DASIO_SDK_DIR=...` is supplied (Steinberg SDK is non-redistributable + Windows-only). See its own `README.md`.

> `lib/RoomAcoustiCpp-1.0.1/` physically still exists but the source
> tree is **empty** (only empty dirs). The implementation is a pure-Java
> port in the `daw-acoustics` Maven module; the GPLv3 derivative-work
> notice is carried by `daw-acoustics/NOTICE` + `THIRD_PARTY_NOTICES.md`,
> not by anything compiled from `lib/`. Do not try to build it.

## 2. The build pipeline

```
mvn ... (daw-core)                          [generate-resources phase]
  └─ antrun: mkdir target/build             [initialize phase]
  └─ exec: cmake -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON  <root>/lib
  └─ exec: cmake --build . --config Release --parallel
        └─ lib/CMakeLists.txt
             add_subdirectory(... EXCLUDE_FROM_ALL) per library
             → aggregate custom target `daw-native-libs` (ALL)
             → all artifacts forced into target/build/native/   (flat, all configs)
  └─ exec: bash generate-third-party-notices.sh   (skippable: -DskipNoticesGeneration=true)
```

Then at test/run time (`daw-core/pom.xml` surefire `argLine`):

```
--enable-native-access=daw.core,ALL-UNNAMED -Djava.library.path=<root>/target/build/native
```

Key knobs:

- **`-DskipNativeBuild=true`** — pure-Java workflow; skips configure, build, and the mkdir. Java codec/audio paths then degrade via `NativeLibraryDetector` / `isAvailable()` instead of hard-failing.
- Per-library `option(BUILD_<LIB> ...)` in `lib/CMakeLists.txt:25-45` — all `ON` by default; `BUILD_ASIOSHIM` is a no-op without `ASIO_SDK_DIR`.
- A C smoke test (`vorbis_smoke_test`, `lib/CMakeLists.txt:298-340`) verifies libogg+libvorbis are loadable; it is wired into the aggregate target.

### Hard build-order / layout facts (don't trip on these)

- **FluidSynth is double-nested**: `lib/fluidsynth-2.5.3/fluidsynth-2.5.3/`. The outer `lib/fluidsynth-2.5.3/` contains **nothing but the inner source dir** (no files of its own — not even the license). The real tree, including `LICENSE`, *is* the inner dir, which is what `add_subdirectory` targets (`lib/CMakeLists.txt:122`); `generate-third-party-notices.sh:51` correctly points at the inner `.../fluidsynth-2.5.3/LICENSE`.
- **FluidSynth is the only non-fatal-skip library**: if GLib2 is not found via `pkg-config`, CMake prints a `WARNING` and skips it (`lib/CMakeLists.txt:90-138`). The rest of the build still succeeds; MIDI synth then reports unavailable.
- **libogg must be configured before libvorbis** — vorbis's `find_package(Ogg REQUIRED)` is satisfied by pre-seeding `OGG_INCLUDE_DIR`/`OGG_LIBRARY` cache vars + the in-tree `Ogg::ogg` alias (`lib/CMakeLists.txt:187-216`). `BUILD_LIBVORBIS` without `BUILD_LIBOGG` is a `FATAL_ERROR`.
- **vorbis SOVERSIONs are overridden** in `lib/CMakeLists.txt:217-236` (upstream sets SOVERSION to the full version string, breaking the `.so.0/.so.2/.so.3` symlinks). If you bump vorbis, re-derive these from the libtool `V_LIB_CURRENT/AGE/REVISION` triples.
- **CMake 4 policy floor is scoped to portaudio and vorbis** — CMake 4.0 removed compatibility with `cmake_minimum_required(VERSION)` < 3.5, and `lib/portaudio/CMakeLists.txt` (2.8) and `lib/vorbis-1.3.7/CMakeLists.txt` (2.8.12) declare less. The macro `daw_add_subdirectory_with_policy_floor` (`lib/CMakeLists.txt:47-65`) sets `CMAKE_POLICY_VERSION_MINIMUM=3.5` around just those two `add_subdirectory` calls (`:75`, `:216`) and clears it afterwards; a user-supplied `-DCMAKE_POLICY_VERSION_MINIMUM` is left untouched, and CMake 3.x ignores the variable. Do not edit the vendored `cmake_minimum_required` lines instead. A future vendored library whose minimum is < 3.5 must be routed through the macro deliberately — the floor does not apply to other sub-projects.
- **Windows DLLs are unversioned** — `NativeLibraryLoader.platformLibraryNames` ignores SOVERSION on Windows (`{base}.dll`, `lib{base}.dll`); `.so.N` / `.N.dylib` only matter on Linux/macOS.
- **CLAP has no shared library** — it is an interface/header-only library; headers live at `lib/Clap-1.2.7/include/clap/`. There is intentionally no `clap.dll`.

## 3. The Java FFM layer — the rules

Most native access flows through one loader — this is a **strong
convention, not an enforced invariant.** Know which side of it a class
is on before you copy its pattern:

- **Routes through `NativeLibraryLoader` — copy these for new code:** FluidSynth (`FluidSynthBindings`), AAC (`AacExporter`, `loadLibrary(arena, "fdk-aac", 2)` at `AacExporter.java:347`), OGG/Vorbis import + export.
- **Bypasses the loader — *not* the template:** `PortAudioBindings.java:115` calls `SymbolLookup.libraryLookup(resolveLibraryName(), …)` directly with its own name resolution. It predates the shared loader and so loses the bundled-first search + platform name table + install-hint diagnostics. Migrating it onto `NativeLibraryLoader` is latent debt; do not propagate the pattern.
- **Different category entirely:** `ClapBindings` / `ClapPluginHost` load **nothing from `lib/`** — they FFM-bind *external third-party `.clap` plugin binaries* at runtime via each plugin's global `clap_entry` symbol. Vendored `Clap-1.2.7/` is headers only (the host/plugin ABI), never a shared library.

The loader contract itself:

- **`core.audio.NativeLibraryLoader`** (`daw-core/.../audio/NativeLibraryLoader.java`)
  - `loadLibrary(Arena, baseName, soVersion) → SymbolLookup`. Search order: bundled `java.library.path` first, then OS system loader. Throws `UnsupportedOperationException` with a platform-specific install hint if neither resolves.
  - `platformLibraryNames(os, baseName, soVersion)` is the **single source of truth** for `{base}.dll` / `lib{base}.so.N` / `lib{base}.N.dylib` resolution — shared with the detector. Do not fork this logic.
- **`core.audio.NativeLibraryDetector`** (`.../audio/NativeLibraryDetector.java`)
  - `detectAll()` — the registry of every probed library + display name + "required for" string (powers the Help dialog "Native libraries" tab). **Adding a new library means adding a `detect(...)` line here** (`NativeLibraryDetector.java:42-67`).
  - `isAvailable(baseName[, soVersion])` — lightweight existence check; **call this before invoking any binding method.** Defaults SOVERSION to 0 (correct for `libogg.so.0`, `asioshim.dll`); pass the real SOVERSION for `vorbisenc`/`vorbisfile`.
- **Binding classes** — thin per-library wrappers: cache a `Linker.nativeLinker()`, hold the `SymbolLookup` + a long-lived `Arena`, resolve each symbol into a `MethodHandle` once, expose an `isAvailable()` / `Optional`-returning factory. When extending one, model new code on **`FluidSynthBindings`** (the loader-routed reference), not `PortAudioBindings` (the bypass exception above).

SOVERSION discipline: the number passed from the binding must equal the
library's upstream `LIB_VERSION_CURRENT` (FluidSynth documents this at
`FluidSynthBindings.java:60-61`). If you change a SOVERSION in
`lib/CMakeLists.txt`, change it in the binding, the detector, and any
`isAvailable(name, N)` caller in lockstep.

### Upcall guards

A method that an FFM upcall stub (`Linker.upcallStub`) binds must never let a
throwable leave it: the JDK's `Linker` Javadoc states that if the target method
handle throws, the JVM terminates abruptly. Every target in the tree is guarded
(story 337, rulings E1–E3, E31 and E46):

- **One catch-all in the target, and nothing before it that can throw.** The body
  sits in `try { … } catch (Throwable fault)` inside the bound method itself, not
  in a `MethodHandles.catchException` combinator, so the tests drive the guard and
  the sentinel walks it. Before the `try` there are only reads and writes of the
  class's own fields and primitive arithmetic: `PortAudioBackend.CallbackInvoker.invoke`
  bumps its device heartbeat and computes `hostFrames` and `frames` with conditional
  expressions there, and keeps the old body in a private `render`. An ABI adapter
  that only delegates (`invokeNarrowLong`, the entry the stub binds where a C
  `long` is 32 bits, as on Windows) wraps the delegation in a catch-all of its own
  that returns the continue value and counts nothing: it covers an error raised
  while entering the delegate, such as a stack overflow on a small driver stack.
- **On a fault: silence, count, continue.** PortAudio zeroes the host's extent —
  `hostFrames × outputChannels × 4` bytes when that product is representable, else
  the rendered extent, the normal path's own computation, never
  `framesPerBuffer`-based, which overruns a short host period — with one bulk
  `fill((byte) 0)` of the segment the body already reinterpreted, and returns
  `PA_CONTINUE` on every path, never `paAbort` or `paComplete`: stopping a stream
  is the watchdog's decision, never one block's. The ASIO upcall
  (`AsioBufferSwitchShim.bufferSwitchUpcall`) zeroes every bound output segment of
  the half its cycle owned. A device-notification upcall answers its ABI's safe
  value (`ASE_NOT_PRESENT`, `E_NOINTERFACE`, `S_OK`, `0`); the CLAP host callbacks
  answer `0`, `MemorySegment.NULL` or `-1`.
- **Count, never surface.** A fault bumps a `volatile long` with `++` whose only
  writer is the thread the driver calls on, next to a `volatile Throwable` that
  keeps the last fault (a reference store allocates nothing); the backend reports
  them through `AudioBackend.threadCounters()` and the engine's health record pulls
  them. A count that several native threads share (the device-notification shims)
  takes an `AtomicLong` increment on the failure path only. A CLAP callback on a
  render thread records into that thread's lane
  (`RenderThread.recordOnCurrentThread`).
- **On the fault path, never log, lock, publish or allocate** beyond the thrown
  object — PortAudio's silencing `reinterpret`, taken only when the body had not
  reinterpreted the output yet, excepted; on a driver's real-time thread
  (`CallbackInvoker.invoke`,
  `bufferSwitchUpcall`) the body is held to the same rule, PortAudio's per-period
  `reinterpret` view excepted (accepted as built). The device-notification upcalls
  run on the driver's notification thread and publish a `FormatChangeRequested`
  event from their body; only their handler is held to this rule.
  Every call a handler makes — the silencing reinterpret,
  `noteDeviceNotificationFault`, the lane record — sits inline in a nested
  `try { … } catch (Throwable ignored) { }`, never in a helper method, whose invoke
  would itself sit outside every range. A handler's read of another class's static
  field is a call too (`ClapPluginHost`'s own `NO_EVENT` constant exists for that).
- **The gate.** `RealTimeSafeContractTest.everyUpcallTargetCatchesEverything`
  checks each of the 17 methods in `UPCALL_TARGETS`: every potentially-throwing
  instruction (an invoke, an allocation, `athrow`, an array access, `checkcast`, a
  monitor, `arraylength`, an integer division, an `ldc` of a class, method-type,
  method-handle or dynamic constant, an access to another class's field) lies in a
  catch-any or `Throwable` exception-table range — a nested range counts — and the
  method is not `synchronized`; a target with no throwing instruction
  (`WasapiFormatChangeShim.refcountUpcall`'s `return 2`) needs no handler.
  `everyUpcallStubTargetIsListed` scans every `daw-*/src/main/java` file that calls
  `.upcallStub(…)` and requires the `findVirtual`/`findStatic` name literals (and
  PortAudio's `entryPoint` choice) to equal that list, so a new target fails the
  build until it is listed and guarded. `theUpcallGateRejectsATargetWithoutACatchAll`
  proves the gate is not vacuous. A target the driver enters on its real-time thread
  also belongs in `RT_CALLBACK_BRIDGES`, whose guard walk
  (`everyRtCallbackBridgeIsGuardClean`) forbids logging, locks, publishers,
  collection calls, clock reads and atomic read-modify-writes.

## 4. Recipes

### Add a new vendored C/C++ library

1. Drop the source tree under `lib/<name-version>/`.
2. Add an `option(BUILD_<LIB> ... ON)` + an `add_subdirectory(... EXCLUDE_FROM_ALL)` block in `lib/CMakeLists.txt`, forcing `LIBRARY/RUNTIME_OUTPUT_DIRECTORY` (+ the four per-config overrides) to `${NATIVE_LIBS_OUTPUT_DIR}` — copy an existing block (PortAudio §1 is the simplest template).
3. Append the target to `DAW_NATIVE_TARGETS` (`lib/CMakeLists.txt:259-280`).
4. Add a row to the table in `lib/README.md`.
5. Add a `KNOWN_KEYS`/`KNOWN_VALS` pair in `generate-third-party-notices.sh` (~line 47-52) pointing at the library's `LICENSE`/`COPYING` so `THIRD_PARTY_NOTICES.md` regenerates correctly — **license auto-discovery is mandatory, not optional.**
6. Add a `detect(os, "<display>", "<baseName>", <soVersion>, "<requiredFor>")` line to `NativeLibraryDetector.detectAll()`.
7. Write a binding class modeled on `FluidSynthBindings`; load via `NativeLibraryLoader.loadLibrary(arena, baseName, soVersion)`; guard call sites with `NativeLibraryDetector.isAvailable(...)`.

### Build natives locally / verify

```bash
cmake -S lib -B target/build -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON
cmake --build target/build --config Release --parallel
ls target/build/native            # expect the .dll/.so/.dylib set, flat
ctest --test-dir target/build     # runs vorbis_smoke_test
```

Build asioshim too (Windows, with the SDK):

```bash
cmake -S lib -B target/build -DASIO_SDK_DIR=C:/asiosdk -A x64
cmake --build target/build --config Release
# → target/build/native/asioshim.dll
```

### Diagnose `UnsupportedOperationException` / `UnsatisfiedLinkError` at runtime

1. Was the native build skipped? Check for `-DskipNativeBuild=true` and whether `target/build/native/` is populated.
2. Is `-Djava.library.path` pointed at `target/build/native`? (set in `daw-core/pom.xml` surefire `argLine`; for app runs it's the dist `native/<os>` dir).
3. SOVERSION mismatch: the file on disk is `lib<x>.so.<A>` but the binding asked for `<B>`. Reconcile against upstream `LIB_VERSION_CURRENT` and the vorbis overrides in `lib/CMakeLists.txt:217-236`.
4. FluidSynth specifically missing? GLib2 was absent at configure time — CMake skipped it with a `WARNING` (non-fatal). This is expected on a host without GLib2; the synth path degrades, the build is still green.
5. `--enable-native-access` not granted to `daw.core` → JEP 454 access warning/denial. The reactor is fully JPMS-modular; the flag must name `daw.core`.

## 5. House conventions & gotchas

- **One flat output dir.** Everything is forced into `target/build/native/` regardless of CMake multi-config subdir behavior (the four `*_OUTPUT_DIRECTORY_<CFG>` overrides per target exist for MSVC/Xcode). Don't "simplify" them away.
- **`EXCLUDE_FROM_ALL` everywhere + one aggregate `ALL` target** (`daw-native-libs`). Sub-project install/docs/pkg-config/test targets are deliberately suppressed via cache vars; re-enabling them is not a cleanup, it's a regression.
- **User's primary platform is Windows + ASIO + multi-channel USB.** Treat Windows DLL behavior as the reference path; never write a test/assertion that bakes in "absent on non-Windows" as expected truth.
- **Don't bypass the loader (in new code).** A binding that calls `SymbolLookup.libraryLookup` directly loses the bundled-first search, the platform name table, and the install-hint diagnostics — and drifts from the detector. `PortAudioBindings` already does this (`:115`); it is the one tolerated-debt exception, not precedent for adding more.
- **License notices are load-bearing.** A new `lib/` entry without a `generate-third-party-notices.sh` mapping silently produces an incomplete `THIRD_PARTY_NOTICES.md` — a distribution-compliance bug, not a cosmetic one.
- This skill exists in both trees: `.claude/skills/dawg-native-libs/SKILL.md` and its tracked mirror `.github/instructions/dawg-native-libs/SKILL.md` (story 337, ruling E50). They are not auto-synced, so edit both identically.
