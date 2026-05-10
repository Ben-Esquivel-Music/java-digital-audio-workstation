---
title: "Build asioshim.dll in CI and Bundle it into the Windows Distribution"
labels: ["bug", "build", "native", "windows", "asio", "ci"]
---

# Build asioshim.dll in CI and Bundle it into the Windows Distribution

## Motivation

Story 224 — "Build and Bundle the asioshim.dll Native Library with the Distribution" — calls for the produced `asioshim.dll` to land in `daw-app/target/dist/native/windows-x64/` and `target/build/native/` so the runtime `SymbolLookup.libraryLookup("asioshim", arena)` succeeds for end users on Windows. The native source tree exists and the CMake target is in place:

- `daw-core/native/asio/asioshim.cpp`, `asioshim.h`, `CMakeLists.txt`, `README.md`.
- `lib/CMakeLists.txt` adds the `asioshim` subdirectory under `BUILD_ASIOSHIM=ON` (default ON).
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/NativeLibraryDetector.java` already lists `asioshim` in the detection set.

But:

```
daw-app/target/dist/native/windows-x64/  →  libmp3lame.dll, ogg.dll, portaudio_x64.dll, vorbis.dll, vorbisenc.dll, vorbisfile.dll  (no asioshim.dll)
target/build/native/                      →  libmp3lame.dll, ogg.dll, portaudio_x64.dll, vorbis.dll, vorbisenc.dll, vorbisfile.dll  (no asioshim.dll)
```

The asioshim CMake target conditionally returns when `ASIO_SDK_DIR` is unset (per Steinberg licence), so on a fresh checkout — including the project's CI runs — `asioshim.dll` is never produced. Story 224's explicit requirement is that the default Windows CI build sets `ASIO_SDK_DIR` so PRs detect breakage early; that step is missing. End users running the packaged Windows build therefore never gain the runtime ASIO control-panel launcher (story 212), driver-reported buffer-size / sample-rate enumeration (story 213), driver-reported channel names (story 215), hardware clock-source selection (story 216), or driver-initiated reset handling (story 218) — every one of those stories falls back to its no-shim degradation path.

## Goals

- Add a Windows CI job that:
  - Provisions a Steinberg ASIO SDK (extract from the project's licensed copy stored in CI secrets, or download via the existing build-secret mechanism).
  - Sets `ASIO_SDK_DIR` to the extracted path before invoking Maven.
  - Runs `mvn -B -DskipTests verify` so the CMake build produces `asioshim.dll` and the Maven assembly copies it into `daw-app/target/dist/native/windows-x64/` (the existing wildcard copy in `daw-app/pom.xml` already handles the copy step automatically — only the build needs to fire).
  - Fails the job if `daw-app/target/dist/native/windows-x64/asioshim.dll` is absent after the assembly phase.
- The job runs on every PR that touches `daw-core/native/asio/`, `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/Asio*.java`, or `lib/CMakeLists.txt`. Other PRs may skip the build via path filters to keep CI fast.
- Tighten the existing `AsioFormatChangeShimTest` (and any other ASIO test that gracefully no-ops on absence) on the Windows CI lane so absence is a CI failure rather than a skip — `Assumptions.assumeTrue(NativeLibraryDetector.isAvailable("asioshim"))` becomes `assertTrue(...)` when the run is the dedicated Windows-with-shim job, while remaining a `assumeTrue` skip on Linux / macOS / Windows-without-SDK developer workstations.
- Document the developer-workstation path in `daw-core/native/asio/README.md` (or a new `docs/native-build-setup.md`): how to download the Steinberg ASIO SDK, where to extract it, how to set `ASIO_SDK_DIR`, and what the expected build output is.
- Add `asioshim` to the `THIRD_PARTY_NOTICES.md` entry list per the existing convention (the Steinberg licence requires attribution but not redistribution of source).
- Surface the bundled-shim status in the in-app `HelpDialog` "Native libraries" tab so users have a one-glance way to confirm asioshim is present in their installed copy. The detection already exists in `NativeLibraryDetector` — this story only ensures it reaches the help dialog.
- Tests:
  - `NativeLibraryDetectorTest` adds a Windows-CI-gated assertion that `asioshim` resolves on the bundled distribution.
  - The release-packaging integration test (an existing post-`verify` smoke test, or a new one if absent) confirms `daw-app/target/dist/native/windows-x64/asioshim.dll` exists and is loadable.

## Non-Goals

- Distributing the Steinberg ASIO SDK source — it remains a build-time dependency under the user's licence.
- Code-signing the DLL (a separate release-packaging concern).
- Producing macOS / Linux ASIO shims (Steinberg ASIO is Windows-only by definition).
- Supporting ARM64 Windows in this iteration — x64 only.
- Re-architecting the existing `BUILD_ASIOSHIM` opt-in for non-Steinberg ASIO dialects (legacy ASIO 1.0 outside Steinberg's host glue).

## Technical Notes

- Files: a new GitHub Actions workflow file under `.github/workflows/` (or whichever CI system the repo currently uses), `daw-core/native/asio/README.md` (developer-workstation docs), `THIRD_PARTY_NOTICES.md` (attribution entry), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/HelpDialog.java` (native-libraries tab refresh).
- The existing wildcard copy at `daw-app/pom.xml` line ~152 (`<fileset dir="${native.libs.dir}" includes="*.so,*.so.*,*.dylib,*.dll"/>`) already includes `asioshim.dll` automatically once the CMake build produces it; no per-file pom edits are required.
- Reference original story: **224 — Build and Bundle asioshim Native Library**.
