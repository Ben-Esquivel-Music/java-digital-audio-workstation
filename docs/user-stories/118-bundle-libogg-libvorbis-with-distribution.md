---
title: "Bundle libogg/libvorbis Native Libraries and BSD Attribution with the Distribution"
labels: ["enhancement", "build", "packaging", "native", "licensing"]
---

# Bundle libogg/libvorbis Native Libraries and BSD Attribution with the Distribution

## Motivation

User story 115 makes the CMake build produce `libogg`, `libvorbis`, and `libvorbisenc` shared libraries in a common `native/` output directory, and user story 116 makes the Java FFM layer find those libraries in a platform-portable way. What remains is the last-mile problem: **getting those compiled artifacts into the distribution that users actually run**. Today, even after a full `cmake --build` run, the Maven/Gradle packaging step does not know to pick up the freshly built shared libraries and include them in the application artifact. A user who downloads a release from the project's distribution channel still needs libvorbis installed system-wide for OGG export to work — exactly the failure mode the vendoring effort was meant to eliminate.

The second half of this story is a legal one. libogg and libvorbis are distributed under the BSD 3-clause license, whose binary-redistribution clause explicitly requires:

> Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

The verified `COPYING` files at `lib/ogg-1.3.6/COPYING` and `lib/vorbis-1.3.7/COPYING` contain the exact attribution text that must accompany any binary distribution. Simply shipping the `.so` / `.dll` / `.dylib` without this text is a license violation, even though the license is otherwise maximally permissive. Other vendored libs with attribution requirements (PortAudio, CLAP) have the same obligation, so this story is a good forcing function to establish a repeatable third-party-notices mechanism and apply it consistently.

Finally, with libraries now shipped alongside the application, the `NativeLibraryDetector` introduced in user story 097 needs to be updated: instead of only probing for system installs and reporting "Missing" when they're absent, it should first look in the bundled `native/` directory next to the application and only fall back to system lookup if the bundled copy is missing (which, after this story, should never happen in a properly packaged release).

## Goals

- Add a Maven/Gradle packaging step that, after the CMake `daw-native-libs` target has run, copies the contents of `${NATIVE_LIBS_OUTPUT_DIR}` into the application distribution under a known per-OS/per-architecture path (e.g. `dist/native/windows-x64/`, `dist/native/macos-arm64/`, `dist/native/linux-x64/`), and adds that directory to the `java.library.path` used by the application launcher script / installer
- Ensure the packaging step picks up libogg, libvorbis, libvorbisenc, and libvorbisfile in addition to the already-bundled PortAudio, FluidSynth, and libmp3lame artifacts — the set of libraries to copy should be derived from `DAW_NATIVE_TARGETS` in `lib/CMakeLists.txt`, not hardcoded in the build config, so future additions are picked up automatically
- Add a `THIRD_PARTY_NOTICES.md` (or equivalent per project convention) at the distribution root that embeds, verbatim, the full text of:
  - `lib/ogg-1.3.6/COPYING` under a "libogg" heading with its upstream URL and version
  - `lib/vorbis-1.3.7/COPYING` under a "libvorbis" heading with its upstream URL and version
  - Existing vendored libs with attribution requirements (PortAudio, CLAP/MIT, FluidSynth/LGPL, libmp3lame/LGPL, RoomAcoustiCpp/GPLv3) so the notices file is complete, not just Vorbis-specific
- Generate `THIRD_PARTY_NOTICES.md` via a repeatable build step (script or Maven plugin) that reads each `lib/*/COPYING` or `LICENSE` file at build time, so editing a vendored library's license on upgrade automatically flows into the notices file without a manual sync step
- Update `NativeLibraryDetector` (from story 097) to search the bundled `native/` directory *before* falling back to system library paths, and to report the **resolved absolute path** in `NativeLibraryStatus.detectedPath()` so the System Capabilities panel can show users where each library was actually loaded from
- Update the "required for" description strings in `NativeLibraryDetector` so libogg and libvorbis are listed as "OGG Vorbis import and export" (previously libvorbis was export-only)
- Verify that a clean build on a machine with **no** system-installed libvorbis or libogg can still perform OGG Vorbis export and import successfully — this is the acceptance test for the whole vendoring effort and should be added to CI as a platform-specific smoke test per OS
- Verify the `THIRD_PARTY_NOTICES.md` file is actually present in the final distribution artifact (JAR, zip, installer, whatever the project ships) via a packaging-level test
- Document in `README.md` or the relevant contributor-facing doc that adding a new vendored C library requires (1) wiring it into `lib/CMakeLists.txt`, (2) adding it to `DAW_NATIVE_TARGETS`, and (3) its `LICENSE`/`COPYING` file will be auto-included in `THIRD_PARTY_NOTICES.md` — so the next person doing this work doesn't have to rediscover the pattern

## Non-Goals

- Signing or notarizing the bundled native libraries — that is a release-pipeline concern separate from packaging mechanics
- Stripping debug symbols from shipped binaries — a size optimization, not a correctness concern
- Building a platform-specific installer (MSI, PKG, DEB) — the story covers the distribution artifact as it exists today, whatever form that takes
- Hot-reloading native libraries at runtime — libraries are loaded once at FFM binding construction
- Providing alternate download channels for users who want to fetch native libraries separately — bundling is the whole point
- Back-porting the bundled-library approach to historical releases
