---
title: "Portable FFM Bindings for libvorbis and libogg"
labels: ["enhancement", "native", "export", "core", "bug"]
---

# Portable FFM Bindings for libvorbis and libogg

## Motivation

`OggVorbisExporter` uses the FFM API (JEP 454) to call libvorbis, libvorbisenc, and libogg directly from Java, but its binding layer is **hardcoded to x86_64 Linux** and will silently produce corrupt files — or crash the JVM — on any other platform. Two separate portability bugs are baked into the exporter today:

First, the struct sizes are literal constants taken from one specific compiler on one specific platform: `SIZEOF_VORBIS_INFO = 56`, `SIZEOF_VORBIS_COMMENT = 32`, `SIZEOF_VORBIS_DSP_STATE = 144`, `SIZEOF_VORBIS_BLOCK = 192`, `SIZEOF_OGG_STREAM_STATE = 408`, `SIZEOF_OGG_PAGE = 32`, `SIZEOF_OGG_PACKET = 48` (`OggVorbisExporter.java:39-45`). These values come from `sizeof()` on x86_64 GCC/glibc and are wrong on Windows (MSVC packs structs differently, `long` is 32-bit, pointers still 64-bit) and on 32-bit platforms. The `ogg_page` field offsets `OGG_PAGE_HEADER=0`, `OGG_PAGE_HEADER_LEN=8`, `OGG_PAGE_BODY=16`, `OGG_PAGE_BODY_LEN=24` are similarly x86_64-specific — `writeOggPage()` reads `header_len` and `body_len` as `JAVA_LONG` (8 bytes), which matches Linux `long` but **not** Windows `long` (4 bytes) or the `size_t`-ish fields actually declared in `<ogg/ogg.h>`.

Second, `LibVorbis.loadLibrary()` (`OggVorbisExporter.java:383-397`) only probes Linux SONAMEs: `libvorbis.so.0`, `libvorbisenc.so.2`, `libogg.so.0`. On Windows the runtime names are `vorbis.dll` / `vorbisenc.dll` / `ogg.dll`, on macOS they are `libvorbis.0.dylib` / `libvorbisenc.2.dylib` / `libogg.0.dylib`, and when bundled through user story 115 they live next to the application in the configured `java.library.path` directory rather than in a system lib path. The current error message ("Install libogg and libvorbis (e.g., 'apt install libvorbisenc2 libogg0' on Debian/Ubuntu)") is also Linux-specific and misleading on every other platform.

The correct fix is to regenerate the bindings from the actual libogg/libvorbis headers (now vendored under `lib/ogg-1.3.6/include/` and `lib/vorbis-1.3.7/include/`) using a layout source that the JVM can trust, and to make library name resolution OS-aware so it finds both bundled and system installs.

## Goals

- Replace the hardcoded `SIZEOF_*` constants in `OggVorbisExporter` with `MemoryLayout` / `StructLayout` definitions derived from the actual C struct declarations in `vorbis/codec.h` and `ogg/ogg.h`, either by running `jextract` against the vendored headers during the build or by hand-writing layouts that use `ValueLayout` primitives with the correct C ABI widths (`C_INT`, `C_LONG`, `C_POINTER`) rather than raw byte counts
- If `jextract` is used, add a build step that runs it against `lib/ogg-1.3.6/include/ogg/ogg.h` and `lib/vorbis-1.3.7/include/vorbis/codec.h` + `vorbis/vorbisenc.h` and emits Java sources under `daw-core/target/generated-sources/jextract/` that are compiled alongside the hand-written exporter; check the generated sources in or regenerate at build time, whichever matches the project's existing code-generation conventions
- Replace the `JAVA_LONG` reads in `writeOggPage()` with the platform-correct type: `ogg_page.header_len` and `body_len` are declared `long` in `<ogg/ogg.h>`, which is 32-bit on Windows and 64-bit on Linux/macOS — use the FFM `Linker.nativeLinker().canonicalLayouts().get("long")` lookup so the binding follows the C ABI on each platform
- Rewrite `LibVorbis.loadLibrary()` to resolve library names in an OS-aware way: on Linux try `libvorbis.so.0` then `libvorbis.so`, on macOS try `libvorbis.0.dylib` then `libvorbis.dylib`, on Windows try `vorbis.dll` then `libvorbis.dll` — use `System.getProperty("os.name")` to dispatch, and apply the same pattern for `vorbisenc` and `ogg`
- Prefer libraries bundled in `java.library.path` over system-installed ones by using `SymbolLookup.libraryLookup(Path, Arena)` with a resolved absolute path when the bundled artifact is found, falling back to the unqualified name-based lookup only if the bundled artifact is absent
- Replace the Linux-only error message with a platform-aware one that tells the user exactly what was missing and where it was searched ("Could not load libvorbis from bundled native directory <path> or system library path")
- Add unit tests that exercise the exporter on every CI platform (Windows, macOS, Linux) with a short deterministic buffer and verify the output file decodes correctly (round-trip through `OggVorbisFileReader` from story 117) — this is the only way to catch silent struct-layout regressions
- Add an assertion at `LibVorbis` construction time that `MemoryLayout.byteSize()` for each struct matches a known-good value per platform (derived from running `sizeof()` in a tiny C program shipped as a CTest under story 115), so layout drift is caught at JVM startup rather than mid-encode

## Non-Goals

- Changing the Ogg Vorbis wire format or the encoder quality settings — behavior must remain byte-identical to the current Linux output for a given input and quality
- Supporting 32-bit platforms — the DAW targets 64-bit JVMs only, so `x86` and `armv7` are out of scope
- Refactoring the exporter into a general-purpose Ogg container writer reusable for Opus or Theora (separate story if ever needed)
- Replacing FFM with JNI or JNA — the project has committed to FFM (JEP 454) for native interop
- Handling thread safety for concurrent exports (the exporter is already single-threaded per call and that contract is preserved)
