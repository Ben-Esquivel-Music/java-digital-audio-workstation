# `lib/` — Vendored Native Libraries

This directory contains the source trees of the third-party **native**
(C / C++) libraries that the DAW links against at runtime. Each library
is built into a shared library by `lib/CMakeLists.txt` and loaded from
Java via the Foreign Function & Memory API (JEP 454).

CMake 4.0 removed compatibility with `cmake_minimum_required(VERSION)`
below 3.5, and the vendored `portaudio/` (2.8) and `vorbis-1.3.7/`
(2.8.12) still declare an older minimum. `lib/CMakeLists.txt` therefore
adds those two sub-projects through `daw_add_subdirectory_with_policy_floor`,
which sets `CMAKE_POLICY_VERSION_MINIMUM` to `3.5` for their
`add_subdirectory()` call only, leaving the vendored sources unmodified.
A value you pass yourself (`-DCMAKE_POLICY_VERSION_MINIMUM=...`) is used
unchanged; CMake 3.x ignores the variable.

PortAudio is built as a shared library only, with no name suffix (story
348): the PortAudio block of `lib/CMakeLists.txt` forces `PA_BUILD_SHARED`
on and `PA_BUILD_STATIC` and `PA_LIBNAME_ADD_SUFFIX` off before it adds the
sub-project. Upstream's MSVC default appends `_x64`, and building the
static library beside the shared one forces that suffix back on, so the
static library stays off. The result is `portaudio.dll` (import library
`portaudio.lib`) with MSVC, `libportaudio.so` (no version number) on Linux
and `libportaudio.dylib` on macOS. Java loads it through
`NativeLibraryLoader.loadLibrary(arena, "portaudio", 2)`, which tries
`portaudio.dll` / `libportaudio.dll`, `libportaudio.so.2` /
`libportaudio.so` and `libportaudio.2.dylib` / `libportaudio.dylib`. The
same block forces `PA_USE_ASIO` off, so PortAudio never loads an ASIO
driver; ASIO goes through the project's own `asioshim`.

| Directory             | Library          | License      | Built by CMake |
|-----------------------|------------------|--------------|----------------|
| `Clap-1.2.7/`         | CLAP             | MIT          | header-only    |
| `fluidsynth-2.5.3/`   | FluidSynth       | LGPL-2.1     | yes            |
| `libmp3lame/`         | LAME             | LGPL-2       | yes            |
| `ogg-1.3.6/`          | libogg           | BSD-3-Clause | yes            |
| `portaudio/`          | PortAudio        | MIT          | yes            |
| `vorbis-1.3.7/`       | libvorbis        | BSD-3-Clause | yes            |

> **Every entry under `lib/` is a compiled native library.** If you are
> looking for the room-acoustics implementation, it lives in the
> [`daw-acoustics`](../daw-acoustics/) Maven module as a pure-Java port
> of [RoomAcoustiCpp](https://github.com/audiolabs/RoomAcoustiCpp). The
> vendored C++ source tree (`lib/RoomAcoustiCpp-1.0.1/`) was removed
> once the Java port was complete; see `daw-acoustics/NOTICE` for
> attribution and `THIRD_PARTY_LICENSES` for the GPLv3 derivative-work
> notice.

License metadata for each library is auto-discovered by
[`generate-third-party-notices.sh`](../generate-third-party-notices.sh)
and aggregated into `THIRD_PARTY_NOTICES.md` at the repository root.
