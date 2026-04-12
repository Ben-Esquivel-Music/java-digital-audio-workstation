---
title: "Integrate libogg and libvorbis into the Native Build"
labels: ["enhancement", "build", "native", "export", "file-io"]
---

# Integrate libogg and libvorbis into the Native Build

## Motivation

`OggVorbisExporter` already contains a full FFM-based Ogg Vorbis encoder (user story 069), but it depends on `libvorbis`, `libvorbisenc`, and `libogg` being **installed system-wide**. On Linux it probes `libvorbis.so.0`, `libvorbisenc.so.2`, and `libogg.so.0`; on Windows and macOS these libraries are typically not present at all, which causes `SymbolLookup.libraryLookup` to throw and fall back to `UnsupportedOperationException("OGG Vorbis export requires libvorbis.so.0…")`. The project has now vendored the upstream Xiph sources under `lib/ogg-1.3.6/` and `lib/vorbis-1.3.7/` (both BSD 3-clause, GPLv3-compatible), but they are not yet wired into the native build.

The top-level `lib/CMakeLists.txt` already builds PortAudio, FluidSynth, and libmp3lame as shared libraries into a common `native/` output directory that the Java FFM layer discovers via `-Djava.library.path=<native.libs.dir>`. libogg and libvorbis should follow the exact same pattern so that every supported platform (Windows, macOS, Linux, x86_64 and ARM64) ends up with a working, bundled Ogg Vorbis encoder without relying on whatever the user happens to have installed. libvorbis also depends on libogg at build time, so the two must be configured in the correct order with libogg's include directory visible to the libvorbis sub-project.

## Goals

- Add `BUILD_LIBOGG` and `BUILD_LIBVORBIS` options to `lib/CMakeLists.txt`, both defaulting to `ON`, matching the existing `BUILD_PORTAUDIO` / `BUILD_FLUIDSYNTH` / `BUILD_LIBMP3LAME` switches
- Add libogg as a `add_subdirectory(ogg-1.3.6 EXCLUDE_FROM_ALL)` block that forces `BUILD_SHARED_LIBS=ON` and retargets the `ogg` output into `${NATIVE_LIBS_OUTPUT_DIR}` for every build configuration (Debug, Release, RelWithDebInfo, MinSizeRel), mirroring how `portaudio` and `mp3lame` are handled
- Add libvorbis as a `add_subdirectory(vorbis-1.3.7 EXCLUDE_FROM_ALL)` block that produces both `vorbis` and `vorbisenc` shared libraries, linked against the libogg target built in the previous step — set `OGG_LIBRARY`, `OGG_INCLUDE_DIR`, and any other cache variables libvorbis's CMake expects so it finds the in-tree libogg rather than a system install
- Ensure libvorbis's CMake is configured before it runs so that `examples`, `tests`, and `docs` sub-projects are skipped (libvorbis's CMake defaults enable these and they will fail or slow the build)
- Add `ogg`, `vorbis`, and `vorbisenc` to the `DAW_NATIVE_TARGETS` aggregate list so `cmake --build .` produces them as part of the default `daw-native-libs` target
- Verify that the produced artifacts land in `${NATIVE_LIBS_OUTPUT_DIR}` with the platform-correct names (`libogg.so.0` / `libvorbis.so.0` / `libvorbisenc.2.dylib` / `ogg.dll` / `vorbis.dll` / `vorbisenc.dll`) on Linux, macOS, and Windows respectively
- Add a build-time smoke test target (or CTest entry) that links a tiny C program against the freshly built libraries and calls `vorbis_version_string()` to confirm the shared libraries are loadable
- Update `lib/CMakeLists.txt` comments to document the dependency order (libogg → libvorbis → libvorbisenc) and the `EXCLUDE_FROM_ALL` / install-suppression rationale

## Non-Goals

- Building libvorbis `examples/`, `vq/`, `test/`, or `contrib/` sub-projects — only the core `vorbis` and `vorbisenc` libraries are needed
- Building `vorbisfile` (decoder) — covered separately by user story 117
- Shipping pre-built binaries or distributing a Maven classifier artifact — bundling of the compiled libraries into the distribution is covered by user story 118
- Cross-compilation from one host to another platform (CI handles per-platform builds natively)
- Building libogg or libvorbis as static libraries — the project consumes them through FFM, which requires shared libraries
